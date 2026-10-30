package de.augmentia.qoc.decisiongate;

import dev.langchain4j.model.ModelProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionModelSettingsTest {

    @TempDir
    Path temp;

    @Test
    void readsDefaultsAndTypedValuesFromAMap() {
        DecisionModelSettings settings = DecisionModelSettings.fromMap(Map.of(
                "qoc.decision.base-url", "https://decision.example.test",
                "qoc.decision.api-key", "secret",
                "qoc.decision.model", "jev-test",
                "qoc.decision.timeout", "7s",
                "qoc.decision.max-retries", "3"));

        assertThat(settings.baseUrl()).isEqualTo("https://decision.example.test");
        assertThat(settings.apiKey()).isEqualTo("secret");
        assertThat(settings.modelName()).isEqualTo("jev-test");
        assertThat(settings.timeout()).isEqualTo(Duration.ofSeconds(7));
        assertThat(settings.maxRetries()).isEqualTo(3);
        assertThat(settings.toString()).doesNotContain("secret").contains("apiKey=***");
    }

    @Test
    void usesTypeSafeDefaultsWhenNothingIsConfigured() {
        DecisionModelSettings settings = DecisionModelSettings.fromMap(Map.of());

        assertThat(settings.baseUrl()).isEqualTo(DecisionModelSettings.DEFAULT_BASE_URL);
        assertThat(settings.modelName()).isEqualTo(DecisionModelSettings.DEFAULT_MODEL);
        assertThat(settings.timeout()).isEqualTo(DecisionModelSettings.DEFAULT_TIMEOUT);
        assertThat(settings.apiKey()).isEmpty();
    }

    @Test
    void readsApplicationPropertiesAndResolvesMissingPlaceholdersToBlank() throws Exception {
        Path file = temp.resolve("application.properties");
        Files.writeString(file, """
                qoc.decision.base-url=https://decision.example.test
                qoc.decision.model=jev-test
                qoc.decision.timeout=1500ms
                qoc.decision.max-retries=1
                qoc.decision.api-key=${QOC_TEST_MISSING_DECISION_KEY}
                """);

        DecisionModelSettings settings = DecisionModelSettings.fromApplicationProperties(file);

        assertThat(settings.baseUrl()).isEqualTo("https://decision.example.test");
        assertThat(settings.apiKey()).isEmpty();
        assertThat(settings.timeout()).isEqualTo(Duration.ofMillis(1500));
    }

    @Test
    void rejectsInvalidValues() {
        assertThatThrownBy(() -> DecisionModelSettings.fromMap(Map.of("qoc.decision.timeout", "soon")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DecisionModelSettings.fromMap(Map.of("qoc.decision.max-retries", "-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildsATypeSafeModelForACustomSystemOneEndpoint() {
        var model = DecisionModels.typeSafe(new DecisionModelSettings(
                "https://decision.example.test", "", "jev-test", Duration.ofSeconds(5), 1));

        assertThat(model.modelName()).isEqualTo("jev-test");
        assertThat(model.provider()).isEqualTo(ModelProvider.TYPESAFE);
    }
}
