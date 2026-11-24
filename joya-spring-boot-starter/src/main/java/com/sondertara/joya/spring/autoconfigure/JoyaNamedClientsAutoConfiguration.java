package com.sondertara.joya.spring.autoconfigure;

import com.sondertara.joya.core.JoyaClient;
import com.sondertara.joya.spring.support.JoyaManagedTransactionManager;
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
import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ConfigurationCondition;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AbstractTransactionManagementConfiguration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 为 {@code joya.datasources} 中配置的每个命名扩展库按需注册 {@link JoyaClient} Bean（携带 {@link Qualifier}，
 * 绝不标记 {@code @Primary}，采用 {@code factoryBeanName + factoryMethodName} 标准声明以原生兼容 Spring Boot 3 AOT，
 * 并与同名 {@link DataSource} Bean 避让命名冲突），使业务代码可直接 {@code @Qualifier("<name>") JoyaClient} 注入。
 */
@AutoConfiguration(
        after = JoyaAutoConfiguration.class,
        afterName = "org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration"
)
@ConditionalOnClass({DataSource.class, JoyaClient.class})
@ConditionalOnProperty(prefix = "joya", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JoyaNamedClientsAutoConfiguration {

    @Bean
    public static BeanDefinitionRegistryPostProcessor joyaNamedClientRegistrar(Environment environment) {
        return new NamedClientRegistrar(environment);
    }

    /**
     * 兜底事务代理启用：当容器无主库 {@code TransactionManager}（导致 {@code TransactionAutoConfiguration} 退让），
     * 但自建扩展库或显式配置了 {@code expose-transaction-manager=true} 时，自动启用 {@link EnableTransactionManagement}，
     * 避免 {@code @Transactional("<name>TransactionManager")} 静默无代理。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(EnableTransactionManagement.class)
    @ConditionalOnMissingBean(AbstractTransactionManagementConfiguration.class)
    @Conditional(OnAnySelfManagedExposeTxManagerCondition.class)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class SidecarTransactionManagementConfiguration {
    }

    static final class OnAnySelfManagedExposeTxManagerCondition extends SpringBootCondition
            implements ConfigurationCondition {

        @Override
        public ConfigurationPhase getConfigurationPhase() {
            return ConfigurationPhase.REGISTER_BEAN;
        }

        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            JoyaProperties properties = Binder.get(context.getEnvironment())
                    .bind("joya", JoyaProperties.class)
                    .orElse(null);
            if (properties == null || !properties.isEnabled()) {
                return ConditionOutcome.noMatch(ConditionMessage.forCondition("JoyaSidecarTxManagement")
                        .because("joya is not configured or disabled"));
            }
            JoyaProperties.PrimaryDataSourceProperties primary = properties.getPrimary();
            if (!primary.isUseSpring()
                    && Strings.isBlank(primary.getDataSourceBean())
                    && Boolean.TRUE.equals(primary.getExposeTransactionManager())) {
                return ConditionOutcome.match(ConditionMessage.forCondition("JoyaSidecarTxManagement")
                        .found("self-managed primary expose-transaction-manager=true").atAll());
            }
            for (JoyaProperties.JoyaDataSourceProperties ds : properties.getDatasources()) {
                if (Strings.isBlank(ds.getDataSourceBean())
                        && Boolean.TRUE.equals(ds.getExposeTransactionManager())) {
                    return ConditionOutcome.match(ConditionMessage.forCondition("JoyaSidecarTxManagement")
                            .found("self-managed sidecar expose-transaction-manager=true").items(ds.getName()));
                }
            }
            return ConditionOutcome.noMatch(ConditionMessage.forCondition("JoyaSidecarTxManagement")
                    .didNotFind("any self-managed datasource with expose-transaction-manager=true").atAll());
        }
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
            if (properties != null && !properties.isEnabled()) {
                return;
            }
            String primaryName = (properties == null || Strings.isBlank(properties.getPrimary().getName()))
                    ? JoyaClientRegistry.PRIMARY
                    : properties.getPrimary().getName().trim();
            qualifyAutoConfiguredPrimaryClient(registry, primaryName);
            qualifyAutoConfiguredPrimaryTxManager(registry, primaryName);

            String registryBeanName = (registryBeanNames != null && registryBeanNames.length > 0)
                    ? registryBeanNames[0]
                    : "joyaClientRegistry";

            // 若自建主库显式开启 expose-transaction-manager=true，但容器已有其他外部 TM 导致主 transactionManager 退让，
            // 则在保护已有唯一默认 TM 为 @Primary 后，为主库补注命名非 @Primary 事务管理器。
            if (properties != null) {
                JoyaProperties.PrimaryDataSourceProperties primaryProps = properties.getPrimary();
                if (!primaryProps.isUseSpring()
                        && Strings.isBlank(primaryProps.getDataSourceBean())
                        && Boolean.TRUE.equals(primaryProps.getExposeTransactionManager())
                        && !hasAutoConfiguredPrimaryTxManager(registry)) {
                    promoteUniqueDefaultTransactionManagerToPrimary(registry);
                    registerNamedTransactionManager(registry, registryBeanName, primaryName);
                }
            }

            if (properties == null || properties.getDatasources().isEmpty()) {
                return;
            }
            Set<String> seen = new HashSet<>();
            seen.add(primaryName);

            for (JoyaProperties.JoyaDataSourceProperties dsProps : properties.getDatasources()) {
                String name = dsProps.getName() == null ? "" : dsProps.getName().trim();
                if (name.isEmpty() || !seen.add(name)) {
                    continue;
                }
                if (!hasExistingNamedClient(registry, existingClientBeans, name)) {
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
                if (Strings.isBlank(dsProps.getDataSourceBean())
                        && Boolean.TRUE.equals(dsProps.getExposeTransactionManager())) {
                    promoteUniqueDefaultTransactionManagerToPrimary(registry);
                    registerNamedTransactionManager(registry, registryBeanName, name);
                }
            }
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
            // BeanDefinition 已在 postProcessBeanDefinitionRegistry 阶段完成注册
        }

        /**
         * 为自动装配的主库 {@code joyaClient} 补齐 {@code @Qualifier(primaryName)}（保留 {@code @Primary}），
         * 使 {@code @Autowired JoyaClient} 与 {@code @Qualifier("primary") JoyaClient} 对称可用。
         */
        private static void qualifyAutoConfiguredPrimaryClient(BeanDefinitionRegistry registry, String primaryName) {
            if (!registry.containsBeanDefinition("joyaClient")) {
                return;
            }
            BeanDefinition bd = registry.getBeanDefinition("joyaClient");
            if (!(bd instanceof AbstractBeanDefinition abd) || !abd.isPrimary()) {
                return;
            }
            MethodMetadata factoryMethod = (abd instanceof AnnotatedBeanDefinition annotatedBd)
                    ? annotatedBd.getFactoryMethodMetadata()
                    : null;
            boolean fromJoyaAutoConfig = factoryMethod != null
                    ? JoyaAutoConfiguration.class.getName().equals(factoryMethod.getDeclaringClassName())
                    : JoyaAutoConfiguration.class.getName().equals(abd.getFactoryBeanName());
            if (!fromJoyaAutoConfig) {
                return;
            }
            if (!JoyaExtensionPointResolver.extractQualifiers(abd).values().contains(primaryName)) {
                abd.addQualifier(new AutowireCandidateQualifier(Qualifier.class, primaryName));
            }
        }

        private static boolean hasAutoConfiguredPrimaryTxManager(BeanDefinitionRegistry registry) {
            if (!registry.containsBeanDefinition("transactionManager")
                    || !(registry instanceof ListableBeanFactory lbf)) {
                return false;
            }
            return JoyaTransactionManagerResolver.isJoyaManagedTransactionManager(lbf, "transactionManager");
        }

        private static void qualifyAutoConfiguredPrimaryTxManager(BeanDefinitionRegistry registry, String primaryName) {
            if (!hasAutoConfiguredPrimaryTxManager(registry)) {
                return;
            }
            BeanDefinition bd = registry.getBeanDefinition("transactionManager");
            if (bd instanceof AbstractBeanDefinition abd
                    && !JoyaExtensionPointResolver.extractQualifiers(abd).values().contains(primaryName)) {
                abd.addQualifier(new AutowireCandidateQualifier(Qualifier.class, primaryName));
            }
        }

        /**
         * Spring Boot 默认 {@code DataSourceTransactionManagerAutoConfiguration} 注册的 {@code transactionManager}
         * 不带 {@code @Primary}；在新增旁路非主事务管理器前，若容器尚无任何 {@code @Primary} 事务管理器且仅存在唯一默认
         * 外部事务管理器，先将其提升为 {@code @Primary}，确保业务代码原有的无限定 {@code @Transactional} 绝不因多 Bean 冲突报错。
         */
        private static void promoteUniqueDefaultTransactionManagerToPrimary(BeanDefinitionRegistry registry) {
            if (!(registry instanceof ListableBeanFactory lbf)) {
                return;
            }
            String[] tmBeanNames = lbf.getBeanNamesForType(PlatformTransactionManager.class, true, false);
            List<String> externalCandidates = new ArrayList<>(tmBeanNames.length);
            for (String beanName : tmBeanNames) {
                if (!registry.containsBeanDefinition(beanName)) {
                    continue;
                }
                BeanDefinition bd = registry.getBeanDefinition(beanName);
                if (bd.isPrimary()) {
                    return;
                }
                if (!JoyaTransactionManagerResolver.isJoyaManagedTransactionManager(lbf, beanName)) {
                    externalCandidates.add(beanName);
                }
            }
            if (externalCandidates.size() == 1) {
                registry.getBeanDefinition(externalCandidates.get(0)).setPrimary(true);
            }
        }

        private static void registerNamedTransactionManager(BeanDefinitionRegistry registry,
                                                            String registryBeanName,
                                                            String datasourceName) {
            String beanName = resolveUniqueTxManagerBeanName(registry, datasourceName);
            RootBeanDefinition bd = new RootBeanDefinition(JoyaManagedTransactionManager.class);
            bd.setPrimary(false);
            bd.setFactoryBeanName(registryBeanName);
            bd.setFactoryMethodName("transactionManager");
            bd.getConstructorArgumentValues().addIndexedArgumentValue(0, datasourceName);
            bd.setDependsOn(registryBeanName);
            bd.addQualifier(new AutowireCandidateQualifier(Qualifier.class, datasourceName));
            registry.registerBeanDefinition(beanName, bd);
        }

        private static String resolveUniqueTxManagerBeanName(BeanDefinitionRegistry registry, String datasourceName) {
            String primaryCandidate = datasourceName + "TransactionManager";
            if (!isBeanNameTaken(registry, primaryCandidate)) {
                return primaryCandidate;
            }
            String fallback = datasourceName + "JoyaTransactionManager";
            if (!isBeanNameTaken(registry, fallback)) {
                return fallback;
            }
            throw new IllegalStateException("Cannot register named PlatformTransactionManager bean for datasource '"
                    + datasourceName + "': both candidate bean names ('" + primaryCandidate + "' and '" + fallback
                    + "') are already in use in the Spring application context.");
        }

        private static boolean hasExistingNamedClient(BeanDefinitionRegistry registry,
                                                      String[] existingClientBeans,
                                                      String targetName) {
            for (String beanName : existingClientBeans) {
                if (beanName.equals(targetName)) {
                    return true;
                }
                if (registry.containsBeanDefinition(beanName)
                        && JoyaExtensionPointResolver.extractQualifiers(registry.getBeanDefinition(beanName))
                                .values()
                                .contains(targetName)) {
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
