package com.sondertara.joya.migration.oracle;

import com.sondertara.joya.migration.features.AbstractProductFeatures;
import com.sondertara.joya.migration.features.ProductFeatures;
import com.sondertara.joya.migration.provider.AbstractFactoryProvider;
import com.sondertara.joya.migration.provider.meta.MetadataProvider;

import javax.sql.DataSource;

/**
 * TODO
 *
 * @author huangxiaohu.1ih
 * @date 2024/10/28 10:37
 */
public class OracleProductFactoryProvider extends AbstractFactoryProvider {
    public OracleProductFactoryProvider(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    public ProductFeatures getProductFeatures() {
        return new AbstractProductFeatures() {
        };
    }

    @Override
    public MetadataProvider createMetadataQueryProvider() {
        return new OracleMetadataQueryProvider(this);
    }
}
