/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package com.sondertara.joya.utils;

import com.sondertara.joya.utils.ConvertUtils;
import com.sondertara.joya.exceptions.TaraException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

/**
 * A help for dealing with BLOB and CLOB data
 *
 * @author Steve Ebersole
 */
@Slf4j
public final class SqlDataUtils {


    private static final char NULL_CHAR = '\u0000';

    public static final String ORACLE_TIMESTAMP = "oracle.sql.TIMESTAMP";
    private static final Logger LOG = LoggerFactory.getLogger(SqlDataUtils.class);

    private SqlDataUtils() {
    }

    /**
     * The size of the buffer we will use to deserialize larger streams
     */
    private static final int BUFFER_SIZE = 1024 * 4;


    public static boolean isNClob(final Class<?> type) {
        return java.sql.NClob.class.isAssignableFrom(type);
    }

    public static boolean isClob(final Class<?> type) {
        return Clob.class.isAssignableFrom(type);
    }

    /**
     * Extract the contents of the given reader/stream as a string.
     * The reader will be closed.
     *
     * @param reader The reader for the content
     * @return The content as string
     */
    public static String extractString(Reader reader) {
        return extractString(reader, BUFFER_SIZE);
    }

    /**
     * Extract the contents of the given reader/stream as a string.
     * The reader will be closed.
     *
     * @param reader     The reader for the content
     * @param lengthHint if the length is known in advance the implementation can be slightly more efficient
     * @return The content as string
     */
    public static String extractString(Reader reader, int lengthHint) {
        if (null == reader) {
            return null;
        }
        // read the Reader contents into a buffer and return the complete string
        final int bufferSize = getSuggestedBufferSize(lengthHint);
        final StringBuilder stringBuilder = new StringBuilder(bufferSize);
        try {
            char[] buffer = new char[bufferSize];
            while (true) {
                int amountRead = reader.read(buffer, 0, bufferSize);
                if (amountRead == -1) {
                    break;
                }
                // null value
                if (amountRead == 1 && buffer[0] == NULL_CHAR) {
                    if (stringBuilder.length() == 0) {
                        return null;
                    }
                }
                stringBuilder.append(buffer, 0, amountRead);
            }
        } catch (IOException ioe) {
            throw new TaraException("IOException occurred reading text", ioe);
        } finally {
            try {
                reader.close();
            } catch (IOException e) {
                LOG.error("", e);
            }
        }
        return stringBuilder.toString();
    }

    /**
     * Extracts a portion of the contents of the given reader/stream as a string.
     *
     * @param characterStream The reader for the content
     * @param start           The start position/offset (0-based, per general stream/reader contracts).
     * @param length          The amount to extract
     * @return The content as string
     */
    private static String extractString(Reader characterStream, long start, int length) {
        if (length == 0) {
            return "";
        }
        StringBuilder stringBuilder = new StringBuilder(length);
        try {
            long skipped = characterStream.skip(start);
            if (skipped != start) {
                throw new TaraException("Unable to skip needed bytes");
            }
            final int bufferSize = getSuggestedBufferSize(length);
            char[] buffer = new char[bufferSize];
            int charsRead = 0;
            while (true) {
                int amountRead = characterStream.read(buffer, 0, bufferSize);
                if (amountRead == -1) {
                    break;
                }
                stringBuilder.append(buffer, 0, amountRead);
                if (amountRead < bufferSize) {
                    // we have read up to the end of stream
                    break;
                }
                charsRead += amountRead;
                if (charsRead >= length) {
                    break;
                }
            }
        } catch (IOException ioe) {
            throw new TaraException("IOException occurred reading a binary value", ioe);
        }
        return stringBuilder.toString();
    }

    /**
     * Extract a portion of a reader, wrapping the portion in a new reader.
     *
     * @param characterStream The reader for the content
     * @param start           The start position/offset (0-based, per general stream/reader contracts).
     * @param length          The amount to extract
     * @return The content portion as a reader
     */
    public static Object subStream(Reader characterStream, long start, int length) {
        return new StringReader(extractString(characterStream, start, length));
    }

