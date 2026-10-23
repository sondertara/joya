package com.sondertara.joya.core.naming;

import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;

/**
 * SQL 渲染与标识符命名策略。
 *
 * <p>遵循“显式注解优先”原则：
 * <ul>
 *   <li>当实体或属性未显式声明 {@code @Table(name)} / {@code @Column(name)}，或显式开启了全局大小写强制转换时，按策略转换。</li>
 *   <li>当属性通过 {@code @Column(name = "OrderNo")} 显式声明了混合大小写名称且未开启全局 {@code columnUppercase} 时，原样保留注解文本。</li>
 * </ul>
 *
 * <p>表名解析（动态分表 / schema 切换）通过 {@link TableNameResolver} 在渲染期完成，
 * 不再存在执行期的字符串改写。
 *
 * @param maxJoinTable      单次查询允许的最大关联表数量（默认 10）
 * @param columnUppercase   是否将列名转为大写
 * @param tableUppercase    是否将表名（含 schema）转为大写
 * @param tableNameResolver 物理表名解析策略
 */
public record NamingPolicy(
        int maxJoinTable,
        boolean columnUppercase,
        boolean tableUppercase,
        TableNameResolver tableNameResolver
) {

    public static final int DEFAULT_MAX_JOIN_TABLE = 10;

    public static final NamingPolicy DEFAULT =
            new NamingPolicy(DEFAULT_MAX_JOIN_TABLE, false, false, TableNameResolver.IDENTITY);

    /** 兼容构造：未指定表名解析策略时使用恒等解析。 */
    public NamingPolicy(int maxJoinTable, boolean columnUppercase, boolean tableUppercase) {
        this(maxJoinTable, columnUppercase, tableUppercase, TableNameResolver.IDENTITY);
    }

    public NamingPolicy {
        if (maxJoinTable <= 0) {
            maxJoinTable = DEFAULT_MAX_JOIN_TABLE;
        }
        tableNameResolver = Objects.requireNonNullElse(tableNameResolver, TableNameResolver.IDENTITY);
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

    /**
     * 解析最终写入 SQL 的表名：先按大小写策略格式化，再交给 {@link TableNameResolver}。
     *
     * @param schema 原始 schema 名，可能为 {@code null}
     * @param table  原始表名（不含 schema）
     */
    public String resolveTable(@Nullable String schema, String table) {
        Objects.requireNonNull(table, "table name must not be null");
        String formattedSchema = schema == null || schema.isBlank() ? null : formatTable(schema);
        return tableNameResolver.resolve(formattedSchema, formatTable(table));
    }
}
