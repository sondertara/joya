package com.sondertara.joya.core.jdbc.resultset.mapper;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sondertara.common.function.TypeConverter;
import com.sondertara.common.function.TypedConverter;
import com.sondertara.common.reflect.ReflectUtils;
import com.sondertara.common.text.CharUtils;
import com.sondertara.common.base.StringUtils;
import com.sondertara.common.collection.BiMap;
import com.sondertara.common.collection.CollectionUtils;
import com.sondertara.common.collection.LinkedCaseInsensitiveMap;
import com.sondertara.common.reflect.ClassUtils;
import com.sondertara.common.reflect.PropertyUtils;
import com.sondertara.common.text.VariableStyles;
import com.sondertara.joya.core.JoyaJdbc;
import com.sondertara.joya.core.jdbc.resultset.FieldAlias;
import com.sondertara.joya.core.cache.CacheHolder;
import com.sondertara.joya.core.model.EntityTableMetaDefinition;
import com.sondertara.joya.exceptions.DbException;
import com.sondertara.joya.utils.SqlDataUtils;
import com.sondertara.joya.utils.SqlFormatUtils;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 将一行数据转换成JavaBean
 *
 * @author huangxiaohu
 */

@Slf4j
public class BeanRowMapper<T> implements RowMapper<T> {
    private final Class<T> type;

    private LinkedCaseInsensitiveMap<PropertyDescriptor> mappedFields;

    @Nullable
    private LinkedCaseInsensitiveMap<String> columnPropertyAlias = null;

    private Map<String, TypeConverter> fieldTypeConverterMap;

    @Nullable
    private EntityTableMetaDefinition metaDefinition;

    public BeanRowMapper(Class<T> type) {
        this.type = type;
        initialize();
    }


