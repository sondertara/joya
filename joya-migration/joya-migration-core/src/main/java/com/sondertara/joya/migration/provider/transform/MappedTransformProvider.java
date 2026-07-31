
package com.sondertara.joya.migration.provider.transform;

import com.sondertara.joya.migration.provider.ProductFactoryProvider;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 支持指定转换字典的转换器
 */
public class MappedTransformProvider extends DefaultTransformProvider {

    private ColumnValueDataMapTable valueDataMap;

    public MappedTransformProvider(ProductFactoryProvider factoryProvider) {
        super(factoryProvider);
        this.valueDataMap = new ColumnValueDataMapTable();
    }

    @Override
    public String getTransformerName() {
        return this.getClass().getSimpleName();
    }

    public void setValueDataMap(ColumnValueDataMapTable valueDataMap) {
        this.valueDataMap = Objects.requireNonNull(valueDataMap, "valueDataMap is null");
    }

    @Override
    public Object[] doTransform(String schema, String table, List<String> fieldNames, Object[] recordValue) {
        if (!valueDataMap.isEmpty()) {
            for (int i = 0; i < fieldNames.size(); ++i) {
                String column = fieldNames.get(i);
                Map<String, String> mapper = valueDataMap.get(schema, table, column);
                if (null != mapper) {
                    String origin = String.valueOf(recordValue[i]);
                    if (mapper.containsKey(origin)) {
                        recordValue[i] = mapper.get(origin);
                    }
                }
            }
        }
        return recordValue;
    }

    @Override
    public String getName() {
        return "mapped";
    }
}
