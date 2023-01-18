package com.sondertara.joya.core.query.pagination;

import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import lombok.Getter;

import java.util.List;

/**
 * 分页结果
 *
 * @author huangxiaohu
 * @date 2021/7/21 12:43
 */
@Getter
public class PageResult<T> extends com.sondertara.common.model.PageResult<T> {


    public PageResult(Integer page, Integer pageSize, Long total, List<T> data) {
        super(data, total, page, pageSize);
    }

    @Override
    public String toString() {
        return JSONObject.toJSONString(this, JSONWriter.Feature.WriteMapNullValue);
    }
}
