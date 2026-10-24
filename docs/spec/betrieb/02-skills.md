# 02 – Skills

> **Question:** How does the model get additional domain knowledge without every
> skill standing in every prompt?
>
> **Answer:** A skill is **text with metadata**. The model sees only ID, name and
> description; it loads the content on request. This "show first, load later" is
> the whole mechanism.

A skill is **not a tool** and **not a plugin**. It executes nothing. It
delivers instructions and files the model can read.

## 1. Sources

| Source | Form | Base environment | Evidence |
|---|---|---|---|
| Project configuration | `<directory>/skill/**/SKILL.md` | **yes** | `packages/core/src/config/plugin/skill.ts:89` |
| Project configuration | `<directory>/skills/**/SKILL.md` | **yes** | `packages/core/src/config/plugin/skill.ts:90` |
| Configuration | `skills: ["path", "https://…"]` | no | `packages/core/src/config/normalize.ts:234` |
| URL catalog | `index.json` with `{skills: [{name, version?, files}]}` | no | `packages/core/src/skill/discovery.ts:48` |
| Embedded | skills shipped with the installation | no | `packages/core/src/plugin/skill.ts:12-13,33,42` |

The base environment uses **only** the project directory. Thus the set of skills
is as large as the repository – not as large as the internet.

## 2. File format

A skill is a Markdown file with a YAML header:

```markdown
---
name: commit
description: Use when the user asks to commit, stage, or write a commit message.
metadata:
  opencode/autoinvoke: true
disable-model-invocation: false
---
# Commit
…instructions…
```

| Field | Required | Meaning | Evidence |
|---|---|---|---|
| `name` | no | display name; default is the ID | `packages/core/src/config/plugin/skill-file.ts:11` |
| `description` | no | **only** text the model sees beforehand | `packages/core/src/config/plugin/skill-file.ts:12` |
| `metadata` | no | free data; `opencode/autoinvoke` controls visibility | `packages/core/src/config/plugin/skill-file.ts:13,48` |
| `disable-model-invocation` | no | `true` = do not include in the list | `packages/core/src/config/plugin/skill-file.ts:14,49` |

The **body** is the text after the frontmatter. It is loaded unchanged. There is
no template substitution and no variables.

### ID and name

| Case | ID | Name (without `name`) |
|---|---|---|
| `<dir>/SKILL.md` | name of the directory `<dir>` | ID |
| `<dir>/<file>.md` (depth 0) | `<file>` without `.md` | ID |

Evidence: `packages/core/src/config/plugin/skill-file.ts:44-47`.

> **The ID is the contract.** It stands in the system part, in the tool call
> and in the rights rule. The name is display only.

## 3. Discovery

```
function discover(source):
  files = []
  queue = [(source, depth=0)]
  for (directory, depth) in queue:
      entries = read_directory(directory)
      entries = filter(entries, name does not start with ".")
      if "SKILL.md" in entries:
          files += [SKILL.md]
          continue                    # this directory is a skill
      if depth == 0:
          files += all_*.md(entries)
      if depth < 6:
          queue += subdirectories(entries, depth+1)
  sort(files)
```

Evidence: `packages/core/src/config/plugin/skill-scan.ts:7,18-40`.

| Rule | Value | Evidence |
|---|---|---|
| maximum depth | 6 | `packages/core/src/config/plugin/skill-scan.ts:7` |
| hidden entries | skipped | `packages/core/src/config/plugin/skill-scan.ts:9,20` |
| directory with `SKILL.md` | only this file, no recursion | `packages/core/src/config/plugin/skill-scan.ts:28-31` |
| flat skills | `*.md` in the root | `packages/core/src/config/plugin/skill-scan.ts:32-36` |
| order | sorted by path | `packages/core/src/config/plugin/skill-scan.ts:43` |

**Changes at runtime.** A file watcher observes the sources; several events are
batched for 100 ms, then reloaded
(`packages/core/src/config/plugin/skill.ts:173`). The next model step sees the
change – without a restart.

## 4. Visibility

Not every discovered skill is offered to the model. The rule is:

```
visible(skill) =
      skill.description is set
  and skill.autoinvoke != false
  and allowed("skill", skill.id, rules) != deny
```

Evidence: `packages/core/src/skill/instructions.ts:82`,
`packages/core/src/skill.ts:33`.

The visible skills are **sorted by ID** and rendered as one block in the
**instruction part** of the system prompt:

```text
Skills provide specialized instructions and workflows for specific tasks.
Use the skill tool to load a skill when a task matches its description.
The user may also invoke a skill directly. When that happens, its instructions
appear in the conversation as a <skill_content> block, the same shape the skill
tool returns. A skill that is already present this way does not need to be
invoked again.

<available_skills>
  <skill>
    <id>commit</id>
    <name>commit</name>
    <description>Use when the user asks to commit, stage, or write a commit message.</description>
  </skill>
</available_skills>
```

With zero skills it says: `No skills are currently available.`
(`packages/core/src/skill/instructions.ts:31`). The block is **not** its own
system block; it is part of the instruction base (second block, see
[`../session-engine/05-provider.md`](../session-engine/05-provider.md) P-02).

