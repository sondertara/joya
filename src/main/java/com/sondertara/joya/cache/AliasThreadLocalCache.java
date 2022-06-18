package com.sondertara.joya.cache;

import com.sondertara.common.exception.TaraException;
import com.sondertara.common.function.TaraFunction;
import com.sondertara.common.lang.Assert;
import com.sondertara.common.util.StringFormatter;
import com.sondertara.common.util.StringUtils;
import com.sondertara.joya.core.model.ColumnAlias;
import com.sondertara.joya.core.model.TableAlias;
import com.sondertara.joya.core.model.TableStruct;
import com.sondertara.joya.utils.ThreadLocalUtil;

import javax.persistence.EntityNotFoundException;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static com.sondertara.joya.core.constant.JoyaConst.JOYA_SQL;

/**
 * (non-Javadoc)
 *
 * <p>Alias cache based on ThreadLocal Save the relationship of query table name and table alias
 *
 * @author huangxiaohu
 */
public final class AliasThreadLocalCache {

  /** The pattern for get part of method */
  private static final Pattern GET_PATTERN = Pattern.compile("^get[A-Z].*");
  /** The pattern for set part of method */
  private static final Pattern IS_PATTERN = Pattern.compile("^is[A-Z].*");

  /**
   * (non-Javadoc) set table alias
   *
   * <p>the table alias will be like t0,t1...
   *
   * @param aClass the entity class
   */
  @SuppressWarnings("unchecked")
  public static void generateTableAlias(Class<?> aClass) {

    TableStruct tableStruct =
        LocalEntityCache.getInstance()
            .get(aClass.getName())
            .orElseThrow(
                () -> new TaraException("No entity found with class:{}", aClass.getName()));
    LinkedHashMap<String, TableAlias> aliasMap =
        (LinkedHashMap<String, TableAlias>) ThreadLocalUtil.get(JOYA_SQL);

    TableAlias aliasDTO = new TableAlias();
    aliasDTO.setClassName(aClass.getName());
    aliasDTO.setTableName(tableStruct.getTableName());
    aliasDTO.setAliasName(StringFormatter.format("t{}", aliasMap.size()));
    aliasMap.putIfAbsent(aClass.getName(), aliasDTO);
  }

  @SuppressWarnings("unchecked")
  public static void generateTableAlias(String tableAndAlias) {
    Assert.notBlank(tableAndAlias);
    String[] strings = tableAndAlias.split(" ");

    LocalEntityCache.getInstance()
        .get(strings[0].toLowerCase())
        .ifPresent(
            t -> {
              LinkedHashMap<String, TableAlias> aliasMap =
                  (LinkedHashMap<String, TableAlias>) ThreadLocalUtil.get(JOYA_SQL);

              TableAlias aliasDTO = new TableAlias();
              aliasDTO.setClassName(t.getClassName());
              aliasDTO.setTableName(t.getTableName());
              aliasDTO.setAliasName(StringFormatter.format("t{}", aliasMap.size()));
              aliasMap.putIfAbsent(t.getClassName(), aliasDTO);
            });
  }

