package com.hkjc.training.betting.exception

import com.hkjc.training.betting.configuration.TraceIdFilter
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import java.time.Instant

@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {
    @ExceptionHandler(GameNotFoundException::class)
    fun handleGameNotFound(
        exception: GameNotFoundException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiError> =
        errorResponse(
            status = HttpStatus.NOT_FOUND,
            code = "GAME_NOT_FOUND",
            message = exception.message ?: "Game was not found",
            request = request,
        )

    /**
     * Without this handler the exception escapes to the servlet container, which logs it after
     * the filter cleared the MDC — leaving the line with no trace identifier.
     */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(
        exception: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ApiError> {
        log.error("request failed path={} reason={}", request.requestURI, exception.toString(), exception)
        return errorResponse(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            code = "INTERNAL_ERROR",
            // Generic on purpose: exception text leaks internal addresses.
            message = "The request could not be completed",
            request = request,
        )
    }

    private fun errorResponse(
        status: HttpStatus,
        code: String,
        message: String,
        request: HttpServletRequest,
    ): ResponseEntity<ApiError> =
        ResponseEntity.status(status).body(
            ApiError(
                timestamp = Instant.now(),
                status = status.value(),
                code = code,
                message = message,
                path = request.requestURI,
                traceId = TraceIdFilter.currentTraceId(),
            ),
        )

    private companion object {
        private val log = LoggerFactory.getLogger(ApiExceptionHandler::class.java)
    }
}
