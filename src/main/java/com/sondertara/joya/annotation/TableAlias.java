package com.sondertara.joya.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/**
 * 表别名
 *
 * @author huangxiaohu
 * 
 */

@Documented
@Target({ ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface TableAlias {

    /** 表的别名 数据权限使用,应该与模板查询中的别名一致, 默认: a */
    String value() default "a";

}