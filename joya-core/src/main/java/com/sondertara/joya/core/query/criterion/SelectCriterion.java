package com.sondertara.joya.core.query.criterion;

import com.sondertara.common.function.IGetter;
import com.sondertara.joya.core.cache.AliasCacheHelper;
import com.sondertara.joya.core.model.ColumnAlias;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * select condition
 *
 * @author SonderTara
 * @date 2021/11/14 17:23
 */
public class SelectCriterion {

    /**
     * select fields
     */
    private final List<ColumnAlias> fields;

    private final AliasCacheHelper aliasCacheHelper;

    /**
     * construct
     */
    public SelectCriterion(AliasCacheHelper aliasCacheHelper) {
        this.aliasCacheHelper = aliasCacheHelper;
        this.fields = new ArrayList<>();
    }

    /**
     * add select field
     *
     * @param fn  apply column
     * @param <T> generic table entity
     * @return this
     */
    public <T> SelectCriterion add(IGetter<T, ?> fn) {
        fields.add(this.aliasCacheHelper.getColumn(fn));
        return this;

    }

    /**
     * get select sql str
     *
     * @return sql str
     */
    public List<ColumnAlias> getSelectFields() {
        return Collections.unmodifiableList(this.fields);
    }
}
