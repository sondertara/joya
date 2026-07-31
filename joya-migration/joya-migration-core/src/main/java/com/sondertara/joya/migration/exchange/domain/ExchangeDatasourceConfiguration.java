package com.sondertara.joya.migration.exchange.domain;

import com.sondertara.common.collection.Sets;
import com.sondertara.joya.core.jdbc.support.meta.TableType;
import lombok.Data;

import java.io.Serializable;
import java.util.Set;

/**
 * TODO
 *
 * @author huangxiaohu.1ih
 * @date 2024/10/29 9:52
 */
@Data
public class ExchangeDatasourceConfiguration implements Serializable {
    private SourceEndpointProperties source = new SourceEndpointProperties();

    private TargetEndpointProperties target = new TargetEndpointProperties();

    private GlobalParamConfigProperties config = new GlobalParamConfigProperties();
}
