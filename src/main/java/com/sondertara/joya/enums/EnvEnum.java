package com.sondertara.joya.enums;

/**
 *
 *
 * @author huangxiaohu
 * @date 2021/7/9 15:20
 */
public enum EnvEnum {
    /**
     * 测试，开发，预发，线上
     */
    TEST(0, "test"), DEV(1, "dev"), PREPUB(2, "prepub"), PROD(3, "prod");


    private final int code;
    private final String name;

    EnvEnum(int code, String name) {
        this.code = code;
        this.name = name;
    }

    public int getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public static EnvEnum getEnum(String name) {
        if (null == name) {
            return null;
        }
        final EnvEnum[] enums = EnvEnum.values();
        for (EnvEnum envEnum : enums) {
            if (envEnum.getName().equalsIgnoreCase(name)) {
                return envEnum;
            }
        }
        return null;
    }
}
