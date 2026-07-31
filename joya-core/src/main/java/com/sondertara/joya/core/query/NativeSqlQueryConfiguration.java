package com.sondertara.joya.core.query;

import com.sondertara.common.bean.container.context.BeanContainer;
import com.sondertara.joya.core.JoyaJdbc;
import com.sondertara.joya.core.query.parser.ParserTypeEnum;
import com.sondertara.joya.core.query.parser.SegmentParser;
import com.sondertara.joya.core.query.parser.SqlParser;
import com.sondertara.joya.core.query.parser.SqlPartParser;
import io.agroal.pool.DataSource;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * TODO
 *
 * @author huangxiaohu.1ih
 * @date 2024/11/18 16:47
 */
@SuppressWarnings({"rawtypes"})
@Getter
public class NativeSqlQueryConfiguration {

    public NativeSqlQueryConfiguration() {
        this.columnUppercase = JoyaJdbc.config().isColumnUppercase();
    }

    @Setter
    private boolean columnUppercase;

    @Setter
    private LinkType rootOperator = LinkType.AND;
    private final Map<ParserTypeEnum, Class<? extends SegmentParser>> customSegmentParsers = new HashMap<>();
    private final Map<ParserTypeEnum, Class<? extends SqlPartParser>> customSqlPartParsers = new HashMap<>();


    @SuppressWarnings({"unchecked", "rawtypes"})
    public void registerParser(ParserTypeEnum type, Class<? extends SqlParser> clazz) {
        if (SegmentParser.class.isAssignableFrom(clazz)) {
            customSegmentParsers.put(type, (Class<? extends SegmentParser>) clazz);
        } else if (SqlPartParser.class.isAssignableFrom(clazz)) {
            customSqlPartParsers.put(type, (Class<? extends SqlPartParser>) clazz);
        }
    }

}
