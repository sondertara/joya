package com.sondertara.joya.core.jdbc.mapper;

import com.sondertara.joya.core.jdbc.Record;

/**
 * 结果集转换器接口
 *
 * @author huangxiaohu
 */
@FunctionalInterface
public interface RecordMapper<T> {

    /**
     * convert the record
     *
     * @param record the result record
     * @return the target object
     */
    T map(Record record);
}
