
package com.sondertara.joya.migration.provider;

import com.sondertara.common.base.Assert;
import com.sondertara.common.reflect.ReflectUtils;
import com.sondertara.joya.core.jdbc.support.DbType;
import com.sondertara.joya.migration.ProductExtensionFactory;
import com.sondertara.joya.migration.service.AbstractMetaService;
import com.sondertara.joya.migration.service.MetaDataService;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class ProductProviderFactory {

    private static Map<DbType, Class<? extends ProductFactoryProvider>> providers;

    static {
        providers = new ConcurrentHashMap<>();

        Map<String, Class<? extends ProductFactoryProvider>> classes = ProductExtensionFactory.findExtensionClasses(ProductFactoryProvider.class);

        classes.forEach((k, v) -> {
            DbType dbType = DbType.of(k);
            ProductProviderFactory.register(dbType, v);

        });
    }


    public static MetaDataService crateMetaService(ProductFactoryProvider factoryProvider) {
        return ReflectUtils.newInstance(ProductExtensionFactory.findExtensionClass(MetaDataService.class, null), factoryProvider);
    }


    public static void register(DbType type, Class<? extends ProductFactoryProvider> clazz) {
        log.info("Register product {} by subclass :{} ", type, clazz);
        // Check exists
        Assert.isFalse(providers.containsKey(type),
                "Exists ProductFactoryProvider: {} ({})",
                type.name(), providers.get(type.name()));

        // Register class
        providers.put(type, clazz);
    }

    public static ProductFactoryProvider newProvider(DbType type, DataSource dataSource) {
        Class<? extends ProductFactoryProvider> clazz = providers.get(type);
        Assert.notNull(clazz,
                "Not exists ProductFactoryProvider: {}", type);

        assert ProductFactoryProvider.class.isAssignableFrom(clazz);
        ProductFactoryProvider instance = null;
        try {
            instance = clazz.getDeclaredConstructor(DataSource.class).newInstance(dataSource);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Assert.isTrue(type.equals(instance.getProductType()),
                "ProductFactoryProvider with type '{}' " +
                        "can't be opened by product type '{}'",
                instance.getProductType(), type);
        return instance;
    }

}
