package com.sondertara.joya.core.listener;

import com.sondertara.joya.core.kernel.support.Ordered;

/**
 * 实体生命周期监听器。
 *
 * <p>既支持实体自身实现（历史用法），也支持通过 {@code JoyaClient.Builder.entityListener(...)}
 * 注册全局监听器；全局监听器可用 {@link #supports(Class)} 过滤实体类型。
 *
 * @author huangxiaohu
 */
public interface EntityListener extends Ordered {

    /**
     * 是否处理指定实体类型；默认全部处理。
     */
    default boolean supports(Class<?> entityType) {
        return true;
    }

    @Override
    default int order() {
        return 0;
    }

    default void prePersist(Object entity) {
    }

    default void postPersist(Object entity) {
    }

    default void preUpdate(Object entity) {
    }

    default void postUpdate(Object entity) {
    }

    default void preRemove(Object entity) {
    }

    default void postRemove(Object entity) {
    }
}
