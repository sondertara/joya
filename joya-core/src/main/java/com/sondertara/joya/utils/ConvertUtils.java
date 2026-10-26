package com.sondertara.joya.utils;

import com.sondertara.joya.exceptions.DbException;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * 面向 JDBC 结果集与主键的轻量级标量类型转换工具（零外部依赖）。
 */
public final class ConvertUtils {

    private ConvertUtils() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Nullable
    public static <T> T convert(@Nullable Object value, Class<T> targetType) {
        if (value == null) {
            return null;
        }
        if (targetType == null || targetType == Object.class) {
            return (T) value;
        }
        Class<?> boxedTarget = LambdaUtils.boxed(targetType);
        if (boxedTarget.isInstance(value)) {
            return (T) value;
        }

        // 1. CharSequence / String target
        if (boxedTarget == String.class || boxedTarget == CharSequence.class) {
            return (T) value.toString();
        }

        // 2. Number targets
        if (Number.class.isAssignableFrom(boxedTarget)) {
            return (T) convertToNumber(value, boxedTarget);
        }

        // 3. Boolean target
        if (boxedTarget == Boolean.class) {
            return (T) convertToBoolean(value);
        }

        // 4. Character target
        if (boxedTarget == Character.class) {
            String s = value.toString();
            return s.isEmpty() ? null : (T) Character.valueOf(s.charAt(0));
        }

        // 5. Enum target
        if (boxedTarget.isEnum()) {
            return (T) convertToEnum(value, (Class<? extends Enum>) boxedTarget);
        }

        // 6. Temporal & Date targets
        if (boxedTarget == LocalDateTime.class) {
            return (T) convertToLocalDateTime(value);
        }
        if (boxedTarget == LocalDate.class) {
            return (T) convertToLocalDate(value);
        }
        if (boxedTarget == LocalTime.class) {
            return (T) convertToLocalTime(value);
        }
        if (boxedTarget == Instant.class) {
            return (T) convertToInstant(value);
        }
        if (boxedTarget == OffsetDateTime.class) {
            return (T) convertToOffsetDateTime(value);
        }
        if (boxedTarget == ZonedDateTime.class) {
            return (T) convertToZonedDateTime(value);
        }
        if (boxedTarget == Timestamp.class) {
            Instant instant = convertToInstant(value);
            return instant == null ? null : (T) Timestamp.from(instant);
        }
        if (boxedTarget == java.sql.Date.class) {
            LocalDate ld = convertToLocalDate(value);
            return ld == null ? null : (T) java.sql.Date.valueOf(ld);
        }
        if (boxedTarget == Time.class) {
            LocalTime lt = convertToLocalTime(value);
            return lt == null ? null : (T) Time.valueOf(lt);
        }
        if (boxedTarget == Date.class) {
            Instant instant = convertToInstant(value);
            return instant == null ? null : (T) Date.from(instant);
        }

        // 7. UUID / URI / URL
        if (boxedTarget == UUID.class) {
            return (T) UUID.fromString(value.toString().trim());
        }
        if (boxedTarget == URI.class) {
            return (T) URI.create(value.toString().trim());
        }
        if (boxedTarget == URL.class) {
            try {
                return (T) URI.create(value.toString().trim()).toURL();
            } catch (Exception e) {
                throw new DbException("Cannot convert value [" + value + "] to URL", e);
            }
        }

        throw new DbException("Unsupported type conversion from " + value.getClass().getName() + " to " + targetType.getName());
    }

