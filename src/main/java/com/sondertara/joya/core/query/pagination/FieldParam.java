package com.sondertara.joya.core.query.pagination;

import lombok.Data;

/**
 * 封装查询参数
 *
 * @author huangxiaohu
 * @version 1.0 2020年12月
 */
@Data
public class FieldParam implements java.io.Serializable {

    /**
     * 字段名称
     */
    private String fieldName;
    /**
     * 字段值
     */
    private Object fieldValue;
    /**
     * 查询操作
     */
    private Operator operator;

    /**
     * 字段判断类型
     */
    public enum Operator {
        /**
         * 相等
         */
        EQ,
        /**
         * like
         */
        LIKE,
        /**
         * 左like %kk
         */
        LIKE_L,
        /**
         * 右like kk%
         */
        LIKE_R,
        /**
         * 大于
         */
        GT,
        /**
         * 小于
         */
        LT,
        /**
         * 大于等于
         */
        GTE,
        /**
         * 小于等于
         */
        LTE,
        /**
         * IN范围查询
         */
        IN,
        /**
         * 不等
         */
        NEQ
    }
}
