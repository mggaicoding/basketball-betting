package com.hkjc.training.betting.configuration

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** Injecting the clock keeps Instant.now() out of business logic, so time is testable. */
@Configuration(proxyBeanMethods = false)
class TimeConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
