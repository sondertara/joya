package com.sondertara.joya.core.cache;


import com.sondertara.common.base.Assert;
import com.sondertara.common.classpath.ClassLoaderUtils;
import com.sondertara.common.collection.Maps;
import com.sondertara.common.exception.TaraException;
import com.sondertara.common.function.IGetter;
import com.sondertara.common.struct.Pair;
import com.sondertara.common.base.StringUtils;
import com.sondertara.joya.core.model.ColumnAlias;
import com.sondertara.joya.core.model.EntityTableMetaDefinition;
import com.sondertara.joya.core.model.TableAlias;
import com.sondertara.joya.core.query.ColumnPair;
import com.sondertara.joya.core.query.NativeSqlQueryConfiguration;
import com.sondertara.joya.exceptions.SqlParseException;
import com.sondertara.joya.utils.LambdaUtils;
import com.sondertara.joya.utils.SqlFormatUtils;

import javax.persistence.EntityNotFoundException;
import java.lang.invoke.SerializedLambda;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * (non-Javadoc)
 * <p>
 * Alias cache based on ThreadLocal
 * Save the relationship of query table name and table alias
 *
 * @author huangxiaohu
 */
public final class AliasCacheHelper {

    /**
     * The pattern  for get part of method
     */
    public static final Pattern GET_PATTERN = Pattern.compile("^get[A-Z].*");
    /**
     * The pattern  for set part of method
     */
    public static final Pattern IS_PATTERN = Pattern.compile("^is[A-Z].*");

    private final Map<String, TableAlias> aliasMap = Maps.newConcurrentMap();

    private final NativeSqlQueryConfiguration queryConfiguration;

    public AliasCacheHelper(NativeSqlQueryConfiguration queryConfiguration) {
        this.queryConfiguration = queryConfiguration;
    }


    /**
     * (non-Javadoc)
     * set table alias
     * <p>
     * the table alias will be like t0,t1...
     *
     * @param aClass the entity class
     */
    public TableAlias generateTableAlias(Class<?> aClass) {

        EntityTableMetaDefinition metaDefinition = getEntityMetaDefinition(aClass).orElseThrow(() -> new TaraException("No entity found with class:{}", aClass.getName()));
        TableAlias aliasDTO = new TableAlias();
        aliasDTO.setTableName(metaDefinition.getTableName());
        aliasDTO.setAliasName(StringUtils.format("t{}", aliasMap.size()));
        aliasDTO.setEntityClass(aClass);
        aliasMap.putIfAbsent(metaDefinition.getTableName().toLowerCase(), aliasDTO);

        TableAlias tableAlias = aliasMap.get(metaDefinition.getTableName().toLowerCase());
        if (null==tableAlias.getEntityClass()){
            tableAlias.setEntityClass(metaDefinition.getEntityClass());
        }
        return aliasDTO;
    }

