package com.sondertara.joya.core.jdbc.resultset.mapper;


import com.sondertara.common.base.StringUtils;
import com.sondertara.joya.utils.LinkedCaseInsensitiveMap;
import com.sondertara.joya.utils.SqlDataUtils;
import lombok.extern.slf4j.Slf4j;

import javax.xml.bind.DataBindingException;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Map;

/**
 * 将一行数据转换成Map
 *
 * @author huangxiaohu
 */
@Slf4j
public class MapRowMapper implements RowMapper<Map<String, Object>> {
    @Override
    public Map<String, Object> map(ResultSet row) {
        Map<String, Object> map = new LinkedCaseInsensitiveMap<>();
        try {
            ResultSetMetaData metaData = row.getMetaData();

            int count = metaData.getColumnCount();
            for (int i = 1; i <= count; i++) {
                String key = SqlDataUtils.lookupColumnName(metaData, i);
                //if the name has alias
                if (key.contains(".")) {
                    key = StringUtils.substringAfter(key, ".");
                }
                Object value = SqlDataUtils.getResultSetValue(row, i);
                map.put(key, value);
            }
        } catch (SQLException e) {
            throw new DataBindingException(e);
        }
        return map;
    }
}
