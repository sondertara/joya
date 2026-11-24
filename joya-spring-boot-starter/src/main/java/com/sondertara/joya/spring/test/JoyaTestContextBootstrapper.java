package com.sondertara.joya.spring.test;

import org.springframework.boot.test.context.SpringBootTestContextBootstrapper;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;

/**
 * {@link JoyaTest} 引导器：将 {@link JoyaTest#properties()} 注入测试环境。
 */
final class JoyaTestContextBootstrapper extends SpringBootTestContextBootstrapper {

    @Override
    protected String[] getProperties(Class<?> testClass) {
        JoyaTest annotation = MergedAnnotations.from(testClass, SearchStrategy.TYPE_HIERARCHY)
                .get(JoyaTest.class)
                .synthesize(MergedAnnotation::isPresent)
                .orElse(null);
        return (annotation != null) ? annotation.properties() : null;
    }
}
