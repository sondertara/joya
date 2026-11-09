package com.sondertara.joya.core.jdbc.resultset.mapper;

import com.sondertara.joya.core.kernel.executor.RowMapper;
import com.sondertara.joya.exceptions.DbException;
import com.sondertara.joya.utils.LinkedCaseInsensitiveMap;
import com.sondertara.joya.utils.SqlDataUtils;
import com.sondertara.joya.utils.Strings;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Map;

/**
 * 将一行数据转换成大小写不敏感 Map（支持 {@link #forResultSet(ResultSet)} 列布局预解析）。
 *
 * @author huangxiaohu
 */
public class MapRowMapper implements RowMapper<Map<String, Object>> {

    public static final MapRowMapper INSTANCE = new MapRowMapper();

    @Override
    public RowMapper<Map<String, Object>> forResultSet(ResultSet rs) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        int count = metaData.getColumnCount();
        String[] rawKeys = new String[count];
        String[] strippedKeys = new String[count];
        String[] colClassNames = new String[count];
        for (int i = 1; i <= count; i++) {
            String key = SqlDataUtils.lookupColumnName(metaData, i);
            rawKeys[i - 1] = key;
            strippedKeys[i - 1] = (key != null && key.indexOf('.') >= 0) ? Strings.subAfter(key, ".") : null;
            colClassNames[i - 1] = SqlDataUtils.safeGetColumnClassName(metaData, i);
        }
        return row -> mapWithLayout(row, count, rawKeys, strippedKeys, colClassNames);
    }

    @Override
    public Map<String, Object> map(ResultSet row) {
        try {
            return forResultSet(row).map(row);
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    private static Map<String, Object> mapWithLayout(
            ResultSet row,
            int count,
            String[] rawKeys,
            String[] strippedKeys,
            String[] colClassNames
    ) {
        Map<String, Object> map = new LinkedCaseInsensitiveMap<>(count);
        try {
            for (int i = 0; i < count; i++) {
                String key = rawKeys[i];
                String stripped = strippedKeys[i];
                if (stripped != null && !map.containsKey(stripped)) {
                    key = stripped;
                }
                Object value = SqlDataUtils.getResultSetValue(row, i + 1, colClassNames[i]);
                map.put(key, value);
            }
        } catch (SQLException e) {
            throw new DbException(e);
        }
        return map;
    }
}
