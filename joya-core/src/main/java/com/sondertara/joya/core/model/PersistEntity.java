package com.sondertara.joya.core.model;

import java.io.Serializable;

/**
 * 通用实体,属性不会映射到数据库
 *
 * @author huangxiaohu
 */
public class PersistEntity implements Serializable {
    private transient boolean isNew = true;

    public boolean isNew() {
        return isNew;
    }

    public void setNew(boolean aNew) {
        isNew = aNew;
    }
}
