package com.sondertara.joya.core.query.parser.segment;

import com.sondertara.common.struct.Pair;
import com.sondertara.common.collection.CollectionUtils;
import com.sondertara.joya.core.model.ColumnAlias;
import com.sondertara.joya.core.model.FormPart;
import com.sondertara.joya.core.model.JoinPart;
import com.sondertara.joya.core.model.TableAlias;
import com.sondertara.joya.core.query.parser.ParserTypeEnum;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/**
 * @author huangxiaohu
 */
public class FromSegmentParser extends AbstractSegment<FormPart> {


    @Override
    public ParserTypeEnum type() {
        return ParserTypeEnum.FROM;
    }

    public FromSegmentParser(FormPart formPart) {
        super(formPart);
    }

    @Override
    public Pair<String, String> bound() {
        return Pair.of("FROM  ", null);
    }

    @Override
    public StringJoiner segments() {
        Set<TableAlias> tables = this.sqlPart.getTables();
        List<JoinPart> joinParts = this.sqlPart.getJoinParts();


        if (CollectionUtils.isEmpty(joinParts)) {
            StringJoiner sj = new StringJoiner(",");
            for (TableAlias table : tables) {
                sj.add(table.getTableName() + " " + table.getAliasName());
            }
            return sj;
        }
        StringJoiner sj = new StringJoiner(" ");
        Set<TableAlias> existsTable = new HashSet<>();
        StringBuilder sb = new StringBuilder();
        for (JoinPart segment : joinParts) {
            ColumnAlias first = segment.getLeftColumn();
            ColumnAlias last = segment.getRightColumn();
            String code = segment.getJoinType().getCode();
            if (existsTable.contains(TableAlias.of(first.getTableName(), first.getTableAlias()))) {
                sb.append(' ').append(code).append(' ');
                sb.append(last.getTableName()).append(' ').append(last.getTableAlias());
                sb.append(" ON ").append(first.identity()).append(" = ").append(last.identity());
            } else if (existsTable.contains(TableAlias.of(last.getTableName(), last.getTableAlias()))) {
                sb.append(' ').append(code).append(' ');
                sb.append(first.getTableName()).append(' ').append(first.getTableAlias());
                sb.append(" ON ").append(first.identity()).append(" = ").append(last.identity());
            } else {
                sb.append(first.getTableName()).append(" ").append(first.getTableAlias());
                sb.append(' ').append(code).append(' ');
                sb.append(last.getTableName()).append(' ').append(last.getTableAlias());
                sb.append(" ON ").append(first.identity()).append(" = ").append(last.identity());

            }
            existsTable.add(TableAlias.of(first.getTableName(), first.getTableAlias()));
            existsTable.add(TableAlias.of(last.getTableName(), last.getTableAlias()));
            sj.add(sb);
            sb.delete(0, sb.length());
        }
        return sj;
    }
}
