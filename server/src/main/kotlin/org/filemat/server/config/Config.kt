package org.filemat.server.config

import me.desair.tus.server.TusFileUploadService
import org.filemat.server.common.State
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import org.springframework.context.annotation.Lazy
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

    @Bean
    @DependsOn("initialization")
    fun tusService(): TusFileUploadService {
        return TusFileUploadService()
            .withUploadUri("/api/v1/file/upload")
            .withStoragePath(State.App.uploadFolderPath)
            // .withUploadExpirationPeriod(7 * 24 * 60 * 60 * 1000)
    }


}