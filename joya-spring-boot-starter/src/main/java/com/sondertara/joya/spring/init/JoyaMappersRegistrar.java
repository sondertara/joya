package com.sondertara.joya.spring.init;


import com.sondertara.common.base.StringUtils;
import com.sondertara.joya.spring.annotation.EnableJoyaJdbc;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.lang.NonNull;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * @author huangxiaohu
 */
@Slf4j
public class JoyaMappersRegistrar implements ImportBeanDefinitionRegistrar, EnvironmentAware, Ordered {
    private static final String PREFIX = "joya#";


    @Override
    public void registerBeanDefinitions(@NonNull AnnotationMetadata annotationMetadata, @NonNull BeanDefinitionRegistry beanDefinitionRegistry) {


        Map<String, Object> defaultAttrs = annotationMetadata.getAnnotationAttributes(EnableJoyaJdbc.class.getName(), true);

        Set<String> packageNames = new HashSet<>();

        if (defaultAttrs != null) {
            boolean enableScan = Boolean.parseBoolean(defaultAttrs.get("enableScan").toString());
            if (!enableScan) {
                return;
            }
            Object basePackages = defaultAttrs.get("value");
            if (ObjectUtils.isEmpty(basePackages)) {
                basePackages = defaultAttrs.get("basePackages");
            }
            if (basePackages instanceof String) {
                String trim = StringUtils.trim(basePackages.toString());
                if (!trim.isEmpty()) {
                    packageNames.add(trim);
                }
            } else if (basePackages instanceof String[]) {
                String[] strings = (String[]) basePackages;
                for (String s : strings) {
                    packageNames.add(StringUtils.trim(s));
                }
            }
            if (packageNames.isEmpty()) {
                packageNames.add(getDefaultBasePackage(annotationMetadata));
            }


            BeanDefinitionBuilder definition = BeanDefinitionBuilder.genericBeanDefinition(JoyaMapperConfigurer.class);
            definition.addPropertyValue("basePackage", String.join(",", packageNames));
            beanDefinitionRegistry.registerBeanDefinition("joyaMapperScannerConfigurer", definition.getBeanDefinition());
            log.info("Scanning joya mapper for packages: {}", packageNames);
        }
    }

    private static String getDefaultBasePackage(AnnotationMetadata importingClassMetadata) {
        return ClassUtils.getPackageName(importingClassMetadata.getClassName());
    }

    private <T> void registerMapper(BeanDefinitionRegistry registry, Class<T> clazz) {
        String name = PREFIX + clazz.getSimpleName();
        BeanDefinitionBuilder definitionBuilder = BeanDefinitionBuilder.genericBeanDefinition(clazz);

        definitionBuilder.addPropertyValue("name", name);
        definitionBuilder.addPropertyValue("mapperInterface", clazz);
        registry.registerBeanDefinition(name, definitionBuilder.getBeanDefinition());


    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public void setEnvironment(@NonNull Environment environment) {
        log.info("EnvironmentAware#setEnvironment");
    }
}
