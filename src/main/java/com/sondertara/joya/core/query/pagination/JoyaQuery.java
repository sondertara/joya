package com.sondertara.joya.core.query.pagination;

import com.google.common.collect.Lists;
import lombok.Data;

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
    public String specificW;
    /**
     * 指定select语句
     */
    public String select;
    /**
     * 特殊select 用来指定重名字段的别名
     */
    public List<String> specificS;

    public List<String> getSpecificS() {
        return specificS;
    }

    public void setSpecificS(String... specificS) {
        this.specificS = Lists.newArrayList(specificS);
    }

    public void setSpecificF(String... specificF) {
        this.specificS = Lists.newArrayList(specificF);
    }
}
