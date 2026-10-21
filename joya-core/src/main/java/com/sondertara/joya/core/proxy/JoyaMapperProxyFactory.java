package com.sondertara.joya.core.proxy;

import com.sondertara.joya.core.JoyaClient;
import com.sondertara.joya.core.jdbc.dao.JoyaMapper;

/**
 * Create proxy for interfaces which implement {@link JoyaMapper}
 *
 * @author huangxiaohu
 */

public class JoyaMapperProxyFactory {

    private final JoyaClient joyaClient;

    public JoyaMapperProxyFactory(JoyaClient joyaClient) {
        this.joyaClient = joyaClient;
    }


    public JoyaMapperProxy create() {

        return new JdkMapperProxy(joyaClient);

    }

}