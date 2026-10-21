package com.sondertara.joya.core.query.pagination;
import java.util.Arrays;


import lombok.Data;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * @author huangxiaohu
 * @date 2021/11/19 9:19
 * @since 1.0.0
 */
@Data
public abstract class JoyaQuery {

    /**
     * 特殊where语句
     */
    @Getter
    protected List<String> condition = new ArrayList<>();

    /**
     * 指定from
     */
    protected String from;
    /**
     * 特殊select 用来指定重名字段的别名
     */
    protected List<String> alias;

    public void addCondition(String condition) {
        this.condition.add(condition);
    }


    public void alias(String... alias) {
        this.alias = new ArrayList<>(Arrays.asList(alias));
    }
}
