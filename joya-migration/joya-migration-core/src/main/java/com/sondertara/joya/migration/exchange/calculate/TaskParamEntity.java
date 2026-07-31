
package com.sondertara.joya.migration.exchange.calculate;

import com.sondertara.joya.migration.provider.ProductFactoryProvider;
import com.sondertara.joya.migration.provider.transform.RecordTransformProvider;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 任务参数实体类定义
 *
 * @author tang
 */
@Data
@Builder
@AllArgsConstructor
public class TaskParamEntity {


    private ProductFactoryProvider sourceFactoryProvider;

    private ProductFactoryProvider targetFactoryProvider;

    /**
     * 老表的schema名
     */
    @NonNull
    private String sourceSchemaName;

    /**
     * 老表的table名
     */
    @NonNull
    private String sourceTableName;


    /**
     * 新表的schema名
     */
    @NonNull
    private String targetSchemaName;

    /**
     * 新表的table名
     */
    @NonNull
    private String targetTableName;
    /**
     * 字段列
     */
    private List<String> readSourceColumns;

    private List<String> writeTargetColumns;
    /**
     * 是否删除不存在的行,默认删除和源数据保持一致, 为false时不处理
     */
    private boolean deleteNotExistRow;
    /**
     * 字段名映射关系
     */
    @NonNull
    @Builder.Default
    private Map<String, String> columnsMap = Collections.emptyMap();

    /**
     * 值转换器
     */
    @NonNull
    private RecordTransformProvider transformer;
}
