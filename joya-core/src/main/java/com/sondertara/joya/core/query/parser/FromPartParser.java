package com.sondertara.joya.core.query.parser;

import com.sondertara.joya.core.model.FormPart;
import com.sondertara.joya.core.model.JoinPart;
import com.sondertara.joya.core.model.TableAlias;

import java.util.List;
import java.util.Set;

/**
 * @author huangxiaohu
 */
public class FromPartParser implements SqlPartParser<FormPart> {

    protected final Set<TableAlias> tables;
    protected final List<JoinPart> joinParts;

    public FromPartParser(Set<TableAlias> tables, List<JoinPart> joinParts) {
        this.tables = tables;
        this.joinParts = joinParts;
    }


    @Override
    public FormPart parse() {
        return new FormPart(joinParts, tables);
    }


    @Override
    public ParserTypeEnum type() {
        return ParserTypeEnum.FROM;
    }
}
