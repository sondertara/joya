package com.sondertara.joya.core.query.pagination;

import com.sondertara.common.collection.Lists;
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
    private List<OrderParam> orderList = Lists.newArrayList();

    /**
     * the param of where
     * 搜索参数
     */
    @Getter
    @Setter
    private List<SearchParam> params = Lists.newArrayList();

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

    @Override
    public PageQueryParam clone() {
        PageQueryParam pageQueryParam = new PageQueryParam();
        pageQueryParam.setNested(getNested());
        pageQueryParam.linkType(getRootOperator());
        pageQueryParam.setParams(getParams());
        pageQueryParam.setOrderList(getOrderList());
        pageQueryParam.setCondition(getCondition());
        pageQueryParam.setFrom(getFrom());
        pageQueryParam.setAlias(getAlias());
        pageQueryParam.page(getPage());
        pageQueryParam.pageSize(getPageSize());
        return pageQueryParam;
    }

    @Data
    public static class Nested {
        private List<SearchParam> and = Lists.newArrayList();
        private List<SearchParam> or = Lists.newArrayList();

        public void addAndParam(String filed, Object value, FieldParam.Operator operator) {
            this.and.add(new SearchParam(filed, value, operator));
        }

        public void addOrParam(String filed, Object value, FieldParam.Operator operator) {
            this.or.add(new SearchParam(filed, value, operator));
        }
    }
}
