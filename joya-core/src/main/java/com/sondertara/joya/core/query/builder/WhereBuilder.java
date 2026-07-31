package com.sondertara.joya.core.query.builder;

import com.sondertara.joya.core.query.criterion.WhereCriterion;

import java.util.function.UnaryOperator;

/**
 * @author huangxiaohu
 */
public interface WhereBuilder extends ExtPartBuilder,LimitBuilder<ExtPartBuilder> {


    /**
     * lambda where
     * <p>
     * example :nativeSql.where(w -> w.eq().ne().in())
     *
     * @param func the function
     * @return the next builder
     */
    ExtPartBuilder where(UnaryOperator<WhereCriterion> func);

    default SetupBuilder limit(int n){
       return limit(0,n);
    }

    SetupBuilder limit(int offset,int n);


}