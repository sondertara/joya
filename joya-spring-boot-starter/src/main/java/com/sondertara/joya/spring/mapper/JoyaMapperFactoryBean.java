package com.sondertara.joya.spring.mapper;

import com.sondertara.joya.core.JoyaClient;
import com.sondertara.joya.core.mapper.JoyaMapper;
import com.sondertara.joya.spring.autoconfigure.JoyaClientRegistry;
import com.sondertara.joya.utils.Strings;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.BeanFactoryAnnotationUtils;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

import java.util.Objects;

/**
 * 将 {@link JoyaMapper} 子接口适配为 Spring 单例 Bean 的 {@link FactoryBean}：
 * <ul>
 *   <li>类型预测阶段直接通过 {@link #getObjectType()} 与 {@link FactoryBean#OBJECT_TYPE_ATTRIBUTE} 返回接口类型，
 *       不提前触发 {@link JoyaClientRegistry} 或底层 {@code DataSource} 初始化；</li>
 *   <li>获取实例时惰性解析目标 {@link JoyaClient} 并委托 {@link JoyaClient#mapper(Class)} 产出无状态线程安全代理。</li>
 * </ul>
 *
 * @param <M> Mapper 接口类型
 */
public final class JoyaMapperFactoryBean<M extends JoyaMapper<?>>
        implements FactoryBean<M>, BeanFactoryAware, EnvironmentAware {

    private final Class<M> mapperInterface;
    private final String clientName;
    private @Nullable BeanFactory beanFactory;
    private @Nullable Environment environment;

    public JoyaMapperFactoryBean(Class<M> mapperInterface) {
        this(mapperInterface, "");
    }

    public JoyaMapperFactoryBean(Class<M> mapperInterface, @Nullable String clientName) {
        this.mapperInterface = Objects.requireNonNull(mapperInterface, "mapperInterface must not be null");
        if (!mapperInterface.isInterface() || !JoyaMapper.class.isAssignableFrom(mapperInterface)) {
            throw new IllegalArgumentException(
                    "mapperInterface must be a sub-interface of JoyaMapper: " + mapperInterface.getName());
        }
        this.clientName = clientName == null ? "" : clientName.trim();
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public M getObject() {
        return resolveJoyaClient().mapper(mapperInterface);
    }

    @Override
    public Class<?> getObjectType() {
        return mapperInterface;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }

    public String getClientName() {
        return clientName;
    }

    private JoyaClient resolveJoyaClient() {
        BeanFactory bf = this.beanFactory;
        if (bf == null) {
            throw new IllegalStateException(
                    "BeanFactory has not been injected into JoyaMapperFactoryBean for " + mapperInterface.getName());
        }
        if (clientName.isEmpty()) {
            return resolvePrimaryClient(bf);
        }
        JoyaClientRegistry registry = bf.getBeanProvider(JoyaClientRegistry.class).getIfAvailable();
        if (registry != null) {
            var registered = registry.find(clientName);
            if (registered.isPresent()) {
                return registered.get();
            }
        }
        try {
            return BeanFactoryAnnotationUtils.qualifiedBeanOfType(bf, JoyaClient.class, clientName);
        } catch (NoSuchBeanDefinitionException ex) {
            if (registry != null) {
                return registry.get(clientName);
            }
            if (clientName.equals(resolvePrimaryName())) {
                return resolvePrimaryClient(bf);
            }
            throw new IllegalStateException("No JoyaClient found with name/qualifier '" + clientName
                    + "' for mapper " + mapperInterface.getName(), ex);
        }
    }

    private JoyaClient resolvePrimaryClient(BeanFactory bf) {
        try {
            return bf.getBean(JoyaClient.class);
        } catch (NoSuchBeanDefinitionException ex) {
            JoyaClientRegistry registry = bf.getBeanProvider(JoyaClientRegistry.class).getIfAvailable();
            if (registry != null) {
                return registry.primary();
            }
            throw new IllegalStateException("No primary JoyaClient bean found in Spring context for mapper "
                    + mapperInterface.getName() + "; ensure DataSource / JoyaAutoConfiguration is active or "
                    + "specify @Qualifier(\"<client>\") / @JoyaMapperScan(client = \"<client>\").", ex);
        }
    }

    private String resolvePrimaryName() {
        if (environment == null) {
            return JoyaClientRegistry.PRIMARY;
        }
        String configured = environment.getProperty("joya.primary.name", "");
        return Strings.isBlank(configured) ? JoyaClientRegistry.PRIMARY : configured.trim();
    }
}
