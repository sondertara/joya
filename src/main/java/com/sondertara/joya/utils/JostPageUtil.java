package com.sondertara.joya.utils;

import com.sondertara.common.util.StringFormatter;
import com.sondertara.common.util.StringUtils;
import com.sondertara.joya.core.query.pagination.FieldParam;
import com.sondertara.joya.core.query.pagination.OrderParam;
import com.sondertara.joya.core.query.pagination.PageQueryParam;
import com.sondertara.joya.core.query.pagination.SearchParam;
import com.sondertara.joya.core.query.criterion.JoinCriterion;
import com.sondertara.joya.core.query.NativeSqlQuery;
import com.sondertara.joya.core.query.criterion.WhereCriterion;

import java.util.Collections;
import java.util.List;
import java.util.StringJoiner;
import java.util.function.UnaryOperator;


/**
 * 构建分页对象 PageRequest 工具类
 *
 * @author huangxiaohu
 */
public class JostPageUtil {

    /**
     * 带join查询
     *
     * @param queryParam
     * @param joinPart
     * @return
     */
    public static NativeSqlQuery buildNativeQuery(PageQueryParam queryParam, UnaryOperator<JoinCriterion> joinPart) {



        return buildNativeQuery(queryParam, joinPart, new Class<?>[0]);
    }

    /**
     * 联表查询 关联条件在 sqlStr中
     *
     * @param queryParam
     * @param targetClass
     * @return
     */
    public static NativeSqlQuery buildNativeQuery(PageQueryParam queryParam, Class<?>... targetClass) {


        return buildNativeQuery(queryParam, null, targetClass);
    }


    private static NativeSqlQuery buildNativeQuery(PageQueryParam queryParam, UnaryOperator<JoinCriterion> joinFunc, Class<?>... targetClass) {
        List<SearchParam> searchParams = queryParam.getParams();
        UnaryOperator<WhereCriterion> func = w -> {
            if (StringUtils.isNotBlank(queryParam.getSpecificW())) {
                w.specificW(queryParam.getSpecificW());
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
        NativeSqlQuery.NativeSqlQueryBuilder builder = NativeSqlQuery.builder();
        if (null != queryParam.getSelect()) {
            builder.select(queryParam.getSelect());
        } else {
            if (null != queryParam.getSpecificS()) {
                builder.specificS(queryParam.getSpecificS().toArray(new String[0]));
            }
            builder.select();
        }
        if (null != targetClass && targetClass.length > 0) {
            builder.from(targetClass);
        } else {
            builder.from(joinFunc);
        }

        builder.where(func, PageQueryParam.LinkType.OR.equals(queryParam.getLinkType()));
        List<OrderParam> list = queryParam.getOrderList();
        StringJoiner sj = new StringJoiner(", ");
        for (OrderParam param : list) {
            sj.add(StringFormatter.format("{} {}", param.getFieldName(), param.getOrderType().toString()));
        }
        builder.orderBy(sj.toString());
        return builder.build();
    }
}
