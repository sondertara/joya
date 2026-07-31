package com.sondertara.joya.core.query;

import java.io.Serializable;
import java.util.List;

/**
 * TODO
 *
 * @author huangxiaohu.1ih
 * @date 2025/4/16 14:44
 */
public interface Page<T> extends Serializable {

    boolean isEmpty();

    int getCurrentPage();


    int getPageSize();



    List<T> getData();

    int getTotalPages();

    /**
     * Returns the total amount of elements.
     *
     * @return the total amount of elements
     */
    long getTotalElements();
}
