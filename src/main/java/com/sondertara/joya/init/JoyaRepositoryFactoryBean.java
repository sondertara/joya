package com.sondertara.joya.init;

import com.sondertara.common.util.StringFormatter;
import com.sondertara.joya.ext.JostSpringContext;
import com.sondertara.joya.jpa.repository.JoyaRepository;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.AbstractFactoryBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.lang.NonNull;
import org.springframework.util.Assert;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

/**
 * JostRepository Factory
 * <p>
 * It will inject the jostRepository and jostSpringContext {@link JostSpringContext}which is a SpringContext util
 *
 * @author huangxiaohu
 * @date 2021/11/15 12:09
 * @since 1.0.1
 */
public class JoyaRepositoryFactoryBean extends AbstractFactoryBean<JoyaRepository> implements ApplicationContextAware {
    private EntityManager entityManager;


    private static final String CONTEXT_HOLDER = "jostSpringContext";

    private ConfigurableApplicationContext applicationContext;

    @PersistenceContext
    public void setEntityManager(EntityManager entityManager) {
        this.entityManager = entityManager;
    }


    @Override
    public Class<?> getObjectType() {
        return JoyaRepositoryFactoryBean.class;
    }

    @Override
    @NonNull
    protected JoyaRepository createInstance() {
        if (this.entityManager == null) {
            throw new IllegalArgumentException("'entityManager' is required");
        }
        registerJostSpringContext();
        logger.info("Initializing JostTara Sql.");
        return new JoyaRepository(entityManager);
    }


    @Override
    public void setApplicationContext(@NonNull ApplicationContext applicationContext) throws BeansException {
        Assert.isTrue(applicationContext instanceof ConfigurableApplicationContext,
                "ApplicationContext does not implement ConfigurableApplicationContext");
        this.applicationContext = (ConfigurableApplicationContext) applicationContext;
    }

    public void registerJostSpringContext() {
        if (applicationContext.containsBean(CONTEXT_HOLDER)) {
            Object bean = applicationContext.getBean(CONTEXT_HOLDER);
            if (bean.getClass().isAssignableFrom(JostSpringContext.class)) {
                return;
            } else {
                throw new RuntimeException(StringFormatter.format("The bean name of '{}' is duplicated", CONTEXT_HOLDER));
            }
        }
        BeanDefinitionBuilder beanDefinitionBuilder = BeanDefinitionBuilder.genericBeanDefinition(JostSpringContext.class);

        BeanDefinition beanDefinition = beanDefinitionBuilder.getRawBeanDefinition();
        BeanDefinitionRegistry definitionRegistry = (BeanDefinitionRegistry) applicationContext.getBeanFactory();
        definitionRegistry.registerBeanDefinition(CONTEXT_HOLDER, beanDefinition);
        applicationContext.getBean(CONTEXT_HOLDER, JostSpringContext.class);
    }
}
