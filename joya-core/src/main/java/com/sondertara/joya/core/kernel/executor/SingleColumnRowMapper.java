package com.sondertara.joya.core.kernel.executor;

import com.sondertara.joya.utils.ConvertUtils;
import com.sondertara.joya.utils.SqlDataUtils;
import com.sondertara.joya.exceptions.DbException;
import lombok.extern.slf4j.Slf4j;

import java.sql.ResultSet;

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
            Object val = SqlDataUtils.getResultSetValue(row, 1, clazz);
            if (val == null) {
                return null;
            }
            if (clazz != null && !clazz.isPrimitive() && !clazz.isInstance(val)) {
                return ConvertUtils.convert(val, clazz);
            }
            return (T) val;
        } catch (Exception e) {
            log.error("get column error.", e);
            throw new DbException("Get column error.", e);
        }
    }
}
