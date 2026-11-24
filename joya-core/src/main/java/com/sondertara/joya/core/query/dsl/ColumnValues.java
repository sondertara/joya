package com.sondertara.joya.core.query.dsl;

import com.sondertara.joya.core.kernel.executor.RowMapper;
import com.sondertara.joya.core.metadata.ColumnConverter;
import com.sondertara.joya.exceptions.DbException;
import com.sondertara.joya.utils.ConvertUtils;
import com.sondertara.joya.utils.LambdaUtils;
import com.sondertara.joya.utils.SqlDataUtils;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 按声明类型预绑定列读取策略（单次读取、零异常控制流、消除同列二次读取，并保留精确整型溢出守卫）。
 */
final class ColumnValues {

    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);

    private ColumnValues() {
    }

    static SqlDataUtils.ColumnReader readerFor(@Nullable Class<?> declaredType) {
        return readerFor(declaredType, null);
    }

    static SqlDataUtils.ColumnReader readerFor(@Nullable Class<?> declaredType,
                                               @Nullable ColumnConverter converter) {
        if (converter != null) {
            Class<?> dbType = converter.databaseColumnType();
            SqlDataUtils.ColumnReader dbReader = dbType == Object.class
                    ? SqlDataUtils::getResultSetValue
                    : readerFor(dbType, null);
            return (rs, index, colClassName) -> converter.toEntity(dbReader.read(rs, index, colClassName));
        }
        if (declaredType == null || declaredType == Object.class) {
            return SqlDataUtils::getResultSetValue;
        }
        Class<?> target = LambdaUtils.boxed(declaredType);
        if (isGuardedIntegral(target)) {
            return (rs, index, colClassName) -> readIntegralWithGuard(rs, index, target);
        }
        SqlDataUtils.ColumnReader baseReader = SqlDataUtils.readerFor(target);
        return (rs, index, colClassName) -> {
            Object val = baseReader.read(rs, index, colClassName);
            if (val == null || target.isInstance(val)) {
                return val;
            }
            return ConvertUtils.convert(val, target);
        };
    }

    static <A> RowMapper<A> singleMapper(@Nullable Class<?> declaredType) {
        return singleMapper(declaredType, null);
    }

    @SuppressWarnings("unchecked")
    static <A> RowMapper<A> singleMapper(@Nullable Class<?> declaredType, @Nullable ColumnConverter converter) {
        SqlDataUtils.ColumnReader reader = readerFor(declaredType, converter);
        return new RowMapper<>() {
            @Override
            public RowMapper<A> forResultSet(ResultSet rs) throws SQLException {
                String colClassName = SqlDataUtils.safeGetColumnClassName(rs.getMetaData(), 1);
                return row -> (A) reader.read(row, 1, colClassName);
            }

            @Override
            public A map(ResultSet record) throws SQLException {
                return (A) reader.read(record, 1, null);
            }
        };
    }

    private static boolean isGuardedIntegral(Class<?> boxedTarget) {
        return boxedTarget == Long.class
                || boxedTarget == Integer.class
                || boxedTarget == Short.class
                || boxedTarget == Byte.class;
    }

    private static @Nullable Object readIntegralWithGuard(ResultSet rs, int index, Class<?> target) throws SQLException {
        Object raw = rs.getObject(index);
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number num) {
            verifyIntegralRange(num, target, index);
            if (target.isInstance(num)) {
                return num;
            }
        }
        return ConvertUtils.convert(raw, target);
    }

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
        if (num instanceof BigInteger bi) {
            return bi.compareTo(LONG_MAX) > 0 || bi.compareTo(LONG_MIN) < 0;
        }
        if (num instanceof BigDecimal bd) {
            BigInteger bi = bd.toBigInteger();
            return bi.compareTo(LONG_MAX) > 0 || bi.compareTo(LONG_MIN) < 0;
        }
        if (num instanceof Double || num instanceof Float) {
            double d = num.doubleValue();
            return d > Long.MAX_VALUE || d < Long.MIN_VALUE;
        }
        return false;
    }
}
