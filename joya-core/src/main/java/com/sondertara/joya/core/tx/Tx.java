package com.sondertara.joya.core.tx;

import com.sondertara.joya.core.DataOps;
import com.sondertara.joya.core.EntityOps;
import com.sondertara.joya.core.SqlOps;
import com.sondertara.joya.core.query.dsl.Query;

import java.util.function.Consumer;

/**
 * 事务句柄 —— <b>显式值，不是环境态</b>（不变量 I1）。
 *
 * <p>这是本架构相对 Spring 环境态最本质的区别：事务是一个可以传递的对象，
 * 而不是"线程上隐式的状态"。由此带来的收益：
 * <ul>
 *   <li>不存在自调用失效（同类方法互调丢失事务）</li>
 *   <li>不存在虚拟线程 / 线程池下的静默错乱与泄漏</li>
 *   <li>不存在"我以为在事务里"——在不在取决于你用的是不是 {@code Tx}</li>
 * </ul>
 *
 * <p>{@code Tx} 实现 {@link DataOps}：{@code tx.from(User.class)} / {@code tx.of(User.class)} / {@code tx.sql(...)} 与
 * {@code joya.from(...)} / {@code joya.of(...)} / {@code joya.sql(...)} 写法完全一致，只是 SQL 落在本事务的连接上。
 */
public interface Tx extends DataOps, AutoCloseable {

    /** 本事务上的统一查询视图。 */
    @Override
    <T> Query<T> from(Class<T> entityType);

    /** 本事务上的实体操作视图。 */
    @Override
    <T> EntityOps<T> of(Class<T> entityType);

    /** 本事务上的裸 SQL 视图。 */
    @Override
    SqlOps sql(String sql, Object... args);

    /**
     * 保存点子事务：内层失败只回滚到保存点，<b>不</b>标记全局仅回滚，外层可继续并提交。
     * 支持保存点内再开保存点。
     */
    void savepoint(TxConsumer body);

    /**
     * 带返回值的保存点子事务：内层失败只回滚到保存点，<b>不</b>标记全局仅回滚，外层可继续并提交。
     */
    <R> R savepointCall(TxCallback<R> body);

    /** 注册提交成功后执行的回调（如发送消息）。回调异常只记录日志，不影响已提交的数据。 */
    void afterCommit(Runnable callback);

    /** 注册事务结束后执行的回调（无论提交还是回滚，恰好一次）。 */
    void afterCompletion(Consumer<TxOutcome> callback);

    // ===== 生命周期 =====

    void commit();

    void rollback();

    /** 标记仅回滚：业务校验失败但不想抛异常时使用。 */
    void setRollbackOnly();

    boolean isRollbackOnly();

    /** 结束事务并归还连接；未提交的部分一律回滚。幂等。 */
    @Override
    void close();
}
