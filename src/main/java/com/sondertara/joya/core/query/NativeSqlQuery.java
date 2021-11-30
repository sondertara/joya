package com.sondertara.joya.core.query;


import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sondertara.common.exception.TaraException;
import com.sondertara.common.function.TaraFunction;
import com.sondertara.common.structure.NodeList;
import com.sondertara.common.util.StringFormatter;
import com.sondertara.common.util.StringUtils;
import com.sondertara.joya.cache.AliasThreadLocalCache;
import com.sondertara.joya.cache.LocalEntityCache;
import com.sondertara.joya.core.constant.JoyaConst;
import com.sondertara.joya.core.model.ColumnAliasDTO;
import com.sondertara.joya.core.model.EntityFieldDTO;
import com.sondertara.joya.core.model.TableAliasDTO;
import com.sondertara.joya.core.model.TableDTO;
import com.sondertara.joya.core.query.criterion.JoinCriterion;
import com.sondertara.joya.core.query.criterion.SelectCriterion;
import com.sondertara.joya.core.query.criterion.WhereCriterion;
import com.sondertara.joya.core.query.pagination.OrderParam;
import com.sondertara.joya.utils.SqlUtils;
import com.sondertara.joya.utils.ThreadLocalUtil;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


/**
 * 条件查询构造器
 *
 * @author huangxiaohu
 */

public class NativeSqlQuery {
    private static final Pattern PARAM_PATTERN = Pattern.compile("\\?\\d{1,2}");

    private static final String SELECT_ALL = "*";



    /**
     * 查询语句
     */
    private String sqlStr;
    /**
     * 占位符对应的参数值
     */
    private List<Object> params;


    public NativeSqlQuery(String sqlStr, List<Object> params) {
        this.sqlStr = sqlStr;
        this.params = params;
    }


    private NativeSqlQuery() {

    }


    public static NativeSqlQueryBuilder builder() {
        ThreadLocalUtil.put(JoyaConst.JOYA_SQL, Maps.newLinkedHashMap());
        return new NativeSqlQueryBuilder();
    }


    public static class NativeSqlQueryBuilder {
        private String select = SELECT_ALL;
        private String from;

        private WhereCriterion where;

        /**
         * 特殊select字段
         */
        private List<String> specificS;

        private String groupBy;
        private WhereCriterion having;
        private StringJoiner orderBy;
        private JoinCriterion joinCriterion;
        /**
         * 占位符计数器 ?1 ?2 ?3
         */
        private int counts = 0;

        /**
         * 占位符对应的参数值
         */
        private List<Object> params;

        NativeSqlQueryBuilder() {
            this.orderBy = new StringJoiner(", ");

        }


        /**
         * select 要查询的列 格式为[t0.user_name] 或者[t0.usr_name AS userName]
         */
        public NativeSqlQuery.NativeSqlQueryBuilder select(String... columns) {

            this.select = String.join(", ", columns);
            return this;
        }

        /**
         * 查询表的全部列,对于联表查询 如果字段名字有重复,只保留前面一个字段,可以配置specificS来指定字段别名
         */
        public NativeSqlQuery.NativeSqlQueryBuilder select() {
            return this;
        }

        /**
         * 特殊select 字段 搭配select(),指定字段的别名
         *
         * @param selectFields 特殊字段
         */
        public NativeSqlQuery.NativeSqlQueryBuilder specificS(String... selectFields) {
            this.specificS = Lists.newArrayList(selectFields);
            return this;
        }

        /**
         * where 字段追加 一般用于特殊sql,如联表查询条件、特殊sql处理
         * 指定字段的别名
         *
         * @param whereFields 特殊字段
         */
        public NativeSqlQuery.NativeSqlQueryBuilder specificW(String... whereFields) {
            for (String whereField : whereFields) {
                this.where.specificW(whereField);
            }
            return this;
        }

