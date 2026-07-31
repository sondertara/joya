
package com.sondertara.joya.migration.exchange.domain;

import com.sondertara.common.base.StringUtils;
import com.sondertara.joya.core.jdbc.support.DbType;
import com.sondertara.joya.core.jdbc.support.meta.TableType;
import com.sondertara.joya.datasource.DataSourceProperties;
import javafx.scene.control.Tab;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 源端参数配置
 *
 * @author tang
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SourceEndpointProperties extends DataSourceProperties {

    private DbType dbType;
    private Integer fetchSize = 5000;
    private String sourceSchema = "";
    private String tableType = "TABLE";
    private String sourceIncludes = "";
    private String sourceExcludes = "";
    private List<PatternMapper> regexTableMapper;
    private List<PatternMapper> regexColumnMapper;

    public String getTableType() {
        return StringUtils.isBlank(this.tableType) ? TableType.TABLE.getCode() : this.tableType;
    }
}
