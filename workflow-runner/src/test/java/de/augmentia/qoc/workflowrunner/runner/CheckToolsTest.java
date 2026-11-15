package de.augmentia.qoc.workflowrunner.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CheckToolsTest {

    @TempDir
    Path workDir;

    /**
     * The test JVM runs under surefire, so the recursion guard would refuse
     * every test_runner invocation. These unit tests simulate the workflow
     * runtime (NOT a build) via the documented override property; the guard
     * behavior itself is asserted separately.
     */
    @org.junit.jupiter.api.BeforeEach
    void allowChecksOutsideBuild() {
        System.setProperty("qoc.checktools.inside-build", "false");
    }

    @org.junit.jupiter.api.AfterEach
    void clearOverride() {
        System.clearProperty("qoc.checktools.inside-build");
    }

    // ------------------------------------------------------------------
    // hash_checker
    // ------------------------------------------------------------------

    @Test
    void hashCheckerStableWhenHashMatches() throws Exception {
        Path contract = workDir.resolve("openapi.yaml");
        String content = "openapi: 3.1.0\ninfo:\n  title: t\n";
        Files.writeString(contract, content, StandardCharsets.UTF_8);
        String hash = CheckTools.sha256(content.getBytes(StandardCharsets.UTF_8));

        CheckTool tool = CheckTools.byName("hash_checker");
        Map<String, Object> result = tool.execute(workDir,
                Map.of("file", "openapi.yaml", "expected_hash", hash), new WorkflowContext());

        assertEquals("STABLE", result.get("status"));
        assertEquals(hash, result.get("hash"));
    }

    @Test
    void hashCheckerChangedWhenContractEdited() throws Exception {
        Path contract = workDir.resolve("openapi.yaml");
        Files.writeString(contract, "openapi: 3.1.0\n", StandardCharsets.UTF_8);

        CheckTool tool = CheckTools.byName("hash_checker");
        Map<String, Object> result = tool.execute(workDir,
                Map.of("file", "openapi.yaml",
                        "expected_hash", "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef"),
                new WorkflowContext());

        assertEquals("CHANGED", result.get("status"));
    }

    @Test
    void hashCheckerMissingFileIsNotStable() {
        CheckTool tool = CheckTools.byName("hash_checker");
        Map<String, Object> result = tool.execute(workDir,
                Map.of("file", "nope/openapi.yaml", "expected_hash", "abc"), new WorkflowContext());

        assertEquals("MISSING", result.get("status"));
    }

    @Test
    void hashCheckerReadsExpectedHashFromState() throws Exception {
        Path contract = workDir.resolve("openapi.yaml");
        String content = "openapi: 3.1.0\n";
        Files.writeString(contract, content, StandardCharsets.UTF_8);
        String hash = CheckTools.sha256(content.getBytes(StandardCharsets.UTF_8));

        WorkflowContext ctx = new WorkflowContext();
        ctx.setState("contract_hash", hash);

        CheckTool tool = CheckTools.byName("hash_checker");
        Map<String, Object> result = tool.execute(workDir, Map.of("file", "openapi.yaml"), ctx);

        assertEquals("STABLE", result.get("status"));
    }

    @Test
    void hashCheckerWithoutExpectedHashIsExplicit() throws Exception {
        Files.writeString(workDir.resolve("openapi.yaml"), "openapi: 3.1.0\n", StandardCharsets.UTF_8);

        CheckTool tool = CheckTools.byName("hash_checker");
        Map<String, Object> result = tool.execute(workDir,
                Map.of("file", "openapi.yaml"), new WorkflowContext());

        assertEquals("NO_EXPECTED_HASH", result.get("status"));
    }

    // ------------------------------------------------------------------
    // test_runner
    // ------------------------------------------------------------------

    @Test
    void testRunnerGreenOnTrivialEcho() {
        // A command that exits 0 - deterministic green without mvn.
        CheckTool tool = CheckTools.byName("test_runner");
        Map<String, Object> result = tool.execute(workDir,
                Map.of("command", "true"), new WorkflowContext());

        assertEquals("GREEN", result.get("status"));
        assertEquals(0, result.get("exit_code"));
    }

    @Test
    void testRunnerRedOnFailingCommand() {
        CheckTool tool = CheckTools.byName("test_runner");
        Map<String, Object> result = tool.execute(workDir,
                Map.of("command", "false"), new WorkflowContext());

        assertEquals("RED", result.get("status"));
        assertNotEquals(0, result.get("exit_code"));
    }

    @Test
    void testRunnerParsesSurefireSummary() {
        CheckTool tool = CheckTools.byName("test_runner");
        // sh -c lets us print a summary and fail/succeed deterministically
        Map<String, Object> result = tool.execute(workDir,
                Map.of("command",
                        "echo 'Tests run: 12, Failures: 0, Errors: 0, Skipped: 0' && exit 0"),
                new WorkflowContext());

        assertEquals("GREEN", result.get("status"));
        assertEquals(12, result.get("tests_run"));
        assertEquals(0, result.get("failures"));
        assertEquals(0, result.get("errors"));
    }

    @Test
    void testRunnerRedOnFailuresInSummary() {
        CheckTool tool = CheckTools.byName("test_runner");
        Map<String, Object> result = tool.execute(workDir,
                Map.of("command",
                        "echo 'Tests run: 12, Failures: 2, Errors: 0, Skipped: 0' && exit 0"),
                new WorkflowContext());

        assertEquals("RED", result.get("status"));
        assertEquals(2, result.get("failures"));
    }

    @Test
    void testRunnerRedWhenCoverageBelowMinimum() throws Exception {
        // Write a JaCoCo-like CSV: 10 missed, 10 covered = 50% < 80%
        Path jacoco = workDir.resolve("jacoco.csv");
        Files.writeString(jacoco,
                "GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED\n"
                        + "app,com/x,Foo,10,10\n", StandardCharsets.UTF_8);

        CheckTool tool = CheckTools.byName("test_runner");
        Map<String, Object> result = tool.execute(workDir,
                Map.of("command", "true",
                        "coverage_report", "jacoco.csv",
                        "coverage_min", 80),
                new WorkflowContext());

        assertEquals("RED", result.get("status"));
        assertEquals(50.0, result.get("coverage"));
    }

    @Test
    void testRunnerGreenWhenCoverageAboveMinimum() throws Exception {
        Path jacoco = workDir.resolve("jacoco.csv");
        Files.writeString(jacoco,
                "GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED\n"
                        + "app,com/x,Foo,1,9\n", StandardCharsets.UTF_8);

        CheckTool tool = CheckTools.byName("test_runner");
        Map<String, Object> result = tool.execute(workDir,
                Map.of("command", "true",
                        "coverage_report", "jacoco.csv",
                        "coverage_min", 80),
                new WorkflowContext());

        assertEquals("GREEN", result.get("status"));
        assertEquals(90.0, result.get("coverage"));
    }

    @Test
    void testRunnerRedWhenTraceabilityGapsPresent() throws Exception {
        Files.createDirectories(workDir.resolve("docs/requirements"));
        Files.writeString(workDir.resolve("docs/requirements/traceability_matrix.json"),
                "{\"blocking_gaps\": 3}", StandardCharsets.UTF_8);

        CheckTool tool = CheckTools.byName("test_runner");
        Map<String, Object> result = tool.execute(workDir,
                Map.of("command", "true"), new WorkflowContext());

        assertEquals("RED", result.get("status"));
        assertEquals(3, result.get("traceability_gaps"));
    }

    // ------------------------------------------------------------------
    // registry
    // ------------------------------------------------------------------

    @Test
    void unknownToolNameIsNull() {
        assertNull(CheckTools.byName("frobnicator"));
        assertNotNull(CheckTools.byName("test_runner"));
        assertNotNull(CheckTools.byName("hash_checker"));
    }

    // ------------------------------------------------------------------
    // recursion guard (fork-bomb prevention)
    // ------------------------------------------------------------------

    @Test
    void testRunnerRefusesToRunInsideABuild() {
        // Restore the real (detected) state: surefire parent chain present.
        System.clearProperty("qoc.checktools.inside-build");
        CheckTool tool = CheckTools.byName("test_runner");
        Map<String, Object> result = tool.execute(workDir,
                Map.of("command", "true"), new WorkflowContext());

        assertEquals("REFUSED", result.get("status"),
                "guard must detect surefire parent chain");
        assertNull(result.get("exit_code"), "no command must have been executed");
        assertTrue(String.valueOf(result.get("reason")).contains("recurse"));
    }

    @Test
    void recursionGuardDetectsSurefireProperty() {
        System.clearProperty("qoc.checktools.inside-build");
        assertTrue(CheckTools.runningInsideBuild(),
                "this JVM runs under surefire");
    }

    @Test
    void recursionGuardOverrideExplicitTrueAlwaysRefuses() {
        System.setProperty("qoc.checktools.inside-build", "true");
        assertTrue(CheckTools.runningInsideBuild());
        CheckTool tool = CheckTools.byName("test_runner");
        Map<String, Object> result = tool.execute(workDir,
                Map.of("command", "true"), new WorkflowContext());
        assertEquals("REFUSED", result.get("status"));
    }

    @Test
    void recursionGuardOverrideFalseAllowsExecution() {
        System.setProperty("qoc.checktools.inside-build", "false");
        assertFalse(CheckTools.runningInsideBuild());
    }
}
