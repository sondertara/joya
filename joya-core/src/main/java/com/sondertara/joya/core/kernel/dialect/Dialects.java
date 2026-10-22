package com.sondertara.joya.core.kernel.dialect;

import com.sondertara.joya.core.kernel.dialect.DbType;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内置标准方言常量池与可扩展注册表（零反射、GraalVM Native 友好）。
 */
public final class Dialects {

    public static final Dialect MYSQL = new StandardDialect(
            DbType.mysql, QuoteStyle.BACKTICK, PaginationStyle.MYSQL_LIMIT, true);

    public static final Dialect MARIADB = new StandardDialect(
            DbType.mariadb, QuoteStyle.BACKTICK, PaginationStyle.MYSQL_LIMIT, true);

    public static final Dialect TIDB = new StandardDialect(
            DbType.tidb, QuoteStyle.BACKTICK, PaginationStyle.MYSQL_LIMIT, true);

    public static final Dialect POSTGRESQL = new StandardDialect(
            DbType.postgresql, QuoteStyle.DOUBLE_QUOTE, PaginationStyle.LIMIT_OFFSET, true);

    public static final Dialect H2 = new StandardDialect(
            DbType.h2, QuoteStyle.DOUBLE_QUOTE, PaginationStyle.LIMIT_OFFSET, true);

    public static final Dialect SQLITE = new StandardDialect(
            DbType.sqlite, QuoteStyle.DOUBLE_QUOTE, PaginationStyle.LIMIT_OFFSET, true);

    public static final Dialect HSQL = new StandardDialect(
            DbType.hsql, QuoteStyle.DOUBLE_QUOTE, PaginationStyle.LIMIT_OFFSET, true);

    public static final Dialect KINGBASE = new StandardDialect(
            DbType.kingbase, QuoteStyle.DOUBLE_QUOTE, PaginationStyle.LIMIT_OFFSET, true);

    public static final Dialect GAUSSDB = new StandardDialect(
            DbType.gaussdb, QuoteStyle.DOUBLE_QUOTE, PaginationStyle.LIMIT_OFFSET, true);

    public static final Dialect PHOENIX = new StandardDialect(
            DbType.phoenix, QuoteStyle.DOUBLE_QUOTE, PaginationStyle.LIMIT_OFFSET, true);

    public static final Dialect CLICKHOUSE = new StandardDialect(
            DbType.clickhouse, QuoteStyle.BACKTICK, PaginationStyle.LIMIT_OFFSET, true);

    public static final Dialect DM = new StandardDialect(
            DbType.dm, QuoteStyle.DOUBLE_QUOTE, PaginationStyle.MYSQL_LIMIT, false);

    public static final Dialect XUGU = new StandardDialect(
            DbType.xugu, QuoteStyle.DOUBLE_QUOTE, PaginationStyle.MYSQL_LIMIT, true);

    public static final Dialect ORACLE = new StandardDialect(
            DbType.oracle, QuoteStyle.DOUBLE_QUOTE, PaginationStyle.ORACLE_ROWNUM, false);

    public static final Dialect ORACLE_12C = new StandardDialect(
            DbType.oracle, QuoteStyle.DOUBLE_QUOTE, PaginationStyle.ANSI_OFFSET_FETCH, false);

    public static final Dialect SQLSERVER = new StandardDialect(
            DbType.sqlserver, QuoteStyle.BRACKET, PaginationStyle.ANSI_OFFSET_FETCH, true);

    public static final Dialect SQLSERVER_2005 = new StandardDialect(
            DbType.sqlserver, QuoteStyle.BRACKET, PaginationStyle.SQLSERVER_ROW_NUMBER, true);

    public static final Dialect DB2 = new StandardDialect(
            DbType.db2, QuoteStyle.DOUBLE_QUOTE, PaginationStyle.DB2_ROWNUMBER, true);

    public static final Dialect DERBY = new StandardDialect(
            DbType.derby, QuoteStyle.DOUBLE_QUOTE, PaginationStyle.ANSI_OFFSET_FETCH, true);

