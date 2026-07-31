package com.sondertara.joya.core.query;

import com.sondertara.common.collection.CollectionUtils;
import com.sondertara.common.collection.Maps;
import com.sondertara.common.collection.Sets;
import com.sondertara.common.function.IGetter;
import com.sondertara.common.reflect.ReflectUtils;
import com.sondertara.common.regex.RegexUtils;
import com.sondertara.common.struct.Pair;
import com.sondertara.common.text.CharUtils;
import com.sondertara.common.base.StringUtils;
import com.sondertara.joya.core.cache.AliasCacheHelper;
import com.sondertara.joya.core.model.ColumnAlias;
import com.sondertara.joya.core.model.EntityTableMetaDefinition;
import com.sondertara.joya.core.model.JoinPart;
import com.sondertara.joya.core.model.OrderByAlias;
import com.sondertara.joya.core.model.TableAlias;
import com.sondertara.joya.core.query.builder.ExtPartBuilder;
import com.sondertara.joya.core.query.builder.FromBuilder;
import com.sondertara.joya.core.query.builder.SelectBuilder;
import com.sondertara.joya.core.query.builder.SetupBuilder;
import com.sondertara.joya.core.query.builder.WhereBuilder;
import com.sondertara.joya.core.query.criterion.JoinCriterion;
import com.sondertara.joya.core.query.criterion.SelectCriterion;
import com.sondertara.joya.core.query.criterion.WhereCriterion;
import com.sondertara.joya.core.query.pagination.OrderParam;
import com.sondertara.joya.core.query.parser.FromPartParser;
import com.sondertara.joya.core.query.parser.GroupByPartParser;
import com.sondertara.joya.core.query.parser.HavingPartParser;
import com.sondertara.joya.core.query.parser.LimitPartParser;
import com.sondertara.joya.core.query.parser.OrderByPartParser;
import com.sondertara.joya.core.query.parser.ParserTypeEnum;
import com.sondertara.joya.core.query.parser.SegmentParser;
import com.sondertara.joya.core.query.parser.SelectPartParser;
import com.sondertara.joya.core.query.parser.SqlPartParser;
import com.sondertara.joya.core.query.parser.WherePartParser;
import com.sondertara.joya.exceptions.SqlParseException;
import com.sondertara.joya.utils.SqlFormatUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author huangxiaohu
 */
public class NativeSqlQueryBuilder implements SelectBuilder, FromBuilder, WhereBuilder {
    private static final Pattern PARAM_PATTERN = Pattern.compile("\\?\\d{1,2}");

    private static final Pattern COMPLEX_SQL_STR = Pattern.compile("[@#$%&\\[\\]^*~()\\-+=]");


    private static final Map<ParserTypeEnum, Class<? extends SqlPartParser<?>>> DEFAULT_PART_PARSER_MAP = Maps.newLinkedHashMap();

    static {
        DEFAULT_PART_PARSER_MAP.put(ParserTypeEnum.SELECT, SelectPartParser.class);
        DEFAULT_PART_PARSER_MAP.put(ParserTypeEnum.FROM, FromPartParser.class);
        DEFAULT_PART_PARSER_MAP.put(ParserTypeEnum.WHERE, WherePartParser.class);
        DEFAULT_PART_PARSER_MAP.put(ParserTypeEnum.ORDER_BY, OrderByPartParser.class);
        DEFAULT_PART_PARSER_MAP.put(ParserTypeEnum.GROUP_BY, GroupByPartParser.class);
        DEFAULT_PART_PARSER_MAP.put(ParserTypeEnum.HAVING, HavingPartParser.class);
        DEFAULT_PART_PARSER_MAP.put(ParserTypeEnum.LIMIT, LimitPartParser.class);
    }


    private final LinkType rootOpt;
    private final Set<ColumnPair> selects = Sets.newLinkedHashSet();
    private Set<TableAlias> allTableAlias = Sets.newLinkedHashSet();

    private WhereCriterion whereCriterion;

    /**
     * 特殊select字段
     */
    private final Set<ColumnPair> aliasList;


