package com.sondertara.joya.core.listener;

/**
 * 实体自实现的写前生命周期钩子（可由实体或项目公共 {@code BaseEntity} 实现，用于填充审计时间、雪花主键等默认值）。
 *
 * <p>触发边界：
 * <ul>
 *   <li>{@code insert(entity)} / {@code insertAll} / {@code EntityInsert.values(entity).execute()}：
 *       在快照与非空主键校验前触发 {@link #prePersist()}；</li>
 *   <li>{@code update(entity)} / {@code patch(entity)} / {@code updateAll} / {@code patchAll}：
 *       在主键校验与快照前触发 {@link #preUpdate()}；</li>
 *   <li>{@code save} / {@code saveAll}：仅当实体实现 {@code EntityListener} 时按存在性触发
 *       {@link #prePersist()} 或 {@link #preUpdate()}（未实现该接口的实体在原生 UPSERT 下零额外探测开销）；</li>
 *   <li>列级/条件级构建器（无源实体）与删除操作不触发。</li>
 * </ul>
 *
 * @author huangxiaohu
 */
public interface EntityListener {

    /**
     * 实体首次持久化写入前调用。
     */
    default void prePersist() {
    }

    /**
     * 实体更新写入前调用。
     */
    default void preUpdate() {
    }
}
