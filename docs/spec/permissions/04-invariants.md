# 04 – Invariants

> The testable rules of the permission system, numbered `P-01…`.
> Check types: **State** (visible in the rules) · **Flow** (only from
> temporal behavior) · **Effect** (on the outside world) · **Log**.

## P-01 Basic rules of evaluation

| # | Rule | Check type |
|---|---|---|
| **P-01** | WHEN several rules match, THEN the **last** wins. | State |
| **P-02** | WHEN no rule matches, THEN “ask" applies. | State |
| **P-03** | WHEN any resource evaluates to “deny", THEN the operation is blocked – even across a stored approval. | State |
| **P-04** | WHEN several resources are checked, THEN “ask" wins over “allow". | State |
| **P-05** | WHEN an extension hook changes the evaluation, THEN it may change effect **and** message. | State |
| **P-06** | WHEN no agent is present, THEN everything is **denied**. | State |
| **P-07** | WHEN the rule chain is built, THEN the order is: agent rules, then session rules. | State |

## P-02 The three replies

| # | Rule | Check type |
|---|---|---|
| **P-10** | WHEN “once" is answered, THEN **nothing** is stored. | State |
| **P-11** | WHEN “always" is answered, THEN the pattern suggested by the **tool** is stored, **per project**. | State |
| **P-12** | WHEN “always" is answered, THEN all other open requests are **re-evaluated** and approved automatically where applicable. | Flow |
| **P-13** | WHEN “always" is answered and no save pattern is present, THEN **nothing** is stored. | State |
| **P-14** | WHEN “reject" is answered, THEN **all** parallel requests of the same session are rejected. | Flow |
| **P-15** | WHEN “reject" is answered with feedback, THEN the feedback applies to the **entire** rejection. | State |

## P-03 Channel separation

| # | Rule | Check type |
|---|---|---|
| **P-20** | WHEN rejected without feedback, THEN the error is **not** model-facing. | State |
| **P-21** | WHEN rejected without feedback, THEN the **step** ends (no further tool call in this step). | Flow |
| **P-22** | WHEN rejected with feedback, THEN the error stays model-facing and the model may continue. | State |
| **P-23** | WHEN a denial error arises, THEN it is model-facing (it is an error, not a rejection). | State |
| **P-24** | WHEN a rejection without feedback arises, THEN it is **not** converted into an ordinary error. | Effect |

## P-04 Patterns

| # | Rule | Check type |
|---|---|---|
| **P-30** | WHEN a pattern contains `*`, THEN it matches any characters (including `/`). | State |
| **P-31** | WHEN a pattern contains `?`, THEN it matches exactly one character. | State |
| **P-32** | WHEN a pattern ends in ` *`, THEN it also matches the value **without** the last argument. | State |
| **P-33** | WHEN matching is checked, THEN it is anchored to the **whole** value. | State |
| **P-34** | WHEN the value or the pattern contains backslashes, THEN they are normalized to `/`. | State |
| **P-35** | WHEN the operating system is Windows, THEN matching is case-insensitive; otherwise case-sensitive. | State |
| **P-36** | WHEN the pattern contains characters other than `*` and `?`, THEN they are to be understood **literally** (no glob syntax). | State |

## P-05 Boundaries and catalog

| # | Rule | Check type |
|---|---|---|
| **P-40** | WHEN a path lies outside location and project worktree, THEN the **directory boundary** is checked first, then the file rule. | State |
| **P-41** | WHEN the directory boundary is not expressly governed, THEN “allow" applies – external accesses raise **no** prompt. | State |
| **P-42** | WHEN a tool is filtered from the catalog, THEN that is **catalog visibility**, not execution authorization. | Flow |
| **P-43** | WHEN a tool has been filtered from the catalog, THEN its nested tools keep their own rules. | State |
| **P-44** | WHEN `execute` is denied with `*`, THEN Code Mode and all tools reachable only through it disappear. | State |
| **P-45** | WHEN `write`, `edit` or `patch` is checked, THEN the action is **`edit`** for all three. | State |
| **P-46** | WHEN `grep` is checked, THEN the resource is the **regex**, not the search path. | State |
| **P-47** | WHEN `glob` is checked, THEN the resource is the **pattern**. | State |
| **P-48** | WHEN `shell` is checked, THEN the resource is the **scanned command string** per subcommand. | State |
| **P-49** | WHEN a shell command is chained, THEN **every** subcommand is checked individually. | State |
| **P-50** | WHEN a command touches an external directory, THEN the directory boundary is additionally checked. | State |

