package com.sondertara.joya.core.proxy;

import com.sondertara.joya.core.JoyaJdbc;
import com.sondertara.joya.core.jdbc.dao.JoyaMapper;

import javax.sql.DataSource;

/**
 * @author huangxiaohu
 */
public class SingletonMapperProxy implements JoyaMapperProxy {

    private static volatile SingletonMapperProxy INSTANCE = null;


    private final JoyaMapperProxy delegate;

    private SingletonMapperProxy(DataSource dataSource) {
        this.delegate = new ByteBuddyMapperProxy(new JoyaJdbc(dataSource));
    }

    public static SingletonMapperProxy getInstance() {
        if (null == INSTANCE) {
            throw new IllegalStateException("SingletonMapperFactory is not initialized,please init by call #init");
        }
        return INSTANCE;

    }

    public static SingletonMapperProxy init(DataSource dataSource) {

        if (null == INSTANCE) {
            synchronized (SingletonMapperProxy.class) {
                if (null == INSTANCE) {
                    INSTANCE = new SingletonMapperProxy(dataSource);
                }
            }
        }

        return INSTANCE;
    }


    @Override
    public <T extends JoyaMapper> T create(Class<T> mapperInterface) {
        return this.delegate.create(mapperInterface);
    }

    @Override
    public <T> JoyaMapper<T> build(Class<T> entityClass) {
        return this.delegate.build(entityClass);
    }
}
