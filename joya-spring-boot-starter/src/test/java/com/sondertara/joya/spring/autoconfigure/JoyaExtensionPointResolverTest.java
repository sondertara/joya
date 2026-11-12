package com.sondertara.joya.spring.autoconfigure;

import com.sondertara.joya.core.kernel.error.SqlExceptionTranslator;
import com.sondertara.joya.core.kernel.statement.SqlStatement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * E-4：FactoryBean 定义的扩展点必须可被发现（类型判定需要 eager init），
 * 且限定名优先 + 同级多候选启动失败的规则保持不变。
 */
class JoyaExtensionPointResolverTest {

    static class GlobalTranslator implements SqlExceptionTranslator {
        @Override
        public RuntimeException translate(String task, SqlStatement statement, SQLException ex) {
            return new IllegalStateException("global");
        }
    }

    static class ScopedTranslator implements SqlExceptionTranslator {
        @Override
        public RuntimeException translate(String task, SqlStatement statement, SQLException ex) {
            return new IllegalStateException("scoped");
        }
    }

    /** 典型场景：只有初始化后才能确定 {@code getObjectType()} 的 FactoryBean。 */
    static class TranslatorFactory implements FactoryBean<SqlExceptionTranslator> {
        static int created;

        @Override
        public SqlExceptionTranslator getObject() {
            created++;
            return new GlobalTranslator();
        }

        @Override
        public Class<?> getObjectType() {
            return SqlExceptionTranslator.class;
        }

        @Override
        public boolean isSingleton() {
            return true;
        }
    }

    @Test
    void factoryBeanExtensionPointIsDiscoveredDespiteLazyType() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("translator", new RootBeanDefinition(TranslatorFactory.class));

        assertEquals(0, beanFactory.getBeanNamesForType(SqlExceptionTranslator.class, false, false).length,
                "前置条件：不允许 eager init 时该 FactoryBean 的扩展点类型不可见（E-4 的漏检根因）");

        JoyaExtensionPointResolver resolver = new JoyaExtensionPointResolver(beanFactory);
        SqlExceptionTranslator translator = resolver.singleForClient(SqlExceptionTranslator.class, "primary");

        assertNotNull(translator, "FactoryBean 扩展点不应被静默跳过");
        assertInstanceOf(GlobalTranslator.class, translator);
        assertEquals(1, TranslatorFactory.created);
    }

    @Test
    void twoGlobalCandidatesFailFast() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("translatorA", new GlobalTranslator());
        beanFactory.registerSingleton("translatorB", new ScopedTranslator());

        JoyaExtensionPointResolver resolver = new JoyaExtensionPointResolver(beanFactory);

        assertThrows(IllegalStateException.class,
                () -> resolver.singleForClient(SqlExceptionTranslator.class, "primary"));
    }

    @Test
    void scopedQualifierWinsOverGlobalCandidate() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        AbstractBeanDefinition scoped = new RootBeanDefinition(ScopedTranslator.class);
        scoped.addQualifier(new AutowireCandidateQualifier(Qualifier.class, "order"));
        beanFactory.registerBeanDefinition("orderTranslator", scoped);
        beanFactory.registerSingleton("globalTranslator", new GlobalTranslator());

        JoyaExtensionPointResolver resolver = new JoyaExtensionPointResolver(beanFactory);

        assertInstanceOf(ScopedTranslator.class,
                resolver.singleForClient(SqlExceptionTranslator.class, "order"));
        assertInstanceOf(GlobalTranslator.class,
                resolver.singleForClient(SqlExceptionTranslator.class, "primary"));
    }

    @Test
    void globalAndScopedBeansBothRouteToScopedClient() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        AbstractBeanDefinition scoped = new RootBeanDefinition(ScopedTranslator.class);
        scoped.addQualifier(new AutowireCandidateQualifier(Qualifier.class, "order"));
        beanFactory.registerBeanDefinition("orderTranslator", scoped);
        beanFactory.registerSingleton("globalTranslator", new GlobalTranslator());

        JoyaExtensionPointResolver resolver = new JoyaExtensionPointResolver(beanFactory);

        List<SqlExceptionTranslator> order = resolver.forClient(SqlExceptionTranslator.class, "order");
        List<SqlExceptionTranslator> primary = resolver.forClient(SqlExceptionTranslator.class, "primary");
        assertEquals(2, order.size());
        assertEquals(1, primary.size());
        assertInstanceOf(GlobalTranslator.class, primary.get(0));
    }
}
