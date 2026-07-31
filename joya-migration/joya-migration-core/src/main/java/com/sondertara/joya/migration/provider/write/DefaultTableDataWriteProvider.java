
package com.sondertara.joya.migration.provider.write;


import com.sondertara.common.base.StringUtils;
import com.sondertara.joya.core.JoyaJdbc;
import com.sondertara.joya.migration.provider.AbstractCommonProvider;
import com.sondertara.joya.migration.provider.ProductFactoryProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 数据同步抽象基类
 *
 * @author tang
 */
@Slf4j
public class DefaultTableDataWriteProvider
        extends AbstractCommonProvider implements TableDataWriteProvider {

    private JoyaJdbc jdbcTemplate;
    protected List<String> fieldOrders;
    protected Map<String, Integer> columnType;
    protected List<String> pksOrders;

    protected String schemaName;
    protected String tableName;

    public DefaultTableDataWriteProvider(ProductFactoryProvider factoryProvider) {
        this.jdbcTemplate = new JoyaJdbc(factoryProvider.getDataSource());
        this.columnType = new HashMap<>();
    }

    @Override
    public void prepare(String schemaName, String tableName, List<String> fieldNames, List<String> pks) {
        if (fieldNames.isEmpty() || fieldNames.size() < pks.size()) {
            throw new IllegalArgumentException("字段列表和主键列表不能为空，或者字段总个数应不小于主键总个数");
        }
        if (!new HashSet<>(fieldNames).containsAll(pks)) {
            throw new IllegalArgumentException("字段列表必须包含主键列表");
        }

        this.schemaName = schemaName;
        this.tableName = tableName;
        this.columnType = getTableColumnMetaData(schemaName, tableName, fieldNames);
        this.fieldOrders = new ArrayList<>(fieldNames);
        this.pksOrders = new ArrayList<>(pks);
    }

    @Override
    public long executeInsert(List<String> fieldNames, List<Object[]> records) {

        String statementSql = getInsertPrepareStatementSql(schemaName, tableName, fieldNames);

        int[] insertArgsType = getInsertArgsType(fieldNames);

        jdbcTemplate.batchUpdate(statementSql, records, insertArgsType);
        return records.size();
    }

    protected int[] getInsertArgsType(List<String> fieldNames) {
        int[] insertArgsType = new int[fieldNames.size()];
        for (int k = 0; k < fieldNames.size(); ++k) {
            String field = fieldNames.get(k);
            insertArgsType[k] = this.columnType.get(field);
        }
        return insertArgsType;

    }

    protected int[] getUpdateArgsType(List<String> fieldNames) {
        int[] updateArgsType = new int[fieldNames.size()];
        int idx = 0;
        for (int i = 0; i < fieldNames.size(); ++i) {
            String field = fieldNames.get(i);
            if (!pksOrders.contains(field)) {
                updateArgsType[idx++] = this.columnType.get(field);
            }
        }
        for (String pk : pksOrders) {
            updateArgsType[idx++] = this.columnType.get(pk);
        }

        return updateArgsType;
    }

    @Override
    public long executeUpdate(List<String> fieldNames, List<Object[]> records) {

        if (this.pksOrders.isEmpty()) {
            throw new IllegalArgumentException(StringUtils.format("executeUpdate:{}.{}表主键列表不能为空", schemaName, tableName));
        }

        String updateStatementSql = getUpdatePrepareStatementSql(schemaName, tableName, fieldNames, this.pksOrders);

        int[] updateArgsType = getUpdateArgsType(fieldNames);

        List<Object[]> dataLists = new LinkedList<>();
        for (Object[] r : records) {
            Object[] nr = new Object[this.fieldOrders.size()];
            int idx = 0;
            for (int i = 0; i < this.fieldOrders.size(); ++i) {
                String field = this.fieldOrders.get(i);
                if (!this.pksOrders.contains(field)) {
                    int index = this.fieldOrders.indexOf(field);
                    nr[idx++] = r[index];
                }
            }
            for (int j = 0; j < this.pksOrders.size(); ++j) {
                String pk = this.pksOrders.get(j);
                int index = this.fieldOrders.indexOf(pk);
                nr[idx++] = r[index];
            }
            dataLists.add(nr);
        }

        if (log.isDebugEnabled()) {
            log.debug("Execute Update SQL : {}", updateStatementSql);
        }

        jdbcTemplate.batchUpdate(updateStatementSql, dataLists, updateArgsType);
        return dataLists.size();

    }

    @Override
    public long executeDelete(List<Object[]> records) {
        if (this.pksOrders.isEmpty()) {
            throw new IllegalArgumentException(StringUtils.format("executeUpdate:{}.{}表主键列表不能为空", schemaName, tableName));
        }
        String deleteStatementSql = getDeletePrepareStatementSql(schemaName, tableName, pksOrders);
        int[] deleteArgsType = new int[pksOrders.size()];
        for (int j = 0; j < pksOrders.size(); ++j) {
            String pk = pksOrders.get(j);
            deleteArgsType[j] = this.columnType.get(pk);
        }
        List<Object[]> dataLists = new LinkedList<>();
        for (Object[] r : records) {
            Object[] nr = new Object[this.pksOrders.size()];
            for (int i = 0; i < this.pksOrders.size(); ++i) {
                String pk = this.pksOrders.get(i);
                int index = this.fieldOrders.indexOf(pk);
                nr[i] = r[index];
            }
            dataLists.add(nr);
        }

        if (log.isDebugEnabled()) {
            log.debug("Execute Delete SQL : {}", deleteStatementSql);
        }

        jdbcTemplate.batchUpdate(deleteStatementSql, dataLists, deleteArgsType);
        return dataLists.size();
    }

    /**
     * 生成Insert操作的SQL语句
     *
     * @param schemaName 模式名称
     * @param tableName  表名称
     * @param fieldNames 字段列表
     * @return Insert操作的SQL语句
     */
    protected String getInsertPrepareStatementSql(String schemaName, String tableName,
                                                  List<String> fieldNames) {
        List<String> placeHolders = Collections.nCopies(fieldNames.size(), "?");
        String fullTableName = sqlDialect.tableName(false, null, schemaName, tableName);
        return String.format("INSERT INTO %s ( %s ) VALUES ( %s )",
                fullTableName,
                fieldNames.stream().map(s -> sqlDialect.fmtName(false, s)).collect(Collectors.joining(",")),
                StringUtils.join(placeHolders, ","));
    }


    /**
     * 生成Update操作的SQL语句
     *
     * @param schemaName 模式名称
     * @param tableName  表名称
     * @param fieldNames 字段列表
     * @param pks        主键列表
     * @return Update操作的SQL语句
     */
    protected String getUpdatePrepareStatementSql(String schemaName, String tableName,
                                                  List<String> fieldNames, List<String> pks) {
        String fullTableName = sqlDialect.tableName(false, null, schemaName, tableName);
        List<String> uf = fieldNames.stream()
                .filter(field -> !pks.contains(field))
                .map(field -> String.format("%s=?", sqlDialect.fmtName(false, field)))
                .collect(Collectors.toList());
        List<String> uw = pks.stream()
                .map(pk -> String.format("%s=?", sqlDialect.fmtName(false, pk)))
                .collect(Collectors.toList());
        return String.format("UPDATE %s SET %s WHERE %s",
                fullTableName, StringUtils.join(uf, " , "),
                StringUtils.join(uw, " AND "));
    }

    /**
     * 生成Delete操作的SQL语句
     *
     * @param schemaName 模式名称
     * @param tableName  表名称
     * @param pks        主键列表
     * @return Delete操作的SQL语句
     */
    protected String getDeletePrepareStatementSql(String schemaName, String tableName,
                                                  List<String> pks) {
        String fullTableName = sqlDialect.tableName(false, null, schemaName, tableName);
        List<String> uw = pks.stream()
                .map(pk -> String.format("%s=?", sqlDialect.fmtName(false, pk)))
                .collect(Collectors.toList());
        return String.format("DELETE FROM %s WHERE %s ",
                fullTableName, StringUtils.join(uw, "  AND  "));
    }

}
