package de.augmentia.oc.engine.runtime.resilience;

import de.augmentia.oc.engine.core.model.DecisionRequest;
import de.augmentia.oc.engine.core.model.DecisionResult;
import de.augmentia.oc.engine.core.port.runtime.DecisionModel;
import de.augmentia.oc.engine.runtime.context.ContextBuilder;
import de.augmentia.oc.engine.runtime.permission.engine.PermissionEngine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionConsumerTest {

    private static DecisionModel model(String answer, boolean available) {
        return new DecisionModel() {
            @Override
            public DecisionResult decide(DecisionRequest request) {
                return new DecisionResult(request.taskType(), answer, List.of(answer),
                        List.of(0.9), 0.5, answer.equalsIgnoreCase("NO"), 1);
            }
            @Override
            public boolean isAvailable() { return available; }
        };
    }

    @Test
    void triageUsesDecisionModelWhenAvailable() {
        DecisionModel dm = model("YES", true);
        TriageService delegate = ctx -> new TriageService.TriageResult(false, "", "delegate");
        DecisionTriageService svc = new DecisionTriageService(delegate, dm);
        TriageService.TriageResult r = svc.analyze(new TriageService.TriageContext("t", "err", "in", "st", 1, 3));
        assertThat(r.retryable()).isTrue();
    }

    @Test
    void triageFallsBackWhenUnavailable() {
        DecisionModel dm = model("YES", false);
        TriageService delegate = ctx -> new TriageService.TriageResult(true, "x", "delegate");
        DecisionTriageService svc = new DecisionTriageService(delegate, dm);
        TriageService.TriageResult r = svc.analyze(new TriageService.TriageContext("t", "err", "in", "st", 1, 3));
        assertThat(r.explanation()).isEqualTo("delegate");
    }

    @Test
    void triageReturnsNonRetryableWhenModelSaysNo() {
        DecisionModel dm = model("NO", true);
        TriageService delegate = ctx -> new TriageService.TriageResult(true, "x", "delegate");
        DecisionTriageService svc = new DecisionTriageService(delegate, dm);
        TriageService.TriageResult r = svc.analyze(new TriageService.TriageContext("t", "err", "in", "st", 1, 3));
        assertThat(r.retryable()).isFalse();
    }

    @Test
    void triageHandlesNullContext() {
        DecisionModel dm = model("YES", true);
        TriageService delegate = ctx -> new TriageService.TriageResult(false, "", "delegate");
        DecisionTriageService svc = new DecisionTriageService(delegate, dm);
        TriageService.TriageResult r = svc.analyze(null);
        assertThat(r).isNotNull();
    }

    @Test
    void triageFallsBackOnException() {
        DecisionModel dm = new DecisionModel() {
            @Override
            public DecisionResult decide(DecisionRequest request) { throw new RuntimeException("boom"); }
            @Override
            public boolean isAvailable() { return true; }
        };
        TriageService delegate = ctx -> new TriageService.TriageResult(true, "x", "delegate");
        DecisionTriageService svc = new DecisionTriageService(delegate, dm);
        TriageService.TriageResult r = svc.analyze(new TriageService.TriageContext("t", "err", "in", "st", 1, 3));
        assertThat(r.explanation()).isEqualTo("delegate");
    }

    @Test
    void loopDetectorHeuristicStandsWhenUnavailable() {
        DecisionModel dm = model("NO", false);
        LoopDetector delegate = new LoopDetector.Default();
        DecisionLoopDetector ld = new DecisionLoopDetector(delegate, dm);
        // heuristic: 2 same calls in a row with threshold 2 -> loop
        var call = new de.augmentia.oc.engine.core.contract.tool.ToolCall(
                de.augmentia.oc.engine.core.model.CallId.create(),
                de.augmentia.oc.engine.core.model.ToolName.of("t"), new de.augmentia.oc.engine.core.json.JsonValue.Obj(java.util.Map.of()));
        assertThat(ld.isLoop(java.util.List.of(call), call, 2)).isTrue();
    }

    @Test
    void loopDetectorVetoesWhenModelSaysNo() {
        DecisionModel dm = model("NO", true);
        LoopDetector delegate = new LoopDetector.Default();
        DecisionLoopDetector ld = new DecisionLoopDetector(delegate, dm);
        var call = new de.augmentia.oc.engine.core.contract.tool.ToolCall(
                de.augmentia.oc.engine.core.model.CallId.create(),
                de.augmentia.oc.engine.core.model.ToolName.of("t"), new de.augmentia.oc.engine.core.json.JsonValue.Obj(java.util.Map.of()));
        assertThat(ld.isLoop(java.util.List.of(call), call, 2)).isFalse();
    }

    @Test
    void loopDetectorConfirmsWhenModelSaysYes() {
        DecisionModel dm = model("YES", true);
        LoopDetector delegate = new LoopDetector.Default();
        DecisionLoopDetector ld = new DecisionLoopDetector(delegate, dm);
        var call = new de.augmentia.oc.engine.core.contract.tool.ToolCall(
                de.augmentia.oc.engine.core.model.CallId.create(),
                de.augmentia.oc.engine.core.model.ToolName.of("t"), new de.augmentia.oc.engine.core.json.JsonValue.Obj(java.util.Map.of()));
        assertThat(ld.isLoop(java.util.List.of(call), call, 2)).isTrue();
    }

    @Test
    void loopDetectorFailOpenOnException() {
        DecisionModel dm = new DecisionModel() {
            @Override
            public DecisionResult decide(DecisionRequest request) { throw new RuntimeException("boom"); }
            @Override
            public boolean isAvailable() { return true; }
        };
        LoopDetector delegate = new LoopDetector.Default();
        DecisionLoopDetector ld = new DecisionLoopDetector(delegate, dm);
        var call = new de.augmentia.oc.engine.core.contract.tool.ToolCall(
                de.augmentia.oc.engine.core.model.CallId.create(),
                de.augmentia.oc.engine.core.model.ToolName.of("t"), new de.augmentia.oc.engine.core.json.JsonValue.Obj(java.util.Map.of()));
        assertThat(ld.isLoop(java.util.List.of(call), call, 2)).isTrue();
    }

    @Test
    void permissionEngineIsHighRiskWithModel() {
        DecisionModel dm = model("HIGH", true);
        PermissionEngine pe = new PermissionEngine(de.augmentia.oc.engine.core.model.Option.none(),
                de.augmentia.oc.engine.core.model.Option.none(),
                de.augmentia.oc.engine.core.model.Option.none(),
                de.augmentia.oc.engine.core.model.Option.none());
        pe.setDecisionModel(dm);
        var sessionId = de.augmentia.oc.engine.core.model.SessionId.create(null);
        var reqId = de.augmentia.oc.engine.core.model.PermissionRequestId.create();
        var inv = new de.augmentia.oc.engine.core.contract.permission.PermissionRequest.InvocationSource("tool", null, null);
        var req = new de.augmentia.oc.engine.core.contract.permission.PermissionRequest(
                reqId, sessionId, "read", java.util.List.of("file.txt"), java.util.List.of(),
                java.util.Map.of(), inv, "msg");
        var check = new de.augmentia.oc.engine.core.eval.PermissionCheck(
                req, de.augmentia.oc.engine.core.model.Option.none(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), java.util.List.of());
        assertThat(pe.isHighRisk(check)).isTrue();
    }

    @Test
    void permissionEngineDeniesHighRiskAsk() {
        DecisionModel dm = model("HIGH", true);
        PermissionEngine pe = new PermissionEngine(de.augmentia.oc.engine.core.model.Option.none(),
                de.augmentia.oc.engine.core.model.Option.none(),
                de.augmentia.oc.engine.core.model.Option.none(),
                de.augmentia.oc.engine.core.model.Option.none());
        pe.setDecisionModel(dm);
        var sessionId = de.augmentia.oc.engine.core.model.SessionId.create(null);
        var reqId = de.augmentia.oc.engine.core.model.PermissionRequestId.create();
        var inv = new de.augmentia.oc.engine.core.contract.permission.PermissionRequest.InvocationSource("tool", null, null);
        var req = new de.augmentia.oc.engine.core.contract.permission.PermissionRequest(
                reqId, sessionId, "read", java.util.List.of("file.txt"), java.util.List.of(),
                java.util.Map.of(), inv, "msg");
        var check = new de.augmentia.oc.engine.core.eval.PermissionCheck(
                req, de.augmentia.oc.engine.core.model.Option.some(de.augmentia.oc.engine.core.model.AgentId.create()),
                java.util.List.of(new de.augmentia.oc.engine.core.model.Rule("*", "*",
                        de.augmentia.oc.engine.core.model.Effect.ASK)),
                java.util.List.of(), java.util.List.of(), java.util.List.of());
        var decision = pe.decide(check);
        assertThat(decision).isInstanceOf(de.augmentia.oc.engine.core.eval.PermissionDecision.Denied.class);
    }

    @Test
    void permissionEngineIsHighRiskFalseWhenUnavailable() {
        PermissionEngine pe = new PermissionEngine(de.augmentia.oc.engine.core.model.Option.none(),
                de.augmentia.oc.engine.core.model.Option.none(),
                de.augmentia.oc.engine.core.model.Option.none(),
                de.augmentia.oc.engine.core.model.Option.none());
        pe.setDecisionModel(new de.augmentia.oc.engine.core.noop.NoOpDecisionModel());
        assertThat(pe.isHighRisk(null)).isFalse();
    }

    @Test
    void contextBuilderIsAnswerableFailOpen() {
        ContextBuilder cb = new ContextBuilder(new de.augmentia.oc.engine.testing.InMemoryConversation(),
                de.augmentia.oc.engine.runtime.skill.SkillCatalog.empty());
        cb.setDecisionModel(new de.augmentia.oc.engine.core.noop.NoOpDecisionModel());
        assertThat(cb.isAnswerable("q", "ctx")).isTrue();
    }

    @Test
    void contextBuilderIsAnswerableWithModel() {
        DecisionModel dm = model("NO", true);
        ContextBuilder cb = new ContextBuilder(new de.augmentia.oc.engine.testing.InMemoryConversation(),
                de.augmentia.oc.engine.runtime.skill.SkillCatalog.empty());
        cb.setDecisionModel(dm);
        assertThat(cb.isAnswerable("q", "ctx")).isFalse();
        assertThat(cb.decisionModel()).isSameAs(dm);
    }

    @Test
    void noOpDecisionModelIsUnavailable() {
        assertThat(new de.augmentia.oc.engine.core.noop.NoOpDecisionModel().isAvailable()).isFalse();
    }

    @Test
    void decisionModelDefaultIsAvailable() {
        DecisionModel dm = new DecisionModel() {
            @Override
            public DecisionResult decide(DecisionRequest request) {
                return new DecisionResult(request.taskType(), "", java.util.List.of(), java.util.List.of(), 0.0, true, 0);
            }
        };
        assertThat(dm.isAvailable()).isTrue();
    }

    @Test
    void defaultCallbackMethodsCovered() {
        de.augmentia.oc.engine.runtime.callback.EventPublisher ep = new de.augmentia.oc.engine.runtime.callback.EventPublisher() {
            public void publishLive(String sessionId, String json) { /* no-op */ }
        };
        ep.publishToolResult("s", "c", "r");
        de.augmentia.oc.engine.runtime.callback.EventPublisher noop = de.augmentia.oc.engine.runtime.callback.EventPublisher.noop();
        noop.publishToolResult("s", "c", "r");
        noop.publishLive("s", "j");
        de.augmentia.oc.engine.runtime.callback.TracerProvider tp = new de.augmentia.oc.engine.runtime.callback.TracerProvider() {
            public Object startSpan(String name, java.util.Map<String, String> attributes) { return null; }
        };
        tp.endSpan(null);
        de.augmentia.oc.engine.runtime.callback.TracerProvider noopTp = de.augmentia.oc.engine.runtime.callback.TracerProvider.noop();
        noopTp.endSpan(null);
        noopTp.startSpan("n", java.util.Map.of());
    }

    @Test
    void callIdEqualsAndHashCode() {
        var a = de.augmentia.oc.engine.core.model.CallId.create();
        var b = de.augmentia.oc.engine.core.model.CallId.create();
        assertThat(a.equals(a)).isTrue();
        assertThat(a.equals(null)).isFalse();
        assertThat(a.equals("x")).isFalse();
        assertThat(a.equals(b)).isFalse();
        assertThat(a.hashCode()).isNotEqualTo(0);
        assertThat(a.toString()).startsWith("call_");
    }

    @Test
    void callIdParseNullThrows() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                de.augmentia.oc.engine.core.model.CallId.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void backgroundTasksFindRunningCoversReturn() {
        var bt = new de.augmentia.oc.engine.runtime.tool.subagent.BackgroundTasks();
        var parent = de.augmentia.oc.engine.core.model.SessionId.create(null);
        String id = bt.create(parent, "agent", "prompt");
        assertThat(bt.findRunning(parent, "agent", "prompt")).isEqualTo(id);
    }

    @Test
    void contextBuilderDefaultsCovered() {
        assertThat(de.augmentia.oc.engine.runtime.context.ContextBuilder.allowAllDefaults()).isNotEmpty();
        assertThat(de.augmentia.oc.engine.runtime.context.ContextBuilder.defaults("allow-all")).isNotEmpty();
        assertThat(de.augmentia.oc.engine.runtime.context.ContextBuilder.defaults("least-privilege")).isNotEmpty();
        assertThat(de.augmentia.oc.engine.runtime.context.ContextBuilder.leastPrivilegeDefaults()).isNotEmpty();
    }

    @Test
    void contextBuilderIsAnswerableFailOpenOnException() {
        DecisionModel dm = new DecisionModel() {
            @Override
            public DecisionResult decide(DecisionRequest request) { throw new RuntimeException("boom"); }
            @Override
            public boolean isAvailable() { return true; }
        };
        ContextBuilder cb = new ContextBuilder(new de.augmentia.oc.engine.testing.InMemoryConversation(),
                de.augmentia.oc.engine.runtime.skill.SkillCatalog.empty());
        cb.setDecisionModel(dm);
        assertThat(cb.isAnswerable("q", "ctx")).isTrue();
    }

    @Test
    void permissionEngineIsHighRiskFailOpenOnException() {
        DecisionModel dm = new DecisionModel() {
            @Override
            public DecisionResult decide(DecisionRequest request) { throw new RuntimeException("boom"); }
            @Override
            public boolean isAvailable() { return true; }
        };
        PermissionEngine pe = new PermissionEngine(de.augmentia.oc.engine.core.model.Option.none(),
                de.augmentia.oc.engine.core.model.Option.none(),
                de.augmentia.oc.engine.core.model.Option.none(),
                de.augmentia.oc.engine.core.model.Option.none());
        pe.setDecisionModel(dm);
        var sessionId = de.augmentia.oc.engine.core.model.SessionId.create(null);
        var reqId = de.augmentia.oc.engine.core.model.PermissionRequestId.create();
        var inv = new de.augmentia.oc.engine.core.contract.permission.PermissionRequest.InvocationSource("tool", null, null);
        var req = new de.augmentia.oc.engine.core.contract.permission.PermissionRequest(
                reqId, sessionId, "read", java.util.List.of("file.txt"), java.util.List.of(),
                java.util.Map.of(), inv, "msg");
        var check = new de.augmentia.oc.engine.core.eval.PermissionCheck(
                req, de.augmentia.oc.engine.core.model.Option.none(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), java.util.List.of());
        assertThat(pe.isHighRisk(check)).isFalse();
        assertThat(pe.decisionModel()).isSameAs(dm);
    }

    @Test
    void admissionConstructorNullThrows() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new de.augmentia.oc.engine.runtime.session.core.Admission(null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void admissionAdmitNullThrows() {
        var journal = new de.augmentia.oc.engine.testing.InMemoryJournal();
        var sessions = new de.augmentia.oc.engine.testing.InMemorySessions();
        var wake = new de.augmentia.oc.engine.testing.CountingWake();
        var admission = new de.augmentia.oc.engine.runtime.session.core.Admission(journal, sessions, wake);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                admission.admit(null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sessionServiceConstructorNullThrows() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new de.augmentia.oc.engine.runtime.session.core.SessionService(null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sessionServiceCreateNullThrows() {
        var journal = new de.augmentia.oc.engine.testing.InMemoryJournal();
        var sessions = new de.augmentia.oc.engine.testing.InMemorySessions();
        var wake = new de.augmentia.oc.engine.testing.CountingWake();
        var ss = new de.augmentia.oc.engine.runtime.session.core.SessionService(journal, sessions, wake, sessions::removeInbox);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                ss.create(null, null, de.augmentia.oc.engine.core.model.Option.none(), java.time.Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void admissionSetDecisionModel() {
        var journal = new de.augmentia.oc.engine.testing.InMemoryJournal();
        var sessions = new de.augmentia.oc.engine.testing.InMemorySessions();
        var wake = new de.augmentia.oc.engine.testing.CountingWake();
        var admission = new de.augmentia.oc.engine.runtime.session.core.Admission(journal, sessions, wake);
        DecisionModel dm = model("YES", true);
        admission.setDecisionModel(dm);
        assertThat(admission.decisionModel()).isSameAs(dm);
    }

    @Test
    void sessionServiceSetDecisionModel() {
        var journal = new de.augmentia.oc.engine.testing.InMemoryJournal();
        var sessions = new de.augmentia.oc.engine.testing.InMemorySessions();
        var wake = new de.augmentia.oc.engine.testing.CountingWake();
        var ss = new de.augmentia.oc.engine.runtime.session.core.SessionService(
                journal, sessions, wake, sessions::removeInbox);
        DecisionModel dm = model("YES", true);
        ss.setDecisionModel(dm);
        assertThat(ss.decisionModel()).isSameAs(dm);
    }
}
