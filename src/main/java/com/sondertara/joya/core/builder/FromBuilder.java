package com.sondertara.joya.core.builder;

import com.sondertara.joya.core.query.criterion.JoinCriterion;

import java.util.function.UnaryOperator;

/**
 * @author huangxiaohu
 */
public interface FromBuilder {

  /**
   * sql from part with string
   *
   * @param tableAndJoinTable the sql from segment
   * @return the next builder
   */
  WhereBuilder from(String... tableAndJoinTable);

  /**
   * the table class
   *
   * <p>when use it the table alias will set to default like t0,t1... 根据class获取表，别名为表的顺序t0,t1,t2,t2
   *
   * @param clazz form entity class
   * @return query
   */
  WhereBuilder from(Class<?>... clazz);

  /**
   * the table class with join params
   *
   * <p>the limit is only support join tree table,if there is complicated sql query just use the
   * native sql when use it the table alias will set to default like t0,t1...
   * 根据class获取表，别名为表的顺序t0,t1,t2,t2
   *
   * @param func join param
   * @return query
   */
  WhereBuilder from(UnaryOperator<JoinCriterion> func);
}
