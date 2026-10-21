package com.sondertara.joya.spring.autoconfigure;

import com.sondertara.joya.core.JoyaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 多数据源 {@link JoyaClient} 注册表：主库（默认名称 {@value #PRIMARY}）与扩展库完全同构存储。
 *
 * <pre>{@code
 * // 1. 主库操作（等价于 get("primary")）
 * joyaRegistry.primary().from(UserAccount.class).where(...).fetch();
 * joyaRegistry.primary().mapper(UserAccountMapper.class).findByUserName("Alice");
 *
 * // 2. 扩展库操作（名称对应 joya.datasources.<name>）
 * joyaRegistry.get("order").from(UserAccount.class).where(...).fetch();
 * joyaRegistry.get("order").mapper(UserAccountMapper.class).findByUserName("Alice");
 *
 * // 3. 动态路由与降级
 * JoyaClient client = joyaRegistry.find(tenantKey).orElseGet(joyaRegistry::primary);
 * }</pre>
 */
public final class JoyaClientRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JoyaClientRegistry.class);

    public static final String PRIMARY = "primary";

    private final String primaryName;
    private final Map<String, JoyaClient> clients;
    private final List<AutoCloseable> managedResources;

    public JoyaClientRegistry(Map<String, JoyaClient> clients) {
        this(PRIMARY, clients, List.of());
    }

    public JoyaClientRegistry(String primaryName, Map<String, JoyaClient> clients) {
        this(primaryName, clients, List.of());
    }

    JoyaClientRegistry(String primaryName, Map<String, JoyaClient> clients, List<AutoCloseable> managedResources) {
        Objects.requireNonNull(primaryName, "primaryName must not be null");
        Objects.requireNonNull(clients, "clients must not be null");
        if (clients.isEmpty()) {
            throw new IllegalArgumentException("clients must not be empty");
        }
        if (!clients.containsKey(primaryName)) {
            throw new IllegalArgumentException(
                    "Primary client '" + primaryName + "' is not present in registered clients: " + clients.keySet());
        }
        this.primaryName = primaryName;
        this.clients = Collections.unmodifiableMap(new LinkedHashMap<>(clients));
        this.managedResources = managedResources == null ? List.of() : List.copyOf(managedResources);
    }

    /**
     * 获取主数据源对应的 {@link JoyaClient}（即 {@code get(primaryName)}）。
     */
    public JoyaClient primary() {
        return get(primaryName);
    }

    /**
     * 按注册名称精确获取 {@link JoyaClient}（如 {@code "primary"}、{@code "order"}）。
     *
     * @throws IllegalArgumentException 当指定名称未注册时抛出，异常信息包含当前所有可用名称
     */
    public JoyaClient get(String name) {
        return find(name).orElseThrow(() -> new IllegalArgumentException(
                "No JoyaClient registered for name '" + name + "'. Available clients: " + clients.keySet()));
    }

    /**
     * 按注册名称安全查找 {@link JoyaClient}，适合动态路由与降级场景。
     */
    public Optional<JoyaClient> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(clients.get(name));
    }

    /**
     * 返回所有已注册的客户端名称集合（只读，如 {@code ["primary", "order"]}）。
     */
    public Set<String> names() {
        return clients.keySet();
    }

    @Override
    public void close() {
        for (AutoCloseable resource : managedResources) {
            try {
                resource.close();
            } catch (Exception e) {
                log.warn("Failed to close Joya managed DataSource resource", e);
            }
        }
    }
}
