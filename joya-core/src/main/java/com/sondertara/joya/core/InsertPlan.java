package com.sondertara.joya.core;

import com.sondertara.joya.core.listener.EntityLifecycle;
import com.sondertara.joya.core.kernel.executor.ExecutionOptions;
import com.sondertara.joya.core.kernel.ExecutionKernel;
import com.sondertara.joya.core.kernel.executor.BatchSlice;
import com.sondertara.joya.core.kernel.statement.BatchSqlStatement;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.metadata.EntitySnapshot;
import com.sondertara.joya.core.result.MutationResult;
import com.sondertara.joya.core.statement.MutationContext;
import com.sondertara.joya.core.statement.factory.InsertStatementFactory;
import com.sondertara.joya.utils.ConvertUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 实体插入计划：单条插入与批量插入共用的唯一规划/校验/执行入口。
 *
 * <p>每个实体在 {@code prePersist} 之后只做一次字段快照，后续单条渲染、批量行提取、
 * 生成键回写全部复用该快照，避免同一行被反复反射读取。
 */
final class InsertPlan<T> {

    private static final Logger log = LoggerFactory.getLogger(InsertPlan.class);

    private final EntityMetadataContext meta;
    private final List<T> entities;
    private final boolean ignoreNull;
    private final Map<String, Object> overrides;
    private final EntityLifecycle lifecycle;

    InsertPlan(EntityMetadataContext meta, Collection<T> entities, boolean ignoreNull, String nullMessage) {
        this(meta, entities, ignoreNull, nullMessage, Map.of(), EntityLifecycle.EMPTY);
    }

    InsertPlan(EntityMetadataContext meta, Collection<T> entities, boolean ignoreNull, String nullMessage,
               Map<String, Object> overrides) {
        this(meta, entities, ignoreNull, nullMessage, overrides, EntityLifecycle.EMPTY);
    }

