package com.sondertara.joya.core.naming;

import org.jspecify.annotations.Nullable;

/**
 * 物理表名解析策略 —— 在 SQL 渲染期把「逻辑表」映射为「最终写入 SQL 的表名」。
 *
 * <p>这是 Joya 对动态表名（按月分表、按租户分表、schema 切换）的唯一扩展点：
 * 表名在生成 SQL 的那一刻就确定，不存在"先拼字符串再全文替换"的二次改写。
 *
 * <p>实现约定：
 * <ul>
 *   <li>入参已完成大小写等格式策略处理，返回值将原样写入 SQL；</li>
 *   <li>必须是无副作用的纯函数。不要读取 {@code ThreadLocal} 等隐式上下文——
 *       那会把 Joya 刻意去除的隐式状态重新引回来；</li>
 *   <li>需要按请求路由时，应为每个请求构造独立的 {@code JoyaClient} 视图（共享 DataSource、不同配置），
 *       把路由作为显式值传递。</li>
 * </ul>
 */
@FunctionalInterface
public interface TableNameResolver {

    /**
     * @param schema 已格式化的 schema 名，可能为 {@code null}
     * @param table  已格式化的表名，不含 schema
     * @return 可直接写入 SQL 的最终表名（可带 schema）
     */
    String resolve(@Nullable String schema, String table);

    /** 恒等解析：有 schema 时拼成 {@code schema.table}，否则返回裸表名。 */
    TableNameResolver IDENTITY = (schema, table) -> schema == null ? table : schema + "." + table;
}
