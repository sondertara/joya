package com.sondertara.joya.core.jdbc.resultset.mapper;

import com.sondertara.joya.utils.SqlDataUtils;
import com.sondertara.joya.exceptions.DbException;
import lombok.extern.slf4j.Slf4j;

import java.sql.ResultSet;
import java.util.Date;

/**
 * 转换只有一个列的数据行
 *
 * @author huangxiaohu
 */
@Slf4j
public class SingleColumnRowMapper<T> implements RowMapper<T> {
    private final Class<T> clazz;

    public SingleColumnRowMapper(Class<T> clazz) {
        this.clazz = clazz;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T map(ResultSet row) {
        try {
            if (Number.class.isAssignableFrom(this.clazz)) {
                return clazz.getDeclaredConstructor(String.class).newInstance(row.getString(1));
            } else if (this.clazz.equals(String.class)) {
                return (T) row.getString(1);
            } else if (Date.class.equals(this.clazz)) {
                return (T) row.getDate(1);
            } else {
                return (T) SqlDataUtils.getResultSetValue(row, 1, clazz);
            }
        } catch (Exception e) {
            log.error("get column error.", e);
            throw new DbException("Get column error.", e);
        }
    }
}
