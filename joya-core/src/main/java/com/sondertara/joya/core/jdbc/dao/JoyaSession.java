package com.sondertara.joya.core.jdbc.dao;

import com.sondertara.joya.core.JoyaJdbc;
import com.sondertara.joya.core.jdbc.dao.JoyaMapper;
import com.sondertara.joya.core.jdbc.support.JdbcAccessor;
import com.sondertara.joya.core.proxy.ByteBuddyMapperProxy;
import com.sondertara.joya.core.proxy.JoyaMapperProxy;

import javax.sql.DataSource;

/**
 * @author huangxiaohu
 */
public class JoyaSession extends JdbcAccessor {

    private final JoyaJdbc joyaJdbc;

    private final JoyaMapperProxy joyaMapperProxy;

    public JoyaSession(DataSource dataSource) {
        this.dataSource = dataSource;
        this.joyaJdbc = new JoyaJdbc(dataSource);
        this.joyaMapperProxy = new ByteBuddyMapperProxy(joyaJdbc);
    }

    public <T> JoyaMapper<T> buildMapper(Class<T> entityType) {

        return joyaMapperProxy.build(entityType);
    }

    public <T extends JoyaMapper> T createMapper(Class<T> mapperType) {
        if (!mapperType.isInterface()) {
            throw new UnsupportedOperationException("mapperType " + mapperType.getName() + " is not interface.");
        }
        return this.joyaMapperProxy.create(mapperType);
    }
}
