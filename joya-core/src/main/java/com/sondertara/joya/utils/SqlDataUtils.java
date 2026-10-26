package com.sondertara.joya.utils;

import com.sondertara.joya.exceptions.DbException;
import com.sondertara.joya.exceptions.TaraException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * JDBC 结果集列名解析与类型化列值提取工具。
 *
 * @author huangxiaohu
 */
@Slf4j
public final class SqlDataUtils {

    private static final char NULL_CHAR = '\u0000';

    private SqlDataUtils() {
    }

    public static String lookupColumnName(ResultSetMetaData resultSetMetaData, int columnIndex) throws SQLException {
        String name = resultSetMetaData.getColumnLabel(columnIndex);
        if (!Strings.hasLength(name)) {
            name = resultSetMetaData.getColumnName(columnIndex);
        }
        return name;
    }

    private static String checkNullString(String source) {
        if (null == source) {
            return null;
        }
        if (source.length() == 1 && source.charAt(0) == NULL_CHAR) {
            return null;
        }
        return source;
    }

    private static String readReaderAsString(Reader reader) {
        if (reader == null) {
            return null;
        }
        try (Reader r = reader; StringWriter writer = new StringWriter()) {
            r.transferTo(writer);
            return checkNullString(writer.toString());
        } catch (IOException e) {
            throw new TaraException("Failed to read character stream from ResultSet", e);
        }
    }

    private static String readClobAsString(Clob clob) {
        if (clob == null) {
            return null;
        }
        try {
            return readReaderAsString(clob.getCharacterStream());
        } catch (SQLException e) {
            throw new TaraException("Failed to read Clob character stream from ResultSet", e);
        }
    }

    public static Object getResultSetValue(ResultSet rs, int index, @Nullable Class<?> requiredType) throws SQLException {
        if (requiredType == null) {
            return getResultSetValue(rs, index);
        }

        Object value;

        if (String.class == requiredType) {
            String string = rs.getString(index);
            if (null == string) {
                return null;
            }
            return checkNullString(string);
        } else if (boolean.class == requiredType || Boolean.class == requiredType) {
            value = rs.getBoolean(index);
        } else if (byte.class == requiredType || Byte.class == requiredType) {
            value = rs.getByte(index);
        } else if (short.class == requiredType || Short.class == requiredType) {
            value = rs.getShort(index);
        } else if (int.class == requiredType || Integer.class == requiredType) {
            value = rs.getInt(index);
        } else if (long.class == requiredType || Long.class == requiredType) {
            value = rs.getLong(index);
        } else if (float.class == requiredType || Float.class == requiredType) {
            value = rs.getFloat(index);
        } else if (double.class == requiredType || Double.class == requiredType) {
            value = rs.getDouble(index);
        } else if (Number.class == requiredType) {
            Object raw = rs.getObject(index);
            if (raw == null) {
                return null;
            }
            if (raw instanceof Number num) {
                return num;
            }
            return rs.getBigDecimal(index);
        } else if (BigDecimal.class == requiredType) {
            return rs.getBigDecimal(index);
        } else if (LocalTime.class == requiredType) {
            return readLocalTime(rs, index);
        } else if (java.sql.Date.class == requiredType) {
            return rs.getDate(index);
        } else if (java.sql.Time.class == requiredType) {
            return rs.getTime(index);
        } else if (java.sql.Timestamp.class == requiredType || java.util.Date.class == requiredType) {
            return rs.getTimestamp(index);
        } else if (byte[].class == requiredType) {
            return rs.getBytes(index);
        } else if (Blob.class == requiredType) {
            return rs.getBlob(index);
        } else if (Clob.class == requiredType) {
            return rs.getClob(index);
        } else if (requiredType.isEnum()) {
            Object obj = rs.getObject(index);
            if (obj == null) {
                return null;
            }
            if (requiredType.isInstance(obj)) {
                return obj;
            }
            Object[] constants = requiredType.getEnumConstants();
            if (obj instanceof Number num) {
                int ordinal = num.intValue();
                if (constants != null && ordinal >= 0 && ordinal < constants.length) {
                    return constants[ordinal];
                }
                return ConvertUtils.convert(ordinal, requiredType);
            }
            String text = (obj instanceof String s) ? s : rs.getString(index);
            if (text == null) {
                return null;
            }
            if (constants != null) {
                for (Object constant : constants) {
                    Enum<?> e = (Enum<?>) constant;
                    if (e.name().equals(text) || e.name().equalsIgnoreCase(text)) {
                        return e;
                    }
                }
            }
            return ConvertUtils.convert(text, requiredType);
        } else {
            String columnClassName = rs.getMetaData().getColumnClassName(index);
            try {
                String typeName = requiredType.getSimpleName();
                if (LocalDate.class.isAssignableFrom(requiredType)) {
                    if (String.class.getName().equals(columnClassName)) {
                        return ConvertUtils.convert(rs.getObject(index), requiredType);
                    } else {
                        java.sql.Date d = rs.getDate(index);
                        return d == null ? null : d.toLocalDate();
                    }
                } else if (LocalDateTime.class.getSimpleName().equals(typeName)) {
                    if (String.class.getName().equals(columnClassName)) {
                        return ConvertUtils.convert(rs.getObject(index), requiredType);
                    } else {
                        java.sql.Timestamp ts = rs.getTimestamp(index);
                        return ts == null ? null : ts.toLocalDateTime();
                    }
                } else if (java.time.OffsetDateTime.class.isAssignableFrom(requiredType)) {
                    if (String.class.getName().equals(columnClassName)) {
                        return ConvertUtils.convert(rs.getObject(index), requiredType);
                    } else {
                        java.sql.Timestamp ts = rs.getTimestamp(index);
                        return ts == null ? null : ts.toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime();
                    }
                }
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("Column of type {} cannot be converted to {}", columnClassName, requiredType.getName(), e);
                }
            }
            try {
                return ConvertUtils.convert(rs.getObject(index), requiredType);
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("Column of type {} cannot be converted to {} by ConvertUtils", columnClassName, requiredType.getName(), e);
                }
            }
            try {
                return rs.getObject(index, requiredType);
            } catch (AbstractMethodError err) {
                log.debug("JDBC driver does not implement JDBC 4.1 'getObject(int, Class)' method", err);
            } catch (SQLFeatureNotSupportedException ex) {
                log.debug("JDBC driver does not support JDBC 4.1 'getObject(int, Class)' method", ex);
            } catch (SQLException ex) {
                log.debug("JDBC driver has limited support for JDBC 4.1 'getObject(int, Class)' method", ex);
            }

