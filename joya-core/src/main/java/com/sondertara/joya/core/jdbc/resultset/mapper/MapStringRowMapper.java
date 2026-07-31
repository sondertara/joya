package com.sondertara.joya.core.jdbc.resultset.mapper;

import com.sondertara.common.convert.ConvertUtils;
import com.sondertara.common.text.CharUtils;
import com.sondertara.common.base.StringUtils;
import com.sondertara.joya.core.jdbc.support.JdbcType;
import com.sondertara.joya.utils.SqlDataUtils;
import lombok.extern.slf4j.Slf4j;

import javax.xml.bind.DataBindingException;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将一行数据转换成Map
 *
 * @author huangxiaohu
 */
@Slf4j
public class MapStringRowMapper implements RowMapper<Map<String, String>> {
    @Override
    public Map<String, String> map(ResultSet row) {
        Map<String, String> map = new LinkedHashMap<>();
        try {
            ResultSetMetaData metaData = row.getMetaData();
            int count = metaData.getColumnCount();
            for (int i = 1; i <= count; i++) {
                String key = SqlDataUtils.lookupColumnName(metaData, i);
                //if the name has alias
                if (key.contains(StringUtils.DOT)) {
                    key = StringUtils.subAfter(key, CharUtils.DOT, true);
                }
                Object value = SqlDataUtils.getResultSetValue(row, i);
                String str = null;
                if (null != value) {
                    int columnType = metaData.getColumnType(i);
                    JdbcType jdbcType = JdbcType.ofCode(columnType);
                    switch (jdbcType) {
                        case TINYINT:
                        case BIT:
                        case BOOLEAN:
                            if (value instanceof Boolean) {
                                str = ConvertUtils.convert(Integer.class, value).toString();
                            } else {
                                str = value.toString();
                            }
                            break;
                        default:
                            str = ConvertUtils.convert(String.class, value);

                    }
                }
                map.put(key, str);
            }
        } catch (SQLException e) {
            throw new DataBindingException(e);
        }
        return map;
    }
}
