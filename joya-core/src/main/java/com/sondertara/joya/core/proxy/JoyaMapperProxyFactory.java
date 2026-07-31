package com.sondertara.joya.core.proxy;

import com.sondertara.joya.core.JoyaJdbc;
import com.sondertara.joya.core.jdbc.dao.JoyaMapper;

/**
 * Create proxy for interfaces which implement {@link JoyaMapper}
 *
 * @author huangxiaohu
 */

public class JoyaMapperProxyFactory {

    private final JoyaJdbc joyaJdbc;

    public JoyaMapperProxyFactory(JoyaJdbc joyaJdbc) {
        this.joyaJdbc = joyaJdbc;
    }


    public JoyaMapperProxy create() {

        return new ByteBuddyMapperProxy(joyaJdbc);

    }


}