    private final Set<ColumnAlias> groupBySegments = new LinkedHashSet<>();
    private String havingString;
    private final Set<OrderByAlias> orderBySegments = new LinkedHashSet<>();
    private JoinCriterion join;
    /**
     * 占位符计数器 ?1 ?2 ?3
     */
    private final int counts = 0;

    private Pair<Integer, Integer> limit;


    private final AliasCacheHelper aliasCacheHelper;

    private final NativeSqlQueryConfiguration configuration;

    public NativeSqlQueryBuilder() {
        this(new NativeSqlQueryConfiguration());
    }

    public NativeSqlQueryBuilder(NativeSqlQueryConfiguration configuration) {
        this.configuration = configuration;
        this.rootOpt = configuration.getRootOperator();
        this.aliasList = Sets.newLinkedHashSet();
        this.aliasCacheHelper = new AliasCacheHelper(configuration);
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

    @Override
    public FromBuilder customized(String customizedSqlQuery) {
        if (StringUtils.isNotBlank(customizedSqlQuery)) {
            this.selects.add(new ColumnPair(customizedSqlQuery, null, false));

        }
        return this;
    }

    /**
     * select 要查询的列 格式为[t0.user_name] 或者[t0.usr_name AS userName]
     */
    @Override
    public FromBuilder select(String... columns) {
        for (String column : columns) {
            //除了数字.,_字母还有其他字符直接取
            if (RegexUtils.contains(COMPLEX_SQL_STR, column)) {
                String s = SqlFormatUtils.replaceAs(column);
                this.selects.add(new ColumnPair(s, null, false));
            } else {
                for (String string : column.split(StringUtils.COMMA)) {
                    String s = SqlFormatUtils.replaceAs(string);
                    this.selects.add(SqlFormatUtils.warpColumn(s));
                }
            }
        }
        return this;
    }

    /**
     * 查询表的全部列,对于联表查询 如果字段名字有重复,只保留前面一个字段,可以配置 {@link NativeSqlQueryBuilder#alias(String...)} ()}alias来指定字段别名
     */
    @Override
    public FromBuilder select() {
        return this;
    }

    /**
     * 特殊select 字段 搭配select(),指定字段的别名
     *
     * @param selectFields 特殊字段
     */
    @Override
    public SelectBuilder alias(String... selectFields) {
        for (String selectField : selectFields) {
            for (String string : selectField.split(StringUtils.COMMA)) {
                String s = SqlFormatUtils.replaceAs(string.trim());
                ColumnPair columnPair = SqlFormatUtils.warpColumn(s);
                this.aliasList.add(columnPair);
            }
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
    @Override
    public <T> FromBuilder select(IGetter<T, ?> f1) {
        this.selects.add(aliasCacheHelper.getColumn(f1).queryPair());
        return this;
    }

    /**
     * @param f1   column1
     * @param f2   column2
     * @param <T1> entity1
     * @param <T2> entity2
     * @return query
     */
    @Override
    public <T1, T2> FromBuilder select(IGetter<T1, ?> f1, IGetter<T2, ?> f2) {
        this.selects.add(aliasCacheHelper.getColumn(f1).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f2).queryPair());
        return this;
    }

    @Override
    public <T1, T2, T3> FromBuilder select(IGetter<T1, ?> f1, IGetter<T2, ?> f2, IGetter<T3, ?> f3) {


        this.selects.add(aliasCacheHelper.getColumn(f1).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f2).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f3).queryPair());
        return this;
    }

