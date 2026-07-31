package com.sondertara.joya.core.query;

import com.sondertara.common.struct.Pair;

import java.util.Objects;

/**
 * @author huangxiaohu
 */
public class TablePair extends Pair<String, String> {
    /**
     * 构造
     *
     * @param key   键
     * @param value 值
     */
    public TablePair(String key, String value) {
        super(key, value);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof TablePair)) {
            return false;
        }
        TablePair other = (TablePair) o;
        return getKey().equals(other.getKey()) || getValue().equals(other.getValue());
    }

    @Override
    public int hashCode() {
        if (null == getKey() && null == getValue()) {
            return 0;
        }
        return Objects.hashCode(getKey());
    }
}
