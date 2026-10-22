package com.sondertara.joya.core;

import com.sondertara.joya.core.kernel.JoyaTemplate;
import com.sondertara.joya.core.kernel.statement.BatchSqlStatement;
import com.sondertara.joya.core.metadata.EntityMetadataContext;
import com.sondertara.joya.core.result.MutationResult;
import com.sondertara.joya.utils.ConvertUtils;

import java.util.List;

/**
 * 数据库生成主键回写器：负责单行与批量插入后的自增主键类型转换与实体字段回写。
 */
final class GeneratedKeyWriter {

    private GeneratedKeyWriter() {
    }

    static void writeBackPrimaryKey(EntityMetadataContext meta, Object entity, Object generatedKey) {
        String pkCol = meta.primaryKey();
        if (pkCol == null || generatedKey == null || entity == null) {
            return;
        }
        try {
            Class<?> pkType = meta.primaryKeyType();
            Object converted = (pkType != null && !pkType.isInstance(generatedKey))
                    ? ConvertUtils.convert(generatedKey, pkType)
                    : generatedKey;
            meta.writeFieldValue(entity, pkCol, converted);
        } catch (Exception ignored) {
            // 若实体无对应可写字段或转换失败，保持静默，调用方仍可从 MutationResult 获取生成键
        }
    }

    static <T> MutationResult executeInsertBatchWithKeys(
            JoyaTemplate template,
            EntityMetadataContext meta,
            List<T> rows,
            List<String> columns,
            boolean returnGeneratedKeys) {
        if (rows == null || rows.isEmpty()) {
            return MutationResult.EMPTY;
        }
        BatchSqlStatement batch = BatchPlanner.buildInsertBatch(template, meta, rows, columns, returnGeneratedKeys);
        MutationResult res = BatchPlanner.executeBatch(template, batch);
        if (returnGeneratedKeys && res.hasGeneratedKeys()) {
            List<Object> keys = res.generatedKeys();
            int limit = Math.min(rows.size(), keys.size());
            for (int i = 0; i < limit; i++) {
                writeBackPrimaryKey(meta, rows.get(i), keys.get(i));
            }
        }
        return res;
    }
}
