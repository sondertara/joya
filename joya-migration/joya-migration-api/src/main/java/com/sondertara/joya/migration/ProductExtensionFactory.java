package com.sondertara.joya.migration;

import com.sondertara.common.spi.ExtensionServiceProvider;
import com.sondertara.common.spi.ServiceUsed;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Optional;

/**
 * TODO
 *
 * @author huangxiaohu.1ih
 * @date 2024/10/30 16:50
 */
public class ProductExtensionFactory {

    private static final String MIGRATOR_SPI_FILE = "META-INF/joya-migration/";


    static ExtensionServiceProvider EXTENSION_SERVICE_PROVIDER = new ExtensionServiceProvider(MIGRATOR_SPI_FILE);

    public static <T> T createExtension(Class<T> clazz, @Nullable String name) {

        String extensionName = buildName(clazz, name);

        return EXTENSION_SERVICE_PROVIDER.loadInstances(clazz).get(extensionName);

    }

    public static <T> Class<? extends T> findExtensionClass(Class<T> clazz, @Nullable String name) {
        String extensionName = buildName(clazz, name);
        return EXTENSION_SERVICE_PROVIDER.loadClasses(clazz).get(extensionName);
    }

    public static <T> Map<String, Class<? extends T>> findExtensionClasses(Class<T> clazz) {
        return EXTENSION_SERVICE_PROVIDER.loadClasses(clazz);
    }


    private static <T> String buildName(Class<T> clazz, @Nullable String name) {
        return Optional.ofNullable(name)
                .orElse(
                        Optional.ofNullable(clazz.getAnnotation(ServiceUsed.class))
                                .map(ServiceUsed::value)
                                .orElse("default"));
    }
}
