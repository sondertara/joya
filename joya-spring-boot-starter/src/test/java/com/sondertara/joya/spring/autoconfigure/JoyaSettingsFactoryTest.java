package com.sondertara.joya.spring.autoconfigure;

import com.sondertara.joya.core.JoyaSettings;
import com.sondertara.joya.core.kernel.dialect.DialectStrategy;
import com.sondertara.joya.core.kernel.dialect.QuoteStyle;
import com.sondertara.joya.core.kernel.statement.DbType;
import com.sondertara.joya.spring.autoconfigure.JoyaProperties.JoyaDataSourceProperties;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 配置绑定与“配置 → 内核对象”转换的边界测试：{@code JoyaProperties} 只做绑定，合并/方言解析集中在
 * {@link JoyaSettingsFactory}。
 */
class JoyaSettingsFactoryTest {

    @Test
    void settings_mergesGlobalValuesWithPerDataSourceOverrides() {
        JoyaProperties root = new JoyaProperties();
        root.setQueryTimeoutSeconds(7);
        root.setBatchSize(500);
        root.setMaxJoinTable(3);
        root.setColumnUppercase(true);
        root.setQuoteStyle(QuoteStyle.BACKTICK);

        JoyaDataSourceProperties override = new JoyaDataSourceProperties();
        override.setBatchSize(50);
        override.setTableUppercase(true);
        override.setQuoteStyle(QuoteStyle.NONE);

        JoyaSettings settings = JoyaSettingsFactory.settings(root, override);

        assertEquals(7, settings.executionOptions().queryTimeoutSeconds());
        assertEquals(50, settings.executionOptions().batchSize());
        assertEquals(3, settings.namingPolicy().maxJoinTable());
        assertEquals(true, settings.namingPolicy().columnUppercase());
        assertEquals(true, settings.namingPolicy().tableUppercase());
        assertEquals('\0', settings.namingPolicy().openQuote(),
                "单库 quote-style 覆写必须覆盖全局值");
    }

    @Test
    void settings_perDataSourceWithoutQuoteStyle_inheritsGlobalQuoting() {
        JoyaProperties root = new JoyaProperties();
        root.setQuoteStyle(QuoteStyle.BACKTICK);
        JoyaSettings settings = JoyaSettingsFactory.settings(root, new JoyaDataSourceProperties());
        assertEquals('`', settings.namingPolicy().openQuote());
    }

    @Test
    void settings_withoutOverride_usesGlobalValues() {
        JoyaProperties root = new JoyaProperties();
        root.setBatchSize(12);
        JoyaSettings settings = JoyaSettingsFactory.settings(root, null);
        assertEquals(12, settings.executionOptions().batchSize());
    }

    @Test
    void dialectStrategy_precedenceIsDynamicThenFixedThenCached() {
        JoyaProperties root = new JoyaProperties();
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:settings_factory;DB_CLOSE_DELAY=-1");

        DialectStrategy cached = JoyaSettingsFactory.dialectStrategy(root, null, dataSource);
        assertEquals(DbType.h2, cached.peek().dbType(),
                "未显式配置时按 DataSource URL 惰性探测");

        root.setDialect(DbType.h2);
        assertEquals(DbType.h2, JoyaSettingsFactory.dialectStrategy(root, null, dataSource).peek().dbType());

        JoyaDataSourceProperties perDsFixed = new JoyaDataSourceProperties();
        perDsFixed.setDialect(DbType.oracle);
        assertEquals(DbType.oracle,
                JoyaSettingsFactory.dialectStrategy(root, perDsFixed, dataSource).peek().dbType());

        JoyaDataSourceProperties perDsDynamic = new JoyaDataSourceProperties();
        perDsDynamic.setDynamicDialect(true);
        assertNull(JoyaSettingsFactory.dialectStrategy(root, perDsDynamic, dataSource).peek(),
                "动态方言必须每次按连接解析");
    }

    @Test
    void invalidNumericLimits_failAtBindingTime() {
        assertThrows(IllegalArgumentException.class, () -> new JoyaProperties().setBatchSize(0));
        assertThrows(IllegalArgumentException.class, () -> new JoyaProperties().setMaxJoinTable(-1));
        assertThrows(IllegalArgumentException.class, () -> new JoyaDataSourceProperties().setBatchSize(0));
        assertThrows(IllegalArgumentException.class, () -> new JoyaDataSourceProperties().setMaxJoinTable(0));
        assertThrows(IllegalArgumentException.class, () -> new JoyaDataSourceProperties().setQueryTimeoutSeconds(-1));
    }
}
