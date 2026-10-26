package de.augmentia.oc.engine.adapter;

import de.augmentia.oc.engine.adapter.fs.NioInstructionLoader;
import de.augmentia.oc.engine.core.port.world.InstructionLoader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NioInstructionLoaderTest {

    @Test
    void loadsSystemContextAndAppendInOrder(@TempDir Path root) throws Exception {
        Path global = Files.createDirectories(root.resolve("global"));
        Path sub = Files.createDirectories(root.resolve("work/sub"));
        Files.writeString(global.resolve("AGENTS.md"), "global rules");
        Files.writeString(root.resolve("work/AGENTS.md"), "project rules");
        Files.writeString(sub.resolve("CLAUDE.md"), "sub rules");
        Files.writeString(sub.resolve("SYSTEM.md"), "custom system");
        Files.writeString(sub.resolve("RULES.md"), "sticky rules");
        Files.writeString(sub.resolve("APPEND_SYSTEM.md"), "appended");

        List<InstructionLoader.InstructionFile> files =
                new NioInstructionLoader(global).load(sub);

        assertThat(files).extracting(InstructionLoader.InstructionFile::kind).containsExactly(
                InstructionLoader.Kind.SYSTEM,
                InstructionLoader.Kind.AGENTS,
                InstructionLoader.Kind.AGENTS,
                InstructionLoader.Kind.AGENTS,
                InstructionLoader.Kind.RULES,
                InstructionLoader.Kind.APPEND_SYSTEM);
        assertThat(files).extracting(InstructionLoader.InstructionFile::text).containsExactly(
                "custom system", "global rules", "project rules", "sub rules",
                "sticky rules", "appended");
    }

    @Test
    void agentsWinsOverClaudeInTheSameDirectory(@TempDir Path root) throws Exception {
        Path global = Files.createDirectories(root.resolve("global"));
        Path work = Files.createDirectories(root.resolve("work"));
        Files.writeString(work.resolve("AGENTS.md"), "agents");
        Files.writeString(work.resolve("CLAUDE.md"), "claude");

        List<InstructionLoader.InstructionFile> files =
                new NioInstructionLoader(global).load(work);

        assertThat(files).extracting(InstructionLoader.InstructionFile::text).containsExactly("agents");
    }

    @Test
    void oversizedFilesAreSkipped(@TempDir Path root) throws Exception {
        Path global = Files.createDirectories(root.resolve("global"));
        Path work = Files.createDirectories(root.resolve("work"));
        Files.writeString(work.resolve("AGENTS.md"),
                "x".repeat(NioInstructionLoader.MAX_FILE_BYTES + 1));

        assertThat(new NioInstructionLoader(global).load(work)).isEmpty();
    }

    @Test
    void missingFilesYieldAnEmptyList(@TempDir Path root) throws Exception {
        Path global = Files.createDirectories(root.resolve("global"));
        Path work = Files.createDirectories(root.resolve("work"));

        assertThat(new NioInstructionLoader(global).load(work)).isEmpty();
    }
}
