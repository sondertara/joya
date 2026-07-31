package com.sondertara.joya.core.jdbc.support.meta;


import java.util.EnumSet;

public enum TableType {
    /**
     *
     */
    TABLE("TABLE"),
    VIEW("VIEW"),
    SYSTEM_TABLE("SYSTEM TABLE"),
    GLOBAL_TEMPORARY("GLOBAL TEMPORARY"),
    LOCAL_TEMPORARY("LOCAL TEMPORARY"),
    ALIAS("ALIAS"),
    SYNONYM("SYNONYM");

    private String code;

    TableType(String code) {
        this.code = code;
    }

    public static TableType ofCode(final String code) {
        return EnumSet.allOf(TableType.class).stream().filter(t -> t.code.equals(code)).findFirst().orElse(null);
    }

    public String getCode(){
        return code;
    }
}
