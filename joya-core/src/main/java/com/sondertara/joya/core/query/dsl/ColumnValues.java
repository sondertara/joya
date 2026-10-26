package com.sondertara.joya.core.query.dsl;

import com.sondertara.joya.utils.ConvertUtils;
import com.sondertara.joya.exceptions.DbException;
import com.sondertara.joya.utils.LambdaUtils;
import com.sondertara.joya.utils.SqlDataUtils;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 按声明类型读取结果集列值：保留 {@code null}，必要时经 {@link ConvertUtils} 转换（如 {@code BigDecimal → Integer}）。
 */
final class ColumnValues {

    private ColumnValues() {
    }

    static Object read(ResultSet rs, int index, Class<?> declaredType) {
        try {
            Object raw = rs.getObject(index);
            if (raw == null) {
                return null;
            }
            if (declaredType == null || declaredType == Object.class) {
                return raw;
            }
            Class<?> target = LambdaUtils.boxed(declaredType);
            if (raw instanceof Number num) {
                verifyIntegralRange(num, target, index);
            }
            if (target.isInstance(raw)) {
                return raw;
            }
            Object converted = null;
            try {
                converted = ConvertUtils.convert(raw, target);
            } catch (RuntimeException ignored) {
                // 回退到 JDBC 按类型取值
            }
            if (converted != null) {
                return converted;
            }
            Object typed = SqlDataUtils.getResultSetValue(rs, index, target);
            if (typed != null && target.isInstance(typed)) {
                return typed;
            }
            throw new DbException("Cannot convert column " + index + " value of type "
                    + raw.getClass().getName() + " to " + target.getName());
        } catch (SQLException e) {
            throw new DbException("Failed to read column " + index, e);
        }
    }

    static Object primitiveDefault(Class<?> type) {
        if (type == null || !type.isPrimitive()) {
            return null;
        }
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == boolean.class) return false;
        if (type == double.class) return 0.0d;
        if (type == float.class) return 0.0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return '\0';
        return null;
    }

    private static void verifyIntegralRange(Number num, Class<?> target, int index) {
        if (target == Integer.class) {
            long val = num.longValue();
            if (val > Integer.MAX_VALUE || val < Integer.MIN_VALUE) {
                throw new DbException("Numeric overflow at column " + index + ": value [" + num
                        + "] overflows Integer; use Long or BigDecimal");
            }
        } else if (target == Short.class) {
            long val = num.longValue();
            if (val > Short.MAX_VALUE || val < Short.MIN_VALUE) {
                throw new DbException("Numeric overflow at column " + index + ": value [" + num
                        + "] overflows Short; use Integer, Long, or BigDecimal");
            }
        } else if (target == Byte.class) {
            long val = num.longValue();
            if (val > Byte.MAX_VALUE || val < Byte.MIN_VALUE) {
                throw new DbException("Numeric overflow at column " + index + ": value [" + num
                        + "] overflows Byte; use Integer, Long, or BigDecimal");
            }
        }
    }
}
