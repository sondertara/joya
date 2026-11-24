package com.sondertara.joya.core;

import com.sondertara.joya.core.function.IGetter;
import com.sondertara.joya.core.kernel.ExecutionKernel;
import com.sondertara.joya.core.kernel.statement.AstStatement;
import com.sondertara.joya.core.kernel.statement.SqlRenderer;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.metadata.VersionMetadata;
import com.sondertara.joya.core.result.MutationResult;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 实体链式插入构建器 —— 由 {@link EntityOps#insert()} 创建。
 *
 * @param <T> 实体类型
 */
public final class EntityInsert<T> {

    private final ExecutionKernel kernel;
    private final EntityMetadataContext meta;
    private final Map<String, Object> values = new LinkedHashMap<>();
    private final Map<String, Object> explicitValues = new LinkedHashMap<>();
    private @Nullable T sourceEntity;
    private boolean ignoreNull;

    EntityInsert(ExecutionKernel kernel, EntityMetadataContext meta) {
        this.kernel = Objects.requireNonNull(kernel, "kernel must not be null");
        this.meta = Objects.requireNonNull(meta, "meta must not be null");
    }

    public <V> EntityInsert<T> value(IGetter<T, V> column, @Nullable V value) {
        String colName = validateInsertableColumn(meta.resolveColumn(column));
        values.put(colName, value);
        explicitValues.put(colName, value);
        return this;
    }

    public <V> EntityInsert<T> valueIfPresent(IGetter<T, V> column, @Nullable V value) {
        String colName = validateInsertableColumn(meta.resolveColumn(column));
        if (value != null) {
            values.put(colName, value);
            explicitValues.put(colName, value);
        }
        return this;
    }

    public EntityInsert<T> value(String columnOrField, @Nullable Object value) {
        String colName = validateInsertableColumn(meta.resolveColumnName(columnOrField));
        values.put(colName, value);
        explicitValues.put(colName, value);
        return this;
    }

    public EntityInsert<T> valueIfPresent(String columnOrField, @Nullable Object value) {
        String colName = validateInsertableColumn(meta.resolveColumnName(columnOrField));
        if (value != null) {
            values.put(colName, value);
            explicitValues.put(colName, value);
        }
        return this;
    }

    public EntityInsert<T> values(Map<String, ?> map) {
        Objects.requireNonNull(map, "values map must not be null");
        map.forEach(this::value);
        return this;
    }

    public EntityInsert<T> values(T entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        this.sourceEntity = entity;
        Object[] snapshot = meta.snapshotValues(entity);
        String pk = meta.primaryKey();
        Object pkVal = pk == null ? null : meta.columnValue(snapshot, pk);
        for (String col : InsertPlan.candidateColumns(meta, pkVal)) {
            values.put(col, meta.columnValue(snapshot, col));
        }
        return this;
    }

    public EntityInsert<T> ignoreNulls() {
        this.ignoreNull = true;
        return this;
    }

    /** 构建未渲染的结构化 {@link AstStatement}（包内使用，不触发 {@code prePersist}）。 */
    AstStatement toAstStatement() {
        if (values.isEmpty()) {
            throw new IllegalStateException("Insert values must not be empty");
        }
        String pk = meta.primaryKey();
        Object pkValue = pk != null ? values.get(pk) : null;
        meta.requireAssignedPrimaryKey(pkValue);
        VersionMetadata versionMeta = meta.versionMetadata();
        String versionCol = versionMeta != null ? versionMeta.columnName() : null;
        boolean versionAdded = false;
        List<String> finalColumns = new ArrayList<>(values.size() + (versionMeta != null ? 1 : 0));
        List<Object> finalValues = new ArrayList<>(values.size() + (versionMeta != null ? 1 : 0));
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String col = entry.getKey();
            Object val = entry.getValue();
            if (versionCol != null && col.equals(versionCol)) {
                if (val == null) {
                    val = versionMeta.initialValue();
                }
                versionAdded = true;
            }
            if (ignoreNull && val == null) {
                continue;
            }
            if (meta.nonInsertableColumns().contains(col)) {
                continue;
            }
            finalColumns.add(col);
            finalValues.add(meta.convertToDatabaseIfAttribute(col, val));
        }
        if (versionMeta != null && !versionAdded && !meta.nonInsertableColumns().contains(versionCol)) {
            finalColumns.add(versionCol);
            finalValues.add(versionMeta.initialValue());
        }
        if (finalColumns.isEmpty()) {
            throw new IllegalStateException("INSERT statement has no insertable columns");
        }
        return EntityStatements.insertAst(meta, finalColumns, finalValues, kernel.namingPolicy());
    }

    /** 离线编译为不可变物理 {@link SqlStatement}（不触发 {@code prePersist}）。 */
    public SqlStatement toSqlStatement() {
        return SqlRenderer.render(toAstStatement(), kernel.namingPolicy());
    }

    /** 执行插入并返回包含生成键与真实受影响行数的 {@link MutationResult}。 */
    public MutationResult execute() {
        if (sourceEntity != null) {
            return new InsertPlan<>(meta, List.of(sourceEntity), ignoreNull,
                    "entity must not be null", explicitValues).execute(kernel);
        }
        AstStatement astStmt = toAstStatement();
        String pk = meta.primaryKey();
        Object existingPkVal = pk == null ? null : values.get(pk);
        return InsertPlan.executeSingleAst(kernel, meta, astStmt, null, existingPkVal);
    }

    private String validateInsertableColumn(String colName) {
        if (meta.nonInsertableColumns().contains(colName)) {
            throw new IllegalArgumentException(
                    "Column '" + colName + "' is marked as insertable=false on entity " + meta.entityType().getName());
        }
        String pk = meta.primaryKey();
        if (meta.generatedColumns().contains(colName) && !colName.equals(pk)) {
            throw new IllegalArgumentException(
                    "Column '" + colName + "' is database-generated and cannot be inserted on entity "
                            + meta.entityType().getName());
        }
        return colName;
    }
}
