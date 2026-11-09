package com.sondertara.joya.core;

import com.sondertara.joya.core.kernel.connection.ConnectionCallback;
import com.sondertara.joya.core.kernel.statement.DbType;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.query.dsl.Query;

import java.io.Reader;

/**
 * 数据操作入口 —— Joya 统一的数据操作门面：{@code 入口.from(实体)}（统一查询）、{@code 入口.of(实体)}（实体写与按主键读）
 * 或 {@code 入口.sql(...)}（参数化 SQL）再接链式调用。
 *
 * <p>{@link JoyaClient}（自动提交）与 {@link com.sondertara.joya.core.tx.Tx}（事务）都实现本接口，
 * 因此<b>事务内外写法完全一致，区别只在于调用对象</b>。仓储代码可直接接收 {@code DataOps}，
 * 由调用方决定传入自动提交入口还是事务：
 *
 * <pre>{@code
 * void transfer(DataOps db, long from, long to, int amount) {
 *     db.sql("update account set bal = bal - ? where id = ?", amount, from).update();
 *     db.sql("update account set bal = bal + ? where id = ?", amount, to).update();
 * }
 * joya.tx(tx -> transfer(tx, 1, 2, 10));   // 原子
 * }</pre>
 */
public interface DataOps {

    /** 统一查询入口（单表、联表、部分列、DTO/Record 投影、分组聚合、分页全部由此进入）。 */
    <T> Query<T> from(Class<T> entityType);

    /** 实体域操作入口（实体级写操作、列级变更构建器、按主键快捷读）。 */
    <T> EntityOps<T> of(Class<T> entityType);

    /** 裸 SQL 入口；占位符为 {@code ?}。 */
    SqlOps sql(String sql, Object... args);

    /** 已构建 {@link SqlStatement} 执行入口（完整保留表名与操作类型元数据）。 */
    SqlOps sql(SqlStatement statement);

    /**
     * 获取自定义 {@code JoyaMapper} 子接口的动态代理（支持基础 CRUD 与 {@code default} 自定义查询方法）。
     */
    <M> M mapper(Class<M> mapperType);

    /**
     * 连接级逃生舱：在<b>当前数据操作上下文所用的连接</b>上执行底层 JDBC 回调（不经过改写/观测管线，调用方自负其责）。
     *
     * <p>在 {@code JoyaClient} 上每次借还一条自动提交连接；在 {@link com.sondertara.joya.core.tx.Tx} 上则运行于
     * 该事务的连接，随事务一同提交 / 回滚——事务内不会静默落到事务之外。
     */
    <T> T withConnection(ConnectionCallback<T> action);

    /**
     * 执行 SQL 脚本流（DDL / 初始化数据，不经过改写/观测管线）。与 {@link #withConnection} 一样，
     * 在 {@code Tx} 上运行于事务连接。
     */
    void script(Reader reader);

    /**
     * 获取当前数据操作上下文的客户端配置（默认返回 {@link JoyaSettings#DEFAULT}）。
     *
     * <p>在 {@link com.sondertara.joya.core.tx.Tx} 上，事务提交/回滚/关闭后访问会抛出
     * {@link com.sondertara.joya.core.tx.IllegalTxStateException}，避免误用已完成的事务上下文。
     */
    default JoyaSettings settings() {
        return JoyaSettings.DEFAULT;
    }

    /**
     * 获取当前数据操作上下文绑定的数据库方言类型（默认返回 {@link DbType#other}）。
     *
     * <p>默认 {@code DialectStrategy.cached(dataSource)} 或 {@code fixed(...)} 策略下直接返回已缓存的方言类型；
     * 若显式配置了 {@code DialectStrategy.perConnection()} 动态路由策略，则每次调用会通过当前连接提供者探测一次。
     *
     * <p>在 {@link com.sondertara.joya.core.tx.Tx} 上，事务提交/回滚/关闭后访问同样会抛出
     * {@link com.sondertara.joya.core.tx.IllegalTxStateException}。
     */
    default DbType dbType() {
        return DbType.other;
    }
}
