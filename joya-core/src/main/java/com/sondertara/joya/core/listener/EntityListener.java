package com.sondertara.joya.core.listener;

import com.sondertara.joya.core.kernel.support.Ordered;

/**
 * 实体生命周期监听器。
 *
 * <p>既支持实体自身实现（历史用法），也支持通过 {@code JoyaClient.Builder.entityListener(...)}
 * 注册全局监听器；全局监听器可用 {@link #supports(Class)} 过滤实体类型。
 *
 * <p><b>触发边界</b>（框架不为了触发监听器而隐式 SELECT）：
 *
 * <table border="1">
 *   <caption>按入口的触发矩阵</caption>
 *   <tr><th>入口</th><th>persist 回调</th><th>update 回调</th><th>remove 回调</th></tr>
 *   <tr><td>{@code insert(entity)} / {@code insertAll}</td><td>✅ prePersist / postPersist</td><td>—</td><td>—</td></tr>
 *   <tr><td>{@code update(entity)} / {@code patch(entity)}</td><td>—</td><td>✅ preUpdate / postUpdate</td><td>—</td></tr>
 *   <tr><td>{@code updateAll} / {@code patchAll}</td><td>—</td><td>✅ 逐行 preUpdate / postUpdate</td><td>—</td></tr>
 *   <tr><td>{@code save} / {@code saveAll}</td><td>✅ 不存在分支</td><td>✅ 存在分支</td><td>—</td></tr>
 *   <tr><td>{@code delete(entity)} / {@code deleteAll}</td><td>—</td><td>—</td><td>✅ preRemove / postRemove</td></tr>
 *   <tr><td>{@code deleteById} / {@code deleteByIds} / {@code truncate}</td><td>—</td><td>—</td><td>❌ 不触发</td></tr>
 *   <tr><td>{@code EntityInsert.values(...)} 列级构建器</td><td>❌ 不触发</td><td>—</td><td>—</td></tr>
 *   <tr><td>{@code EntityUpdate} / {@code EntityDelete} 条件构建器</td><td>—</td><td>❌ 不触发</td><td>❌ 不触发</td></tr>
 * </table>
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
