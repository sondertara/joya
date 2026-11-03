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

    private static final java.math.BigInteger LONG_MAX = java.math.BigInteger.valueOf(Long.MAX_VALUE);
    private static final java.math.BigInteger LONG_MIN = java.math.BigInteger.valueOf(Long.MIN_VALUE);

    private static void verifyIntegralRange(Number num, Class<?> target, int index) {
        if (target == Long.class) {
            if (overflowsLong(num)) {
                throw new DbException("Numeric overflow at column " + index + ": value [" + num
                        + "] overflows Long; use BigInteger or BigDecimal");
            }
        } else if (target == Integer.class) {
            if (overflowsLong(num) || num.longValue() > Integer.MAX_VALUE || num.longValue() < Integer.MIN_VALUE) {
                throw new DbException("Numeric overflow at column " + index + ": value [" + num
                        + "] overflows Integer; use Long or BigDecimal");
            }
        } else if (target == Short.class) {
            if (overflowsLong(num) || num.longValue() > Short.MAX_VALUE || num.longValue() < Short.MIN_VALUE) {
                throw new DbException("Numeric overflow at column " + index + ": value [" + num
                        + "] overflows Short; use Integer, Long, or BigDecimal");
            }
        } else if (target == Byte.class) {
            if (overflowsLong(num) || num.longValue() > Byte.MAX_VALUE || num.longValue() < Byte.MIN_VALUE) {
                throw new DbException("Numeric overflow at column " + index + ": value [" + num
                        + "] overflows Byte; use Integer, Long, or BigDecimal");
            }
        }
    }

    private static boolean overflowsLong(Number num) {
        if (num instanceof java.math.BigInteger bi) {
            return bi.compareTo(LONG_MAX) > 0 || bi.compareTo(LONG_MIN) < 0;
        }
        if (num instanceof java.math.BigDecimal bd) {
            java.math.BigInteger bi = bd.toBigInteger();
            return bi.compareTo(LONG_MAX) > 0 || bi.compareTo(LONG_MIN) < 0;
        }
        if (num instanceof Double || num instanceof Float) {
            double d = num.doubleValue();
            return d > Long.MAX_VALUE || d < Long.MIN_VALUE;
        }
        return false;
    }
}
