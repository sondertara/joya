package com.sondertara.joya.core.kernel.dialect;

import com.sondertara.joya.core.kernel.dialect.DbType;
import com.sondertara.joya.core.kernel.statement.SqlStatement;

/**
 * 数据库方言端口 —— 全框架统一的 SQL 方言能力契约（直接面向 {@link SqlStatement}，零 {@code BoundSql} 遗产耦合）。
 *
 * <ul>
 *   <li>{@link #dbType()} —— 数据库类型标识，供拦截器路由与分支决策。</li>
 *   <li>{@link #quoteStyle()} / {@link #quoteIdentifier(String)} —— 标识符（表名/列名/保留字）安全转义。</li>
 *   <li>{@link #paginationStyle()} —— 标准分页策略描述符。</li>
 *   <li>{@link #countSql(SqlStatement)} —— 直接生成计数 {@link SqlStatement}，保留参数与元数据。</li>
 *   <li>{@link #pageSql(SqlStatement, long, long)} —— 直接生成分页 {@link SqlStatement}，保留参数与 {@code ParameterBinder}。</li>
 * </ul>
 */
public interface Dialect {

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

    /** 子查询表别名是否支持 {@code AS} 关键字（如 Oracle 不支持 {@code FROM (...) AS TEMP_T}）。 */
    default boolean supportsSubqueryAliasAs() {
        return true;
    }

    /** 对表名、列名或 {@code schema.table} 标识符按当前方言风格进行安全转义。 */
    default String quoteIdentifier(String identifier) {
        return quoteStyle().quote(identifier);
    }

    /** 基于原查询语句生成 {@code SELECT COUNT(*)} 包装语句（保留原参数列表与元数据）。 */
    SqlStatement countSql(SqlStatement statement);

    /** 基于原查询语句生成方言分页语句（保留原参数绑定器与元数据）。 */
    SqlStatement pageSql(SqlStatement statement, long offset, long limit);
}
