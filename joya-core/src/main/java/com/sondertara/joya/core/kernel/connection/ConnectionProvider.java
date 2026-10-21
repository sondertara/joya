package com.sondertara.joya.core.kernel.connection;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.OptionalInt;

/**
 * 连接来源端口（策略）。
 *
 * <p>执行器（{@code JdbcExecutor}）只通过本端口借还连接，<b>不读取任何线程级状态</b>。
 * 连接从哪里来、用完如何处理，完全由实现决定：
 * <ul>
 *   <li>{@link DataSourceConnectionProvider}：自动提交模式，每次借出、用完归还。</li>
 *   <li>{@code TxConnectionProvider}：绑定某个事务连接，归还为空操作。</li>
 *   <li>Spring 等外部事务桥接：由适配模块实现，核心不感知。</li>
 * </ul>
 */
public interface ConnectionProvider {

    /** 借出一条连接。 */
    Connection acquire() throws SQLException;

    /** 归还由 {@link #acquire()} 借出的连接。 */
    void release(Connection connection) throws SQLException;

    /** 语句级超时（秒）；未设置时为空。事务超时通过此处下沉到每条语句。 */
    default OptionalInt queryTimeoutSeconds() {
        return OptionalInt.empty();
    }
}
