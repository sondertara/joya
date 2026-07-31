package com.sondertara.joya.core.query.parser;

import com.sondertara.joya.core.model.SelectPart;
import com.sondertara.joya.core.model.TableAlias;
import com.sondertara.joya.core.query.ColumnPair;
import lombok.Getter;

import java.util.Set;

/**
 * @author huangxiaohu
 */
public class SelectPartParser implements SqlPartParser<SelectPart> {



    protected final Set<ColumnPair> selects;

    protected final Set<TableAlias> allTables;

    public SelectPartParser(Set<TableAlias> allTables, Set<ColumnPair> selects) {
        this.selects = selects;
        this.allTables = allTables;
    }

    @Override
    public SelectPart parse() {
        return new SelectPart(this.allTables, this.selects);
    }

    @Override
    public ParserTypeEnum type() {
        return ParserTypeEnum.SELECT;
    }
}
