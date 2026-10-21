package com.sondertara.joya.core;

import com.sondertara.common.function.IGetter;
import com.sondertara.joya.core.kernel.JoyaTemplate;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.metadata.MutationContext;
import com.sondertara.joya.core.query.criterion.WhereCriterion;
import com.sondertara.joya.core.result.MutationResult;
import com.sondertara.joya.core.statement.factory.UpdateStatementFactory;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    private final JoyaTemplate template;
    private final EntityMetadataContext meta;
    private final Map<String, Object> values = new LinkedHashMap<>();
    private final List<UnaryOperator<WhereCriterion>> whereCallbacks = new ArrayList<>();
    private WhereCriterion explicitCriterion;
    private boolean allowAll;

    EntityUpdate(JoyaTemplate template, EntityMetadataContext meta) {
        this.template = Objects.requireNonNull(template, "template must not be null");
        this.meta = Objects.requireNonNull(meta, "meta must not be null");
    }

    /** 按 Lambda 属性引用设置待更新列。 */
    public <V> EntityUpdate<T> set(IGetter<T, V> column, @Nullable V value) {
        String colName = EntityOps.resolveColumn(meta, column);
        values.put(colName, value);
        return this;
    }

    /** 按列名或字段名设置待更新列。 */
    public EntityUpdate<T> set(String columnOrField, @Nullable Object value) {
        String colName = EntityOps.resolveColumnName(meta, columnOrField);
        values.put(colName, value);
        return this;
    }

    /** 批量设置待更新列。 */
    public EntityUpdate<T> set(Map<String, ?> map) {
        Objects.requireNonNull(map, "values map must not be null");
        map.forEach(this::set);
        return this;
    }

    /** 追加 WHERE 过滤条件（多次调用以 AND 合并）。 */
    public EntityUpdate<T> where(UnaryOperator<WhereCriterion> condition) {
        Objects.requireNonNull(condition, "where condition must not be null");
        this.whereCallbacks.add(condition);
        return this;
    }

    /** 使用预构建的 {@link WhereCriterion}。 */
    public EntityUpdate<T> where(WhereCriterion criterion) {
        this.explicitCriterion = Objects.requireNonNull(criterion, "criterion must not be null");
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
        WhereCriterion where = buildWhere();
        Set<String> declared = new LinkedHashSet<>(values.keySet());
        MutationContext ctx = new MutationContext(meta, values, where, declared, false, allowAll, template.settings());
        return UpdateStatementFactory.create(ctx);
    }

    /** 执行更新并返回 {@link MutationResult}。 */
    public MutationResult execute() {
        int affected = template.update(toSqlStatement());
        return MutationResult.ofAffected(affected);
    }

    private WhereCriterion buildWhere() {
        if (explicitCriterion == null && whereCallbacks.isEmpty()) {
            return null;
        }
        WhereCriterion w = explicitCriterion != null
                ? explicitCriterion
                : WhereCriterion.forEntity(meta.entityType());
        for (UnaryOperator<WhereCriterion> cb : whereCallbacks) {
            cb.apply(w);
        }
        return w;
    }
}