    public static final Dialect INFORMIX = new StandardDialect(
            DbType.informix, QuoteStyle.DOUBLE_QUOTE, PaginationStyle.INFORMIX_SKIP_FIRST, false);

    public static final Dialect HIVE = new StandardDialect(
            DbType.hive, QuoteStyle.BACKTICK, PaginationStyle.UNSUPPORTED, true);

    public static final Dialect DEFAULT = new StandardDialect(
            DbType.other, QuoteStyle.NONE, PaginationStyle.LIMIT_OFFSET, true);

    private static final Map<DbType, Dialect> REGISTRY = new ConcurrentHashMap<>();

    static {
        reset();
    }

    private Dialects() {
    }

    /**
     * 按 {@link DbType} 获取已注册的方言实例；未显式注册的按数据库家族归类回退。
     */
    public static Dialect forDbType(DbType dbType) {
        if (dbType == null) {
            return DEFAULT;
        }
        Dialect registered = REGISTRY.get(dbType);
        if (registered != null) {
            return registered;
        }
        if (dbType.isLikePostgres()) {
            return new StandardDialect(dbType, QuoteStyle.DOUBLE_QUOTE, PaginationStyle.LIMIT_OFFSET, true);
        }
        if (dbType.isLikeMysql()) {
            return new StandardDialect(dbType, QuoteStyle.BACKTICK, PaginationStyle.MYSQL_LIMIT, true);
        }
        if (dbType.isLikeOracle()) {
            return new StandardDialect(dbType, QuoteStyle.DOUBLE_QUOTE, PaginationStyle.ORACLE_ROWNUM, false);
        }
        if (dbType.isLikeSqlServer()) {
            return new StandardDialect(dbType, QuoteStyle.BRACKET, PaginationStyle.ANSI_OFFSET_FETCH, true);
        }
        return new StandardDialect(dbType, QuoteStyle.NONE, PaginationStyle.LIMIT_OFFSET, true);
    }

    /**
     * 注册或覆盖指定 {@link DbType} 的方言实现（如切换 Oracle 为 12c+ {@link #ORACLE_12C}）。
     */
    public static void register(DbType dbType, Dialect dialect) {
        Objects.requireNonNull(dbType, "dbType must not be null");
        Objects.requireNonNull(dialect, "dialect must not be null");
        REGISTRY.put(dbType, dialect);
    }

    /**
     * 重置为默认内置方言表。
     */
    public static void reset() {
        REGISTRY.clear();
        REGISTRY.put(DbType.mysql, MYSQL);
        REGISTRY.put(DbType.mariadb, MARIADB);
        REGISTRY.put(DbType.tidb, TIDB);
        REGISTRY.put(DbType.postgresql, POSTGRESQL);
        REGISTRY.put(DbType.h2, H2);
        REGISTRY.put(DbType.sqlite, SQLITE);
        REGISTRY.put(DbType.hsql, HSQL);
        REGISTRY.put(DbType.kingbase, KINGBASE);
        REGISTRY.put(DbType.gaussdb, GAUSSDB);
        REGISTRY.put(DbType.phoenix, PHOENIX);
        REGISTRY.put(DbType.clickhouse, CLICKHOUSE);
        REGISTRY.put(DbType.dm, DM);
        REGISTRY.put(DbType.xugu, XUGU);
        REGISTRY.put(DbType.oracle, ORACLE);
        REGISTRY.put(DbType.sqlserver, SQLSERVER);
        REGISTRY.put(DbType.jtds, new StandardDialect(
                DbType.jtds, QuoteStyle.BRACKET, PaginationStyle.ANSI_OFFSET_FETCH, true));
        REGISTRY.put(DbType.db2, DB2);
        REGISTRY.put(DbType.derby, DERBY);
        REGISTRY.put(DbType.informix, INFORMIX);
        REGISTRY.put(DbType.hive, HIVE);
    }
}