    private void initialize() {
        this.mappedFields = new LinkedCaseInsensitiveMap<>();
        this.fieldTypeConverterMap = new HashMap<>();
        Collection<PropertyDescriptor> collection = PropertyUtils.getPropertyDescriptors(type);
        collection.forEach(pd -> {
            if (pd.getWriteMethod() != null) {
                this.mappedFields.put(pd.getName(), pd);
            }

        });
        if (type.isAnnotationPresent(Table.class) || type.isAnnotationPresent(Entity.class)) {
            metaDefinition = CacheHolder.tableMetaCache().findByEntityClass(type).orElseThrow(() -> new IllegalStateException("No table entity class found for class:" + type));
        } else if (!SqlFormatUtils.isPrimitive(type)) {
            try {
                LinkedCaseInsensitiveMap<String> tmp = new LinkedCaseInsensitiveMap<>();
                LinkedCaseInsensitiveMap<String> aliasTmpMap = new LinkedCaseInsensitiveMap<>();
                List<Field> fields = ClassUtils.getFieldsByCache(type);
                for (Field field : fields) {
                    JsonProperty annotation = field.getAnnotation(JsonProperty.class);
                    if (null != annotation) {
                        if (StringUtils.isNotBlank(annotation.value())) {
                            tmp.put(annotation.value().toLowerCase(), field.getName());
                        }
                    } else if (field.isAnnotationPresent(FieldAlias.class)) {
                        FieldAlias fieldAlias = field.getAnnotation(FieldAlias.class);
                        TypedConverter typeConverter = ReflectUtils.newInstance(fieldAlias.converter());
                        fieldTypeConverterMap.put(field.getName(), typeConverter);
                        for (String s : fieldAlias.value()) {
                            aliasTmpMap.put(s.trim(), field.getName());
                        }

                    } else {
                        Column column = field.getAnnotation(Column.class);
                        if (null != column) {
                            if (StringUtils.isNotBlank(column.name())) {
                                tmp.put(column.name().toLowerCase(), field.getName());
                            }
                        }
                    }
                }
                tmp.putAll(aliasTmpMap);
                if (!tmp.isEmpty()) {
                    this.columnPropertyAlias = tmp;
                }
            } catch (Exception e) {
                log.error("Get fields for class[{}] error", type, e);
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public T map(ResultSet row) {
        try {
            if (log.isDebugEnabled()) {
                log.debug("BeanRowMapper#convert start...");
            }
            ResultSetMetaData metaData = row.getMetaData();
            int count = metaData.getColumnCount();
            if (count < 1) {
                return null;
            }
            if (Blob.class == type) {
                return (T) row.getBlob(1);
            } else if (Clob.class == type) {
                return (T) row.getClob(1);
            }

            if (Date.class.isAssignableFrom(type)) {
                return (T) row.getDate(1);
            }
            if (this.mappedFields.isEmpty()) {
                return (T) setSingleField(row);
            }
            T bean = type.getDeclaredConstructor().newInstance();
            for (int i = 1; i <= count; i++) {
                String columnName = SqlDataUtils.lookupColumnName(metaData, i);
                //if the name has alias
                if (columnName.contains(StringUtils.DOT)) {
                    columnName = StringUtils.subAfter(columnName, CharUtils.DOT, true);
                }

                //not property found for bean field
                String bestGuessProperty = bestGuessProperty(columnName);
                if (null == bestGuessProperty) {
                    continue;
                }
                PropertyDescriptor pd = this.mappedFields.get(bestGuessProperty);
                if (pd == null) {
                    continue;
                }
                Class<?> propertyType = pd.getPropertyType();
                Object value = SqlDataUtils.getResultSetValue(row, i, propertyType);
                if (fieldTypeConverterMap.containsKey(pd.getName())) {
                    value = fieldTypeConverterMap.get(pd.getName()).apply(value);
                }
                Method setter = pd.getWriteMethod();
                if (value != null && null != setter) {
                    try {
                        setter.invoke(bean, value);
                    } catch (Exception e) {
                        log.error("Invoke method[{}] value[{}] type[{}] error:{}", setter.getName(), value, value.getClass().getName(), e.getMessage(), e);
                        throw new DbException(e);
                    }
                }
            }
            return bean;
        } catch (Exception e) {
            if (e instanceof DbException) {
                throw (DbException) e;
            }
            throw new DbException(e.getMessage(), e);
        }
    }

    private String bestGuessProperty(String aliasName) {
        String propertyName = null;
        //1.判断是否为entity
        if (null != metaDefinition) {
            //如果是下划线
            if (aliasName.contains(String.valueOf(CharUtils.UNDERLINE))) {
                propertyName = metaDefinition.findFieldNameByColumn(aliasName);
            } else {
                //不是下划线，寻找属性名
                if (metaDefinition.containFiled(aliasName)) {
                    propertyName = aliasName;
                } else {
                    propertyName = metaDefinition.getFieldNames().stream().filter(s -> s.equalsIgnoreCase(aliasName)).findFirst().orElse(null);
                }
            }
        }
        //2.普通pojo
        if (null == propertyName) {
            propertyName = aliasName;
            if (aliasName.contains(String.valueOf(CharUtils.UNDERLINE))) {
                propertyName = VariableStyles.SNAKE.toCamelCase(aliasName);
            }
        }
        if (this.mappedFields.containsKey(propertyName)) {
            return propertyName;
        }
        //2.1模糊匹配
        for (Map.Entry<String, PropertyDescriptor> entry : this.mappedFields.entrySet()) {
            if (propertyName.equalsIgnoreCase(entry.getKey())) {
                propertyName = entry.getKey();
                break;
            }
        }

        //3.判断普通 pojo是否设置别名
        if (null == propertyName && CollectionUtils.isNotEmpty(this.columnPropertyAlias)) {
            propertyName = this.columnPropertyAlias.get(aliasName);
            if (null == propertyName) {
                for (Map.Entry<String, String> entry : this.columnPropertyAlias.entrySet()) {
                    if (aliasName.equalsIgnoreCase(entry.getKey()) || aliasName.equalsIgnoreCase(entry.getValue())) {
                        propertyName = entry.getValue();
                        break;
                    }
                }
            }
        }
        return propertyName;
    }

    /**
     * set single value
     *
     * @param resultSet value
     * @return value after transformed
     */
    private Object setSingleField(ResultSet resultSet) throws SQLException {

        if (SqlFormatUtils.isPrimitive(type)) {
            if (Character.class.isAssignableFrom(type)) {
                throw new IllegalArgumentException("Not support Character!");
            }
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                try {
                    int count = constructor.getParameterCount();
                    if (count == 1) {
                        if (String.class.isAssignableFrom(constructor.getParameterTypes()[0])) {

                            if (Objects.isNull(resultSet.getObject(1))) {
                                return null;
                            } else {
                                return constructor.newInstance(resultSet.getObject(1).toString());
                            }
                        }
                    }
                } catch (InstantiationException | IllegalAccessException | InvocationTargetException | SQLException e) {
                    throw new DbException(e);
                }
            }
        }
        if (String.class.equals(type)) {
            if (Objects.isNull(resultSet.getString(1))) {
                return null;
            } else {
                return resultSet.getString(1);
            }
        }
        return SqlDataUtils.getResultSetValue(resultSet, 1, type);

    }


}
