package com.sondertara.joya.domain;

import com.alibaba.fastjson.annotation.JSONField;
import com.sondertara.common.util.LocalDateTimeUtils;
import com.sondertara.common.util.StringUtils;

import javax.persistence.EntityListeners;
import javax.persistence.MappedSuperclass;
import javax.persistence.Transient;
import java.io.Serializable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 类将不是一个完整的实体类，他将不会映射到数据库表，但是他的属性都将映射到其子类的数据库字段中。 用于监听实体类操作的
 *
 * @author SonderTara
 */
@MappedSuperclass
@EntityListeners({BaseEntityListener.class})
public abstract class BaseEntity<T extends Model<T, ID>, ID extends Serializable>
    extends Model<T, ID> {

  /**
   * 请求参数(只是查询需要用到,实体类属性不需要有) 例如: params["ids"]=[10000,10010,10086] 例如:
   * params["createTime"]=["2049-01-01","2049-12-12"]
   */
  @Transient
  @JSONField(serialize = false)
  private Map<String, Object> params;

  public BaseEntity<T, ID> setParams(Map<String, Object> params) {
    this.params = params;
    return this;
  }

  public void putParam(String key, Object value) {
    if (this.params == null) {
      params = new HashMap<>(16);
    }
    this.params.put(key, value);
  }

  public Object getParams(String fieldName) {
    if (params == null) {
      return null;
    }
    Object values = params.get(fieldName);
    if (values == null) {
      return null;
    }
    if (values.getClass().isArray()) {
      Object[] objects = ((Object[]) values);

      if ((StringUtils.endWith(fieldName, "Time", true)
          || StringUtils.endWith(fieldName, "Date", true))) {
        if (objects.length == 2) {
          return Arrays.asList(
              LocalDateTimeUtils.parseToDate((String) objects[0]),
              LocalDateTimeUtils.parseToDate((String) objects[1]));
        }
      }
      return Arrays.asList(objects);
    }

    return values;
  }

  @Override
  @JSONField(serialize = false)
  public boolean isNew() {
    return null == getId();
  }

  /**
   * 返回ID
   *
   * @return id
   */
  @Override
  public abstract ID getId();
}
