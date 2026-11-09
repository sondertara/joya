package com.sondertara.joya.core.kernel.dialect;

import com.sondertara.joya.core.kernel.statement.DbType;
import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlFormatUtils;
import com.sondertara.joya.core.kernel.statement.SqlParameter;
import com.sondertara.joya.core.kernel.statement.SqlStatement;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 标准化的数据库分页语法策略（直接变换 {@link SqlStatement}，保留全部 {@link SqlParameter} 及其 {@code ParameterBinder}）。
 */
public enum PaginationStyle {

    /**
     * {@code LIMIT ? [OFFSET ?]}（PostgreSQL / H2 / SQLite / Kingbase / GaussDB / HSQL / Phoenix / ClickHouse）。
     */
    LIMIT_OFFSET {
        @Override
        public SqlStatement paginate(SqlStatement statement, long offset, long limit) {
            validatePageBounds(offset, limit);
            DbType dbType = effectiveDbType(statement, DbType.postgresql);
            SqlParts parts = splitTrailingForUpdate(statement.sql(), dbType);
            StringBuilder sql = new StringBuilder(parts.body());
            List<SqlParameter> params = new ArrayList<>(statement.parameters());
            sql.append(" LIMIT ?");
            appendPageParam(params, limit);
            if (offset > 0) {
                sql.append(" OFFSET ?");
                appendPageParam(params, offset);
            }
            sql.append(parts.lockClause());
            return new SqlStatement(sql.toString(), params, statement.metadata());
        }
    },

    /**
     * {@code LIMIT ?} 或 {@code LIMIT ?, ?}（MySQL / MariaDB / TiDB / 达梦 / 虚谷 / GBase / OceanBase）。
     */
    MYSQL_LIMIT {
        @Override
        public SqlStatement paginate(SqlStatement statement, long offset, long limit) {
            validatePageBounds(offset, limit);
            DbType dbType = effectiveDbType(statement, DbType.mysql);
            SqlParts parts = splitTrailingForUpdate(statement.sql(), dbType);
            StringBuilder sql = new StringBuilder(parts.body());
            List<SqlParameter> params = new ArrayList<>(statement.parameters());
            if (offset == 0) {
                sql.append(" LIMIT ?");
                appendPageParam(params, limit);
            } else {
                sql.append(" LIMIT ?, ?");
                appendPageParam(params, offset);
                appendPageParam(params, limit);
            }
            sql.append(parts.lockClause());
            return new SqlStatement(sql.toString(), params, statement.metadata());
        }
    },

