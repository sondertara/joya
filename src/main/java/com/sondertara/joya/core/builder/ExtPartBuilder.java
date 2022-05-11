package com.sondertara.joya.core.builder;

import com.sondertara.common.function.TaraFunction;
import com.sondertara.joya.core.query.criterion.WhereCriterion;
import com.sondertara.joya.core.query.pagination.OrderParam;

import java.util.function.UnaryOperator;

/**
 * @author huangxiaohu
 */
public interface ExtPartBuilder extends SetupBuilder {

    /**
     * groupBy
     */
    ExtPartBuilder groupBy(String groupBySegment);

    /**
     * having
     * 使用方式: nativeSql.having(h -> h.eq().ne().in())
     */
    ExtPartBuilder having(UnaryOperator<WhereCriterion> func);

    /**
     * orderBy
     * 排序参数如果是前端传进来,用QueryRequest接收的 ===> nativeSql.orderBy( queryRequest.getOrderBy(表别名) )
     * 手写逻辑指定排序字段 ==> nativeSql.orderBy("su.age asc")
     */
    <T> ExtPartBuilder orderBy(TaraFunction<T, ?> fn, OrderParam.OrderBy orderBy);

    /**
     * orderBy
     * 排序参数如果是前端传进来,用QueryRequest接收的 ===> nativeSql.orderBy( queryRequest.getOrderBy(表别名) )
     * 手写逻辑指定排序字段 ==> nativeSql.orderBy("su.age asc")
     */
    ExtPartBuilder orderBy(String... orderBySegment);
}