  /**
   * (non-Javadoc) Get the column information
   *
   * @param taraSqlFn the apply function
   * @param <T> generic
   * @return Object contain
   */
  @SuppressWarnings("unchecked")
  public static <T> ColumnAlias getColumn(TaraFunction<T, ?> taraSqlFn) {
    try {
      Method method = taraSqlFn.getClass().getDeclaredMethod("writeReplace");
      method.setAccessible(Boolean.TRUE);
      SerializedLambda serializedLambda = (SerializedLambda) method.invoke(taraSqlFn);
      String implClass = serializedLambda.getImplClass();
      String getter = serializedLambda.getImplMethodName();

      String className = implClass.replace("/", ".");

      Optional<TableStruct> optional = LocalEntityCache.getInstance().get(className);
      // get the field name
      if (GET_PATTERN.matcher(getter).matches()) {
        getter = getter.substring(3);
      } else if (IS_PATTERN.matcher(getter).matches()) {
        getter = getter.substring(2);
      }
      String finalGetter = getter;
      AtomicReference<String> tableName = new AtomicReference<>();
      String columnName =
          optional
              .map(
                  tableDTO -> {
                    Map<String, String> map = tableDTO.getFields();
                    tableName.set(tableDTO.getTableName());

                    String fieldName = StringUtils.lowerFirst(finalGetter);
                    if (map.containsKey(fieldName)) {
                      return map.get(fieldName);
                    }
                    throw new EntityNotFoundException("No column found by " + finalGetter);
                  })
              .orElseThrow(() -> new EntityNotFoundException("no entity found for " + implClass));
      LinkedHashMap<String, TableAlias> aliasMap =
          (LinkedHashMap<String, TableAlias>) ThreadLocalUtil.get(JOYA_SQL);
      TableAlias tableAlias =
          aliasMap.computeIfAbsent(
              className,
              k -> {
                TableAlias aliasDTO = new TableAlias();
                aliasDTO.setTableName(tableName.get());
                aliasDTO.setClassName(className);
                aliasDTO.setAliasName(StringFormatter.format("t{}", aliasMap.size()));
                return aliasDTO;
              });
      ColumnAlias columnAlias = new ColumnAlias();
      columnAlias.setTableName(tableName.get());
      columnAlias.setColumnName(columnName);
      columnAlias.setTableAlias(tableAlias.getAliasName());
      columnAlias.setColumnAlias(
          StringFormatter.format("{}.{}", tableAlias.getAliasName(), columnName));
      return columnAlias;
    } catch (ReflectiveOperationException e) {
      throw new TaraException(e);
    }
  }

  /**
   * (non-Javadoc) get all relation tables for the query
   *
   * @return Object of table
   */
  @SuppressWarnings("unchecked")
  public static List<TableAlias> getTables() {
    LinkedHashMap<String, TableAlias> aliasMap =
        (LinkedHashMap<String, TableAlias>) ThreadLocalUtil.get(JOYA_SQL);
    return new ArrayList<>(aliasMap.values());
  }

  public static TableStruct getTable(String className) {
    Optional<TableStruct> optional = LocalEntityCache.getInstance().get(className);
    return optional.orElseThrow(
        () -> new TaraException("No Table found by className:" + className));
  }

  /**
   * 获取数据库中的列明名
   *
   * @param column 表别名
   * @return 列名
   */
  public static String getColumnName(String column) {

    if (null == column) {
      return null;
    }
    int index = column.indexOf(".");
    if (index < 0) {
      throw new TaraException("The column is incorrect,it should like 't0.userName.'");
    }
    String tableAlias = column.substring(0, index);
    String fieldName = column.substring(index + 1);
    String s = null;
    List<TableAlias> tables = getTables();
    for (TableAlias table : tables) {
      if (tableAlias.equals(table.getAliasName())) {
        String className = table.getClassName();
        s =
            LocalEntityCache.getInstance()
                .get(className)
                .map(
                    t -> {
                      Map<String, String> fields = t.getFields();
                      // if the column  is entity field
                      if (fields.containsKey(StringUtils.toCamelCase(fieldName))) {
                        return fields.get(StringUtils.toCamelCase(fieldName));
                      } else {
                        // if the column is table column
                        for (String value : fields.values()) {
                          if (value.equalsIgnoreCase(StringUtils.toUnderlineCase(fieldName))) {
                            return value;
                          }
                        }
                      }
                      throw new TaraException(
                          "No column found for table [{}] by name [{}]",
                          table.getTableName(),
                          fieldName);
                    })
                .orElseThrow(
                    () -> new TaraException("No table found by className [{}]", className));
      }
    }
    return StringFormatter.format("{}.{}", tableAlias, s);
  }
}
