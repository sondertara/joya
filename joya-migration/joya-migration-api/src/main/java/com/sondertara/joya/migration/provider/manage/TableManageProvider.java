
package com.sondertara.joya.migration.provider.manage;


import com.sondertara.common.spi.ServiceUsed;

@ServiceUsed
public interface TableManageProvider {

    /**
     * 清除指定表的所有数据
     *
     * @param schemaName 模式名称
     * @param tableName  表名称
     */
    void truncateTableData(String schemaName, String tableName);

    /**
     * 删除指定物理表
     *
     * @param schemaName 模式名称
     * @param tableName  表名称
     */
    void dropTable(String schemaName, String tableName);
}
