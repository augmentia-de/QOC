# 02 – Tool family: filesystem

> Tools: `read`, `write`, `edit`, `patch`, `glob`, `grep`.
> Common action: `read` (read-only) and **`edit`** (for `write`, `edit`
> and `patch`).

## 1. Path resolution

```
resolve(path, kind) =
    normalize:
        ~  → home directory
        ~/ → home directory
        Windows path normalization (backslashes, drive letters)
    absolute ← resolve relative to the location's directory

    internal ← absolute lies inside location.directory
          OR inside the project worktree (except its root)

    if internal:
        resource ← path relative to the location directory   (or "." )
        return { internal: yes, absolute, resource }

    else:
        directory ← kind = "directory" ? absolute : parent(absolute)
        return { internal: no, absolute, resource: absolutePath,
                 boundary: { action: "external_directory"
                           , directory
                           , resource: directory + "/*"
                           , save: projectRoot + "/*" } }
```

> **The boundary is the checkpoint for “outside”.** It is checked **before**
> the action check (`read`, `edit`) – the directory boundary is the broader
> condition (`packages/core/src/file-access.ts:153-159`).
>
> **Decision (2026-10-22, O-07).** Whether a path is “external” is checked
> against the global working directory plus the configured allow-list
> (Read/Write); accesses outside are **denied**, not “asked” or “allowed”.
> See [`permissions/03-effect-matrix.md`](./../permissions/03-effect-matrix.md) §5.

### Order of checks

```
1. external_directory   ← only if the path lies outside
2. read | edit | glob | grep
```

> The order is reversible **only** in effect: both checks must pass. But
> the order determines **which** rule the user sees when both fail.

## 2. `read`

```
Input:  { path, offset?, limit? }
Output:  File | Text page | Directory listing
```

**Flow:**

```
1. authorize_read_access(path)          → external_directory?, then read
2. read page (offset, limit)
3. if not found:
     a. alternative path: same basename, normalized (NFC, Unicode spaces)
        – only if there is exactly ONE match
     b. re-authorize the alternative path (with “sibling” relation)
     c. still not there → error "File not found" + up to 3 name suggestions
4. AGENTS.md discovery: walk upwards to the location root
     → found files are injected as durable instructions
     → errors there are swallowed (the read still succeeds)
5. binary file (base64, not image/PDF) → error
6. build model content:
     Text:     "Read file <path>, lines <a>-<b>" + "<line>: <content>" per line
     Directory: "Read directory <path>, entries <a>-<b>" + paths
     Image/PDF: text hint + file content (base64)
```

**Limits:**

| Limit | Value |
|---|---|
| Lines per page | default and maximum **2000** |
| Bytes per page | **50 KiB** |
| Line length | > 2000 chars → truncated |
| Image/PDF | up to **20 MiB** |
| other binary files | fail |

> **The line prefix `<n>: <content>` is part of the content, not the file.**
> It serves to orient the model.

> **The AGENTS.md discovery is the surprising part:** a successful
> `read` can change the context of the **next** step by injecting new
> instructions (`packages/core/src/tool/plugin/read.ts:87-107`).

## 3. `write`

```
Input:  { path, content }
Output: { operation: "write", target, resource, existed }
```

**Flow:**

```
1. resolve(path, "file")
2. authorize external directories
3. read current content (for the diff)
4. diff preview = (resource, old, new, "added" | "modified")
5. permission.assert({ action: "edit", resources: [resource], save: ["*"],
                       metadata: { files: [diff preview] } })
6. write (preserve BOM, emit at most one BOM)
7. apply formatter if configured (sync BOM afterwards)
```

> **The action is `edit`, not `write`.** `write`, `edit` and `patch` share
> a single approval point – a rule for `edit` applies to all three.

> `save: ["*"]` means: if the user answers “always”, a **project-wide**
> approval is stored for *every* path.

> **What does NOT happen:** The **content** of the change is not durably
> logged. The replay shows *that* something was written, not *what*
> (`packages/core/src/tool/plugin/write.ts:42-43`).

## 4. `edit`

```
Input:  { path, old, new }        # replacement of an exact text block
```

**Flow:** like `write`, additionally:

```
- find the old block exactly (multiple occurrences → error)
- insert the new block at that position
```

> `edit` is the **safer** variant: it fails when the anchor no
> longer matches. A model using `write` overwrites silently.

## 5. `patch`

