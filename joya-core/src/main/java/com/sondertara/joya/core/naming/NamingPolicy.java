package com.sondertara.joya.core.naming;

import java.util.Locale;

/**
 * SQL 渲染与标识符命名策略。
 *
 * <p>遵循“显式注解优先”原则：
 * <ul>
 *   <li>当实体或属性未显式声明 {@code @Table(name)} / {@code @Column(name)}，或显式开启了全局大小写强制转换时，按策略转换。</li>
 *   <li>当属性通过 {@code @Column(name = "OrderNo")} 显式声明了混合大小写名称且未开启全局 {@code columnUppercase} 时，原样保留注解文本。</li>
 * </ul>
 *
 * @param maxJoinTable    单次查询允许的最大关联表数量（默认 10）
 * @param columnUppercase 是否将列名转为大写
 * @param tableUppercase  是否将表名（含 schema）转为大写
 */
public record NamingPolicy(
        int maxJoinTable,
        boolean columnUppercase,
        boolean tableUppercase
) {

    public static final int DEFAULT_MAX_JOIN_TABLE = 10;

    public static final NamingPolicy DEFAULT = new NamingPolicy(DEFAULT_MAX_JOIN_TABLE, false, false);

    public NamingPolicy {
        if (maxJoinTable <= 0) {
            maxJoinTable = DEFAULT_MAX_JOIN_TABLE;
        }
    }

    public String formatTable(String tableName) {
        if (tableName == null) {
            return null;
        }
        return tableUppercase ? tableName.toUpperCase(Locale.ROOT) : tableName;
    }

    public String formatColumn(String columnName) {
        if (columnName == null) {
            return null;
        }
        return columnUppercase ? columnName.toUpperCase(Locale.ROOT) : columnName;
    }
}
