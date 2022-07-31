package com.sondertara.joya.enums;

import com.sondertara.common.exception.TaraException;
import lombok.Getter;


/**
 * @author huangxiaohu
 * @date 2021/8/27 22:14
 */
@Getter
public enum DbType {

    /**
     * 数据库类型
     */
    MYSQL("MYSQL", "com.mysql.cj.jdbc.Driver"), ORACLE("ORACLE", "oracle.jdbc.driver.OracleDriver");

    private final String type;
    private final String driverClassName;

    DbType(String type, String driverClassName) {

        this.type = type;
        this.driverClassName = driverClassName;
    }

    public static DbType getDbType(String type) {
        for (DbType dbTypeEnum : DbType.values()) {
            if (dbTypeEnum.getType().equals(type)) {
                return dbTypeEnum;
            }
        }
        throw new TaraException("不支持的数据库类型:{}", type);
    }
}
