
package com.sondertara.joya.core.query.pagination;

import com.google.common.collect.Lists;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.List;


/**
 * 分页参数
 *
 * @author huangxiaohu
 * @version 1.0 2020年12月
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class PageQueryParam extends JoyaQuery implements Serializable {
    /**
     * 分页大小
     */
    private Integer pageSize = 10;
    /**
     * 页数 默认从0开始
     */
    private Integer page = 0;
    /**
     * 连接类型 默认and
     */
    private LinkType linkType = LinkType.AND;
    /**
     * 排序字段
     */
    private List<OrderParam> orderList = Lists.newArrayList();

    /**
     * 搜索参数
     */
    private List<SearchParam> params = Lists.newArrayList();

    /**
     * group by
     */
    private String groupBy;

    public enum LinkType {
        /**
         *
         */
        AND,
        OR;

    }


}