        /**
         * 选择一个字段
         *
         * @param f1  column
         * @param <T> entity
         * @return query
         */
        public <T> NativeSqlQuery.NativeSqlQueryBuilder select(TaraFunction<T, ?> f1) {
            this.select = AliasThreadLocalCache.getColumn(f1).getColumnAlias();
            return this;
        }

        /**
         * @param f1   column1
         * @param f2   column2
         * @param <T1> entity1
         * @param <T2> entity2
         * @return query
         */
        public <T1, T2> NativeSqlQuery.NativeSqlQueryBuilder select(TaraFunction<T1, ?> f1, TaraFunction<T2, ?> f2) {
            StringJoiner sj = new StringJoiner(", ");
            this.select = sj.add(AliasThreadLocalCache.getColumn(f1).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f2).getColumnAlias())
                    .toString();
            return this;
        }


        public <T1, T2, T3> NativeSqlQuery.NativeSqlQueryBuilder select(TaraFunction<T1, ?> f1, TaraFunction<T2, ?> f2, TaraFunction<T3, ?> f3) {
            StringJoiner sj = new StringJoiner(", ");
            this.select = sj.add(AliasThreadLocalCache.getColumn(f1).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f2).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f3).getColumnAlias())
                    .toString();
            return this;
        }

        public <T1, T2, T3, T4> NativeSqlQuery.NativeSqlQueryBuilder select(TaraFunction<T1, ?> f1, TaraFunction<T2, ?> f2, TaraFunction<T3, ?> f3,
                                                                            TaraFunction<T4, ?> f4) {
            StringJoiner sj = new StringJoiner(", ");
            this.select = sj.add(AliasThreadLocalCache.getColumn(f1).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f2).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f3).getColumnAlias())
                    .add(AliasThreadLocalCache.getColumn(f4).getColumnAlias())
                    .toString();
            return this;
        }

        public <T1, T2, T3, T4, T5> NativeSqlQuery.NativeSqlQueryBuilder select(TaraFunction<T1, ?> f1, TaraFunction<T2, ?> f2, TaraFunction<T3, ?> f3,
                                                                                TaraFunction<T4, ?> f4, TaraFunction<T5, ?> f5) {
            StringJoiner sj = new StringJoiner(", ");
            this.select = sj.add(AliasThreadLocalCache.getColumn(f1).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f2).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f3).getColumnAlias())
                    .add(AliasThreadLocalCache.getColumn(f4).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f5).getColumnAlias())
                    .toString();
            return this;
        }

        public <T1, T2, T3, T4, T5, T6> NativeSqlQuery.NativeSqlQueryBuilder select(TaraFunction<T1, ?> f1, TaraFunction<T2, ?> f2, TaraFunction<T3, ?> f3,
                                                                                    TaraFunction<T4, ?> f4, TaraFunction<T5, ?> f5, TaraFunction<T6, ?> f6) {
            StringJoiner sj = new StringJoiner(", ");
            this.select = sj.add(AliasThreadLocalCache.getColumn(f1).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f2).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f3).getColumnAlias())
                    .add(AliasThreadLocalCache.getColumn(f4).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f5).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f6).getColumnAlias())
                    .toString();
            return this;
        }

        public <T1, T2, T3, T4, T5, T6, T7> NativeSqlQuery.NativeSqlQueryBuilder select(TaraFunction<T1, ?> f1, TaraFunction<T2, ?> f2, TaraFunction<T3, ?> f3,
                                                                                        TaraFunction<T4, ?> f4, TaraFunction<T5, ?> f5, TaraFunction<T6, ?> f6,
                                                                                        TaraFunction<T7, ?> f7) {
            StringJoiner sj = new StringJoiner(", ");
            this.select = sj.add(AliasThreadLocalCache.getColumn(f1).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f2).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f3).getColumnAlias())
                    .add(AliasThreadLocalCache.getColumn(f4).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f5).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f6).getColumnAlias())
                    .add(AliasThreadLocalCache.getColumn(f7).getColumnAlias())
                    .toString();
            return this;
        }

        public <T1, T2, T3, T4, T5, T6, T7, T8> NativeSqlQuery.NativeSqlQueryBuilder select(TaraFunction<T1, ?> f1, TaraFunction<T2, ?> f2, TaraFunction<T3, ?> f3,
                                                                                            TaraFunction<T4, ?> f4, TaraFunction<T5, ?> f5, TaraFunction<T6, ?> f6,
                                                                                            TaraFunction<T7, ?> f7, TaraFunction<T8, ?> f8) {
            StringJoiner sj = new StringJoiner(", ");
            this.select = sj.add(AliasThreadLocalCache.getColumn(f1).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f2).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f3).getColumnAlias())
                    .add(AliasThreadLocalCache.getColumn(f4).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f5).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f6).getColumnAlias())
                    .add(AliasThreadLocalCache.getColumn(f7).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f8).getColumnAlias())
                    .toString();
            return this;
        }

        public <T1, T2, T3, T4, T5, T6, T7, T8, T9> NativeSqlQuery.NativeSqlQueryBuilder select(TaraFunction<T1, ?> f1, TaraFunction<T2, ?> f2, TaraFunction<T3, ?> f3,
                                                                                                TaraFunction<T4, ?> f4, TaraFunction<T5, ?> f5, TaraFunction<T6, ?> f6,
                                                                                                TaraFunction<T7, ?> f7, TaraFunction<T8, ?> f8, TaraFunction<T9, ?> f9) {
            StringJoiner sj = new StringJoiner(", ");
            this.select = sj.add(AliasThreadLocalCache.getColumn(f1).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f2).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f3).getColumnAlias())
                    .add(AliasThreadLocalCache.getColumn(f4).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f5).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f6).getColumnAlias())
                    .add(AliasThreadLocalCache.getColumn(f7).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f8).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f9).getColumnAlias())
                    .toString();
            return this;
        }

        public <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10> NativeSqlQuery.NativeSqlQueryBuilder select(TaraFunction<T1, ?> f1, TaraFunction<T2, ?> f2, TaraFunction<T3, ?> f3,
                                                                                                     TaraFunction<T4, ?> f4, TaraFunction<T5, ?> f5, TaraFunction<T6, ?> f6,
                                                                                                     TaraFunction<T7, ?> f7, TaraFunction<T8, ?> f8, TaraFunction<T9, ?> f9,
                                                                                                     TaraFunction<T10, ?> f10) {
            StringJoiner sj = new StringJoiner(", ");
            this.select = sj.add(AliasThreadLocalCache.getColumn(f1).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f2).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f3).getColumnAlias())
                    .add(AliasThreadLocalCache.getColumn(f4).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f5).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f6).getColumnAlias())
                    .add(AliasThreadLocalCache.getColumn(f7).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f8).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f9).getColumnAlias())
                    .add(AliasThreadLocalCache.getColumn(f10).getColumnAlias())
                    .toString();
            return this;
        }

        public <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11> NativeSqlQuery.NativeSqlQueryBuilder select(TaraFunction<T1, ?> f1, TaraFunction<T2, ?> f2, TaraFunction<T3, ?> f3,
                                                                                                          TaraFunction<T4, ?> f4, TaraFunction<T5, ?> f5, TaraFunction<T6, ?> f6,
                                                                                                          TaraFunction<T7, ?> f7, TaraFunction<T8, ?> f8, TaraFunction<T9, ?> f9,
                                                                                                          TaraFunction<T10, ?> f10, TaraFunction<T11, ?> f11) {
            StringJoiner sj = new StringJoiner(", ");
            this.select = sj.add(AliasThreadLocalCache.getColumn(f1).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f2).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f3).getColumnAlias())
                    .add(AliasThreadLocalCache.getColumn(f4).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f5).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f6).getColumnAlias())
                    .add(AliasThreadLocalCache.getColumn(f7).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f8).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f9).getColumnAlias())
                    .add(AliasThreadLocalCache.getColumn(f10).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f11).getColumnAlias())
                    .toString();
            return this;
        }

        public <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12> NativeSqlQuery.NativeSqlQueryBuilder select(TaraFunction<T1, ?> f1, TaraFunction<T2, ?> f2, TaraFunction<T3, ?> f3,
                                                                                                               TaraFunction<T4, ?> f4, TaraFunction<T5, ?> f5, TaraFunction<T6, ?> f6,
                                                                                                               TaraFunction<T7, ?> f7, TaraFunction<T8, ?> f8, TaraFunction<T9, ?> f9,
                                                                                                               TaraFunction<T10, ?> f10, TaraFunction<T11, ?> f11, TaraFunction<T12, ?> f12) {
            StringJoiner sj = new StringJoiner(", ");
            this.select = sj.add(AliasThreadLocalCache.getColumn(f1).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f2).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f3).getColumnAlias())
                    .add(AliasThreadLocalCache.getColumn(f4).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f5).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f6).getColumnAlias())
                    .add(AliasThreadLocalCache.getColumn(f7).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f8).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f9).getColumnAlias())
                    .add(AliasThreadLocalCache.getColumn(f10).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f11).getColumnAlias()).add(AliasThreadLocalCache.getColumn(f12).getColumnAlias())
                    .toString();
            return this;
        }

        /**
         * lambda select 同样冲突字段需要调用specificS()
         *
         * @param func select field
         * @return query
         */
        public NativeSqlQuery.NativeSqlQueryBuilder select(UnaryOperator<SelectCriterion> func) {
            SelectCriterion selectFields = func.apply(new SelectCriterion());
            this.select = selectFields.getSelectFields();
            return this;
        }

        /**
         * 字符串格式的from语句
         * from 要查询的表以及关联表
         */
        public NativeSqlQuery.NativeSqlQueryBuilder from(String... tableAndJoinTable) {
            this.from = String.join(", ", tableAndJoinTable);
            return this;
        }

        /**
         * 根据class获取表，别名为表的顺序t0,t1,t2,t2
         *
         * @param clazz form entity class
         * @return query
         */
        public NativeSqlQuery.NativeSqlQueryBuilder from(Class<?>... clazz) {
            for (Class<?> aClass : clazz) {
                //表别名
                AliasThreadLocalCache.generateTableAlias(aClass);
            }
            return this;
        }

        /**
         * 根据class获取表，别名为表的顺序t0,t1,t2,t2
         *
         * @param func join param
         * @return query
         */
        public NativeSqlQuery.NativeSqlQueryBuilder from(UnaryOperator<JoinCriterion> func) {

            this.joinCriterion = func.apply(new JoinCriterion());
            return this;
        }

        /**
         * where
         * 使用方式: nativeSql.where(w -> w.eq().ne().in())
         */
        public NativeSqlQuery.NativeSqlQueryBuilder where(UnaryOperator<WhereCriterion> func) {
            // 条件之间 用 and 连接
            this.where = func.apply(new WhereCriterion(WhereCriterion.Operator.AND));
            return this;
        }


        /**
         * where
         * 使用方式: nativeSql.where(w -> w.eq().ne().in())
         *
         * @param func   条件
         * @param linkOr 是否为or查询 默认为and
         */
        public NativeSqlQuery.NativeSqlQueryBuilder where(UnaryOperator<WhereCriterion> func, boolean linkOr) {
            // 条件之间 用 or 连接
            if (linkOr) {
                this.where = func.apply(new WhereCriterion(WhereCriterion.Operator.OR));
            } else {
                this.where = func.apply(new WhereCriterion(WhereCriterion.Operator.AND));
            }
            return this;
        }


        /**
         * groupBy
         */
        public NativeSqlQuery.NativeSqlQueryBuilder groupBy(String groupBySegment) {
            this.groupBy = groupBySegment;
            return this;
        }

        /**
         * having
         * 使用方式: nativeSql.having(h -> h.eq().ne().in())
         */
        public NativeSqlQuery.NativeSqlQueryBuilder having(UnaryOperator<WhereCriterion> func) {
            // 条件之间 用 and 连接
            this.having = func.apply(new WhereCriterion(WhereCriterion.Operator.AND));
            return this;
        }


        /**
         * orderBy
         * 排序参数如果是前端传进来,用QueryRequest接收的 ===> nativeSql.orderBy( queryRequest.getOrderBy(表别名) )
         * 手写逻辑指定排序字段 ==> nativeSql.orderBy("su.age asc")
         */
        public <T> NativeSqlQuery.NativeSqlQueryBuilder orderBy(TaraFunction<T, ?> fn, OrderParam.OrderBy orderBy) {
            String column = AliasThreadLocalCache.getColumn(fn).getColumnAlias();
            this.orderBy.add(StringFormatter.format("{} {}", column, orderBy.toString()));

            return this;
        }

        /**
         * orderBy
         * 排序参数如果是前端传进来,用QueryRequest接收的 ===> nativeSql.orderBy( queryRequest.getOrderBy(表别名) )
         * 手写逻辑指定排序字段 ==> nativeSql.orderBy("su.age asc")
         */
        public void orderBy(String orderBySegment) {
            if (StringUtils.isNotBlank(orderBySegment)) {
                if (orderBy == null) {
                    //  多次调用此方法,用逗号拼接 ==> su.age asc,so.create_time desc
                    orderBy = new StringJoiner(",");
                }
                this.orderBy.add(orderBySegment);
            }
        }

        private void buildJoin() {
            if (StringUtils.isBlank(this.from) && null != joinCriterion) {
                NodeList<ColumnAliasDTO> nodeList = joinCriterion.getSegments();
                List<String> join = joinCriterion.getJoin();
                if ((nodeList.getSize() / join.size()) != JoyaConst.TWO_QUERY_COUNT) {
                    throw new TaraException("The join part is incorrect!");
                }
                StringJoiner sb = new StringJoiner(" ");
                if (!nodeList.isEmpty()) {

                    ColumnAliasDTO first = nodeList.getFirst();
                    ColumnAliasDTO second = nodeList.get(1);

                    sb.add(first.getTableName()).add("AS").add(first.getTableAlias());
                    sb.add(join.get(0));
                    sb.add(second.getTableName()).add("AS").add(second.getTableAlias());
                    sb.add("ON");
                    sb.add(first.getColumnAlias()).add("=").add(second.getColumnAlias());
                    if (nodeList.getSize() > 2) {
                        ColumnAliasDTO third = nodeList.get(2);
                        ColumnAliasDTO forth = nodeList.get(3);

                        if (forth.getTableName().equals(first.getTableName()) || forth.getTableName().equals(second.getTableName())) {
                            ColumnAliasDTO temp = forth;
                            forth = third;
                            third = temp;
                        }
                        sb.add(join.get(1));
                        sb.add(forth.getTableName()).add("AS").add(forth.getTableAlias());
                        sb.add("NO");
                        sb.add(third.getColumnAlias()).add("=").add(forth.getColumnAlias());
                    }
                    this.from = sb.toString();
                }
            }
        }

        /**
         * 生成完整的sql
         */
        public String toSqlStr() {
            StringJoiner sj = new StringJoiner(" ");

            List<TableAliasDTO> tables = AliasThreadLocalCache.getTables();
            if (SELECT_ALL.equals(select)) {
                Map<String, String> map = null;
                if (null != specificS) {
                    map = specificS.stream().collect(Collectors.toMap(s -> {
                        String s1 = SqlUtils.warpColumn(s);
                        int index = s1.indexOf("AS");
                        return StringUtils.trim(s1.substring(0, index));
                    }, Function.identity(), (k1, k2) -> k1));
                }
                Set<String> columns = new HashSet<>();
                StringJoiner stringJoiner = new StringJoiner(",");
                for (TableAliasDTO table : tables) {
                    Optional<TableDTO> optional = LocalEntityCache.getInstance().get(table.getClassName());
                    Map<String, String> finalMap = map;
                    optional.ifPresent(t -> {
                        Set<EntityFieldDTO> set = t.getFieldNames();
                        for (EntityFieldDTO entity : set) {
                            String columnName = entity.getColumnName();
                            String fieldName = entity.getFieldName();

                            if (null != finalMap) {
                                String s1 = entity.getSame() ?
                                        StringFormatter.format("{}.{}", table.getAliasName(), columnName)
                                        : StringFormatter.format("{}.{} AS {} ", table.getAliasName(), columnName, fieldName);
                                if (finalMap.containsKey(s1)) {
                                    stringJoiner.add(finalMap.get(s1));
                                    continue;
                                }
                            }
                            if (columns.contains(entity.getColumnName())) {
                                continue;
                            }
                            columns.add(entity.getColumnName());
                            String s = entity.getSame() ?
                                    StringFormatter.format("{}.{}", table.getAliasName(), columnName) :
                                    StringFormatter.format("{}.{} AS {}", table.getAliasName(), columnName, fieldName);
                            stringJoiner.add(s);
                        }
                    });
                }
                this.select = stringJoiner.toString();
            }

            sj.add("SELECT " + select);
            //字符串from
            if (StringUtils.isNotBlank(from)) {
                sj.add("FROM " + from);
            } else {
                //class from
                sj.add("FROM");
                StringJoiner stringJoiner = new StringJoiner(", ");
                for (TableAliasDTO table : tables) {
                    stringJoiner.add(StringFormatter.format("{} AS {}", table.getTableName(), table.getAliasName()));
                }
                sj.merge(stringJoiner);
            }
            if (where != null) {
                String wh = where.getSegments().toString();
                if (wh.length() > 0) {
                    sj.add("WHERE " + wh);
                    counts += where.getCounts();
                    params = where.getParams();
                }
            }
            if (StringUtils.isNotBlank(groupBy)) {
                sj.add("GROUP BY " + groupBy);
            }
            if (having != null) {
                String hv = having.getSegments().toString();
                if (hv.length() > 0) {
                    sj.add("HAVING " + moveCount(hv, counts));
                    counts += having.getCounts();
                    if (params == null) {
                        params = having.getParams();
                    } else {
                        params.addAll(having.getParams());
                    }
                }
            }

            if (orderBy != null && StringUtils.isNotBlank(orderBy.toString())) {
                sj.add(new StringBuilder("ORDER BY ").append(orderBy));
            }
            ThreadLocalUtil.clear();
            return sj.toString();
        }

        /**
         * build
         */
        public NativeSqlQuery build() {
            buildJoin();
            String sqlStr = toSqlStr();
            return new NativeSqlQuery(sqlStr, this.params);
        }
    }

    /**
     * 获取格式化sql
     *
     * @return sql
     */
    public String toFormattedSql() {
        return SqlUtils.formatSql(this.sqlStr);

    }

    public String toSql() {
        return this.sqlStr;
    }

    public List getParams() {
        return params;
    }

    /**
     * 占位符数字调整
     */
    private static String moveCount(String value, int counts) {
        StringBuffer sb = new StringBuffer();
        Matcher m = PARAM_PATTERN.matcher(value);
        while (m.find()) {
            m.appendReplacement(sb, "?" + (Integer.parseInt(m.group().substring(1)) + counts));
        }
        m.appendTail(sb);
        return sb.toString();
    }


}
