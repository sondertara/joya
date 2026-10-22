package com.sondertara.joya.core.kernel.dialect;

import com.sondertara.joya.exceptions.DbException;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;

/**
 * 数据库类型枚举（含 JDBC 产品名与 URL 启发式识别及数据库家族归类）。
 *
 * @author huangxiaohu
 */
public enum DbType {

    other,
    jtds,
    hsql,
    db2,
    postgresql,
    sqlserver,
    oracle,
    mysql,
    mariadb,
    derby,
    hive,
    h2,
    dm,
    kingbase,
    gbase,
    oceanbase,
    informix,
    odps,
    teradata,
    phoenix,
    edb,
    kylin,
    sqlite,
    ads,
    presto,
    elastic_search,
    hbase,
    drds,
    clickhouse,
    blink,
    antspark,
    oceanbase_oracle,
    polardb,
    ali_oracle,
    mock,
    sybase,
    highgo,
    greenplum,
    gaussdb,
    trino,
    oscar,
    tidb,
    tydb,
    ingres,
    cloudscape,
    timesten,
    as400,
    sapdb,
    kdb,
    log4jdbc,
    xugu,
    firebirdsql,
    JSQLConnect,
    JTurbo,
    interbase,
    pointbase,
    edbc,
    mimer;

    /**
     * 依据连接的元数据探测数据库类型：优先产品名，其次 JDBC URL，均失败时返回 {@link #other}。
     *
     * <p>该能力原位于 {@code utils.JdbcUtils}，下沉到方言包后，
     * {@code kernel} 不再需要为一次探测反向依赖工具包。
     */
    public static DbType of(Connection connection) {
        if (connection == null) {
            return other;
        }
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            String productName = metaData.getDatabaseProductName();
            if (productName != null && !productName.isBlank()) {
                try {
                    return of(productName);
                } catch (RuntimeException ignored) {
                    // 继续尝试 URL
                }
            }
            String url = metaData.getURL();
            if (url != null && !url.isBlank()) {
                return of(url);
            }
        } catch (SQLException | RuntimeException ignored) {
            // 探测失败不阻断主流程，交由调用方按 other 处理
        }
        return other;
    }

    /**
     * 按数据库名称、JDBC 产品名或 URL 关键字解析 {@link DbType}。
     */
    public static DbType of(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("database name must not be null or blank");
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        if ("aliyun_ads".equals(normalized)) {
            return ads;
        }
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            // Fall through to product name / JDBC URL heuristics
        }
        if (normalized.contains("postgresql") || normalized.contains("enterprisedb")) {
            return postgresql;
        }
        if (normalized.contains("mariadb")) {
            return mariadb;
        }
        if (normalized.contains("mysql")) {
            return mysql;
        }
        if (normalized.contains("oracle")) {
            return oracle;
        }
        if (normalized.contains("sql server") || normalized.contains("sqlserver") || normalized.contains("microsoft")) {
            return sqlserver;
        }
        if (normalized.contains("h2")) {
            return h2;
        }
        if (normalized.contains("sqlite")) {
            return sqlite;
        }
        if (normalized.contains("db2")) {
            return db2;
        }
        if (normalized.contains("derby")) {
            return derby;
        }
        if (normalized.contains("hsql")) {
            return hsql;
        }
        if (normalized.contains("dm") || normalized.contains("dameng")) {
            return dm;
        }
        if (normalized.contains("kingbase")) {
            return kingbase;
        }
        if (normalized.contains("gauss") || normalized.contains("opengauss")) {
            return gaussdb;
        }
        if (normalized.contains("tidb")) {
            return tidb;
        }
        if (normalized.contains("oceanbase")) {
            return oceanbase;
        }
        if (normalized.contains("clickhouse")) {
            return clickhouse;
        }
        if (normalized.contains("informix")) {
            return informix;
        }
        if (normalized.contains("phoenix")) {
            return phoenix;
        }
        if (normalized.contains("xugu")) {
            return xugu;
        }
        if (normalized.contains("hive")) {
            return hive;
        }
        if (normalized.contains("gbase")) {
            return gbase;
        }
        if (normalized.contains("sybase") || normalized.contains("adaptive server")) {
            return sybase;
        }
        throw new DbException("Unknown database type: " + name);
    }

    /**
     * 判断给定名称是否匹配当前 {@link DbType}。
     */
    public final boolean matches(String other) {
        if (other == null || other.isBlank()) {
            return false;
        }
        try {
            return this == of(other);
        } catch (Exception e) {
            return false;
        }
    }

    /** 类似于 PostgreSQL 系列的数据库类型。 */
    public boolean isLikePostgres() {
        return this == postgresql || this == kingbase || this == gaussdb || this == greenplum || this == highgo || this == edb;
    }

    /** 类似于 MySQL 系列的数据库类型。 */
    public boolean isLikeMysql() {
        return this == mysql || this == mariadb || this == gbase || this == tidb || this == oceanbase || this == polardb || this == drds;
    }

    /** 类似于 Oracle 系列的数据库类型。 */
    public boolean isLikeOracle() {
        return this == oracle || this == dm || this == oceanbase_oracle || this == ali_oracle;
    }

    /** 类似于 SQL Server 系列的数据库类型。 */
    public boolean isLikeSqlServer() {
        return this == sqlserver || this == jtds || this == sybase;
    }

    /** 类似于 Hive 系列的数据库类型。 */
    public boolean isLikeHive() {
        return this == hive;
    }

    /** 是否为 ClickHouse 数据库类型。 */
    public boolean isClickHouse() {
        return this == clickhouse;
    }

    /** 是否为 MongoDB 数据库类型。 */
    public boolean isMongodb() {
        return false;
    }
}
