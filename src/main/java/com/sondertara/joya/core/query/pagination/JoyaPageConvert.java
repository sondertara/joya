package com.sondertara.joya.core.query.pagination;

import com.sondertara.common.util.CollectionUtils;
import com.sondertara.common.util.StringFormatter;
import com.sondertara.joya.core.query.NativeSqlQuery;
import com.sondertara.joya.core.query.NativeSqlQueryBuilder;
import com.sondertara.joya.core.query.criterion.JoinCriterion;
import com.sondertara.joya.core.query.criterion.WhereCriterion;
import com.sondertara.joya.utils.SqlUtils;

import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;


/**
 * 构建分页对象 PageRequest 工具类
 *
 * @author huangxiaohu
 */
public class JoyaPageConvert {

    /**
     * 带join查询
     *
     * @param queryParam page query
     * @param joinPart   join part
     * @return native sql
     */
    public static NativeSqlQuery buildNativeQuery(PageQueryParam queryParam, UnaryOperator<JoinCriterion> joinPart) {


        return buildNativeQuery(queryParam, joinPart, new Class<?>[0]);
    }

    /**
     * 联表查询 关联条件在 sqlStr中
     *
     * @param queryParam  page query
     * @param targetClass the query table entity class
     * @return native sql
     */
    public static NativeSqlQuery buildNativeQuery(PageQueryParam queryParam, Class<?>... targetClass) {


        return buildNativeQuery(queryParam, null, targetClass);
    }


    private static NativeSqlQuery buildNativeQuery(PageQueryParam queryParam, UnaryOperator<JoinCriterion> joinFunc, Class<?>... targetClass) {
        List<SearchParam> searchParams = queryParam.getParams();
        UnaryOperator<WhereCriterion> func = w -> {
            if (CollectionUtils.isNotEmpty(queryParam.getCondition())) {
                for (String s : queryParam.getCondition()) {
                    w.addCondition(s);
                }
            }
            for (SearchParam searchParam : searchParams) {
                String fieldName = searchParam.getFieldName();
                fieldName = SqlUtils.warpColumn(fieldName);
                Object fieldValue = searchParam.getFieldValue();
                FieldParam.Operator operator = searchParam.getOperator();
                switch (operator) {
                    case EQ:
                        w.eq(fieldName, fieldValue);
                        break;
                    case NEQ:
                        w.ne(fieldName, fieldValue);
                        break;
                    case GT:
                        w.gt(fieldName, fieldValue);
                        break;
                    case GTE:
                        w.gte(fieldName, fieldValue);
                        break;
                    case LT:
                        w.lt(fieldName, fieldValue);
                        break;
                    case LTE:
                        w.lte(fieldName, fieldValue);
                        break;
                    case IN:
                        w.in(fieldName, Collections.singleton(fieldValue));
                        break;
                    case LIKE:
                        w.contains(fieldName, fieldValue);
                        break;
                    case LIKE_L:
                        w.endsWith(fieldName, fieldValue);
                        break;
                    case LIKE_R:
                        w.startsWith(fieldName, fieldValue);
                        break;

                    default:
                }
            }
            return w;
        };
        NativeSqlQueryBuilder builder = new NativeSqlQueryBuilder();
        if (null != queryParam.getColumns()) {
            builder.wrapColumn(queryParam.getColumns().toArray(new String[0]));
        }
        builder.select();
        if (null != targetClass && targetClass.length > 0) {
            builder.from(targetClass);
        } else {
            builder.from(joinFunc);
        }

        builder.where(func, PageQueryParam.LinkType.OR.equals(queryParam.getLinkType()));
        List<OrderParam> list = queryParam.getOrderList();
        for (OrderParam param : list) {
            String orderBy = StringFormatter.format("{} {}", param.getFieldName(), param.getOrderType().toString());
            builder.orderBy(orderBy);
        }
        return builder.build();
    }
}