    InsertPlan(EntityMetadataContext meta, Collection<T> entities, boolean ignoreNull, String nullMessage,
               Map<String, Object> overrides, EntityLifecycle lifecycle) {
        this.meta = Objects.requireNonNull(meta, "meta must not be null");
        Objects.requireNonNull(entities, "entities must not be null");
        this.entities = new ArrayList<>(entities.size());
        for (T entity : entities) {
            this.entities.add(Objects.requireNonNull(entity, nullMessage));
        }
        this.ignoreNull = ignoreNull;
        this.overrides = (overrides == null || overrides.isEmpty())
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(overrides));
        this.lifecycle = lifecycle == null ? EntityLifecycle.EMPTY : lifecycle;
    }

    /**
     * 一次实体快照：列选择、单条渲染、批量行提取、生成键回写共用。
     */
    private record RowPlan<E>(E entity, Map<String, Object> values, List<String> columns) {
    }

    /**
     * 监听器执行后的实体快照；显式 {@code value(...)} 覆盖值优先于快照。
     */
    private Map<String, Object> valuesOf(T entity) {
        Map<String, Object> values = new LinkedHashMap<>(EntitySnapshot.snapshot(meta, entity).values());
        if (!overrides.isEmpty()) {
            values.putAll(overrides);
        }
        return values;
    }

    MutationResult execute(ExecutionKernel template) {
        if (entities.isEmpty()) {
            return MutationResult.EMPTY;
        }
        String pk = meta.primaryKey();
        boolean pkGenerated = pk != null && meta.generatedColumns().contains(pk);

        record InsertRun<E>(List<String> cols, List<RowPlan<E>> rows) {
        }
        List<InsertRun<T>> runs = new ArrayList<>();
        for (T entity : entities) {
            lifecycle.prePersist(entity);
            Map<String, Object> values = valuesOf(entity);
            List<String> cols = columnsFor(values, pk, pkGenerated);
            RowPlan<T> row = new RowPlan<>(entity, values, cols);
            if (!runs.isEmpty() && runs.get(runs.size() - 1).cols().equals(cols)) {
                runs.get(runs.size() - 1).rows().add(row);
            } else {
                List<RowPlan<T>> rows = new ArrayList<>();
                rows.add(row);
                runs.add(new InsertRun<>(cols, rows));
            }
        }

        MutationResult total = MutationResult.EMPTY;
        for (InsertRun<T> run : runs) {
            MutationResult result;
            if (runs.size() == 1 && run.rows().size() == 1) {
                result = insertSingle(template, run.rows().get(0), pk);
            } else {
                boolean wantKeys = pkGenerated && !run.cols().contains(pk);
                result = insertBatch(template, run.rows(), run.cols(), wantKeys);
            }
            total = total.merge(result);
            for (RowPlan<T> row : run.rows()) {
                lifecycle.postPersist(row.entity());
            }
        }
        return total;
    }

    static List<String> candidateColumns(EntityMetadataContext meta, @Nullable Object pkValue) {
        return pkValue != null ? meta.insertableColumnsWithPrimaryKey() : meta.insertableColumns();
    }

    /**
     * 计算一行实际要写入的列：主键非空时保留主键列；{@code ignoreNull} 时过滤 null 列；
     * 并对“非自增主键为 null”给出明确错误，避免落到 JDBC 层的 {@code NULL not allowed}。
     */
    private List<String> columnsFor(Map<String, Object> values, String pk, boolean pkGenerated) {
        Object pkValue = pk == null ? null : values.get(pk);
        if (pk != null && !pkGenerated && pkValue == null) {
            throw new IllegalArgumentException("Primary key '" + pk + "' of " + meta.entityType().getName()
                    + " is null and is not annotated with @GeneratedValue; assign it before insert");
        }
        List<String> candidate = candidateColumns(meta, pkValue);
        List<String> cols;
        if (ignoreNull) {
            cols = new ArrayList<>(candidate.size());
            for (String col : candidate) {
                if (values.get(col) != null) {
                    cols.add(col);
                }
            }
            cols = List.copyOf(cols);
        } else {
            cols = candidate;
        }
        if (cols.isEmpty()) {
            throw new IllegalStateException("INSERT statement has no insertable columns");
        }
        return cols;
    }

    private MutationResult insertSingle(ExecutionKernel template, RowPlan<T> row, String pk) {
        Set<String> declared = new LinkedHashSet<>(row.columns());
        MutationContext ctx = new MutationContext(meta, row.values(), null, declared, ignoreNull, false,
                template.namingPolicy());
        Object existingPkValue = pk == null ? null : row.values().get(pk);
        return executeSingleStatement(template, meta, InsertStatementFactory.create(ctx),
                row.entity(), existingPkValue, false, lifecycle);
    }

    private MutationResult insertBatch(ExecutionKernel template, List<RowPlan<T>> rows,
                                       List<String> columns, boolean wantKeys) {
        List<Object[]> batchRows = new ArrayList<>(rows.size());
        for (RowPlan<T> row : rows) {
            batchRows.add(rowValues(row.values(), columns));
        }
        int batchSize = template.executionOptions().batchSize() > 0
                ? template.executionOptions().batchSize()
                : ExecutionOptions.DEFAULT_BATCH_SIZE;
        BatchSqlStatement batch = InsertStatementFactory.createBatch(
                meta, columns, batchRows, template.namingPolicy(), batchSize, wantKeys);
        BatchSlice slice = template.batchExecute(batch);
        MutationResult result = slice.isEmpty()
                ? MutationResult.EMPTY
                : MutationResult.ofBatch(slice.batchCounts(), slice.generatedKeys());
        if (wantKeys) {
            if (slice.generatedKeys().size() == rows.size()) {
                for (int i = 0; i < rows.size(); i++) {
                    writeBackPrimaryKey(meta, rows.get(i).entity(), slice.generatedKeys().get(i));
                }
            } else {
                log.warn("Generated key count mismatch for batch insert on {}: expected {} but got {}; "
                                + "primary keys will not be written back",
                        meta.entityType().getName(), rows.size(), slice.generatedKeys().size());
            }
        }
        return result;
    }

    private static Object[] rowValues(Map<String, Object> values, List<String> columns) {
        Object[] row = new Object[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            row[i] = values.get(columns.get(i));
        }
        return row;
    }

    private static void writeBackPrimaryKey(EntityMetadataContext meta, Object entity, Object generatedKey) {
        String pkCol = meta.primaryKey();
        if (pkCol == null || generatedKey == null || entity == null) {
            return;
        }
        try {
            Class<?> pkType = meta.primaryKeyType();
            Object converted = (pkType != null && !pkType.isInstance(generatedKey))
                    ? ConvertUtils.convert(generatedKey, pkType)
                    : generatedKey;
            meta.writeFieldValue(entity, pkCol, converted);
        } catch (Exception ignored) {
            // 与旧 GeneratedKeyWriter 行为一致：回写失败不阻断已成功的插入，调用方仍可从 MutationResult 取键
            log.debug("Failed to write back generated key to entity {}: {}",
                    meta.entityType().getName(), ignored.getMessage());
        }
    }

    static <T> MutationResult executeSingleStatement(
            ExecutionKernel template,
            EntityMetadataContext meta,
            SqlStatement statement,
            @Nullable T sourceEntity,
            @Nullable Object existingPkValue,
            boolean firePostPersist) {
        return executeSingleStatement(template, meta, statement, sourceEntity, existingPkValue,
                firePostPersist, EntityLifecycle.EMPTY);
    }

    static <T> MutationResult executeSingleStatement(
            ExecutionKernel template,
            EntityMetadataContext meta,
            SqlStatement statement,
            @Nullable T sourceEntity,
            @Nullable Object existingPkValue,
            boolean firePostPersist,
            EntityLifecycle lifecycle) {
        var slice = template.insertExecute(statement);
        Object generatedKey = slice.generatedKey();
        if (sourceEntity != null) {
            if (existingPkValue == null && generatedKey != null) {
                writeBackPrimaryKey(meta, sourceEntity, generatedKey);
            }
            if (firePostPersist) {
                lifecycle.postPersist(sourceEntity);
            }
        }
        return MutationResult.ofInsert(slice.affectedRows(),
                generatedKey != null ? generatedKey : existingPkValue);
    }
}
