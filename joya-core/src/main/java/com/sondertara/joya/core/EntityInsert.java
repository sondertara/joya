package com.sondertara.joya.core;

import com.sondertara.joya.core.function.IGetter;
import com.sondertara.joya.core.kernel.JoyaTemplate;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.metadata.EntitySnapshot;
import com.sondertara.joya.core.statement.MutationContext;
import com.sondertara.joya.core.result.MutationResult;
import com.sondertara.joya.core.statement.factory.InsertStatementFactory;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 实体链式插入构建器 —— 由 {@link EntityOps#insert()} 创建。
 *
 * @param <T> 实体类型
 */
public final class EntityInsert<T> {

    private final JoyaTemplate template;
    private final EntityMetadataContext meta;
    private final Map<String, Object> values = new LinkedHashMap<>();
    private boolean ignoreNull;

    EntityInsert(JoyaTemplate template, EntityMetadataContext meta) {
        this.template = Objects.requireNonNull(template, "template must not be null");
        this.meta = Objects.requireNonNull(meta, "meta must not be null");
    }

    /** 按 Lambda 属性引用设置列值。 */
    public <V> EntityInsert<T> value(IGetter<T, V> column, @Nullable V value) {
        String colName = EntityOps.resolveColumn(meta, column);
        values.put(colName, value);
        return this;
    }

    /** 按列名或字段名设置列值。 */
    public EntityInsert<T> value(String columnOrField, @Nullable Object value) {
        String colName = EntityOps.resolveColumnName(meta, columnOrField);
        values.put(colName, value);
        return this;
    }

    /** 批量设置列值。 */
    public EntityInsert<T> values(Map<String, ?> map) {
        Objects.requireNonNull(map, "values map must not be null");
        map.forEach(this::value);
        return this;
    }

    /** 从实体快照提取列值（若主键为 null 则自动跳过自增主键列）。 */
    public EntityInsert<T> values(T entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        EntitySnapshot snapshot = EntitySnapshot.snapshot(meta, entity);
        String pk = meta.primaryKey();
        for (Map.Entry<String, Object> entry : snapshot.values().entrySet()) {
            if (entry.getKey().equals(pk) && entry.getValue() == null) {
                continue;
            }
            values.put(entry.getKey(), entry.getValue());
        }
        return this;
    }

    /** 忽略值为 null 的列（交由数据库默认值填充）。 */
    public EntityInsert<T> ignoreNulls() {
        this.ignoreNull = true;
        return this;
    }

    /** 渲染为不可变 {@link SqlStatement}。 */
    public SqlStatement toSqlStatement() {
        if (values.isEmpty()) {
            throw new IllegalStateException("Insert values must not be empty");
        }
        Set<String> declared = new LinkedHashSet<>(values.keySet());
        MutationContext ctx = new MutationContext(meta, values, null, declared, ignoreNull, false, template.namingPolicy());
        return InsertStatementFactory.create(ctx);
    }

    /** 执行插入并返回包含生成键与影响行数的 {@link MutationResult}。 */
    public MutationResult execute() {
        Object generatedKey = template.insert(toSqlStatement());
        return MutationResult.ofInsert(1, generatedKey);
    }
}
