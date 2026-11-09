package com.sondertara.joya.core.kernel.dialect;

import com.sondertara.joya.core.kernel.statement.UpsertStyle;
import com.sondertara.joya.core.kernel.statement.DbType;
import com.sondertara.joya.core.kernel.statement.SqlStatement;

import java.util.Objects;

/**
 * 数据库方言契约与内置标准实现（组合式特征描述符，无继承层级、无全局可变注册表）。
 */
public interface Dialect {

    int DEFAULT_STREAM_FETCH_SIZE = 2000;
    int MYSQL_STREAM_FETCH_SIZE = Integer.MIN_VALUE;

    Dialect MYSQL = of(DbType.mysql, QuoteStyle.BACKTICK, PaginationStyle.MYSQL_LIMIT, true, MYSQL_STREAM_FETCH_SIZE);
    Dialect MARIADB = of(DbType.mariadb, QuoteStyle.BACKTICK, PaginationStyle.MYSQL_LIMIT, true, MYSQL_STREAM_FETCH_SIZE);
    Dialect TIDB = of(DbType.tidb, QuoteStyle.BACKTICK, PaginationStyle.MYSQL_LIMIT, true, MYSQL_STREAM_FETCH_SIZE);
    Dialect POSTGRESQL = of(DbType.postgresql, QuoteStyle.DOUBLE_QUOTE, PaginationStyle.LIMIT_OFFSET, true, DEFAULT_STREAM_FETCH_SIZE);
    Dialect H2 = of(DbType.h2, QuoteStyle.DOUBLE_QUOTE, PaginationStyle.LIMIT_OFFSET, true, DEFAULT_STREAM_FETCH_SIZE);
    Dialect SQLITE = of(DbType.sqlite, QuoteStyle.DOUBLE_QUOTE, PaginationStyle.LIMIT_OFFSET, true, DEFAULT_STREAM_FETCH_SIZE);
    Dialect HSQL = of(DbType.hsql, QuoteStyle.DOUBLE_QUOTE, PaginationStyle.LIMIT_OFFSET, true, DEFAULT_STREAM_FETCH_SIZE);
    Dialect KINGBASE = of(DbType.kingbase, QuoteStyle.DOUBLE_QUOTE, PaginationStyle.LIMIT_OFFSET, true, DEFAULT_STREAM_FETCH_SIZE);
    Dialect GAUSSDB = of(DbType.gaussdb, QuoteStyle.DOUBLE_QUOTE, PaginationStyle.LIMIT_OFFSET, true, DEFAULT_STREAM_FETCH_SIZE);
    Dialect PHOENIX = of(DbType.phoenix, QuoteStyle.DOUBLE_QUOTE, PaginationStyle.LIMIT_OFFSET, true, DEFAULT_STREAM_FETCH_SIZE);
    Dialect CLICKHOUSE = of(DbType.clickhouse, QuoteStyle.BACKTICK, PaginationStyle.LIMIT_OFFSET, true, DEFAULT_STREAM_FETCH_SIZE);
    Dialect DM = of(DbType.dm, QuoteStyle.DOUBLE_QUOTE, PaginationStyle.MYSQL_LIMIT, false, DEFAULT_STREAM_FETCH_SIZE);
    Dialect XUGU = of(DbType.xugu, QuoteStyle.DOUBLE_QUOTE, PaginationStyle.MYSQL_LIMIT, true, DEFAULT_STREAM_FETCH_SIZE);
    Dialect ORACLE = of(DbType.oracle, QuoteStyle.DOUBLE_QUOTE, PaginationStyle.ANSI_OFFSET_FETCH, false, DEFAULT_STREAM_FETCH_SIZE);
    Dialect SQLSERVER = of(DbType.sqlserver, QuoteStyle.BRACKET, PaginationStyle.ANSI_OFFSET_FETCH, true, DEFAULT_STREAM_FETCH_SIZE);
    Dialect DB2 = of(DbType.db2, QuoteStyle.DOUBLE_QUOTE, PaginationStyle.ANSI_OFFSET_FETCH, true, DEFAULT_STREAM_FETCH_SIZE);
    Dialect DERBY = of(DbType.derby, QuoteStyle.DOUBLE_QUOTE, PaginationStyle.ANSI_OFFSET_FETCH, true, DEFAULT_STREAM_FETCH_SIZE);
    Dialect INFORMIX = of(DbType.informix, QuoteStyle.DOUBLE_QUOTE, PaginationStyle.LIMIT_OFFSET, false, DEFAULT_STREAM_FETCH_SIZE);
    Dialect HIVE = of(DbType.hive, QuoteStyle.BACKTICK, PaginationStyle.UNSUPPORTED, true, DEFAULT_STREAM_FETCH_SIZE);
    Dialect DEFAULT = of(DbType.other, QuoteStyle.NONE, PaginationStyle.LIMIT_OFFSET, true, DEFAULT_STREAM_FETCH_SIZE);

    /** 目标数据库类型。 */
    DbType dbType();

    /** 标识符转义风格。 */
    default QuoteStyle quoteStyle() {
        return QuoteStyle.NONE;
    }

    /** 分页策略。 */
    default PaginationStyle paginationStyle() {
        return PaginationStyle.LIMIT_OFFSET;
    }

