package com.sondertara.joya.core.jdbc.dao.support;

import lombok.Getter;
import lombok.ToString;

/**
 * @author huangxiaohu
 */
@Getter
@ToString
public class PropertyPath {

    private final String owner;
    private final String name;

    public PropertyPath(String name, String owner) {
        this.owner = owner;
        this.name = name;
    }

    public static PropertyPath from(String name, Class<?> clazz) {
        return new PropertyPath(name, clazz.getSimpleName());
    }
}
