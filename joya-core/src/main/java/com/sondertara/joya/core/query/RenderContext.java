package com.sondertara.joya.core.query;

import com.sondertara.joya.core.metadata.LocalTableMetaCache;
import com.sondertara.joya.core.naming.NamingPolicy;
import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlNode;
import com.sondertara.joya.core.kernel.statement.SqlNodes;
import com.sondertara.joya.core.kernel.statement.SqlParameter;
import com.sondertara.joya.core.kernel.statement.SqlParameterList;
import com.sondertara.joya.core.kernel.statement.SqlRenderer;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.statement.StatementMetadata;
import com.sondertara.joya.core.kernel.statement.TableRef;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.model.ColumnAlias;
import com.sondertara.joya.exceptions.DbException;
import com.sondertara.joya.utils.Strings;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

/**
 * 运行期统一 SQL 渲染上下文。
 *
 * <p>仅在生成 {@code SqlStatement} 时创建一次，承担两大职责：
 * <ol>
 *   <li><b>表注册与别名绑定</b>：多表查询模式（{@link #forQuery}）按 {@code FROM + JOIN} 确定性顺序分配 {@code t0, t1, t2}；
 *       单表模式（{@link #forSingleTable}）不分配表别名，直接输出裸列名，彻底消除事后正则剥离 {@code t0.} 的脆弱逻辑。</li>
 *   <li><b>表归属安全校验</b>：任何通过 {@link ColumnMetaProvider} 引用的实体必须已在当前上下文注册，
 *       否则立即抛出 {@link IllegalStateException}，从根源上杜绝未声明 JOIN 导致的隐式笛卡尔积。</li>
 * </ol>
 *
 * @author huangxiaohu
 */
public final class RenderContext {

    private static final String ALIAS_PREFIX = "t";

    private final Class<?> rootType;
    private final boolean aliased;
    private final List<JoinPart> joins;
    private final NamingPolicy namingPolicy;
    private final Map<Class<?>, TableBinding> tableBindings = new LinkedHashMap<>();
    private final List<TableBinding> allBindings = new ArrayList<>();
    private final Map<JoinPart, SelfJoinTarget> selfJoinBindings = new java.util.IdentityHashMap<>();
    private @Nullable TableBinding activeJoinTargetBinding;

    private record TableBinding(
            Class<?> entityClass,
            EntityMetadataContext meta,
            @Nullable String alias,
            String physicalName
    ) {
    }

    private record SelfJoinTarget(TableBinding binding, boolean onLeft) {
    }

    private RenderContext(Class<?> rootType, boolean aliased, List<JoinPart> joins, NamingPolicy namingPolicy) {
        this.rootType = Objects.requireNonNull(rootType, "rootType must not be null");
        this.aliased = aliased;
        this.joins = joins == null ? List.of() : List.copyOf(joins);
        this.namingPolicy = Objects.requireNonNullElse(namingPolicy, NamingPolicy.DEFAULT);
    }

    /**
     * 构造多表查询渲染上下文（带表别名 {@code t0, t1...}，严格按 {@code rootType + joins} 顺序绑定）。
     */
    public static RenderContext forQuery(Class<?> rootType, List<JoinPart> joins) {
        return forQuery(rootType, joins, NamingPolicy.DEFAULT);
    }

