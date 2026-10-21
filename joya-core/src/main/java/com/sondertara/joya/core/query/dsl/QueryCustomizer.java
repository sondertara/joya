package com.sondertara.joya.core.query.dsl;

/**
 * 查询定制扩展点 —— 由 {@link Query#apply(QueryCustomizer)} 调用。
 *
 * <p>用于把协议相关的查询条件（例如前端分页参数 {@code PageQueryParam}）以适配器形式接入，
 * 查询内核不感知任何具体协议。定制器只能通过 {@link QueryScope} 追加「查哪些行」的条件，不能修改投影。
 */
@FunctionalInterface
public interface QueryCustomizer {

    /**
     * 向查询追加条件与排序。
     *
     * @param scope 受限的查询视图
     */
    void customize(QueryScope scope);
}
