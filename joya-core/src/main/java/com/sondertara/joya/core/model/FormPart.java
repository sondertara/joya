package com.sondertara.joya.core.model;

import com.sondertara.joya.core.query.parser.ParserTypeEnum;
import lombok.Data;

import java.util.List;
import java.util.Set;

/**
 * @author huangxiaohu
 */
@Data
public class FormPart  implements SqlPart{

    private List<JoinPart> joinParts;
    private Set<TableAlias> tables;

    public FormPart(List<JoinPart> joinParts, Set<TableAlias> tables) {
        this.joinParts = joinParts;
        this.tables = tables;
    }

    @Override
    public ParserTypeEnum type() {
        return ParserTypeEnum.FROM;
    }
}
