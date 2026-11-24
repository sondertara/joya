package com.sondertara.joya.spring.test;

import org.springframework.boot.test.autoconfigure.filter.StandardAnnotationCustomizableTypeExcludeFilter;

/**
 * {@link JoyaTest} 专属组件扫描过滤器：按标准 Spring Boot 切片语义仅保留测试显式包含的组件与 Joya 数据层配置。
 */
final class JoyaTypeExcludeFilter extends StandardAnnotationCustomizableTypeExcludeFilter<JoyaTest> {

    JoyaTypeExcludeFilter(Class<?> testClass) {
        super(testClass);
    }
}
