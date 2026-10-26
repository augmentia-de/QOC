package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.runtime.command.CommandLoader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CommandLoaderTest {

    @Test
    void expandsProjectCommandWithArguments(@TempDir Path root) throws Exception {
        Path command = Files.createDirectories(root.resolve("command"));
        Files.writeString(command.resolve("review.md"), "Review $1 with $ARGUMENTS");
        CommandLoader loader = new CommandLoader(root, null);

        Option<String> expanded = loader.expand("review", "src/Main.java please");

        assertThat(expanded.orElseThrow()).isEqualTo("Review src/Main.java with src/Main.java please");
    }

    @Test
    void unknownCommandIsEmpty() {
        assertThat(new CommandLoader(Path.of("/tmp"), null).expand("missing", "").isEmpty()).isTrue();
    }

    @Test
    void quotedArgumentsStayTogether() {
        assertThat(CommandLoader.substitute("$1|$2|$@", "\"a b\" c"))
                .isEqualTo("a b|c|a b c");
    }
}