    /**
     * 构造多表查询渲染上下文（支持传入实例级 {@link NamingPolicy} 控制最大联表数与大小写策略）。
     */
    public static RenderContext forQuery(Class<?> rootType, List<JoinPart> joins, NamingPolicy namingPolicy) {
        RenderContext ctx = new RenderContext(rootType, true, joins, namingPolicy);
        ctx.registerQueryTable(rootType, null);
        if (joins != null) {
            for (JoinPart join : joins) {
                Class<?> leftType = join.getLeftColumn().entityClass();
                Class<?> rightType = join.getRightColumn().entityClass();
                String explicitAlias = join.getAlias();
                boolean leftRegistered = leftType != null
                        && ctx.isTableRegistered(leftType, join.getLeftColumn().tableAlias());
                boolean rightRegistered = rightType != null
                        && ctx.isTableRegistered(rightType, join.getRightColumn().tableAlias());
                if (leftType != null && rightType != null && !leftRegistered && !rightRegistered) {
                    throw new IllegalStateException("Disconnected JOIN between ["
                            + leftType.getSimpleName() + "] and [" + rightType.getSimpleName()
                            + "]: neither table is connected to previously registered tables "
                            + ctx.tableBindings.keySet().stream().map(Class::getSimpleName).toList());
                }
                if (leftType != null && !leftRegistered) {
                    ctx.registerQueryTable(leftType, explicitAlias);
                }
                if (rightType != null) {
                    if (Objects.equals(leftType, rightType) || (leftRegistered && rightRegistered)) {
                        String leftExplicitAlias = join.getLeftColumn().tableAlias();
                        String rightExplicitAlias = join.getRightColumn().tableAlias();
                        boolean onLeft;
                        if (leftExplicitAlias == null && rightExplicitAlias != null) {
                            onLeft = true;
                        } else if (leftExplicitAlias != null && rightExplicitAlias == null) {
                            onLeft = false;
                        } else {
                            onLeft = !Objects.equals(leftType, rightType) && Objects.equals(rightType, rootType);
                        }
                        Class<?> targetType = onLeft ? leftType : rightType;
                        ctx.registerSelfJoinTable(join, targetType, explicitAlias, onLeft);
                    } else if (!rightRegistered) {
                        ctx.registerQueryTable(rightType, explicitAlias);
                    }
                }
            }
        }
        int maxJoinedTables = ctx.namingPolicy.maxJoinTable();
        if (ctx.allBindings.size() > maxJoinedTables) {
            throw new DbException("The maximum number of tables that can be joined is " + maxJoinedTables);
        }
        return ctx;
    }

    /**
     * 构造单表语句渲染上下文（无表别名，直接输出列名，适用于单表 UPDATE / DELETE / SELECT）。
     */
    public static RenderContext forSingleTable(EntityMetadataContext meta) {
        return forSingleTable(meta, NamingPolicy.DEFAULT);
    }

    /**
     * 构造单表语句渲染上下文（支持传入实例级 {@link NamingPolicy}）。
     */
    public static RenderContext forSingleTable(EntityMetadataContext meta, NamingPolicy namingPolicy) {
        Objects.requireNonNull(meta, "meta must not be null");
        Class<?> entityType = meta.entityType() != null ? meta.entityType() : Object.class;
        RenderContext ctx = new RenderContext(entityType, false, List.of(), namingPolicy);
        TableBinding single = new TableBinding(entityType, meta, null, meta.qualifiedTableName(ctx.namingPolicy));
        ctx.tableBindings.put(entityType, single);
        ctx.allBindings.add(single);
        return ctx;
    }

    /**
     * 构造带主表别名 {@code t0} 的单表查询渲染上下文（支持传入自定义 {@link EntityMetadataContext}）。
     */
    public static RenderContext forAliasedSingleTable(EntityMetadataContext meta, NamingPolicy namingPolicy) {
        Objects.requireNonNull(meta, "meta must not be null");
        Class<?> entityType = meta.entityType() != null ? meta.entityType() : Object.class;
        RenderContext ctx = new RenderContext(entityType, true, List.of(), namingPolicy);
        TableBinding single = new TableBinding(entityType, meta, "t0", meta.qualifiedTableName(ctx.namingPolicy));
        ctx.tableBindings.put(entityType, single);
        ctx.allBindings.add(single);
        return ctx;
    }

    /**
     * 构造单表语句渲染上下文（按实体类加载元数据，无表别名）。
     */
    public static RenderContext forSingleTable(Class<?> entityClass) {
        Objects.requireNonNull(entityClass, "entityClass must not be null");
        EntityMetadataContext meta = LocalTableMetaCache.getMetadataContext(entityClass);
        return forSingleTable(meta, NamingPolicy.DEFAULT);
    }

    public NamingPolicy namingPolicy() {
        return namingPolicy;
    }

    private boolean isTableRegistered(Class<?> entityClass, @Nullable String tableAlias) {
        if (tableAlias != null) {
            for (TableBinding b : allBindings) {
                if (b.alias() != null && b.alias().equalsIgnoreCase(tableAlias) && b.entityClass().equals(entityClass)) {
                    return true;
                }
            }
            return false;
        }
        return tableBindings.containsKey(entityClass);
    }

