package com.sondertara.joya.spring.annotation;


import com.sondertara.joya.spring.init.JoyaMappersRegistrar;
import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author huangxiaohu
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import({JoyaMappersRegistrar.class})
@Documented
public @interface EnableJoyaJdbc {

    /**
     * @return the array of 'basePackages'.
     */
    String[] value() default {};


    String[] basePackages() default {};

    boolean enableScan() default true;
}