    /**
     * return the tableAndAlias eg: test t0
     *
     * @param tableAndAlias
     * @return
     */
    public TableAlias generateTableAlias(String tableAndAlias) {
        Assert.notBlank(tableAndAlias);
        String[] strings = tableAndAlias.split(" ");
        String tableName = StringUtils.trim(strings[0]);

        return aliasMap.computeIfAbsent(tableName.toLowerCase(), key -> {
            String aliasName = null;
            if (strings.length == 2) {
                aliasName = StringUtils.trim(strings[1]);
            } else {
                aliasName = StringUtils.format("t{}", aliasMap.size());
            }
            TableAlias aliasDTO = new TableAlias();
            aliasDTO.setTableName(tableName);
            aliasDTO.setAliasName(aliasName);

            return aliasDTO;

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
    public <T> ColumnAlias getColumn(IGetter<T, ?> taraSqlFn) {
        SerializedLambda serializedLambda = LambdaUtils.resolve(taraSqlFn);
        String implClass = serializedLambda.getImplClass();
        String getter = serializedLambda.getImplMethodName();

        String className = implClass.replace("/", ".");

        Optional<EntityTableMetaDefinition> optional = getEntityMetaDefinition(ClassLoaderUtils.loadClass(className));
        // get the field name
        if (GET_PATTERN.matcher(getter).matches()) {
            getter = getter.substring(3);
        } else if (IS_PATTERN.matcher(getter).matches()) {
            getter = getter.substring(2);
        }
        String finalGetter = getter;
        AtomicReference<String> tableName = new AtomicReference<>();
        ColumnPair columnPair = optional.map(metaDefinition -> {
            tableName.set(metaDefinition.getTableName());

            Set<String> fieldNames = metaDefinition.getFieldNames();

            String fieldName = StringUtils.lowerFirst(finalGetter);
            if (fieldNames.contains(fieldName)) {
                return new ColumnPair(metaDefinition.findColumnNameByField(fieldName), fieldName);
            }
            throw new EntityNotFoundException("No column found by " + finalGetter);
        }).orElseThrow(() -> new EntityNotFoundException("no entity found for " + implClass));
        TableAlias tableAlias = this.aliasMap.computeIfAbsent(tableName.get().toLowerCase(), k -> {
            TableAlias aliasDTO = new TableAlias();
            aliasDTO.setTableName(tableName.get());
            aliasDTO.setEntityClass(optional.get().getEntityClass());
            aliasDTO.setAliasName(StringUtils.format("t{}", aliasMap.size()));
            return aliasDTO;
        });
        ColumnAlias columnAlias = new ColumnAlias();
        columnAlias.setTableName(tableName.get());
        columnAlias.setTableAlias(tableAlias.getAliasName());
        columnAlias.setColumnAlias(columnPair.getValue());
        columnAlias.setColumnName(columnPair.getKey());
        return columnAlias;

    }

    /**
     * (non-Javadoc)
     * get all relation tables for the query
     *
     * @return Object of table
     */
    public LinkedHashSet<TableAlias> getTables() {
        return new LinkedHashSet<>(aliasMap.values()).stream().sorted(Comparator.comparing(TableAlias::getAliasName)).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public LinkedHashSet<ColumnAlias> getAllColumns(Class<?> entityClass) {
        LinkedHashSet<ColumnAlias> columnAliases = new LinkedHashSet<>();
        EntityTableMetaDefinition metaDefinition =getEntityMetaDefinition(entityClass).orElseThrow(() -> new EntityNotFoundException("No entity found for " + entityClass));
        TableAlias tableAlias = aliasMap.get(metaDefinition.getTableName().toLowerCase());
        if (null==tableAlias.getEntityClass()){
            tableAlias.setEntityClass(metaDefinition.getEntityClass());
        }

        for (String columnName : metaDefinition.getColumnNames()) {
            ColumnAlias columnAlias = new ColumnAlias();
            columnAlias.setColumnName(columnName);
            columnAlias.setTableName(tableAlias.getTableName());
            columnAlias.setTableAlias(tableAlias.getAliasName());
            columnAlias.setColumnAlias(metaDefinition.findFieldNameByColumn(columnName));
            columnAliases.add(columnAlias);
        }

        return columnAliases;
    }

    /**
     * 获取数据库中的列名
     *
     * @param column 表别名
     * @return 列名
     */

    public String getColumnName(String column) {
        return resolveColumn(column).identity();

    }


    public ColumnAlias resolveColumn(String column) {
        if (null == column) {
            return null;
        }
        int index = column.indexOf(".");
        ColumnAlias columnAlias = new ColumnAlias();
        if (index < 0) {
            ColumnPair columnPair = SqlFormatUtils.warpColumn(column);
            columnAlias.setColumnName(columnPair.getKey());
            columnAlias.setColumnAlias(columnPair.getValue());
            return columnAlias;
        }
        String tableAlias = column.substring(0, index);
        String fieldName = column.substring(index + 1);
        ColumnPair warpedColumn = SqlFormatUtils.warpColumn(fieldName);

        ColumnPair columnPair = warpedColumn;
        TableAlias currentTableAlias = null;
        Set<TableAlias> tables = getTables();
        for (TableAlias table : tables) {
            if (tableAlias.equals(table.getAliasName())) {

                columnPair = Optional.ofNullable(table.getEntityClass()).map(e -> {
                    return getEntityMetaDefinition(e).map(t -> {
                        String warpedColumnKey = warpedColumn.getKey();
                        String warpedColumnValue = warpedColumn.getValue();
                        //通过columnName查找
                        Pair<String, String> pair = t.findColumnFiledNamePairByColumn(warpedColumnKey);
                        if (pair != null) {
                            return new ColumnPair(pair.getKey(), Optional.ofNullable(warpedColumnValue).orElse(pair.getValue()));
                        }
                        //通过fieldName查找
                        String trimmed = fieldName.split(" ")[0].trim();
                        if (trimmed.contains("_")) {
                            trimmed = StringUtils.toCamelCase(trimmed);
                        }
                        final String finalTrimmed = trimmed;
                        Optional<String> optional = t.getFieldNames().stream().filter(s -> s.equalsIgnoreCase(finalTrimmed)).findFirst();
                        if (optional.isPresent()) {
                            return new ColumnPair(t.findColumnNameByField(optional.get()), Optional.ofNullable(warpedColumnValue).orElse(optional.get()));
                        }
                        throw new SqlParseException("No column found for table [{}] by name [{}]", table.getTableName(), fieldName);
                    }).orElse(warpedColumn);

                }).orElse(warpedColumn);

                currentTableAlias = table;
                break;
            }
        }

        columnAlias.setTableName(Optional.ofNullable(currentTableAlias).map(TableAlias::getTableName).orElse(null));
        columnAlias.setTableAlias(Optional.ofNullable(currentTableAlias).map(TableAlias::getAliasName).orElse(tableAlias));
        columnAlias.setColumnName(columnPair.getKey());
        columnAlias.setColumnAlias(columnPair.getValue());
        return columnAlias;
    }


    public Optional<EntityTableMetaDefinition> getEntityMetaDefinition(Class<?> entityClass) {
        return CacheHolder.tableMetaCache().findByEntityClass(entityClass).map(s -> {
            s.setColumnUppercase(queryConfiguration.isColumnUppercase());
            return s;
        });
    }


}