    private String allocateAlias(@Nullable String explicitAlias) {
        if (explicitAlias != null) {
            for (TableBinding b : allBindings) {
                if (b.alias() != null && b.alias().equalsIgnoreCase(explicitAlias)) {
                    throw new IllegalArgumentException("Duplicate table alias [" + explicitAlias
                            + "] in query rooted at [" + rootType.getSimpleName() + "]");
                }
            }
            return explicitAlias;
        }
        int idx = allBindings.size();
        while (true) {
            String candidate = ALIAS_PREFIX + idx;
            boolean taken = false;
            for (TableBinding b : allBindings) {
                if (b.alias() != null && b.alias().equalsIgnoreCase(candidate)) {
                    taken = true;
                    break;
                }
            }
            if (!taken) {
                return candidate;
            }
            idx++;
        }
    }

    private void registerQueryTable(Class<?> entityClass, @Nullable String explicitAlias) {
        if (tableBindings.containsKey(entityClass)) {
            return;
        }
        EntityMetadataContext meta = LocalTableMetaCache.getMetadataContext(entityClass);
        String alias = allocateAlias(explicitAlias);
        TableBinding binding = new TableBinding(entityClass, meta, alias, meta.qualifiedTableName(namingPolicy));
        tableBindings.put(entityClass, binding);
        allBindings.add(binding);
    }

    private void registerSelfJoinTable(JoinPart join, Class<?> entityClass, @Nullable String explicitAlias, boolean onLeft) {
        EntityMetadataContext meta = LocalTableMetaCache.getMetadataContext(entityClass);
        String alias = allocateAlias(explicitAlias);
        TableBinding binding = new TableBinding(entityClass, meta, alias, meta.qualifiedTableName(namingPolicy));
        selfJoinBindings.put(join, new SelfJoinTarget(binding, onLeft));
        allBindings.add(binding);
    }

    /**
     * 当前上下文是否为带表别名模式。
     */
    public boolean isAliased() {
        return aliased;
    }

    /**
     * 主表实体类型。
     */
    public Class<?> rootType() {
        return rootType;
    }

    /**
     * 当前查询是否包含联表（{@code JOIN}）。
     */
    public boolean hasJoins() {
        return !joins.isEmpty();
    }

    /**
     * 已注册的参与实体集合（按 FROM + JOIN 注册顺序）。
     */
    public Collection<Class<?>> registeredEntities() {
        return Collections.unmodifiableSet(tableBindings.keySet());
    }

    /**
     * 解析实体方法引用对应的列（强校验实体已在 FROM / JOIN 中注册）。
     */
    public ColumnAlias resolveGetterColumn(Class<?> entityClass, String propertyName) {
        TableBinding binding = (activeJoinTargetBinding != null && activeJoinTargetBinding.entityClass().equals(entityClass))
                ? activeJoinTargetBinding
                : tableBindings.get(entityClass);
        if (binding == null) {
            throw unjoinedEntityError(entityClass, propertyName);
        }
        String columnName = binding.meta().columnByField(propertyName);
        if (columnName == null) {
            throw new DbException(Strings.format(
                    "Can not find column for property [{}] on entity [{}]", propertyName, entityClass.getName()));
        }
        return buildColumnAlias(binding, columnName, propertyName);
    }

    /**
     * 解析显式指定表别名的实体方法引用列（用于自连接或同一目标实体多次 JOIN 场景，如 {@code Exprs.col("t1", Emp::getName)}）。
     */
    public ColumnAlias resolveAliasedGetterColumn(String tableAlias, Class<?> entityClass, String propertyName) {
        if (Strings.isBlank(tableAlias)) {
            return resolveGetterColumn(entityClass, propertyName);
        }
        TableBinding matched = null;
        for (TableBinding b : allBindings) {
            if (b.alias() != null && b.alias().equalsIgnoreCase(tableAlias.trim())) {
                matched = b;
                break;
            }
        }
        if (matched == null) {
            List<String> availableAliases = allBindings.stream()
                    .map(TableBinding::alias)
                    .filter(Objects::nonNull)
                    .toList();
            throw new IllegalStateException("Table alias [" + tableAlias + "] is not registered in query rooted at ["
                    + rootType.getSimpleName() + "]. Available aliases: " + availableAliases);
        }
        if (!matched.entityClass().equals(entityClass)) {
            throw new IllegalStateException("Table alias [" + tableAlias + "] is bound to entity ["
                    + matched.entityClass().getSimpleName() + "], not [" + entityClass.getSimpleName() + "]");
        }
        String columnName = matched.meta().columnByField(propertyName);
        if (columnName == null) {
            throw new DbException(Strings.format(
                    "Can not find column for property [{}] on entity [{}]", propertyName, entityClass.getName()));
        }
        return buildColumnAlias(matched, columnName, propertyName);
    }

