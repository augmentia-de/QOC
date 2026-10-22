# 05 – Java 21 implementation

> The permission system in Java 21: types, pattern matching, rule chain,
> prompt.

## 1. Types

```java
public enum Effect { ALLOW, DENY, ASK }

public record Rule(String action, String resource, Effect effect) {}

public record PermissionRequest(
        String id,
        String sessionId,
        String action,
        List<String> resources,
        List<String> save,
        Map<String, Object> metadata,
        InvocationSource source,
        String message) {}

public enum Reply { ONCE, ALWAYS, REJECT }
```

> **`action` stays a `String`** – no enums. MCP tools bring their own
> actions (`<server>_<tool>`), and a `switch` evaluation over actions
> would be the wrong place for a closed set.

## 2. Pattern matching

```java
public final class Wildcard {

    private Wildcard() {}

    public static boolean match(String input, String pattern) {
        String normalizedInput   = input.replace('\\', '/');
        String normalizedPattern = pattern.replace('\\', '/');

        StringBuilder regex = new StringBuilder("^");
        List<String> literal = new ArrayList<>();

        for (int i = 0; i < normalizedPattern.length(); i++) {
            char c = normalizedPattern.charAt(i);
            if (c == '*')      { flush(literal, regex); regex.append(".*"); }
            else if (c == '?') { flush(literal, regex); regex.append('.'); }
            else               { literal.add(String.valueOf(c)); }
        }
        flush(literal, regex);

        // " *" at the end: also matches the value WITHOUT the last argument
        if (normalizedPattern.endsWith(" *")) {
            regex.setLength(regex.length() - 2);   // remove ".*"
            regex.append("( .*)?");
        }
        regex.append('$');

        int flags = Pattern.DOTALL | (isWindows() ? Pattern.CASE_INSENSITIVE : 0);
        return Pattern.compile(regex.toString(), flags).matcher(normalizedInput).matches();
    }

    private static void flush(List<String> literal, StringBuilder regex) {
        if (literal.isEmpty()) return;
        regex.append(Pattern.quote(String.join("", literal)));
        literal.clear();
    }
}
```

> **Why `Pattern.quote`:** literal characters like `.` `[` `(` must
> **not** have regex meaning. `Pattern.quote` per contiguous literal is
> correct **and** faster than the per-character variant.
>
> **The special case at the end** (`" *"` → `( .*)?`) is the reason why
> `git status *` also allows `git status`.

## 3. The rule chain

```java
public final class PermissionEngine {

    private final AgentRules agents;
    private final SessionRules sessions;
    private final SavedApprovals saved;
    private final HookRegistry hooks;
    private final ApprovalService approvals;

    public void assert(PermissionRequest request) {
        List<Rule> rules = concat(agents.of(request), sessions.of(request.sessionId()));
        if (rules.isEmpty()) rules = List.of(new Rule("*", "*", Effect.DENY));

        // 1. DENY first – beats every stored approval
        if (anyEvaluatesTo(rules, request, Effect.DENY)) {
            throw new PermissionRejectedException(request, matchingRules(rules, request));
        }

        // 2. + stored approvals
        List<Rule> all = concat(rules, saved.of(projectOf(request)));

        // 3. "ask" dominates "allow"
        Effect effect = anyEvaluatesTo(all, request, Effect.ASK) ? Effect.ASK : Effect.ALLOW;

        // 4. Extension hook
        PermissionEvaluation evaluation = hooks.evaluatePermission(request, effect);
        effect = evaluation.effect();

        switch (effect) {
            case ALLOW -> { }
            case DENY  -> throw new PermissionRejectedException(request, matchingRules(all, request), evaluation.message());
            case ASK   -> handleAsk(request, evaluation.message());
        }
    }

    private void handleAsk(PermissionRequest request, String message) {
        Reply reply = approvals.request(request, message);   // prompt dock OR HITL provider

        switch (reply) {
            case ONCE -> { }
            case ALWAYS -> {
                if (!request.save().isEmpty()) {
                    saved.add(projectOf(request), request.action(), request.save());
                    // re-evaluate all other open requests
                    approvals.revalidateOthers(request.sessionId());
                }
            }
            case REJECT -> {
                approvals.rejectAll(request.sessionId(), feedbackOf(request));
                if (feedbackOf(request) == null) {
                    // channel: ABORT. No model-facing text!
                    throw new Aborted("declined");
                }
                throw new PermissionRejectedException(request, feedbackOf(request));
            }
        }
    }

    private boolean anyEvaluatesTo(List<Rule> rules, PermissionRequest request, Effect wanted) {
        return request.resources().stream()
                .anyMatch(resource -> evaluate(rules, request.action(), resource) == wanted);
    }

    /** The last matching rule wins. */
    private Effect evaluate(List<Rule> rules, String action, String resource) {
        Rule hit = null;
        for (Rule rule : rules) {
            if (Wildcard.match(action, rule.action()) && Wildcard.match(resource, rule.resource())) {
                hit = rule;                       // overwrites → the last wins
            }
        }
        return hit == null ? Effect.ASK : hit.effect();
    }
}
```

