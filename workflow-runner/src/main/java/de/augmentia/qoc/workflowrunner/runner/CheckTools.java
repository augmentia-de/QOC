package de.augmentia.qoc.workflowrunner.runner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Built-in check tools for SDLC gate verification.
 *
 * <ul>
 *   <li><b>test_runner</b> — runs a build command (default: {@code mvn test}),
 *       parses the test summary and optional coverage/traceability data, and
 *       returns a machine-checkable result:
 *       {@code status} (GREEN/RED), {@code tests_run}, {@code failures},
 *       {@code errors}, {@code coverage}, {@code traceability_gaps},
 *       {@code command}, {@code exit_code}, {@code duration_ms}.</li>
 *   <li><b>hash_checker</b> — computes the SHA-256 hash of a file (default:
 *       {@code docs/api/openapi.yaml}) and compares it against the expected
 *       hash supplied via args or via a previous node output
 *       ({@code contract_hash}). Returns {@code status} (STABLE/CHANGED),
 *       {@code hash}, {@code expected_hash}, {@code file}.</li>
 * </ul>
 *
 * <p>Both tools write deterministic state that decision nodes route on —
 * never prose to be judged by a model (SDW-02, SDW-04).</p>
 */
public final class CheckTools {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(15);

    private CheckTools() {}

    /** Registry lookup by {@code mcpTool} name. */
    public static CheckTool byName(String name) {
        return switch (name == null ? "" : name) {
            case "test_runner" -> new TestRunner();
            case "hash_checker" -> new HashChecker();
            default -> null;
        };
    }

    // ------------------------------------------------------------------
    // test_runner
    // ------------------------------------------------------------------

    public static final class TestRunner implements CheckTool {

        private static final Pattern SUREFIRE_SUMMARY = Pattern.compile(
                "Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+),\\s*Skipped:\\s*(\\d+)");

        @Override
        public String name() {
            return "test_runner";
        }

        @Override
        public Map<String, Object> execute(Path workDir, Map<String, Object> args,
                                           WorkflowContext context) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("tool", name());

            // Recursion guard: running a build command from inside a build
            // (surefire forks, mvn-in-mvn) is a fork bomb, not a check.
            // Detect the parent chain and refuse deterministically.
            if (runningInsideBuild()) {
                result.put("status", "REFUSED");
                result.put("reason",
                        "test_runner invoked from inside a build (MAVEN/surefire in parent chain); "
                                + "running 'mvn test' here would recurse. Run checks outside the build "
                                + "or set command to a non-build tool.");
                return result;
            }

            String command = str(args.getOrDefault("command", "mvn test"));
            Double coverageMin = doubleArg(args.get("coverage_min"));
            double coverageMinPct = coverageMin == null ? 0.0 : coverageMin;
            int maxDurationSec = intArg(args.get("timeout_seconds"), (int) DEFAULT_TIMEOUT.toSeconds());

            result.put("command", command);

            long started = System.currentTimeMillis();
            ProcessResult proc = runCommand(workDir, command, maxDurationSec);
            long duration = System.currentTimeMillis() - started;

            result.put("exit_code", proc.exitCode());
            result.put("duration_ms", duration);
            result.put("output_tail", tail(proc.output(), 4000));

            // Parse last "Tests run: ... Failures: ... Errors: ... Skipped: ..."
            // summary line (Maven prints one aggregate per module, then a total).
            int tests = 0, failures = 0, errors = 0, skipped = 0;
            Matcher matcher = SUREFIRE_SUMMARY.matcher(proc.output());
            boolean found = false;
            while (matcher.find()) {
                tests = Integer.parseInt(matcher.group(1));
                failures = Integer.parseInt(matcher.group(2));
                errors = Integer.parseInt(matcher.group(3));
                skipped = Integer.parseInt(matcher.group(4));
                found = true;
            }
            if (!found) {
                // No summary parsed - command result alone decides.
                // Keep counters at 0 but mark RED when the command failed so
                // "no summary + exit != 0" is RED, not silently green.
                if (proc.exitCode() != 0) {
                    result.put("parse_error", "no test summary found in output");
                }
            }
            result.put("tests_run", tests);
            result.put("failures", failures);
            result.put("errors", errors);
            result.put("skipped", skipped);

            // Coverage: read JaCoCo report if present (xml or csv).
            Double coverage = readCoverage(workDir, args);
            result.put("coverage", coverage == null ? null : coverage);

            // Traceability gaps: from a JSON report if present
            // (docs/requirements/traceability_matrix.json field "blocking_gaps").
            Integer gaps = readTraceabilityGaps(workDir, args);
            result.put("traceability_gaps", gaps);

            boolean green = proc.exitCode() == 0 && failures == 0 && errors == 0
                    && (coverage == null || coverage >= coverageMinPct)
                    && (gaps == null || gaps == 0);
            result.put("status", green ? "GREEN" : "RED");
            return result;
        }

        private Double readCoverage(Path workDir, Map<String, Object> args) {
            String path = str(args.getOrDefault("coverage_report",
                    "target/site/jacoco/jacoco.csv"));
            Path report = workDir.resolve(path);
            if (!Files.isRegularFile(report)) {
                return null;
            }
            try {
                // JaCoCo CSV: GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,
                //              BRANCH_MISSED,BRANCH_COVERED,...
                // Aggregate over all rows (instruction counter).
                long missed = 0, covered = 0;
                for (String line : Files.readAllLines(report, StandardCharsets.UTF_8)) {
                    String[] cols = line.split(",");
                    if (cols.length < 5 || "INSTRUCTION_MISSED".equals(cols[3])) {
                        continue; // header
                    }
                    try {
                        missed += Long.parseLong(cols[3].trim());
                        covered += Long.parseLong(cols[4].trim());
                    } catch (NumberFormatException ignored) {
                        // try next line
                    }
                }
                if (missed + covered > 0) {
                    return round(100.0 * covered / (missed + covered));
                }
                return null;
            } catch (IOException | RuntimeException e) {
                // unreadable report -> no coverage data (not a failure by itself)
                return null;
            }
        }