Like `edit`, but in patch format. **Only available for supported models**;
other models receive `edit` + `write` instead of `patch`.

## 6. `glob` and `grep`

| | `glob` | `grep` |
|---|---|---|
| Action | `glob` | `grep` |
| Resource | the **pattern** | the **regex** (not the path!) |
| Default hits | 100 files | 100 matching lines |
| Timeout | 30 s | 30 s |
| Path parameter | search root | search root |

> **The `grep` resource is the regex, not the search path.** This is a
> common misconception: a rule `grep: "*.ts"` allows grep with any pattern
> matching `*.ts` – the path is governed by the action “grep” and the
> context, not by the resource.

Both check the directory boundary for their search root **before**
searching.

## 7. Mutations

```
type Mutation =
  { target: absolutePath, content: Bytes, kind: "write" | "replace" }
```

**Rules:**

1. Mutations are **serialized per path, process-local** (key lock over
   the resolved path). Cooperating OpenCode writes wait for each other.
2. **External writers can still race.** The lock protects only
   cooperating participants.
3. **No multi-file transactionalism.** If the second of five changes
   fails, the first four remain. This is deliberate and is reported,
   not hidden.
4. **No byte content in the log.** Only the tool call is durable.

## 8. Snapshots (Undo)

```
Snapshot = a separate version-control repository
         under <data directory>/snapshot/<projectID>/<hash>
```

| Rule | Meaning |
|---|---|
| A snapshot is created **before** a mutation | basis for Undo |
| Snapshots live **outside** the database | a database backup contains **no** Undo history |
| The snapshot range is logged at step end | start/end of the step, so “which file did this step change" is answerable |
| `revert` resets files to the snapshot and deletes messages from the boundary | one coherent operation |

> The snapshot range is carried along in the step as `files` (list of
> changed files), so the UI can show “what did this turn change" without
> opening the snapshot.

## 9. Bounding (output truncation)

After execution, **in the runner**, not in the tool:

```
truncate(result) =
    if the result is already marked "truncated": return unchanged
    text  = all text parts concatenated
    if lines(text) ≤ 2000 AND bytes(text) ≤ 50 KiB: return

    file = <data directory>/tool-output/tool_<id>
    write the full text to file
    replace the visible text with:
        [showing lines 1-<n> of <total>; full output saved to <file>]
    mark: metadata = { truncated: yes, outputPath: file }
```

| Property | Value |
|---|---|
| Max lines | **2000** |
| Max bytes | **50 KiB** |
| Retention | **7 days**, cleanup hourly |

> **Two levels:** The tool may have **its own** limits (`read` paginates,
> `shell` gives a truncated preview plus path). The generic bounding
> kicks in only when the producer marked nothing. And: **bounding never
> changes the machine output value** – only the visible content.

## 10. The filesystem tools at a glance

| Tool | Action | Resource | `save` | Result |
|---|---|---|---|---|
| `read` | `read` | location-relative path | `["*"]` | Text/Image/PDF/Directory |
| `write` | `edit` | target path | `["*"]` | `{operation, target, resource, existed}` |
| `edit` | `edit` | target path | `["*"]` | like `write` |
| `patch` | `edit` | target path | `["*"]` | like `write` |
| `glob` | `glob` | pattern | `["*"]` | file list |
| `grep` | `grep` | regex | `["*"]` | matching lines |

> All six use `save: ["*"]` – a durable approval then applies to **every**
> path of this action.

## 11. Verified semantics (reference checkout)

| Concept | Location |
|---|---|
| Path resolution, internal/external boundary | `packages/core/src/file-access.ts:93-161` |
| `read` incl. AGENTS.md discovery, alternative path, suggestions | `packages/core/src/tool/plugin/read.ts:47-171` |
| `write` incl. diff, action `edit`, BOM, formatter | `packages/core/src/tool/plugin/write.ts:64-96` |
| `glob`/`grep` action and resource | `packages/core/src/tool/plugin/glob.ts:68-80`, `grep.ts:87-99` |
| Mutation lock per path, no multi-file rollback | `packages/core/src/file-mutation.ts:59-121`, `:129-134` |
| Bounding: 2000 lines, 50 KiB, 7 days, `outputPath` | `packages/core/src/tool-output.ts:13-15`, `:69-120` |
| Snapshots in their own repository | `packages/core/src/snapshot.ts:92-103` |
| Action `edit` for `write`/`edit`/`patch` | `packages/core/src/tool/AGENTS.md` |