### Changes in the system part

The block has the key `core/skill-guidance`
(`packages/core/src/skill/instructions.ts:88`). When the list changes, the whole
block is **not** repeated, but a delta is rendered:

* new skills: "New skills are available …"
* changed skills: "The following skills have changed …"
* removed skills: "The following skill IDs are no longer available and must
  not be used: …"

The end value is durable (`session.instructions.updated`); a client need not
render it.

> **Decision (closes PRM-D):** The **list** stands in the system part, the
> **content** comes as a tool result or as an attachment to the user message.
> Thus the cache prefix stays stable as long as the list does not change.

## 5. Activation

### By the model

```json
{ "id": "commit" }
```

The tool `skill` (`packages/core/src/tool/plugin/skill.ts:12`) checks the ID,
asks the right `skill` for exactly this ID and returns:

```text
<skill_content name="commit">
# Skill: commit

…body of SKILL.md…

Base directory for this skill: /path/to/.opencode/skills/commit
Relative paths in this skill (e.g. scripts/, reference/) are relative to this base directory.
Note: file list is sampled.

<skill_files>
<file>/path/to/.opencode/skills/commit/scripts/check.sh</file>
</skill_files>
</skill_content>
```

Evidence: `packages/core/src/skill.ts:36-52`.

| Property | Value | Evidence |
|---|---|---|
| file list | only for `SKILL.md` skills | `packages/core/src/skill.ts:57` |
| number of files | at most 10, sorted | `packages/core/src/skill.ts:68` |
| flat skills | empty file list | `packages/core/src/skill.ts:57` |
| base directory | directory of the skill file | `packages/core/src/skill.ts:37` |
| right | action `skill`, resource = ID, storable | `packages/core/src/tool/plugin/skill.ts:51` |

### By the user

The prompt can attach skills directly:

```json
{
  "text": "Please commit according to the project convention.",
  "skills": [{ "id": "commit" }]
}
```

Evidence: `packages/schema/src/prompt-input.ts:33`. The engine loads the skill and
attaches the content as a **skill attachment to the user message**
(`packages/core/src/session/prompt.ts:56`). In the model transcript the
`<skill_content>` block appears as part of the user message
(`packages/core/src/session/runner/to-llm-message.ts:257`); the compact history
view shows it as `[Skill activated: <name>]`
(`packages/core/src/session/recent-text.ts:24`). An already loaded skill need not
be called again – the system text says so explicitly
(`packages/core/src/skill/instructions.ts:28`).

## 6. The three skills of the base environment

Location: `.opencode/skills/<id>/SKILL.md` in the project directory. Three
suffice; each covers a recurring task without prescribing normal operation to
the model.

### 6.1 `commit`

```markdown
---
name: commit
description: Use when the user asks to commit, stage, or write a commit message. Reads the diff, proposes a Conventional Commit message, and commits only the paths the user named.
---
# Commit
1. Read the change before writing anything: `git status --short`, `git diff`, `git diff --staged`.
2. Group the change by intent. If it contains unrelated edits, propose separate commits.
3. Write the message as `type(scope): summary` with type in feat, fix, docs, chore, refactor, test.
4. Stage only the paths the user named. Never `git add -A` unless the user asked for everything.
5. Commit. Do not push unless the user asked to push.
6. Report the commit hash and the subject line.
```

### 6.2 `review`

```markdown
---
name: review
description: Use when the user asks for a code review, a risk check, or feedback on a change. Reads the diff and the surrounding code and reports findings with file:line, without editing files.
---
# Review
1. Establish the change: `git diff <base>...HEAD` or the paths the user named.
2. Read the surrounding code, not only the diff.
3. Report findings in severity order: correctness, data loss, security, performance, style.
4. Every finding cites `path:line` and states the failure mode.
5. If nothing is wrong, say so and name what you checked.
6. Do not edit files. Do not run commands that mutate state.
```

### 6.3 `test`

```markdown
---
name: test
description: Use when the user asks to run tests for a package or verify a change. Runs the documented test command and reports the first actionable failure.
---
# Test
1. Find the package that owns the change.
2. Use the command documented in that package (README, package.json scripts, build file). Do not invent a runner.
3. Run the smallest scope that covers the change first, then the package suite.
4. On failure, report the test name, the first actionable error, and the file:line.
5. Do not change tests to make them pass unless the user asked for that.
6. Report what ran, what passed, and what could not run.
```

> **Why exactly these three?** They cover the three most frequent interventions:
> committing something (`commit`), checking something (`review`), proving something
> (`test`). None of them needs its own tool; all three are pure text.

### Limits of the base environment

| Limit | Value | Character |
|---|---|---|
| number of skills | 3 | decision (not reference) |
| description | ≤ 500 characters | decision (token budget) |
| body | ≤ 8 KiB | decision (one prompt block) |
| files per skill | ≤ 10 | reference behavior (`packages/core/src/skill.ts:68`) |
| search depth | 6 | reference behavior (`packages/core/src/config/plugin/skill-scan.ts:7`) |

