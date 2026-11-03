package com.sondertara.joya.core.kernel.statement;

import com.sondertara.joya.exceptions.DbException;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;

/**
 * 关系型数据库类型枚举（含 JDBC 产品名与 URL 识别及 SQL 家族归类）。
 *
 * @author huangxiaohu
 */
public enum DbType {

    other,
    // MySQL 家族
    mysql,
    mariadb,
    tidb,
    oceanbase,
    gbase,
    polardb,
    drds,
    xugu,
    // PostgreSQL / LIMIT-OFFSET 家族
    postgresql,
    h2,
    sqlite,
    hsql,
    kingbase,
    gaussdb,
    greenplum,
    highgo,
    edb,
    phoenix,
    clickhouse,
    // Oracle 家族
    oracle,
    dm,
    oceanbase_oracle,
    ali_oracle,
    // SQL Server / ANSI OFFSET-FETCH 家族
    sqlserver,
    jtds,
    sybase,
    db2,
    derby,
    informix,
    // 分析型（不支持行级分页）
    hive;

    /**
     * 依据连接的元数据探测数据库类型。
     */
    public static DbType of(Connection connection) {
        if (connection == null) {
            return other;
        }
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            if (metaData == null) {
                return other;
            }
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
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            // Fall through to product name / JDBC URL heuristics
        }
        if (normalized.startsWith("jdbc:")) {
            int semi = normalized.indexOf(';');
            if (semi > 0) {
                normalized = normalized.substring(0, semi);
            }
            int qmark = normalized.indexOf('?');
            if (qmark > 0) {
                normalized = normalized.substring(0, qmark);
            }
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

    /**
     * 当前数据库类型的默认流式游标 {@code fetchSize}（MySQL 系为 {@link Integer#MIN_VALUE}，其余为 {@code 2000}）。
     */
    public int defaultStreamFetchSize() {
        return isLikeMysql() ? Integer.MIN_VALUE : 2000;
    }
}
