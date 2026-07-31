/*
 * Copyright 2015-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sondertara.joya.core.jdbc.transaction;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式事务注解，可标注在方法或类上。
 * <p>
 * 由 {@link TransactionalInterceptor} 在 ByteBuddy 代理时识别。
 *
 * @author huangxiaohu
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Transactional {
    /** 事务传播行为，默认 {@link Propagation#REQUIRED} */
    Propagation propagation() default Propagation.REQUIRED;

    /** 事务隔离级别，默认 {@link Isolation#DEFAULT}（沿用数据源配置） */
    Isolation isolation() default Isolation.DEFAULT;

    /** 是否为只读事务；递交时会被回滚 */
    boolean readOnly() default false;

    /** 遇到下列异常 <em>不</em> 回滚（按类匹配） */
    Class<? extends Throwable>[] noRollbackFor() default {};

    /** 遇到下列异常 <em>不</em> 回滚（按全限定类名匹配） */
    String[] noRollbackForClassName() default {};

    /** 遇到下列异常触发回滚（按类匹配）；为空时仅 RuntimeException/Error 触发 */
    Class<? extends Throwable>[] rollbackFor() default {};

    /** 遇到下列异常触发回滚（按全限定类名匹配） */
    String[] rollbackForClassName() default {};
}
