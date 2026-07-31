package com.sondertara.joya.core.model;

import com.sondertara.common.collection.CollectionUtils;
import com.sondertara.common.regex.RegexUtils;
import com.sondertara.joya.core.query.Operator;
import com.sondertara.joya.core.query.parser.chain.HandlerChain;
import com.sondertara.joya.core.query.parser.segment.WhereSegment;
import com.sondertara.joya.exceptions.SqlParseException;
import com.sondertara.joya.utils.SqlFormatUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * @author huangxiaohu
 */
public class RawStringCondition implements WhereConditionVisitor {

    private final String conditionStr;
    private final Object[] params;

    public RawStringCondition(String conditionStr, Object... params) {
        this.conditionStr = conditionStr;
        this.params = params;
    }

    @Override
    public Operator operator() {
        return Operator.NONE;
    }

    @Override
    public void process(WhereSegment input, WhereSegment result, HandlerChain<WhereSegment, WhereSegment> chain) {
        StringJoiner segments = input.getSegments();
        List<Object> parameters = input.parameters();
        int prevIndex = parameters.size();
        int lastIndex = prevIndex + 1;
        String sql = conditionStr;
        int index = 0;
        List<Object> addParameters = new ArrayList<>();
        List<String> allGroup0 = RegexUtils.findAllGroup0(SqlFormatUtils.POSITIONAL_OR_INDEXED_PARAMETER, sql);
        if (CollectionUtils.isNotEmpty(allGroup0)) {
            if (allGroup0.size() != this.params.length) {
                throw new SqlParseException("Sql str parameters => {} length is not match the giving parameters =>", conditionStr, this.params);
            }
            for (String string : allGroup0) {
                sql = sql.replace(string, "?" + lastIndex + index);
                addParameters.add(this.params[index]);
                index++;
            }
        } else {
            List<String> allGroup01 = RegexUtils.findAllGroup0(SqlFormatUtils.JDBC_STYLE_PARAM, sql);
            for (String string : allGroup01) {
                sql = sql.replace(string, "?" + lastIndex + index);
                addParameters.add(this.params[index]);
                index++;
            }
        }
        segments.add(sql);
        parameters.addAll(addParameters);
        chain.handle(input, result);

    }

    public static void main(String[] args) {
        List<String> allGroup0 = RegexUtils.findAllGroup0(SqlFormatUtils.POSITIONAL_OR_INDEXED_PARAMETER, "SELECT\n" +
                "  t0.*,\n" +
                "  t1.*\n" +
                "FROM\n" +
                "  app_comp t0,\n" +
                "  app_du t1\n" +
                "WHERE\n" +
                "  t0.app_id = ?1\n" +
                "  AND (\n" +
                "    t0.cname < ?2\n" +
                "    OR t0.app_level < ?3\n" +
                "  )");
        System.out.println(allGroup0);
    }
}
