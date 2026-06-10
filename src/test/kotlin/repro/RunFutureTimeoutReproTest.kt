package repro

import dev.restate.client.Client
import dev.restate.sdk.kotlin.endpoint.endpoint
import dev.restate.sdk.testing.RestateRunner
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

private const val RESTATE_SERVER_IMAGE = "docker.io/restatedev/restate:1.6.2"
private const val DEADLOCK_GUARD_SECONDS = 60L

/**
 * Expected: all five tests pass (every run block completes immediately; no timeout should fire).
 *
 * Actual (SDK 2.4.1 and 2.8.0; server 1.6 and 1.6.2 — identical results):
 *  - the two controls pass
 *  - run-with-timeout / run-await-duration: the workflow completes "successfully" but its return value
 *    is null instead of "value" (the attach response body is literally `null`) — silent value corruption,
 *    not a TimeoutException
 *  - two-runs-with-timeout: returns "null,null" — both run results corrupted
 *  - two-runs-with-timeout hangs until the JUnit @Timeout guard kills it
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunFutureTimeoutReproTest {

    private lateinit var runner: RestateRunner
    private lateinit var ingress: Client

    @BeforeAll
    fun setUp() {
        runner = RestateRunner
            .from(endpoint { bind(ReproWorkflow()) })
            .withRestateContainerImage(RESTATE_SERVER_IMAGE)
            .build()
        runner.start()
        ingress = Client.connect(runner.restateUrl.toString())
    }

    @AfterAll
    fun tearDown() {
        runner.stop()
    }

    private fun runCase(case: String): String = runBlocking {
        val client = ReproWorkflowClient.fromClient(ingress, case)
        client.submit(case)
        client.workflowHandle().attach().response()
    }

    @Test
    fun `control - bare runAsync await returns its value`() {
        assertEquals("value", runCase("bare-run"))
    }

    @Test
    fun `control - awakeable through withTimeout returns its value`() {
        assertEquals("value", runCase("awakeable-with-timeout"))
    }

    @Test
    fun `BUG - runAsync through withTimeout should return its value`() {
        assertEquals("value", runCase("run-with-timeout"))
    }

    @Test
    fun `BUG - runAsync await(duration) should return its value`() {
        assertEquals("value", runCase("run-await-duration"))
    }

    @Test
    @Timeout(DEADLOCK_GUARD_SECONDS)
    fun `BUG - two timeout-armed runAsync futures should both return their values`() {
        assertEquals("v0,v1", runCase("two-runs-with-timeout"))
    }
}