The values are **upper bounds**, not obligations. A skill that exceeds them is
not silently truncated but **rejected** and logged.

## 7. Tools and rights

The base environment has seven tools; `skill` is one of them (`U-04`). It has
no external effect: it reads files already in the project. It is its own action
(`skill`, resource = ID, suggestion `[*]`, see
[`../permissions/03-effect-matrix.md`](../permissions/03-effect-matrix.md) §1).
The base environment allows it through the agent rule, but can set it to `ask`
or `deny`. A `deny` on an ID hides the skill **and** prevents loading.

## 8. Error cases

| Case | Behavior | Evidence |
|---|---|---|
| YAML is invalid | file skipped, logged | `packages/core/src/config/plugin/skill-file.ts:42` |
| file is not Markdown | skipped | `packages/core/src/config/plugin/skill-file.ts:40` |
| `description` missing | skill loadable, but **not listed** | `packages/core/src/skill/instructions.ts:82` |
| `autoinvoke: false` | not listed, but loadable by ID | `packages/core/src/skill/instructions.ts:82` |
| two skills, same ID | **later source wins** | `packages/core/src/config/plugin/skill.ts:155` |
| right `deny` | not listed, loading fails | `packages/core/src/skill.ts:33` |
| file disappears | skill disappears on next reload | `packages/core/src/config/plugin/skill.ts:173` |
| skill ID unknown | tool error "Unable to load skill …" | `packages/core/src/tool/plugin/skill.ts:30` |

**Precedence with same ID.** The configuration delivers sources from "lowest to
highest priority" (`packages/core/src/config.ts:30`). The skills are written
into a map in this order; the last source wins
(`packages/core/src/config/plugin/skill.ts:155`). In practice: **project beats
global**; a later configured source beats an earlier one.

## 9. Rules

| # | Rule | Check |
|---|---|---|
| **SK-01** | A skill is text with metadata. It is loaded, never executed. | State |
| **SK-02** | The base environment discovers skills only under `<project>/skill` and `<project>/skills`. | State |
| **SK-03** | A directory with `SKILL.md` yields exactly this file and is not searched further; `*.md` in the root are flat skills; hidden entries are skipped; depth is limited to 6. | State |
| **SK-04** | The ID is the directory name or, for flat skills, the file name without `.md`. The name is `name` or the ID. | State |
| **SK-05** | With the same ID the source with higher priority wins; project beats global. | State |
| **SK-06** | A skill is **listed** when `description` is set, `autoinvoke != false` and the right is not `deny`. The list is sorted by ID. | State |
| **SK-07** | The list stands in the instruction part of the system prompt under the key `core/skill-guidance`; changes appear as a delta, removals are named. | Protocol |
| **SK-08** | Loading requires the right `skill` for the ID. A `deny` hides and blocks. | Effect |
| **SK-09** | Loaded content stands in `<skill_content name="…">` with base directory and at most 10 files; flat skills have an empty file list. | Protocol |
| **SK-10** | A skill explicitly attached in the prompt is loaded as an attachment to the user message, not as a second system block. | Flow |
| **SK-11** | Invalid frontmatter skips the file; a missing description makes the skill loadable but invisible. | State |
| **SK-12** | A skill change is picked up without a restart and applies from the next model step. | Flow |

## 10. Evidence

| Rule | Test | Kind |
|---|---|---|
| SK-01 | Skill with executable content is delivered only as text; no process execution. | pure |
| SK-02 | Skill outside the two directories is not found. | pure |
| SK-03 | `SKILL.md` at depth 2 is found; `.md` at depth 1 is not; depth 7 is not. | pure |
| SK-04 | Directory and file name yield the expected ID. | pure |
| SK-05 | same skill in global and project: project content wins. | pure |
| SK-06 | without `description` not listed; with `autoinvoke: false` not listed. | pure |
| SK-07 | two runs: first full, second delta; removal is named. | pure |
| SK-08 | `deny` on ID: loading fails with a rights error. | Integration |
| SK-09 | 12 files: at most 10 in the list; flat skill: empty list. | pure |
| SK-10 | prompt with `skills`: user message carries the attachment, system part unchanged. | Integration |
| SK-11 | broken YAML: file skipped, other skills remain. | pure |
| SK-12 | change file, wait 100 ms, next step sees new text. | Integration |

## 11. Open points

| Point | Question | Impact |
|---|---|---|
| **SK-A** | Should the limits from §6 (3 skills, 8 KiB) be hard or only recommended? | operations vs. flexibility |
| **SK-B** | Should a URL catalog pin versioned skills? | reproducibility over time |
| **SK-C** | Does `autoinvoke` need more than `true`/`false` (e.g. "only on match")? | model selection |
| **SK-D** | Should skill texts be redacted on session transfer? | the reference does it (`session/transfer.ts`), the base environment does not need it |

> **Decision (2026-10-22, SK-A…SK-D).** Limits warn softly (hard only for
> scan depth 6 / 10 files); URL catalog only on demand (manifest
> `{url, sha256}`); `autoinvoke` stays `true`/`false`; skill texts are removed
> by default on transfer.