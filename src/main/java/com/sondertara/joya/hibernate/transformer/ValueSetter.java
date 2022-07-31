package com.sondertara.joya.hibernate.transformer;

import com.sondertara.joya.hibernate.transformer.mappedfileds.CollectionFields;
import com.sondertara.joya.hibernate.transformer.mappedfileds.Fields;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.PropertyAccessorFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.data.convert.Jsr310Converters;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.sondertara.joya.core.constant.JoyaConst.ALIAS_SPLIT;

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

    private final Map<String, Object> genericMap = new HashMap<>();

    public static BeanWrapper getBeanWrapper(Object instantiate) {
        BeanWrapper bw = PropertyAccessorFactory.forBeanPropertyAccess(instantiate);
        bw.setConversionService(conversionService);
        return bw;
    }

    public void set(Object instantiate, String alias, Object value, Map<String, Fields> mappedFields) {
        BeanWrapper beanWrapper = getBeanWrapper(instantiate);
        if (!alias.contains(ALIAS_SPLIT)) {
            Fields field = bestGuessFields(alias, mappedFields);
            if (null == field) {
                return;
            }
            field.setResultPropertyValue(beanWrapper, instantiate, alias, value);
            return;
        }


        int index = alias.indexOf(ALIAS_SPLIT);
        String alias2 = alias.substring(0, index);
        String remainAlias = alias.substring(index + 1);

        Fields field = bestGuessFields(alias, mappedFields);
        if (null == field) {
            return;
        }
        Object propertyValue = beanWrapper.getPropertyValue(field.getName());
        if (propertyValue == null) {
            propertyValue = field.instantiateObjectValue();
            field.setObjectPropertyValue(beanWrapper, propertyValue);
        }

        if (field.isMap()) {
            if (remainAlias.contains(ALIAS_SPLIT)) {
                throw new RuntimeException("Map对象结果只能嵌入一层，不能嵌入多层，alias:" + alias);
            }
            field.setResultPropertyValue(null, propertyValue, remainAlias, value);
            return;
        }
        set(propertyValue, remainAlias, value, field.getChildrenFields());
    }


    private Fields bestGuessFields(String aliasName, Map<String, Fields> mappedFields) {
        Fields fields = mappedFields.get(aliasName);
        if (null == fields) {
            Set<String> set = mappedFields.keySet().stream().filter(aliasName::equalsIgnoreCase).collect(Collectors.toSet());
            if (set.size() > 0) {
                return mappedFields.get(set.iterator().next());
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