    private static Number convertToNumber(Object value, Class<?> targetType) {
        if (value instanceof Boolean b) {
            value = b ? 1 : 0;
        }
        if (value instanceof Number num) {
            if (targetType == Long.class) return num.longValue();
            if (targetType == Integer.class) return num.intValue();
            if (targetType == Double.class) return num.doubleValue();
            if (targetType == Float.class) return num.floatValue();
            if (targetType == Short.class) return num.shortValue();
            if (targetType == Byte.class) return num.byteValue();
            if (targetType == BigDecimal.class) {
                if (num instanceof BigDecimal bd) return bd;
                if (num instanceof BigInteger bi) return new BigDecimal(bi);
                if (num instanceof Long || num instanceof Integer || num instanceof Short || num instanceof Byte) {
                    return BigDecimal.valueOf(num.longValue());
                }
                return new BigDecimal(num.toString());
            }
            if (targetType == BigInteger.class) {
                if (num instanceof BigInteger bi) return bi;
                if (num instanceof BigDecimal bd) return bd.toBigInteger();
                return BigInteger.valueOf(num.longValue());
            }
            return num;
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        BigDecimal decimal = new BigDecimal(text);
        if (targetType == Long.class) return decimal.longValue();
        if (targetType == Integer.class) return decimal.intValue();
        if (targetType == Double.class) return decimal.doubleValue();
        if (targetType == Float.class) return decimal.floatValue();
        if (targetType == Short.class) return decimal.shortValue();
        if (targetType == Byte.class) return decimal.byteValue();
        if (targetType == BigDecimal.class) return decimal;
        if (targetType == BigInteger.class) return decimal.toBigInteger();
        return decimal;
    }

    private static Boolean convertToBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number num) {
            return num.intValue() != 0;
        }
        String s = value.toString().trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return null;
        }
        return "true".equals(s) || "1".equals(s) || "yes".equals(s) || "y".equals(s) || "on".equals(s);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <E extends Enum<E>> E convertToEnum(Object value, Class<E> enumType) {
        E[] constants = enumType.getEnumConstants();
        if (value instanceof Number num) {
            int ordinal = num.intValue();
            if (constants != null && ordinal >= 0 && ordinal < constants.length) {
                return constants[ordinal];
            }
            throw new DbException("Invalid enum ordinal " + ordinal + " for " + enumType.getName());
        }
        String name = value.toString().trim();
        if (name.isEmpty()) {
            return null;
        }
        if (constants != null) {
            for (E constant : constants) {
                if (constant.name().equals(name) || constant.name().equalsIgnoreCase(name)) {
                    return constant;
                }
            }
            try {
                int ordinal = Integer.parseInt(name);
                if (ordinal >= 0 && ordinal < constants.length) {
                    return constants[ordinal];
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return (E) Enum.valueOf((Class) enumType, name);
    }

    private static Instant convertToInstant(Object value) {
        if (value instanceof Instant i) return i;
        if (value instanceof Timestamp ts) return ts.toInstant();
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant();
        }
        if (value instanceof Time sqlTime) {
            return Instant.ofEpochMilli(sqlTime.getTime());
        }
        if (value instanceof Date d) return d.toInstant();
        if (value instanceof LocalDateTime ldt) return ldt.atZone(ZoneId.systemDefault()).toInstant();
        if (value instanceof LocalDate ld) return ld.atStartOfDay(ZoneId.systemDefault()).toInstant();
        if (value instanceof OffsetDateTime odt) return odt.toInstant();
        if (value instanceof ZonedDateTime zdt) return zdt.toInstant();
        if (value instanceof Number n) return Instant.ofEpochMilli(n.longValue());
        String s = value.toString().trim();
        if (s.isEmpty()) return null;
        try {
            return Instant.parse(s);
        } catch (Exception ignored) {
            LocalDateTime ldt = parseLocalDateTimeString(s);
            return ldt.atZone(ZoneId.systemDefault()).toInstant();
        }
    }

    private static LocalDateTime convertToLocalDateTime(Object value) {
        if (value instanceof LocalDateTime ldt) return ldt;
        if (value instanceof Timestamp ts) return ts.toLocalDateTime();
        if (value instanceof java.sql.Date d) return d.toLocalDate().atStartOfDay();
        if (value instanceof Date d) return LocalDateTime.ofInstant(d.toInstant(), ZoneId.systemDefault());
        if (value instanceof Instant i) return LocalDateTime.ofInstant(i, ZoneId.systemDefault());
        if (value instanceof OffsetDateTime odt) return odt.toLocalDateTime();
        if (value instanceof ZonedDateTime zdt) return zdt.toLocalDateTime();
        if (value instanceof LocalDate ld) return ld.atStartOfDay();
        if (value instanceof Number n) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(n.longValue()), ZoneId.systemDefault());
        }
        String s = value.toString().trim();
        return s.isEmpty() ? null : parseLocalDateTimeString(s);
    }

    private static LocalDate convertToLocalDate(Object value) {
        if (value instanceof LocalDate ld) return ld;
        if (value instanceof java.sql.Date d) return d.toLocalDate();
        if (value instanceof Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        if (value instanceof LocalDateTime ldt) return ldt.toLocalDate();
        if (value instanceof OffsetDateTime odt) return odt.toLocalDate();
        if (value instanceof ZonedDateTime zdt) return zdt.toLocalDate();
        if (value instanceof Date d) return LocalDateTime.ofInstant(d.toInstant(), ZoneId.systemDefault()).toLocalDate();
        if (value instanceof Instant i) return LocalDateTime.ofInstant(i, ZoneId.systemDefault()).toLocalDate();
        String s = value.toString().trim();
        if (s.isEmpty()) return null;
        int space = s.indexOf(' ');
        if (space > 0) {
            s = s.substring(0, space);
        } else {
            int tIdx = s.indexOf('T');
            if (tIdx > 0) {
                s = s.substring(0, tIdx);
            }
        }
        return LocalDate.parse(s);
    }

    private static LocalTime convertToLocalTime(Object value) {
        if (value instanceof LocalTime lt) return lt;
        if (value instanceof Time t) {
            LocalTime time = t.toLocalTime();
            int millis = (int) Math.floorMod(t.getTime(), 1000L);
            return millis == 0 ? time : time.withNano(millis * 1_000_000);
        }
        if (value instanceof Timestamp ts) return ts.toLocalDateTime().toLocalTime();
        if (value instanceof LocalDateTime ldt) return ldt.toLocalTime();
        if (value instanceof OffsetDateTime odt) return odt.toLocalTime();
        if (value instanceof ZonedDateTime zdt) return zdt.toLocalTime();
        if (value instanceof Date d) return LocalDateTime.ofInstant(d.toInstant(), ZoneId.systemDefault()).toLocalTime();
        String s = value.toString().trim();
        return s.isEmpty() ? null : LocalTime.parse(s);
    }

    private static OffsetDateTime convertToOffsetDateTime(Object value) {
        if (value instanceof OffsetDateTime odt) return odt;
        if (value instanceof ZonedDateTime zdt) return zdt.toOffsetDateTime();
        if (value instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) return null;
            try {
                return OffsetDateTime.parse(trimmed);
            } catch (Exception ignored) {
                return parseLocalDateTimeString(trimmed).atZone(ZoneId.systemDefault()).toOffsetDateTime();
            }
        }
        Instant instant = convertToInstant(value);
        return instant == null ? null : instant.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    private static ZonedDateTime convertToZonedDateTime(Object value) {
        if (value instanceof ZonedDateTime zdt) return zdt;
        if (value instanceof OffsetDateTime odt) return odt.toZonedDateTime();
        Instant instant = convertToInstant(value);
        return instant == null ? null : instant.atZone(ZoneId.systemDefault());
    }

    private static LocalDateTime parseLocalDateTimeString(String s) {
        String normalized = s.replace(' ', 'T');
        try {
            return LocalDateTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            return LocalDate.parse(s).atStartOfDay();
        }
    }
}
