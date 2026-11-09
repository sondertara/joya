package com.sondertara.joya.core;

import com.sondertara.joya.core.function.IGetter;
import com.sondertara.joya.core.kernel.ExecutionKernel;
import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlFormatUtils;
import com.sondertara.joya.core.kernel.statement.SqlNode;
import com.sondertara.joya.core.kernel.statement.SqlNodes;
import com.sondertara.joya.core.kernel.statement.SqlParameter;
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
 * <p>仅更新通过 {@link #set} 显式声明的列，绝不会将实体中未声明的列覆盖为 {@code NULL}。
 * 默认要求非空 WHERE 条件，全表更新须显式调用 {@link #allowAll()}。
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

    /** 按 Lambda 属性引用设置待更新列。 */
    public <V> EntityUpdate<T> set(IGetter<T, V> column, @Nullable V value) {
        String colName = validateUpdatableColumn(meta.resolveColumn(column));
        values.put(colName, value);
        return this;
    }

    /** 当值非 {@code null} 时按 Lambda 属性引用设置待更新列。 */
    public <V> EntityUpdate<T> setIfPresent(IGetter<T, V> column, @Nullable V value) {
        if (value != null) {
            set(column, value);
        }
        return this;
    }

    /** 按列名或字段名设置待更新列。 */
    public EntityUpdate<T> set(String columnOrField, @Nullable Object value) {
        String colName = validateUpdatableColumn(meta.resolveColumnName(columnOrField));
        values.put(colName, value);
        return this;
    }

    /** 批量设置待更新列。 */
    public EntityUpdate<T> set(Map<String, ?> map) {
        Objects.requireNonNull(map, "values map must not be null");
        map.forEach(this::set);
        return this;
    }

    /**
     * 按 Lambda 属性引用执行列级原子增减（渲染为 {@code col = col + ?}，传入负数即为原子自减）。
     */
    public <N extends Number> EntityUpdate<T> incr(IGetter<T, N> column, Number delta) {
        Objects.requireNonNull(delta, "delta must not be null");
        String colName = validateUpdatableColumn(meta.resolveColumn(column));
        String formattedCol = meta.formatColumn(colName, kernel.namingPolicy());
        values.put(colName, new SqlNode.Binary("+",
                SqlNodes.column((SqlNode.Table) null, formattedCol),
                SqlNodes.param(colName, delta, ParameterSource.SET)));
        return this;
    }

    /**
     * 按列名或字段名执行列级原子增减（渲染为 {@code col = col + ?}，传入负数即为原子自减）。
     */
    public EntityUpdate<T> incr(String columnOrField, Number delta) {
        Objects.requireNonNull(delta, "delta must not be null");
        String colName = validateUpdatableColumn(meta.resolveColumnName(columnOrField));
        String formattedCol = meta.formatColumn(colName, kernel.namingPolicy());
        values.put(colName, new SqlNode.Binary("+",
                SqlNodes.column((SqlNode.Table) null, formattedCol),
                SqlNodes.param(colName, delta, ParameterSource.SET)));
        return this;
    }

    /**
     * 按 Lambda 属性引用设置参数化 SQL 表达式（如 {@code setExpr(User::getScore, "GREATEST(score - ?, 0)", 10)}）。
     */
    public <V> EntityUpdate<T> setExpr(IGetter<T, V> column, String sqlExpr, Object... args) {
        String colName = validateUpdatableColumn(meta.resolveColumn(column));
        values.put(colName, buildExprNode(colName, sqlExpr, args));
        return this;
    }

    /**
     * 按列名或字段名设置参数化 SQL 表达式。
     */
    public EntityUpdate<T> setExpr(String columnOrField, String sqlExpr, Object... args) {
        String colName = validateUpdatableColumn(meta.resolveColumnName(columnOrField));
        values.put(colName, buildExprNode(colName, sqlExpr, args));
        return this;
    }

    /** 追加 WHERE 过滤条件（多次调用以 AND 合并）。 */
    public EntityUpdate<T> where(UnaryOperator<WhereCriterion> condition) {
        Objects.requireNonNull(condition, "where condition must not be null");
        this.whereCallbacks.add(condition);
        return this;
    }

    /** 追加预构建的 {@link WhereCriterion}（多次调用以 AND 合并）。 */
    public EntityUpdate<T> where(WhereCriterion criterion) {
        Objects.requireNonNull(criterion, "criterion must not be null");
        WhereCriterion snapshot = criterion.copy();
        this.explicitCriteria.add(snapshot);
        this.whereCallbacks.add(w -> w.and(inner -> snapshot.copy()));
        return this;
    }

    /** 显式放行无 WHERE 条件的全表更新。 */
    public EntityUpdate<T> allowAll() {
        this.allowAll = true;
        return this;
    }

    /** 渲染为不可变 {@link SqlStatement}。 */
    public SqlStatement toSqlStatement() {
        if (values.isEmpty()) {
            throw new IllegalStateException("Update values must not be empty");
        }
        return UpdatePlan.explicit(meta, values, buildWhere(), allowAll).toSqlStatement(kernel);
    }

    /** 执行更新并返回 {@link MutationResult}。 */
    public MutationResult execute() {
        if (values.isEmpty()) {
            throw new IllegalStateException("Update values must not be empty");
        }
        return UpdatePlan.explicit(meta, values, buildWhere(), allowAll).execute(kernel);
    }

    private WhereCriterion buildWhere() {
        if (explicitCriteria.size() == 1 && whereCallbacks.size() == 1) {
            return explicitCriteria.get(0).copy();
        }
        return WhereCriterion.merge(null, whereCallbacks);
    }

    private String validateUpdatableColumn(String colName) {
        if (meta.nonUpdatableColumns().contains(colName)) {
            throw new IllegalArgumentException(
                    "Column '" + colName + "' is marked as updatable=false on entity " + meta.entityType().getName());
        }
        if (meta.generatedColumns().contains(colName)) {
            throw new IllegalArgumentException(
                    "Column '" + colName + "' is database-generated and cannot be updated on entity "
                            + meta.entityType().getName());
        }
        return colName;
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
