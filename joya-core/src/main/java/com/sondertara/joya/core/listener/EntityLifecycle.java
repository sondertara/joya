package com.sondertara.joya.core.listener;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 实体生命周期统一派发器。
 *
 * <p>替代散落在 Plan/Deleter 中的 {@code instanceof EntityListener} 判断：
 * 单条与批量共享同一套顺序，实体自身监听器 + 全局监听器按 {@link EntityListener#order()} 排序。
 */
public final class EntityLifecycle {

    private static final Logger log = LoggerFactory.getLogger(EntityLifecycle.class);

    public static final EntityLifecycle EMPTY = new EntityLifecycle(List.of());

    private final List<EntityListener> globalListeners;
    private final ClassValue<List<EntityListener>> globalForType = new ClassValue<>() {
        @Override
        protected List<EntityListener> computeValue(Class<?> type) {
            if (globalListeners.isEmpty()) {
                return List.of();
            }
            List<EntityListener> matched = new ArrayList<>(globalListeners.size());
            for (EntityListener listener : globalListeners) {
                if (listener.supports(type)) {
                    matched.add(listener);
                }
            }
            return sort(matched);
        }
    };

    public EntityLifecycle(List<EntityListener> globalListeners) {
        this.globalListeners = sort(globalListeners);
    }

    public void prePersist(Object entity) {
        for (EntityListener listener : listenersFor(entity)) {
            listener.prePersist(entity);
        }
    }

    public void postPersist(Object entity) {
        for (EntityListener listener : listenersFor(entity)) {
            try {
                listener.postPersist(entity);
            } catch (RuntimeException e) {
                log.warn("EntityListener {} failed; persistence result is not affected", "postPersist", e);
            }
        }
    }

    public void preUpdate(Object entity) {
        for (EntityListener listener : listenersFor(entity)) {
            listener.preUpdate(entity);
        }
    }

    public void postUpdate(Object entity) {
        for (EntityListener listener : listenersFor(entity)) {
            try {
                listener.postUpdate(entity);
            } catch (RuntimeException e) {
                log.warn("EntityListener {} failed; persistence result is not affected", "postUpdate", e);
            }
        }
    }

    public void preRemove(Object entity) {
        for (EntityListener listener : listenersFor(entity)) {
            listener.preRemove(entity);
        }
    }

    public void postRemove(Object entity) {
        for (EntityListener listener : listenersFor(entity)) {
            try {
                listener.postRemove(entity);
            } catch (RuntimeException e) {
                log.warn("EntityListener {} failed; persistence result is not affected", "postRemove", e);
            }
        }
    }

    /**
     * 实体自身监听器在前，全局监听器随后；最终按 order + 类名排序。
     */
    public boolean hasListenersFor(Object entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        return entity instanceof EntityListener || !globalForType.get(entity.getClass()).isEmpty();
    }

    public List<EntityListener> listenersFor(Object entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        List<EntityListener> globals = globalForType.get(entity.getClass());
        if (!(entity instanceof EntityListener self)) {
            return globals;
        }
        if (globals.isEmpty()) {
            return List.of(self);
        }
        List<EntityListener> listeners = new ArrayList<>(globals.size() + 1);
        listeners.add(self);
        listeners.addAll(globals);
        return sort(listeners);
    }

    private static List<EntityListener> sort(List<EntityListener> listeners) {
        if (listeners == null || listeners.isEmpty()) {
            return List.of();
        }
        List<EntityListener> sorted = new ArrayList<>(listeners);
        sorted.sort(Comparator.comparingInt(EntityListener::order)
                .thenComparing(l -> l.getClass().getName()));
        return List.copyOf(sorted);
    }
}
