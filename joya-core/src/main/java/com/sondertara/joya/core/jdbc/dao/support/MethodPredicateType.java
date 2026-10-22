package com.sondertara.joya.core.jdbc.dao.support;

import com.sondertara.joya.utils.Pair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public enum MethodPredicateType {
    /**
     *
     */
    BETWEEN(2, "IsBetween", "Between"), IS_NOT_NULL(0, "IsNotNull", "NotNull"), IS_NULL(0, "IsNull", "Null"), LESS_THAN(
            "IsLessThan", "LessThan"), LESS_THAN_EQUAL("IsLessThanEqual", "LessThanEqual"), GREATER_THAN("IsGreaterThan",
            "GreaterThan"), GREATER_THAN_EQUAL("IsGreaterThanEqual", "GreaterThanEqual"), BEFORE("IsBefore",
            "Before"), AFTER("IsAfter", "After"), NOT_LIKE("IsNotLike", "NotLike"), LIKE("IsLike",
            "Like"), STARTING_WITH("IsStartingWith", "StartingWith", "StartsWith"), ENDING_WITH("IsEndingWith",
            "EndingWith", "EndsWith"), IS_NOT_EMPTY(0, "IsNotEmpty", "NotEmpty"), IS_EMPTY(0, "IsEmpty",
            "Empty"), NOT_CONTAINING("IsNotContaining", "NotContaining", "NotContains"), CONTAINING(
            "IsContaining", "Containing", "Contains"), NOT_IN("IsNotIn", "NotIn"), IN("IsIn",
            "In"), NEAR("IsNear", "Near"), WITHIN("IsWithin", "Within"), REGEX("MatchesRegex",
            "Matches", "Regex"), EXISTS(0, "Exists"), TRUE(0, "IsTrue", "True"), FALSE(0,
            "IsFalse", "False"), NEGATING_SIMPLE_PROPERTY("IsNot",
            "Not"), SIMPLE_PROPERTY("Is", "Equals");

    private static final List<MethodPredicateType> ALL = Arrays.asList(IS_NOT_NULL, IS_NULL, BETWEEN, LESS_THAN, LESS_THAN_EQUAL,
            GREATER_THAN, GREATER_THAN_EQUAL, BEFORE, AFTER, NOT_LIKE, LIKE, STARTING_WITH, ENDING_WITH, IS_NOT_EMPTY,
            IS_EMPTY, NOT_CONTAINING, CONTAINING, NOT_IN, IN, NEAR, WITHIN, REGEX, EXISTS, TRUE, FALSE,
            NEGATING_SIMPLE_PROPERTY, SIMPLE_PROPERTY);

    public static final Collection<String> ALL_KEYWORDS;

    static {
        List<String> allKeywords = new ArrayList<>();
        for (MethodPredicateType methodPredicateType : ALL) {
            allKeywords.addAll(methodPredicateType.keywords);
        }
        ALL_KEYWORDS = Collections.unmodifiableList(allKeywords);
    }

    private final List<String> keywords;
    private final int numberOfArguments;

    /**
     * Creates a new {@link MethodPredicateType} using the given keyword, number of arguments to be bound and operator. Keyword and
     * operator can be {@literal null}.
     *
     * @param numberOfArguments
     * @param keywords
     */
    private MethodPredicateType(int numberOfArguments, String... keywords) {

        this.numberOfArguments = numberOfArguments;
        this.keywords = Arrays.asList(keywords);
    }

    private MethodPredicateType(String... keywords) {
        this(1, keywords);
    }

    /**
     * Returns the {@link MethodPredicateType} of the {@link PredicatePart} for the given raw propertyPath. This will try to detect e.g.
     * keywords contained in the raw propertyPath that trigger special query creation. Returns {@link #SIMPLE_PROPERTY}
     * by default.
     *
     * @param rawProperty
     * @return
     */
    public static MethodPredicateType fromProperty(String rawProperty) {

        for (MethodPredicateType methodPredicateType : ALL) {
            if (methodPredicateType.supports(rawProperty)) {
                return methodPredicateType;
            }
        }

        return SIMPLE_PROPERTY;
    }

    /**
     * Returns all keywords supported by the current {@link MethodPredicateType}.
     *
     * @return
     */
    public Collection<String> getKeywords() {
        return Collections.unmodifiableList(keywords);
    }

    /**
     * Returns whether the the type supports the given raw property. Default implementation checks whether the property
     * ends with the registered keyword. Does not support the keyword if the property is a valid field as is.
     *
     * @param property
     * @return
     */
    protected boolean supports(String property) {

        for (String keyword : keywords) {
            if (property.endsWith(keyword)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns the number of arguments the propertyPath binds. By default this exactly one argument.
     *
     * @return
     */
    public int getNumberOfArguments() {
        return numberOfArguments;
    }

    /**
     * Callback method to extract the actual propertyPath to be bound from the given part. Strips the keyword from the
     * part's end if available.
     *
     * @param part
     * @return
     */
    public Pair<String,String> extractProperty(String part) {

        for (String keyword : keywords) {
            if (part.endsWith(keyword)) {
                return Pair.of(part.substring(0, part.length() - keyword.length()),keyword);
            }
        }
        return Pair.of(part,null);
    }

    @Override
    public String toString() {
        return String.format("%s (%s): %s", name(), getNumberOfArguments(), getKeywords());
    }
}