
package com.sondertara.joya.core.jdbc.support.meta;

public enum TableIndexEnum {
    /**
     *
     */
    NORMAL("普通索引"),
    UNIQUE("唯一索引"),
    ;

    private String description;

    TableIndexEnum(String description) {
        this.description = description;
    }

    public boolean isUnique() {
        return UNIQUE == this;
    }

}
