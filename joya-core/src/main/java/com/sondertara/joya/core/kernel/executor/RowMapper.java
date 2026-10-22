package com.sondertara.joya.core.kernel.executor;

import java.sql.ResultSet;

/**
 * @author huangxiaohu
 */
public interface RowMapper<T> {

    /**
     * convert the record
     *
     * @param record the result record
     * @return the target object
     */
    T map(ResultSet record);
}
