package com.sondertara.joya.core.kernel.dialect;

import com.sondertara.joya.core.jdbc.support.DbType;
import com.sondertara.joya.core.jdbc.support.dialect.BoundSql;

/**
 * 方言端口（DIP）：执行内核只依赖本抽象，不直接触碰 {@code SqlDialectRegister}。
 * <p>
 * 职责：
 * <ul>
 *   <li>{@link #dbType()} —— 目标数据库类型，供拦截器路由、读写分离判断。</li>
 *   <li>{@link #countSql(BoundSql)} —— 生成 count 查询 SQL。</li>
 *   <li>{@link #pageSql(BoundSql, long, long)} —— 生成分页查询 SQL。</li>
 * </ul>
 * 实现由 {@link JdbcDialect}（适配现有方言体系）提供，R2dbc / 其他执行后端可实现独立适配。
 */
public interface Dialect {

    DbType dbType();

    /** 生成 count 查询 SQL。 */
    BoundSql countSql(BoundSql boundSql);

    /** 生成分页查询 SQL。 */
    BoundSql pageSql(BoundSql boundSql, long start, long limit);
}
