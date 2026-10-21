package com.sondertara.joya.core;

import com.sondertara.common.convert.ConvertUtils;
import com.sondertara.common.function.IGetter;
import com.sondertara.joya.core.cache.CacheHolder;
import com.sondertara.joya.core.jdbc.resultset.mapper.BeanRowMapper;
import com.sondertara.joya.core.jdbc.support.listener.EntityListener;
import com.sondertara.joya.core.kernel.JoyaTemplate;
import com.sondertara.joya.core.kernel.statement.BatchSqlStatement;
import com.sondertara.joya.core.kernel.statement.ColumnBinding;
import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.statement.StatementMetadata;
import com.sondertara.joya.core.kernel.statement.TableRef;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.metadata.EntitySnapshot;
import com.sondertara.joya.core.metadata.MutationContext;
import com.sondertara.joya.core.query.criterion.WhereCriterion;
import com.sondertara.joya.core.query.dsl.Query;
import com.sondertara.joya.core.query.pagination.OrderParam;
import com.sondertara.joya.core.result.MutationResult;
import com.sondertara.joya.core.statement.factory.DeleteStatementFactory;
import com.sondertara.joya.core.statement.factory.InsertStatementFactory;
import com.sondertara.joya.core.statement.factory.SelectStatementFactory;
import com.sondertara.joya.core.statement.factory.UpdateStatementFactory;
import com.sondertara.joya.exceptions.DbException;
import com.sondertara.joya.utils.LambdaUtils;
import com.sondertara.joya.utils.Strings;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * 类型化实体域门面 —— 由 {@link DataOps#of(Class)} 创建。
 *
 * <p>统一承载单个实体类型的三类操作：
 * <ul>
 *   <li><b>命令式实体写操作</b>：{@link #insert(Object)}、{@link #insertIgnoreNull(Object)}、{@link #insertAll(Collection)}、
 *       {@link #update(Object)}、{@link #patch(Object)}、{@link #updateAll(Collection)}、{@link #save(Object)}、
 *       {@link #saveIgnoreNull(Object)}、{@link #saveAll(Collection)}、{@link #deleteById(Serializable)}、
 *       {@link #deleteByIds(Collection)}、{@link #delete(Object)}、{@link #truncate()}。</li>
 *   <li><b>链式 DSL 入口</b>：{@link #query()}、{@link #where(UnaryOperator)}、{@link #insert()}、
 *       {@link #update()}、{@link #delete()}。</li>
 *   <li><b>按主键与表级快捷读</b>：{@link #findById(Serializable)}、{@link #findByIds(Collection)}、
 *       {@link #findAll()}、{@link #existsById(Serializable)}、{@link #count()}。</li>
 * </ul>
 *
 * @param <T> 实体类型
 */
public final class EntityOps<T> {

    private static final int DEFAULT_BATCH_PARTITION_SIZE = 8000;

    private final JoyaTemplate template;
    private final Class<T> entityType;
    private final EntityMetadataContext meta;

    public EntityOps(JoyaTemplate template, Class<T> entityType) {
        this.template = Objects.requireNonNull(template, "template must not be null");
        this.entityType = Objects.requireNonNull(entityType, "entityType must not be null");
        this.meta = metadata(entityType);
    }

    // ===== 1. 命令式实体写操作 =====

    /**
     * 插入单个实体（不执行 SELECT 存在性探测，直接执行 INSERT）。
     * 若实体主键为空且数据库生成了自增键，则自动将生成键回写至实体主键字段，并通过 {@link MutationResult} 返回。
     */
    public MutationResult insert(T entity) {
        return doInsert(entity, false);
    }

    /**
     * 插入单个实体并跳过值为 {@code null} 的字段（由数据库填充默认值）。
     */
    public MutationResult insertIgnoreNull(T entity) {
        return doInsert(entity, true);
    }

    /**
     * 批量插入实体集合（自动按是否预置主键分组执行 JDBC 批处理）。
     */
    public int[] insertAll(Collection<T> entities) {
        if (entities == null || entities.isEmpty()) {
            return new int[0];
        }
        List<T> rowsWithoutKey = new ArrayList<>();
        List<T> rowsWithKey = new ArrayList<>();
        String pk = meta.primaryKey();
        for (T row : entities) {
            if (row instanceof EntityListener listener) {
                listener.prePersist(row);
            }
            Object pkVal = pk == null ? null : EntitySnapshot.snapshot(meta, row).value(pk);
            if (pkVal == null) {
                rowsWithoutKey.add(row);
            } else {
                rowsWithKey.add(row);
            }
        }
        int[] r1 = executeBatch(buildInsertBatch(rowsWithoutKey, false));
        int[] r2 = executeBatch(buildInsertBatch(rowsWithKey, true));
        return concatCounts(r1, r2);
    }

    /**
     * 按主键全量更新实体（更新除主键外的所有可更新列，包括值为 {@code null} 的列）。
     */
    public MutationResult update(T entity) {
        return doUpdateByPrimaryKey(entity, false);
    }

    /**
     * 按主键局部更新实体（仅更新非 {@code null} 的列，跳过 {@code null} 字段）。
     */
    public MutationResult patch(T entity) {
        return doUpdateByPrimaryKey(entity, true);
    }

    /**
     * 按主键批量更新实体集合。
     */
    public int[] updateAll(Collection<T> entities) {
        if (entities == null || entities.isEmpty()) {
            return new int[0];
        }
        List<T> list = entities instanceof List<T> l ? l : new ArrayList<>(entities);
        return executeBatch(buildUpdateBatch(list));
    }

    /**
     * 保存实体（Upsert 语义：主键为空或记录不存在时执行 {@link #insert(Object)}，否则执行 {@link #update(Object)}）。
     */
    public MutationResult save(T entity) {
        return doUpsert(entity, false);
    }

    /**
     * 保存实体并跳过 {@code null} 字段（主键为空或记录不存在时执行 {@link #insertIgnoreNull(Object)}，否则执行 {@link #patch(Object)}）。
     */
    public MutationResult saveIgnoreNull(T entity) {
        return doUpsert(entity, true);
    }

    /**
     * 批量保存实体（Upsert 语义：按 8000 行分片，使用单条 {@code IN} 批量探测已存在主键后分别批量插入与更新）。
     */
    public void saveAll(Collection<T> entities) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        List<T> rows = entities instanceof List<T> l ? l : new ArrayList<>(entities);
        String pk = requirePrimaryKey();
        int batchPartitionSize = template.settings().batchSize() > 0
                ? template.settings().batchSize()
                : DEFAULT_BATCH_PARTITION_SIZE;
        for (List<T> partition : partition(rows, batchPartitionSize)) {
            List<T> insertWithoutKey = new ArrayList<>();
            List<T> insertWithKey = new ArrayList<>();
            List<T> toUpdate = new ArrayList<>();
            Map<Object, T> keyed = new LinkedHashMap<>();

            for (T row : partition) {
                Object pkVal = meta.readFieldValue(row, pk);
                if (pkVal == null) {
                    if (row instanceof EntityListener listener) {
                        listener.prePersist(row);
                    }
                    Object pkAfterHook = meta.readFieldValue(row, pk);
                    if (pkAfterHook == null) {
                        insertWithoutKey.add(row);
                    } else {
                        insertWithKey.add(row);
                    }
                } else {
                    keyed.put(pkVal, row);
                }
            }
            if (!keyed.isEmpty()) {
                Set<Object> existing = findExistingIds(keyed.keySet());
                keyed.forEach((pkVal, row) -> {
                    if (existing.contains(pkVal)) {
                        toUpdate.add(row);
                    } else {
                        if (row instanceof EntityListener listener) {
                            listener.prePersist(row);
                        }
                        insertWithKey.add(row);
                    }
                });
            }
            executeBatch(buildUpdateBatch(toUpdate));
            executeBatch(buildInsertBatch(insertWithoutKey, false));
            executeBatch(buildInsertBatch(insertWithKey, true));
        }
    }

    /**
     * 按主键删除单条记录。
     */
    public MutationResult deleteById(Serializable id) {
        Objects.requireNonNull(id, "id must not be null");
        String pk = requirePrimaryKey();
        return delete().where(w -> w.eq(pk, id)).execute();
    }

    /**
     * 按主键集合批量删除（单条 {@code DELETE ... WHERE pk IN (...)} 语句，消除 N+1 循环）。
     */
    public MutationResult deleteByIds(Collection<? extends Serializable> ids) {
        if (ids == null || ids.isEmpty()) {
            return MutationResult.EMPTY;
        }
        String pk = requirePrimaryKey();
        return delete().where(w -> w.in(pk, ids)).execute();
    }

    /**
     * 按实体实例的主键值删除记录。
     */
    public MutationResult delete(T entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        String pk = requirePrimaryKey();
        Object pkVal = EntitySnapshot.snapshot(meta, entity).value(pk);
        if (pkVal == null) {
            throw new IllegalArgumentException("Entity primary key must not be null for delete: " + entityType.getName());
        }
        return delete().where(w -> w.eq(pk, pkVal)).execute();
    }

    /**
     * 按实体集合批量删除记录（单条 {@code IN} 语句）。
     */
    public MutationResult deleteAll(Collection<? extends T> entities) {
        if (entities == null || entities.isEmpty()) {
            return MutationResult.EMPTY;
        }
        String pk = requirePrimaryKey();
        List<Serializable> ids = new ArrayList<>(entities.size());
        for (T entity : entities) {
            Object pkVal = EntitySnapshot.snapshot(meta, entity).value(pk);
            if (pkVal instanceof Serializable s) {
                ids.add(s);
            } else if (pkVal != null) {
                ids.add(pkVal.toString());
            }
        }
        return deleteByIds(ids);
    }

    /**
     * 清空整张表（执行 {@code TRUNCATE TABLE}）。
     */
    public void truncate() {
        String tableName = meta.qualifiedTableName(template.settings());
        StatementMetadata metadata = StatementMetadata.builder(Operation.DELETE)
                .table(TableRef.of(tableName))
                .entityType(entityType)
                .build();
        template.update(SqlStatement.of("TRUNCATE TABLE " + tableName, List.of(), metadata, false));
    }

    // ===== 2. 链式变更构建器入口 =====

    /** 开启链式列级插入构建器。 */
    public EntityInsert<T> insert() {
        return new EntityInsert<>(template, meta);
    }

    /** 开启链式条件更新构建器（显式指定待更新列与 WHERE 条件）。 */
    public EntityUpdate<T> update() {
        return new EntityUpdate<>(template, meta);
    }

    /** 开启链式条件删除构建器（强制要求非空 WHERE 或显式 {@code allowAll()}）。 */
    public EntityDelete<T> delete() {
        return new EntityDelete<>(template, meta);
    }

    // ===== 3. 按主键与表级快捷读 =====

    /** 按主键查询单条记录。 */
    public Optional<T> findById(Serializable id) {
        Objects.requireNonNull(id, "id must not be null");
        SqlStatement stmt = SelectStatementFactory.byPrimaryKey(meta, id, template.settings());
        return Optional.ofNullable(template.query(stmt, BeanRowMapper.of(entityType)));
    }

    /** 按主键集合批量查询记录（单条 {@code IN} 语句）。 */
    public List<T> findByIds(Collection<? extends Serializable> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String pk = requirePrimaryKey();
        return new Query<>(template, entityType).where(w -> w.in(pk, ids)).list();
    }

    /** 查询表中全部记录。 */
    public List<T> findAll() {
        return new Query<>(template, entityType).list();
    }

    /** 查询表中全部记录并按指定排序参数排序（字段名经元数据白名单校验）。 */
    public List<T> findAll(OrderParam... orderParams) {
        return new Query<>(template, entityType).orderBy(orderParams).list();
    }

    /** 判断指定主键的记录是否存在。 */
    public boolean existsById(Serializable id) {
        Objects.requireNonNull(id, "id must not be null");
        String pk = requirePrimaryKey();
        return new Query<>(template, entityType).where(w -> w.eq(pk, id)).exists();
    }

    /** 统计表总行数。 */
    public long count() {
        return new Query<>(template, entityType).count();
    }

    // ===== 内部辅助与元数据解析 =====

    private MutationResult doInsert(T entity, boolean ignoreNull) {
        Objects.requireNonNull(entity, "entity must not be null");
        if (entity instanceof EntityListener listener) {
            listener.prePersist(entity);
        }
        EntitySnapshot snapshot = EntitySnapshot.snapshot(meta, entity);
        Map<String, Object> values = snapshot.values();
        String pk = meta.primaryKey();
        Object existingPkVal = pk == null ? null : values.get(pk);

        Set<String> declaredColumns;
        if (existingPkVal != null) {
            declaredColumns = new LinkedHashSet<>(meta.columnNames());
        } else {
            declaredColumns = new LinkedHashSet<>(meta.insertableColumns());
        }
        MutationContext ctx = new MutationContext(meta, values, null, declaredColumns, ignoreNull, false, template.settings());
        SqlStatement stmt = InsertStatementFactory.create(ctx);
        Object generatedKey = template.insert(stmt);
        Object finalKey = generatedKey != null ? generatedKey : existingPkVal;
        if (existingPkVal == null && generatedKey != null) {
            writeBackPrimaryKey(entity, generatedKey);
        }
        return MutationResult.ofInsert(1, finalKey);
    }

    private MutationResult doUpdateByPrimaryKey(T entity, boolean ignoreNull) {
        Objects.requireNonNull(entity, "entity must not be null");
        String pk = requirePrimaryKey();
        if (entity instanceof EntityListener listener) {
            listener.preUpdate(entity);
        }
        EntitySnapshot snapshot = EntitySnapshot.snapshot(meta, entity);
        Map<String, Object> values = snapshot.values();
        Object pkValue = values.get(pk);
        if (pkValue == null) {
            throw new IllegalArgumentException("Entity primary key must not be null for update: " + entityType.getName());
        }
        List<String> setColumns = new ArrayList<>(meta.updatableColumns());
        setColumns.remove(pk);

        WhereCriterion where = WhereCriterion.create();
        where.eq(pk, pkValue);
        MutationContext ctx = new MutationContext(meta, values, where,
                new LinkedHashSet<>(setColumns), ignoreNull, false, template.settings());
        int affected = template.update(UpdateStatementFactory.create(ctx));
        return MutationResult.ofAffected(affected);
    }

    private MutationResult doUpsert(T entity, boolean ignoreNull) {
        Objects.requireNonNull(entity, "entity must not be null");
        String pk = meta.primaryKey();
        Object pkVal = pk == null ? null : meta.readFieldValue(entity, pk);
        boolean exists = (pkVal instanceof Serializable s) && existsById(s);
        if (!exists) {
            return doInsert(entity, ignoreNull);
        }
        MutationResult updated = doUpdateByPrimaryKey(entity, ignoreNull);
        return MutationResult.ofInsert(updated.affectedRows(), pkVal);
    }

    private void writeBackPrimaryKey(T entity, Object generatedKey) {
        String pkCol = meta.primaryKey();
        if (pkCol == null) {
            return;
        }
        try {
            Class<?> pkType = meta.primaryKeyType();
            Object converted = (pkType != null && !pkType.isInstance(generatedKey))
                    ? ConvertUtils.convert(generatedKey, pkType)
                    : generatedKey;
            meta.writeFieldValue(entity, pkCol, converted);
        } catch (Exception ignored) {
            // 若实体无对应可写字段或转换失败，保持静默，调用方仍可从 MutationResult 获取生成键
        }
    }

    private String requirePrimaryKey() {
        String pk = meta.primaryKey();
        if (pk == null || pk.isBlank()) {
            throw new IllegalStateException("Entity has no primary key: " + entityType.getName());
        }
        return pk;
    }

    private Set<Object> findExistingIds(Collection<?> keys) {
        String pk = requirePrimaryKey();
        WhereCriterion where = WhereCriterion.create();
        where.in(pk, keys);
        SqlStatement stmt = SelectStatementFactory.create(meta, where, Set.of(pk), null, template.settings());
        Class<?> pkType = meta.primaryKeyType();
        List<Object> rows = template.queryList(stmt, rs -> {
            try {
                Object raw = rs.getObject(1);
                if (raw != null && pkType != null && !pkType.isInstance(raw)) {
                    return ConvertUtils.convert(raw, pkType);
                }
                return raw;
            } catch (java.sql.SQLException e) {
                throw new DbException(e);
            }
        });
        return new HashSet<>(rows);
    }

    private BatchSqlStatement buildInsertBatch(List<T> rows, boolean withKey) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        var settings = template.settings();
        List<String> columns = withKey ? new ArrayList<>(meta.columnNames()) : meta.insertableColumns();
        List<String> formattedColumns = columns.stream().map(c -> meta.formatColumn(c, settings)).toList();
        String tableName = meta.qualifiedTableName(settings);
        StatementMetadata metadata = StatementMetadata.builder(Operation.INSERT)
                .table(TableRef.of(tableName))
                .entityType(entityType)
                .build();
        return new BatchSqlStatement(
                buildInsertSql(tableName, formattedColumns),
                bindingsOf(columns),
                rowsOf(rows, columns),
                metadata);
    }

    private BatchSqlStatement buildUpdateBatch(List<T> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        var settings = template.settings();
        String pk = requirePrimaryKey();
        List<String> setColumns = new ArrayList<>(meta.updatableColumns());
        setColumns.remove(pk);
        for (T row : rows) {
            if (row instanceof EntityListener listener) {
                listener.preUpdate(row);
            }
        }
        List<String> formattedSetColumns = setColumns.stream().map(c -> meta.formatColumn(c, settings)).toList();
        List<String> allColumns = new ArrayList<>(setColumns);
        allColumns.add(pk);
        String tableName = meta.qualifiedTableName(settings);
        StatementMetadata metadata = StatementMetadata.builder(Operation.UPDATE)
                .table(TableRef.of(tableName))
                .entityType(entityType)
                .build();
        return new BatchSqlStatement(
                buildUpdateSql(tableName, formattedSetColumns, meta.formatColumn(pk, settings)),
                bindingsOf(allColumns),
                rowsOf(rows, allColumns),
                metadata);
    }

    private int[] executeBatch(BatchSqlStatement batch) {
        if (batch == null || batch.rowCount() == 0) {
            return new int[0];
        }
        return template.batchUpdate(batch);
    }

    private static List<ColumnBinding> bindingsOf(List<String> columns) {
        List<ColumnBinding> bindings = new ArrayList<>(columns.size());
        for (String col : columns) {
            bindings.add(ColumnBinding.of(col));
        }
        return bindings;
    }

    private List<Object[]> rowsOf(List<T> rows, List<String> columns) {
        List<Object[]> batchRows = new ArrayList<>(rows.size());
        for (T row : rows) {
            EntitySnapshot snapshot = EntitySnapshot.snapshot(meta, row);
            Object[] values = new Object[columns.size()];
            for (int i = 0; i < columns.size(); i++) {
                values[i] = snapshot.value(columns.get(i));
            }
            batchRows.add(values);
        }
        return batchRows;
    }

    private static String buildInsertSql(String tableName, List<String> columns) {
        if (columns.isEmpty()) {
            return "INSERT INTO " + tableName + " DEFAULT VALUES";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("INSERT INTO ").append(tableName)
                .append(" (").append(String.join(", ", columns)).append(") VALUES (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('?');
        }
        return sb.append(')').toString();
    }

    private static String buildUpdateSql(String tableName, List<String> setColumns, String pk) {
        StringBuilder sb = new StringBuilder();
        sb.append("UPDATE ").append(tableName).append(" SET ");
        for (int i = 0; i < setColumns.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(setColumns.get(i)).append(" = ?");
        }
        return sb.append(" WHERE ").append(pk).append(" = ?").toString();
    }

    private static <E> List<List<E>> partition(List<E> list, int size) {
        List<List<E>> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            result.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return result;
    }

    private static int[] concatCounts(int[] a, int[] b) {
        if (a.length == 0) {
            return b;
        }
        if (b.length == 0) {
            return a;
        }
        int[] merged = new int[a.length + b.length];
        System.arraycopy(a, 0, merged, 0, a.length);
        System.arraycopy(b, 0, merged, a.length, b.length);
        return merged;
    }

    static EntityMetadataContext metadata(Class<?> entityType) {
        return CacheHolder.tableMetaCache().getMetadataContext(entityType);
    }

    static <E> String resolveColumn(EntityMetadataContext meta, IGetter<E, ?> getter) {
        String methodName = LambdaUtils.getImplMethodName(getter);
        String rawProp;
        if (methodName.startsWith("get") && methodName.length() > 3) {
            rawProp = Strings.lowerFirst(methodName.substring(3));
        } else if (methodName.startsWith("is") && methodName.length() > 2) {
            rawProp = Strings.lowerFirst(methodName.substring(2));
        } else {
            rawProp = methodName;
        }
        String col = meta.columnByField(rawProp);
        if (col == null) {
            throw new IllegalArgumentException(
                    "Unknown property '" + rawProp + "' on entity " + meta.entityType().getName());
        }
        return col;
    }

    static String resolveColumnName(EntityMetadataContext meta, String columnOrField) {
        if (columnOrField == null || columnOrField.isBlank()) {
            throw new IllegalArgumentException("Column or field name must not be blank");
        }
        String trimmed = columnOrField.trim();
        if (meta.containsField(trimmed)) {
            return meta.columnByField(trimmed);
        }
        if (meta.containsColumn(trimmed)) {
            return trimmed;
        }
        String lower = trimmed.toLowerCase();
        if (meta.containsColumn(lower)) {
            return lower;
        }
        String upper = trimmed.toUpperCase();
        if (meta.containsColumn(upper)) {
            return upper;
        }
        throw new IllegalArgumentException(
                "Unknown column or field '" + columnOrField + "' for entity " + meta.entityType().getName());
    }
}
