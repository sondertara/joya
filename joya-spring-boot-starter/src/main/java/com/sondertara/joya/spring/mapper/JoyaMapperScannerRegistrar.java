package com.sondertara.joya.spring.mapper;

import com.sondertara.joya.core.mapper.JoyaMapper;
import com.sondertara.joya.spring.annotation.JoyaMapperScan;
import com.sondertara.joya.spring.annotation.JoyaMapperScans;
import com.sondertara.joya.spring.autoconfigure.JoyaClientRegistry;
import com.sondertara.joya.utils.Strings;
import com.sondertara.joya.utils.TypeUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.SingletonBeanRegistry;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.beans.Introspector;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 处理 {@link JoyaMapperScan} / {@link JoyaMapperScans} 的包扫描注册器：
 * <ul>
 *   <li>在 {@code ImportBeanDefinitionRegistrar} 阶段仅收集扫描指令并注册基础设施级
 *       {@link BeanDefinitionRegistryPostProcessor}，确保在全应用所有 {@code @Configuration} 的 {@code @Bean}
 *       注册完成后再统一扫描，避免子配置类声明同名 {@code @Bean UserMapper} 时触发覆盖冲突；</li>
 *   <li>仅接纳具化了实体类型参数的独立 {@link JoyaMapper} 子接口（自动忽略泛型中间父接口与实现类）；</li>
 *   <li>主库 Mapper 同步赋予 {@code @Primary} 与 {@code @Qualifier(primaryName)}，与 {@code JoyaClient} 对称；</li>
 *   <li>采用构造器参数 + {@link FactoryBean#OBJECT_TYPE_ATTRIBUTE} 注册 {@link JoyaMapperFactoryBean}，
 *       原生兼容 Spring Boot 3 AOT 且不在扫描阶段实例化任何业务或数据源 Bean。</li>
 * </ul>
 */
public final class JoyaMapperScannerRegistrar implements ImportBeanDefinitionRegistrar {

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata,
                                        BeanDefinitionRegistry registry,
                                        BeanNameGenerator importBeanNameGenerator) {
        int index = 0;
        AnnotationAttributes direct = AnnotationAttributes.fromMap(
                importingClassMetadata.getAnnotationAttributes(JoyaMapperScan.class.getName()));
        if (direct != null) {
            registerScannerConfigurer(registry, importingClassMetadata.getClassName(), index++, direct);
        }
        AnnotationAttributes container = AnnotationAttributes.fromMap(
                importingClassMetadata.getAnnotationAttributes(JoyaMapperScans.class.getName()));
        if (container != null) {
            for (AnnotationAttributes nested : container.getAnnotationArray("value")) {
                registerScannerConfigurer(registry, importingClassMetadata.getClassName(), index++, nested);
            }
        }
    }

    private static void registerScannerConfigurer(BeanDefinitionRegistry registry,
                                                  String importingClassName,
                                                  int scanIndex,
                                                  AnnotationAttributes scanAnnotation) {
        Set<String> rawPackages = new LinkedHashSet<>();
        for (String pkg : scanAnnotation.getStringArray("value")) {
            if (StringUtils.hasText(pkg)) {
                rawPackages.add(pkg.trim());
            }
        }
        for (String pkg : scanAnnotation.getStringArray("basePackages")) {
            if (StringUtils.hasText(pkg)) {
                rawPackages.add(pkg.trim());
            }
        }
        for (Class<?> anchor : scanAnnotation.getClassArray("basePackageClasses")) {
            String pkg = ClassUtils.getPackageName(anchor);
            if (StringUtils.hasText(pkg)) {
                rawPackages.add(pkg);
            }
        }
        if (rawPackages.isEmpty()) {
            String defaultPkg = ClassUtils.getPackageName(importingClassName);
            if (StringUtils.hasText(defaultPkg)) {
                rawPackages.add(defaultPkg);
            }
        }
        if (rawPackages.isEmpty()) {
            return;
        }
        String rawClient = scanAnnotation.getString("client").trim();
        RootBeanDefinition bd = new RootBeanDefinition(JoyaMapperScannerConfigurer.class);
        bd.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
        bd.getConstructorArgumentValues().addIndexedArgumentValue(0, rawPackages.toArray(String[]::new));
        bd.getConstructorArgumentValues().addIndexedArgumentValue(1, rawClient);
        String baseName = importingClassName + "#" + JoyaMapperScannerConfigurer.class.getSimpleName() + "#" + scanIndex;
        String beanName = baseName;
        int suffix = 1;
        while (registry.containsBeanDefinition(beanName)) {
            beanName = baseName + "_" + (suffix++);
        }
        registry.registerBeanDefinition(beanName, bd);
    }

    /**
     * 在所有 {@code @Configuration} 类的 {@code @Bean} 定义注册完毕后执行包扫描的基础设施处理器。
     */
    public static final class JoyaMapperScannerConfigurer
            implements BeanDefinitionRegistryPostProcessor, ResourceLoaderAware, EnvironmentAware {

        private final String[] rawBasePackages;
        private final String rawClient;
        private ResourceLoader resourceLoader;
        private Environment environment = new StandardEnvironment();

        public JoyaMapperScannerConfigurer(String[] rawBasePackages, String rawClient) {
            this.rawBasePackages = rawBasePackages == null ? new String[0] : rawBasePackages.clone();
            this.rawClient = rawClient == null ? "" : rawClient;
        }

        @Override
        public void setResourceLoader(ResourceLoader resourceLoader) {
            this.resourceLoader = resourceLoader;
        }

        @Override
        public void setEnvironment(Environment environment) {
            if (environment != null) {
                this.environment = environment;
            }
        }

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
            Set<String> basePackages = new LinkedHashSet<>();
            for (String raw : rawBasePackages) {
                if (StringUtils.hasText(raw)) {
                    String resolved = environment.resolvePlaceholders(raw.trim()).trim();
                    if (StringUtils.hasText(resolved)) {
                        basePackages.add(resolved);
                    }
                }
            }
            if (basePackages.isEmpty()) {
                return;
            }
            String primaryName = resolvePrimaryName(environment);
            String scanClient = environment.resolvePlaceholders(rawClient).trim();
            ClassPathScanningCandidateComponentProvider scanner = createScanner();
            ClassLoader classLoader = resourceLoader != null
                    ? resourceLoader.getClassLoader()
                    : ClassUtils.getDefaultClassLoader();

            for (String basePackage : basePackages) {
                for (BeanDefinition candidate : scanner.findCandidateComponents(basePackage)) {
                    String className = candidate.getBeanClassName();
                    if (!StringUtils.hasText(className)) {
                        continue;
                    }
                    Class<?> mapperClass = loadClass(className, classLoader);
                    if (!isConcreteJoyaMapperInterface(mapperClass)) {
                        continue;
                    }
                    String effectiveClient = resolveEffectiveClient(mapperClass, scanClient);
                    if (shouldSkipRegistration(registry, mapperClass, effectiveClient, primaryName)) {
                        continue;
                    }
                    registerMapperBeanDefinition(registry, mapperClass, effectiveClient, primaryName);
                }
            }
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
            // BeanDefinition 已在 postProcessBeanDefinitionRegistry 阶段完成注册
        }

        private ClassPathScanningCandidateComponentProvider createScanner() {
            ClassPathScanningCandidateComponentProvider scanner =
                    new ClassPathScanningCandidateComponentProvider(false, environment) {
                        @Override
                        protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                            AnnotationMetadata metadata = beanDefinition.getMetadata();
                            return metadata.isIndependent() && metadata.isInterface();
                        }
                    };
            if (resourceLoader != null) {
                scanner.setResourceLoader(resourceLoader);
            }
            scanner.addIncludeFilter(new AssignableTypeFilter(JoyaMapper.class));
            return scanner;
        }

        private String resolveEffectiveClient(Class<?> mapperClass, String scanClient) {
            MergedAnnotation<Qualifier> qualifier = MergedAnnotations.from(mapperClass).get(Qualifier.class);
            if (qualifier.isPresent()) {
                String ifaceClient = environment.resolvePlaceholders(qualifier.getString("value")).trim();
                if (!ifaceClient.isEmpty()) {
                    return ifaceClient;
                }
            }
            return scanClient;
        }
    }

    private static String resolvePrimaryName(Environment environment) {
        String configured = environment.getProperty("joya.primary.name", "");
        return Strings.isBlank(configured) ? JoyaClientRegistry.PRIMARY : configured.trim();
    }

    private static boolean isPrimaryTarget(String client, String primaryName) {
        return client.isEmpty() || client.equals(primaryName);
    }

    private static Class<?> loadClass(String className, ClassLoader classLoader) {
        try {
            return ClassUtils.forName(className, classLoader);
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("Failed to load scanned JoyaMapper interface: " + className, ex);
        }
    }

    private static boolean isConcreteJoyaMapperInterface(Class<?> candidate) {
        if (candidate == null
                || !candidate.isInterface()
                || candidate == JoyaMapper.class
                || !JoyaMapper.class.isAssignableFrom(candidate)
                || candidate.getTypeParameters().length > 0) {
            return false;
        }
        Class<?> entityClass = TypeUtils.resolveTypeArgument(candidate, JoyaMapper.class);
        return entityClass != null && entityClass != Object.class;
    }

    private static boolean shouldSkipRegistration(BeanDefinitionRegistry registry,
                                                  Class<?> mapperClass,
                                                  String effectiveClient,
                                                  String primaryName) {
        boolean targetsPrimary = isPrimaryTarget(effectiveClient, primaryName);
        String targetQualifier = targetsPrimary ? primaryName : effectiveClient;

        if (registry instanceof ListableBeanFactory lbf) {
            String[] existingNames = lbf.getBeanNamesForType(mapperClass, true, false);
            for (String existingName : existingNames) {
                if (!registry.containsBeanDefinition(existingName)) {
                    return true;
                }
                BeanDefinition existingBd = registry.getBeanDefinition(existingName);
                if (isSameScannedMapper(existingBd, mapperClass, effectiveClient, primaryName)) {
                    return true;
                }
                if (!JoyaMapperFactoryBean.class.getName().equals(existingBd.getBeanClassName())) {
                    Set<String> userQualifiers = extractBeanQualifierValues(existingBd);
                    if (userQualifiers.isEmpty()
                            || existingBd.isPrimary() && targetsPrimary
                            || userQualifiers.contains(targetQualifier)) {
                        return true;
                    }
                }
            }
        } else {
            for (String beanName : registry.getBeanDefinitionNames()) {
                BeanDefinition existingBd = registry.getBeanDefinition(beanName);
                if (isSameScannedMapper(existingBd, mapperClass, effectiveClient, primaryName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<String> extractBeanQualifierValues(BeanDefinition bd) {
        Set<String> values = new LinkedHashSet<>();
        if (bd instanceof AbstractBeanDefinition abd) {
            for (AutowireCandidateQualifier q : abd.getQualifiers()) {
                Object val = q.getAttribute(AutowireCandidateQualifier.VALUE_KEY);
                if (val != null && !val.toString().isBlank()) {
                    values.add(val.toString().trim());
                }
            }
        }
        if (bd instanceof AnnotatedBeanDefinition annotatedBd) {
            MethodMetadata fm = annotatedBd.getFactoryMethodMetadata();
            if (fm != null) {
                MergedAnnotation<Qualifier> q = fm.getAnnotations().get(Qualifier.class);
                if (q.isPresent() && !q.getString("value").isBlank()) {
                    values.add(q.getString("value").trim());
                }
            }
        }
        return values;
    }

    private static boolean isSameScannedMapper(BeanDefinition bd,
                                               Class<?> mapperClass,
                                               String effectiveClient,
                                               String primaryName) {
        if (!JoyaMapperFactoryBean.class.getName().equals(bd.getBeanClassName())) {
            return false;
        }
        ConstructorArgumentValues.ValueHolder typeHolder = bd.getConstructorArgumentValues()
                .getIndexedArgumentValue(0, Class.class);
        ConstructorArgumentValues.ValueHolder clientHolder = bd.getConstructorArgumentValues()
                .getIndexedArgumentValue(1, String.class);
        Object existingType = typeHolder != null ? typeHolder.getValue() : bd.getAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE);
        String existingClient = clientHolder != null && clientHolder.getValue() != null
                ? clientHolder.getValue().toString()
                : "";
        boolean sameType = mapperClass.equals(existingType)
                || mapperClass.getName().equals(existingType);
        if (!sameType) {
            return false;
        }
        if (isPrimaryTarget(existingClient, primaryName) && isPrimaryTarget(effectiveClient, primaryName)) {
            return true;
        }
        return Objects.equals(effectiveClient, existingClient);
    }

    private static boolean hasExistingPrimaryBean(BeanDefinitionRegistry registry, Class<?> mapperClass) {
        if (!(registry instanceof ListableBeanFactory lbf)) {
            return false;
        }
        for (String existingName : lbf.getBeanNamesForType(mapperClass, true, false)) {
            if (registry.containsBeanDefinition(existingName)
                    && registry.getBeanDefinition(existingName).isPrimary()) {
                return true;
            }
        }
        return false;
    }

    private static void registerMapperBeanDefinition(BeanDefinitionRegistry registry,
                                                     Class<?> mapperClass,
                                                     String effectiveClient,
                                                     String primaryName) {
        boolean targetsPrimary = isPrimaryTarget(effectiveClient, primaryName);
        String targetQualifier = targetsPrimary ? primaryName : effectiveClient;
        String beanName = resolveUniqueMapperBeanName(registry, mapperClass, targetsPrimary ? "" : effectiveClient);

        RootBeanDefinition bd = new RootBeanDefinition(JoyaMapperFactoryBean.class);
        bd.setTargetType(ResolvableType.forClassWithGenerics(JoyaMapperFactoryBean.class, mapperClass));
        bd.setAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE, mapperClass);
        bd.getConstructorArgumentValues().addIndexedArgumentValue(0, mapperClass);
        bd.getConstructorArgumentValues().addIndexedArgumentValue(1, effectiveClient);
        bd.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_NO);
        bd.addQualifier(new AutowireCandidateQualifier(Qualifier.class, targetQualifier));
        if (targetsPrimary && !hasExistingPrimaryBean(registry, mapperClass)) {
            bd.setPrimary(true);
        }
        registry.registerBeanDefinition(beanName, bd);
    }

    private static String resolveUniqueMapperBeanName(BeanDefinitionRegistry registry,
                                                      Class<?> mapperClass,
                                                      String disambiguator) {
        String shortName = Introspector.decapitalize(mapperClass.getSimpleName());
        if (!isBeanNameTaken(registry, shortName)) {
            return shortName;
        }
        if (!disambiguator.isEmpty()) {
            String qualifiedShort = shortName + "#" + disambiguator;
            if (!isBeanNameTaken(registry, qualifiedShort)) {
                return qualifiedShort;
            }
        }
        String fqn = mapperClass.getName();
        if (!isBeanNameTaken(registry, fqn)) {
            return fqn;
        }
        if (!disambiguator.isEmpty()) {
            String qualifiedFqn = fqn + "#" + disambiguator;
            if (!isBeanNameTaken(registry, qualifiedFqn)) {
                return qualifiedFqn;
            }
        }
        throw new IllegalStateException("Cannot register JoyaMapper bean for " + mapperClass.getName()
                + " (client='" + disambiguator + "'): all candidate bean names are already taken.");
    }

    private static boolean isBeanNameTaken(BeanDefinitionRegistry registry, String candidate) {
        if (registry.containsBeanDefinition(candidate) || registry.isAlias(candidate)) {
            return true;
        }
        return registry instanceof SingletonBeanRegistry sbr && sbr.containsSingleton(candidate);
    }
}
