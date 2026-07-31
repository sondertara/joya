package com.sondertara.joya.core.query.pagination;

import com.sondertara.common.base.StringUtils;
import com.sondertara.common.collection.ArrayUtils;
import com.sondertara.common.exception.TaraException;
import com.sondertara.common.base.Assert;
import com.sondertara.common.struct.Pair;
import com.sondertara.common.collection.CollectionUtils;
import com.sondertara.joya.core.query.NativeSqlQuery;
import com.sondertara.joya.core.query.NativeSqlQueryBuilder;
import com.sondertara.joya.core.query.NativeSqlQueryConfiguration;
import com.sondertara.joya.core.query.criterion.JoinCriterion;
import com.sondertara.joya.core.query.criterion.WhereCriterion;
import com.sondertara.joya.core.query.parser.ParserTypeEnum;
import com.sondertara.joya.core.query.parser.SqlParser;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.UnaryOperator;


/**
 * 构建分页对象 PageRequest 工具类
 *
 * @author huangxiaohu
 */
public class JoyaPageConvert {

    public static JoyaPageConvert create() {
        return new JoyaPageConvert();
    }

    @SuppressWarnings("rawtypes")
    private final List<Pair<ParserTypeEnum, Class<? extends SqlParser>>> pairList = new ArrayList<>();

    private PageQueryParam queryParam;

    private UnaryOperator<JoinCriterion> joinPart;

    private Class<?>[] targetClass;


    public <R, T extends SqlParser<R>> JoyaPageConvert registerParser(ParserTypeEnum type, Class<T> clazz) {
        this.pairList.add(Pair.of(type, clazz));
        return this;
    }

    public JoyaPageConvert queryParam(PageQueryParam pageQueryParam) {
        this.queryParam = pageQueryParam;
        return this;
    }

    @Deprecated
    public JoyaPageConvert join(UnaryOperator<JoinCriterion> joinPart) {
        this.joinPart = joinPart;
        return this;
    }

    public JoyaPageConvert from(UnaryOperator<JoinCriterion> join) {
        this.joinPart = join;
        return this;
    }

    public JoyaPageConvert from(Class<?>... targetClass) {
        this.targetClass = targetClass;
        return this;
    }

    public NativeSqlQuery build() {
        Assert.notNull(this.queryParam, "PageQuery parameter must be not null");
        UnaryOperator<WhereCriterion> func = getWhereCriterionUnaryOperator();
        NativeSqlQueryConfiguration configuration = new NativeSqlQueryConfiguration();
        configuration.setRootOperator(queryParam.getRootOperator());
        this.pairList.forEach(p -> configuration.registerParser(p.getKey(), p.getValue()));
        NativeSqlQueryBuilder builder = new NativeSqlQueryBuilder(configuration);
        if (null != queryParam.getAlias()) {
            builder.alias(queryParam.getAlias().toArray(new String[0]));
        }
        builder.select();
        if (null != targetClass && targetClass.length > 0) {
            builder.from(targetClass);

        } else if (null != queryParam.getFrom()) {
            builder.from(queryParam.getFrom());
        } else if (null != joinPart) {
            builder.from(joinPart);
        } else {
            throw new IllegalStateException("PageQuery from  part must not be null");
        }
        builder.where(func);
        List<OrderParam> list = queryParam.getOrderList();
        for (OrderParam param : list) {
            String orderBy = StringUtils.format("{} {}", param.getFieldName(), param.getOrderType().toString());
            builder.orderBy(orderBy);
        }
        return builder.build();
    }

    @NonNull
    private UnaryOperator<WhereCriterion> getWhereCriterionUnaryOperator() {
        List<SearchParam> searchParams = this.queryParam.getParams();
        return w -> {
            if (CollectionUtils.isNotEmpty(queryParam.getCondition())) {
                for (String s : queryParam.getCondition()) {
                    w.addCondition(s);
                }
            }
            buildParam(w, searchParams);

            if (queryParam.getNested() != null) {
                List<SearchParam> and = queryParam.getNested().getAnd();
                if (!and.isEmpty()) {
                    w.andNested(whereCriterion -> buildParam(whereCriterion, and));
                }
                List<SearchParam> or = queryParam.getNested().getOr();
                if (!or.isEmpty()) {
                    w.orNested(whereCriterion -> buildParam(whereCriterion, or));

                }
            }
            return w;
        };
    }

    @SuppressWarnings("unchecked")
    private static WhereCriterion buildParam(WhereCriterion w, List<SearchParam> searchParams) {
        for (SearchParam searchParam : searchParams) {
            String fieldName = searchParam.getFieldName();
            Object fieldValue = searchParam.getFieldValue();
            FieldParam.Operator operator = searchParam.getOperator();
            switch (operator) {
                case EQ:
                    w.eq(fieldName, fieldValue);
                    break;
                case NEQ:
                    w.ne(fieldName, fieldValue);
                    break;
                case GT:
                    w.gt(fieldName, fieldValue);
                    break;
                case GTE:
                    w.gte(fieldName, fieldValue);
                    break;
                case LT:
                    w.lt(fieldName, fieldValue);
                    break;
                case LTE:
                    w.lte(fieldName, fieldValue);
                    break;
                case IN:
                    if (ArrayUtils.isArray(fieldValue)) {
                        w.in(fieldName, Arrays.asList((Object[]) fieldValue));
                    } else if (fieldValue instanceof Collection) {
                        w.in(fieldName, (Collection<Object>) fieldValue);
                    } else {
                        throw new TaraException("The value of operator [IN] must be  collection");
                    }
                    break;
                case LIKE:
                    w.contains(fieldName, fieldValue);
                    break;
                case LIKE_L:
                    w.endsWith(fieldName, fieldValue);
                    break;
                case LIKE_R:
                    w.startsWith(fieldName, fieldValue);
                    break;

                default:
            }
        }
        return w;
    }
}