    @Override
    public <T1, T2, T3, T4> FromBuilder select(IGetter<T1, ?> f1, IGetter<T2, ?> f2, IGetter<T3, ?> f3, IGetter<T4, ?> f4) {
        this.selects.add(aliasCacheHelper.getColumn(f1).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f2).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f3).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f4).queryPair());

        return this;
    }

    @Override
    public <T1, T2, T3, T4, T5> FromBuilder select(IGetter<T1, ?> f1, IGetter<T2, ?> f2, IGetter<T3, ?> f3, IGetter<T4, ?> f4, IGetter<T5, ?> f5) {
        this.selects.add(aliasCacheHelper.getColumn(f1).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f2).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f3).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f4).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f5).queryPair());
        return this;
    }

    @Override
    public <T1, T2, T3, T4, T5, T6> FromBuilder select(IGetter<T1, ?> f1, IGetter<T2, ?> f2, IGetter<T3, ?> f3, IGetter<T4, ?> f4, IGetter<T5, ?> f5, IGetter<T6, ?> f6) {
        this.selects.add(aliasCacheHelper.getColumn(f1).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f2).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f3).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f4).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f5).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f6).queryPair());
        return this;
    }

    @Override
    public <T1, T2, T3, T4, T5, T6, T7> FromBuilder select(IGetter<T1, ?> f1, IGetter<T2, ?> f2, IGetter<T3, ?> f3, IGetter<T4, ?> f4, IGetter<T5, ?> f5, IGetter<T6, ?> f6, IGetter<T7, ?> f7) {
        this.selects.add(aliasCacheHelper.getColumn(f1).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f2).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f3).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f4).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f5).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f6).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f7).queryPair());
        return this;
    }

    @Override
    public <T1, T2, T3, T4, T5, T6, T7, T8> FromBuilder select(IGetter<T1, ?> f1, IGetter<T2, ?> f2, IGetter<T3, ?> f3, IGetter<T4, ?> f4, IGetter<T5, ?> f5, IGetter<T6, ?> f6, IGetter<T7, ?> f7, IGetter<T8, ?> f8) {
        this.selects.add(aliasCacheHelper.getColumn(f1).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f2).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f3).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f4).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f5).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f6).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f7).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f8).queryPair());
        return this;
    }

    @Override
    public <T1, T2, T3, T4, T5, T6, T7, T8, T9> FromBuilder select(IGetter<T1, ?> f1, IGetter<T2, ?> f2, IGetter<T3, ?> f3, IGetter<T4, ?> f4, IGetter<T5, ?> f5, IGetter<T6, ?> f6, IGetter<T7, ?> f7, IGetter<T8, ?> f8, IGetter<T9, ?> f9) {

        this.selects.add(aliasCacheHelper.getColumn(f1).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f2).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f3).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f4).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f5).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f6).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f7).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f8).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f9).queryPair());
        return this;
    }

    @Override
    public <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10> FromBuilder select(IGetter<T1, ?> f1, IGetter<T2, ?> f2, IGetter<T3, ?> f3, IGetter<T4, ?> f4, IGetter<T5, ?> f5, IGetter<T6, ?> f6, IGetter<T7, ?> f7, IGetter<T8, ?> f8, IGetter<T9, ?> f9, IGetter<T10, ?> f10) {

        this.selects.add(aliasCacheHelper.getColumn(f1).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f2).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f3).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f4).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f5).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f6).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f7).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f8).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f9).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f10).queryPair());
        return this;
    }

    @Override
    public <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11> FromBuilder select(IGetter<T1, ?> f1, IGetter<T2, ?> f2, IGetter<T3, ?> f3, IGetter<T4, ?> f4, IGetter<T5, ?> f5, IGetter<T6, ?> f6, IGetter<T7, ?> f7, IGetter<T8, ?> f8, IGetter<T9, ?> f9, IGetter<T10, ?> f10, IGetter<T11, ?> f11) {
        this.selects.add(aliasCacheHelper.getColumn(f1).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f2).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f3).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f4).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f5).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f6).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f7).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f8).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f9).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f10).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f11).queryPair());
        return this;
    }

    @Override
    public <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12> FromBuilder select(IGetter<T1, ?> f1, IGetter<T2, ?> f2, IGetter<T3, ?> f3, IGetter<T4, ?> f4, IGetter<T5, ?> f5, IGetter<T6, ?> f6, IGetter<T7, ?> f7, IGetter<T8, ?> f8, IGetter<T9, ?> f9, IGetter<T10, ?> f10, IGetter<T11, ?> f11, IGetter<T12, ?> f12) {
        this.selects.add(aliasCacheHelper.getColumn(f1).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f2).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f3).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f4).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f5).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f6).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f7).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f8).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f9).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f10).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f11).queryPair());
        this.selects.add(aliasCacheHelper.getColumn(f12).queryPair());
        return this;
    }

    @Override
    public FromBuilder select(Class<?>... clazz) {
        for (Class<?> aClass : clazz) {
            TableAlias tableAlias = aliasCacheHelper.generateTableAlias(aClass);
            String aliasName = tableAlias.getAliasName();
            EntityTableMetaDefinition metaDefinition = this.aliasCacheHelper.getEntityMetaDefinition(aClass).get();
            for (String columnName : metaDefinition.getColumnNames()) {
                this.selects.add(new ColumnPair(aliasName + "." + columnName, metaDefinition.findFieldNameByColumn(columnName)));
            }
        }
        return this;
    }

    /**
     * lambda select 同样冲突字段需要调用specificS()
     *
     * @param func select field
     * @return query
     */
    @Override
    public FromBuilder select(UnaryOperator<SelectCriterion> func) {
        SelectCriterion selectFields = func.apply(new SelectCriterion(aliasCacheHelper));
        List<ColumnPair> list = selectFields.getSelectFields().stream().map(ColumnAlias::queryPair).collect(Collectors.toList());
        this.selects.addAll(list);
        return this;
    }

    /**
     * 字符串格式的from语句
     * from 要查询的表以及关联表
     */
    @Override
    public WhereBuilder from(String... tables) {
        //1.单表
        for (String table : tables) {
            String[] split = table.split(",");
            for (String s1 : split) {
                String tableName = s1.trim();
                if (StringUtils.containsIgnoreCase(tableName, "join")) {
                    throw new SqlParseException("Table join is not allowed here,please use [from(Class<?>... clazz)] or [from(UnaryOperator<JoinCriterion> func)].");
                }
                //有别名的情况
                if (s1.contains(" ") || StringUtils.containsIgnoreCase(s1, "AS")) {
                    if (tableName.contains(" ") || StringUtils.containsIgnoreCase(tableName, "AS")) {
                        String replace = SqlFormatUtils.replaceAs(tableName).replace("AS ", "");
                        aliasCacheHelper.generateTableAlias(replace);
                    }
                } else {
                    aliasCacheHelper.generateTableAlias(tableName);
                }
            }
        }
        return this;
    }

    /**
     * 根据class获取表，别名为表的顺序t0,t1,t2,t2
     *
     * @param clazz form entity class
     * @return query
     */
    @Override
    public WhereBuilder from(Class<?>... clazz) {
        for (Class<?> aClass : clazz) {
            //表别名
            aliasCacheHelper.generateTableAlias(aClass);
        }
        return this;
    }

    /**
     * 根据class获取表，别名为表的顺序t0,t1,t2,t2
     *
     * @param join join param
     * @return query
     */
    @Override
    public WhereBuilder from(UnaryOperator<JoinCriterion> join) {

        this.join = join.apply(new JoinCriterion(aliasCacheHelper));

        return this;
    }

    /**
     * where
     * 使用方式: nativeSql.where(w -> w.eq().ne().in())
     */
    @Override
    public ExtPartBuilder where(UnaryOperator<WhereCriterion> func) {
        // 条件之间 用 and 连接
        this.whereCriterion = func.apply(new WhereCriterion(rootOpt, aliasCacheHelper));
        return this;
    }


    @Override
    public <T> ExtPartBuilder groupBy(IGetter<T, ?> fn) {
        ColumnAlias columnAlias = aliasCacheHelper.getColumn(fn);
        this.groupBySegments.add(columnAlias);
        return this;
    }

    /**
     * groupBy
     */
    @Override
    public ExtPartBuilder groupBy(String... groupBySegment) {
        if (null != groupBySegment) {
            for (String segment : groupBySegment) {
                for (String string : segment.split(StringUtils.COMMA)) {
                    ColumnAlias columnAlias = aliasCacheHelper.resolveColumn(string.trim());
                    this.groupBySegments.add(columnAlias);
                }
            }
        }
        return this;
    }

    /**
     * having
     * 使用方式: nativeSql.having(h -> h.eq().ne().in())
     */
    @Override
    public ExtPartBuilder having(String query) {
        this.havingString = query;
        return this;
    }

    /**
     * orderBy
     * 排序参数如果是前端传进来,用QueryRequest接收的 ===> nativeSql.orderBy( queryRequest.getOrderBy(表别名) )
     * 手写逻辑指定排序字段 ==> nativeSql.orderBy("su.age asc")
     */
    @Override
    public <T> ExtPartBuilder orderBy(IGetter<T, ?> fn, OrderParam.OrderBy orderBy) {
        ColumnAlias columnAlias = aliasCacheHelper.getColumn(fn);
        OrderByAlias orderByAlias = new OrderByAlias(orderBy);
        orderByAlias.setTableName(columnAlias.getTableName());
        orderByAlias.setColumnName(columnAlias.getColumnName());
        orderByAlias.setTableAlias(columnAlias.getTableAlias());
        this.orderBySegments.add(orderByAlias);

        return this;
    }

    /**
     * orderBy
     * 排序参数如果是前端传进来,用QueryRequest接收的 ===> nativeSql.orderBy( queryRequest.getOrderBy(表别名) )
     * 手写逻辑指定排序字段 ==> nativeSql.orderBy("su.age asc")
     */
    @Override
    public ExtPartBuilder orderBy(String... orderBySegment) {
        if (null != orderBySegment) {
            for (String segment : orderBySegment) {
                if (StringUtils.isBlank(segment.trim())) {
                    continue;
                }
                for (String string : segment.split(StringUtils.COMMA)) {
                    String[] split = string.trim().split("(\\s+)");
                    String column = split[0].trim();
                    String orderByType = split[1];
                    ColumnAlias columnAlias = aliasCacheHelper.resolveColumn(column);
                    OrderParam.OrderBy orderBy = OrderParam.OrderBy.resolve(orderByType.trim());
                    OrderByAlias orderByAlias = new OrderByAlias(orderBy);
                    orderByAlias.setTableName(columnAlias.getTableName());
                    orderByAlias.setColumnName(columnAlias.getColumnName());
                    orderByAlias.setTableAlias(columnAlias.getTableAlias());
                    this.orderBySegments.add(orderByAlias);
                }
            }
        }
        return this;
    }

    private LinkedHashSet<ColumnPair> buildSelect() {
        LinkedHashSet<ColumnPair> set = new LinkedHashSet<>();
        //存在别名就添加
        Set<String> aliasNames = new HashSet<>();
        for (ColumnPair columnPair : this.aliasList) {
            setSelectColumn(set, aliasNames, columnPair);
        }
        //没有选择字段,获取所有column,只包含列名，不包含对象映射
        if (this.selects.isEmpty()) {
            EntityTableMetaDefinition entityTableMetaDefinition = null;
            for (TableAlias alias : this.allTableAlias) {
                if (null != alias.getEntityClass()) {
                    entityTableMetaDefinition = this.aliasCacheHelper.getEntityMetaDefinition(alias.getEntityClass()).orElse(null);
                }
                if (null == entityTableMetaDefinition) {
                    ColumnPair columnPair = new ColumnPair(alias.getAliasName() + ".*", null);
                    set.add(columnPair);
                } else {
                    LinkedHashSet<ColumnAlias> allColumns = aliasCacheHelper.getAllColumns(entityTableMetaDefinition.getEntityClass());
                    for (ColumnAlias columnAlias : allColumns) {
                        //只包含列名，不包含对象映射
                        ColumnPair columnPair = new ColumnPair(columnAlias.identity(), null);
                        if (!aliasNames.contains(columnAlias.getColumnName())) {
                            set.add(columnPair);
                            aliasNames.add(columnAlias.getColumnName());
                        }
                    }

                }
                entityTableMetaDefinition = null;
            }
        } else {
            //选择了字段，则使用带别名映射的 ColumnPair
            for (ColumnPair pair : this.selects) {
                setSelectColumn(set, aliasNames, pair);
            }
        }
        return set;
    }

    private void setSelectColumn(LinkedHashSet<ColumnPair> set, Set<String> aliasNames, ColumnPair pair) {
        String aliasName = pair.getValue();
        String pairKey = pair.getKey();
        if (pair.isPureName()) {
            String columnName = this.aliasCacheHelper.getColumnName(pairKey);
            if (null == aliasName) {
                aliasName = StringUtils.subAfter(pairKey, CharUtils.DOT, true);

            }
            if (aliasNames.contains(aliasName)) {
                return;
            }
            set.add(new ColumnPair(columnName, aliasName));
            aliasNames.add(aliasName);
            return;

        }
        if (aliasNames.contains(pairKey)) {
            return;
        }
        set.add(pair);
        aliasNames.add(pairKey);
    }

    private void buildOrderBy() {
        if (CollectionUtils.isEmpty(this.orderBySegments)) {
            return;
        }
        for (OrderByAlias segment : this.orderBySegments) {
            ColumnAlias columnAlias = this.aliasCacheHelper.resolveColumn(segment.identity());
            segment.setColumnName(columnAlias.getColumnName());
        }
    }

    private void buildGroupBy() {
        if (CollectionUtils.isEmpty(this.groupBySegments)) {
            return;
        }
        for (ColumnAlias segment : this.groupBySegments) {
            ColumnAlias columnAlias = this.aliasCacheHelper.resolveColumn(segment.identity());
            segment.setColumnName(columnAlias.getColumnName());
        }
    }

    private Map<ParserTypeEnum, Class<? extends SqlPartParser>> prepareSqlPartParser() {
        final Map<ParserTypeEnum, Class<? extends SqlPartParser>> partParserMap = new HashMap<>();
        DEFAULT_PART_PARSER_MAP.forEach((k, v) -> {
            if (!partParserMap.containsKey(k)) {
                partParserMap.put(k, v);
            }
        });
        if (CollectionUtils.isNotEmpty(configuration.getCustomSqlPartParsers())) {
            partParserMap.putAll(configuration.getCustomSqlPartParsers());
        }

        return partParserMap;
    }

    /**
     * build
     */
    @Override
    public NativeSqlQuery build() {

        Map<ParserTypeEnum, Class<? extends SqlPartParser>> partParsers = prepareSqlPartParser();
        LinkedList<JoinPart> joinParts = Optional.ofNullable(join).map(JoinCriterion::getJoinSegments).orElse(new LinkedList<>());
        this.allTableAlias = aliasCacheHelper.getTables();
        LinkedHashSet<ColumnPair> set = buildSelect();
        buildGroupBy();
        buildOrderBy();

        Map<ParserTypeEnum, Pair<SqlPartParser<?>, Class<? extends SegmentParser>>> parsers = Maps.newLinkedHashMap();
        partParsers.forEach((k, v) -> {
            SqlPartParser<?> sqlPartParser = null;
            switch (k) {
                case SELECT:
                    sqlPartParser = ReflectUtils.newInstance(v, this.allTableAlias, set);
                    break;
                case FROM:
                    sqlPartParser = ReflectUtils.newInstance(v, this.allTableAlias, joinParts);
                    break;
                case WHERE:
                    if (null != this.whereCriterion && CollectionUtils.isNotEmpty(this.whereCriterion.getConditions())) {
                        sqlPartParser = ReflectUtils.newInstance(v, this.whereCriterion.getConditions(), rootOpt);
                    }
                    break;
                case ORDER_BY:
                    if (!this.orderBySegments.isEmpty()) {
                        sqlPartParser = ReflectUtils.newInstance(v, this.orderBySegments);
                    }
                    break;
                case GROUP_BY:
                    if (!this.groupBySegments.isEmpty()) {
                        sqlPartParser = ReflectUtils.newInstance(v, this.groupBySegments);
                    }
                    break;
                case HAVING:
                    if (StringUtils.isNotBlank(this.havingString)) {
                        sqlPartParser = ReflectUtils.newInstance(v, this.havingString);
                    }
                    break;
                case LIMIT:
                    if (null != limit) {
                        sqlPartParser = ReflectUtils.newInstance(v, limit.getKey(), limit.getValue());
                    }
                    break;
                default:
            }
            if (null != sqlPartParser) {
                parsers.put(k, Pair.of(sqlPartParser, configuration.getCustomSegmentParsers().get(k)));
            }

        });
        NativeSqlQuery sqlQuery = new NativeSqlQuery(parsers);
        sqlQuery.generate();
        return sqlQuery;
    }

    @Override
    public SetupBuilder limit(int offset, int n) {
        limit = Pair.of(offset, n);
        return this;
    }
}