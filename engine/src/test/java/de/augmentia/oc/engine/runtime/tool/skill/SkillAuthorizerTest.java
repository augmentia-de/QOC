package de.augmentia.oc.engine.runtime.tool.skill;

import de.augmentia.oc.engine.core.contract.permission.PermissionRequest;
import de.augmentia.oc.engine.core.contract.tool.ToolCall;
import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.runtime.tool.runtime.ToolAuthorizer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillAuthorizerTest {

    @Test
    void usesTheSkillIdAsPermissionResource() {
        ToolContext context = new ToolContext(SessionId.create(), AgentId.create(), MessageId.create(), CallId.create());
        ToolDefinition definition = SkillTool.definition(SkillCatalogHolder.empty(), List.of());
        ToolCall call = new ToolCall(CallId.create(), new de.augmentia.oc.engine.core.model.ToolName("skill"),
                JsonValue.of(java.util.Map.of("id", JsonValue.of("commit"))));

        PermissionRequest request = new SkillAuthorizer(ToolAuthorizer.defaultAuthorizer())
                .request(context, definition, call);

        assertThat(request.action()).isEqualTo("skill");
        assertThat(request.resources()).containsExactly("commit");
    }

    @Test
    void usesWildcardForSkillListing() {
        ToolContext context = new ToolContext(SessionId.create(), AgentId.create(), MessageId.create(), CallId.create());
        ToolDefinition definition = SkillTool.listDefinition(SkillCatalogHolder.empty(), agentId -> List.of());
        ToolCall call = new ToolCall(CallId.create(), new de.augmentia.oc.engine.core.model.ToolName("skill_list"),
                JsonValue.of(java.util.Map.of()));

        PermissionRequest request = new SkillAuthorizer(ToolAuthorizer.defaultAuthorizer())
                .request(context, definition, call);

        assertThat(request.action()).isEqualTo("skill");
        assertThat(request.resources()).containsExactly("*");
    }

    @Test
    void delegatesOtherToolsUnchanged() {
        ToolContext context = new ToolContext(SessionId.create(), AgentId.create(), MessageId.create(), CallId.create());
        ToolDefinition definition = new ToolDefinition(new de.augmentia.oc.engine.core.model.ToolName("read"),
                "read", de.augmentia.oc.engine.core.json.Schema.str(), Option.none(),
                (value, ctx) -> null, null);
        ToolCall call = new ToolCall(CallId.create(), new de.augmentia.oc.engine.core.model.ToolName("read"), JsonValue.of("x"));

        PermissionRequest delegated = ToolAuthorizer.defaultAuthorizer().request(context, definition, call);
        PermissionRequest wrapped = new SkillAuthorizer(ToolAuthorizer.defaultAuthorizer()).request(context, definition, call);

        assertThat(wrapped.resources()).isEqualTo(delegated.resources());
        assertThat(wrapped.action()).isEqualTo(delegated.action());
    }

    private static final class SkillCatalogHolder {
        static de.augmentia.oc.engine.runtime.skill.SkillCatalog empty() {
            return de.augmentia.oc.engine.runtime.skill.SkillCatalog.empty();
        }
    }
}