    /** 原生 upsert 语法族；默认不支持，由方言覆盖。 */
    default UpsertStyle upsertStyle() {
        return switch (dbType()) {
            case postgresql, sqlite -> UpsertStyle.ON_CONFLICT;
            case mysql, mariadb, tidb, oceanbase, gbase, polardb, drds, xugu -> UpsertStyle.ON_DUPLICATE_KEY;
            case h2 -> UpsertStyle.MERGE;
            default -> UpsertStyle.NONE;
        };
    }

    /** 子查询表别名是否支持 {@code AS} 关键字（如 Oracle 不支持 {@code FROM (...) AS TEMP_T}）。 */
    default boolean supportsSubqueryAliasAs() {
        return true;
    }


    /** 流式查询（{@code queryStream}）的游标 {@code fetchSize}。 */
    default int streamFetchSize() {
        return DEFAULT_STREAM_FETCH_SIZE;
    }

    /** 基于原查询语句生成 {@code SELECT COUNT(*)} 包装语句（保留原参数列表与元数据）。 */
    SqlStatement countSql(SqlStatement statement);

    /** 基于原查询语句生成方言分页语句（保留原参数绑定器与元数据）。 */
    SqlStatement pageSql(SqlStatement statement, long offset, long limit);

    /**
     * 按 {@link DbType} 获取对应的内置标准方言实例。
     */
    static Dialect of(DbType dbType) {
        if (dbType == null) {
            return DEFAULT;
        }
        return switch (dbType) {
            case mysql -> MYSQL;
            case mariadb -> MARIADB;
            case tidb -> TIDB;
            case oceanbase, gbase, polardb, drds ->
                    of(dbType, QuoteStyle.BACKTICK, PaginationStyle.MYSQL_LIMIT, true, MYSQL_STREAM_FETCH_SIZE);
            case xugu -> XUGU;
            case postgresql -> POSTGRESQL;
            case h2 -> H2;
            case sqlite -> SQLITE;
            case hsql -> HSQL;
            case kingbase -> KINGBASE;
            case gaussdb -> GAUSSDB;
            case phoenix -> PHOENIX;
            case clickhouse -> CLICKHOUSE;
            case greenplum, highgo, edb ->
                    of(dbType, QuoteStyle.DOUBLE_QUOTE, PaginationStyle.LIMIT_OFFSET, true, DEFAULT_STREAM_FETCH_SIZE);
            case dm -> DM;
            case oracle -> ORACLE;
            case oceanbase_oracle, ali_oracle ->
                    of(dbType, QuoteStyle.DOUBLE_QUOTE, PaginationStyle.ANSI_OFFSET_FETCH, false, DEFAULT_STREAM_FETCH_SIZE);
            case sqlserver -> SQLSERVER;
            case jtds, sybase ->
                    of(dbType, QuoteStyle.BRACKET, PaginationStyle.ANSI_OFFSET_FETCH, true, DEFAULT_STREAM_FETCH_SIZE);
            case db2 -> DB2;
            case derby -> DERBY;
            case informix -> INFORMIX;
            case hive -> HIVE;
            case other -> DEFAULT;
        };
    }

    /**
     * 构造自定义特征组合的不可变方言实例。
     */
    static Dialect of(
            DbType dbType,
            QuoteStyle quoteStyle,
            PaginationStyle paginationStyle,
            boolean supportsSubqueryAliasAs
    ) {
        int fetchSize = dbType != null && dbType.isLikeMysql() ? MYSQL_STREAM_FETCH_SIZE : DEFAULT_STREAM_FETCH_SIZE;
        return new Standard(dbType, quoteStyle, paginationStyle, supportsSubqueryAliasAs, fetchSize);
    }

    /**
     * 构造完整特征组合的不可变方言实例。
     */
    static Dialect of(
            DbType dbType,
            QuoteStyle quoteStyle,
            PaginationStyle paginationStyle,
            boolean supportsSubqueryAliasAs,
            int streamFetchSize
    ) {
        return new Standard(dbType, quoteStyle, paginationStyle, supportsSubqueryAliasAs, streamFetchSize);
    }

    /**
     * 标准不可变方言实现。
     */
    record Standard(
            DbType dbType,
            QuoteStyle quoteStyle,
            PaginationStyle paginationStyle,
            boolean supportsSubqueryAliasAs,
            int streamFetchSize
    ) implements Dialect {

        public Standard {
            Objects.requireNonNull(dbType, "dbType must not be null");
            Objects.requireNonNull(quoteStyle, "quoteStyle must not be null");
            Objects.requireNonNull(paginationStyle, "paginationStyle must not be null");
        }

        @Override
        public SqlStatement countSql(SqlStatement statement) {
            Objects.requireNonNull(statement, "statement must not be null");
            if (paginationStyle == PaginationStyle.UNSUPPORTED) {
                throw new UnsupportedOperationException("Count wrapping is not supported for dialect: " + dbType);
            }
            SqlStatement base = statement.metadata().dbType() == null ? statement.withDbType(dbType) : statement;
            String wrappedSql = PaginationStyle.renderCountSql(
                    base.sql(), dbType, supportsSubqueryAliasAs);
            return base.withSql(wrappedSql);
        }

        @Override
        public SqlStatement pageSql(SqlStatement statement, long offset, long limit) {
            Objects.requireNonNull(statement, "statement must not be null");
            PaginationStyle.validatePageBounds(offset, limit);
            SqlStatement base = statement.metadata().dbType() == null ? statement.withDbType(dbType) : statement;
            return paginationStyle.paginate(base, offset, limit);
        }
    }
}
