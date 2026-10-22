# 03 – Tool family: Bash (with HITL)

> Tool: `shell`. Plus the **interactive** variants as separate tools
> (`pty`, `persistent-pty`) and the HITL path through which an approval
> is granted.

## 1. Contract

```
Input:
  { command: Text
  , workdir?: Text
  , timeout?: Milliseconds     # 0 = off
  , background?: Boolean }

Output:
  { output: Text
  , exit?: Int
  , signal?: Text
  , shellID?: Text
  , truncated: Boolean
  , timeout?: Boolean
  , status?: "completed" | "running" }
```

| Rule | Value |
|---|---|
| Default timeout (foreground) | **2 minutes** |
| Timeout with `background` | **none** (0) |
| `timeout: 0` | timeout off |
| Environment variables | `AGENT=1`, `OPENCODE=1`, `AI_AGENT`, `OPENCODE_SESSION_ID` |
| Kill grace | **3 s** after the timeout, then hard abort |

> **No polling.** A background command notifies **itself** when it is
> finished – as a synthetic message into the session. The agent must not
> poll; this is stated verbatim in the tool description
> (`packages/core/src/tool/plugin/shell.ts:26-27`).

## 2. The scanner – command → resource + save pattern

Before a shell command runs, it is **decomposed**. Result:

```
type ScanResult =
  { commands: List<ScannedCommand>     # per command
  , directories: List<Path> }          # directories recognized by the scanner

type ScannedCommand =
  { resource: Text      # the command text (the checked resource)
  , save: Text }       # the save pattern, e.g. "git status *"
```

**Two scanners:**

| Scanner | When | Properties |
|---|---|---|
| **Structure scanner** (tree parser) | default | recognizes chaining, redirection, pipes, `&&`/`\|\|`, subcommands |
| **Portable scanner** | `experimental.portable_shell_scanner`, or when the parser library is missing, or in the local/dev channel | heuristic instead of grammar |

> **The scanner is interchangeable, the semantics are not.** Both deliver
> `commands` + `directories`.

### The save-pattern rule

```
save = "<prefix> *"
```

where `<prefix>` is the **literal tokens** of the command (the command
name plus its flags). Examples:

| Command | `resource` (check) | `save` (suggestion for “always") |
|---|---|---|
| `git status` | `git status` | `git status *` |
| `git commit -m "x"` | `git commit -m "x"` | `git commit *` |
| `ls -la /tmp` | `ls -la /tmp` | `ls *` |
| `cat a.txt && rm b.txt` | two entries: `cat a.txt` and `rm b.txt` | `cat *`, `rm *` |

> **Why two patterns:** `resource` is the **exact** text that is checked
> (the approval must match exactly). `save` is the **pattern** stored
> when “always allow” is chosen – with a placeholder so the approval
> also covers variants.
>
> **The pattern ` *` also matches the command without arguments** (see
> [`../permissions/02-evaluation.md`](../permissions/02-evaluation.md)):
> `git status *` therefore also allows `git status`.

### Chained commands

```
git status && rm -rf build
```

→ **two** entries, **two** checks. The approval must cover **both**;
`ask` beats `allow` (with multiple resources, `ask` wins).

### Directories

The scanner also reports the **directories** a command touches
(e.g. `cd /etc && ls`). These are **additionally** checked against the
directory boundary – a command that touches an external directory needs
**two** approvals: the directory boundary **and** the command approval.

> **Order:** directory boundary first, then the command. This is the
> same rule as for the filesystem tools.

## 3. Execution flow

```
1. Determine timeout:
     background → input.timeout ?? 0
     otherwise  → input.timeout ?? 2 minutes

2. Choose shell program (compatible with the environment)

3. Build command:
     cwd        = input.workdir (or current directory)
     env        = standard + AGENT/OPENCODE/AI_AGENT/OPENCODE_SESSION_ID
     timeout    = from step 1

4. BEFORE start (prepare):
     a. resolve directory (cwd) → internal/external
     b. scan command → commands + directories
     c. resolve directories (cwd + all recognized directories)
     d. external_directory check for all
     e. permission check:
          action    = "shell"
          resources = all command.resource
          save      = all command.save
     f. check working directory for existence and type  ← immediately before start

5. Start process, output into a file (backing file)

6. Foreground: block until done
     end with error  → ToolFailure
     end with signal → ToolFailure
     timeout         → ToolFailure with hint about larger timeout
     success         → output + metadata
   Background: return immediately
     → { shellID, output: "Command moved to the background …", status: "running" }

7. Background: when the process ends → synthetic message into the session
     (state, output, duration) → the agent is informed
```

> **Step 4f is not optional.** The approval may have taken longer than
> the directory existed – so the working directory is checked **immediately
> before** start, not only during approval
> (`packages/core/src/tool/plugin/shell.ts:146-153`).

## 4. HITL – human approval

Bash is the most important case for HITL, because the resource (a
command) does not structurally point at a filesystem target.

```
Check "shell":
  1. Scanner → commands
  2. For each command: does a rule match (action="shell", resource=<command text>)?
  3. No match → ask
  4. Ask:
       - show: command, arguments, context (session, agent)
       - answer: once | always | reject
  5. "always" → store save pattern (per project)
  6. "reject":
       - without feedback → abort (the step ends, the model sees NOTHING)
       - with feedback   → tool error, the model may retry
```

### The HITL state model

```
type Approval =
  | AutoApprove        # everything allowed
  | DenyAll            # everything blocked
  | ReviewList{ actions: List<Action> }   # only these actions are checked
  | Interactive{ provider: ApprovalProvider }   # ask a human
```

```
type ApprovalProvider = (action: Action, context: Text) -> ApprovalResult
type ApprovalResult =
  | Approved
  | Denied { feedback: Option<Text> }
```

> **The HITL path is a branch in the permission automaton**, not a
> separate mechanism. It replaces the `ask` branch: instead of “the user
> answers in the prompt dock", the HITL provider asks (console, UI,
> email, Kafka).
>
> **The coupling:** an approval can take **longer** than the session in
> which it was requested. That is why the approval is a **checkpoint**,
> not an in-memory promise: the agent is paused and resumed later.

