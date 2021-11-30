package com.sondertara.joya.ext;

import com.sondertara.common.util.StringUtils;
import com.sondertara.joya.enums.EnvEnum;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.lang.NonNull;
import org.springframework.util.Assert;

import java.util.Map;

/**
 * spring util
 *
 * @author huangxiaohu
 */

public class JostSpringContext implements ApplicationContextAware {

    private static ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(@NonNull ApplicationContext context) throws BeansException {
        applicationContext = context;
    }

    /**
     * register a bean
     *
     * @param name  bean name
     * @param clazz bean class
     * @param args  the args of constructor
     * @param <T>   generic
     * @return managed bean
     */
    @SuppressWarnings({"unchecked"})
    public static <T> T registerBean(String name, Class<T> clazz, Object... args) {
        Assert.isTrue(getApplicationContext() instanceof ConfigurableApplicationContext,
                "ApplicationContext does not implement ConfigurableApplicationContext");

        //convert applicationContext to ConfigurableApplicationContext
        ConfigurableApplicationContext configurableApplicationContext = (ConfigurableApplicationContext) applicationContext;
        if (configurableApplicationContext.containsBean(name)) {
            Object bean = configurableApplicationContext.getBean(name);
            if (bean.getClass().isAssignableFrom(clazz)) {
                return (T) bean;
            } else {
                throw new RuntimeException("BeanName 重复 " + name);
            }
        }
        BeanDefinitionBuilder beanDefinitionBuilder = BeanDefinitionBuilder.genericBeanDefinition(clazz);

        for (Object arg : args) {
            beanDefinitionBuilder.addConstructorArgValue(arg);
        }

        BeanDefinition beanDefinition = beanDefinitionBuilder.getRawBeanDefinition();
        BeanDefinitionRegistry definitionRegistry = (BeanDefinitionRegistry) configurableApplicationContext.getBeanFactory();
        definitionRegistry.registerBeanDefinition(name, beanDefinition);
        return getApplicationContext().getBean(name, clazz);
    }

    public static ApplicationContext getApplicationContext() {
        return applicationContext;
    }

    /**
     * get bean by name
     *
     * @param name the bean name
     * @return managed bean
     */
    public static Object getBean(String name) {
        return getApplicationContext().getBean(name);
    }

    /**
     * get bean by class
     *
     * @param clazz the bean class
     * @param <T>   generic
     * @return managed bean
     */
    public static <T> T getBean(Class<T> clazz) {
        return getApplicationContext().getBean(clazz);
    }

    /**
     * get bean by name and class
     *
     * @param name  the bean name
     * @param clazz the bean class
     * @param <T>   generic
     * @return managed bean
     */
    public static <T> T getBean(String name, Class<T> clazz) {
        return getApplicationContext().getBean(name, clazz);
    }


    /**
     * get all bean  of interface implements
     *
     * @param clazz the interface class
     * @param <T>   generic
     * @return bean map
     */
    public static <T> Map<String, T> getBeans(Class<T> clazz) {
        return getApplicationContext().getBeansOfType(clazz);
    }

    /**
     * remove bean
     *
     * @param beanName name
     */
    public static void removeBean(String beanName) {
        DefaultListableBeanFactory defaultListableBeanFactory = (DefaultListableBeanFactory) getApplicationContext().getAutowireCapableBeanFactory();
        defaultListableBeanFactory.removeBeanDefinition(beanName);
    }


    /**
     * publish event
     *
     * @param event the event
     */
    public static void publishEvent(ApplicationEvent event) {
        if (getApplicationContext() == null) {
            return;
        }
        getApplicationContext().publishEvent(event);
    }

    /**
     * get config
     *
     * @param key key the config key eg: tara.sql.enabled
     * @return the config string value
     */
    public static String getConfig(String key) {

        return getApplicationContext().getEnvironment().getProperty(key);
    }

    /**
     * get config with default value
     * <p>
     * if the config is not exist will return the default value
     *
     * @param key          key
     * @param defaultValue default value
     * @return the config string value
     */
    public static String getConfig(String key, String defaultValue) {

        return getApplicationContext().getEnvironment().getProperty(key, defaultValue);
    }

    /**
     * get Boolean config
     *
     * @param key          key
     * @param defaultValue default value
     * @return the config string value
     */
    public static Boolean getConfig(String key, Boolean defaultValue) {

        return Boolean.valueOf(getApplicationContext().getEnvironment().getProperty(key, String.valueOf(defaultValue)));
    }

    /**
     * get current environment
     *
     * @return the environment
     * @see EnvEnum
     */
    public static EnvEnum getEnv() {
        EnvEnum envEnum = null;
        String env = System.getProperty("env");
        if (StringUtils.isBlank(env)) {
            env = System.getProperty("ENV");
        }
        if (StringUtils.isBlank(env)) {
            env = System.getProperty("spring.profiles.active");
        }
        envEnum = EnvEnum.getEnum(env);
        if (null == envEnum) {
            final String[] profiles = getApplicationContext().getEnvironment().getActiveProfiles();
            for (String profile : profiles) {
                envEnum = EnvEnum.getEnum(profile);
                if (null != envEnum) {
                    break;
                }
            }
        }
        return envEnum;

    }
}