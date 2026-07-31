package com.sondertara.joya.core.jdbc.support.listener;

/**
 * @author huangxiaohu
 */
public interface EntityListener {
    /**
     * @param entity
     */
    default void prePersist(Object entity) {

    }

    /**
     * @param entity
     */
    default void postPersist(Object entity) {
    }


    /**
     * @param entity
     */
    default void preUpdate(Object entity) {
    }

    /**
     * @param entity
     */
    default void postUpdate(Object entity) {
    }

    /**
     * @param entity
     */
    default void preRemove(Object entity) {
    }

    /**
     * @param entity
     */
    default void postRemove(Object entity) {
    }
}
