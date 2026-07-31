package com.sondertara.joya.spring.autoconfigure;


import com.sondertara.common.bean.container.context.BeanContainer;
import com.sondertara.joya.core.JoyaJdbc;
import com.sondertara.joya.core.constant.JoyaConst;
import com.sondertara.joya.core.jdbc.dao.JoyaSession;
import com.sondertara.joya.spring.ext.JoyaSpringContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;

/**
 * @author huangxiaohu
 */
@Configuration
@ConditionalOnClass(JoyaJdbc.class)
@ConditionalOnSingleCandidate(DataSource.class)
@Import({JoyaSpringContext.class, JoyaJapConfiguration.class})
@AutoConfigureAfter({DataSourceAutoConfiguration.class})
public class JoyaJdbcAutoConfiguration implements BeanFactoryPostProcessor, ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    private final static Logger log = LoggerFactory.getLogger(JoyaJdbcAutoConfiguration.class);

    @ConditionalOnMissingBean
    @Bean
    public JoyaJdbc joyaJdbc(DataSource dataSource) {
        JoyaJdbc joyaJdbc = new JoyaJdbc(dataSource);
        BeanContainer.getInstance().registerBean("joyaJdbc", joyaJdbc);
        log.info("Initialized JoyaJdbc version: " + JoyaConst.VERSION);
        return joyaJdbc;
    }

    @ConditionalOnMissingBean
    @Bean
    public JoyaSession joyaSession(DataSource dataSource) {
        return new JoyaSession(dataSource);
    }


    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        log.info("BeanFactoryPostProcessor#postProcessBeanFactory:{}", beanFactory.getClass());
    }

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        log.info("ApplicationEnvironmentPreparedEvent#onApplicationEvent:{}", event.getClass());
    }
}
