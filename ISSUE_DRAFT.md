<!-- Draft for: https://github.com/restatedev/sdk-java/issues/new -->
<!-- Title: -->
# [Kotlin] `runAsync` future silently returns `null` when awaited through `withTimeout` / `await(duration)`

## Context

We're building a small library of durable orchestration primitives on top of the Kotlin SDK for our backend services. One of them is a fan-out/scatter-gather helper: it dispatches a batch of items as durable futures with bounded parallelism, arms a per-item timeout on each via `withTimeout`, and partitions the outcomes into succeeded/failed/timed-out. Per-item timeouts over `runAsync` work are a natural fit for that shape — which is how we ran into this.

## Summary

In the Kotlin SDK, awaiting a `ctx.runAsync` (run) future **through a timeout combinator** — either `.withTimeout(duration).await()` or `.await(duration)` — silently corrupts the result: the workflow completes "successfully" but the run block's value is replaced by `null`. No `TimeoutException` is thrown and nothing fails; the wrong value is just returned, so the corruption propagates into journaled workflow state and the workflow's response.

The same combinators over an **awakeable** future work correctly, and a **bare** `runAsync(...).await()` (no timeout) works correctly — the defect is specific to the timeout-combinator-over-run-future composition.

In the fan-out helper described above, this surfaced as a single item returning a wrong value and a multi-item batch producing garbage for every item. We've worked around it by rejecting run futures at the helper's API boundary, but the failure mode is silent data corruption, so it seemed important to report.

## Reproduction

Full self-contained project: https://github.com/innovus-matt-clark/restate-run-timeout-repro (Gradle, one workflow, one JUnit class using `RestateRunner` from `sdk-testing`; `./gradlew test`, Docker required, `-PrestateVersion=` switch). The core of it:

```kotlin
@Workflow
class ReproWorkflow {
    @Workflow
    suspend fun run(ctx: WorkflowContext, case: String): String = when (case) {
        // CONTROL: bare run future, no timeout combinator. ✅ returns "value"
        "bare-run" ->
            ctx.runAsync("work") { "value" }.await()

        // CONTROL: awakeable through the same combinator. ✅ returns "value"
        "awakeable-with-timeout" -> {
            val awakeable = ctx.awakeable<String>()
            ctx.awakeableHandle(awakeable.id).resolve("value")
            awakeable.withTimeout(5.seconds).await()
        }

        // 🐛 BUG: completes "successfully" but returns null instead of "value"
        "run-with-timeout" ->
            ctx.runAsync("work") { "value" }.withTimeout(5.seconds).await()

        // 🐛 BUG: same shape via the await(duration) overload — returns null
        "run-await-duration" ->
            ctx.runAsync("work") { "value" }.await(5.seconds)

        // 🐛 BUG: the fan-out shape — every armed run future corrupts; returns "null,null"
        "two-runs-with-timeout" -> {
            val first = ctx.runAsync("work-0") { "v0" }.withTimeout(5.seconds)
            val second = ctx.runAsync("work-1") { "v1" }.withTimeout(5.seconds)
            first.await() + "," + second.await()
        }

        else -> throw TerminalException("unknown case: $case")
    }
}
```

Every run block completes immediately, so no timeout should ever fire; the expected result in every case is the run block's own return value.

Driven via the ingress client against `RestateRunner` (`sdk-testing`):

```kotlin
val client = ReproWorkflowClient.fromClient(ingress, case)
client.submit(case)
client.workflowHandle().attach().response()   // ← null body for the BUG cases
```

## Observed results

| Case | Expected | Actual |
|---|---|---|
| `bare-run` (control) | `"value"` | ✅ `"value"` |
| `awakeable-with-timeout` (control) | `"value"` | ✅ `"value"` |
| `run-with-timeout` | `"value"` | 🐛 `null` (workflow "succeeds"; attach response body is literally `null`) |
| `run-await-duration` | `"value"` | 🐛 `null` |
| `two-runs-with-timeout` | `"v0,v1"` | 🐛 `"null,null"` |

From the ingress side the corruption surfaces as:

```
dev.restate.client.IngressException: [GET .../restate/workflow/ReproWorkflow/run-with-timeout/attach][Status: 200]
Cannot deserialize the response. Got response body: null
Caused by: kotlinx.serialization.json.internal.JsonDecodingException:
  Unexpected JSON token at offset 0: Expected string literal but 'null' literal was found
```

…and in the `two-runs` case the `null`s flow on *into* the workflow's own logic (string concatenation produces `"null,null"`), i.e. the corrupted value is observable inside the handler, not just at the serde boundary.

## Versions tested (all reproduce identically)

| SDK (`dev.restate:sdk-api-kotlin` et al.) | Server image | Result |
|---|---|---|
| 2.4.1 | `restatedev/restate:1.6` | 🐛 reproduces |
| 2.8.0 | `restatedev/restate:1.6` | 🐛 reproduces |
| 2.8.0 | `restatedev/restate:1.6.2` | 🐛 reproduces |

Environment: Kotlin 2.2.21, KSP 2.3.0, JVM 21, kotlinx-serialization 1.7.3, macOS/Docker (Testcontainers via `RestateRunner`).

## Expected behavior

`runAsync(...).withTimeout(d).await()` should behave like the awakeable case: return the run block's value when it completes within `d`, or throw `TimeoutException` when it doesn't. It should never complete with a value the run block did not produce.

## Notes / hypothesis

Since the bare run future awaits correctly and the awakeable survives `withTimeout` intact, the issue looks like it's in how the timeout combinator (presumably a `select`/race over the run future and a sleep) resolves the **value** of the winning run branch — the completion is detected (no timeout fires, no hang) but the extracted value is `null` rather than the journaled run result. We have only tested the Kotlin API; we don't know whether the Java `withTimeout` is affected.

Happy to provide anything else — the repro project runs with `./gradlew test` (Docker required) and has a `-PrestateVersion=` switch for testing other SDK versions.