            return getResultSetValue(rs, index);
        }

        return (rs.wasNull() ? null : value);
    }

    private static LocalTime readLocalTime(ResultSet rs, int index) throws SQLException {
        Throwable failure = null;
        try {
            return rs.getObject(index, LocalTime.class);
        } catch (SQLException | RuntimeException | AbstractMethodError e) {
            failure = e;
        }

        Object raw = null;
        boolean rawAvailable = false;
        try {
            raw = rs.getObject(index);
            rawAvailable = true;
        } catch (SQLException | RuntimeException | AbstractMethodError e) {
            failure = e;
        }
        if (rawAvailable && raw == null) {
            return null;
        }
        if (raw != null && !(raw instanceof java.sql.Time)) {
            try {
                return ConvertUtils.convert(raw, LocalTime.class);
            } catch (RuntimeException e) {
                failure = e;
            }
        }
        if (raw instanceof java.sql.Time time) {
            LocalTime rawTime = ConvertUtils.convert(time, LocalTime.class);
            if (rawTime.getNano() != 0) {
                return rawTime;
            }
        }

        // 无法从 java.sql.Time 确认小数秒时，只允许继续尝试无损字符串。
        try {
            String text = rs.getString(index);
            if (text != null) {
                return ConvertUtils.convert(text, LocalTime.class);
            }
            if (raw == null) {
                return null;
            }
        } catch (SQLException | RuntimeException e) {
            failure = e;
        }
        throw new DbException(
                "Cannot read column " + index + " as LocalTime without losing fractional seconds", failure);
    }

    public static Object getResultSetValue(ResultSet rs, int index) throws SQLException {
        Object obj = rs.getObject(index);
        if (null == obj) {
            return null;
        }
        String className = obj.getClass().getName();

        if (obj instanceof String s) {
            return checkNullString(s);
        } else if (obj instanceof Blob blob) {
            obj = blob.getBytes(1, (int) blob.length());
        } else if (obj instanceof Clob clob) {
            obj = readClobAsString(clob);
        } else if ("oracle.sql.TIMESTAMP".equals(className) || "oracle.sql.TIMESTAMPTZ".equals(className)) {
            obj = rs.getTimestamp(index);
        } else if (className.startsWith("oracle.sql.DATE")) {
            String metaDataClassName = rs.getMetaData().getColumnClassName(index);
            if ("java.sql.Timestamp".equals(metaDataClassName) || "oracle.sql.TIMESTAMP".equals(metaDataClassName)) {
                obj = rs.getTimestamp(index);
            } else {
                obj = rs.getDate(index);
            }
        } else if (obj instanceof java.sql.Date) {
            if ("java.sql.Timestamp".equals(rs.getMetaData().getColumnClassName(index))) {
                obj = rs.getTimestamp(index);
            }
        } else if (obj instanceof Reader reader) {
            obj = readReaderAsString(reader);
        }
        return obj;
    }
}
