package com.sondertara.joya.core.data;

import com.sondertara.joya.core.model.TableStructDef;

import java.util.List;

/**
 * @author huangxiaohu
 */
public  interface TableResultLoader {
    /**
     * Load the table information and the entity class  of the associated control.
     *
     * @return the list of table
     */
    public abstract List<TableStructDef> load();
}
