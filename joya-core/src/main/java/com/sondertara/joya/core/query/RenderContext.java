package com.sondertara.joya.core.query;

import com.sondertara.joya.core.cache.CacheHolder;
import com.sondertara.joya.core.naming.NamingPolicy;
import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlParameterList;
import com.sondertara.joya.core.kernel.statement.TableRef;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.model.ColumnAlias;
import com.sondertara.joya.core.query.JoinPart;
import com.sondertara.joya.core.query.criterion.WhereCriterion;
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

    private record TableBinding(Class<?> entityClass, EntityMetadataContext meta, @Nullable String alias) {
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
        ctx.registerQueryTable(rootType);
        if (joins != null) {
            for (JoinPart join : joins) {
                Class<?> leftType = join.getLeftColumn().entityClass();
                Class<?> rightType = join.getRightColumn().entityClass();
                if (leftType != null) {
                    ctx.registerQueryTable(leftType);
                }
                if (rightType != null) {
                    ctx.registerQueryTable(rightType);
                }
            }
        }
        int maxJoinedTables = ctx.namingPolicy.maxJoinTable();
        if (ctx.tableBindings.size() > maxJoinedTables) {
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
        ctx.tableBindings.put(entityType, new TableBinding(entityType, meta, null));
        return ctx;
    }

    /**
     * 构造单表语句渲染上下文（按实体类加载元数据，无表别名）。
     */
    public static RenderContext forSingleTable(Class<?> entityClass) {
        Objects.requireNonNull(entityClass, "entityClass must not be null");
        EntityMetadataContext meta = CacheHolder.tableMetaCache().getMetadataContext(entityClass);
        return forSingleTable(meta, NamingPolicy.DEFAULT);
    }

    public NamingPolicy namingPolicy() {
        return namingPolicy;
    }

    private void registerQueryTable(Class<?> entityClass) {
        if (tableBindings.containsKey(entityClass)) {
            return;
        }
        EntityMetadataContext meta = CacheHolder.tableMetaCache().getMetadataContext(entityClass);
        // 若不同实体类映射到同一物理表，复用同一别名
        for (TableBinding existing : tableBindings.values()) {
            if (existing.meta().qualifiedTableName().equalsIgnoreCase(meta.qualifiedTableName())) {
                tableBindings.put(entityClass, new TableBinding(entityClass, meta, existing.alias()));
                return;
            }
        }
        String alias = ALIAS_PREFIX + tableBindings.size();
        tableBindings.put(entityClass, new TableBinding(entityClass, meta, alias));
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
     * 已注册的参与实体集合（按 FROM + JOIN 注册顺序）。
     */
    public Collection<Class<?>> registeredEntities() {
        return Collections.unmodifiableSet(tableBindings.keySet());
    }

    /**
     * 解析实体方法引用对应的列（强校验实体已在 FROM / JOIN 中注册）。
     */
    public ColumnAlias resolveGetterColumn(Class<?> entityClass, String propertyName) {
        TableBinding binding = tableBindings.get(entityClass);
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

    private IllegalStateException unjoinedEntityError(Class<?> entityClass, @Nullable String propertyName) {
        String unjoinedTable = entityClass.getSimpleName();
        try {
            EntityMetadataContext unjoinedMeta = CacheHolder.tableMetaCache().getMetadataContext(entityClass);
            if (unjoinedMeta != null && unjoinedMeta.qualifiedTableName() != null) {
                unjoinedTable = unjoinedMeta.qualifiedTableName(namingPolicy);
            }
        } catch (Exception ignored) {
        }
        List<String> availableTables = new ArrayList<>(tableBindings.size());
        for (TableBinding b : tableBindings.values()) {
            availableTables.add(b.entityClass().getSimpleName() + "(" + b.meta().qualifiedTableName(namingPolicy) + ")");
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
     * 渲染 FROM 与 JOIN 子句（不含前导 {@code FROM } 关键字）。
     */
    public String renderFromClause() {
        return renderFromClause(new SqlParameterList());
    }

    /**
     * 渲染 FROM 与 JOIN 子句（不含前导 {@code FROM } 关键字），并将 {@code JOIN ... ON} 附加条件中的参数写入 {@code params}。
     */
    public String renderFromClause(SqlParameterList params) {
        TableBinding root = tableBindings.get(rootType);
        String rootTableName = root.meta().qualifiedTableName(namingPolicy);
        if (joins.isEmpty()) {
            return root.alias() == null
                    ? rootTableName
                    : rootTableName + " " + root.alias();
        }
        StringJoiner sj = new StringJoiner(" ");
        Set<String> joinedTables = new HashSet<>();
        sj.add(rootTableName + " " + root.alias());
        joinedTables.add(rootTableName.toLowerCase(Locale.ROOT));

        StringBuilder sb = new StringBuilder();
        for (JoinPart segment : joins) {
            ColumnAlias first = segment.getLeftColumn().resolveAlias(this);
            ColumnAlias last = segment.getRightColumn().resolveAlias(this);
            String code = segment.getJoinType().getCode();
            String firstTableKey = first.getTableName().toLowerCase(Locale.ROOT);
            String lastTableKey = last.getTableName().toLowerCase(Locale.ROOT);

            if (joinedTables.contains(firstTableKey)) {
                sb.append(code).append(' ')
                        .append(last.getTableName()).append(' ').append(last.getTableAlias())
                        .append(" ON ").append(first.identity()).append(" = ").append(last.identity());
            } else if (joinedTables.contains(lastTableKey)) {
                sb.append(code).append(' ')
                        .append(first.getTableName()).append(' ').append(first.getTableAlias())
                        .append(" ON ").append(first.identity()).append(" = ").append(last.identity());
            } else {
                sb.append(first.getTableName()).append(' ').append(first.getTableAlias())
                        .append(' ').append(code).append(' ')
                        .append(last.getTableName()).append(' ').append(last.getTableAlias())
                        .append(" ON ").append(first.identity()).append(" = ").append(last.identity());
            }
            WhereCriterion onCriterion = segment.getOnCriterion();
            if (onCriterion != null && !onCriterion.isEmpty()) {
                StringBuilder onSql = new StringBuilder();
                onCriterion.render(this, onSql, params, ParameterSource.WHERE);
                if (!onSql.isEmpty()) {
                    boolean wrapParens = onCriterion.getCurrentOpt() == LinkType.OR
                            && onCriterion.getPredicates().size() > 1;
                    sb.append(" AND ");
                    if (wrapParens) {
                        sb.append('(').append(onSql).append(')');
                    } else {
                        sb.append(onSql);
                    }
                }
            }
            joinedTables.add(firstTableKey);
            joinedTables.add(lastTableKey);
            sj.add(sb.toString());
            sb.setLength(0);
        }
        return sj.toString();
    }

    /**
     * 提取当前查询涉及的表引用集合（供分片/多租户等拦截器直接读取，无需正则解析 SQL）。
     */
    public Set<TableRef> extractTables() {
        Set<TableRef> result = new LinkedHashSet<>();
        for (TableBinding binding : tableBindings.values()) {
            result.add(TableRef.ofQualified(binding.meta().qualifiedTableName(namingPolicy), binding.alias()));
        }
        return result;
    }

    private @Nullable TableBinding findBindingByPrefix(String prefix) {
        if (!aliased && tableBindings.size() == 1) {
            TableBinding single = tableBindings.values().iterator().next();
            if ("t0".equalsIgnoreCase(prefix)
                    || prefix.equalsIgnoreCase(single.entityClass().getSimpleName())
                    || prefix.equalsIgnoreCase(single.meta().tableName())
                    || prefix.equalsIgnoreCase(single.meta().qualifiedTableName())) {
                return single;
            }
        }
        for (TableBinding binding : tableBindings.values()) {
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
        ColumnAlias alias = new ColumnAlias();
        alias.setTableName(binding.meta().qualifiedTableName(namingPolicy));
        alias.setTableAlias(binding.alias());
        alias.setColumnName(formattedCol);
        alias.setColumnAlias(fieldName);
        return alias;
    }
}