> **`anyEvaluatesTo` is the shorthand for “any resource"** – and it is
> exactly the place where the mistake “a `deny` on one resource blocks
> everything" happens. That is why it stands **before** adding the stored
> approvals.

## 4. Stored approvals

```java
public record SavedApproval(String id, String projectId, String action, String resource) {}

public final class SavedApprovals {
    // UNIQUE (project_id, action, resource)
    public void add(String projectId, String action, List<String> resources) {
        for (String resource : resources) {
            insert("insert into permission (id, project_id, action, resource) values (?,?,?,?) "
                 + "on conflict (project_id, action, resource) do nothing",
                   Identifier.create("per"), projectId, action, resource);
        }
    }

    public List<Rule> of(String projectId) {
        return query("select action, resource from permission where project_id = ?", projectId)
                .stream()
                .map(r -> new Rule(r.action(), r.resource(), Effect.ALLOW))
                .toList();
    }
}
```

> **The stored approval is an `ALLOW` rule** – it is inserted into the
> rule chain **at the right place**, not treated separately. That way the
> denial pre-check applies automatically.

## 5. Channel separation in Java

```java
/** Channel 1: expected failure. */
public class PermissionRejectedException extends RuntimeException {
    private final PermissionRequest request;
    public PermissionRejectedException(PermissionRequest request) { … }
    public PermissionRejectedException(PermissionRequest request, String feedback) { … }
}

/** Channel 2: deliberate stop – not model-facing. */
public class Aborted extends RuntimeException {
    public Aborted(String reason) { super(reason); }
}
```

**The tool boundary catches only the first channel:**

```java
try {
    permissions.assert(request);
    result = tool.execute(input, ctx);
} catch (PermissionRejectedException denied) {
    return failed(denied);          // → model-facing
}
// Aborted passes through here – it ends the step without model text.
```

> **The decisive difference:** `Aborted` extends `RuntimeException` –
> `catch (PermissionRejectedException)` does **not** catch it. And
> `catch (Exception)` is **forbidden** at this point, because it would
> swallow `Aborted` (and defects).
>
> **Test for it:** a rejection test that checks the tool text is **empty**
> and the step was **ended**.

## 6. HITL

```java
public interface ApprovalService {
    Reply request(PermissionRequest request, String message);
    void revalidateOthers(String sessionId);
    void rejectAll(String sessionId, String feedback);
}

public final class HitlApprovalService implements ApprovalService {
    private final ApprovalProvider provider;   // console, UI, ticket, email

    @Override public Reply request(PermissionRequest request, String message) {
        ApprovalResult result = provider.requestApproval(request.action(), describe(request));
        return switch (result) {
            case Approved a -> Reply.ONCE;
            case Denied d   -> d.feedback() == null ? Reply.REJECT : Reply.REJECT_WITH_FEEDBACK;
        };
    }
}
```

> **HITL is a replacement for the answer source**, not for the
> mechanism. The mechanism stays: `ask` → answer → `once`/`always`/
> `reject`.

## 7. Organization rules (Policy)

```java
public final class PolicyEnforcer {
    private final List<PolicyStatement> statements;

    /** A Policy-“allow" never grants access – it only lifts a broader denial. */
    public Effect apply(Effect computed, String action, String resource) {
        Effect result = computed;
        for (PolicyStatement s : statements) {
            if (!s.action().equals("tool.use")) continue;
            if (!Wildcard.match(resource, resourceOf(s))) continue;
            if (s.effect() == DENY) return DENY;         // overrides everything
            if (s.effect() == ALLOW && result == DENY) result = computed;  // lifts only the Policy denial
        }
        return result;
    }
}
```

> **The policy is evaluated **after** the normal chain** – it is the
> **final authority**. And a Policy-`allow` does not feed into the
> normal chain; it lifts exclusively a **Policy** denial. That is why
> the code here is **separate** from `evaluate()`.

## 8. The Java pitfalls

| # | Pitfall | Countermeasure |
|---|---|---|
| 1 | `catch (Exception)` catches `Aborted` too → the agent sees “no" and retries | only `catch (PermissionRejectedException)` |
| 2 | Forgetting `Pattern.quote` → `.` matches every character | always quote literals |
| 3 | `(?i)` always active → on Linux `GIT` does not match `git` | flag only on Windows |
| 4 | Rules in a `HashSet` → order gone → “last wins" breaks | `List`, never `Set` |
| 5 | Stored approvals in a separate table instead of the chain → denial precedence bypassed | insert approvals as `ALLOW` rule **into** the chain |
| 6 | `Optional<String>` for the feedback decision → “no feedback" and “empty feedback" indistinguishable | `String` + `null`, or `Optional` **plus** separate flag |
| 7 | Forgetting `equals`/`hashCode` of the rule → `contains`/`indexOf` does not find duplicates | `record` (Java generates both) |