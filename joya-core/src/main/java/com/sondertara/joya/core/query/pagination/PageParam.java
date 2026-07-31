package com.sondertara.joya.core.query.pagination;


import com.sondertara.common.struct.Pair;

import java.io.Serializable;

/**
 * @author huangxiaohu
 */
public class PageParam implements Serializable {

    private Pair<Integer, Integer> pair;

    public PageParam(Pair<Integer, Integer> pair) {
        this.pair = pair;
    }

    /**
     * 0 based
     *
     * @return the query page index
     */
    public Integer getPage() {
        return pair.getKey();
    }

    public Integer getPageSize() {
        return pair.getValue();
    }

    public Integer startPosition() {
        return getPage() * getPageSize();
    }

    public static PageParam of(int page, int pageSize) {
        return new PageParam(Pair.of(page, pageSize));
    }
}
