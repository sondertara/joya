package com.sondertara.joya.core;

import com.sondertara.joya.core.kernel.ExecutionKernel;
import com.sondertara.joya.core.kernel.executor.BatchSlice;
import com.sondertara.joya.core.kernel.statement.AstStatement;
import com.sondertara.joya.core.kernel.statement.BatchAstStatement;
import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlNode;
import com.sondertara.joya.core.kernel.statement.SqlNodes;
import com.sondertara.joya.core.kernel.statement.SqlRenderer;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.metadata.VersionMetadata;
import com.sondertara.joya.core.query.criterion.WhereCriterion;
import com.sondertara.joya.core.result.MutationResult;
import com.sondertara.joya.exceptions.OptimisticLockException;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * 实体链式删除构建器 —— 由 {@link EntityOps#delete()} 创建。
 *
 * @param <T> 实体类型
 */
public final class EntityDelete<T> {

    private final ExecutionKernel kernel;
    private final EntityMetadataContext meta;
    private final List<UnaryOperator<WhereCriterion>> whereCallbacks = new ArrayList<>();
    private final List<WhereCriterion> explicitCriteria = new ArrayList<>();
    private boolean allowAll;

    EntityDelete(ExecutionKernel kernel, EntityMetadataContext meta) {
        this.kernel = Objects.requireNonNull(kernel, "kernel must not be null");
        this.meta = Objects.requireNonNull(meta, "meta must not be null");
    }

    public EntityDelete<T> where(UnaryOperator<WhereCriterion> condition) {
        Objects.requireNonNull(condition, "where condition must not be null");
        this.whereCallbacks.add(condition);
        return this;
    }

    public EntityDelete<T> where(WhereCriterion criterion) {
        Objects.requireNonNull(criterion, "criterion must not be null");
        WhereCriterion snapshot = criterion.copy();
        this.explicitCriteria.add(snapshot);
        this.whereCallbacks.add(w -> w.and(inner -> snapshot.copy()));
        return this;
    }

    public EntityDelete<T> allowAll() {
        this.allowAll = true;
        return this;
    }

    /** 构建未渲染的结构化 {@link AstStatement}（包内使用）。 */
    AstStatement toAstStatement() {
        return EntityStatements.deleteAst(meta, buildWhere(), allowAll, kernel.namingPolicy());
    }

    /** 离线编译为不可变物理 {@link SqlStatement}。 */
    public SqlStatement toSqlStatement() {
        return SqlRenderer.render(toAstStatement(), kernel.namingPolicy());
    }

    /** 执行删除并返回 {@link MutationResult}（在线路径单趟编译）。 */
    public MutationResult execute() {
        int affected = kernel.update(toAstStatement());
        return MutationResult.ofAffected(affected);
    }

    static void truncate(ExecutionKernel kernel, EntityMetadataContext meta) {
        kernel.update(EntityStatements.truncateAst(meta, kernel.namingPolicy()));
    }

    /** 带 {@code @Version} 乐观锁保护的单实体删除（包内收口，避免门面直接构造 AST）。 */
    static <T> MutationResult deleteVersioned(ExecutionKernel kernel,
                                              EntityMetadataContext meta,
                                              String pk,
                                              T entity) {
        VersionMetadata versionMeta = Objects.requireNonNull(meta.versionMetadata(), "versionMetadata must not be null");
        Object[] snapshot = meta.snapshotValues(entity);
        int pkIdx = meta.columnIndex(pk);
        Object pkValue = pkIdx >= 0 ? snapshot[pkIdx] : meta.readFieldValue(entity, pk);
        if (pkValue == null) {
            throw new IllegalArgumentException(
                    "Entity primary key must not be null for delete: " + meta.entityType().getName());
        }
        Object currentVersion = versionMeta.requireCurrent(
                snapshot[versionMeta.columnIndex()], meta.entityType(), pkValue);
        String vCol = versionMeta.columnName();
        SqlNode rowGuard = SqlNodes.eq(
                SqlNodes.column((SqlNode.Table) null, vCol),
                SqlNodes.param(vCol, currentVersion, ParameterSource.WHERE));
        AstStatement ast = EntityStatements.deleteByPkAst(
                meta, pk, meta.convertToDatabase(pk, pkValue), rowGuard, kernel.namingPolicy());
        int affected = kernel.update(ast);
        if (affected == 0) {
            throw OptimisticLockException.of(meta.entityType(), pkValue, currentVersion, "delete");
        }
        return MutationResult.ofAffected(affected);
    }

    /** 带 {@code @Version} 乐观锁保护的批量实体删除（强制走 {@code batchExecuteExact} 逐行核对计数）。 */
    static <T> MutationResult deleteAllVersioned(ExecutionKernel kernel,
                                                 EntityMetadataContext meta,
                                                 String pk,
                                                 Collection<? extends T> entities) {
        VersionMetadata versionMeta = Objects.requireNonNull(meta.versionMetadata(), "versionMetadata must not be null");
        int pkIdx = meta.columnIndex(pk);
        int vIdx = versionMeta.columnIndex();
        List<Object[]> rows = new ArrayList<>(entities.size());
        List<Object> pkValues = new ArrayList<>(entities.size());
        List<Object> versions = new ArrayList<>(entities.size());
        for (T entity : entities) {
            Objects.requireNonNull(entity, "Entity element in deleteAll must not be null");
            Object[] snapshot = meta.snapshotValues(entity);
            Object pkValue = pkIdx >= 0 ? snapshot[pkIdx] : meta.readFieldValue(entity, pk);
            if (pkValue == null) {
                throw new IllegalArgumentException(
                        "Entity primary key must not be null for deleteAll: " + meta.entityType().getName());
            }
            Object currentVersion = versionMeta.requireCurrent(snapshot[vIdx], meta.entityType(), pkValue);
            pkValues.add(pkValue);
            versions.add(currentVersion);
            rows.add(new Object[]{meta.convertToDatabase(pk, pkValue), currentVersion});
        }
        BatchAstStatement batch = EntityStatements.deleteBatchByPkAst(
                meta, pk, versionMeta.columnName(), rows,
                kernel.namingPolicy(), kernel.executionOptions().batchSize());
        BatchSlice slice = kernel.batchExecuteExact(batch);
        int[] counts = slice.batchCounts();
        for (int i = 0; i < pkValues.size(); i++) {
            int affected = i < counts.length ? counts[i] : 0;
            if (affected <= 0) {
                throw OptimisticLockException.of(
                        meta.entityType(), pkValues.get(i), versions.get(i), "batch delete");
            }
        }
        return slice.isEmpty()
                ? MutationResult.EMPTY
                : MutationResult.ofBatch(slice.batchCounts(), slice.generatedKeys());
    }

    private @Nullable WhereCriterion buildWhere() {
        if (explicitCriteria.size() == 1 && whereCallbacks.size() == 1) {
            return explicitCriteria.get(0).copy();
        }
        return WhereCriterion.merge(null, whereCallbacks);
    }
}
