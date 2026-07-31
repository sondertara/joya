package com.sondertara.joya.core.proxy;

import com.sondertara.joya.core.jdbc.dao.JoyaMapper;

/**
 * @author huangxiaohu
 */
public interface JoyaMapperProxy {
    /**
     * 创建mapper
     *
     * @param <T>
     * @return
     */
    public <T extends JoyaMapper> T create(Class<T> mapperInterface);

    /**
     *
     * @param entityClass
     * @return
     * @param <T>
     */
    public <T> JoyaMapper<T> build(Class<T> entityClass);
}
