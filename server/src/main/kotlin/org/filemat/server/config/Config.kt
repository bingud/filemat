package org.filemat.server.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate


@Configuration
class Config {

    @Bean
    fun passwordEncoder(): PasswordEncoder {
        return Argon2PasswordEncoder(16, 32, 2, 65536, 5)
    }

}

@Configuration
class TransactionTemplateConfig(
) {

    companion object {
        lateinit var instance: TransactionTemplate
    }

    @Bean
    fun transactionTemplate(transactionManager: PlatformTransactionManager): TransactionTemplate {
        instance = TransactionTemplate(transactionManager)
        return instance
    }

}