
package com.sondertara.joya.migration.provider.transform;


import com.sondertara.joya.migration.provider.ProductFactoryProvider;

import java.util.List;

/**
 * 默认的转换器(无转换)
 */
public class DefaultTransformProvider implements RecordTransformProvider {

    private ProductFactoryProvider factoryProvider;

    public DefaultTransformProvider(ProductFactoryProvider factoryProvider) {
        this.factoryProvider = factoryProvider;
    }

    protected ProductFactoryProvider getFactoryProvider() {
        return this.factoryProvider;
    }

    @Override
    public String getTransformerName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public Object[] doTransform(String sourceSchema, String sourceTable, List<String> sourceColumns, Object[] recordValue) {
        return recordValue;
    }

    @Override
    public String getName() {
        return "default";
    }
}
