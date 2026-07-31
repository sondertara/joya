
package com.sondertara.joya.migration.utils;


import com.sondertara.common.collection.CollectionUtils;
import com.sondertara.common.collection.Lists;
import com.sondertara.joya.core.jdbc.support.DbType;
import com.sondertara.joya.core.jdbc.support.meta.ColumnDescription;
import com.sondertara.joya.core.jdbc.support.meta.ColumnMetaData;
import com.sondertara.joya.core.jdbc.support.meta.ProductTableEnum;
import com.sondertara.joya.core.jdbc.support.meta.TableDescription;
import com.sondertara.joya.migration.SourceProperties;
import com.sondertara.joya.migration.provider.meta.MetadataProvider;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 拼接SQL工具类
 *
 * @author tang
 */
@UtilityClass
public final class GenerateSqlUtils {

    public static String getDDLCreateTableSQL(
            MetadataProvider provider,
            List<ColumnDescription> fieldNames,
            List<String> primaryKeys,
            String schemaName,
            String tableName,
            boolean autoIncr) {
        return getDDLCreateTableSQL(
                provider,
                fieldNames,
                primaryKeys,
                schemaName,
                tableName,
                false,
                null,
                autoIncr,
                null);
    }

    public static String getDDLCreateTableSQL(
            MetadataProvider provider,
            List<ColumnDescription> fieldNames,
            List<String> primaryKeys,
            String schemaName,
            String tableName,
            boolean withRemarks,
            String tableRemarks,
            boolean autoIncr,
            SourceProperties tblProperties) {
        DbType type = provider.getProductType();
        StringBuilder sb = new StringBuilder();
        Set<String> fieldNameSets = fieldNames.stream()
                .map(ColumnDescription::getColumnName)
                .collect(Collectors.toSet());
        List<String> pks = primaryKeys.stream()
                .filter(fieldNameSets::contains)
                .collect(Collectors.toList());

        sb.append("CREATE TABLE ");
        provider.preAppendCreateTableSql(sb);
        sb.append(provider.getQuotedSchemaTableCombination(schemaName, tableName));
        sb.append("(");

        // StarRocks 当中，字段主键的情况下，必须将字段放在最前面，并且顺序一致。
        if (isPrimaryKeyShouldAtFirst(type)) {
            List<ColumnDescription> copyFieldNames = new ArrayList<>();
            Integer fieldIndex = 0;
            for (int i = 0; i < fieldNames.size(); i++) {
                ColumnDescription cd = fieldNames.get(i);
                if (primaryKeys.contains(cd.getColumnName())) {
                    copyFieldNames.add(fieldIndex++, cd);
                } else {
                    copyFieldNames.add(cd);
                }
            }
            fieldNames = copyFieldNames;
        }

        for (int i = 0; i < fieldNames.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }

            ColumnMetaData v = fieldNames.get(i).getMetaData();
            sb.append(provider.getFieldDefinition(v, pks, autoIncr, false, withRemarks));
        }

        if (isParenthesisBefore(type)) {
            sb.append(")");
            provider.appendPrimaryKeyForCreateTableSql(sb, pks);
        } else {
            provider.appendPrimaryKeyForCreateTableSql(sb, pks);
            sb.append(")");
        }

        provider.postAppendCreateTableSql(sb, tableRemarks, pks, tblProperties);

        return DDLFormatterUtils.format(sb.toString());
    }

    public static List<String> getDDLCreateTableSQL(
            MetadataProvider provider,
            List<ColumnDescription> fieldNames,
            List<String> primaryKeys,
            String schemaName,
            String tableName,
            String tableRemarks,
            boolean autoIncr,
            SourceProperties tblProperties) {
        DbType productType = provider.getProductType();
        if (productType.isLikeHive()) {
            return provider.getCreateTableSqlList(
                    fieldNames, primaryKeys, schemaName, tableName, tableRemarks, autoIncr, tblProperties);
        } else if (noCommentStatement(productType)) {
            String createTableSql = getDDLCreateTableSQL(provider, fieldNames, primaryKeys, schemaName,
                    tableName, true, tableRemarks, autoIncr, tblProperties);
            return Arrays.asList(createTableSql);
        } else {
            String createTableSql = getDDLCreateTableSQL(provider, fieldNames, primaryKeys, schemaName,
                    tableName, true, tableRemarks, autoIncr, tblProperties);
            TableDescription td = new TableDescription();
            td.setSchema(schemaName);
            td.setTableName(tableName);
            td.setRemarks(tableRemarks);
            td.setTableType(ProductTableEnum.TABLE.name());
            List<String> results = provider.getTableColumnCommentDefinition(td, fieldNames);
            if (CollectionUtils.isEmpty(results)) {
                results = Lists.newArrayList(createTableSql);
            } else {
                results.add(0, createTableSql);
            }
            return results;
        }
    }


    public boolean noCommentStatement(DbType target) {
        return Arrays.asList(
                DbType.mysql,
                DbType.mariadb,
                DbType.gbase,
                DbType.hive,
                DbType.sqlite,
                DbType.sybase
        ).contains(target);
    }

    /**
     * 建表语句中主键字段是否必须放在最前边
     *
     * @return boolean
     */
    public boolean isPrimaryKeyShouldAtFirst(DbType target) {
        return false;
    }

    /**
     * 是否拼接建表SQl时小括号在PK前面
     *
     * @return boolean
     */
    public boolean isParenthesisBefore(DbType target) {
        return false;
    }


}
