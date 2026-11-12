package com.sondertara.joya.spring.autoconfigure;

import com.sondertara.joya.spring.support.JpaTransactionManagerSupport;
import com.sondertara.joya.spring.support.SpringTxEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.ResourceTransactionManager;

import javax.sql.DataSource;
import java.util.List;

/**
 * 主库 Spring 事务管理器解析：精确匹配优先、歧义失败、JPA 只读校验（绝不改写用户 Bean）。
 */
final class JoyaTransactionManagerResolver {

    private static final Logger log = LoggerFactory.getLogger(JoyaTransactionManagerResolver.class);

    private JoyaTransactionManagerResolver() {
    }

    /**
     * 解析规则：
     * <ol>
     *   <li>{@link ResourceTransactionManager#getResourceFactory()} 与主 DataSource 精确匹配者优先，多个匹配视为配置歧义；</li>
     *   <li>其次接受已显式绑定同一 DataSource 的 {@code JpaTransactionManager}；</li>
     *   <li>未绑定 / 不匹配 / JTA 明确告警或失败，返回 {@code null} 表示回退纯 JDBC 事务引擎。</li>
     * </ol>
     */
    static PlatformTransactionManager resolve(DataSource primaryDataSource,
                                              ObjectProvider<PlatformTransactionManager> txManagerProvider) {
        List<PlatformTransactionManager> managers = txManagerProvider.orderedStream().toList();
        if (managers.isEmpty()) {
            log.info("No Spring PlatformTransactionManager found; Joya uses its plain JDBC transaction engine "
                    + "and @Transactional connection sharing is disabled.");
            return null;
        }

        List<PlatformTransactionManager> exact = managers.stream()
                .filter(tm -> tm instanceof ResourceTransactionManager rtm
                        && SpringTxEngine.matchesDataSource(rtm.getResourceFactory(), primaryDataSource))
                .toList();
        if (exact.size() > 1) {
            throw new IllegalStateException("Multiple Spring transaction managers match the primary DataSource ("
                    + primaryDataSource + "): " + exact + ". Configure a single @Primary transaction manager.");
        }
        if (exact.size() == 1) {
            return exact.get(0);
        }

        List<PlatformTransactionManager> jpaBound = managers.stream()
                .filter(JpaTransactionManagerSupport::isJpa)
                .filter(tm -> {
                    DataSource jpaDataSource = JpaTransactionManagerSupport.dataSource(tm);
                    return jpaDataSource != null
                            && SpringTxEngine.matchesDataSource(jpaDataSource, primaryDataSource);
                })
                .toList();
        if (jpaBound.size() > 1) {
            throw new IllegalStateException("Multiple JpaTransactionManagers are bound to the primary DataSource ("
                    + primaryDataSource + "): " + jpaBound + ". Configure a single one.");
        }
        if (jpaBound.size() == 1) {
            return jpaBound.get(0);
        }

        for (PlatformTransactionManager tm : managers) {
            if (JpaTransactionManagerSupport.isJta(tm)) {
                throw new IllegalStateException(
                        "JTA transactions are not supported by Joya; configure a DataSourceTransactionManager "
                                + "or a JpaTransactionManager with setDataSource(...) instead of "
                                + tm.getClass().getName());
            }
            if (JpaTransactionManagerSupport.isJpa(tm)) {
                log.warn("Found JpaTransactionManager [{}] without a DataSource bound to the primary DataSource; "
                                + "Joya will not share @Transactional connections with it. Configure it with "
                                + "setDataSource(primaryDataSource) if connection sharing is required.",
                        tm.getClass().getName());
            } else {
                log.warn("Found Spring PlatformTransactionManager [{}] whose resourceFactory does not match primary DataSource; "
                                + "Joya will use a plain JDBC TxEngine and @Transactional connection sharing is disabled. "
                                + "Configure a DataSourceTransactionManager bound to the primary DataSource.",
                        tm.getClass().getName());
            }
        }
        return null;
    }
}
