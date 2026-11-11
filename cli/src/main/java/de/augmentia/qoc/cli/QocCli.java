package de.augmentia.qoc.cli;

import de.augmentia.qoc.cli.commands.AgentCommand;
import de.augmentia.qoc.cli.commands.ChatCommand;
import de.augmentia.qoc.cli.commands.HealthCommand;
import de.augmentia.qoc.cli.commands.ModelCommand;
import de.augmentia.qoc.cli.commands.RunCommand;
import de.augmentia.qoc.cli.commands.SessionCommand;
import de.augmentia.qoc.cli.config.GlobalOptions;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "qoc", mixinStandardHelpOptions = true, version = "qoc-cli 1.0.0-SNAPSHOT",
        description = "Command-line client for the QOC engine.",
        subcommands = {HealthCommand.class, SessionCommand.class, RunCommand.class,
                ChatCommand.class, AgentCommand.class, ModelCommand.class})
public class QocCli implements Runnable {

    @Option(names = {"-u", "--url"}, description = "QOC server base URL")
    void rootUrl(String value) {
        GlobalOptions.setRootUrl(value);
    }

    @Option(names = {"-t", "--token"}, description = "Bearer token")
    void rootToken(String value) {
        GlobalOptions.setRootToken(value);
    }

    @Option(names = {"--timeout"}, description = "HTTP timeout in seconds")
    void rootTimeout(int value) {
        GlobalOptions.setRootTimeoutSeconds(value);
    }

    @Option(names = {"--json"}, description = "Print raw JSON")
    void rootJson(boolean value) {
        GlobalOptions.setRootJson(value);
    }

    @Override
    public void run() {
        System.out.println("Use 'qoc --help' for usage.");
    }

    public static void main(String[] args) {
        int exit = new CommandLine(new QocCli()).execute(args);
        System.exit(exit);
    }
}
