package com.sondertara.joya.core.query;

import com.sondertara.common.base.ObjectUtils;
import com.sondertara.common.struct.Pair;

import java.util.Optional;

/**
 * @author huangxiaohu
 */
public class ColumnPair extends Pair<String, String> {

    private final boolean pureName;

    /**
     * 构造
     *
     * @param key   键
     * @param value 值
     */
    public ColumnPair(String key, String value, boolean pureName) {
        super(key, value);
        this.pureName = pureName;
    }

    public ColumnPair(String key, String value) {
        super(key, value);
        this.pureName = true;
    }

    public String identity() {
        return getKey() + " " + Optional.ofNullable(getValue()).orElse("");
    }

    public boolean isPureName() {
        return pureName;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ColumnPair)) {
            return false;
        }
        ColumnPair other = (ColumnPair) o;
        if (null == getValue() && null == other.getValue()) {
            return getKey().equals(other.getKey());
        } else if (null == getValue()) {
            return false;
        } else if (null == other.getValue()) {
            return false;
        } else {
            return getKey().equals(other.getKey()) && getValue().equals(other.getValue());
        }
    }


    @Override
    public int hashCode() {
        if (null == getKey() && null == getValue()) {
            return 0;
        }
        return ObjectUtils.hashCode(getKey());
    }

    @Override
    public String toString() {
        return super.toString() + ",pureName=" + pureName;
    }
}
