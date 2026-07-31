package com.sondertara.joya.core.query.pagination;

import com.sondertara.common.struct.Pair;
import lombok.Data;

/**
 * 封装查询参数
 *
 * @author huangxiaohu
 * @version 1.0 2020年12月
 */
@Data
public class FieldParam implements java.io.Serializable {

    public FieldParam() {
        this.operator = Operator.EQ;
    }

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


    public Pair<String, Object> getFieldConnector() {
        StringBuilder sb = new StringBuilder();

        Object value = fieldValue;

        sb.append(" ").append(fieldName).append(" ");
        if (null == this.operator) {
            this.operator = Operator.EQ;
        }

        switch (operator) {
            case LIKE:
                sb.append("like ?");
                value = "%" + value + "%";

                break;
            case LT:
                sb.append("< ?");
                break;

            case GT:
                sb.append("> ?");
                break;
            case EQ:
            default:
                sb.append("= ?");


        }
        return Pair.of(sb.toString(), value);
    }

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
