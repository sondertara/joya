package com.sondertara.joya.core.jdbc;

/**
 * 结果集转换器接口
 *
 * @author huangxiaohu
 */
public interface RecordMapper<T> {
    T map(Record record);
}
