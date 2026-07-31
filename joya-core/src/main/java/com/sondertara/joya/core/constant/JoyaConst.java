package com.sondertara.joya.core.constant;

import java.util.regex.Pattern;

/**
 * constant
 *
 * @author huangxiaohu
 * @date 2021/11/19 16:01
 * @since 1.0.1
 */
public class JoyaConst {
    public static final String VERSION="1.0.3";
    /**
     * the local cache key
     */
    public static final String JOYA_SQL = "JOYA_SQL";
    /**
     * maximum join part
     */
    public static final int MAX_JOIN_COUNT = 10;
    /**
     * maximum join table
     */
    public static final int MAX_JOIN_TABLE = 10;
    /**
     * two count
     */
    public static final int TWO_QUERY_COUNT = 2;


    public static class Sql {
        public static final String ORACLE_GET_CURRENT_SCHEMA = "SELECT sys_context('userenv','current_schema') FROM DUAL";
        public static final String ORACLE_SET_CURRENT_SCHEMA = "alter session set current_schema = ?";
    }

    private static final Pattern DELIMITER_PATTERN = Pattern.compile("\\s*;\\s*(?=([^']*'[^']*')*[^']*$)");
}
