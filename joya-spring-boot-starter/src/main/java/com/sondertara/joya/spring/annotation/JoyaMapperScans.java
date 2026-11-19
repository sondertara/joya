package com.sondertara.joya.spring.annotation;

import com.sondertara.joya.spring.mapper.JoyaMapperScannerRegistrar;
import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@link JoyaMapperScan} 的可重复注解容器，用于按不同包路径绑定多个数据源客户端：
 * <pre>{@code
 * @JoyaMapperScan("com.example.user.mapper")
 * @JoyaMapperScan(basePackages = "com.example.order.mapper", client = "order")
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
@Import(JoyaMapperScannerRegistrar.class)
public @interface JoyaMapperScans {

    JoyaMapperScan[] value();
}
