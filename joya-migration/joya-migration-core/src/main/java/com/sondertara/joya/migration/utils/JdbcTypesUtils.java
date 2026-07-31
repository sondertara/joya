
package com.sondertara.joya.migration.utils;

import java.lang.reflect.Field;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;

import com.sondertara.common.convert.ConvertUtils;
import com.sondertara.joya.migration.exchange.domain.TargetEndpointProperties;
import lombok.experimental.UtilityClass;

/**
 * JDBC的数据类型相关工具类
 *
 * @author tang
 */
@UtilityClass
public final class JdbcTypesUtils {

  private static final Map<Integer, String> TYPE_NAMES = new HashMap<>();

  static {
    try {
      for (Field field : Types.class.getFields()) {
        TYPE_NAMES.put((Integer) field.get(null), field.getName());
      }
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to resolve JDBC Types constants", ex);
    }
  }

  /**
   * 将JDBC的整型类型转换成文本类型
   *
   * @param sqlType jdbc的整型类型，详见:{@codejava.sql.Types }
   * @return JDBC的文本类型
   */
  public static String resolveTypeName(int sqlType) {
    return TYPE_NAMES.get(sqlType);
  }

  /**
   * 判断是否为JDCB的浮点数类型
   *
   * @param sqlType jdbc的整型类型，详见:{@codejava.sql.Types }
   * @return true为是，否则为false
   */
  public static boolean isNumeric(int sqlType) {
    // 5
    return (Types.DECIMAL == sqlType || Types.DOUBLE == sqlType || Types.FLOAT == sqlType
        || Types.NUMERIC == sqlType || Types.REAL == sqlType);
  }

  /**
   * 判断是否为JDCB的整型类型
   *
   * @param sqlType jdbc的整型类型，详见:{@codejava.sql.Types }
   * @return true为是，否则为false
   */
  public static boolean isInteger(int sqlType) {
    // 5
    return (Types.BIT == sqlType || Types.BIGINT == sqlType || Types.INTEGER == sqlType
        || Types.SMALLINT == sqlType
        || Types.TINYINT == sqlType);
  }

  /**
   * 判断是否为JDCB的字符文本类型
   *
   * @param sqlType jdbc的整型类型，详见:{@codejava.sql.Types }
   * @return true为是，否则为false
   */
  public static boolean isString(int sqlType) {
    // 10
    return (Types.CHAR == sqlType || Types.NCHAR == sqlType || Types.VARCHAR == sqlType
        || Types.LONGVARCHAR == sqlType || Types.NVARCHAR == sqlType
        || Types.LONGNVARCHAR == sqlType
        || Types.CLOB == sqlType || Types.NCLOB == sqlType || Types.SQLXML == sqlType
        || Types.ROWID == sqlType);
  }

  /**
   * 判断是否为JDCB的时间类型
   *
   * @param sqlType jdbc的整型类型，详见:{@codejava.sql.Types }
   * @return true为是，否则为false
   */
  public static boolean isDateTime(int sqlType) {
    // 5
    return (Types.DATE == sqlType || Types.TIME == sqlType || Types.TIMESTAMP == sqlType
        || Types.TIME_WITH_TIMEZONE == sqlType || Types.TIMESTAMP_WITH_TIMEZONE == sqlType);
  }

  /**
   * 判断是否为JDCB的布尔类型
   *
   * @param sqlType jdbc的整型类型，详见:{@codejava.sql.Types }
   * @return true为是，否则为false
   */
  public static boolean isBoolean(int sqlType) {
    // 1
    return (Types.BOOLEAN == sqlType);
  }

  /**
   * 判断是否为JDCB的二进制类型
   *
   * @param sqlType jdbc的整型类型，详见:{@codejava.sql.Types }
   * @return true为是，否则为false
   */
  public static boolean isBinary(int sqlType) {
    // 4
    return (Types.BINARY == sqlType || Types.VARBINARY == sqlType || Types.BLOB == sqlType
        || Types.LONGVARBINARY == sqlType);
  }

  public static boolean canToText(int sqlType) {
    return isNumeric(sqlType) || isString(sqlType) || isDateTime(sqlType) || isBoolean(sqlType);
  }

  // 其他类型如下：9个
  // JAVA_OBJECT
  // OTHER
  // NULL
  // DISTINCT
  // STRUCT
  // ARRAY
  // REF
  // DATALINK
  // REF_CURSOR

  public static long getObjectSize(int jdbcType, Object value) {
    if (null == value) {
      return 0;
    }

    if (isBinary(jdbcType)) {
      byte[] bytes = ObjectCastUtils.castToByteArray(value);
      return null == bytes ? 0 : bytes.length;
    } else if (isBoolean(jdbcType)) {
      return 1;
    } else {
      String strValue = ConvertUtils.convert(String.class,value);
      return null == strValue ? 0 : strValue.length();
    }
  }

  public static long getRecordSize(Object[] record, int[] jdbcTypes) {
    long bytes = 0;
    if (record.length == jdbcTypes.length) {
      for (int i = 0; i < record.length; ++i) {
        bytes += getObjectSize(jdbcTypes[i], record[i]);
      }
    }
    return bytes;
  }

  public static boolean supportConcurrentWrite(TargetEndpointProperties properties) {
    return !properties.getDriverClassName().contains("sqlite")
            && !properties.getUrl().contains("jdbc:sqlite:");
  }
}
