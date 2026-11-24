package com.sondertara.joya.core.query;

import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlNode;
import com.sondertara.joya.core.kernel.statement.SqlNodes;
import com.sondertara.joya.core.kernel.statement.TableRef;
import com.sondertara.joya.core.metadata.ColumnConverter;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.metadata.LocalTableMetaCache;
import com.sondertara.joya.core.naming.NamingPolicy;
import com.sondertara.joya.exceptions.DbException;
import com.sondertara.joya.utils.Strings;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 查询与单表变更的统一符号解析作用域（替代原 {@code RenderContext} 与 {@code ColumnAlias}）。
 *
 * <p>职责纯粹收敛于<b>语义与符号绑定</b>，完全不拼接 SQL 文本：
 * <ol>
 *   <li><b>表注册与别名分配</b>：查询模式（{@link #forQuery}）按 {@code FROM + JOIN} 确定性顺序分配 {@code t0, t1...}，
 *       并一次性通过 {@code NamingPolicy.resolveTable} 固定物理表名；单表变更模式（{@link #forSingleTable}）不分配表别名。</li>
 *   <li><b>列白名单与表归属守卫</b>：解析出的 {@link ResolvedColumn} 携带未加引号的规范列名与可选表节点，
 *       任何引用未 JOIN 实体的行为立即抛 {@link IllegalStateException} 阻断隐式笛卡尔积。</li>
 * </ol>
 */
public final class TableScope {

    private static final String ALIAS_PREFIX = "t";

    /**
     * 已完成表归属绑定的规范列描述符（{@code columnName} 保持未加引号原始元数据列名，交由 {@code SqlRenderer} 统一格式化）。
     */
    public record ResolvedColumn(
            SqlNode.@Nullable Table table,
            String columnName,
            @Nullable String fieldName,
            @Nullable ColumnConverter converter
    ) {
        public ResolvedColumn {
            columnName = Objects.requireNonNull(columnName, "columnName must not be null");
        }

        public ResolvedColumn(SqlNode.@Nullable Table table, String columnName, @Nullable String fieldName) {
            this(table, columnName, fieldName, null);
        }

        public @Nullable Object convertToDatabase(@Nullable Object value) {
            return converter != null ? converter.toDatabaseIfAttribute(value) : value;
        }

        public @Nullable String tableAlias() {
            return table == null || table.alias().isBlank() ? null : table.alias();
        }

        public SqlNode.Column toNode() {
            return new SqlNode.Column(table, columnName);
        }

        public String formatSql(NamingPolicy namingPolicy) {
            NamingPolicy policy = namingPolicy == null ? NamingPolicy.DEFAULT : namingPolicy;
            String formattedCol = Objects.requireNonNullElse(policy.formatColumn(columnName), columnName);
            String alias = tableAlias();
            return alias == null ? formattedCol : alias + "." + formattedCol;
        }
    }

    private record TableBinding(
            Class<?> entityClass,
            EntityMetadataContext meta,
            @Nullable String alias,
            String physicalName,
            SqlNode.@Nullable Table columnTable
    ) {
        SqlNode.Table statementTable() {
            return new SqlNode.Table(meta.tableName(), physicalName, alias == null ? "" : alias);
        }
    }

    private record SelfJoinTarget(TableBinding binding, boolean onLeft) {
    }

    private final Class<?> rootType;
    private final boolean aliased;
    private final List<JoinPart> joins;
    private final NamingPolicy namingPolicy;
    private final Map<Class<?>, TableBinding> tableBindings = new LinkedHashMap<>();
    private final List<TableBinding> allBindings = new ArrayList<>();
    private final Map<JoinPart, SelfJoinTarget> selfJoinBindings = new IdentityHashMap<>();
    private @Nullable TableBinding activeJoinTargetBinding;

    private TableScope(Class<?> rootType, boolean aliased, List<JoinPart> joins, NamingPolicy namingPolicy) {
        this.rootType = Objects.requireNonNull(rootType, "rootType must not be null");
        this.aliased = aliased;
        this.joins = joins == null ? List.of() : List.copyOf(joins);
        this.namingPolicy = Objects.requireNonNullElse(namingPolicy, NamingPolicy.DEFAULT);
    }

    public static TableScope forQuery(Class<?> rootType, List<JoinPart> joins) {
        return forQuery(rootType, joins, NamingPolicy.DEFAULT);
    }

    public static TableScope forQuery(Class<?> rootType, List<JoinPart> joins, NamingPolicy namingPolicy) {
        TableScope scope = new TableScope(rootType, true, joins, namingPolicy);
        scope.registerQueryTable(rootType, null);
        if (joins != null) {
            for (JoinPart join : joins) {
                Class<?> leftType = join.getLeftColumn().entityClass();
                Class<?> rightType = join.getRightColumn().entityClass();
                String explicitAlias = join.getAlias();
                boolean leftRegistered = leftType != null
                        && scope.isTableRegistered(leftType, join.getLeftColumn().tableAlias());
                boolean rightRegistered = rightType != null
                        && scope.isTableRegistered(rightType, join.getRightColumn().tableAlias());
                if (leftType != null && rightType != null && !leftRegistered && !rightRegistered) {
                    throw new IllegalStateException("Disconnected JOIN between ["
                            + leftType.getSimpleName() + "] and [" + rightType.getSimpleName()
                            + "]: neither table is connected to previously registered tables "
                            + scope.tableBindings.keySet().stream().map(Class::getSimpleName).toList());
                }
                if (leftType != null && !leftRegistered) {
                    scope.registerQueryTable(leftType, explicitAlias);
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
                        scope.registerSelfJoinTable(join, targetType, explicitAlias, onLeft);
                    } else if (!rightRegistered) {
                        scope.registerQueryTable(rightType, explicitAlias);
                    }
                }
            }
        }
        int maxJoinedTables = scope.namingPolicy.maxJoinTable();
        if (scope.allBindings.size() > maxJoinedTables) {
            throw new DbException("The maximum number of tables that can be joined is {}", maxJoinedTables);
        }
        return scope;
    }

    public static TableScope forSingleTable(EntityMetadataContext meta) {
        return forSingleTable(meta, NamingPolicy.DEFAULT);
    }

    public static TableScope forSingleTable(EntityMetadataContext meta, NamingPolicy namingPolicy) {
        Objects.requireNonNull(meta, "meta must not be null");
        Class<?> entityType = meta.entityType() != null ? meta.entityType() : Object.class;
        TableScope scope = new TableScope(entityType, false, List.of(), namingPolicy);
        String physical = meta.qualifiedTableName(scope.namingPolicy);
        TableBinding single = new TableBinding(entityType, meta, null, physical, null);
        scope.tableBindings.put(entityType, single);
        scope.allBindings.add(single);
        return scope;
    }

    public static TableScope forAliasedSingleTable(EntityMetadataContext meta, NamingPolicy namingPolicy) {
        Objects.requireNonNull(meta, "meta must not be null");
        Class<?> entityType = meta.entityType() != null ? meta.entityType() : Object.class;
        TableScope scope = new TableScope(entityType, true, List.of(), namingPolicy);
        String physical = meta.qualifiedTableName(scope.namingPolicy);
        SqlNode.Table table = new SqlNode.Table(meta.tableName(), physical, "t0");
        TableBinding single = new TableBinding(entityType, meta, "t0", physical, table);
        scope.tableBindings.put(entityType, single);
        scope.allBindings.add(single);
        return scope;
    }

    public static TableScope forSingleTable(Class<?> entityClass) {
        Objects.requireNonNull(entityClass, "entityClass must not be null");
        return forSingleTable(LocalTableMetaCache.getMetadataContext(entityClass), NamingPolicy.DEFAULT);
    }

    public NamingPolicy namingPolicy() {
        return namingPolicy;
    }

    public boolean isAliased() {
        return aliased;
    }

    public Class<?> rootType() {
        return rootType;
    }

    public boolean hasJoins() {
        return !joins.isEmpty();
    }

    public Collection<Class<?>> registeredEntities() {
        return Collections.unmodifiableSet(tableBindings.keySet());
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
        String physical = meta.qualifiedTableName(namingPolicy);
        SqlNode.Table table = new SqlNode.Table(meta.tableName(), physical, alias);
        TableBinding binding = new TableBinding(entityClass, meta, alias, physical, table);
        tableBindings.put(entityClass, binding);
        allBindings.add(binding);
    }

    private void registerSelfJoinTable(JoinPart join, Class<?> entityClass, @Nullable String explicitAlias, boolean onLeft) {
        EntityMetadataContext meta = LocalTableMetaCache.getMetadataContext(entityClass);
        String alias = allocateAlias(explicitAlias);
        String physical = meta.qualifiedTableName(namingPolicy);
        SqlNode.Table table = new SqlNode.Table(meta.tableName(), physical, alias);
        TableBinding binding = new TableBinding(entityClass, meta, alias, physical, table);
        selfJoinBindings.put(join, new SelfJoinTarget(binding, onLeft));
        allBindings.add(binding);
    }

    public ResolvedColumn resolveGetterColumn(Class<?> entityClass, String propertyName) {
        TableBinding binding = (activeJoinTargetBinding != null && activeJoinTargetBinding.entityClass().equals(entityClass))
                ? activeJoinTargetBinding
                : tableBindings.get(entityClass);
        if (binding == null) {
            throw unjoinedEntityError(entityClass, propertyName);
        }
        String columnName = binding.meta().columnByField(propertyName);
        if (columnName == null) {
            throw new DbException("Can not find column for property [{}] on entity [{}]",
                    propertyName, entityClass.getName());
        }
        return new ResolvedColumn(binding.columnTable(), columnName, propertyName,
                binding.meta().converterFor(columnName));
    }

    public ResolvedColumn resolveAliasedGetterColumn(String tableAlias, Class<?> entityClass, String propertyName) {
        if (Strings.isBlank(tableAlias)) {
            return resolveGetterColumn(entityClass, propertyName);
        }
        TableBinding matched = null;
        String targetAlias = tableAlias.trim();
        for (TableBinding b : allBindings) {
            if (b.alias() != null && b.alias().equalsIgnoreCase(targetAlias)) {
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
            throw new DbException("Can not find column for property [{}] on entity [{}]",
                    propertyName, entityClass.getName());
        }
        return new ResolvedColumn(matched.columnTable(), columnName, propertyName,
                matched.meta().converterFor(columnName));
    }

    /**
     * 解析框架内部已知合法的主表规范列名（零正则、零线性扫描，专供主键快捷读写使用）。
     */
    public ResolvedColumn resolveTrustedColumn(String canonicalColumn) {
        TableBinding root = tableBindings.get(rootType);
        if (root == null) {
            throw new IllegalStateException("Root table is not registered: " + rootType.getName());
        }
        return new ResolvedColumn(root.columnTable(), canonicalColumn, root.meta().fieldByColumn(canonicalColumn),
                root.meta().converterFor(canonicalColumn));
    }

    public ResolvedColumn resolveIdentifierColumn(String fieldOrColumn) {
        if (Strings.isBlank(fieldOrColumn) || !ColumnMetaProvider.isSafeIdentifier(fieldOrColumn.trim())) {
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
            ResolvedColumn matched = matchColumn(target, name);
            if (matched == null) {
                throw new IllegalArgumentException(
                        "Unknown column or field '" + name + "' on " + target.entityClass().getSimpleName());
            }
            return matched;
        }
        for (TableBinding binding : tableBindings.values()) {
            ResolvedColumn matched = matchColumn(binding, trimmed);
            if (matched != null) {
                return matched;
            }
        }
        throw new IllegalArgumentException(
                "Unknown column or field '" + trimmed + "' for entity " + rootType.getSimpleName());
    }

    public String resolveIdentifier(String fieldOrColumn) {
        return resolveIdentifierColumn(fieldOrColumn).formatSql(namingPolicy);
    }

    public List<ResolvedColumn> getAllColumns(Class<?> entityClass) {
        TableBinding binding = tableBindings.get(entityClass);
        if (binding == null) {
            throw unjoinedEntityError(entityClass, null);
        }
        List<String> colNames = binding.meta().columnNames();
        List<ResolvedColumn> result = new ArrayList<>(colNames.size());
        for (String col : colNames) {
            result.add(new ResolvedColumn(binding.columnTable(), col, binding.meta().fieldByColumn(col),
                    binding.meta().converterFor(col)));
        }
        return result;
    }

    public @Nullable ResolvedColumn findColumn(Class<?> entityClass, String propertyName,
                                               @Nullable String declaredColumnName) {
        TableBinding binding = tableBindings.get(entityClass);
        if (binding == null) {
            throw unjoinedEntityError(entityClass, propertyName);
        }
        String propertyColumn = binding.meta().columnByField(propertyName);
        if (propertyColumn != null) {
            return new ResolvedColumn(binding.columnTable(), propertyColumn, propertyName,
                    binding.meta().converterFor(propertyColumn));
        }
        if (declaredColumnName != null) {
            String canonical = binding.meta().canonicalColumn(declaredColumnName);
            if (canonical != null) {
                return new ResolvedColumn(binding.columnTable(), canonical, binding.meta().fieldByColumn(canonical),
                        binding.meta().converterFor(canonical));
            }
        }
        return null;
    }

    private ResolvedColumn rebindJoinColumn(ColumnMetaProvider columnProvider, TableBinding selfBinding) {
        ResolvedColumn base = columnProvider.resolveColumn(this);
        return new ResolvedColumn(selfBinding.columnTable(), base.columnName(), base.fieldName(), base.converter());
    }

    public SqlNode.From toFromNode() {
        TableBinding root = tableBindings.get(rootType);
        if (root == null) {
            throw new IllegalStateException("Root table is not registered: " + rootType.getName());
        }
        SqlNode.Table rootTable = root.statementTable();
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
            ResolvedColumn first = (selfTarget != null && selfTarget.onLeft())
                    ? rebindJoinColumn(segment.getLeftColumn(), selfTarget.binding())
                    : segment.getLeftColumn().resolveColumn(this);
            ResolvedColumn last = (selfTarget != null && !selfTarget.onLeft())
                    ? rebindJoinColumn(segment.getRightColumn(), selfTarget.binding())
                    : segment.getRightColumn().resolveColumn(this);
            String firstAliasKey = aliasKey(first);
            String lastAliasKey = aliasKey(last);
            ResolvedColumn target;
            if (joinedAliases.contains(firstAliasKey)) {
                target = last;
            } else if (joinedAliases.contains(lastAliasKey)) {
                target = first;
            } else {
                throw new IllegalStateException("Disconnected JOIN segment between '"
                        + tableLabel(first) + "' and '" + tableLabel(last) + "'");
            }
            SqlNode onNode = SqlNodes.eq(first.toNode(), last.toNode());
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
            SqlNode.Table targetTable = Objects.requireNonNull(target.table(),
                    "JOIN column must carry a bound SqlNode.Table");
            joinNodes.add(new SqlNode.Join(segment.getJoinType().toJoinKind(), targetTable, onNode));
            joinedAliases.add(firstAliasKey);
            joinedAliases.add(lastAliasKey);
        }
        return new SqlNode.From(rootTable, joinNodes);
    }

    private static String aliasKey(ResolvedColumn col) {
        if (col.table() == null) {
            return "";
        }
        String alias = col.table().alias();
        return (!alias.isBlank() ? alias : col.table().physicalName()).toLowerCase(Locale.ROOT);
    }

    private static String tableLabel(ResolvedColumn col) {
        return col.table() != null ? col.table().physicalName() : col.columnName();
    }

    public Set<TableRef> extractTables() {
        Set<TableRef> result = new LinkedHashSet<>();
        for (TableBinding binding : allBindings) {
            result.add(TableRef.ofQualified(binding.physicalName(), binding.alias()));
        }
        return result;
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

    private @Nullable ResolvedColumn matchColumn(TableBinding binding, String fieldOrColumn) {
        EntityMetadataContext meta = binding.meta();
        if (meta.containsField(fieldOrColumn)) {
            String col = meta.columnByField(fieldOrColumn);
            return new ResolvedColumn(binding.columnTable(), col, fieldOrColumn, meta.converterFor(col));
        }
        String canonicalCol = meta.canonicalColumn(fieldOrColumn);
        if (canonicalCol != null) {
            return new ResolvedColumn(binding.columnTable(), canonicalCol, meta.fieldByColumn(canonicalCol),
                    meta.converterFor(canonicalCol));
        }
        return null;
    }
}
