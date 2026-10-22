package com.sondertara.joya.core.kernel.statement;

import com.sondertara.joya.core.kernel.dialect.DbType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
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

    /**
     * 直接按列值绑定（批处理热路径使用，{@link #DEFAULT} 实现零 {@link SqlParameter} 对象分配）。
     */
    default void bindValue(PreparedStatement statement, int index, String columnName, Object value) throws SQLException {
        bind(statement, index, SqlParameter.of(index, columnName, value, ParameterSource.RAW));
    }

    /**
     * 直接绑定无列名的参数值。
     */
    default void bindValue(PreparedStatement statement, int index, Object value) throws SQLException {
        bindValue(statement, index, null, value);
    }

    /** 默认绑定：零额外对象分配，含 java.util.Date / OffsetDateTime / Oracle 及跨驱动 NULL 兼容。 */
    ParameterBinder DEFAULT = new ParameterBinder() {
        @Override
        public void bind(PreparedStatement statement, int index, SqlParameter parameter) throws SQLException {
            bindRawValue(statement, index, parameter.value());
        }

        @Override
        public void bindValue(PreparedStatement statement, int index, String columnName, Object value) throws SQLException {
            bindRawValue(statement, index, value);
        }

        @Override
        public void bindValue(PreparedStatement statement, int index, Object value) throws SQLException {
            bindRawValue(statement, index, value);
        }
    };

    private static void bindRawValue(PreparedStatement statement, int index, Object value) throws SQLException {
        if (value == null) {
            bindNull(statement, index);
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
    }

    static void bindNull(PreparedStatement statement, int index) throws SQLException {
        try {
            statement.setNull(index, Types.NULL);
        } catch (SQLException e) {
            try {
                statement.setNull(index, Types.VARCHAR);
            } catch (SQLException ignored) {
                statement.setObject(index, null);
            }
        }
    }

    private static boolean isOracle(PreparedStatement statement) {
        try {
            Connection conn = statement.getConnection();
            return conn != null && DbType.oracle.equals(DbType.of(conn));
        } catch (Exception ignored) {
            return false;
        }
    }
}
