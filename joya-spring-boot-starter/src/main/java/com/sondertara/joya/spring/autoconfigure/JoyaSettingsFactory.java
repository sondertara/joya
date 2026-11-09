package com.sondertara.joya.spring.autoconfigure;

import com.sondertara.joya.core.JoyaSettings;
import com.sondertara.joya.core.kernel.dialect.DialectStrategy;
import com.sondertara.joya.core.kernel.dialect.QuoteStyle;
import com.sondertara.joya.spring.autoconfigure.JoyaProperties.JoyaDataSourceProperties;

import javax.sql.DataSource;

/**
 * {@link JoyaProperties}（纯配置载体） → 内核配置对象的转换：
 * <ul>
 *   <li>{@link JoyaSettings}：全局值 + 单数据源覆写合并；</li>
 *   <li>{@link DialectStrategy}：固定方言 / 动态方言 / 惰性探测。</li>
 * </ul>
 *
 * <p>把转换逻辑从配置绑定类中移出，使 {@code JoyaProperties} 只承担 Spring Boot 配置绑定职责。
 */
final class JoyaSettingsFactory {

    private JoyaSettingsFactory() {
    }

    /**
     * 合并全局 Joya 配置与单数据源覆写（{@code null} 表示沿用全局值）。
     */
    static JoyaSettings settings(JoyaProperties root, JoyaDataSourceProperties override) {
        int timeout = (override != null && override.getQueryTimeoutSeconds() != null)
                ? override.getQueryTimeoutSeconds()
                : root.getQueryTimeoutSeconds();
        int batch = (override != null && override.getBatchSize() != null)
                ? override.getBatchSize()
                : root.getBatchSize();
        int maxJoin = (override != null && override.getMaxJoinTable() != null)
                ? override.getMaxJoinTable()
                : root.getMaxJoinTable();
        boolean colUpper = (override != null && override.getColumnUppercase() != null)
                ? override.getColumnUppercase()
                : root.isColumnUppercase();
        boolean tblUpper = (override != null && override.getTableUppercase() != null)
                ? override.getTableUppercase()
                : root.isTableUppercase();
        QuoteStyle quoteStyle = (override != null && override.getQuoteStyle() != null)
                ? override.getQuoteStyle()
                : root.getQuoteStyle();
        return JoyaSettings.builder()
                .queryTimeoutSeconds(timeout)
                .batchSize(batch)
                .maxJoinTable(maxJoin)
                .columnUppercase(colUpper)
                .tableUppercase(tblUpper)
                .quoteStyle(quoteStyle)
                .build();
    }

    /**
     * 解析方言策略：动态方言 > 固定方言 > 按 DataSource 惰性探测并缓存。
     */
    static DialectStrategy dialectStrategy(JoyaProperties root,
                                           JoyaDataSourceProperties override,
                                           DataSource dataSource) {
        boolean dynamic = (override != null && override.getDynamicDialect() != null)
                ? override.getDynamicDialect()
                : root.isDynamicDialect();
        if (dynamic) {
            return DialectStrategy.perConnection();
        }
        var fixed = (override != null && override.getDialect() != null)
                ? override.getDialect()
                : root.getDialect();
        if (fixed != null) {
            return DialectStrategy.fixed(fixed);
        }
        return DialectStrategy.cached(dataSource);
    }
}
