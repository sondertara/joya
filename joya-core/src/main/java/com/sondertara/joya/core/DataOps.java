package com.sondertara.joya.core;

import com.sondertara.joya.core.kernel.dialect.DbType;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import com.sondertara.joya.core.query.dsl.Query;

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
     * 获取自定义 {@code JoyaMapper} 子接口的动态代理（支持 {@code @Query} 注解、JPA 方法名派生查询与基础 CRUD）。
     */
    <M> M mapper(Class<M> mapperType);

    /** 获取当前数据操作上下文的客户端配置（默认返回 {@link JoyaSettings#DEFAULT}）。 */
    default JoyaSettings settings() {
        return JoyaSettings.DEFAULT;
    }

    /** 获取当前数据操作上下文绑定的数据库方言类型（默认返回 {@link DbType#other}）。 */
    default DbType dbType() {
        return DbType.other;
    }
}
