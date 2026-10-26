package com.sondertara.joya.core.query.pagination;
import java.util.ArrayList;


import com.sondertara.joya.core.query.LinkType;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.List;


/**
 * pagination param
 *
 * @author huangxiaohu
 * @version 1.0 2020年12月
 */
@EqualsAndHashCode(callSuper = true)
public class PageQueryParam extends JoyaQuery implements Serializable, Cloneable {
    /**
     * page size
     * 分页大小
     */
    @Getter
    private Integer pageSize = 10;
    /**
     * page start default is zero
     * 页数 默认从0开始
     */
    @Getter
    private Integer page = 0;

    /**
     * the query type,default is AND
     * 连接类型 默认and
     */
    @Getter
    private LinkType rootOperator = LinkType.AND;
    /**
     * the order param
     * 排序字段
     */
    @Getter
    @Setter
    private List<OrderParam> orderList = new ArrayList<>();

    /**
     * the param of where
     * 搜索参数
     */
    @Getter
    @Setter
    private List<SearchParam> params = new ArrayList<>();

    @Getter
    @Setter
    private Nested nested;

    /**
     * add search param
     *
     * @param filed    fileName
     * @param value    value
     * @param operator operator
     */
    public void addSearchParam(String filed, Object value, FieldParam.Operator operator) {

        this.params.add(new SearchParam(filed, value, operator));
    }

    public boolean removeSearchParam(String fieldName) {
        return this.params.removeIf(searchParam -> searchParam.getFieldName().equalsIgnoreCase(fieldName));
    }

    public SearchParam getSearchParam(String fieldName) {
        return this.params.stream().filter(s -> s.getFieldName().equalsIgnoreCase(fieldName)).findFirst().orElse(null);
    }

    public void pageSize(final Integer pageSize) {
        this.pageSize = pageSize;
    }

    public void page(final Integer page) {
        this.page = page;
    }

    public void linkType(LinkType linkType) {
        this.rootOperator = linkType;
    }

    /**
     * 提取纯分页坐标 {@link PageRequest}。
     */
    public PageRequest toPageRequest() {
        return PageRequest.of(page == null ? 0 : page, pageSize == null ? 10 : pageSize);
    }

    @Override
    public PageQueryParam clone() {
        PageQueryParam copy = new PageQueryParam();
        copy.linkType(getRootOperator());
        copy.page(getPage());
        copy.pageSize(getPageSize());
        if (this.params != null) {
            List<SearchParam> copiedParams = new ArrayList<>(this.params.size());
            for (SearchParam sp : this.params) {
                copiedParams.add(sp == null ? null : new SearchParam(sp.getFieldName(), sp.getFieldValue(), sp.getOperator()));
            }
            copy.setParams(copiedParams);
        }
        if (this.orderList != null) {
            List<OrderParam> copiedOrders = new ArrayList<>(this.orderList.size());
            for (OrderParam op : this.orderList) {
                copiedOrders.add(op == null ? null : new OrderParam(op.getFieldName(), op.getOrderType()));
            }
            copy.setOrderList(copiedOrders);
        }
        if (this.nested != null) {
            Nested copiedNested = new Nested();
            if (this.nested.getAnd() != null) {
                for (SearchParam sp : this.nested.getAnd()) {
                    if (sp != null) {
                        copiedNested.addAndParam(sp.getFieldName(), sp.getFieldValue(), sp.getOperator());
                    }
                }
            }
            if (this.nested.getOr() != null) {
                for (SearchParam sp : this.nested.getOr()) {
                    if (sp != null) {
                        copiedNested.addOrParam(sp.getFieldName(), sp.getFieldValue(), sp.getOperator());
                    }
                }
            }
            copy.setNested(copiedNested);
        }
        return copy;
    }

    @Data
    public static class Nested {
        private List<SearchParam> and = new ArrayList<>();
        private List<SearchParam> or = new ArrayList<>();

        public void addAndParam(String filed, Object value, FieldParam.Operator operator) {
            this.and.add(new SearchParam(filed, value, operator));
        }

        public void addOrParam(String filed, Object value, FieldParam.Operator operator) {
            this.or.add(new SearchParam(filed, value, operator));
        }
    }
}
