package com.sondertara.joya.spring.autoconfigure;

import com.sondertara.joya.core.JoyaClient;

import javax.sql.DataSource;

/**
 * {@link JoyaClient} 构建自定义器：支持在构建每个客户端时按名称（主库为 {@code "primary"}，扩展库为 {@code joya.datasources.<name>} 的 key）
 * 定制拦截器、配置或方言策略。
 */
@FunctionalInterface
public interface JoyaClientCustomizer {

    /**
     * 对指定客户端的 {@link JoyaClient.Builder} 进行定制。
     *
     * @param clientName 客户端注册名称（如 {@code "primary"}、{@code "order"}）
     * @param dataSource 对应的 {@link DataSource} 实例
     * @param builder    当前正在构建的 {@link JoyaClient.Builder}
     */
    void customize(String clientName, DataSource dataSource, JoyaClient.Builder builder);
}
