package com.sondertara.joya.core.model;

import com.sondertara.common.util.PatternPool;
import com.sondertara.joya.utils.SqlUtils;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * the data of one table
 *
 * @author huangxiaohu
 * @date 2021/11/15 16:34
 * @since 1.0.0
 */
@Data

public final class TableDTO implements Serializable {
    private String tableName;
    /**
     * entity className
     */
    private String className;
    /**
     * the  columns map
     * key : the fieldName
     * value: the table columnName
     */
    private Map<String, String> fields;

    public static void main(String[] args) throws Exception {
        String trim = SqlUtils.replaceAs("user  AS t0 join on user_ex as t1").trim();
        System.out.println(trim);

        Pattern pattern = PatternPool.get("[A-Za-z0-9_]+( AS )[A-Za-z0-9_]+");
        Matcher matcher = pattern.matcher(trim);

        while (matcher.find()) {

            System.out.println(matcher.group());


        }

    }
}
