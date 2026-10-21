package com.sondertara.joya.core.query.dsl;

import com.sondertara.joya.core.query.criterion.WhereCriterion;
import com.sondertara.joya.core.query.pagination.OrderParam;

import java.util.function.UnaryOperator;

/**
 * 提供给 {@link QueryCustomizer} 的受限查询视图：只开放「查哪些行」相关的能力。
 */
public interface QueryScope {

    /** 主表实体类型。 */
    Class<?> rootType();

    /** 追加 WHERE 条件（与其他条件以 AND 合并）。 */
    QueryScope where(UnaryOperator<WhereCriterion> condition);

    /** 追加动态排序（字段名经 from / join 实体元数据白名单校验）。 */
    QueryScope orderBy(OrderParam... orderParams);

    /**
     * 把外部传入的字段名或列名解析为带表别名的列（例如 {@code name} → {@code t0.name}）。
     *
     * <p>只接受 from / join 实体中存在的字段或列，主表优先；非法标识符或未知字段抛 {@link IllegalArgumentException}，
     * 用于防止 SQL 注入。
     */
    String resolveColumn(String fieldOrColumn);
}
