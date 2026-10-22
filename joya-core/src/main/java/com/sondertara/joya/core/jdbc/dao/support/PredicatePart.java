package com.sondertara.joya.core.jdbc.dao.support;

import com.sondertara.joya.utils.Pair;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

/**
 * @author huangxiaohu
 */
@Getter
public class PredicatePart {

    private PropertyPath propertyPath;
    private MethodPredicateType methodPredicateType;

    private boolean orPartEnd;
    @Nullable
    private String sourceSuffix;

    public static PredicatePart from(String source, Class<?> clazz) {
        MethodPredicateType methodPredicateType = MethodPredicateType.fromProperty(source);
        Pair<String, String> pair = methodPredicateType.extractProperty(source);
        PredicatePart part = new PredicatePart(PropertyPath.from(pair.getKey(), clazz), methodPredicateType);
        part.setSourceSuffix(pair.getValue());
        return part;
    }

    public PredicatePart(PropertyPath propertyPath, MethodPredicateType methodPredicateType) {
        this.propertyPath = propertyPath;
        this.methodPredicateType = methodPredicateType;
    }

    public String getSourceSuffix() {
        return sourceSuffix;
    }

    public void setSourceSuffix(String sourceSuffix) {
        this.sourceSuffix = sourceSuffix;
    }

    public boolean isOrPartEnd() {
        return orPartEnd;
    }

    public void setOrPartEnd(boolean orPartEnd) {
        this.orPartEnd = orPartEnd;
    }
}