## P-06 Organization rules

| # | Rule | Check type |
|---|---|---|
| **P-55** | WHEN an organization rule `tool.use` matches with “deny", THEN the effect is set to “deny" – even across stored approvals. | State |
| **P-56** | WHEN an organization rule `tool.use` matches with “allow", THEN it **never** grants access; it only lifts a broader denial. | State |
| **P-57** | WHEN an organization rule `provider.use` matches with “deny", THEN the provider is **removed from the catalog** – even with valid credentials. | State |
| **P-58** | WHEN an organization rule `integration.use` matches, THEN the integration is **not connected** and its instruments do not appear. | State |
| **P-59** | WHEN organization rules are applied, THEN that happens **after** all other catalog transformations. | Flow |
| **P-60** | WHEN fetching the organization rules fails, THEN the **last** known configuration stays active. | State |
| **P-61** | WHEN the connection is dropped, THEN the rules are **emptied** (not retained). | State |
| **P-62** | WHEN rules of different connections are present, THEN they are **never** merged. | State |
| **P-63** | WHEN an extension tries to change organization rules, THEN it is **blocked**. | Effect |

## P-07 Events and visibility

| # | Rule | Check type |
|---|---|---|
| **P-70** | WHEN an approval is requested, THEN the event is delivered **live** but **not** logged. | Log |
| **P-71** | WHEN an approval is answered, THEN the event is delivered **live** but **not** logged. | Log |
| **P-72** | WHEN an approval is answered “always", THEN **one** stored row arises – and only that one. | State |
| **P-73** | WHEN the same action and resource for the same project is stored **twice**, THEN **no** second row arises. | State |
| **P-74** | WHEN a stored approval is removed, THEN it applies to **no** further check. | State |

## P-08 System boundaries

| # | Rule |
|---|---|
| **P-80** | The permission system **does not sandbox** the agent. It is a usability feature, not a security boundary. |
| **P-81** | There is **no** permission audit log: request and reply are ephemeral; only the “always" approval is stored. |
| **P-82** | A stored approval applies **per project**, not per session. |
| **P-83** | A stored pattern can be **broader** than the triggering call (e.g. `["*"]`). This is intended: the pattern comes from the tool. |
| **P-84** | An organization's rules are **binary** – “allow" there means “not forbidden", not “approved". |

## Acceptance criteria

| # | Check | Kind |
|---|---|---|
| A-01 | A denial rule beats a stored “always allow" approval. | Unit test |
| A-02 | “Ask" beats “allow" with two resources. | Unit test |
| A-03 | A pattern `git status *` also matches `git status`. | Unit test |
| A-04 | A pattern `*.env` does **not** match `x.envy`. | Unit test |
| A-05 | A rejection without feedback produces **no** model-facing text. | Integration test |
| A-06 | A rejection with feedback produces model-facing text, and the next step continues. | Integration test |
| A-07 | “Always" stores **one** row and releases all other matching requests. | Integration test |
| A-08 | “Reject" rejects all parallel requests of the session. | Integration test |
| A-09 | An external path checks the **directory boundary first**. | Unit test |
| A-10 | A Policy-`deny` beats a stored approval; a Policy-`allow` grants **nothing**. | Unit test |
| A-11 | A provider with Policy-`deny` disappears from the catalog. | Integration test |
| A-12 | `permission.asked` does **not** appear in the event log. | Integration test |