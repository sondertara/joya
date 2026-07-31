
package com.sondertara.joya.migration.provider.transform;

import com.sondertara.common.base.Named;
import com.sondertara.common.spi.ServiceUsed;

import java.util.List;

/**
 * 值映射转换器接口
 */
@ServiceUsed("mapped")
public interface RecordTransformProvider extends Named {

  String getTransformerName();

  Object[] doTransform(String sourceSchema, String sourceTable, List<String> sourceColumns, Object[] recordValue);
}
