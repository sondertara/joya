package com.sondertara.joya.spring.init;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.PropertyResourceConfigurer;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

/**
 * @author huangxiaohu
 */

@Slf4j
public class JoyaMapperConfigurer implements BeanDefinitionRegistryPostProcessor, ApplicationContextAware, InitializingBean, BeanNameAware {


    //Map<String, AbstractBeanDefinition> list = Maps.newHashMap();
    private String basePackage;

    private String beanName;
    private ApplicationContext applicationContext;


    public void setBasePackage(String basePackage) {
        this.basePackage = basePackage;
    }


    @Override
    public void setBeanName(String name) {
        this.beanName = name;
    }


    private Environment getEnvironment() {
        return this.applicationContext.getEnvironment();
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        processPropertyPlaceHolders();
        //for (String pkg : basePackage) {
        //    Set<Class<?>> classes = ClassScanner.scanAllPackageBySuper(pkg, JoyaMapper.class);
        //
        //    for (Class aClass : classes) {
        //        BeanDefinitionBuilder definitionBuilder = BeanDefinitionBuilder.genericBeanDefinition(aClass, () -> {
        //            JoyaMapperFactoryBean<?> bean = new JoyaMapperFactoryBean<>(aClass);
        //            return bean;
        //        }).setLazyInit(true);
        //        String name = "joya#" + aClass.getSimpleName();
        //        definitionBuilder.addPropertyValue("name", name);
        //        definitionBuilder.addPropertyValue("joyaJdbc", new RuntimeBeanReference("joyaJdbc"));
        //        AbstractBeanDefinition definition = definitionBuilder.getBeanDefinition();
        //
        //        list.put(name, definition);
        //    }
        //
        //}


        ClassPathJoyaMapperScanner scanner = new ClassPathJoyaMapperScanner(registry);
        scanner.setResourceLoader(this.applicationContext);
        scanner.setMapperFactoryBeanClass(JoyaMapperFactoryBean.class);
        scanner.setLazyInitialization(true);
        //if (StringUtils.hasText(lazyInitialization)) {
        //    scanner.setLazyInitialization(Boolean.valueOf(lazyInitialization));
        //}
        //if (StringUtils.hasText(defaultScope)) {
        //    scanner.setDefaultScope(defaultScope);
        //}
        scanner.addIncludeFilter((metadataReader, metadataReaderFactory) -> {
            long count = Arrays.stream(metadataReader.getClassMetadata().getInterfaceNames()).filter(s -> s.toLowerCase().contains("joyamapper")).count();
            return count > 0;
        });
        int count = scanner.scan(StringUtils.tokenizeToStringArray(this.basePackage, ConfigurableApplicationContext.CONFIG_LOCATION_DELIMITERS));

        log.info("Scanning joya mapper was completed,found {} mappers", count);


    }

    private void processPropertyPlaceHolders() {
        Map<String, PropertyResourceConfigurer> prcs = applicationContext.getBeansOfType(PropertyResourceConfigurer.class,
                false, false);

        if (!prcs.isEmpty() && applicationContext instanceof ConfigurableApplicationContext) {
            BeanDefinition mapperScannerBean = ((ConfigurableApplicationContext) applicationContext).getBeanFactory()
                    .getBeanDefinition(beanName);

            // PropertyResourceConfigurer does not expose any methods to explicitly perform
            // property placeholder substitution. Instead, create a BeanFactory that just
            // contains this mapper scanner and post process the factory.
            DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
            factory.registerBeanDefinition(beanName, mapperScannerBean);

            for (PropertyResourceConfigurer prc : prcs.values()) {
                prc.postProcessBeanFactory(factory);
            }

            PropertyValues values = mapperScannerBean.getPropertyValues();
            String value = getPropertyValue("basePackage", values);
            if (value != null) {
                this.basePackage = value;
            }

        }
        this.basePackage = Optional.ofNullable(this.basePackage).map(strings -> getEnvironment().resolvePlaceholders(strings)).orElse(null);
    }


    private String getPropertyValue(String propertyName, PropertyValues values) {
        PropertyValue property = values.getPropertyValue(propertyName);

        if (property == null) {
            return null;
        }

        Object value = property.getValue();

        if (value == null) {
            return null;
        } else if (value instanceof String) {
            return value.toString();
        } else if (value instanceof TypedStringValue) {
            return ((TypedStringValue) value).getValue();
        } else {
            return null;
        }
    }


    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {

        //DefaultListableBeanFactory listableBeanFactory = (DefaultListableBeanFactory) beanFactory;
        //for (Map.Entry<String, AbstractBeanDefinition> entry : list.entrySet()) {
        //    listableBeanFactory.registerBeanDefinition(entry.getKey(), entry.getValue());
        //}

    }

    @Override
    public void afterPropertiesSet() {

    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}
