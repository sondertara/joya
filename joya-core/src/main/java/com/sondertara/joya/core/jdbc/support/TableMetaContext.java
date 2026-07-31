package com.sondertara.joya.core.jdbc.support;

import com.sondertara.common.collection.LinkedCaseInsensitiveMap;
import com.sondertara.common.base.StringUtils;
import com.sondertara.common.reflect.ReflectUtils;
import com.sondertara.joya.core.JoyaJdbc;
import com.sondertara.joya.core.cache.CacheHolder;
import com.sondertara.joya.core.jdbc.support.listener.EntityListener;
import com.sondertara.joya.core.model.EntityTableMetaDefinition;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * @author huangxiaohu
 */
public class TableMetaContext implements Serializable {

    private LinkedCaseInsensitiveMap<Object> nullToDefaults;
    @Getter
    private EntityTableMetaDefinition metaDefinition;
    private LinkedHashMap<String, Object> data = new LinkedHashMap<>();
    @Setter
    @Getter
    private Object entity;
    @Getter
    private Class<?> entityClass;

    @Setter
    @Getter
    private List<String> preparedParameterNames;

    private boolean propertyNullable = true;
    private volatile boolean resolved = false;

    public TableMetaContext() {

    }

    TableMetaContext(EntityTableMetaDefinition metaDefinition, @Nullable Object entity) {
        this.metaDefinition = metaDefinition;
        this.entity = entity;
    }

    public static <T> TableMetaContext of(T bean) {
        EntityTableMetaDefinition definition = CacheHolder.tableMetaCache().findByEntityClass(bean.getClass()).orElseThrow(() -> new IllegalStateException("The class is not table entity:" + bean.getClass()));

        return new TableMetaContext(definition, bean);

    }

    public static <T> TableMetaContext of(T bean, Map<String, Object> nullToDefaults) {
        EntityTableMetaDefinition definition = CacheHolder.tableMetaCache().findByEntityClass(bean.getClass()).orElseThrow(() -> new IllegalStateException("The class is not table entity:" + bean.getClass()));
        TableMetaContext context = new TableMetaContext(definition, bean);
        context.setNullToDefaults(nullToDefaults);
        return context;

    }

    public static <T> TableMetaContext of(Class<T> entityClass) {
        Objects.requireNonNull(entityClass);
        EntityTableMetaDefinition definition = CacheHolder.tableMetaCache().findByEntityClass(entityClass).orElseThrow(() -> new IllegalStateException("The class is not table entity:" + entityClass));
        return new TableMetaContext(definition, null);

    }

    public void handleEntityListener(Consumer<EntityListener> consumer) {
        if (isEntityListener()) {
            consumer.accept((EntityListener) entity);
            initData();
        }
    }

    public void propertyNotNull() {
        this.propertyNullable = false;
    }

    void setNullToDefaults(Map<String, Object> nullToDefaults) {
        this.nullToDefaults = new LinkedCaseInsensitiveMap<>(nullToDefaults);
    }

    public Object getPrimaryKeyValue() {
        return getData().get(metaDefinition.getPrimaryKey());
    }

    public String getTableName() {
        if (StringUtils.isBlank(this.metaDefinition.getSchemaName())) {
            return this.metaDefinition.getTableName();
        }

        return String.format("%s.%s", this.metaDefinition.getSchemaName(), this.metaDefinition.getTableName());
    }

    public String getPrimaryKeyName() {
        return metaDefinition.getPrimaryKey();
    }

    public Class<?> getPrimaryKeyType() {
        return metaDefinition.getPrimaryKeyType();
    }

    public LinkedHashMap<String, Object> getData() {
        //first time
        if (resolved) {
            return data;
        }
        initData();
        return data;
    }

    public Object getColumnValue(String columnName) {
        return this.data.get(columnName);
    }

    public synchronized void initData() {
        if (resolved) {
            return;
        }
        if (null != entity) {
            if (this.data == null) {
                this.data = new LinkedHashMap<>();
            } else {
                this.data.clear();
            }


            metaDefinition.getColumnFiledPairs().forEach((key, value) -> {
                Object o = ReflectUtils.getFieldValue(entity, value);
                if (null == o && propertyNullable) {
                    if (null == this.nullToDefaults) {
                        data.put(key, null);
                    } else {
                        Object o1 = nullToDefaults.get(value.getName());
                        if (null == o1) {
                            o1 = nullToDefaults.get(value.getType().getName());
                        }
                        data.put(key, o1);
                    }
                } else {
                    data.put(key, o);
                }
            });
        }
        resolved = true;
    }

    public boolean isEntityListener() {
        return null != entity && entity instanceof EntityListener;
    }

    @Override
    public String toString() {
        return "TableMetaContext{" +
                "metaDefinition=" + metaDefinition +
                ", data=" + data +
                '}';
    }
}
