package com.sondertara.joya.spring.autoconfigure;

import com.sondertara.joya.core.JoyaSettings;
import com.sondertara.joya.core.constant.SchemaValidation;
import com.sondertara.joya.core.jdbc.support.DbType;
import com.sondertara.joya.core.kernel.dialect.DialectStrategy;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

/**
 * Joya Spring Boot 配置项：
 * <ul>
 *   <li>主库通过 {@code joya.primary.*} 配置（默认 {@code use-spring=true} 直接复用 {@code spring.datasource.*}，也可设为 {@code false} 独立配置连接）；</li>
 *   <li>扩展数据源通过 {@code joya.datasources} 数组统一配置连接参数与单库可选的 Joya 参数覆写。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "joya")
public class JoyaProperties {

    /** 全局开关，默认开启。 */
    private boolean enabled = true;

    /** 是否开启内置 SQL 日志拦截器，默认关闭。 */
    private boolean sqlLog = false;

    /** 默认查询超时秒数（{@code <= 0} 表示不限制）。 */
    private int queryTimeoutSeconds = 0;

    /** 批量保存/更新分片大小（默认 8000）。 */
    private int batchSize = JoyaSettings.DEFAULT_BATCH_SIZE;

    /** 联表最大表数限制（默认 10）。 */
    private int maxJoinTable = JoyaSettings.DEFAULT_MAX_JOIN_TABLE;

    /** 列名大写开关。 */
    private boolean columnUppercase = false;

    /** 表名大写开关。 */
    private boolean tableUppercase = false;

    /** Schema 校验策略（默认 NONE）。 */
    private SchemaValidation schemaValidation = SchemaValidation.NONE;

    /** 显式指定固定数据库方言（为空时按 DataSource 惰性探测并缓存）。 */
    private DbType dialect;

    /** 动态路由数据源模式（每次按当前连接解析方言），默认 false。 */
    private boolean dynamicDialect = false;

    /** 主数据源配置（单数对象，天然唯一，默认复用 Spring 原生 {@code spring.datasource}）。 */
    @NestedConfigurationProperty
    private PrimaryDataSourceProperties primary = new PrimaryDataSourceProperties();

    /** 扩展数据源列表（数组形式，支持 IDE 直接补全每个元素的属性）。 */
    private List<JoyaDataSourceProperties> datasources = new ArrayList<>();

    public JoyaSettings toSettings(JoyaDataSourceProperties override) {
        int timeout = (override != null && override.getQueryTimeoutSeconds() != null)
                ? override.getQueryTimeoutSeconds()
                : this.queryTimeoutSeconds;
        int batch = (override != null && override.getBatchSize() != null)
                ? override.getBatchSize()
                : this.batchSize;
        int maxJoin = (override != null && override.getMaxJoinTable() != null)
                ? override.getMaxJoinTable()
                : this.maxJoinTable;
        boolean colUpper = (override != null && override.getColumnUppercase() != null)
                ? override.getColumnUppercase()
                : this.columnUppercase;
        boolean tblUpper = (override != null && override.getTableUppercase() != null)
                ? override.getTableUppercase()
                : this.tableUppercase;
        SchemaValidation validation = (override != null && override.getSchemaValidation() != null)
                ? override.getSchemaValidation()
                : this.schemaValidation;
        return JoyaSettings.builder()
                .queryTimeoutSeconds(timeout)
                .batchSize(batch)
                .maxJoinTable(maxJoin)
                .columnUppercase(colUpper)
                .tableUppercase(tblUpper)
                .schemaValidation(validation)
                .build();
    }

    public DialectStrategy toDialectStrategy(JoyaDataSourceProperties override, DataSource dataSource) {
        boolean dynamic = (override != null && override.getDynamicDialect() != null)
                ? override.getDynamicDialect()
                : this.dynamicDialect;
        if (dynamic) {
            return DialectStrategy.perConnection();
        }
        DbType fixed = (override != null && override.getDialect() != null)
                ? override.getDialect()
                : this.dialect;
        if (fixed != null) {
            return DialectStrategy.fixed(fixed);
        }
        return DialectStrategy.cached(dataSource);
    }

    // ===== Getters & Setters =====

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isSqlLog() {
        return sqlLog;
    }

    public void setSqlLog(boolean sqlLog) {
        this.sqlLog = sqlLog;
    }

    public int getQueryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public void setQueryTimeoutSeconds(int queryTimeoutSeconds) {
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxJoinTable() {
        return maxJoinTable;
    }

    public void setMaxJoinTable(int maxJoinTable) {
        this.maxJoinTable = maxJoinTable;
    }

    public boolean isColumnUppercase() {
        return columnUppercase;
    }

    public void setColumnUppercase(boolean columnUppercase) {
        this.columnUppercase = columnUppercase;
    }

    public boolean isTableUppercase() {
        return tableUppercase;
    }

    public void setTableUppercase(boolean tableUppercase) {
        this.tableUppercase = tableUppercase;
    }

    public SchemaValidation getSchemaValidation() {
        return schemaValidation;
    }

    public void setSchemaValidation(SchemaValidation schemaValidation) {
        this.schemaValidation = schemaValidation;
    }

    public DbType getDialect() {
        return dialect;
    }

    public void setDialect(DbType dialect) {
        this.dialect = dialect;
    }

    public boolean isDynamicDialect() {
        return dynamicDialect;
    }

    public void setDynamicDialect(boolean dynamicDialect) {
        this.dynamicDialect = dynamicDialect;
    }

    public PrimaryDataSourceProperties getPrimary() {
        return primary;
    }

    public void setPrimary(PrimaryDataSourceProperties primary) {
        this.primary = primary;
    }

    public List<JoyaDataSourceProperties> getDatasources() {
        return datasources;
    }

    public void setDatasources(List<JoyaDataSourceProperties> datasources) {
        this.datasources = datasources;
    }

    /**
     * 单个数据源配置：直接继承 Spring Boot 原生 {@link DataSourceProperties}（支持 {@code url}、{@code username}、
     * {@code password}、{@code driver-class-name}、{@code type} 等），并内聚该数据源可选的 Joya 参数覆写。
     */
    public static class JoyaDataSourceProperties extends DataSourceProperties {

        /** 数据源注册名称（如 {@code order}、{@code archive}）。 */
        private String name;

        /** 单库查询超时秒数覆写。 */
        private Integer queryTimeoutSeconds;

        /** 单库批量分片大小覆写。 */
        private Integer batchSize;

        /** 单库联表最大表数限制覆写。 */
        private Integer maxJoinTable;

        /** 单库列名大写开关覆写。 */
        private Boolean columnUppercase;

        /** 单库表名大写开关覆写。 */
        private Boolean tableUppercase;

        /** 单库 Schema 校验策略覆写。 */
        private SchemaValidation schemaValidation;

        /** 单库显式固定数据库方言覆写。 */
        private DbType dialect;

        /** 单库动态方言探测开关覆写。 */
        private Boolean dynamicDialect;

        @Override
        public String getName() {
            return name;
        }

        @Override
        public void setName(String name) {
            this.name = name;
        }

        public Integer getQueryTimeoutSeconds() {
            return queryTimeoutSeconds;
        }

        public void setQueryTimeoutSeconds(Integer queryTimeoutSeconds) {
            this.queryTimeoutSeconds = queryTimeoutSeconds;
        }

        public Integer getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(Integer batchSize) {
            this.batchSize = batchSize;
        }

        public Integer getMaxJoinTable() {
            return maxJoinTable;
        }

        public void setMaxJoinTable(Integer maxJoinTable) {
            this.maxJoinTable = maxJoinTable;
        }

        public Boolean getColumnUppercase() {
            return columnUppercase;
        }

        public void setColumnUppercase(Boolean columnUppercase) {
            this.columnUppercase = columnUppercase;
        }

        public Boolean getTableUppercase() {
            return tableUppercase;
        }

        public void setTableUppercase(Boolean tableUppercase) {
            this.tableUppercase = tableUppercase;
        }

        public SchemaValidation getSchemaValidation() {
            return schemaValidation;
        }

        public void setSchemaValidation(SchemaValidation schemaValidation) {
            this.schemaValidation = schemaValidation;
        }

        public DbType getDialect() {
            return dialect;
        }

        public void setDialect(DbType dialect) {
            this.dialect = dialect;
        }

        public Boolean getDynamicDialect() {
            return dynamicDialect;
        }

        public void setDynamicDialect(Boolean dynamicDialect) {
            this.dynamicDialect = dynamicDialect;
        }
    }

    /**
     * 主数据源配置：继承 {@link JoyaDataSourceProperties}（与扩展库同构），默认名称为 {@code "primary"}，
     * 默认 {@code useSpring = true} 直接复用 Spring 容器原生 {@link DataSource}。
     */
    public static class PrimaryDataSourceProperties extends JoyaDataSourceProperties {

        /** 是否直接复用 Spring 容器原生的主 {@link DataSource}（默认 true；设为 false 时使用当前节点下的 url/username/password 初始化独立数据源）。 */
        private boolean useSpring = true;

        public PrimaryDataSourceProperties() {
            setName(JoyaClientRegistry.PRIMARY);
        }

        public boolean isUseSpring() {
            return useSpring;
        }

        public void setUseSpring(boolean useSpring) {
            this.useSpring = useSpring;
        }
    }
}