### Checkpoint variant

```
1. Check whether this action needs approval
2. Create checkpoint (session, action, arguments, status: open)
3. Pause agent
4. Wait for resolution:
     approved → resume agent, execution runs
     rejected → stop agent, rejection with feedback
     timeout  → as rejected
5. Clean up checkpoint
```

> **The agent is paused, not terminated.** Execution resumes at the same
> point – like a continuation after an incomplete model stream, only
> triggered by a human.

## 5. Interactive terminals (PTY)

Two additional tools, **separate** from the `shell` tool:

| Tool | Lifetime | Purpose |
|---|---|---|
| `pty` | **one call** | a one-off terminal for a single run (interactive commands) |
| `persistent-pty` | **across multiple calls** | a terminal that lives between calls (e.g. a running shell) |

```
type PtySession =
  { id: PtyID
  , sessionID: SessionID
  , status: "running" | "exited"
  , output: Text              # running output
  , exit: Option<Int> }
```

**Rules:**

| # | Rule |
|---|---|
| 1 | A PTY is **bound to the session**; it does not survive a session switch. |
| 2 | The client receives a **ticket** (short-lived, TTL **60 s**) to connect – no durable access right. |
| 3 | Output is held **bounded** (retention, cleanup time-controlled). |
| 4 | A PTY whose session ends is **terminated** – with a grace period. |

> **Why a ticket instead of a permission:** The browser cannot hold a
> TCP connection. The ticket mechanism separates “may watch" (short-lived,
> held by the caller) from “may terminate" (session ownership).

## 6. Output and truncation

```
Command output:
  full output       → backing file in the data directory
  visible preview   → truncated (line and byte limit)
  metadata.outputPath → path of the backing file
```

> The shell producer has **its own** limit; the generic bounding kicks in
> only when it marked nothing. The shell therefore always returns the
> **path** – that is how the model gets at the full output.

**Retention:** Shell output **7 days** (sweep hourly).

## 7. Errors

| Error | Cause | Visible |
|---|---|---|
| “Unable to execute command: …" | command not startable (file missing, not executable) | as tool error |
| “Command exceeded timeout of N ms. Retry with a larger timeout …" | timeout | as tool error |
| “Command cancelled" | aborted by the user | as tool error |
| Scanner error | command not analyzable | **error, but NO rejection** |
| Approval rejected | user | see HITL |
| Process exited with signal | signal | as tool error |

> **Scanner errors are not permission rejections.** A command that cannot
> be analyzed is **not** silently allowed and **not** reported as “you may
> not do that" – it is a technical error. Mixing the two produces either
> security holes or confusion.

## 8. Verified semantics (reference checkout)

| Concept | Location |
|---|---|
| Input/output, default timeout 2 min, environment variables | `packages/core/src/tool/plugin/shell.ts:48-101`, `:203-219` |
| Scanner → `commands` (resource/save) + `directories` | `packages/core/src/shell/parse.ts:18-21`, `:195-199`, `:262-287` |
| Directory check **and** command check, order | `packages/core/src/tool/plugin/shell.ts:129-145` |
| Working directory checked immediately before start | `packages/core/src/tool/plugin/shell.ts:146-153` |
| Background: return immediately + notification | `packages/core/src/tool/plugin/shell.ts:254-257`, `:157-189` |
| `ask` holds the call until answered | `packages/core/src/permission.ts:243-259` |
| Three answers: `once`, `always`, `reject` | `packages/schema/src/permission.ts:17-43` |
| PTY ticket TTL 60 s | `packages/core/src/pty/ticket.ts:9` |
| Kill grace 3 s | `packages/core/src/shell.ts:316`, `:385` |