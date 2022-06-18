package com.sondertara.joya.core.jdbc.mapper;

import com.sondertara.joya.core.jdbc.Row;
import com.sondertara.joya.core.jdbc.SqlDataHelper;
import lombok.extern.slf4j.Slf4j;
import oracle.sql.TIMESTAMP;

import java.sql.Clob;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将一行数据转换成Map
 *
 * @author huangxiaohu
 */
@Slf4j
public class MapRowMapper implements RowMapper<Map<String, Object>> {
  @Override
  public Map<String, Object> map(Row row) {
    Map<String, Object> map = new LinkedHashMap<>();
    int count = row.getColumnCount();
    for (int i = 1; i <= count; i++) {
      String key = row.getColumnLabel(i);
      Object value = row.getObject(i);
      if (null != value) {
        if (SqlDataHelper.isClob(value.getClass())) {
          value = SqlDataHelper.extractString((Clob) value);
        } else if (value instanceof TIMESTAMP) {
          value = SqlDataHelper.extractDate(value);
        }
      }
      map.put(key, value);
    }
    return map;
  }
}
