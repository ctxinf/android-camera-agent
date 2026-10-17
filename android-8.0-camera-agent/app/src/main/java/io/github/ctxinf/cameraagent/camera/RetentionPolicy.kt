package io.github.ctxinf.cameraagent.camera

enum class RetentionDecision {
    CLOSE_IMMEDIATELY,
    RETAIN,
}

data class RetentionInput(
    val activeConsumers: Int,
    val idleDurationMs: Long,
)

interface RetentionPolicy {
    fun decide(input: RetentionInput): RetentionDecision
    val gracePeriodMs: Long get() = 0L
}

/**
 * Phase 1 policy: Close immediately when no active consumers.
 */
class AlwaysClosePolicy : RetentionPolicy {
    override fun decide(input: RetentionInput): RetentionDecision {
        return if (input.activeConsumers <= 0) {
            RetentionDecision.CLOSE_IMMEDIATELY
        } else {
            RetentionDecision.RETAIN
        }
    }
}

/**
 * Keeps camera session open and active for [gracePeriodMs] after last consumer completes,
 * avoiding hardware churn, keeping 3A (AE/AF) converged, and enabling instant subsequent snapshots.
 */
class GracePeriodRetentionPolicy(
    private val getGracePeriodMs: () -> Long,
) : RetentionPolicy {
    constructor(gracePeriodMs: Long = 5000L) : this({ gracePeriodMs })

    override val gracePeriodMs: Long
        get() = getGracePeriodMs()

    override fun decide(input: RetentionInput): RetentionDecision {
        val currentGrace = gracePeriodMs
        return if (input.activeConsumers <= 0) {
            if (currentGrace <= 0L || input.idleDurationMs >= currentGrace) {
                RetentionDecision.CLOSE_IMMEDIATELY
            } else {
                RetentionDecision.RETAIN
            }
        } else {
            RetentionDecision.RETAIN
        }
    }
}
