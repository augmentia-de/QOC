package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.contract.provider.ModelEvent;
import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.Effect;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.Rule;
import de.augmentia.oc.engine.runtime.context.AgentProfile;
import de.augmentia.oc.engine.runtime.context.TitleGenerator;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenPromptLogicTest {

    @Test
    void planModeDetectedByName() {
        AgentProfile plan = AgentProfile.forAgent(AgentId.create(), Option.none(), 10, List.of(), "plan");
        assertThat(plan.planMode()).isTrue();

        AgentProfile build = AgentProfile.forAgent(AgentId.create(), Option.none(), 10, List.of(), "build");
        assertThat(build.planMode()).isFalse();
    }

    @Test
    void planModeDetectedFromDeniedEdit() {
        AgentProfile agent = AgentProfile.forAgent(AgentId.create(), Option.none(), 10,
                List.of(new Rule("edit", "*", Effect.DENY)), "build");
        assertThat(agent.planMode()).isTrue();
    }

    @Test
    void planModeNotDetectedFromAllow() {
        AgentProfile agent = AgentProfile.forAgent(AgentId.create(), Option.none(), 10,
                List.of(new Rule("edit", "*", Effect.ALLOW)), "build");
        assertThat(agent.planMode()).isFalse();
    }

    @Test
    void planModeNotDetectedFromScopedEditDeny() {
        AgentProfile agent = AgentProfile.forAgent(AgentId.create(), Option.none(), 10,
                List.of(new Rule("edit", "*.env", Effect.DENY)), "build");
        assertThat(agent.planMode()).isFalse();
    }

    @Test
    void titleGeneratorFromFirstMessage() {
        de.augmentia.oc.engine.core.contract.provider.ProviderAdapter provider =
                request -> java.util.stream.Stream.of(new ModelEvent.TextEnded("b", "My Title"));
        TitleGenerator generator = new TitleGenerator(provider,
                de.augmentia.oc.engine.core.model.ModelRef.of(
                        de.augmentia.oc.engine.core.model.ProviderId.create(),
                        de.augmentia.oc.engine.core.model.ModelId.create()));

        String title = generator.fromFirstMessage("Hello world");
        assertThat(title).isEqualTo("My Title");
    }

    @Test
    void titleGeneratorTruncates() {
        String longTitle = "x".repeat(200);
        de.augmentia.oc.engine.core.contract.provider.ProviderAdapter provider =
                request -> java.util.stream.Stream.of(new ModelEvent.TextEnded("b", longTitle));
        TitleGenerator generator = new TitleGenerator(provider,
                de.augmentia.oc.engine.core.model.ModelRef.of(
                        de.augmentia.oc.engine.core.model.ProviderId.create(),
                        de.augmentia.oc.engine.core.model.ModelId.create()));

        String title = generator.fromFirstMessage("Hello");
        assertThat(title).hasSize(100);
    }
}
