package com.sondertara.joya.core;

import com.sondertara.joya.core.function.IGetter;
import com.sondertara.joya.core.kernel.ExecutionKernel;
import com.sondertara.joya.core.kernel.statement.AstStatement;
import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlFormatUtils;
import com.sondertara.joya.core.kernel.statement.SqlNode;
import com.sondertara.joya.core.kernel.statement.SqlNodes;
import com.sondertara.joya.core.kernel.statement.SqlParameter;
import com.sondertara.joya.core.kernel.statement.SqlRenderer;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.query.criterion.WhereCriterion;
import com.sondertara.joya.core.result.MutationResult;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * 实体链式更新构建器 —— 由 {@link EntityOps#update()} 创建。
 *
 * @param <T> 实体类型
 */
public final class EntityUpdate<T> {

    private final ExecutionKernel kernel;
    private final EntityMetadataContext meta;
    private final Map<String, Object> values = new LinkedHashMap<>();
    private final List<UnaryOperator<WhereCriterion>> whereCallbacks = new ArrayList<>();
    private final List<WhereCriterion> explicitCriteria = new ArrayList<>();
    private boolean allowAll;

    EntityUpdate(ExecutionKernel kernel, EntityMetadataContext meta) {
        this.kernel = Objects.requireNonNull(kernel, "kernel must not be null");
        this.meta = Objects.requireNonNull(meta, "meta must not be null");
    }

    public <V> EntityUpdate<T> set(IGetter<T, V> column, @Nullable V value) {
        String colName = validateUpdatableColumn(meta.resolveColumn(column));
        values.put(colName, value);
        return this;
    }

    public <V> EntityUpdate<T> setIfPresent(IGetter<T, V> column, @Nullable V value) {
        String colName = validateUpdatableColumn(meta.resolveColumn(column));
        if (value != null) {
            values.put(colName, value);
        }
        return this;
    }

    public EntityUpdate<T> set(String columnOrField, @Nullable Object value) {
        String colName = validateUpdatableColumn(meta.resolveColumnName(columnOrField));
        values.put(colName, value);
        return this;
    }

    public EntityUpdate<T> setIfPresent(String columnOrField, @Nullable Object value) {
        String colName = validateUpdatableColumn(meta.resolveColumnName(columnOrField));
        if (value != null) {
            values.put(colName, value);
        }
        return this;
    }

    public EntityUpdate<T> set(Map<String, ?> map) {
        Objects.requireNonNull(map, "values map must not be null");
        map.forEach(this::set);
        return this;
    }

    /** 按 Lambda 属性引用执行列级原子增减（渲染为 {@code col = col + ?}）。 */
    public <N extends Number> EntityUpdate<T> incr(IGetter<T, N> column, Number delta) {
        Objects.requireNonNull(delta, "delta must not be null");
        String colName = validateUpdatableColumn(meta.resolveColumn(column));
        values.put(colName, SqlNodes.add(
                SqlNodes.column((SqlNode.Table) null, colName),
                SqlNodes.param(colName, delta, ParameterSource.SET)));
        return this;
    }

    /** 按列名或字段名执行列级原子增减（渲染为 {@code col = col + ?}）。 */
    public EntityUpdate<T> incr(String columnOrField, Number delta) {
        Objects.requireNonNull(delta, "delta must not be null");
        String colName = validateUpdatableColumn(meta.resolveColumnName(columnOrField));
        values.put(colName, SqlNodes.add(
                SqlNodes.column((SqlNode.Table) null, colName),
                SqlNodes.param(colName, delta, ParameterSource.SET)));
        return this;
    }

    public <V> EntityUpdate<T> setExpr(IGetter<T, V> column, String sqlExpr, Object... args) {
        String colName = validateUpdatableColumn(meta.resolveColumn(column));
        values.put(colName, buildExprNode(colName, sqlExpr, args));
        return this;
    }

    public EntityUpdate<T> setExpr(String columnOrField, String sqlExpr, Object... args) {
        String colName = validateUpdatableColumn(meta.resolveColumnName(columnOrField));
        values.put(colName, buildExprNode(colName, sqlExpr, args));
        return this;
    }

    public EntityUpdate<T> where(UnaryOperator<WhereCriterion> condition) {
        Objects.requireNonNull(condition, "where condition must not be null");
        this.whereCallbacks.add(condition);
        return this;
    }

    public EntityUpdate<T> where(WhereCriterion criterion) {
        Objects.requireNonNull(criterion, "criterion must not be null");
        WhereCriterion snapshot = criterion.copy();
        this.explicitCriteria.add(snapshot);
        this.whereCallbacks.add(w -> w.and(inner -> snapshot.copy()));
        return this;
    }

    public EntityUpdate<T> allowAll() {
        this.allowAll = true;
        return this;
    }

    /** 构建未渲染的结构化 {@link AstStatement}（包内使用）。 */
    AstStatement toAstStatement() {
        if (values.isEmpty()) {
            throw new IllegalStateException("Update values must not be empty");
        }
        return UpdatePlan.buildExplicitAst(meta, values, buildWhere(), allowAll, kernel.namingPolicy());
    }

    /** 离线编译为不可变物理 {@link SqlStatement}。 */
    public SqlStatement toSqlStatement() {
        return SqlRenderer.render(toAstStatement(), kernel.namingPolicy());
    }

    /** 执行更新并返回 {@link MutationResult}（在线路径单趟编译）。 */
    public MutationResult execute() {
        long affected = kernel.update(toAstStatement());
        return MutationResult.ofAffected(affected);
    }

    private @Nullable WhereCriterion buildWhere() {
        if (explicitCriteria.size() == 1 && whereCallbacks.size() == 1) {
            return explicitCriteria.get(0).copy();
        }
        return WhereCriterion.merge(null, whereCallbacks);
    }

    private String validateUpdatableColumn(String colName) {
        return meta.requireUpdatableColumn(colName);
    }

    private static SqlNode buildExprNode(String colName, String sqlExpr, @Nullable Object[] args) {
        if (sqlExpr == null || sqlExpr.isBlank()) {
            throw new IllegalArgumentException("sqlExpr must not be null or blank");
        }
        String formatted = SqlFormatUtils.underlineColumn(sqlExpr.trim());
        formatted = SqlFormatUtils.getJdbcStyleSql(formatted);
        formatted = SqlFormatUtils.stripTableAlias(formatted);
        int expected = SqlFormatUtils.countPlaceholders(formatted);
        int actual = args == null ? 0 : args.length;
        if (expected != actual) {
            throw new IllegalArgumentException("Placeholder count mismatch in setExpr(\"" + sqlExpr
                    + "\"): expected " + expected + " parameter(s), got " + actual);
        }
        if (actual == 0) {
            return SqlNodes.raw(formatted);
        }
        List<SqlParameter> params = new ArrayList<>(actual);
        for (int i = 0; i < actual; i++) {
            params.add(SqlParameter.of(i + 1, colName, args[i], ParameterSource.SET));
        }
        return SqlNodes.raw(formatted, params);
    }
}
