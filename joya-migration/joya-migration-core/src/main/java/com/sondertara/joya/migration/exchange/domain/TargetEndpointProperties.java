
package com.sondertara.joya.migration.exchange.domain;

import com.sondertara.joya.core.jdbc.support.DbType;
import com.sondertara.joya.datasource.DataSourceProperties;
import com.sondertara.joya.migration.consts.CaseConvertEnum;
import com.sondertara.joya.migration.consts.SyncOptionEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 目标端参数配置
 *
 * @author tang
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TargetEndpointProperties extends DataSourceProperties {
    private DbType dbType;
    private String targetSchema = "";
    private CaseConvertEnum tableNameCase = CaseConvertEnum.NONE;
    private CaseConvertEnum columnNameCase = CaseConvertEnum.NONE;
    private Boolean targetDrop = Boolean.TRUE;
    private Boolean onlyCreate = Boolean.FALSE;
    private Boolean deleteNotExistRow = Boolean.FALSE;
    private Boolean changeDataSync = Boolean.FALSE;
    private Boolean createTableAutoIncrement = Boolean.FALSE;
    private Boolean writerEngineInsert = Boolean.FALSE;

    private String beforeSqlScripts;
    private String afterSqlScripts;
    private SyncOptionEnum targetSyncOption = SyncOptionEnum.INSERT_UPDATE_DELETE;


    public  boolean supportConcurrentWrite(TargetEndpointProperties properties) {
        return !getDriverClassName().contains("sqlite")
                && !getUrl().contains("jdbc:sqlite:");
    }
}
