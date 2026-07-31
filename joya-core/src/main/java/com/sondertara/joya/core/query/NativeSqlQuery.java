package com.sondertara.joya.core.query;


import com.sondertara.common.struct.Pair;
import com.sondertara.common.collection.Maps;
import com.sondertara.common.reflect.ReflectUtils;
import com.sondertara.joya.core.query.parser.SegmentParser;
import com.sondertara.joya.core.query.builder.SelectBuilder;
import com.sondertara.joya.core.query.parser.ParserTypeEnum;
import com.sondertara.joya.core.query.parser.SqlPartParser;
import com.sondertara.joya.core.query.parser.segment.FromSegmentParser;
import com.sondertara.joya.core.query.parser.segment.GroupBySegment;
import com.sondertara.joya.core.query.parser.segment.HavingSegment;
import com.sondertara.joya.core.query.parser.segment.LimitSegment;
import com.sondertara.joya.core.query.parser.segment.OrderBySegment;
import com.sondertara.joya.core.query.parser.segment.SelectSegment;
import com.sondertara.joya.core.query.parser.segment.WhereSegment;
import com.sondertara.joya.utils.SqlFormatUtils;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * 条件查询构造器
 *
 * @author huangxiaohu
 */

public class NativeSqlQuery {


    /**
     * 占位符对应的参数值
     */
    @Getter
    private final List<Object> params = new ArrayList<>();

    private final AtomicBoolean isGenerated = new AtomicBoolean(false);

    private String sqlStr;

    private static final Map<ParserTypeEnum, Class<? extends SegmentParser>> DEFAULT_SEGMENT_MAP = Maps.newHashMap();

    static {
        DEFAULT_SEGMENT_MAP.put(ParserTypeEnum.SELECT, SelectSegment.class);
        DEFAULT_SEGMENT_MAP.put(ParserTypeEnum.FROM, FromSegmentParser.class);
        DEFAULT_SEGMENT_MAP.put(ParserTypeEnum.WHERE, WhereSegment.class);
        DEFAULT_SEGMENT_MAP.put(ParserTypeEnum.ORDER_BY, OrderBySegment.class);
        DEFAULT_SEGMENT_MAP.put(ParserTypeEnum.GROUP_BY, GroupBySegment.class);
        DEFAULT_SEGMENT_MAP.put(ParserTypeEnum.HAVING, HavingSegment.class);
        DEFAULT_SEGMENT_MAP.put(ParserTypeEnum.LIMIT, LimitSegment.class);
    }


    private final Map<ParserTypeEnum, Pair<SqlPartParser<?>, Class<? extends SegmentParser>>> parsers;

    protected NativeSqlQuery(Map<ParserTypeEnum, Pair<SqlPartParser<?>, Class<? extends SegmentParser>>> parsers) {
        this.parsers = parsers;

    }

    protected NativeSqlQuery generate() {
        if (isGenerated.compareAndSet(false, true)) {
            this.sqlStr = generateInternal();
        }
        return this;
    }

    public static SelectBuilder builder() {
        return new NativeSqlQueryBuilder();
    }

    public static SelectBuilder builder(NativeSqlQueryConfiguration configuration) {
        return new NativeSqlQueryBuilder(configuration);
    }

    /**
     * 获取格式化sql
     *
     * @return sql
     */
    public String toFormattedSql() {
        return SqlFormatUtils.formatSql(this.toSql());
    }

    public String toSql() {
        if (isGenerated.get()) {
            return this.sqlStr;
        }
        return generateInternal();
    }

    private String generateInternal() {
        TreeMap<ParserTypeEnum, Pair<SqlPartParser<?>, Class<? extends SegmentParser>>> map = new TreeMap<>(Comparator.comparingInt(Enum::ordinal));
        map.putAll(this.parsers);
        StringJoiner sj = new StringJoiner(" ");
        map.forEach((k, v) -> {
            SqlPartParser<?> sqlPartParser = v.getKey();
            Class<? extends SegmentParser> aClass = v.getValue();
            if (null == aClass) {
                aClass = DEFAULT_SEGMENT_MAP.get(k);
            }
            SegmentParser instance = ReflectUtils.newInstance(aClass, sqlPartParser.parse());
            StringJoiner segments = instance.segments();
            if (segments.length() > 0) {
                Pair<String, String> bound = instance.bound();
                if (null != bound.getKey()) {
                    sj.add(bound.getKey().trim());
                }

                if (segments.length() > 0) {
                    sj.merge(segments);
                    if (null != bound.getValue()) {
                        sj.add(bound.getValue().trim());
                    }
                }
                this.params.addAll(instance.parameters());
            }
        });

        return sj.toString();
    }

    @Override
    public String toString() {
        return "NativeSqlQuery{" + this.toSql() + "}";
    }


}
