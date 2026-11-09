package com.sondertara.joya.core;

import com.sondertara.joya.core.kernel.ExecutionKernel;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.statement.MutationContext;
import com.sondertara.joya.core.query.criterion.WhereCriterion;
import com.sondertara.joya.core.result.MutationResult;
import com.sondertara.joya.core.statement.factory.DeleteStatementFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * 实体链式删除构建器 —— 由 {@link EntityOps#delete()} 创建。
 *
 * <p>默认强制要求非空 WHERE 条件，全表删除须显式调用 {@link #allowAll()}。
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

    /** 追加 WHERE 过滤条件（多次调用以 AND 合并）。 */
    public EntityDelete<T> where(UnaryOperator<WhereCriterion> condition) {
        Objects.requireNonNull(condition, "where condition must not be null");
        this.whereCallbacks.add(condition);
        return this;
    }

    /** 追加预构建的 {@link WhereCriterion}（多次调用以 AND 合并）。 */
    public EntityDelete<T> where(WhereCriterion criterion) {
        Objects.requireNonNull(criterion, "criterion must not be null");
        WhereCriterion snapshot = criterion.copy();
        this.explicitCriteria.add(snapshot);
        this.whereCallbacks.add(w -> w.and(inner -> snapshot.copy()));
        return this;
    }

    /** 显式放行无 WHERE 条件的全表删除。 */
    public EntityDelete<T> allowAll() {
        this.allowAll = true;
        return this;
    }

    /** 渲染为不可变 {@link SqlStatement}。 */
    public SqlStatement toSqlStatement() {
        WhereCriterion where = buildWhere();
        MutationContext ctx = new MutationContext(meta, Map.of(), where, Set.of(), false, allowAll, kernel.namingPolicy());
        return DeleteStatementFactory.create(ctx);
    }

    /** 执行删除并返回 {@link MutationResult}。 */
    public MutationResult execute() {
        int affected = kernel.update(toSqlStatement());
        return MutationResult.ofAffected(affected);
    }

    private WhereCriterion buildWhere() {
        if (explicitCriteria.size() == 1 && whereCallbacks.size() == 1) {
            return explicitCriteria.get(0).copy();
        }
        return WhereCriterion.merge(null, whereCallbacks);
    }
}
