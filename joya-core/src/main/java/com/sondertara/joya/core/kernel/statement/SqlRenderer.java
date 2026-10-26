package com.sondertara.joya.core.kernel.statement;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * 统一 SQL 文本渲染器 —— 全仓唯一负责组装标准 DQL / DML SQL 字符串的组件。
 *
 * <p>各 {@code *StatementFactory} 与查询 DSL 只负责决定“参与的列、条件片段与参数”，
 * 最终 SQL 文本统一交由本类生成，消除单条、批处理、查询流之间的重复拼接。
 *
 * <p><b>能力边界</b>：方言层的分页 / 计数包装（{@code kernel.dialect}）属于数据库特有语法，
 * 仍由各方言自行拼接，不在本类职责内。
 */
public final class SqlRenderer {

    private SqlRenderer() {
    }

    /**
     * 渲染 INSERT 语句文本（当 {@code columns} 为空时生成 {@code INSERT INTO table DEFAULT VALUES}）。
     */
    public static String renderInsert(String tableName, List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            return "INSERT INTO " + tableName + " DEFAULT VALUES";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("INSERT INTO ").append(tableName)
                .append(" (").append(String.join(", ", columns)).append(") VALUES (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('?');
        }
        return sb.append(')').toString();
    }

    /**
     * 渲染 UPDATE 语句文本。
     */
    public static String renderUpdate(String tableName, List<String> setColumns, @Nullable String whereSql) {
        StringBuilder sb = new StringBuilder();
        sb.append("UPDATE ").append(tableName).append(" SET ");
        for (int i = 0; i < setColumns.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(setColumns.get(i)).append(" = ?");
        }
        if (notBlank(whereSql)) {
            sb.append(" WHERE ").append(whereSql);
        }
        return sb.toString();
    }

    /**
     * 渲染 DELETE 语句文本。
     */
    public static String renderDelete(String tableName, @Nullable String whereSql) {
        if (!notBlank(whereSql)) {
            return "DELETE FROM " + tableName;
        }
        return "DELETE FROM " + tableName + " WHERE " + whereSql;
    }

    /**
     * 渲染单表 SELECT 语句文本（{@code extPart} 原样追加在 WHERE 之后，用于方言/扩展子句）。
     */
    public static String renderSelect(String selectPart, String tableName,
                                      @Nullable String whereSql, @Nullable String extPart) {
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT ").append(selectPart).append(" FROM ").append(tableName);
        if (notBlank(whereSql)) {
            sb.append(" WHERE ").append(whereSql);
        }
        if (notBlank(extPart)) {
            sb.append(' ').append(extPart.trim());
        }
        return sb.toString();
    }

    /**
     * 渲染完整查询流 SELECT 文本（DISTINCT / WHERE / GROUP BY / HAVING / ORDER BY）。
     *
     * <p>所有子句均为“已渲染片段”：参数收集仍由查询构建器负责，
     * 本方法只做纯字符串组装，便于快照测试与单点维护。
     *
     * @param selectPart 投影列片段（已用逗号连接）
     * @param fromClause FROM + JOIN 片段
     * @param groupBySql GROUP BY 列片段（不含关键字），可空
     * @param havingSql  HAVING 条件片段（不含关键字），可空
     * @param orderBySql ORDER BY 排序片段（不含关键字），可空
     */
    public static String renderSelect(boolean distinct,
                                      String selectPart,
                                      String fromClause,
                                      @Nullable String whereSql,
                                      @Nullable String groupBySql,
                                      @Nullable String havingSql,
                                      @Nullable String orderBySql) {
        StringBuilder sb = new StringBuilder("SELECT ");
        if (distinct) {
            sb.append("DISTINCT ");
        }
        sb.append(selectPart).append(" FROM ").append(fromClause);
        if (notBlank(whereSql)) {
            sb.append(" WHERE ").append(whereSql);
        }
        if (notBlank(groupBySql)) {
            sb.append(" GROUP BY ").append(groupBySql);
        }
        if (notBlank(havingSql)) {
            sb.append(" HAVING ").append(havingSql);
        }
        if (notBlank(orderBySql)) {
            sb.append(" ORDER BY ").append(orderBySql);
        }
        return sb.toString();
    }

    /**
     * 渲染分组/去重场景的计数包装：{@code SELECT COUNT(*) FROM (inner) TEMP_T}。
     */
    public static String renderCountSubquery(String innerSql) {
        return "SELECT COUNT(*) FROM (" + innerSql + ") TEMP_T";
    }

    /**
     * 在已有 SELECT 文本上追加 {@code DISTINCT}；非 SELECT 语句原样返回。
     */
    public static String renderDistinct(String sql) {
        if (sql == null) {
            return null;
        }
        if (sql.regionMatches(true, 0, "SELECT ", 0, 7)) {
            return "SELECT DISTINCT " + sql.substring(7);
        }
        return sql;
    }

    /**
     * 渲染 {@code ORDER BY <items>} 子句。
     */
    public static String renderOrderBy(String orderByItems) {
        return "ORDER BY " + orderByItems;
    }

    /**
     * 渲染 {@code TRUNCATE TABLE <table>}。
     */
    public static String renderTruncate(String table) {
        return "TRUNCATE TABLE " + table;
    }

    private static boolean notBlank(@Nullable String value) {
        return value != null && !value.isBlank();
    }
}
