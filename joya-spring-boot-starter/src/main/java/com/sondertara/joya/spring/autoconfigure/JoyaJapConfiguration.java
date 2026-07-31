package com.sondertara.joya.spring.autoconfigure;

import com.sondertara.joya.spring.init.JoyaRepositoryFactoryBean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateProperties;
import org.springframework.boot.autoconfigure.orm.jpa.JpaBaseConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.persistence.EntityManager;

/**
 * @author huangxiaohu
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HibernateProperties.class)
@ConditionalOnClass({EntityManager.class,JpaBaseConfiguration.class})
@Slf4j
public class JoyaJapConfiguration {
    @ConditionalOnMissingBean
    @Bean
    public JoyaRepositoryFactoryBean joyaRepositoryFactoryBean() {
        log.info("Create joyaRepositoryFactoryBean start...");
        return new JoyaRepositoryFactoryBean();
    }
}
