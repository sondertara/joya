package com.sondertara.joya.spring.dao.support;

import com.sondertara.joya.core.jdbc.dao.JoyaMapper;
import com.sondertara.joya.core.jdbc.dao.JoyaSession;
import org.springframework.dao.support.DaoSupport;

/**
 * @author huangxiaohu
 */
public abstract class JoyaDaoSupport extends DaoSupport {

    protected JoyaSession joyaSession;


    public JoyaSession getJoyaSession() {
        return joyaSession;
    }

    public void setJoyaSession(JoyaSession joyaSession) {
        this.joyaSession = joyaSession;
    }

    public <T extends JoyaMapper> T createMapper(Class<T> mapperClass) {
        return this.joyaSession.createMapper(mapperClass);
    }
}
