package com.sondertara.joya.core.kernel.tx;

import com.sondertara.joya.core.kernel.JoyaTemplate;
import com.sondertara.joya.core.tx.Tx;
import com.sondertara.joya.core.tx.TxOptions;

/**
 * 事务引擎扩展点（SPI）：负责在指定 {@link JoyaTemplate} 与 {@link TxOptions} 下开启显式事务句柄 {@link Tx}。
 *
 * <ul>
 *   <li>纯 JDBC 环境：默认使用 {@link com.sondertara.joya.core.tx.JdbcTxEngine} 直接管理物理连接与还原点。</li>
 *   <li>Spring 环境：由 {@code SpringTxEngine} 委托给 {@code PlatformTransactionManager}，实现与 {@code @Transactional}
 *       及主 ORM 框架共享同一条连接与事务生命周期。</li>
 * </ul>
 */
@FunctionalInterface
public interface TxEngine {

    /**
     * 开启一个事务句柄。
     *
     * @param rootTemplate 客户端根执行模板
     * @param options      事务配置选项
     * @return 显式事务句柄 {@link Tx}
     */
    Tx begin(JoyaTemplate rootTemplate, TxOptions options);
}
