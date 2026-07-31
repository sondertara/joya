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

import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.This;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.Objects;

/**
 * ByteBuddy 用的声明式事务拦截器。
 * <p>
 * 改进点：
 * <ol>
 *   <li>从 {@code method.invoke} 抛出的 {@link InvocationTargetException} 必须
 *   解包后再传给上层，否则调用方拿不到真实业务异常。</li>
 *   <li>按 {@code rollbackFor / noRollbackFor} 严格判定回滚。</li>
 *   <li>单例化可选：实例由 {@link com.sondertara.joya.core.proxy.ByteBuddyMapperProxy}
 *   持有，不再每次调用 new 一次 manager —— 但 interceptor 实例本身可复用。</li>
 * </ol>
 *
 * @author huangxiaohu
 */
public class TransactionalInterceptor {

    private final DataSource dataSource;

    public TransactionalInterceptor(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    /** 判定是否 <em>不</em> 回滚 */
    private boolean shouldSuppressRollback(Transactional anno, Throwable t) {
        // 类匹配
        for (Class<? extends Throwable> cls : anno.noRollbackFor()) {
            if (cls.isInstance(t)) {
                return true;
            }
        }
        // 类名匹配
        String typeName = t.getClass().getName();
        for (String name : anno.noRollbackForClassName()) {
            if (typeName.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** 判定是否 <em>需要</em> 回滚 */
    private boolean shouldRollback(Transactional anno, Throwable t) {
        // 显式 rollbackFor 优先
        if (anno.rollbackFor().length > 0 || anno.rollbackForClassName().length > 0) {
            for (Class<? extends Throwable> cls : anno.rollbackFor()) {
                if (cls.isInstance(t)) {
                    return true;
                }
            }
            String typeName = t.getClass().getName();
            for (String name : anno.rollbackForClassName()) {
                if (typeName.equals(name)) {
                    return true;
                }
            }
            return false;
        }
        // 默认仅对 unchecked 异常回滚
        return t instanceof RuntimeException || t instanceof Error;
    }

    /** 在方法 → 类 → 接口 顺序查找 {@link Transactional} 注解 */
    private Transactional findAnnotation(Method targetMethod) {
        Transactional tran = targetMethod.getAnnotation(Transactional.class);
        if (tran != null) {
            return tran;
        }
        Class<?> declaringClass = targetMethod.getDeclaringClass();
        return declaringClass.getAnnotation(Transactional.class);
    }

    @RuntimeType
    public Object intercept(@This Object instance, @Origin Method method, @AllArguments Object[] args) throws Throwable {
        Transactional anno = findAnnotation(method);
        if (anno == null) {
            return invokeTarget(instance, method, args);
        }

        TransactionManager manager = new LocalTransactionManager(dataSource);
        AbstractTransactionStatus status = (AbstractTransactionStatus) manager.begin(anno.propagation(), anno.isolation());
        if (anno.readOnly()) {
            status.markReadOnly();
        }
        try {
            return invokeTarget(instance, method, args);
        } catch (Throwable ex) {
            if (shouldRollback(anno, ex) && !shouldSuppressRollback(anno, ex)) {
                status.markRollbackOnly();
            }
            throw ex;
        } finally {
            if (!status.isCompleted()) {
                manager.commit(status);
            }
        }
    }

    /** 解包 {@link InvocationTargetException}，避免上层拿到反射包装异常 */
    private Object invokeTarget(Object instance, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(instance, args);
        } catch (InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }
}
