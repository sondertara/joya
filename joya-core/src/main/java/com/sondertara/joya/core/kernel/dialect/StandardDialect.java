package com.sondertara.joya.core.kernel.dialect;

import com.sondertara.joya.core.kernel.dialect.DbType;
import com.sondertara.joya.core.kernel.statement.SqlStatement;

import java.util.Objects;

/**
 * 标准不可变 SQL 方言描述符（组合式 Value Object，无继承层级、无反射开销）。
 *
 * @param dbType                  数据库类型
 * @param quoteStyle              标识符转义风格
 * @param paginationStyle         分页 SQL 生成策略
 * @param supportsSubqueryAliasAs 子查询表别名是否支持 {@code AS} 关键字（Oracle 不支持 {@code FROM (...) AS TEMP_T}）
 */
public record StandardDialect(
        DbType dbType,
        QuoteStyle quoteStyle,
        PaginationStyle paginationStyle,
        boolean supportsSubqueryAliasAs
) implements Dialect {

    public StandardDialect {
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
        String wrappedSql = PaginationStyle.renderCountSql(
                statement.sql(), dbType.isLikeSqlServer(), supportsSubqueryAliasAs);
        return statement.withSql(wrappedSql);
    }

    @Override
    public SqlStatement pageSql(SqlStatement statement, long offset, long limit) {
        Objects.requireNonNull(statement, "statement must not be null");
        PaginationStyle.validatePageBounds(offset, limit);
        return paginationStyle.paginate(statement, offset, limit);
    }
}
