package com.sondertara.joya.cache;

import com.sondertara.common.function.TaraFunction;
import com.sondertara.common.util.StringFormatter;
import com.sondertara.common.util.StringUtils;
import com.sondertara.joya.utils.ThreadLocalUtil;
import com.sondertara.joya.core.model.ColumnAliasDTO;
import com.sondertara.joya.core.model.EntityFieldDTO;
import com.sondertara.joya.core.model.TableAliasDTO;
import com.sondertara.joya.core.model.TableDTO;

import javax.persistence.EntityNotFoundException;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.sondertara.joya.core.constant.JoyaConst.JOYA_SQL;

/**
 * (non-Javadoc)
 *
 * Alias cache based on ThreadLocal
 * Save the relationship of query table name and table alias
 *
 * @author huangxiaohu
 */
public final class AliasThreadLocalCache {

    /**
     * The pattern  for get part of method
     */
    private static final Pattern GET_PATTERN = Pattern.compile("^get[A-Z].*");
    /**
     * The pattern  for set part of method
     */
    private static final Pattern IS_PATTERN = Pattern.compile("^is[A-Z].*");

    /**
     * (non-Javadoc)
     * set table alias
     * <p>
     * the table alias will be like t0,t1...
     *
     * @param aClass the entity class
     */
    @SuppressWarnings("unchecked")
    public static void generateTableAlias(Class<?> aClass) {


        LocalEntityCache.getInstance().get(aClass.getName()).ifPresent(t -> {
            LinkedHashMap<String, TableAliasDTO> aliasMap = (LinkedHashMap<String, TableAliasDTO>) ThreadLocalUtil.get(JOYA_SQL);

            TableAliasDTO aliasDTO = new TableAliasDTO();
            aliasDTO.setClassName(aClass.getName());
            aliasDTO.setTableName(t.getTableName());
            aliasDTO.setAliasName(StringFormatter.format("t{}", aliasMap.size()));
            aliasMap.putIfAbsent(aClass.getName(), aliasDTO);
        });
    }

    /**
     * (non-Javadoc)
     * Get the column information
     *
     * @param taraSqlFn the apply function
     * @param <T>       generic
     * @return Object contain
     */
    @SuppressWarnings("unchecked")
    public static <T> ColumnAliasDTO getColumn(TaraFunction<T, ?> taraSqlFn) {
        try {
            Method method = taraSqlFn.getClass().getDeclaredMethod("writeReplace");
            method.setAccessible(Boolean.TRUE);
            SerializedLambda serializedLambda = (SerializedLambda) method.invoke(taraSqlFn);
            String implClass = serializedLambda.getImplClass();
            String getter = serializedLambda.getImplMethodName();

            String className = implClass.replace("/", ".");

            Optional<TableDTO> optional = LocalEntityCache.getInstance().get(className);
            // get the field name
            if (GET_PATTERN.matcher(getter).matches()) {
                getter = getter.substring(3);
            } else if (IS_PATTERN.matcher(getter).matches()) {
                getter = getter.substring(2);
            }
            String finalGetter = getter;
            AtomicReference<String> tableName = new AtomicReference<>();
            String columnName = optional.map(tableDTO -> {
                Set<EntityFieldDTO> fieldNames = tableDTO.getFieldNames();
                tableName.set(tableDTO.getTableName());

                Map<String, String> map = fieldNames.stream().collect(Collectors.toMap(EntityFieldDTO::getFieldName, EntityFieldDTO::getColumnName));
                String fieldName = StringUtils.toCamelCase(finalGetter);
                if (map.containsKey(fieldName)) {
                    return map.get(fieldName);
                } else {
                    return StringUtils.toUnderlineCase(finalGetter);
                }
            }).orElseThrow(() -> new EntityNotFoundException("no entity found for " + implClass));
            LinkedHashMap<String, TableAliasDTO> aliasMap = (LinkedHashMap<String, TableAliasDTO>) ThreadLocalUtil.get(JOYA_SQL);
            TableAliasDTO tableAlias = aliasMap.computeIfAbsent(className, k -> {
                TableAliasDTO aliasDTO = new TableAliasDTO();
                aliasDTO.setTableName(tableName.get());
                aliasDTO.setClassName(className);
                aliasDTO.setAliasName(StringFormatter.format("t{}", aliasMap.size()));
                return aliasDTO;
            });
            ColumnAliasDTO columnAliasDTO = new ColumnAliasDTO();
            columnAliasDTO.setTableName(tableName.get());
            columnAliasDTO.setColumnName(columnName);
            columnAliasDTO.setTableAlias(tableAlias.getAliasName());
            columnAliasDTO.setColumnAlias(StringFormatter.format("{}.{}", tableAlias.getAliasName(), columnName));
            return columnAliasDTO;
        } catch (
                ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }

    }

    /**
     * (non-Javadoc)
     * get all relation tables for the query
     *
     * @return Object of table
     */
    @SuppressWarnings("unchecked")
    public static List<TableAliasDTO> getTables() {
        LinkedHashMap<String, TableAliasDTO> aliasMap = (LinkedHashMap<String, TableAliasDTO>) ThreadLocalUtil.get(JOYA_SQL);
        return new ArrayList<>(aliasMap.values());

    }

}