        private Integer readTraceabilityGaps(Path workDir, Map<String, Object> args) {
            String path = str(args.getOrDefault("traceability_report",
                    "docs/requirements/traceability_matrix.json"));
            Path report = workDir.resolve(path);
            if (!Files.isRegularFile(report)) {
                return null;
            }
            try {
                String json = Files.readString(report, StandardCharsets.UTF_8);
                Matcher m = Pattern.compile("\"blocking_gaps\"\\s*:\\s*(\\d+)")
                        .matcher(json);
                if (m.find()) {
                    return Integer.parseInt(m.group(1));
                }
                return null;
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------
    // hash_checker
    // ------------------------------------------------------------------

    public static final class HashChecker implements CheckTool {

        @Override
        public String name() {
            return "hash_checker";
        }

        @Override
        public Map<String, Object> execute(Path workDir, Map<String, Object> args,
                                           WorkflowContext context) {
            Map<String, Object> result = new LinkedHashMap<>();
            String file = str(args.getOrDefault("file", "docs/api/openapi.yaml"));
            result.put("tool", name());
            result.put("file", file);

            Path target = workDir.resolve(file);
            if (!Files.isRegularFile(target)) {
                result.put("status", "MISSING");
                result.put("hash", null);
                result.put("expected_hash", str(args.get("expected_hash")));
                return result;
            }

            String hash;
            try {
                hash = sha256(Files.readAllBytes(target));
            } catch (IOException e) {
                result.put("status", "ERROR");
                result.put("error", e.getMessage());
                return result;
            }
            result.put("hash", hash);

            // Expected hash: explicit arg, or the contract_hash from a prior
            // node output (stress report), or state key contract_hash.
            String expected = str(args.get("expected_hash"));
            if (expected == null || expected.isBlank()) {
                Object fromState = context.getState("contract_hash");
                if (fromState != null) {
                    expected = String.valueOf(fromState);
                }
            }
            result.put("expected_hash", expected);

            if (expected == null || expected.isBlank()) {
                result.put("status", "NO_EXPECTED_HASH");
            } else {
                result.put("status", hash.equalsIgnoreCase(expected.trim()) ? "STABLE" : "CHANGED");
            }
            return result;
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * True when this JVM's parent chain contains a Maven/surefire process,
     * i.e. we are running inside a build. Detected via the surefire
     * classpath property and Maven environment markers.
     *
     * <p>Override for tests and embedding runtimes that are NOT part of a
     * build: set system property {@code qoc.checktools.inside-build=false}.
     * Default when unset: auto-detect. Explicit {@code true} always refuses.</p>
     */
    static boolean runningInsideBuild() {
        String override = System.getProperty("qoc.checktools.inside-build");
        if ("true".equalsIgnoreCase(override)) return true;
        if ("false".equalsIgnoreCase(override)) return false;
        return detectInsideBuild();
    }

    private static boolean detectInsideBuild() {
        // Surefire sets this when forking test JVMs
        if (System.getProperty("surefire.real.class.path") != null
                || System.getProperty("surefire.test.class.path") != null) {
            return true;
        }
        // Maven sets these in its own JVM and forked JVMs inherit them
        String[] mavenEnvMarkers = {
                "MAVEN_CMD_LINE_ARGS", "MAVEN_PROJECTBASEDIR", "MAVEN_HOME"
        };
        for (String marker : mavenEnvMarkers) {
            if (System.getenv(marker) != null) {
                return true;
            }
        }
        // classworlds launcher = Maven's JVM itself
        String classPath = System.getProperty("java.class.path", "");
        return classPath.contains("plexus-classworlds");
    }

    static ProcessResult runCommand(Path workDir, String command, int timeoutSeconds) {
        List<String> parts;
        if (System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            parts = List.of("cmd.exe", "/c", command);
        } else {
            parts = List.of("sh", "-c", command);
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(parts).directory(workDir.toFile())
                    .redirectErrorStream(true);
            Process p = pb.start();
            byte[] out;
            try (java.io.InputStream is = p.getInputStream()) {
                out = is.readAllBytes();
            }
            boolean finished = p.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return new ProcessResult(-1, new String(out, StandardCharsets.UTF_8)
                        + "\n[timeout after " + timeoutSeconds + "s]");
            }
            return new ProcessResult(p.exitValue(), new String(out, StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new ProcessResult(-1, "failed to execute: " + e.getMessage());
        }
    }

    record ProcessResult(int exitCode, String output) {}

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static int intArg(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return def;
    }

    private static Double doubleArg(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return null;
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static String tail(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(s.length() - max);
    }

    static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(data)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Exposed for tests. */
    static List<String> parseSummary(String output) {
        Matcher matcher = SUREFIRE_SUMMARY_PATTERN.matcher(output);
        if (matcher.find()) {
            return List.of(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4));
        }
        return List.of();
    }

    private static final Pattern SUREFIRE_SUMMARY_PATTERN = Pattern.compile(
            "Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+),\\s*Skipped:\\s*(\\d+)");
}