    private IllegalStateException unjoinedEntityError(Class<?> entityClass, @Nullable String propertyName) {
        String unjoinedTable = entityClass.getSimpleName();
        try {
            EntityMetadataContext unjoinedMeta = LocalTableMetaCache.getMetadataContext(entityClass);
            if (unjoinedMeta != null && unjoinedMeta.qualifiedTableName() != null) {
                unjoinedTable = unjoinedMeta.qualifiedTableName(namingPolicy);
            }
        } catch (Exception ignored) {
        }
        List<String> availableTables = new ArrayList<>(tableBindings.size());
        for (TableBinding b : tableBindings.values()) {
            availableTables.add(b.entityClass().getSimpleName() + "(" + b.physicalName() + ")");
        }
        String refDesc = propertyName != null
                ? entityClass.getSimpleName() + "::" + propertyName
                : entityClass.getSimpleName();
        return new IllegalStateException("Entity [" + entityClass.getSimpleName()
                + "] (referenced via '" + refDesc + "', table '" + unjoinedTable
                + "') is not registered in FROM / JOIN of query rooted at [" + rootType.getSimpleName()
                + "]. Available tables: " + availableTables);
    }

    /**
     * 解析外部传入的字段名或列名标识符（白名单校验，主表优先）。
     */
    public ColumnAlias resolveIdentifierColumn(String fieldOrColumn) {
        if (Strings.isBlank(fieldOrColumn) || !ColumnMetaProvider.SAFE_IDENTIFIER.matcher(fieldOrColumn.trim()).matches()) {
            throw new IllegalArgumentException(
                    "Invalid column or field identifier (potential SQL injection rejected): " + fieldOrColumn);
        }
        String trimmed = fieldOrColumn.trim();
        int dot = trimmed.indexOf('.');
        if (dot > 0) {
            String prefix = trimmed.substring(0, dot);
            String name = trimmed.substring(dot + 1);
            TableBinding target = findBindingByPrefix(prefix);
            if (target == null) {
                throw new IllegalArgumentException("Unknown table or alias in identifier: " + trimmed);
            }
            ColumnAlias matched = matchColumn(target, name);
            if (matched == null) {
                throw new IllegalArgumentException(
                        "Unknown column or field '" + name + "' on " + target.entityClass().getSimpleName());
            }
            return matched;
        }
        for (TableBinding binding : tableBindings.values()) {
            ColumnAlias matched = matchColumn(binding, trimmed);
            if (matched != null) {
                return matched;
            }
        }
        throw new IllegalArgumentException(
                "Unknown column or field '" + trimmed + "' for entity " + rootType.getSimpleName());
    }

    /**
     * 解析外部传入的字段名或列名为 SQL 引用字符串（如 {@code t0.user_name} 或单表 {@code user_name}）。
     */
    public String resolveIdentifier(String fieldOrColumn) {
        return resolveIdentifierColumn(fieldOrColumn).identity();
    }

    /**
     * 获取指定已注册实体的全部列元数据。
     */
    public List<ColumnAlias> getAllColumns(Class<?> entityClass) {
        TableBinding binding = tableBindings.get(entityClass);
        if (binding == null) {
            throw unjoinedEntityError(entityClass, null);
        }
        List<String> colNames = binding.meta().columnNames();
        List<ColumnAlias> result = new ArrayList<>(colNames.size());
        for (String col : colNames) {
            result.add(buildColumnAlias(binding, col, binding.meta().fieldByColumn(col)));
        }
        return result;
    }

