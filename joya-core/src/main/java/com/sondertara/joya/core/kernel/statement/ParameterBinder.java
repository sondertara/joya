package com.sondertara.joya.core.kernel.statement;

import com.sondertara.joya.core.jdbc.support.DbType;
import com.sondertara.joya.utils.JdbcUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * 参数绑定策略：将 {@link SqlParameter} 的值写入 {@link PreparedStatement} 指定位置。
 * <p>
 * 与参数描述（{@link SqlParameter}）分离，使类型转换、特殊类型（JSON/UUID/数组）绑定
 * 可独立扩展，而不污染参数模型本身。
 */
@FunctionalInterface
public interface ParameterBinder {

    void bind(PreparedStatement statement, int index, SqlParameter parameter) throws SQLException;

    /** 默认绑定：复用现有 {@code JdbcExecutor} 的成熟逻辑（含 java.util.Date / OffsetDateTime / Oracle 特例）。 */
    ParameterBinder DEFAULT = (statement, index, parameter) -> {
        Object value = parameter.value();
        if (value == null) {
            statement.setObject(index, null);
            return;
        }
        if (value instanceof java.sql.Date date) {
            statement.setDate(index, date);
        } else if (value instanceof Timestamp timestamp) {
            statement.setTimestamp(index, timestamp);
        } else if (value instanceof Date date) {
            statement.setTimestamp(index, Timestamp.from(date.toInstant()));
        } else if (value instanceof OffsetDateTime odt && isOracle(statement)) {
            if (odt.getYear() < 1905) {
                statement.setTimestamp(index, Timestamp.valueOf(odt.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()));
            } else {
                statement.setTimestamp(index, Timestamp.from(odt.toInstant()));
            }
        } else {
            statement.setObject(index, value);
        }
    };

    private static boolean isOracle(PreparedStatement statement) {
        try {
            Connection conn = statement.getConnection();
            return conn != null && DbType.oracle.equals(JdbcUtils.getDatabaseType(conn));
        } catch (Exception ignored) {
            return false;
        }
    }
}
