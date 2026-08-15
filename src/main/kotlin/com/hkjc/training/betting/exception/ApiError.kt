package com.hkjc.training.betting.exception

import java.time.Instant

/** Clients branch on [code], never on [message] text. */
data class ApiError(
    val timestamp: Instant,
    val status: Int,
    val code: String,
    val message: String,
    val path: String,
    val traceId: String,
)