    /**
     * Extract by bytes from the given stream.
     *
     * @param inputStream The stream of bytes.
     * @return The contents as a {@code byte[]}
     */
    public static byte[] extractBytes(InputStream inputStream) {

        // read the stream contents into a buffer and return the complete byte[]
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(BUFFER_SIZE);
        try {
            byte[] buffer = new byte[BUFFER_SIZE];
            while (true) {
                int amountRead = inputStream.read(buffer);
                if (amountRead == -1) {
                    break;
                }
                outputStream.write(buffer, 0, amountRead);
            }
        } catch (IOException ioe) {
            throw new TaraException("IOException occurred reading a binary value", ioe);
        } finally {
            try {
                inputStream.close();
            } catch (IOException e) {
                LOG.error("", e);
            }
            try {
                outputStream.close();
            } catch (IOException e) {
                LOG.error("Close Stream", e);
            }
        }
        return outputStream.toByteArray();
    }


    /**
     * Extract the contents of the given Clob as a string.
     *
     * @param value The clob to to be extracted from
     * @return The content as string
     */
    public static String extractString(final Clob value) {
        try {
            final Reader characterStream = value.getCharacterStream();
            final long length = determineLengthForBufferSizing(value);
            return length > Integer.MAX_VALUE ? extractString(characterStream, Integer.MAX_VALUE) : extractString(characterStream, (int) length);
        } catch (SQLException e) {
            throw new TaraException("Unable to access lob stream", e);
        }
    }

    /**
     * Determine a buffer size for reading the underlying character stream.
     *
     * @param value The Clob value
     * @return The appropriate buffer size ({@link Clob#length()} by default.
     */
    private static long determineLengthForBufferSizing(Clob value) throws SQLException {
        try {
            return value.length();
        } catch (SQLFeatureNotSupportedException e) {
            return BUFFER_SIZE;
        }
    }

    /**
     * Make sure we allocate a buffer sized not bigger than 2048,
     * not higher than what is actually needed, and at least one.
     *
     * @param lengthHint the expected size of the full value
     * @return the buffer size
     */
    private static int getSuggestedBufferSize(final int lengthHint) {
        return Math.max(1, Math.min(lengthHint, BUFFER_SIZE));
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
        // check if null string with char '\u0000'.
        if (source.length() == 1 && source.charAt(0) == NULL_CHAR) {
            return null;
        }
        return source;
    }

