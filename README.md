# Restate Kotlin SDK repro: `runAsync` + timeout combinator returns `null`

Minimal reproduction for: a `ctx.runAsync` (run) future silently returns `null` instead of its value
when awaited through `.withTimeout(duration).await()` or `.await(duration)`. Bare `runAsync` awaits and
awakeable-through-`withTimeout` both work (controls). See `ISSUE_DRAFT.md` for the full write-up.

## Run

Requires Docker (Testcontainers / `RestateRunner`) and JVM 21.

```bash
./gradlew test                          # SDK 2.8.0 (default)
./gradlew test -PrestateVersion=2.4.1   # any other SDK version
```

Expected: 5/5 pass. Actual: the two controls pass, the three `BUG -` tests fail
(SDK 2.4.1 and 2.8.0; server 1.6 and 1.6.2 — identical results).

- `src/main/kotlin/repro/ReproWorkflow.kt` — the five-case workflow
- `src/test/kotlin/repro/RunFutureTimeoutReproTest.kt` — JUnit 5 + `RestateRunner` driver
