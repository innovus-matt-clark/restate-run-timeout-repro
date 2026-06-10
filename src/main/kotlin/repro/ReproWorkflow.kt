package repro

import dev.restate.sdk.annotation.Workflow
import dev.restate.sdk.common.TerminalException
import dev.restate.sdk.kotlin.WorkflowContext
import dev.restate.sdk.kotlin.awakeable
import dev.restate.sdk.kotlin.resolve
import dev.restate.sdk.kotlin.runAsync
import kotlin.time.Duration.Companion.seconds

/**
 * Minimal repro for: a `ctx.runAsync` (run) future returns a corrupted value / deadlocks when awaited
 * through `withTimeout(...)` or `await(duration)`, while `ctx.awakeable` (and `ctx.call`) futures behave
 * correctly under the same combinators.
 *
 * Every run block completes immediately, so no timeout should ever fire — the expectation in every case
 * is simply the run block's own return value.
 */
@Workflow
class ReproWorkflow {

    @Workflow
    suspend fun run(ctx: WorkflowContext, case: String): String = when (case) {
        // CONTROL: bare run future, no timeout combinator. Works.
        "bare-run" ->
            ctx.runAsync("work") { "value" }.await()

        // CONTROL: awakeable future through the same withTimeout combinator. Works.
        "awakeable-with-timeout" -> {
            val awakeable = ctx.awakeable<String>()
            ctx.awakeableHandle(awakeable.id).resolve("value")
            awakeable.withTimeout(5.seconds).await()
        }

        // BUG: the same run future as "bare-run", awaited through withTimeout.
        "run-with-timeout" ->
            ctx.runAsync("work") { "value" }.withTimeout(5.seconds).await()

        // BUG: same shape via the await(duration) overload.
        "run-await-duration" ->
            ctx.runAsync("work") { "value" }.await(5.seconds)

        // BUG: two timeout-armed run futures awaited sequentially (the fan-out shape) — deadlocks.
        "two-runs-with-timeout" -> {
            val first = ctx.runAsync("work-0") { "v0" }.withTimeout(5.seconds)
            val second = ctx.runAsync("work-1") { "v1" }.withTimeout(5.seconds)
            first.await() + "," + second.await()
        }

        else -> throw TerminalException("unknown case: $case")
    }
}
