package de.augmentia.oc.engine.runtime.tool.meta;

import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOptions;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.core.port.world.ProcessRunner;
import de.augmentia.oc.engine.runtime.tool.JsonArgs;
import de.augmentia.oc.engine.runtime.tool.fs.ReadTool;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Git worktree tools: create, list and remove worktrees below
 * {@code <workspace>/.worktrees}. Requires the {@code git} binary.
 */
public final class WorktreeTool {

    public static final String DIRECTORY = ".worktrees";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final ProcessRunner runner;
    private final Path workspace;

    public WorktreeTool(ProcessRunner runner, Path workspace) {
        if (runner == null || workspace == null) {
            throw new IllegalArgumentException("runner and workspace must not be null");
        }
        this.runner = runner;
        this.workspace = workspace.toAbsolutePath().normalize();
    }

    public static ToolDefinition create(WorktreeTool tool) {
        Schema input = Schema.obj(Map.of("name", Schema.str()));
        return new ToolDefinition(new ToolName("worktree_create"),
                "Create a named git worktree below .worktrees and return its directory",
                input, Option.none(),
                (value, context) -> {
                    try {
                        String name = JsonArgs.requiredString(value, "name");
                        if (!name.matches("[A-Za-z0-9._-]{1,64}")) {
                            return ReadTool.failure("worktree_create", "invalid worktree name");
                        }
                        Path directory = tool.workspace.resolve(DIRECTORY).resolve(name);
                        ProcessRunner.Exit exit = tool.git("worktree", "add", directory.toString(), "-b", name);
                        if (exit.code() != 0 && exit.stderr().contains("already exists")) {
                            exit = tool.git("worktree", "add", directory.toString());
                        }
                        if (exit.code() != 0) {
                            return ReadTool.failure("worktree_create", exit.stderr().strip());
                        }
                        return new ToolOutcome.Ok(new ToolResult(Option.none(),
                                List.of(new ToolContent.TextItem("Created worktree in " + directory + ".")),
                                Map.of("directory", directory.toString())));
                    } catch (IllegalArgumentException e) {
                        return ReadTool.failure("worktree_create", e.getMessage());
                    }
                },
                new ToolOptions(Option.none(), Option.of("worktree"), true, false));
    }

    public static ToolDefinition list(WorktreeTool tool) {
        return new ToolDefinition(new ToolName("worktree_list"),
                "List the repository's git worktrees",
                Schema.obj(Map.of()), Option.none(),
                (value, context) -> {
                    ProcessRunner.Exit exit = tool.git("worktree", "list", "--porcelain");
                    if (exit.code() != 0) {
                        return ReadTool.failure("worktree_list", exit.stderr().strip());
                    }
                    List<Map<String, String>> entries = parse(exit.stdout());
                    StringBuilder text = new StringBuilder();
                    for (Map<String, String> entry : entries) {
                        text.append("- ").append(entry.get("worktree"))
                                .append(entry.get("branch") == null ? " (detached)" : " [" + entry.get("branch") + "]")
                                .append('\n');
                    }
                    if (entries.isEmpty()) text.append("No worktrees.");
                    return new ToolOutcome.Ok(new ToolResult(Option.none(),
                            List.of(new ToolContent.TextItem(text.toString().strip())),
                            Map.of("count", entries.size())));
                },
                new ToolOptions(Option.none(), Option.of("worktree"), true, false));
    }

    public static ToolDefinition remove(WorktreeTool tool) {
        Schema input = Schema.obj(Map.of(
                "directory", Schema.str(),
                "force", Schema.opt(Schema.bool())));
        return new ToolDefinition(new ToolName("worktree_remove"),
                "Remove a git worktree below .worktrees",
                input, Option.none(),
                (value, context) -> {
                    try {
                        String raw = JsonArgs.requiredString(value, "directory");
                        Path directory = Path.of(raw).isAbsolute()
                                ? Path.of(raw).normalize()
                                : tool.workspace.resolve(raw).normalize();
                        Path allowed = tool.workspace.resolve(DIRECTORY).normalize();
                        if (!directory.startsWith(allowed)) {
                            return ReadTool.failure("worktree_remove", "directory is outside " + allowed);
                        }
                        boolean force = JsonArgs.boolOr(value, "force", false);
                        ProcessRunner.Exit exit = force
                                ? tool.git("worktree", "remove", "--force", directory.toString())
                                : tool.git("worktree", "remove", directory.toString());
                        if (exit.code() != 0) {
                            return ReadTool.failure("worktree_remove", exit.stderr().strip());
                        }
                        return new ToolOutcome.Ok(new ToolResult(Option.none(),
                                List.of(new ToolContent.TextItem("Removed worktree " + directory + ".")),
                                Map.of("directory", directory.toString(), "removed", true)));
                    } catch (IllegalArgumentException e) {
                        return ReadTool.failure("worktree_remove", e.getMessage());
                    }
                },
                new ToolOptions(Option.none(), Option.of("edit"), true, false));
    }

    private ProcessRunner.Exit git(String... arguments) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(workspace.toString());
        command.addAll(List.of(arguments));
        return runner.run(new ProcessRunner.ProcessSpec(command, Option.none(),
                Map.of("GIT_TERMINAL_PROMPT", "0"), Option.none(), false), TIMEOUT);
    }

    private static List<Map<String, String>> parse(String porcelain) {
        List<Map<String, String>> entries = new ArrayList<>();
        Map<String, String> current = null;
        for (String line : porcelain.split("\n")) {
            if (line.isBlank()) {
                if (current != null) entries.add(current);
                current = null;
                continue;
            }
            if (line.startsWith("worktree ")) {
                if (current != null) entries.add(current);
                current = new java.util.LinkedHashMap<>();
                current.put("worktree", line.substring("worktree ".length()).trim());
            } else if (current != null && line.startsWith("branch ")) {
                current.put("branch", line.substring("branch ".length()).trim());
            } else if (current != null && line.equals("detached")) {
                current.put("detached", "true");
            }
        }
        if (current != null) entries.add(current);
        return entries;
    }
}
