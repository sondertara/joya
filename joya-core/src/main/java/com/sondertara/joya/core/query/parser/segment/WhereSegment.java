package com.sondertara.joya.core.query.parser.segment;

import com.sondertara.common.struct.Pair;
import com.sondertara.common.collection.CollectionUtils;
import com.sondertara.joya.core.model.WherePart;
import com.sondertara.joya.core.query.parser.ParserTypeEnum;
import com.sondertara.joya.core.query.parser.WhereSegmentDetector;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * @author huangxiaohu
 */
@Getter
@Setter
public class WhereSegment extends AbstractSegment<WherePart> {

    private final StringJoiner segments;

    private final List<Object> parameters;

    @Override
    public ParserTypeEnum type() {
        return ParserTypeEnum.WHERE;
    }


    public WhereSegment(WherePart wherePart) {
        super(wherePart);
        Objects.requireNonNull(wherePart, "WherePart is required");
        this.parameters = new ArrayList<>();
        if (CollectionUtils.isEmpty(this.sqlPart.getSegments())) {
            this.segments = new StringJoiner("");
        } else {
            this.segments = new StringJoiner(wherePart.getRootLinkType().toString());

            parseInternal();
        }
    }

    public WhereSegment(WherePart wherePart, List<Object> parameters) {
        super(wherePart);
        Objects.requireNonNull(wherePart, "WherePart is required");
        if (CollectionUtils.isEmpty(this.sqlPart.getSegments())) {
            this.segments = new StringJoiner("");
            this.parameters = Optional.ofNullable(parameters).orElse(new ArrayList<>());
        } else {
            this.segments = new StringJoiner(wherePart.getRootLinkType().toString());
            this.parameters = parameters;
            parseInternal();
        }
    }

    protected void parseInternal() {
        WhereSegmentDetector whereSegmentDetector = new WhereSegmentDetector(this.sqlPart);
        whereSegmentDetector.handle(this, this);
    }

    @Override
    public String toString() {
        return "{\"WhereSegment\":{"
                + "\"segments\":"
                + segments
                + ",\"parameters\":"
                + parameters

                + "}}\n";
    }

    @Override
    public Pair<String, String> bound() {
        return Pair.of("WHERE ", null);

    }

    @Override
    public StringJoiner segments() {
        return this.segments;
    }

    @Override
    public List<Object> parameters() {
        return this.parameters;
    }
}
