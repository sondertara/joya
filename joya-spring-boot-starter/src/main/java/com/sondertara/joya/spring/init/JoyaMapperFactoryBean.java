package com.sondertara.joya.spring.init;

import com.sondertara.common.bean.container.context.BeanContainer;
import com.sondertara.joya.core.jdbc.dao.JoyaMapper;
import com.sondertara.joya.spring.dao.support.JoyaDaoSupport;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 * JoyaMapperFactoryBean crate proxy for joya mapper
 *
 * <p>
 * It is injected by {@link JoyaMapperConfigurer}
 *
 * @author huangxiaohu
 * @date 2021/11/15 12:09
 * @since 1.0.1
 */
public class JoyaMapperFactoryBean<T extends JoyaMapper> extends JoyaDaoSupport implements FactoryBean<T>, ApplicationContextAware {
    private Class<T> mapperInterface;

    private String name;

    public JoyaMapperFactoryBean() {
    }

    public JoyaMapperFactoryBean(Class<T> mapperInterface) {
        this.mapperInterface = mapperInterface;
    }

    @Override
    public T getObject() {
        return BeanContainer.getInstance().getBean(this.mapperInterface);

    }


    @Override
    public Class<?> getObjectType() {
        return mapperInterface;
    }


    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {

    }

    public Class<T> getMapperInterface() {
        return mapperInterface;
    }

    public void setMapperInterface(Class<T> mapperInterface) {
        this.mapperInterface = mapperInterface;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }


    @Override
    protected void checkDaoConfig() throws IllegalArgumentException {
        if (null == this.joyaSession) {
            throw new IllegalArgumentException("JoyaSession must not be mull");
        }
        BeanContainer.getInstance().registerBean(name, createMapper(this.mapperInterface));

    }
}
