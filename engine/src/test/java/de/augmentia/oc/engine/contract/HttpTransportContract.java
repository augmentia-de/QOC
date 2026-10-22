package de.augmentia.oc.engine.contract;

import de.augmentia.oc.engine.core.port.world.HttpTransport;
import de.augmentia.oc.engine.testing.ScriptedHttp;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contract of {@link HttpTransport}. */
class HttpTransportContract {

    @Test
    void postJsonReturnsTheScriptedResponse() throws Exception {
        ScriptedHttp http = new ScriptedHttp(ScriptedHttp.Response.text(200, "{\"ok\":true}"));
        HttpTransport.ResponseStream response = http.postJson(URI.create("https://example.test"),
                Map.of("x-session", "s1"), "{}", Duration.ofSeconds(5));

        assertThat(response.status()).isEqualTo(200);
        assertThat(new String(response.body().readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo("{\"ok\":true}");
        assertThat(http.requests()).hasSize(1);
        assertThat(http.requests().getFirst().headers()).containsEntry("x-session", "s1");
        response.close();
    }

    @Test
    void responsesAreConsumedInOrder() {
        ScriptedHttp http = new ScriptedHttp(
                ScriptedHttp.Response.text(200, "one"),
                ScriptedHttp.Response.text(500, "two"));

        assertThat(http.postJson(URI.create("https://example.test"), Map.of(), "", Duration.ofSeconds(1)).status())
                .isEqualTo(200);
        assertThat(http.postJson(URI.create("https://example.test"), Map.of(), "", Duration.ofSeconds(1)).status())
                .isEqualTo(500);
    }

    @Test
    void missingResponseIsAnError() {
        ScriptedHttp http = new ScriptedHttp();
        assertThatThrownBy(() -> http.postJson(URI.create("https://example.test"), Map.of(), "",
                Duration.ofSeconds(1))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void channelRecordsSentMessagesAndEmitsToTheListener() {
        ScriptedHttp http = new ScriptedHttp();
        HttpTransport.Channel channel = http.openChannel(URI.create("wss://example.test"), Map.of());
        List<String> received = new java.util.ArrayList<>();
        channel.onMessage(received::add);

        channel.send("hello");
        ((ScriptedHttp.ScriptedChannel) channel).emit("world");

        assertThat(((ScriptedHttp.ScriptedChannel) channel).sent()).containsExactly("hello");
        assertThat(received).containsExactly("world");
        channel.close();
        assertThat(((ScriptedHttp.ScriptedChannel) channel).closed()).isTrue();
    }
}
