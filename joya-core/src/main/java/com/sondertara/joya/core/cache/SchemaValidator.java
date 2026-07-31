package com.sondertara.joya.core.cache;

import com.sondertara.joya.core.constant.JoyaConfig;
import com.sondertara.joya.core.constant.SchemaValidation;
import com.sondertara.joya.core.jdbc.support.meta.SchemaTableDescription;
import com.sondertara.joya.core.model.EntityTableMetaDefinition;
import com.sondertara.joya.exceptions.DbException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entity ↔ DB schema 校验器
 * <p>
 * 首次访问某个 entity 的表时执行一次懒校验，后续不再重复。
 * 校验策略由 {@link JoyaConfig#getSchemaValidation()} 控制：
 * <ul>
 *   <li>NONE（默认）— 不执行校验，零开销</li>
 *   <li>WARN — 不一致时日志 warn</li>
 *   <li>THROW — 不一致时抛 {@link DbException}</li>
 * </ul>
 * <p>
 * DB 元数据由 {@link CacheableDatabaseMetaProvider} 提供，按 datasource+table 缓存。
 * 调用方传入 Connection 以保证同一数据源的校验同步。
 *
 * @author sondertara
 */
public final class SchemaValidator {

    private static final Logger log = LoggerFactory.getLogger(SchemaValidator.class);

    /**
     * key = tableName，value = Boolean.TRUE 表示"已校验通过"、Boolean.FALSE 表示"校验过但 drifted"
     * 既然保留 FALSE 而非仅在 THROW 时无值，是因为 WARN 模式下只要校验过一次就不重入。
     */
    private static final Map<String, Boolean> VALIDATED = new ConcurrentHashMap<>();

    private SchemaValidator() {
    }

    /**
     * 执行 schema 校验（仅 NONE 以外模式 + 该表尚未校验时触发）。
     *
     * @param conn   当前 JDBC 连接（用于差异化标识数据源；若为 null 则仅按 tableName）
     * @param entity entity 实体元数据定义（有 @Table / @Entity 注解）
     * @param config 全局配置
     */
    public static void validate(Connection conn, EntityTableMetaDefinition entity, JoyaConfig config) {
        if (config.getSchemaValidation() == SchemaValidation.NONE) {
            return;
        }
        String key = entity.getTableName();
        Boolean already = VALIDATED.putIfAbsent(key, Boolean.TRUE);
        if (already != null) {
            return;
        }

        SchemaTableDescription dbSchema = CacheHolder.databaseMetaProvider()
                .getTableMeta(conn, null, null, entity.getTableName());

        Set<String> entityColumns = entity.getColumnNames();
        Set<String> dbColumnsLower = dbSchema.getColumns() == null
                ? java.util.Collections.emptySet()
                : dbSchema.getColumns().stream()
                    .map(md -> md.getColumnName().toLowerCase())
                    .collect(java.util.stream.Collectors.toSet());

        // 将 entity 的列名也转成小写再比较（避免 db 小写 + entity 混合大小写）
        Set<String> entityColumnsLower = entityColumns.stream()
                .map(String::toLowerCase)
                .collect(java.util.stream.Collectors.toSet());

        Set<String> missingInDb = new java.util.LinkedHashSet<>(entityColumnsLower);
        missingInDb.removeAll(dbColumnsLower);

        if (missingInDb.isEmpty()) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[schema-drift] Entity ").append(entity.getEntityClass().getName());
        sb.append(" vs DB table '").append(entity.getTableName()).append("'");
        sb.append(" — entity 中存在但 DB 中不存在的列: ").append(missingInDb);

        if (config.getSchemaValidation() == SchemaValidation.THROW) {
            throw new DbException(sb.toString());
        } else {
            log.warn("{}", sb);
        }
    }

    /**
     * 清空已校验记录（配合 {@link CacheHolder#clearAll} 一起使用）
     */
    public static void clear() {
        VALIDATED.clear();
    }
}
