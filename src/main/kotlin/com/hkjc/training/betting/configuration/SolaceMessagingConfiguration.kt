package com.hkjc.training.betting.configuration

import com.hkjc.training.betting.configuration.TraceIdFilter
import org.slf4j.LoggerFactory
import org.springframework.cloud.stream.function.StreamBridge
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.messaging.Message
import org.springframework.messaging.support.MessageBuilder
import org.springframework.stereotype.Component
import java.util.function.Consumer
import com.hkjc.training.betting.messaging.BetPlacedEvent
import com.hkjc.training.betting.messaging.NotificationBetPlacedConsumer


@Configuration(proxyBeanMethods = false)
@Profile("solace")
class SolaceMessagingConfiguration {
    /** Takes the whole [Message] because the broker's consumer thread starts with an empty MDC. */
    @Bean
    fun notifyBetPlaced(notificationConsumer: NotificationBetPlacedConsumer): Consumer<Message<BetPlacedEvent>> =
        Consumer { message ->
            val traceId = message.headers[TraceIdFilter.TRACE_ID_HEADER] as? String
            TraceIdFilter.withTraceId(traceId) { notificationConsumer.accept(message.payload) }
        }
}
