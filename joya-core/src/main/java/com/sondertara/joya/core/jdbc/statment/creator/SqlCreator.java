package com.sondertara.joya.core.jdbc.statment.creator;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author huangxiaohu
 */
public interface SqlCreator {

    String create();

    List<PreparedParameterBinder> parameters();

    default Object[] createParameters() {
        return this.parameters().stream().map(PreparedParameterBinder::getValue).toArray();
    }

    default List<String> createParameterNames() {
        return this.parameters().stream().map(PreparedParameterBinder::getName).collect(Collectors.toList());
    }

    @Builder
    @Getter
    @Setter
    @ToString
    class PreparedParameterBinder implements Serializable {

        private int position;

        private String name;

        private Object value;

        public Object getValue() {
            return value;
        }
    }
}
