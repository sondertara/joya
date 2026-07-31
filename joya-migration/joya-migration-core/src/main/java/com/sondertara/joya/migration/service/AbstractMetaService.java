package com.sondertara.joya.migration.service;

import com.sondertara.joya.migration.provider.ProductFactoryProvider;

/**
 * TODO
 *
 * @author huangxiaohu.1ih
 * @date 2024/10/30 17:25
 */
public abstract class AbstractMetaService implements MetaDataService {
    protected final ProductFactoryProvider factoryProvider;

    protected AbstractMetaService(ProductFactoryProvider factoryProvider) {
        this.factoryProvider = factoryProvider;
    }

}


