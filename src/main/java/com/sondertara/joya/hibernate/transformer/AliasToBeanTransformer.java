package com.sondertara.joya.hibernate.transformer;

import com.sondertara.common.util.CollectionUtils;
import com.sondertara.common.util.StringUtils;
import com.sondertara.joya.core.jdbc.SqlDataHelper;
import com.sondertara.joya.hibernate.transformer.mappedfileds.Fields;
import oracle.sql.TIMESTAMP;
import org.hibernate.transform.ResultTransformer;
import org.springframework.beans.BeanUtils;

import java.lang.reflect.Constructor;
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
    }


    @Override
    public Object transformTuple(Object[] tuple, String[] aliases) {
        if (CollectionUtils.isEmpty(mappedFields)) {
            if (isPrimitive(mappedClass) || String.class.isAssignableFrom(mappedClass) || Date.class.isAssignableFrom(mappedClass)) {
                return setSingleField(tuple);

            }
        }
        T mappedObject = BeanUtils.instantiateClass(mappedClass);

        for (int i = 0; i < aliases.length; i++) {
            String trim = aliases[i].trim();
            String alias = trim.contains("_") ? StringUtils.toCamelCase(trim) : trim;
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
        if (isPrimitive(mappedClass)) {
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
            if (value instanceof TIMESTAMP) {
                return SqlDataHelper.extractDate(value);
            }
            return value;
        }

        throw new RuntimeException(" Can not transfer for class " + mappedClass);

    }

    private static boolean isPrimitive(Class<?> propertyType) {

        if (propertyType.isPrimitive()) {
            return true;
        }
        if (Number.class.isAssignableFrom(propertyType)) {
            return true;
        }
        try {
            return ((Class<?>) propertyType.getField("TYPE").get(null)).isPrimitive();
        } catch (Exception e) {
            return false;
        }
    }

}
