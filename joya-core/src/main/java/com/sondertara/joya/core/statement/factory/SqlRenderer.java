package com.sondertara.joya.core.statement.factory;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * 统一 SQL 文本渲染器 —— 全仓唯一负责组装标准 <b>DML</b>（INSERT / UPDATE / DELETE）SQL 字符串的组件。
 *
 * <p>各 {@code *StatementFactory} 仅负责决定“参与的列、参数与元数据”，
 * 最终 SQL 字符串统一交由本类生成，彻底消除单条与批处理之间的重复拼接。
 *
 * <p><b>能力边界（已知技术债）</b>：DQL（SELECT）尚未收敛到本类 ——
 * 复杂查询仍由 {@code Query.renderStatement} 直接拼装，方言层另有分页/计数包装。
 * 此处不宣称覆盖 DQL，避免注释承诺大于实际能力；统一工作待后续单独变更完成。
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
        if (whereSql != null && !whereSql.isEmpty()) {
            sb.append(" WHERE ").append(whereSql);
        }
        return sb.toString();
    }

    /**
     * 渲染 DELETE 语句文本。
     */
    public static String renderDelete(String tableName, @Nullable String whereSql) {
        if (whereSql == null || whereSql.isEmpty()) {
            return "DELETE FROM " + tableName;
        }
        return "DELETE FROM " + tableName + " WHERE " + whereSql;
    }

    /**
     * 渲染单表 SELECT 语句文本。
     */
    public static String renderSelect(String selectPart, String tableName, @Nullable String whereSql, @Nullable String extPart) {
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT ").append(selectPart).append(" FROM ").append(tableName);
        if (whereSql != null && !whereSql.isEmpty()) {
            sb.append(" WHERE ").append(whereSql);
        }
        if (extPart != null && !extPart.isBlank()) {
            sb.append(' ').append(extPart.trim());
        }
        return sb.toString();
    }
}
