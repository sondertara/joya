package com.sondertara.joya.core.query.pagination;

import com.google.common.collect.Lists;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * @author huangxiaohu
 * @date 2021/11/19 9:19
 * @since 1.0.0
 */
@Data
public abstract class JoyaQuery {

  /** 特殊where语句 */
  protected List<String> condition = new ArrayList<>();

  /** 指定from */
  protected String from;
  /** 特殊select 用来指定重名字段的别名 */
  protected List<String> columns;

  protected List<String> getCondition() {
    return condition;
  }

  public void addCondition(String condition) {
    this.condition.add(condition);
  }

  public void column(String... columns) {
    this.columns = Lists.newArrayList(columns);
  }
}
