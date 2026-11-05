package com.chatapp.status.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * General wiring: our settings, the scheduled clean-up job and the transaction helper.
 *
 * Transactions use READ COMMITTED: the clean-up job locks exactly the expired rows it deletes, and MySQL's default
 * (REPEATABLE READ) would also lock "gaps" between rows, which only causes needless waiting here.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(AppProperties.class)
public class AppConfig {

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }
}