    public static Object getResultSetValue(ResultSet rs, int index, @Nullable Class<?> requiredType) throws SQLException {
        if (requiredType == null) {
            return getResultSetValue(rs, index);
        }

        Object value;

        // Explicitly extract typed value, as far as possible.
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
        } else if (double.class == requiredType || Double.class == requiredType ||
                Number.class == requiredType) {
            value = rs.getDouble(index);
        } else if (BigDecimal.class == requiredType) {
            return rs.getBigDecimal(index);
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
                // Corresponding SQL types for JSR-310 / Joda-Time types, left up
                // to the caller to convert them (e.g. through a ConversionService).
                String typeName = requiredType.getSimpleName();
                if (LocalDate.class.isAssignableFrom(requiredType)) {
                    if (String.class.getName().equals(columnClassName)) {
                        return ConvertUtils.convert(rs.getObject(index), requiredType);
                    } else {
                        java.sql.Date d = rs.getDate(index);
                        return d == null ? null : d.toLocalDate();
                    }
                } else if (LocalTime.class.isAssignableFrom(requiredType)) {
                    if (String.class.getName().equals(columnClassName)) {
                        return ConvertUtils.convert(rs.getObject(index), requiredType);
                    } else {
                        java.sql.Time t = rs.getTime(index);
                        return t == null ? null : t.toLocalTime();
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
            // Some unknown type desired -> rely on getObject.
            try {
                return rs.getObject(index, requiredType);
            } catch (AbstractMethodError err) {
                log.debug("JDBC driver does not implement JDBC 4.1 'getObject(int, Class)' method", err);
            } catch (SQLFeatureNotSupportedException ex) {
                log.debug("JDBC driver does not support JDBC 4.1 'getObject(int, Class)' method", ex);
            } catch (SQLException ex) {
                log.debug("JDBC driver has limited support for JDBC 4.1 'getObject(int, Class)' method", ex);
            }


            // Fall back to getObject without type specification, again
            // left up to the caller to convert the value if necessary.
            return getResultSetValue(rs, index);
        }

        // Perform was-null check if necessary (for results that the JDBC driver returns as primitives).
        return (rs.wasNull() ? null : value);
    }

    public static Object getResultSetValue(ResultSet rs, int index) throws SQLException {
        Object obj = rs.getObject(index);
        if (null == obj) {
            return null;
        }
        String className = obj.getClass().getName();

        if (obj instanceof String) {
            return checkNullString((String) obj);
        } else if (obj instanceof Blob) {
            Blob blob = (Blob) obj;
            obj = blob.getBytes(1, (int) blob.length());
        } else if (obj instanceof Clob) {
            Clob clob = (Clob) obj;
            obj = extractString(clob);
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
        } else if (obj instanceof Reader) {
            obj = extractString((Reader) obj);
        }
        return obj;
    }

    static boolean shouldIgnoreGetParameterType = false;


    private static final Map<Class<?>, Integer> javaTypeToSqlTypeMap = new HashMap<>(32);

    static {
        javaTypeToSqlTypeMap.put(boolean.class, Types.BOOLEAN);
        javaTypeToSqlTypeMap.put(Boolean.class, Types.BOOLEAN);
        javaTypeToSqlTypeMap.put(byte.class, Types.TINYINT);
        javaTypeToSqlTypeMap.put(Byte.class, Types.TINYINT);
        javaTypeToSqlTypeMap.put(short.class, Types.SMALLINT);
        javaTypeToSqlTypeMap.put(Short.class, Types.SMALLINT);
        javaTypeToSqlTypeMap.put(int.class, Types.INTEGER);
        javaTypeToSqlTypeMap.put(Integer.class, Types.INTEGER);
        javaTypeToSqlTypeMap.put(long.class, Types.BIGINT);
        javaTypeToSqlTypeMap.put(Long.class, Types.BIGINT);
        javaTypeToSqlTypeMap.put(BigInteger.class, Types.BIGINT);
        javaTypeToSqlTypeMap.put(float.class, Types.FLOAT);
        javaTypeToSqlTypeMap.put(Float.class, Types.FLOAT);
        javaTypeToSqlTypeMap.put(double.class, Types.DOUBLE);
        javaTypeToSqlTypeMap.put(Double.class, Types.DOUBLE);
        javaTypeToSqlTypeMap.put(BigDecimal.class, Types.DECIMAL);
        javaTypeToSqlTypeMap.put(LocalDate.class, Types.DATE);
        javaTypeToSqlTypeMap.put(LocalTime.class, Types.TIME);
        javaTypeToSqlTypeMap.put(LocalDateTime.class, Types.TIMESTAMP);
        javaTypeToSqlTypeMap.put(java.sql.Date.class, Types.DATE);
        javaTypeToSqlTypeMap.put(java.sql.Time.class, Types.TIME);
        javaTypeToSqlTypeMap.put(java.sql.Timestamp.class, Types.TIMESTAMP);
        javaTypeToSqlTypeMap.put(Blob.class, Types.BLOB);
        javaTypeToSqlTypeMap.put(Clob.class, Types.CLOB);
    }


    /**
     * Derive a default SQL type from the given Java type.
     *
     * @param javaType the Java type to translate
     * @return the corresponding SQL type, or {@link JdbcUtils#TYPE_UNKNOWN} if none found
     */
    public static int javaTypeToSqlParameterType(@Nullable Class<?> javaType) {
        if (javaType == null) {
            return JdbcUtils.TYPE_UNKNOWN;
        }
        Integer sqlType = javaTypeToSqlTypeMap.get(javaType);
        if (sqlType != null) {
            return sqlType;
        }
        if (Number.class.isAssignableFrom(javaType)) {
            return Types.NUMERIC;
        }
        if (isStringValue(javaType)) {
            return Types.VARCHAR;
        }
        if (isDateValue(javaType) || Calendar.class.isAssignableFrom(javaType)) {
            return Types.TIMESTAMP;
        }
        return JdbcUtils.TYPE_UNKNOWN;
    }

    /**
     * Check whether the given value can be treated as a String value.
     */
    private static boolean isStringValue(Class<?> inValueType) {
        // Consider any CharSequence (including StringBuffer and StringBuilder) as a String.
        return (CharSequence.class.isAssignableFrom(inValueType) ||
                StringWriter.class.isAssignableFrom(inValueType));
    }

    /**
     * Check whether the given value is a {@code java.util.Date}
     * (but not one of the JDBC-specific subclasses).
     */
    private static boolean isDateValue(Class<?> inValueType) {
        return (java.util.Date.class.isAssignableFrom(inValueType) &&
                !(java.sql.Date.class.isAssignableFrom(inValueType) ||
                        java.sql.Time.class.isAssignableFrom(inValueType) ||
                        java.sql.Timestamp.class.isAssignableFrom(inValueType)));
    }

}