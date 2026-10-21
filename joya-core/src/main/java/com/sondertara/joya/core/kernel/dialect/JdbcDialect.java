package com.sondertara.joya.core.kernel.dialect;

import com.sondertara.joya.core.jdbc.support.DbType;
import com.sondertara.joya.core.jdbc.support.dialect.BoundSql;
import com.sondertara.joya.core.jdbc.support.dialect.PageSqlDialect;

import java.util.Objects;

/**
 * 适配器（Adapter）：把现有 {@link PageSqlDialect} 方言体系适配为内核 {@link Dialect} 端口。
 * <p>
 * 复用既有方言实现（{@code PageSqlDialect} 的分页 / count 能力），不重写任何 SQL 生成逻辑；
 * 同时携带 {@link DbType} 供拦截器与路由层使用。
 */
public class JdbcDialect implements Dialect {

    private final DbType dbType;
    private final PageSqlDialect delegate;

    public JdbcDialect(DbType dbType, PageSqlDialect delegate) {
        this.dbType = Objects.requireNonNull(dbType, "dbType must not be null");
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    @Override
    public DbType dbType() {
        return dbType;
    }

    @Override
    public BoundSql countSql(BoundSql boundSql) {
        return delegate.countSql(boundSql);
    }

    @Override
    public BoundSql pageSql(BoundSql boundSql, long start, long limit) {
        return delegate.pageSql(boundSql, start, limit);
    }

    /** 暴露底层方言，供需要方言特性（关键字转义等）的高级场景使用。 */
    public PageSqlDialect delegate() {
        return delegate;
    }
}
