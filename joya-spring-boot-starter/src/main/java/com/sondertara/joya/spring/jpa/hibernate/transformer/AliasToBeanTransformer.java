package com.sondertara.joya.spring.jpa.hibernate.transformer;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sondertara.common.base.StringUtils;
import com.sondertara.common.collection.CollectionUtils;
import com.sondertara.common.collection.LinkedCaseInsensitiveMap;
import com.sondertara.common.reflect.ClassUtils;
import com.sondertara.common.reflect.ReflectUtils;
import com.sondertara.joya.spring.jpa.hibernate.transformer.mappedfileds.Fields;
import com.sondertara.joya.utils.SqlFormatUtils;
import org.hibernate.transform.ResultTransformer;
import org.springframework.beans.BeanUtils;

import javax.persistence.Column;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;


/**
 * bean transformer
 *
 * @author huangxiaohu
 */
public class AliasToBeanTransformer<T> implements ResultTransformer {

    /**
     *
     */
    private final Class<T> mappedClass;
    private final Map<String, Fields> mappedFields;

    private final ValueSetter valueSetter;

    public AliasToBeanTransformer(Class<T> mappedClass) {
        this.mappedClass = mappedClass;
        MappedFieldsInitializer mappedFieldsInitializer = new MappedFieldsInitializer();
        this.mappedFields = mappedFieldsInitializer.init(mappedClass);
        this.valueSetter = new ValueSetter();
        initProperty();
    }


    void initProperty() {
        if (SqlFormatUtils.isPrimitive(mappedClass)) {
            return;
        }
        try {
            LinkedCaseInsensitiveMap<String> tmp = new LinkedCaseInsensitiveMap<>();
            List<Field> fields = ClassUtils.getFieldsByCache(mappedClass);
            for (Field field : fields) {
                JsonProperty annotation = field.getAnnotation(JsonProperty.class);
                if (null != annotation) {
                    if (StringUtils.isNotBlank(annotation.value())) {
                        tmp.put(annotation.value().toLowerCase(), field.getName());
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
            if (!tmp.isEmpty()) {
                this.valueSetter.setColumnPropertyAlias(tmp);
            }
        } catch (Exception ignored) {

        }
    }


    @Override
    public Object transformTuple(Object[] tuple, String[] aliases) {
        if (CollectionUtils.isEmpty(mappedFields)) {
            if (SqlFormatUtils.isPrimitive(mappedClass) || String.class.isAssignableFrom(mappedClass) || Date.class.isAssignableFrom(mappedClass)) {
                return setSingleField(tuple);

            }
        }
        T mappedObject = BeanUtils.instantiateClass(mappedClass);

        for (int i = 0; i < aliases.length; i++) {
            String alias = aliases[i].trim();
            Object value = tuple[i];
            valueSetter.set(mappedObject, alias, value, this.mappedFields);
        }
        valueSetter.clearGenericMap();
        return mappedObject;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public List transformList(List list) {
        return list;
    }

    public final Class<?> getMappedClass() {
        return this.mappedClass;
    }

    /**
     * set single value
     *
     * @param tuple value
     * @return value after transformed
     */
    private Object setSingleField(Object[] tuple) {
        if (tuple.length < 1) {
            return null;
        }
        Object value = tuple[0];
        if (SqlFormatUtils.isPrimitive(mappedClass)) {
            if (Character.class.isAssignableFrom(mappedClass)) {
                throw new IllegalArgumentException("Not support Character!");
            }
            for (Constructor<?> constructor : mappedClass.getDeclaredConstructors()) {
                try {
                    int count = constructor.getParameterCount();
                    if (count == 1) {
                        if (String.class.isAssignableFrom(constructor.getParameterTypes()[0])) {

                            if (Objects.isNull(value)) {
                                return null;
                            } else {
                                return constructor.newInstance(value.toString());
                            }
                        }
                    }
                } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        if (String.class.equals(mappedClass)) {
            if (Objects.isNull(value)) {
                return null;
            } else {
                return value.toString();
            }
        }

        if (Date.class.equals(mappedClass)) {
            //Oracle
            if ("oracle.sql.TIMESTAMP".equals(value.getClass().getName())) {
                return ReflectUtils.invoke(value, "timestampValue");
            }
            return value;
        }

        throw new RuntimeException(" Can not transfer for class " + mappedClass);

    }

}
