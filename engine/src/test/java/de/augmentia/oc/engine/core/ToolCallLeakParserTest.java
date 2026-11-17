package de.augmentia.oc.engine.core;

import de.augmentia.oc.engine.core.eval.ToolCallLeakParser;
import de.augmentia.oc.engine.core.json.JsonValue;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallLeakParserTest {

    @Test
    void recoversTheProductionPayload() {
        String text = """
                I have the full causal chain. One last verification.

                <｜｜DSML｜｜ calls>
                <｜｜DSML｜｜ invoke name="shell">
                <｜｜DSML｜｜ parameter name="command" string="true">cd /tmp && ls</｜｜DSML｜｜ parameter>
                </｜｜DSML｜｜ invoke>
                <｜｜DSML｜｜ invoke name="shell">
                <｜｜DSML｜｜ parameter name="command" string="true">grep -rn foo . | head</｜｜DSML｜｜ parameter>
                </｜｜DSML｜｜ invoke>
                </｜｜DSML｜｜ calls>""";

        List<ToolCallLeakParser.SalvagedCall> calls = ToolCallLeakParser.parse(text);

        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).name()).isEqualTo("shell");
        assertThat(calls.get(0).input()).isEqualTo(JsonValue.of(Map.of(
                "command", JsonValue.of("cd /tmp && ls"))));
        assertThat(calls.get(1).input()).isEqualTo(JsonValue.of(Map.of(
                "command", JsonValue.of("grep -rn foo . | head"))));
    }

    @Test
    void parsesNonStringParametersAsJson() {
        String text = """
                <|DSML| calls>
                <|DSML| invoke name="read">
                <|DSML| parameter name="limit" string="false">25</|DSML| parameter>
                <|DSML| parameter name="paths" string="false">["a","b"]</|DSML| parameter>
                <|DSML| parameter name="recursive" string="false">true</|DSML| parameter>
                </|DSML| invoke>
                </|DSML| calls>""";

        ToolCallLeakParser.SalvagedCall call = ToolCallLeakParser.parse(text).getFirst();

        assertThat(call.input()).isEqualTo(JsonValue.of(Map.of(
                "limit", JsonValue.of(25),
                "paths", JsonValue.of(List.of(JsonValue.of("a"), JsonValue.of("b"))),
                "recursive", JsonValue.of(true))));
    }

    @Test
    void unescapesXmlEntitiesInStringParameters() {
        String text = """
                <|DSML| invoke name="write">
                <|DSML| parameter name="content" string="true">&lt;tag&gt; &amp; &quot;quoted&quot;</|DSML| parameter>
                </|DSML| invoke>""";

        ToolCallLeakParser.SalvagedCall call = ToolCallLeakParser.parse(text).getFirst();

        assertThat(call.input()).isEqualTo(JsonValue.of(Map.of(
                "content", JsonValue.of("<tag> & \"quoted\""))));
    }

    @Test
    void ignoresMalformedOrPlainText() {
        assertThat(ToolCallLeakParser.parse(null)).isEmpty();
        assertThat(ToolCallLeakParser.parse("plain text")).isEmpty();
        assertThat(ToolCallLeakParser.parse("<|DSML| invoke name=\"read\">unclosed")).isEmpty();
    }

    @Test
    void capsRecoveredCalls() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < ToolCallLeakParser.MAX_CALLS + 3; i++) {
            text.append("<|DSML| invoke name=\"echo\"></|DSML| invoke>");
        }

        assertThat(ToolCallLeakParser.parse(text.toString())).hasSize(ToolCallLeakParser.MAX_CALLS);
    }
}
