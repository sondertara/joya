package com.sondertara.joya.core.query.criterion;

import com.sondertara.common.function.TaraFunction;
import com.sondertara.joya.cache.AliasThreadLocalCache;

import java.util.StringJoiner;

/**
 * select condition
 *
 * @author SonderTara
 * @date 2021/11/14 17:23
 */
public class SelectCriterion {

  /** select fields */
  private final StringJoiner fields;

  /** construct */
  public SelectCriterion() {
    this.fields = new StringJoiner(", ");
  }

  /**
   * add select field
   *
   * @param fn apply column
   * @param <T> generic table entity
   * @return this
   */
  public <T> SelectCriterion add(TaraFunction<T, ?> fn) {

    String column = AliasThreadLocalCache.getColumn(fn).getColumnAlias();
    fields.add(column);

    return this;
  }

  /**
   * get select sql str
   *
   * @return sql str
   */
  public String getSelectFields() {
    return this.fields.toString();
  }
}
