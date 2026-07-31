
package com.sondertara.joya.migration.consts;

import com.sondertara.common.base.StringUtils;

/**
 * 处理名称转换的枚举类
 *
 * @author tang
 */
public enum CaseConvertEnum {
  /**
   * 不做任何处理
   */
  NONE(s -> s),
  /**
   * 全大写
   */
  UPPER(String::toUpperCase),
  /**
   * 全小写
   */
  LOWER(String::toLowerCase),
  /**
   * 驼峰转下划线
   */
  SNAKE(StringUtils::toUnderlineCase),
  /**
   * 下划线转驼峰
   */
  CAMEL(StringUtils::toCamelCase);

  private Converter function;

  CaseConvertEnum(Converter function) {
    this.function = function;
  }

  public String convert(String name) {
    return function.convert(name);
  }

  interface Converter {

    String convert(String s);
  }

}
