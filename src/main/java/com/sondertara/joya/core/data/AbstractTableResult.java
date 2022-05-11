package com.sondertara.joya.core.data;

import com.sondertara.joya.core.model.TableStruct;

import java.util.List;

/**
 * @author huangxiaohu
 */
public abstract class AbstractTableResult {
    /**
     * Load the table information and the entity class  of the associated control.
     *
     * @return the list of table
     */
    public abstract List<TableStruct> load();
}
