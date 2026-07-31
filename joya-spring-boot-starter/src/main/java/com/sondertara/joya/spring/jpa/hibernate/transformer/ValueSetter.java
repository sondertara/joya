package com.sondertara.joya.spring.jpa.hibernate.transformer;


import com.sondertara.common.collection.CollectionUtils;
import com.sondertara.common.text.CharUtils;
import com.sondertara.common.base.StringUtils;
import com.sondertara.common.collection.LinkedCaseInsensitiveMap;
import com.sondertara.common.text.VariableStyles;
import com.sondertara.joya.core.JoyaJdbc;
import com.sondertara.joya.core.cache.CacheHolder;
import com.sondertara.joya.core.model.EntityTableMetaDefinition;
import com.sondertara.joya.spring.jpa.hibernate.transformer.mappedfileds.CollectionFields;
import com.sondertara.joya.spring.jpa.hibernate.transformer.mappedfileds.Fields;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.PropertyAccessorFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.data.convert.Jsr310Converters;

import javax.persistence.Entity;
import javax.persistence.Table;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;


/**
 * @author huangxiaohu
 */
public class ValueSetter {

    protected static DefaultConversionService conversionService;

    static {
        ValueSetter.conversionService = new DefaultConversionService();
        Collection<Converter<?, ?>> convertersToRegister = Jsr310Converters.getConvertersToRegister();
        for (Converter<?, ?> converter : convertersToRegister) {
            ValueSetter.conversionService.addConverter(converter);
        }
    }

    @Nullable
    private LinkedCaseInsensitiveMap<String> columnPropertyAlias = null;
    private final Map<String, Object> genericMap = new HashMap<>();

    public void setColumnPropertyAlias(@Nullable LinkedCaseInsensitiveMap<String> propertyAlias) {
        this.columnPropertyAlias = propertyAlias;
    }

    public static BeanWrapper getBeanWrapper(Object instantiate) {
        BeanWrapper bw = PropertyAccessorFactory.forBeanPropertyAccess(instantiate);
        bw.setConversionService(conversionService);
        return bw;
    }

    public void set(Object instantiate, String alias, Object value, Map<String, Fields> mappedFields) {
        BeanWrapper beanWrapper = getBeanWrapper(instantiate);
        //没有其他别名
        if (!alias.contains(StringUtils.DOT)) {
            Fields field = bestGuessFields(beanWrapper, alias, mappedFields);
            if (null == field) {
                return;
            }
            field.setResultPropertyValue(beanWrapper, instantiate, alias, value);
            return;
        }

        //存在别名
        int index = alias.indexOf(StringUtils.DOT);
        String remainAlias = alias.substring(index + 1);

        Fields field = bestGuessFields(beanWrapper, remainAlias, mappedFields);
        if (null == field) {
            return;
        }
        Object propertyValue = beanWrapper.getPropertyValue(field.getName());
        if (propertyValue == null) {
            propertyValue = field.instantiateObjectValue();
            field.setObjectPropertyValue(beanWrapper, propertyValue);
        }

        if (field.isMap()) {
            if (remainAlias.contains(StringUtils.DOT)) {
                throw new RuntimeException("Map对象结果只能嵌入一层，不能嵌入多层，alias:" + alias);
            }
            field.setResultPropertyValue(null, propertyValue, remainAlias, value);
            return;
        }
        set(propertyValue, remainAlias, value, field.getChildrenFields());
    }


    private Fields bestGuessFields(final BeanWrapper beanWrapper, String aliasName, Map<String, Fields> mappedFields) {
        Fields fields = null;
        //如果是下划线
        boolean camelCase = true;
        if (aliasName.contains(String.valueOf(CharUtils.UNDERLINE))) {
            camelCase = false;
            String s = VariableStyles.SNAKE.toCamelCase(aliasName);
            fields = mappedFields.get(s);
        } else {
            fields = mappedFields.get(aliasName);
        }
        if (null == fields) {
            String name = null;
            //是否为Entity
            Class<?> wrappedClass = beanWrapper.getWrappedClass();
            if (wrappedClass.isAnnotationPresent(Table.class) || wrappedClass.isAnnotationPresent(Entity.class)) {
                EntityTableMetaDefinition metaDefinition = CacheHolder.tableMetaCache().findByEntityClass(wrappedClass).get();

                if (camelCase) {
                    name=metaDefinition.getFieldNames().stream().filter(s->s.equalsIgnoreCase(aliasName)).findFirst().orElse(null);
                } else {
                    name = metaDefinition.findFieldNameByColumn(aliasName);
                }
                if (null != name) {
                    fields = mappedFields.get(name);
                }
            }
            if (null == fields && CollectionUtils.isNotEmpty(this.columnPropertyAlias)) {
                if (camelCase) {
                    for (Map.Entry<String, String> entry : this.columnPropertyAlias.entrySet()) {
                        if (StringUtils.toUnderlineCase(aliasName).equalsIgnoreCase(entry.getKey()) || aliasName.equalsIgnoreCase(entry.getValue())) {
                            name = entry.getValue();
                            break;
                        }
                    }
                } else {
                    name = this.columnPropertyAlias.get(aliasName);
                }
            }
            if (null != name) {
                fields = mappedFields.get(name);
            }
        }
        return fields;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object getGenericObject(Object propertyValue, CollectionFields collectionFields) {
        String name = collectionFields.getName();
        Object o = this.genericMap.get(name);
        if (o != null) {
            return o;
        }
        Collection collection = (Collection) propertyValue;
        Object instantiate = collectionFields.instantiateGenericObject();
        collection.add(instantiate);
        this.genericMap.put(name, instantiate);
        return instantiate;
    }

    public void clearGenericMap() {
        this.genericMap.clear();
    }


}
