package com.sondertara.joya.spring.annotation;

import com.sondertara.joya.spring.mapper.JoyaMapperScannerRegistrar;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 按需扫描并注册 {@link com.sondertara.joya.core.mapper.JoyaMapper} 子接口为 Spring 单例 Bean，
 * 使业务层可直接通过 {@code @Autowired UserMapper} 注入使用。
 *
 * <p>Joya 默认保持零扫描开销（随时可通过 {@code joya.mapper(UserMapper.class)} 或 {@code tx.mapper(UserMapper.class)} 获取）；
 * 仅当配置类或启动类显式标注本注解时才触发类路径接口扫描。
 *
 * <p><b>多数据源绑定规则（优先级从高到低）</b>：
 * <ol>
 *   <li>Mapper 接口自身标注的 {@link org.springframework.beans.factory.annotation.Qualifier @Qualifier("dsName")}；</li>
 *   <li>本注解的 {@link #client()} 属性（支持通过 {@code @Repeatable} 按不同包绑定不同数据源）；</li>
 *   <li>默认主库 {@link com.sondertara.joya.core.JoyaClient}。</li>
 * </ol>
 *
 * <p><b>事务协作说明</b>：在复用 Spring 事务管理器的主库或容器数据源上，注入的 Mapper Bean 会通过
 * {@code SpringConnectionProvider} 自动参与 {@code @Transactional}（及 {@code SpringTxEngine} 托管的 {@code joya.tx(...)}）；
 * 对于自建旁路连接池的纯 JDBC 显式值事务，请在事务闭包内使用 {@code tx.mapper(OrderMapper.class)}。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
@Repeatable(JoyaMapperScans.class)
@Import(JoyaMapperScannerRegistrar.class)
public @interface JoyaMapperScan {

    /**
     * 要扫描的包路径（{@link #basePackages()} 的别名）。
     */
    @AliasFor("basePackages")
    String[] value() default {};

    /**
     * 要扫描的包路径。当 {@code value}、{@code basePackages} 与 {@code basePackageClasses}
     * 均未指定时，默认扫描标注本注解的配置类所在包。
     */
    @AliasFor("value")
    String[] basePackages() default {};

    /**
     * 类型安全的基准包锚点类（扫描各锚点类所在的包）。
     */
    Class<?>[] basePackageClasses() default {};

    /**
     * 绑定的目标数据源客户端名称（对应 {@code joya.primary.name} 或 {@code joya.datasources[].name}）。
     * 默认空字符串表示绑定主库 {@link com.sondertara.joya.core.JoyaClient}。
     */
    String client() default "";
}
