package com.sondertara.joya.spring.autoconfigure;

import com.sondertara.joya.core.JoyaClient;
import com.sondertara.joya.utils.Strings;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.SingletonBeanRegistry;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.env.Environment;
import org.springframework.core.type.MethodMetadata;

import javax.sql.DataSource;
import java.lang.annotation.Annotation;
import java.util.HashSet;
import java.util.Set;

/**
 * 为 {@code joya.datasources} 中配置的每个命名扩展库按需注册 {@link JoyaClient} Bean（携带 {@link Qualifier}，
 * 绝不标记 {@code @Primary}，采用 {@code factoryBeanName + factoryMethodName} 标准声明以原生兼容 Spring Boot 3 AOT，
 * 并与同名 {@link DataSource} Bean 避让命名冲突），使业务代码可直接 {@code @Qualifier("<name>") JoyaClient} 注入。
 */
@AutoConfiguration(after = JoyaAutoConfiguration.class)
@ConditionalOnClass({DataSource.class, JoyaClient.class})
@ConditionalOnProperty(prefix = "joya", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JoyaNamedClientsAutoConfiguration {

    @Bean
    public static BeanDefinitionRegistryPostProcessor joyaNamedClientRegistrar(Environment environment) {
        return new NamedClientRegistrar(environment);
    }

    private static final class NamedClientRegistrar implements BeanDefinitionRegistryPostProcessor {

        private final Environment environment;

        private NamedClientRegistrar(Environment environment) {
            this.environment = environment;
        }

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
            String[] registryBeanNames = null;
            String[] existingClientBeans = new String[0];
            if (registry instanceof ListableBeanFactory lbf) {
                registryBeanNames = lbf.getBeanNamesForType(JoyaClientRegistry.class, true, false);
                if (registryBeanNames.length == 0) {
                    return;
                }
                existingClientBeans = lbf.getBeanNamesForType(JoyaClient.class, true, false);
            }
            JoyaProperties properties = Binder.get(environment)
                    .bind("joya", JoyaProperties.class)
                    .orElse(null);
            if (properties == null || !properties.isEnabled() || properties.getDatasources().isEmpty()) {
                return;
            }
            String primaryName = Strings.isBlank(properties.getPrimary().getName())
                    ? JoyaClientRegistry.PRIMARY
                    : properties.getPrimary().getName().trim();
            Set<String> seen = new HashSet<>();
            seen.add(primaryName);
            String registryBeanName = (registryBeanNames != null && registryBeanNames.length > 0)
                    ? registryBeanNames[0]
                    : "joyaClientRegistry";

            for (JoyaProperties.JoyaDataSourceProperties dsProps : properties.getDatasources()) {
                String name = dsProps.getName() == null ? "" : dsProps.getName().trim();
                if (name.isEmpty() || !seen.add(name)) {
                    continue;
                }
                if (hasExistingNamedClient(registry, existingClientBeans, name)) {
                    continue;
                }
                String beanName = resolveUniqueBeanName(registry, name);
                RootBeanDefinition bd = new RootBeanDefinition(JoyaClient.class);
                bd.setPrimary(false);
                bd.setFactoryBeanName(registryBeanName);
                bd.setFactoryMethodName("get");
                bd.getConstructorArgumentValues().addIndexedArgumentValue(0, name);
                bd.setDependsOn(registryBeanName);
                bd.addQualifier(new AutowireCandidateQualifier(Qualifier.class, name));
                registry.registerBeanDefinition(beanName, bd);
            }
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
            // BeanDefinition 已在 postProcessBeanDefinitionRegistry 阶段完成注册
        }

        private static boolean hasExistingNamedClient(BeanDefinitionRegistry registry,
                                                      String[] existingClientBeans,
                                                      String targetName) {
            for (String beanName : existingClientBeans) {
                if (beanName.equals(targetName)) {
                    return true;
                }
                if (!registry.containsBeanDefinition(beanName)) {
                    continue;
                }
                BeanDefinition bd = registry.getBeanDefinition(beanName);
                if (bd instanceof AbstractBeanDefinition abd) {
                    for (AutowireCandidateQualifier q : abd.getQualifiers()) {
                        if (targetName.equals(q.getAttribute(AutowireCandidateQualifier.VALUE_KEY))) {
                            return true;
                        }
                    }
                }
                if (bd instanceof AnnotatedBeanDefinition annotatedBd) {
                    MethodMetadata factoryMethod = annotatedBd.getFactoryMethodMetadata();
                    if (factoryMethod != null && hasQualifierAnnotation(factoryMethod.getAnnotations(), targetName)) {
                        return true;
                    }
                    if (hasQualifierAnnotation(annotatedBd.getMetadata().getAnnotations(), targetName)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private static boolean hasQualifierAnnotation(MergedAnnotations annotations, String targetName) {
            for (MergedAnnotation<Annotation> ann : annotations) {
                if (Qualifier.class.equals(ann.getType()) && targetName.equals(ann.getString("value"))) {
                    return true;
                }
            }
            return false;
        }

        private static String resolveUniqueBeanName(BeanDefinitionRegistry registry, String clientName) {
            if (!isBeanNameTaken(registry, clientName)) {
                return clientName;
            }
            String fallback = clientName + "JoyaClient";
            if (!isBeanNameTaken(registry, fallback)) {
                return fallback;
            }
            throw new IllegalStateException("Cannot register named JoyaClient bean for datasource '"
                    + clientName + "': both candidate bean names ('" + clientName + "' and '" + fallback
                    + "') are already in use in the Spring application context.");
        }

        private static boolean isBeanNameTaken(BeanDefinitionRegistry registry, String candidate) {
            if (registry.containsBeanDefinition(candidate) || registry.isAlias(candidate)) {
                return true;
            }
            return registry instanceof SingletonBeanRegistry sbr && sbr.containsSingleton(candidate);
        }
    }
}
