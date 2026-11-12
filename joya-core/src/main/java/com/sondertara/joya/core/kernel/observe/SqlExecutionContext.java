package com.sondertara.joya.core.kernel.observe;

import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.kernel.statement.StatementMetadata;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * 一次 SQL 执行的只读上下文，供 {@link SqlExecutionListener} 使用。
 *
 * <p>{@link #statement()} 始终代表<b>实际送入 JDBC 执行器</b>的物理语句（含方言分页包装与 {@code COUNT(*)} 包装）；
 * 当处于分页执行（{@link #inPage()} 为 {@code true}）时，可通过 {@link #logicalStatement()} 获取改写完成但未经方言包装的逻辑语句，
 * 并通过 {@link #offset()} / {@link #limit()} 读取显式分页坐标。
 *
 * <p>{@code attributes} 为单次物理执行的内部横切属性包：由准备阶段的 {@code SqlRewriter} 写入
 * （如链路追踪标签、改写标记），在此以不可变视图暴露给观测监听器。
 */
public record SqlExecutionContext(
        SqlStatement statement,
        boolean batch,
        Map<String, Object> attributes,
        @Nullable PaginationView page
) {

    /** 批量模式标记，值为 {@link Boolean#TRUE}。 */
    public static final String ATTR_BATCH = "joya.batch";

    /** 批量总行数（{@link Integer}）。 */
    public static final String ATTR_BATCH_ROWS = "joya.batch.rows";

    /**
     * 一次分页请求的坐标与包装前的逻辑语句。
     */
    public record PaginationView(SqlStatement logicalStatement, long offset, long limit) {
        public PaginationView {
            logicalStatement = Objects.requireNonNull(logicalStatement, "logicalStatement must not be null");
            if (offset < 0) {
                throw new IllegalArgumentException("offset must be >= 0: " + offset);
            }
            if (limit < 0) {
                throw new IllegalArgumentException("limit must be >= 0: " + limit);
            }
        }
    }

    /** 兼容旧构造：非分页执行。 */
    public SqlExecutionContext(SqlStatement statement, boolean batch, Map<String, Object> attributes) {
        this(statement, batch, attributes, null);
    }

    public SqlExecutionContext {
        statement = Objects.requireNonNull(statement, "statement must not be null");
        attributes = attributes == null ? Collections.emptyMap() : Collections.unmodifiableMap(attributes);
    }

    public StatementMetadata metadata() {
        return statement.metadata();
    }

    /** 当前观测是否属于分页请求（覆盖分页数据语句与分页关联计数语句）。 */
    public boolean inPage() {
        return page != null;
    }

    /**
     * 返回 {@code SqlRewriter} 改写完成、方言分页/{@code COUNT(*)} 包装之前的逻辑语句；
     * 非分页执行时直接返回 {@link #statement()} 同一引用（零额外分配）。
     *
     * <p>注意：经过方言分页或 {@code COUNT(*)} 包装后的物理 {@link #statement()} 其 {@code ast()} 为 {@code null}；
     * 需要读取结构化 AST 的监听器请访问 {@code logicalStatement().ast()}。
     */
    public SqlStatement logicalStatement() {
        return page == null ? statement : page.logicalStatement();
    }

    /** 分页起始偏移量；非分页执行返回 {@code -1L}。 */
    public long offset() {
        return page == null ? -1L : page.offset();
    }

    /** 分页每页截取上限；非分页执行返回 {@code -1L}。 */
    public long limit() {
        return page == null ? -1L : page.limit();
    }
}
