/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package com.sondertara.joya.utils;

import com.sondertara.common.convert.ConvertUtils;
import com.sondertara.common.exception.TaraException;
import com.sondertara.common.base.StringUtils;
import com.sondertara.joya.core.jdbc.support.SqlParameter;
import com.sondertara.joya.core.jdbc.support.SqlParameterValue;
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
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

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
        if (!StringUtils.hasLength(name)) {
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
            // Enums can either be represented through a String or an enum index value:
            // leave enum type conversion up to the caller (e.g. a ConversionService)
            // but make sure that we return nothing other than a String or an Integer.
            Object obj = rs.getObject(index);
            if (obj instanceof String) {
                return obj;
            } else if (obj instanceof Number) {
                // Defensively convert any Number to an Integer (as needed by our
                // ConversionService's IntegerToEnumConverterFactory) for use as index
                try {
                    return obj.getClass().getConstructor(String.class).newInstance(obj.toString());
                } catch (InstantiationException | IllegalAccessException | InvocationTargetException |
                         NoSuchMethodException e) {
                    throw new RuntimeException(e);
                }
            } else {
                // e.g. on Postgres: getObject returns a PGObject but we need a String
                return rs.getString(index);
            }
        } else {

            String columnClassName = rs.getMetaData().getColumnClassName(index);
            try {
                // Corresponding SQL types for JSR-310 / Joda-Time types, left up
                // to the caller to convert them (e.g. through a ConversionService).
                String typeName = requiredType.getSimpleName();
                if (LocalDate.class.isAssignableFrom(requiredType)) {
                    if (String.class.getName().equals(columnClassName)) {
                        return ConvertUtils.convert(requiredType, rs.getObject(index));
                    } else {
                        return rs.getDate(index).toLocalDate();
                    }
                } else if (LocalTime.class.isAssignableFrom(requiredType)) {
                    if (String.class.getName().equals(columnClassName)) {
                        return ConvertUtils.convert(requiredType, rs.getObject(index));
                    } else {
                        return rs.getTime(index).toLocalTime();
                    }
                } else if (LocalDateTime.class.getSimpleName().equals(typeName)) {
                    if (String.class.getName().equals(columnClassName)) {
                        return ConvertUtils.convert(requiredType, rs.getObject(index));
                    } else {
                        return rs.getTimestamp(index).toLocalDateTime();
                    }
                } else if (java.time.OffsetDateTime.class.isAssignableFrom(requiredType)) {
                    if (String.class.getName().equals(columnClassName)) {
                        return ConvertUtils.convert(requiredType, rs.getObject(index));
                    } else {
                        return rs.getTimestamp(index).toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime();
                    }
                }
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("Column of type {} cannot be converted to {}", columnClassName, requiredType.getName(), e);
                }
            }
            try {
                return ConvertUtils.convert(requiredType, rs.getObject(index));
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
     * Set the value for a parameter. The method used is based on the SQL type
     * of the parameter and we can handle complex types like arrays and LOBs.
     *
     * @param ps         the prepared statement or callable statement
     * @param paramIndex index of the parameter we are setting
     * @param param      the parameter as it is declared including type
     * @param inValue    the value to set
     * @throws SQLException if thrown by PreparedStatement methods
     */
    public static void setParameterValue(PreparedStatement ps, int paramIndex, SqlParameter param,
                                         @Nullable Object inValue) throws SQLException {

        setParameterValueInternal(ps, paramIndex, param.getSqlType(), param.getTypeName(), param.getScale(), inValue);
    }

    /**
     * Set the value for a parameter. The method used is based on the SQL type
     * of the parameter and we can handle complex types like arrays and LOBs.
     *
     * @param ps         the prepared statement or callable statement
     * @param paramIndex index of the parameter we are setting
     * @param sqlType    the SQL type of the parameter
     * @param inValue    the value to set (plain value or an SqlTypeValue)
     * @throws SQLException if thrown by PreparedStatement methods
     */
    public static void setParameterValue(PreparedStatement ps, int paramIndex, int sqlType,
                                         @Nullable Object inValue) throws SQLException {

        setParameterValueInternal(ps, paramIndex, sqlType, null, null, inValue);
    }

    /**
     * Set the value for a parameter. The method used is based on the SQL type
     * of the parameter and we can handle complex types like arrays and LOBs.
     *
     * @param ps         the prepared statement or callable statement
     * @param paramIndex index of the parameter we are setting
     * @param sqlType    the SQL type of the parameter
     * @param typeName   the type name of the parameter
     *                   (optional, only used for SQL NULL and SqlTypeValue)
     * @param inValue    the value to set (plain value or an SqlTypeValue)
     * @throws SQLException if thrown by PreparedStatement methods
     */
    public static void setParameterValue(PreparedStatement ps, int paramIndex, int sqlType, String typeName,
                                         @Nullable Object inValue) throws SQLException {

        setParameterValueInternal(ps, paramIndex, sqlType, typeName, null, inValue);
    }

    /**
     * Set the value for a parameter. The method used is based on the SQL type
     * of the parameter and we can handle complex types like arrays and LOBs.
     *
     * @param ps         the prepared statement or callable statement
     * @param paramIndex index of the parameter we are setting
     * @param sqlType    the SQL type of the parameter
     * @param typeName   the type name of the parameter
     *                   (optional, only used for SQL NULL and SqlTypeValue)
     * @param scale      the number of digits after the decimal point
     *                   (for DECIMAL and NUMERIC types)
     * @param inValue    the value to set (plain value or an SqlTypeValue)
     * @throws SQLException if thrown by PreparedStatement methods
     */
    private static void setParameterValueInternal(PreparedStatement ps, int paramIndex, int sqlType,
                                                  @Nullable String typeName, @Nullable Integer scale, @Nullable Object inValue) throws SQLException {

        String typeNameToUse = typeName;
        int sqlTypeToUse = sqlType;
        Object inValueToUse = inValue;

        // override type info?
        if (inValue instanceof SqlParameterValue) {
            SqlParameterValue parameterValue = (SqlParameterValue) inValue;
            if (log.isDebugEnabled()) {
                log.debug("Overriding type info with runtime info from SqlParameterValue: column index " + paramIndex +
                        ", SQL type " + parameterValue.getSqlType() + ", type name " + parameterValue.getTypeName());
            }
            if (parameterValue.getSqlType() != JdbcUtils.TYPE_UNKNOWN) {
                sqlTypeToUse = parameterValue.getSqlType();
            }
            if (parameterValue.getTypeName() != null) {
                typeNameToUse = parameterValue.getTypeName();
            }
            inValueToUse = parameterValue.getValue();
        }

        if (log.isTraceEnabled()) {
            log.trace("Setting SQL statement parameter value: column index " + paramIndex +
                    ", parameter value [" + inValueToUse +
                    "], value class [" + (inValueToUse != null ? inValueToUse.getClass().getName() : "null") +
                    "], SQL type " + (sqlTypeToUse == JdbcUtils.TYPE_UNKNOWN ? "unknown" : Integer.toString(sqlTypeToUse)));
        }

        if (inValueToUse == null) {
            setNull(ps, paramIndex, sqlTypeToUse, typeNameToUse);
        } else {
            setValue(ps, paramIndex, sqlTypeToUse, scale, inValueToUse);
        }
    }

    /**
     * Set the specified PreparedStatement parameter to null,
     * respecting database-specific peculiarities.
     */
    private static void setNull(PreparedStatement ps, int paramIndex, int sqlType, String typeName)
            throws SQLException {

        if (sqlType == JdbcUtils.TYPE_UNKNOWN || (sqlType == Types.OTHER && typeName == null)) {
            boolean useSetObject = false;
            Integer sqlTypeToUse = null;
            if (!shouldIgnoreGetParameterType) {
                try {
                    sqlTypeToUse = ps.getParameterMetaData().getParameterType(paramIndex);
                } catch (SQLException ex) {
                    if (log.isDebugEnabled()) {
                        log.debug("JDBC getParameterType call failed - using fallback method instead: " + ex);
                    }
                }
            }
            if (sqlTypeToUse == null) {
                // Proceed with database-specific checks
                sqlTypeToUse = Types.NULL;
                DatabaseMetaData dbmd = ps.getConnection().getMetaData();
                String jdbcDriverName = dbmd.getDriverName();
                String databaseProductName = dbmd.getDatabaseProductName();
                if (databaseProductName.startsWith("Informix") ||
                        (jdbcDriverName.startsWith("Microsoft") && jdbcDriverName.contains("SQL Server"))) {
                    // "Microsoft SQL Server JDBC Driver 3.0" versus "Microsoft JDBC Driver 4.0 for SQL Server"
                    useSetObject = true;
                } else if (databaseProductName.startsWith("DB2") ||
                        jdbcDriverName.startsWith("jConnect") ||
                        jdbcDriverName.startsWith("SQLServer") ||
                        jdbcDriverName.startsWith("Apache Derby")) {
                    sqlTypeToUse = Types.VARCHAR;
                }
            }
            if (useSetObject) {
                ps.setObject(paramIndex, null);
            } else {
                ps.setNull(paramIndex, sqlTypeToUse);
            }
        } else if (typeName != null) {
            ps.setNull(paramIndex, sqlType, typeName);
        } else {
            ps.setNull(paramIndex, sqlType);
        }
    }

    private static void setValue(PreparedStatement ps, int paramIndex, int sqlType, @Nullable Integer scale, Object inValue) throws SQLException {

        if (sqlType == Types.VARCHAR || sqlType == Types.LONGVARCHAR) {
            ps.setString(paramIndex, inValue.toString());
        } else if (sqlType == Types.NVARCHAR || sqlType == Types.LONGNVARCHAR) {
            ps.setNString(paramIndex, inValue.toString());
        } else if ((sqlType == Types.CLOB || sqlType == Types.NCLOB) && isStringValue(inValue.getClass())) {
            String strVal = inValue.toString();
            if (strVal.length() > 4000) {
                // Necessary for older Oracle drivers, in particular when running against an Oracle 10 database.
                // Should also work fine against other drivers/databases since it uses standard JDBC 4.0 API.
                if (sqlType == Types.NCLOB) {
                    ps.setNClob(paramIndex, new StringReader(strVal), strVal.length());
                } else {
                    ps.setClob(paramIndex, new StringReader(strVal), strVal.length());
                }
            } else {
                // Fallback: setString or setNString binding
                if (sqlType == Types.NCLOB) {
                    ps.setNString(paramIndex, strVal);
                } else {
                    ps.setString(paramIndex, strVal);
                }
            }
        } else if (sqlType == Types.DECIMAL || sqlType == Types.NUMERIC) {
            if (inValue instanceof BigDecimal) {
                ps.setBigDecimal(paramIndex, (BigDecimal) inValue);
            } else if (scale != null) {
                ps.setObject(paramIndex, inValue, sqlType, scale);
            } else {
                ps.setObject(paramIndex, inValue, sqlType);
            }
        } else if (sqlType == Types.BOOLEAN) {
            if (inValue instanceof Boolean) {
                ps.setBoolean(paramIndex, (Boolean) inValue);
            } else {
                ps.setObject(paramIndex, inValue, Types.BOOLEAN);
            }
        } else if (sqlType == Types.DATE) {
            if (inValue instanceof java.util.Date) {
                if (inValue instanceof java.sql.Date) {
                    ps.setDate(paramIndex, (java.sql.Date) inValue);
                } else {
                    ps.setDate(paramIndex, new java.sql.Date(((java.util.Date) inValue).getTime()));
                }
            } else if (inValue instanceof Calendar) {
                Calendar cal = (Calendar) inValue;
                ps.setDate(paramIndex, new java.sql.Date(cal.getTime().getTime()), cal);
            } else {
                ps.setObject(paramIndex, inValue, Types.DATE);
            }
        } else if (sqlType == Types.TIME) {
            if (inValue instanceof java.util.Date) {
                if (inValue instanceof java.sql.Time) {
                    ps.setTime(paramIndex, (java.sql.Time) inValue);
                } else {
                    ps.setTime(paramIndex, new java.sql.Time(((java.util.Date) inValue).getTime()));
                }
            } else if (inValue instanceof Calendar) {
                Calendar cal = (Calendar) inValue;
                ps.setTime(paramIndex, new java.sql.Time(cal.getTime().getTime()), cal);
            } else {
                ps.setObject(paramIndex, inValue, Types.TIME);
            }
        } else if (sqlType == Types.TIMESTAMP) {
            if (inValue instanceof java.util.Date) {
                if (inValue instanceof java.sql.Timestamp) {
                    ps.setTimestamp(paramIndex, (java.sql.Timestamp) inValue);
                } else {
                    ps.setTimestamp(paramIndex, new java.sql.Timestamp(((java.util.Date) inValue).getTime()));
                }
            } else if (inValue instanceof Calendar) {
                Calendar cal = (Calendar) inValue;
                ps.setTimestamp(paramIndex, new java.sql.Timestamp(cal.getTime().getTime()), cal);
            } else {
                ps.setObject(paramIndex, inValue, Types.TIMESTAMP);
            }
        } else if (sqlType == JdbcUtils.TYPE_UNKNOWN || (sqlType == Types.OTHER &&
                "Oracle".equals(ps.getConnection().getMetaData().getDatabaseProductName()))) {
            if (isStringValue(inValue.getClass())) {
                ps.setString(paramIndex, inValue.toString());
            } else if (isDateValue(inValue.getClass())) {
                ps.setTimestamp(paramIndex, new java.sql.Timestamp(((java.util.Date) inValue).getTime()));
            } else if (inValue instanceof Calendar) {
                Calendar cal = (Calendar) inValue;
                ps.setTimestamp(paramIndex, new java.sql.Timestamp(cal.getTime().getTime()), cal);
            } else {
                // Fall back to generic setObject call without SQL type specified.
                ps.setObject(paramIndex, inValue);
            }
        } else {
            // Fall back to generic setObject call with SQL type specified.
            ps.setObject(paramIndex, inValue, sqlType);
        }
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

    /**
     * Clean up all resources held by parameter values which were passed to an
     * execute method. This is for example important for closing LOB values.
     *
     * @param paramValues parameter values supplied. May be {@code null}.
     */
    public static void cleanupParameters(@Nullable Object... paramValues) {
        if (paramValues != null) {
            cleanupParameters(Arrays.asList(paramValues));
        }
    }

    /**
     * Clean up all resources held by parameter values which were passed to an
     * execute method. This is for example important for closing LOB values.
     *
     * @param paramValues parameter values supplied. May be {@code null}.
     */
    public static void cleanupParameters(@Nullable Collection<?> paramValues) {
        if (paramValues != null) {
            for (Object inValue : paramValues) {
                // Unwrap SqlParameterValue first...
                if (inValue instanceof SqlParameterValue) {
                    inValue = ((SqlParameterValue) inValue).getValue();
                }
            }
        }
    }


}