package com.sondertara.joya.core.kernel.dialect;

import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlParameter;
import com.sondertara.joya.core.kernel.statement.SqlStatement;

import java.util.ArrayList;
import java.util.List;

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
            StringBuilder sql = new StringBuilder(terminateLineComment(statement.sql()));
            List<SqlParameter> params = new ArrayList<>(statement.parameters());
            sql.append(" LIMIT ?");
            appendPageParam(params, limit);
            if (offset > 0) {
                sql.append(" OFFSET ?");
                appendPageParam(params, offset);
            }
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
            StringBuilder sql = new StringBuilder(terminateLineComment(statement.sql()));
            List<SqlParameter> params = new ArrayList<>(statement.parameters());
            if (offset == 0) {
                sql.append(" LIMIT ?");
                appendPageParam(params, limit);
            } else {
                sql.append(" LIMIT ?, ?");
                appendPageParam(params, offset);
                appendPageParam(params, limit);
            }
            return new SqlStatement(sql.toString(), params, statement.metadata());
        }
    },

    /**
     * ANSI SQL:2008 {@code OFFSET ? ROWS FETCH NEXT ? ROWS ONLY}（SQL Server 2012+ / Apache Derby / Oracle 12c+ / DB2 11+）。
     */
    ANSI_OFFSET_FETCH {
        @Override
        public SqlStatement paginate(SqlStatement statement, long offset, long limit) {
            validatePageBounds(offset, limit);
            String sqlText = statement.sql();
            StringBuilder sql = new StringBuilder(terminateLineComment(sqlText));
            if (!containsTopLevelOrderBy(sqlText)) {
                sql.append(" ORDER BY (SELECT 0)");
            }
            sql.append(" OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
            List<SqlParameter> params = new ArrayList<>(statement.parameters());
            appendPageParam(params, offset);
            appendPageParam(params, limit);
            return new SqlStatement(sql.toString(), params, statement.metadata());
        }
    },

    /**
     * SQL Server 2005 兼容 {@code ROW_NUMBER() OVER (...) BETWEEN ? AND ?}。
     */
    SQLSERVER_ROW_NUMBER {
        @Override
        public SqlStatement paginate(SqlStatement statement, long offset, long limit) {
            validatePageBounds(offset, limit);
            String original = statement.sql();
            int statementStart = skipTrivia(original, 0);
            String leadingTrivia = original.substring(0, statementStart);
            String sql = original.substring(statementStart).trim();
            int mainSelectIdx = findTopLevelKeywordIndex(sql, "SELECT");
            boolean hasCte = keywordAt(sql, 0, "WITH");
            if (mainSelectIdx < 0 || (!hasCte && mainSelectIdx != 0)) {
                throw rowNumberUnsupported("only SELECT statements with an optional leading CTE are supported");
            }
            String ctePrefix = hasCte ? sql.substring(0, mainSelectIdx).trim() : null;
            if (hasCte && (mainSelectIdx == 0 || !ctePrefix.endsWith(")"))) {
                throw rowNumberUnsupported("the leading CTE cannot be parsed safely");
            }
            if (hasTopLevelToken(sql, ";") || hasTopLevelKeywordAfter(sql, "OFFSET", mainSelectIdx)
                    || hasTopLevelKeywordAfter(sql, "FETCH", mainSelectIdx)
                    || hasTopLevelKeywordAfter(sql, "OPTION", mainSelectIdx)
                    || hasTopLevelKeywordAfter(sql, "FOR", mainSelectIdx)) {
                throw rowNumberUnsupported("existing OFFSET/FETCH/OPTION/FOR clauses or semicolons are not supported");
            }

            int orderByIdx = findTopLevelOrderByIndex(sql);
            String orderBy = orderByIdx >= 0 ? sql.substring(orderByIdx).trim() : "ORDER BY CURRENT_TIMESTAMP";
            if (orderByIdx >= 0 && hasLexicalPlaceholder(orderBy)) {
                throw rowNumberUnsupported("parameterized ORDER BY would change bind order");
            }
            String sqlWithoutOrderBy = orderByIdx >= 0 ? sql.substring(0, orderByIdx).trim() : sql;
            int bodyStart = skipTrivia(sqlWithoutOrderBy, mainSelectIdx + 6);
            boolean isDistinct = keywordAt(sqlWithoutOrderBy, bodyStart, "DISTINCT");
            if (isDistinct) {
                bodyStart = skipTrivia(sqlWithoutOrderBy, bodyStart + 8);
            }
            if (keywordAt(sqlWithoutOrderBy, bodyStart, "TOP")) {
                throw rowNumberUnsupported("an existing TOP clause is not supported");
            }
            boolean isSetQuery = hasTopLevelKeywordAfter(sqlWithoutOrderBy, "UNION", mainSelectIdx)
                    || hasTopLevelKeywordAfter(sqlWithoutOrderBy, "INTERSECT", mainSelectIdx)
                    || hasTopLevelKeywordAfter(sqlWithoutOrderBy, "EXCEPT", mainSelectIdx);

            String cteName = chooseUniqueIdentifier(sql, "selectTemp");
            String innerAlias = chooseUniqueIdentifier(sql, "innerTemp", cteName);
            String rowNumAlias = chooseUniqueIdentifier(sql, "__row_number__", cteName, innerAlias);

            String pagedBody;
            if (isDistinct || isSetQuery) {
                String core = sqlWithoutOrderBy.substring(mainSelectIdx).trim();
                String outerOrderBy = orderByIdx >= 0
                        ? remapOuterOrderBy(orderBy, firstSelectList(core))
                        : orderBy;
                pagedBody = "SELECT TOP 100 PERCENT " + innerAlias + ".*, ROW_NUMBER() OVER ("
                        + terminateLineComment(outerOrderBy) + ") as " + rowNumAlias + " FROM ("
                        + terminateLineComment(core) + ") " + innerAlias;
            } else {
                String body = sqlWithoutOrderBy.substring(bodyStart).trim();
                if (keywordAt(body, 0, "ALL")) {
                    body = body.substring(skipTrivia(body, 3)).trim();
                }
                int fromIdx = findTopLevelFromIndex(body);
                String selectList = fromIdx >= 0 ? body.substring(0, fromIdx).trim() : body;
                String fromClause = fromIdx >= 0 ? body.substring(fromIdx).trim() : "";
                if (selectList.isEmpty()) {
                    throw rowNumberUnsupported("the SELECT list is empty");
                }
                String windowOrderBy = orderByIdx >= 0
                        ? rewriteWindowOrderBy(orderBy, selectList)
                        : orderBy;
                pagedBody = "SELECT TOP 100 PERCENT " + selectList + ", ROW_NUMBER() OVER ("
                        + terminateLineComment(windowOrderBy) + ") as " + rowNumAlias
                        + (fromClause.isEmpty() ? "" : " " + fromClause);
            }
            String withClause = hasCte
                    ? terminateLineComment(ctePrefix) + ", " + cteName + " AS ("
                    : "WITH " + cteName + " AS (";
            String pagedSql = leadingTrivia + withClause + terminateLineComment(pagedBody)
                    + ") SELECT * FROM " + cteName + " WHERE " + rowNumAlias + " BETWEEN ? AND ? ORDER BY " + rowNumAlias;
            List<SqlParameter> params = new ArrayList<>(statement.parameters());
            appendPageParam(params, offset + 1);
            appendPageParam(params, offset + limit);
            return new SqlStatement(pagedSql, params, statement.metadata());
        }
    },

    /**
     * Oracle 传统三层 {@code ROWNUM} 嵌套分页（兼容 Oracle 11g 及更早版本）。
     */
    ORACLE_ROWNUM {
        @Override
        public SqlStatement paginate(SqlStatement statement, long offset, long limit) {
            validatePageBounds(offset, limit);
            String pagedSql = "SELECT * FROM ( SELECT TMP.*, ROWNUM ROW_ID FROM ( "
                    + terminateLineComment(statement.sql())
                    + " ) TMP WHERE ROWNUM <= ? ) WHERE ROW_ID > ?";
            List<SqlParameter> params = new ArrayList<>(statement.parameters());
            appendPageParam(params, offset + limit);
            appendPageParam(params, offset);
            return new SqlStatement(pagedSql, params, statement.metadata());
        }
    },

    /**
     * DB2 {@code ROWNUMBER() OVER() ... BETWEEN ? AND ?} 分页（按 1-based 闭区间 {@code offset + 1 .. offset + limit} 计算）。
     */
    DB2_ROWNUMBER {
        @Override
        public SqlStatement paginate(SqlStatement statement, long offset, long limit) {
            validatePageBounds(offset, limit);
            String pagedSql = "SELECT * FROM (SELECT TMP_PAGE.*, ROWNUMBER() OVER() AS ROW_ID FROM ( "
                    + terminateLineComment(statement.sql())
                    + " ) AS TMP_PAGE) TMP_PAGE WHERE ROW_ID BETWEEN ? AND ?";
            List<SqlParameter> params = new ArrayList<>(statement.parameters());
            appendPageParam(params, offset + 1);
            appendPageParam(params, offset + limit);
            return new SqlStatement(pagedSql, params, statement.metadata());
        }
    },

    /**
     * Informix {@code SELECT SKIP ? FIRST ? * FROM (...) TEMP_T} 前置参数分页。
     */
    INFORMIX_SKIP_FIRST {
        @Override
        public SqlStatement paginate(SqlStatement statement, long offset, long limit) {
            validatePageBounds(offset, limit);
            StringBuilder sql = new StringBuilder("SELECT ");
            List<Object> prefixArgs = new ArrayList<>(2);
            if (offset > 0) {
                sql.append("SKIP ? ");
                prefixArgs.add(offset);
            }
            sql.append("FIRST ? ");
            prefixArgs.add(limit);
            sql.append("* FROM ( ").append(terminateLineComment(statement.sql())).append(" ) TEMP_T");

            List<SqlParameter> orig = statement.parameters();
            List<SqlParameter> merged = new ArrayList<>(prefixArgs.size() + orig.size());
            for (Object arg : prefixArgs) {
                int idx = merged.size() + 1;
                merged.add(SqlParameter.of(idx, "page_arg_" + idx, arg, ParameterSource.RAW));
            }
            for (SqlParameter existing : orig) {
                int idx = merged.size() + 1;
                merged.add(new SqlParameter(
                        idx, existing.name(), existing.value(), existing.source(), existing.binder()));
            }
            return new SqlStatement(sql.toString(), merged, statement.metadata());
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

    private record SelectItem(String expression, String outputName, boolean explicitAlias) {
    }

    static void validatePageBounds(long offset, long limit) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0: " + offset);
        }
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be >= 0: " + limit);
        }
    }

    static String renderCountSql(String sql, boolean sqlServerLike, boolean supportsSubqueryAliasAs) {
        int statementStart = skipTrivia(sql, 0);
        String leadingTrivia = sql.substring(0, statementStart);
        String trimmed = sql.substring(statementStart).trim();
        if (keywordAt(trimmed, 0, "WITH")) {
            int mainSelectIdx = findTopLevelKeywordIndex(trimmed, "SELECT");
            String ctePrefix = mainSelectIdx > 0 ? trimmed.substring(0, mainSelectIdx).trim() : "";
            if (mainSelectIdx <= 0 || !ctePrefix.endsWith(")") || hasTopLevelToken(trimmed, ";")) {
                throw new UnsupportedOperationException("Count wrapping rejected: the leading CTE cannot be parsed safely");
            }
            String mainSelect = trimmed.substring(mainSelectIdx).trim();
            String countBody = sqlServerLike ? sqlServerCountInput(mainSelect) : mainSelect;
            String alias = chooseUniqueIdentifier(trimmed, "TEMP_T");
            String aliasClause = supportsSubqueryAliasAs ? ") AS " + alias : ") " + alias;
            return leadingTrivia + terminateLineComment(ctePrefix)
                    + " SELECT COUNT(*) FROM (" + terminateLineComment(countBody) + aliasClause;
        }
        String inputSql = sqlServerLike ? sqlServerCountInput(sql) : sql;
        String alias = chooseUniqueIdentifier(inputSql, "TEMP_T");
        String aliasClause = supportsSubqueryAliasAs ? ") AS " + alias : ") " + alias;
        return "SELECT COUNT(*) FROM (" + terminateLineComment(inputSql) + aliasClause;
    }

    /** SQL Server 计数时仅移除不影响结果行数的顶层排序（含 TOP / TOP WITH TIES 时保留必需的 ORDER BY）。 */
    static String sqlServerCountInput(String sql) {
        int orderByIdx = findTopLevelOrderByIndex(sql);
        if (orderByIdx < 0) {
            return sql;
        }
        String orderByTail = sql.substring(orderByIdx);
        if (findTopLevelKeywordIndex(orderByTail, "OFFSET") >= 0
                || findTopLevelKeywordIndex(orderByTail, "FETCH") >= 0
                || findTopLevelKeywordIndex(orderByTail, "OPTION") >= 0
                || findTopLevelKeywordIndex(orderByTail, "FOR") >= 0) {
            throw new UnsupportedOperationException("SQL Server COUNT cannot safely remove this top-level ORDER BY; "
                    + "provide an independent count statement for parameterized ORDER BY or OFFSET/FETCH/OPTION/FOR queries");
        }
        if (hasTopLevelSelectTop(sql)) {
            return sql;
        }
        if (hasLexicalPlaceholder(orderByTail)) {
            throw new UnsupportedOperationException("SQL Server COUNT cannot safely remove this top-level ORDER BY; "
                    + "provide an independent count statement for parameterized ORDER BY or OFFSET/FETCH/OPTION/FOR queries");
        }
        return sql.substring(0, orderByIdx).stripTrailing();
    }

    private static boolean hasTopLevelSelectTop(String sql) {
        int mainSelectIdx = findTopLevelKeywordIndex(sql, "SELECT");
        if (mainSelectIdx < 0) {
            return false;
        }
        if (hasTopLevelKeywordAfter(sql, "UNION", mainSelectIdx)
                || hasTopLevelKeywordAfter(sql, "INTERSECT", mainSelectIdx)
                || hasTopLevelKeywordAfter(sql, "EXCEPT", mainSelectIdx)) {
            return false;
        }
        int bodyStart = skipTrivia(sql, mainSelectIdx + 6);
        if (keywordAt(sql, bodyStart, "DISTINCT")) {
            bodyStart = skipTrivia(sql, bodyStart + 8);
        } else if (keywordAt(sql, bodyStart, "ALL")) {
            bodyStart = skipTrivia(sql, bodyStart + 3);
        }
        return keywordAt(sql, bodyStart, "TOP");
    }

    /** 若 SQL 停在行注释中，追加右括号或分页子句前必须换行。 */
    static String terminateLineComment(String sql) {
        return endsInLineComment(sql) ? sql + '\n' : sql;
    }

    private static void appendPageParam(List<SqlParameter> params, long value) {
        int idx = params.size() + 1;
        params.add(SqlParameter.of(idx, "page_arg_" + idx, value, ParameterSource.RAW));
    }

    private static UnsupportedOperationException rowNumberUnsupported(String reason) {
        return new UnsupportedOperationException("SQLSERVER_ROW_NUMBER pagination rejected: " + reason
                + "; use ANSI_OFFSET_FETCH or a simpler SELECT statement");
    }

    private static boolean containsTopLevelOrderBy(String sql) {
        return findTopLevelOrderByIndex(sql) >= 0;
    }

    static int findTopLevelOrderByIndex(String sql) {
        int found = -1;
        for (int orderIdx : topLevelTokens(sql, "ORDER", true)) {
            int byIdx = skipTrivia(sql, orderIdx + 5);
            if (byIdx > orderIdx + 5 && keywordAt(sql, byIdx, "BY")) {
                found = orderIdx;
            }
        }
        return found;
    }

    static int findTopLevelFromIndex(String sql) {
        return findTopLevelKeywordIndex(sql, "FROM");
    }

    private static int findTopLevelKeywordIndex(String sql, String keyword) {
        List<Integer> indexes = topLevelTokens(sql, keyword, true);
        return indexes.isEmpty() ? -1 : indexes.get(0);
    }

    private static boolean hasTopLevelKeywordAfter(String sql, String keyword, int fromIndex) {
        for (int index : topLevelTokens(sql, keyword, true)) {
            if (index > fromIndex) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTopLevelToken(String sql, String token) {
        return !topLevelTokens(sql, token, false).isEmpty();
    }

    private static boolean keywordAt(String sql, int index, String keyword) {
        return matchesToken(sql, index, keyword, true);
    }

    private static String firstSelectList(String core) {
        if (!keywordAt(core, 0, "SELECT")) {
            throw rowNumberUnsupported("a set query must start with SELECT");
        }
        int bodyStart = skipTrivia(core, 6);
        if (keywordAt(core, bodyStart, "DISTINCT")) {
            bodyStart = skipTrivia(core, bodyStart + 8);
        } else if (keywordAt(core, bodyStart, "ALL")) {
            bodyStart = skipTrivia(core, bodyStart + 3);
        }
        String body = core.substring(bodyStart);
        int end = body.length();
        for (String keyword : List.of("FROM", "UNION", "INTERSECT", "EXCEPT")) {
            int index = findTopLevelKeywordIndex(body, keyword);
            if (index >= 0) {
                end = Math.min(end, index);
            }
        }
        return body.substring(0, end).trim();
    }

    /** 将 DISTINCT / 集合查询的排序映射为派生表输出列，禁止生成语义不同的常量或失效排序。 */
    private static String remapOuterOrderBy(String orderBy, String selectList) {
        int orderIdx = findTopLevelOrderByIndex(orderBy);
        int byIdx = skipTrivia(orderBy, orderIdx + 5);
        String orderItems = orderBy.substring(byIdx + 2);
        List<SelectItem> selectItems = parseSelectItems(selectList);
        List<String> mapped = new ArrayList<>();
        for (String rawItem : splitTopLevel(orderItems)) {
            String item = rawItem.trim();
            String base = item;
            String direction = "";
            int descIdx = trailingTopLevelKeyword(item, "DESC");
            int ascIdx = trailingTopLevelKeyword(item, "ASC");
            int directionIdx = Math.max(descIdx, ascIdx);
            if (directionIdx >= 0) {
                base = item.substring(0, directionIdx).trim();
                direction = descIdx >= 0 ? " DESC" : " ASC";
            }
            String normalizedBase = normalizeExpression(base);
            String replacement = null;
            if (isUnsignedInteger(normalizedBase)) {
                int ordinal = Integer.parseInt(normalizedBase);
                if (ordinal > 0 && ordinal <= selectItems.size()) {
                    replacement = selectItems.get(ordinal - 1).outputName();
                }
            } else {
                for (SelectItem selectItem : selectItems) {
                    if (selectItem.outputName() != null
                            && (normalizedBase.equals(normalizeExpression(selectItem.expression()))
                            || sameQualifiedIdentifier(normalizedBase, selectItem.expression()))) {
                        replacement = selectItem.outputName();
                        break;
                    }
                }
                if (replacement == null && isIdentifier(normalizedBase)) {
                    for (SelectItem selectItem : selectItems) {
                        if (sameIdentifier(normalizedBase, selectItem.outputName())) {
                            replacement = selectItem.outputName();
                            break;
                        }
                    }
                }
            }
            if (replacement == null) {
                throw rowNumberUnsupported("cannot map ORDER BY item [" + item
                        + "] to a derived-table output column; use an explicit SELECT alias");
            }
            mapped.add(replacement + direction);
        }
        if (mapped.isEmpty()) {
            throw rowNumberUnsupported("the ORDER BY list is empty");
        }
        return "ORDER BY " + String.join(", ", mapped);
    }

    /**
     * 普通 SELECT 的 ROW_NUMBER() OVER (ORDER BY ...) 与 SELECT 处于同一作用域，
     * SQL Server 不允许在同层 OVER(...) 中直接引用 SELECT 列别名或列序号，需安全内联为原列达式或快速拒绝。
     */
    private static String rewriteWindowOrderBy(String orderBy, String selectList) {
        int orderIdx = findTopLevelOrderByIndex(orderBy);
        int byIdx = skipTrivia(orderBy, orderIdx + 5);
        String orderItems = orderBy.substring(byIdx + 2);
        List<SelectItem> selectItems = parseSelectItems(selectList);
        List<String> rewritten = new ArrayList<>();
        for (String rawItem : splitTopLevel(orderItems)) {
            String item = rawItem.trim();
            String base = item;
            String direction = "";
            int descIdx = trailingTopLevelKeyword(item, "DESC");
            int ascIdx = trailingTopLevelKeyword(item, "ASC");
            int directionIdx = Math.max(descIdx, ascIdx);
            if (directionIdx >= 0) {
                base = item.substring(0, directionIdx).trim();
                direction = descIdx >= 0 ? " DESC" : " ASC";
            }
            String normalizedBase = normalizeExpression(base);
            String mappedBase = base;
            if (isUnsignedInteger(normalizedBase)) {
                int ordinal = Integer.parseInt(normalizedBase);
                if (ordinal <= 0 || ordinal > selectItems.size()) {
                    throw rowNumberUnsupported("ORDER BY ordinal [" + ordinal + "] is out of range");
                }
                String expr = selectItems.get(ordinal - 1).expression();
                if (!isSafeWindowOrderExpression(expr)) {
                    throw rowNumberUnsupported("ORDER BY ordinal [" + ordinal
                            + "] targets SELECT expression [" + expr + "] that cannot be inlined into OVER(...)");
                }
                mappedBase = expr;
            } else if (isIdentifier(normalizedBase)) {
                List<SelectItem> aliasMatches = new ArrayList<>();
                for (SelectItem selectItem : selectItems) {
                    if (selectItem.explicitAlias()
                            && selectItem.outputName() != null
                            && sameIdentifier(normalizedBase, selectItem.outputName())) {
                        aliasMatches.add(selectItem);
                    }
                }
                if (aliasMatches.size() > 1) {
                    throw rowNumberUnsupported("ORDER BY references ambiguous SELECT alias [" + base + "]");
                }
                if (aliasMatches.size() == 1) {
                    String expr = aliasMatches.get(0).expression();
                    if (!isSafeWindowOrderExpression(expr)) {
                        throw rowNumberUnsupported("ORDER BY alias [" + base
                                + "] targets SELECT expression [" + expr + "] that cannot be inlined into OVER(...)");
                    }
                    mappedBase = expr;
                }
            } else {
                for (SelectItem selectItem : selectItems) {
                    if (selectItem.explicitAlias()
                            && selectItem.outputName() != null
                            && !sameIdentifier(selectItem.outputName(), selectItem.expression())
                            && referencesUnqualifiedIdentifier(base, unquoteIdentifier(selectItem.outputName()))) {
                        throw rowNumberUnsupported("ORDER BY expression [" + item
                                + "] references SELECT alias [" + selectItem.outputName()
                                + "] which is not visible inside ROW_NUMBER() OVER (...)");
                    }
                }
            }
            rewritten.add(mappedBase + direction);
        }
        if (rewritten.isEmpty()) {
            throw rowNumberUnsupported("the ORDER BY list is empty");
        }
        return "ORDER BY " + String.join(", ", rewritten);
    }

    private static boolean isSafeWindowOrderExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            return false;
        }
        String trimmed = expression.trim();
        if ("*".equals(trimmed) || trimmed.endsWith(".*")) {
            return false;
        }
        if (hasLexicalPlaceholder(trimmed)) {
            return false;
        }
        return topLevelTokens(trimmed, "OVER", true).isEmpty();
    }

    private static boolean referencesUnqualifiedIdentifier(String sql, String targetUnquoted) {
        for (int idx : topLevelTokens(sql, targetUnquoted, true)) {
            int prev = idx - 1;
            while (prev >= 0 && Character.isWhitespace(sql.charAt(prev))) {
                prev--;
            }
            if (prev < 0 || sql.charAt(prev) != '.') {
                return true;
            }
        }
        return false;
    }

    private static int trailingTopLevelKeyword(String sql, String keyword) {
        List<Integer> indexes = topLevelTokens(sql, keyword, true);
        if (indexes.isEmpty()) {
            return -1;
        }
        int index = indexes.get(indexes.size() - 1);
        return skipTrivia(sql, index + keyword.length()) == sql.length() ? index : -1;
    }

    private static List<SelectItem> parseSelectItems(String selectList) {
        List<SelectItem> result = new ArrayList<>();
        for (String rawItem : splitTopLevel(selectList)) {
            String item = rawItem.trim();
            List<Integer> asIndexes = topLevelTokens(item, "AS", true);
            if (!asIndexes.isEmpty()) {
                int asIdx = asIndexes.get(asIndexes.size() - 1);
                String expression = item.substring(0, asIdx).trim();
                String alias = item.substring(asIdx + 2).trim();
                result.add(new SelectItem(expression, isIdentifier(alias) ? alias : null, true));
            } else {
                SelectItem spaceAliased = parseSpaceAliasedSelectItem(item);
                if (spaceAliased != null) {
                    result.add(spaceAliased);
                } else {
                    result.add(new SelectItem(item, implicitOutputName(item), false));
                }
            }
        }
        return result;
    }

    private static SelectItem parseSpaceAliasedSelectItem(String item) {
        if (isIdentifier(item) || implicitOutputName(item) != null) {
            return null;
        }
        int end = item.length();
        while (end > 0 && Character.isWhitespace(item.charAt(end - 1))) {
            end--;
        }
        if (end == 0) {
            return null;
        }
        int aliasStart = -1;
        char last = item.charAt(end - 1);
        if (last == ']' || last == '"' || last == '`') {
            char open = last == ']' ? '[' : last;
            for (int i = end - 2; i >= 0; i--) {
                if (item.charAt(i) == open) {
                    if (i > 0 && item.charAt(i - 1) == open) {
                        i--;
                        continue;
                    }
                    aliasStart = i;
                    break;
                }
            }
        } else if (isIdentifierPart(last)) {
            aliasStart = end - 1;
            while (aliasStart > 0 && isIdentifierPart(item.charAt(aliasStart - 1))) {
                aliasStart--;
            }
        }
        if (aliasStart <= 0 || !Character.isWhitespace(item.charAt(aliasStart - 1))) {
            return null;
        }
        String alias = item.substring(aliasStart, end).trim();
        String expression = item.substring(0, aliasStart).trim();
        if (expression.isEmpty() || !isIdentifier(alias) || isReservedSelectTailKeyword(unquoteIdentifier(alias))) {
            return null;
        }
        char exprLast = expression.charAt(expression.length() - 1);
        if (exprLast == '.' || exprLast == '+' || exprLast == '-' || exprLast == '*' || exprLast == '/' || exprLast == '=') {
            return null;
        }
        return new SelectItem(expression, alias, true);
    }

    private static boolean isReservedSelectTailKeyword(String token) {
        return switch (token.toUpperCase(java.util.Locale.ROOT)) {
            case "END", "NULL", "ASC", "DESC", "DISTINCT", "ALL", "FROM" -> true;
            default -> false;
        };
    }

    private static String implicitOutputName(String expression) {
        String trimmed = expression.trim();
        if (isIdentifier(trimmed)) {
            return trimmed;
        }
        List<String> segments = splitQualifiedSegments(trimmed);
        if (segments.size() < 2) {
            return null;
        }
        for (String segment : segments) {
            if (!isIdentifier(segment)) {
                return null;
            }
        }
        return segments.get(segments.size() - 1);
    }

    private static List<String> splitQualifiedSegments(String expression) {
        List<Integer> dots = topLevelTokens(expression, ".", false);
        if (dots.isEmpty()) {
            return isIdentifier(expression) ? List.of(expression.trim()) : List.of();
        }
        List<String> segments = new ArrayList<>(dots.size() + 1);
        int start = 0;
        for (int dot : dots) {
            segments.add(expression.substring(start, dot).trim());
            start = dot + 1;
        }
        segments.add(expression.substring(start).trim());
        return segments;
    }

    private static boolean sameQualifiedIdentifier(String left, String right) {
        List<String> leftSegs = splitQualifiedSegments(left);
        List<String> rightSegs = splitQualifiedSegments(right);
        if (leftSegs.isEmpty() || leftSegs.size() != rightSegs.size()) {
            return false;
        }
        for (int i = 0; i < leftSegs.size(); i++) {
            if (!isIdentifier(leftSegs.get(i)) || !isIdentifier(rightSegs.get(i))
                    || !sameIdentifier(leftSegs.get(i), rightSegs.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static List<String> splitTopLevel(String sql) {
        List<String> result = new ArrayList<>();
        int start = 0;
        for (int comma : topLevelTokens(sql, ",", false)) {
            result.add(sql.substring(start, comma));
            start = comma + 1;
        }
        result.add(sql.substring(start));
        return result;
    }

    private static boolean sameIdentifier(String left, String right) {
        return right != null && unquoteIdentifier(left).equalsIgnoreCase(unquoteIdentifier(right));
    }

    private static String unquoteIdentifier(String identifier) {
        String value = identifier.trim();
        if (value.length() >= 2 && value.startsWith("[") && value.endsWith("]")) {
            return value.substring(1, value.length() - 1).replace("]]", "]");
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).replace("\"\"", "\"");
        }
        if (value.length() >= 2 && value.startsWith("`") && value.endsWith("`")) {
            return value.substring(1, value.length() - 1).replace("``", "`");
        }
        return value;
    }

    private static boolean isIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 3 && trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return isSingleDelimitedIdentifier(trimmed, ']');
        }
        if (trimmed.length() >= 3 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return isSingleDelimitedIdentifier(trimmed, '"');
        }
        if (trimmed.length() >= 3 && trimmed.startsWith("`") && trimmed.endsWith("`")) {
            return isSingleDelimitedIdentifier(trimmed, '`');
        }
        if (!Character.isLetter(trimmed.charAt(0)) && trimmed.charAt(0) != '_') {
            return false;
        }
        for (int i = 1; i < trimmed.length(); i++) {
            if (!isIdentifierPart(trimmed.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSingleDelimitedIdentifier(String trimmed, char closeChar) {
        int end = trimmed.length() - 1;
        for (int i = 1; i < end; i++) {
            if (trimmed.charAt(i) == closeChar) {
                if (i + 1 < end && trimmed.charAt(i + 1) == closeChar) {
                    i++;
                } else {
                    return false;
                }
            }
        }
        return true;
    }

    private static String chooseUniqueIdentifier(String sql, String baseName, String... reserved) {
        String candidate = baseName;
        int suffix = 1;
        while (containsIdentifierIgnoreCase(sql, candidate) || matchesAnyIgnoreCase(candidate, reserved)) {
            candidate = baseName + "_" + suffix++;
        }
        return candidate;
    }

    private static boolean matchesAnyIgnoreCase(String candidate, String... reserved) {
        if (reserved == null) {
            return false;
        }
        for (String r : reserved) {
            if (r != null && r.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsIdentifierIgnoreCase(String sql, String identifier) {
        if (sql == null || sql.isEmpty() || identifier == null || identifier.isEmpty()) {
            return false;
        }
        String lowerSql = sql.toLowerCase(java.util.Locale.ROOT);
        String lowerIdent = identifier.toLowerCase(java.util.Locale.ROOT);
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

    static boolean hasLexicalPlaceholder(String sql) {
        if (sql == null || sql.isEmpty()) {
            return false;
        }
        boolean single = false;
        boolean doubleQuote = false;
        boolean bracket = false;
        boolean backtick = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n' || c == '\r') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (single || doubleQuote || bracket || backtick) {
                if ((single && c == '\'') || (doubleQuote && c == '"') || (backtick && c == '`')) {
                    if (next == c) {
                        i++;
                    } else {
                        single = false;
                        doubleQuote = false;
                        backtick = false;
                    }
                } else if (bracket && c == ']') {
                    if (next == ']') {
                        i++;
                    } else {
                        bracket = false;
                    }
                }
                continue;
            }
            if (c == '-' && next == '-') {
                lineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                blockComment = true;
                i++;
                continue;
            }
            if (c == '\'') {
                single = true;
                continue;
            }
            if (c == '"') {
                doubleQuote = true;
                continue;
            }
            if (c == '[') {
                bracket = true;
                continue;
            }
            if (c == '`') {
                backtick = true;
                continue;
            }
            if (c == '?') {
                return true;
            }
        }
        return false;
    }

    private static boolean isUnsignedInteger(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    /** 仅在比较表达式时去除注释和空白；引号及字符串内容原样保留。 */
    private static String normalizeExpression(String sql) {
        StringBuilder result = new StringBuilder(sql.length());
        boolean single = false;
        boolean doubleQuote = false;
        boolean bracket = false;
        boolean backtick = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (single || doubleQuote || bracket || backtick) {
                result.append(c);
                if ((single && c == '\'') || (doubleQuote && c == '"') || (backtick && c == '`')) {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == c) {
                        result.append(sql.charAt(++i));
                    } else {
                        single = false;
                        doubleQuote = false;
                        backtick = false;
                    }
                } else if (bracket && c == ']') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == ']') {
                        result.append(sql.charAt(++i));
                    } else {
                        bracket = false;
                    }
                }
                continue;
            }
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                while (i + 1 < sql.length() && sql.charAt(i + 1) != '\n' && sql.charAt(i + 1) != '\r') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < sql.length() && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i++;
                continue;
            }
            result.append(c);
            if (c == '\'') {
                single = true;
            } else if (c == '"') {
                doubleQuote = true;
            } else if (c == '[') {
                bracket = true;
            } else if (c == '`') {
                backtick = true;
            }
        }
        return result.toString();
    }

    private static int skipTrivia(String sql, int start) {
        int i = start;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < sql.length() && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
                    i++;
                }
            } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < sql.length() && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i = i + 1 < sql.length() ? i + 2 : sql.length();
            } else {
                break;
            }
        }
        return i;
    }

    private static List<Integer> topLevelTokens(String sql, String token, boolean keyword) {
        List<Integer> result = new ArrayList<>();
        if (sql == null || token == null || token.isEmpty()) {
            return result;
        }
        int depth = 0;
        boolean single = false;
        boolean doubleQuote = false;
        boolean bracket = false;
        boolean backtick = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n' || c == '\r') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (single || doubleQuote || bracket || backtick) {
                if ((single && c == '\'') || (doubleQuote && c == '"') || (backtick && c == '`')) {
                    if (next == c) {
                        i++;
                    } else {
                        single = false;
                        doubleQuote = false;
                        backtick = false;
                    }
                } else if (bracket && c == ']') {
                    if (next == ']') {
                        i++;
                    } else {
                        bracket = false;
                    }
                }
                continue;
            }
            if (c == '-' && next == '-') {
                lineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                blockComment = true;
                i++;
                continue;
            }
            if (c == '\'') {
                single = true;
                continue;
            }
            if (c == '"') {
                doubleQuote = true;
                continue;
            }
            if (c == '[') {
                bracket = true;
                continue;
            }
            if (c == '`') {
                backtick = true;
                continue;
            }
            if (c == '(') {
                depth++;
                continue;
            }
            if (c == ')') {
                if (depth > 0) {
                    depth--;
                }
                continue;
            }
            if (depth == 0 && matchesToken(sql, i, token, keyword)) {
                result.add(i);
            }
        }
        return result;
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

    private static boolean endsInLineComment(String sql) {
        boolean single = false;
        boolean doubleQuote = false;
        boolean bracket = false;
        boolean backtick = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n' || c == '\r') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (single || doubleQuote || bracket || backtick) {
                if ((single && c == '\'') || (doubleQuote && c == '"') || (backtick && c == '`')) {
                    if (next == c) {
                        i++;
                    } else {
                        single = false;
                        doubleQuote = false;
                        backtick = false;
                    }
                } else if (bracket && c == ']') {
                    if (next == ']') {
                        i++;
                    } else {
                        bracket = false;
                    }
                }
                continue;
            }
            if (c == '-' && next == '-') {
                lineComment = true;
                i++;
            } else if (c == '/' && next == '*') {
                blockComment = true;
                i++;
            } else if (c == '\'') {
                single = true;
            } else if (c == '"') {
                doubleQuote = true;
            } else if (c == '[') {
                bracket = true;
            } else if (c == '`') {
                backtick = true;
            }
        }
        return lineComment;
    }
}
