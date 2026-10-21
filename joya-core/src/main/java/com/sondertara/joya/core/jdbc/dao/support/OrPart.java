package com.sondertara.joya.core.jdbc.dao.support;

import com.sondertara.joya.utils.Strings;
import lombok.Getter;


import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author huangxiaohu
 */
@Getter
public class OrPart implements Streamable<PredicatePart> {
    private static final String KEYWORD_TEMPLATE = "(%s)(?=(\\p{Lu}|\\P{InBASIC_LATIN}))";

    private static String[] split(String text, String keyword) {

        Pattern pattern = Pattern.compile(String.format(KEYWORD_TEMPLATE, keyword));
        return pattern.split(text);
    }

    private final List<PredicatePart> children;

    private final String source;

    /**
     * Creates a new {@link OrPart}.
     *
     * @param source      the source to split up into {@literal And} parts in turn.
     * @param domainClass the domain class to check the resulting {@link PredicatePart}s against.
     */
    OrPart(String source, Class<?> domainClass) {

        this.source = source;
        String[] split = split(source, "And");
        this.children = Arrays.stream(split)
                .filter(Strings::hasText)
                .map(part -> PredicatePart.from(part, domainClass))
                .collect(Collectors.toList());
    }

    @Override

    public Iterator<PredicatePart> iterator() {
        return children.iterator();
    }

    @Override
    public String toString() {
        return children.stream().map(Object::toString).collect(Collectors.joining(" and"));
    }
}