    /**
     * 按 DTO 属性名或其 {@code @Column} 原始声明匹配单张已注册表的列。
     * 匹配始终使用未格式化的元数据列名，避免引号或大小写格式化干扰注解匹配。
     */
    public @Nullable ColumnAlias findColumn(Class<?> entityClass, String propertyName,
                                            @Nullable String declaredColumnName) {
        TableBinding binding = tableBindings.get(entityClass);
        if (binding == null) {
            throw unjoinedEntityError(entityClass, propertyName);
        }
        return findColumnInBinding(binding, propertyName, declaredColumnName);
    }

    private @Nullable ColumnAlias findColumnInBinding(TableBinding binding, String propertyName,
                                                      @Nullable String declaredColumnName) {
        String propertyColumn = binding.meta().columnByField(propertyName);
        if (propertyColumn != null) {
            return buildColumnAlias(binding, propertyColumn, propertyName);
        }
        if (declaredColumnName != null) {
            for (String columnName : binding.meta().columnNames()) {
                if (declaredColumnName.equalsIgnoreCase(columnName)) {
                    return buildColumnAlias(binding, columnName, binding.meta().fieldByColumn(columnName));
                }
            }
        }
        return null;
    }

    /**
     * 渲染 FROM 与 JOIN 子句（不含前导 {@code FROM } 关键字）。
     */
    public String renderFromClause() {
        return renderFromClause(new SqlParameterList());
    }

    /**
     * 渲染 FROM 与 JOIN 子句（不含前导 {@code FROM } 关键字），并将 {@code JOIN ... ON} 附加条件中的参数写入 {@code params}。
     * <p>与 {@link #toFromNode()} 共用同一 AST 构造，避免两套 FROM 拼装逻辑漂移。
     */
    public String renderFromClause(SqlParameterList params) {
        SqlStatement statement = SqlRenderer.render(toFromNode(), StatementMetadata.empty());
        for (SqlParameter parameter : statement.parameters()) {
            params.add(parameter);
        }
        return statement.sql();
    }

    private ColumnAlias rebindJoinColumn(ColumnMetaProvider columnProvider, TableBinding selfBinding) {
        ColumnAlias base = columnProvider.resolveAlias(this);
        return new ColumnAlias(
                selfBinding.physicalName(),
                base.getColumnName(),
                selfBinding.alias(),
                base.getColumnAlias());
    }

    /**
     * 构造 FROM/JOIN 的结构化 AST 节点（连接键与 ON 附加条件均产出结构化 {@link SqlNode}，
     * 参数由 {@link SqlRenderer} 按遍历顺序统一重排）。
     */
    public SqlNode.From toFromNode() {
        TableBinding root = tableBindings.get(rootType);
        if (root == null) {
            throw new IllegalStateException("Root table is not registered: " + rootType.getName());
        }
        SqlNode.Table rootTable = tableNode(root.meta().tableName(),
                root.physicalName(), root.alias());
        if (joins.isEmpty()) {
            return new SqlNode.From(rootTable, List.of());
        }
        List<SqlNode.Join> joinNodes = new ArrayList<>(joins.size());
        Set<String> joinedAliases = new HashSet<>();
        if (root.alias() != null) {
            joinedAliases.add(root.alias().toLowerCase(Locale.ROOT));
        }
        for (JoinPart segment : joins) {
            SelfJoinTarget selfTarget = selfJoinBindings.get(segment);
            ColumnAlias first = (selfTarget != null && selfTarget.onLeft())
                    ? rebindJoinColumn(segment.getLeftColumn(), selfTarget.binding())
                    : segment.getLeftColumn().resolveAlias(this);
            ColumnAlias last = (selfTarget != null && !selfTarget.onLeft())
                    ? rebindJoinColumn(segment.getRightColumn(), selfTarget.binding())
                    : segment.getRightColumn().resolveAlias(this);
            String firstAliasKey = first.getTableAlias() != null
                    ? first.getTableAlias().toLowerCase(Locale.ROOT)
                    : first.getTableName().toLowerCase(Locale.ROOT);
            String lastAliasKey = last.getTableAlias() != null
                    ? last.getTableAlias().toLowerCase(Locale.ROOT)
                    : last.getTableName().toLowerCase(Locale.ROOT);
            ColumnAlias target;
            if (joinedAliases.contains(firstAliasKey)) {
                target = last;
            } else if (joinedAliases.contains(lastAliasKey)) {
                target = first;
            } else {
                throw new IllegalStateException("Disconnected JOIN segment between '"
                        + first.getTableName() + "' and '" + last.getTableName() + "'");
            }
            SqlNode onNode = SqlNodes.eq(
                    SqlNodes.column(first.getTableAlias(), first.getColumnName()),
                    SqlNodes.column(last.getTableAlias(), last.getColumnName()));
            OnPredicate onCriterion = segment.getOnCriterion();
            if (onCriterion != null && !onCriterion.isEmpty()) {
                SqlNode extraNode;
                TableBinding prevTarget = this.activeJoinTargetBinding;
                this.activeJoinTargetBinding = selfTarget != null ? selfTarget.binding() : null;
                try {
                    extraNode = onCriterion.toNode(this, ParameterSource.JOIN);
                } finally {
                    this.activeJoinTargetBinding = prevTarget;
                }
                if (extraNode != null) {
                    onNode = SqlNodes.and(onNode, extraNode);
                }
            }
            TableBinding targetBinding = target.getTableAlias() != null
                    ? findBindingByPrefix(target.getTableAlias())
                    : null;
            String logicalTargetName = targetBinding != null
                    ? targetBinding.meta().tableName()
                    : target.getTableName();
            joinNodes.add(new SqlNode.Join(segment.getJoinType().getCode(),
                    tableNode(logicalTargetName, target.getTableName(), target.getTableAlias()),
                    onNode));
            joinedAliases.add(firstAliasKey);
            joinedAliases.add(lastAliasKey);
        }
        return new SqlNode.From(rootTable, joinNodes);
    }

