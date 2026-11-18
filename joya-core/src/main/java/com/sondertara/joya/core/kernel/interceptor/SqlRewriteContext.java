package com.sondertara.joya.core.kernel.interceptor;

import com.sondertara.joya.core.kernel.statement.DbType;
import com.sondertara.joya.core.kernel.statement.Operation;
import com.sondertara.joya.core.kernel.statement.TableRef;
import org.jspecify.annotations.Nullable;

import java.util.Set;

/**
 * {@link SqlRewriter} 的只读结构化上下文。
 *
 * <p>刻意只暴露语句的静态结构元信息，不暴露可变语句对象或弱类型属性袋：
 * 改写只能通过无状态纯函数返回新 {@code SqlNode} 完成，保证单条、批量及预探测门禁语义完全一致。
 *
 * @param operation  SQL 操作类型
 * @param entityType 关联实体类型（原生 SQL 为 {@code null}）
 * @param dbType     目标数据库方言类型
 * @param tables     本次语句涉及的物理表引用集合
 * @param rootAlias  SELECT 根表别名；UPDATE/DELETE/INSERT 为 {@code null}
 * @param batch      是否处于批量模板改写阶段
 */
public record SqlRewriteContext(
        @Nullable Operation operation,
        @Nullable Class<?> entityType,
        @Nullable DbType dbType,
        Set<TableRef> tables,
        @Nullable String rootAlias,
        boolean batch
) {

    public SqlRewriteContext {
        tables = tables == null ? Set.of() : Set.copyOf(tables);
    }

    /** 是否批量模板探针（与 {@link #batch()} 等价）。 */
    public boolean isBatch() {
        return batch;
    }

    public static SqlRewriteContext of(@Nullable Operation operation,
                                       @Nullable Class<?> entityType,
                                       @Nullable DbType dbType) {
        return new SqlRewriteContext(operation, entityType, dbType, Set.of(), null, false);
    }
}
