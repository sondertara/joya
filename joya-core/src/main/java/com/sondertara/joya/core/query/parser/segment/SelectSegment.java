package com.sondertara.joya.core.query.parser.segment;

import com.sondertara.common.struct.Pair;
import com.sondertara.common.collection.CollectionUtils;
import com.sondertara.joya.core.model.SelectPart;
import com.sondertara.joya.core.model.TableAlias;
import com.sondertara.joya.core.query.ColumnPair;
import com.sondertara.joya.core.query.parser.ParserTypeEnum;

import java.util.Set;
import java.util.StringJoiner;

/**
 * @author huangxiaohu
 */
public class SelectSegment extends AbstractSegment<SelectPart> {
    public SelectSegment(SelectPart selectPart) {
        super(selectPart);
    }

    @Override
    public ParserTypeEnum type() {
        return ParserTypeEnum.SELECT;
    }

    @Override
    public Pair<String, String> bound() {
        return Pair.of("SELECT ", null);
    }

    @Override
    public StringJoiner segments() {
        StringJoiner sj = new StringJoiner(", ");
        Set<ColumnPair> selects = this.sqlPart.getSegments();
        if (CollectionUtils.isEmpty(selects)) {
            Set<TableAlias> list = this.sqlPart.getAllTables();
            for (TableAlias tableAlias : list) {
                sj.add(tableAlias.getAliasName() + ".*");
            }
        } else {
            for (ColumnPair select : selects) {
                String key = select.getKey();
                sj.add(select.getValue() == null ? key : key + " " + select.getValue());
            }
        }
        return sj;
    }


}
