package com.sondertara.joya.core.kernel.dialect;

import com.sondertara.joya.core.kernel.statement.ParameterSource;
import com.sondertara.joya.core.kernel.statement.SqlParameter;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.utils.Strings;

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
            StringBuilder sql = new StringBuilder(statement.sql());
            List<SqlParameter> params = new ArrayList<>(statement.parameters());
            if (limit > 0) {
                sql.append(" LIMIT ?");
                appendPageParam(params, limit);
            }
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
            StringBuilder sql = new StringBuilder(statement.sql());
            List<SqlParameter> params = new ArrayList<>(statement.parameters());
            if (offset <= 0) {
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
            String sqlText = statement.sql();
            StringBuilder sql = new StringBuilder(sqlText);
            if (!containsTopLevelOrderBy(sqlText)) {
                sql.append(" ORDER BY (SELECT 0)");
            }
            sql.append(" OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
            List<SqlParameter> params = new ArrayList<>(statement.parameters());
            appendPageParam(params, Math.max(0L, offset));
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
            String sqlString = statement.sql();
            String orderBy = extractOrderByPart(sqlString);
            String distinctStr = "";
            String lowered = sqlString.trim().toLowerCase(Locale.ROOT);
            String body = sqlString.trim();
            if (lowered.startsWith("select")) {
                int idx = 6;
                if (lowered.startsWith("select distinct")) {
                    distinctStr = "DISTINCT ";
                    idx = 15;
                }
                body = body.substring(idx);
            }
            if (Strings.isBlank(orderBy)) {
                orderBy = "ORDER BY CURRENT_TIMESTAMP";
            }
            String pagedSql = "WITH selectTemp AS (SELECT " + distinctStr + "TOP 100 PERCENT "
                    + "ROW_NUMBER() OVER (" + orderBy + ") as __row_number__, " + body
                    + ") SELECT * FROM selectTemp WHERE __row_number__ BETWEEN ? AND ? ORDER BY __row_number__";
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
            String pagedSql = "SELECT * FROM ( SELECT TMP.*, ROWNUM ROW_ID FROM ( "
                    + statement.sql()
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
            String pagedSql = "SELECT * FROM (SELECT TMP_PAGE.*, ROWNUMBER() OVER() AS ROW_ID FROM ( "
                    + statement.sql()
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
            StringBuilder sql = new StringBuilder("SELECT ");
            List<Object> prefixArgs = new ArrayList<>(2);
            if (offset > 0) {
                sql.append("SKIP ? ");
                prefixArgs.add(offset);
            }
            if (limit > 0) {
                sql.append("FIRST ? ");
                prefixArgs.add(limit);
            }
            sql.append("* FROM ( ").append(statement.sql()).append(" ) TEMP_T");

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

    private static void appendPageParam(List<SqlParameter> params, long value) {
        int idx = params.size() + 1;
        params.add(SqlParameter.of(idx, "page_arg_" + idx, value, ParameterSource.RAW));
    }

    private static boolean containsTopLevelOrderBy(String sql) {
        return sql.toLowerCase(Locale.ROOT).contains("order by");
    }

    private static String extractOrderByPart(String sql) {
        String lowered = sql.toLowerCase(Locale.ROOT);
        int idx = lowered.lastIndexOf("order by");
        return idx != -1 ? sql.substring(idx) : "";
    }
}
