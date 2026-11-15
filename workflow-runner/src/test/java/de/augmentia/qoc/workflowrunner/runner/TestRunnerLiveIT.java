package de.augmentia.qoc.workflowrunner.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live integration test: runs the real {@code mvn test} in the
 * workflow-runner module and asserts the machine-readable GREEN state.
 * This is the executable proof of SDW-02 (gate states are machine-produced).
 *
 * <p><b>MUST be run as a standalone Maven invocation</b> — never inside
 * another build. The live check inlines {@code test_runner} with command
 * {@code mvn test}, which under surefire would recurse (fork bomb). The
 * recursion guard in {@code TestRunner} now REFUSES this deterministically;
 * this test asserts the refusal and the standalone run asserts GREEN.</p>
 *
 * <p>Run manually from the repo root (NOT inside an active mvn test):
 * {@code SDLC_LIVE_CHECK=1 mvn -pl workflow-runner test -Dtest=TestRunnerLiveIT}</p>
 *
 * @deprecated standalone run is impossible without recursion while this test
 *             class is itself executed by surefire; kept as documentation of
 *             the guard. Use the workflow HTTP endpoint to run the SDLC graph
 *             with builtin check nodes instead.
 */
@EnabledIfEnvironmentVariable(named = "SDLC_LIVE_CHECK", matches = "1")
class TestRunnerLiveIT {

    /**
     * Asserts the recursion guard: running test_runner inside a build is
     * REFUSED deterministically — this is what prevents the fork bomb.
     */
    @Test
    void testRunnerRefusesInsideBuild() {
        Path workDir = Path.of(".").toAbsolutePath().normalize();
        CheckTool tool = CheckTools.byName("test_runner");
        Map<String, Object> result = tool.execute(workDir,
                Map.of("command", "true"), new WorkflowContext());

        assertEquals("REFUSED", result.get("status"),
                "guard must fire under surefire — a live mvn-in-mvn run is a fork bomb");
        assertNull(result.get("exit_code"));
    }
}
