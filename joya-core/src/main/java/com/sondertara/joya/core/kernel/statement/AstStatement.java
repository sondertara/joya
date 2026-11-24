package com.sondertara.joya.core.kernel.statement;

import java.util.Objects;

/**
 * 待编译的结构化 AST 语句载体（在线查询与实体变更管线提交给 {@code ExecutionKernel} 的内部契约）。
 *
 * <p>与已完成文本编译的物理 {@link SqlStatement} 严格分离：在线执行路径直接向内核提交本对象，
 * 在完成方言解析与 {@code SqlRewriter} 结构改写后仅由 {@link SqlRenderer} 单趟编译一次，消除双重渲染。
 *
 * @param ast      结构化 SQL 语法树根节点
 * @param metadata 语句级元数据（操作类型、表引用、实体类型、主键等）
 */
public record AstStatement(
        SqlNode ast,
        StatementMetadata metadata
) {

    public AstStatement {
        ast = Objects.requireNonNull(ast, "ast must not be null");
        metadata = metadata == null ? StatementMetadata.empty() : metadata;
    }

    public static AstStatement of(SqlNode ast, StatementMetadata metadata) {
        return new AstStatement(ast, metadata);
    }

    public AstStatement withMetadata(StatementMetadata newMetadata) {
        return new AstStatement(ast, newMetadata);
    }

    public AstStatement withDbType(DbType dbType) {
        return dbType == null || metadata.dbType() == dbType
                ? this
                : new AstStatement(ast, metadata.withDbType(dbType));
    }
}