    private static SqlNode.Table tableNode(String logicalName, String physicalName, @Nullable String alias) {
        return new SqlNode.Table(logicalName, physicalName, alias == null ? "" : alias);
    }

    /**
     * 提取当前查询涉及的表引用集合（供分片/多租户等拦截器直接读取，无需正则解析 SQL）。
     */
    public Set<TableRef> extractTables() {
        Set<TableRef> result = new LinkedHashSet<>();
        for (TableBinding binding : allBindings) {
            result.add(TableRef.ofQualified(binding.physicalName(), binding.alias()));
        }
        return result;
    }

    private @Nullable TableBinding findBindingByPrefix(String prefix) {
        if (!aliased && allBindings.size() == 1) {
            TableBinding single = allBindings.get(0);
            if ("t0".equalsIgnoreCase(prefix)
                    || prefix.equalsIgnoreCase(single.entityClass().getSimpleName())
                    || prefix.equalsIgnoreCase(single.meta().tableName())
                    || prefix.equalsIgnoreCase(single.meta().qualifiedTableName())) {
                return single;
            }
        }
        for (TableBinding binding : allBindings) {
            if ((binding.alias() != null && prefix.equalsIgnoreCase(binding.alias()))
                    || prefix.equalsIgnoreCase(binding.entityClass().getSimpleName())
                    || prefix.equalsIgnoreCase(binding.meta().tableName())
                    || prefix.equalsIgnoreCase(binding.meta().qualifiedTableName())) {
                return binding;
            }
        }
        return null;
    }

    private @Nullable ColumnAlias matchColumn(TableBinding binding, String fieldOrColumn) {
        EntityMetadataContext meta = binding.meta();
        if (meta.containsField(fieldOrColumn)) {
            String col = meta.columnByField(fieldOrColumn);
            return buildColumnAlias(binding, col, fieldOrColumn);
        }
        for (String col : meta.columnNames()) {
            String field = meta.fieldByColumn(col);
            if (fieldOrColumn.equalsIgnoreCase(col) || (field != null && fieldOrColumn.equals(field))) {
                return buildColumnAlias(binding, col, field);
            }
        }
        return null;
    }

    private ColumnAlias buildColumnAlias(TableBinding binding, String columnName, @Nullable String fieldName) {
        String formattedCol = binding.meta().formatColumn(columnName, namingPolicy);
        return new ColumnAlias(
                binding.physicalName(),
                formattedCol,
                binding.alias(),
                fieldName);
    }
}
