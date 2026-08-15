package com.hkjc.training.betting.configuration

import jakarta.servlet.DispatcherType
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * One trace identifier per request, published through the MDC so every log line carries it.
 * Ordered before the security chain so 401 and 403 responses are correlated too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TraceIdFilter : OncePerRequestFilter() {
    // Not the inherited `logger`: that one is Commons Logging, without SLF4J placeholders.
    private val log = LoggerFactory.getLogger(TraceIdFilter::class.java)

    /**
     * Spring MVC finishes an asynchronous handler on a second, ASYNC dispatch. Filtering that
     * dispatch too keeps the identifier available to `@RestControllerAdvice`, which would
     * otherwise run after the original thread already cleared the MDC.
     */
    override fun shouldNotFilterAsyncDispatch(): Boolean = false

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        // Reuse the initial dispatch's value; regenerating would contradict the response header.
        val traceId =
            request.getAttribute(TRACE_ID_ATTRIBUTE) as? String
                ?: request.getHeader(TRACE_ID_HEADER)?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString()
        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId)

        MDC.put(TRACE_ID_KEY, traceId)
        response.setHeader(TRACE_ID_HEADER, traceId)
        try {
            if (request.dispatcherType == DispatcherType.REQUEST) {
                log.info("request received method={} path={}", request.method, request.requestURI)
            }
            filterChain.doFilter(request, response)
        } finally {
            // Containers reuse threads; a stale value would leak into the next request.
            MDC.remove(TRACE_ID_KEY)
        }
    }

    companion object {
        const val TRACE_ID_HEADER = "X-Trace-Id"

        private const val TRACE_ID_ATTRIBUTE = "com.hkjc.training.betting.traceId"

        /** Matches the MDC key Micrometer Tracing uses, so the log pattern survives the switch. */
        const val TRACE_ID_KEY = "traceId"

        const val UNKNOWN_TRACE_ID = "no-trace"

        fun currentTraceId(): String = MDC.get(TRACE_ID_KEY) ?: UNKNOWN_TRACE_ID

        /** Runs [block] with [traceId] in the MDC, restoring the previous state afterwards. */
        fun <T> withTraceId(
            traceId: String?,
            block: () -> T,
        ): T {
            val previous = MDC.get(TRACE_ID_KEY)
            MDC.put(TRACE_ID_KEY, traceId?.takeIf { it.isNotBlank() } ?: UNKNOWN_TRACE_ID)
            try {
                return block()
            } finally {
                if (previous == null) MDC.remove(TRACE_ID_KEY) else MDC.put(TRACE_ID_KEY, previous)
            }
        }
    }
}