    /**
     * ANSI SQL:2008 {@code OFFSET ? ROWS FETCH NEXT ? ROWS ONLY}（SQL Server 2012+ / Oracle 12c+ / DB2 11+ / Apache Derby）。
     */
    ANSI_OFFSET_FETCH {
        @Override
        public SqlStatement paginate(SqlStatement statement, long offset, long limit) {
            validatePageBounds(offset, limit);
            DbType dbType = effectiveDbType(statement, DbType.sqlserver);
            SqlParts parts = splitTrailingForUpdate(statement.sql(), dbType);
            String bodySql = parts.body();
            StringBuilder sql = new StringBuilder(bodySql);
            if (dbType.isLikeSqlServer() && findTopLevelOrderByIndex(bodySql, dbType) < 0) {
                sql.append(" ORDER BY (SELECT 0)");
            }
            sql.append(" OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
            sql.append(parts.lockClause());
            List<SqlParameter> params = new ArrayList<>(statement.parameters());
            appendPageParam(params, offset);
            appendPageParam(params, limit);
            return new SqlStatement(sql.toString(), params, statement.metadata());
        }
    },

    /**
     * 不支持行级分页的引擎（如 Hive）。
     */
    UNSUPPORTED {
        @Override
        public SqlStatement paginate(SqlStatement statement, long offset, long limit) {
            throw new UnsupportedOperationException("Pagination is not supported for this database dialect");
        }
    };

    public abstract SqlStatement paginate(SqlStatement statement, long offset, long limit);

    private static DbType effectiveDbType(SqlStatement statement, DbType fallback) {
        return statement != null && statement.metadata().dbType() != null ? statement.metadata().dbType() : fallback;
    }

    static void validatePageBounds(long offset, long limit) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0: " + offset);
        }
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be >= 0: " + limit);
        }
    }

    static String renderCountSql(String sql, DbType dbType, boolean supportsSubqueryAliasAs) {
        // FOR UPDATE 属于行锁子句，计数包装既无意义又可能被部分数据库拒绝。
        int forUpdateIdx = findTopLevelForUpdateIndex(sql, dbType);
        if (forUpdateIdx >= 0) {
            sql = sql.substring(0, forUpdateIdx).stripTrailing();
        }
        boolean sqlServerLike = dbType != null && dbType.isLikeSqlServer();
        int statementStart = skipTrivia(sql, 0, dbType);
        String leadingTrivia = sql.substring(0, statementStart);
        String trimmed = sql.substring(statementStart).trim();
        if (keywordAt(trimmed, 0, "WITH")) {
            int mainSelectIdx = findTopLevelKeywordIndex(trimmed, "SELECT", dbType);
            String ctePrefix = mainSelectIdx > 0 ? trimmed.substring(0, mainSelectIdx).trim() : "";
            if (mainSelectIdx <= 0 || !ctePrefix.endsWith(")") || hasTopLevelToken(trimmed, ";", dbType)) {
                throw new UnsupportedOperationException("Count wrapping rejected: the leading CTE cannot be parsed safely");
            }
            String mainSelect = trimmed.substring(mainSelectIdx).trim();
            String countBody = sqlServerLike ? sqlServerCountInput(mainSelect, dbType) : mainSelect;
            String alias = chooseUniqueIdentifier(trimmed, "TEMP_T");
            String aliasClause = supportsSubqueryAliasAs ? ") AS " + alias : ") " + alias;
            return leadingTrivia + terminateLineComment(ctePrefix, dbType)
                    + " SELECT COUNT(*) FROM (" + terminateLineComment(countBody, dbType) + aliasClause;
        }
        String inputSql = sqlServerLike ? sqlServerCountInput(sql, dbType) : sql;
        String alias = chooseUniqueIdentifier(inputSql, "TEMP_T");
        String aliasClause = supportsSubqueryAliasAs ? ") AS " + alias : ") " + alias;
        return "SELECT COUNT(*) FROM (" + terminateLineComment(inputSql, dbType) + aliasClause;
    }

    /** SQL Server 计数时仅移除不影响结果行数的顶层排序（含 TOP / TOP WITH TIES 时保留必需的 ORDER BY）。 */
    static String sqlServerCountInput(String sql, DbType dbType) {
        int orderByIdx = findTopLevelOrderByIndex(sql, dbType);
        if (orderByIdx < 0) {
            return sql;
        }
        String orderByTail = sql.substring(orderByIdx);
        if (findTopLevelKeywordIndex(orderByTail, "OFFSET", dbType) >= 0
                || findTopLevelKeywordIndex(orderByTail, "FETCH", dbType) >= 0
                || findTopLevelKeywordIndex(orderByTail, "OPTION", dbType) >= 0
                || findTopLevelKeywordIndex(orderByTail, "FOR", dbType) >= 0) {
            throw new UnsupportedOperationException("SQL Server COUNT cannot safely remove this top-level ORDER BY; "
                    + "provide an independent count statement for parameterized ORDER BY or OFFSET/FETCH/OPTION/FOR queries");
        }
        if (hasTopLevelSelectTop(sql, dbType)) {
            return sql;
        }
        if (hasLexicalPlaceholder(orderByTail, dbType)) {
            throw new UnsupportedOperationException("SQL Server COUNT cannot safely remove this top-level ORDER BY; "
                    + "provide an independent count statement for parameterized ORDER BY or OFFSET/FETCH/OPTION/FOR queries");
        }
        return sql.substring(0, orderByIdx).stripTrailing();
    }

    private static boolean hasTopLevelSelectTop(String sql, DbType dbType) {
        int mainSelectIdx = findTopLevelKeywordIndex(sql, "SELECT", dbType);
        if (mainSelectIdx < 0) {
            return false;
        }
        if (hasTopLevelKeywordAfter(sql, "UNION", mainSelectIdx, dbType)
                || hasTopLevelKeywordAfter(sql, "INTERSECT", mainSelectIdx, dbType)
                || hasTopLevelKeywordAfter(sql, "EXCEPT", mainSelectIdx, dbType)) {
            return false;
        }
        int bodyStart = skipTrivia(sql, mainSelectIdx + 6, dbType);
        if (keywordAt(sql, bodyStart, "DISTINCT")) {
            bodyStart = skipTrivia(sql, bodyStart + 8, dbType);
        } else if (keywordAt(sql, bodyStart, "ALL")) {
            bodyStart = skipTrivia(sql, bodyStart + 3, dbType);
        }
        return keywordAt(sql, bodyStart, "TOP");
    }

    /** 若 SQL 停在行注释中，追加右括号或分页子句前必须换行。 */
    static String terminateLineComment(String sql, DbType dbType) {
        return endsInLineComment(sql, dbType) ? sql + '\n' : sql;
    }

    private static void appendPageParam(List<SqlParameter> params, long value) {
        int idx = params.size() + 1;
        params.add(SqlParameter.of(idx, "page_arg_" + idx, value, ParameterSource.RAW));
    }

    static int findTopLevelOrderByIndex(String sql, DbType dbType) {
        int found = -1;
        for (int orderIdx : topLevelTokens(sql, "ORDER", true, dbType)) {
            int byIdx = skipTrivia(sql, orderIdx + 5, dbType);
            if (byIdx > orderIdx + 5 && keywordAt(sql, byIdx, "BY")) {
                found = orderIdx;
            }
        }
        return found;
    }

    static int findTopLevelForUpdateIndex(String sql, DbType dbType) {
        int found = -1;
        for (int forIdx : topLevelTokens(sql, "FOR", true, dbType)) {
            int updateIdx = skipTrivia(sql, forIdx + 3, dbType);
            if (updateIdx > forIdx + 3 && keywordAt(sql, updateIdx, "UPDATE")) {
                found = forIdx;
            }
        }
        return found;
    }

    private static SqlParts splitTrailingForUpdate(String sql, DbType dbType) {
        int forUpdateIdx = findTopLevelForUpdateIndex(sql, dbType);
        if (forUpdateIdx < 0) {
            return new SqlParts(terminateLineComment(sql, dbType), "");
        }
        String body = terminateLineComment(sql.substring(0, forUpdateIdx).stripTrailing(), dbType);
        String lockClause = " " + terminateLineComment(sql.substring(forUpdateIdx).trim(), dbType);
        return new SqlParts(body, lockClause);
    }

    private record SqlParts(String body, String lockClause) {
    }

    private static int findTopLevelKeywordIndex(String sql, String keyword, DbType dbType) {
        List<Integer> indexes = topLevelTokens(sql, keyword, true, dbType);
        return indexes.isEmpty() ? -1 : indexes.get(0);
    }

    private static boolean hasTopLevelKeywordAfter(String sql, String keyword, int fromIndex, DbType dbType) {
        for (int index : topLevelTokens(sql, keyword, true, dbType)) {
            if (index > fromIndex) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTopLevelToken(String sql, String token, DbType dbType) {
        return !topLevelTokens(sql, token, false, dbType).isEmpty();
    }

    private static boolean keywordAt(String sql, int index, String keyword) {
        return matchesToken(sql, index, keyword, true);
    }

    private static String chooseUniqueIdentifier(String sql, String baseName) {
        String candidate = baseName;
        int suffix = 1;
        while (containsIdentifierIgnoreCase(sql, candidate)) {
            candidate = baseName + "_" + suffix++;
        }
        return candidate;
    }

    private static boolean containsIdentifierIgnoreCase(String sql, String identifier) {
        if (sql == null || sql.isEmpty() || identifier == null || identifier.isEmpty()) {
            return false;
        }
        String lowerSql = sql.toLowerCase(Locale.ROOT);
        String lowerIdent = identifier.toLowerCase(Locale.ROOT);
        int from = 0;
        while (true) {
            int idx = lowerSql.indexOf(lowerIdent, from);
            if (idx < 0) {
                return false;
            }
            boolean leftBoundary = idx == 0 || !isIdentifierPart(sql.charAt(idx - 1));
            int end = idx + identifier.length();
            boolean rightBoundary = end == sql.length() || !isIdentifierPart(sql.charAt(end));
            if (leftBoundary && rightBoundary) {
                return true;
            }
            from = idx + 1;
        }
    }

    static boolean hasLexicalPlaceholder(String sql, DbType dbType) {
        return SqlFormatUtils.countPlaceholders(sql, dbType) > 0;
    }

    private static int skipTrivia(String sql, int start, DbType dbType) {
        return SqlFormatUtils.skipTrivia(sql, start, dbType);
    }

    private static List<Integer> topLevelTokens(String sql, String token, boolean keyword, DbType dbType) {
        return SqlFormatUtils.topLevelTokens(sql, token, keyword, dbType);
    }

    private static boolean matchesToken(String sql, int index, String token, boolean keyword) {
        if (index < 0 || index + token.length() > sql.length()
                || !sql.regionMatches(true, index, token, 0, token.length())) {
            return false;
        }
        if (!keyword) {
            return true;
        }
        return (index == 0 || !isIdentifierPart(sql.charAt(index - 1)))
                && (index + token.length() == sql.length()
                || !isIdentifierPart(sql.charAt(index + token.length())));
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
    }

    private static boolean endsInLineComment(String sql, DbType dbType) {
        return SqlFormatUtils.endsInLineComment(sql, dbType);
    }
}
