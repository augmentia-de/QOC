# 08 – Tool Family: Remote Shell and File Transfer (SSH)

> Tool: `remote` (V1 transport: SSH). Executes policy-protected actions on a
> remote host and transfers files or directories between the remote host and the
> local workspace. The host is addressed exclusively via a configured alias,
> never via an IP address or domain supplied by the model.

## Contents

- [1. Purpose](#1-purpose)
- [2. Scope](#2-scope)
- [3. Tool Contract](#3-tool-contract)
- [4. Host Resolution](#4-host-resolution)
- [5. Permissions and Policy](#5-permissions-and-policy)
- [6. Remote Policy](#6-remote-policy)
- [7. Execution Algorithm](#7-execution-algorithm)
- [8. Error Handling](#8-error-handling)
- [9. Security](#9-security)
- [10. Output, Bounding, and Audit](#10-output-bounding-and-audit)
- [11. Limits and Concurrency](#11-limits-and-concurrency)
- [12. Invariants](#12-invariants)
- [13. Adapter and Runtime](#13-adapter-and-runtime)
- [14. Best Practices](#14-best-practices)
- [15. References](#15-references)

## 1. Purpose

The tool allows an agent to work in a controlled way on a remote server,
typically a GCP VM:

- execute commands,
- read files,
- write files,
- list directories,
- read file/directory metadata,
- download files from the remote host into the local workspace,
- upload files from the local workspace to the remote host,
- copy directories recursively in both directions.

It is **not an interactive SSH terminal**, not a general port-forwarding
proxy, and not a synchronization tool. The focus is on reproducible,
auditable single actions with clear boundaries.

## 2. Scope

### 2.1 In Scope (V1)

| Action | Meaning |
|---|---|
| `exec` | execute a command on the remote host |
| `read` | read a remote file |
| `write` | write a remote file |
| `list` | list a remote directory |
| `stat` | read metadata of a remote file/directory |
| `download` | copy a remote file or directory into the local workspace |
| `upload` | copy a local file or directory to the remote host |

### 2.2 Not in Scope (V1)

- interactive shell / PTY
- port forwarding
- X11 forwarding
- SSH agent forwarding
- password authentication
- arbitrary hosts/IPs from the model
- `sudo` execution
- package installation
- bidirectional synchronization / mirroring (e.g. rsync semantics)
- metadata and permission synchronization
- resumption of interrupted transfers
- persistent remote sessions

These points can later be specified as separate tools or as explicit
extensions.

## 3. Tool Contract

### 3.1 Name

```text
remote
```

The name is deliberately generic. V1 supports only the transport `ssh`.
Further transports (e.g. a remote agent over HTTP) can later be added under
the same tool name without changing the model interface.

### 3.2 Input

```json
{
  "host": "gcp-prod",
  "action": "exec",
  "command": "systemctl status qoc --no-pager",
  "cwd": "/opt/qoc",
  "timeoutMs": 10000
}
```

Example of a recursive download:

```json
{
  "host": "gcp-prod",
  "action": "download",
  "remotePath": "/var/log/qoc",
  "localPath": "logs/qoc",
  "recursive": true,
  "conflict": "overwrite",
  "symlinks": "skip",
  "timeoutMs": 120000
}
```

Example of a recursive upload:

```json
{
  "host": "gcp-prod",
  "action": "upload",
  "localPath": "releases/qoc-1.2.3",
  "remotePath": "/opt/qoc/releases/qoc-1.2.3",
  "recursive": true,
  "conflict": "overwrite",
  "symlinks": "skip",
  "timeoutMs": 120000
}
```

| Field | Type | Required | Meaning |
|---|---|---|---|
| `host` | String | yes | Host alias from configuration |
| `action` | Enum | yes | `exec`, `read`, `write`, `list`, `stat`, `download`, `upload` |
| `command` | String | only `exec` | Remote command |
| `path` | String | only `read`/`write`/`list`/`stat` | Remote path |
| `remotePath` | String | only `download`/`upload` | Remote source or remote destination |
| `localPath` | String | only `download`/`upload` | local path relative to workspace root |
| `content` | String | only `write` | File content |
| `encoding` | String | no | `utf-8` (default) or `base64` |
| `cwd` | String | no | Working directory for `exec` |
| `recursive` | Boolean | no | Copy directory recursively; default `false` |
| `conflict` | Enum | no | `overwrite`; V1 default and only allowed value |
| `symlinks` | Enum | no | `skip`; V1 default and only allowed value |
| `timeoutMs` | Integer | no | Timeout; capped at the host maximum |
| `maxBytes` | Integer | no | Maximum return size; capped at the host maximum |

Rules:

- Exactly one action per call.
- Unknown fields are rejected.
- `host` is an alias, not an IP/domain.
- `command`, `path`, `remotePath`, and `localPath` must not be empty.
- `content` may only be set for `write`.
- `remotePath` and `localPath` may only be set for `download`/`upload`.
- `recursive`, `conflict`, and `symlinks` may only be set for `download`/`upload`.
- `localPath` is always relative to the workspace root. Absolute paths, `..`,
  `.` as source or destination, NUL characters, and path traversal are
  rejected.
- `remotePath` may not be `/` for copy actions.
- `recursive=false` requires a regular file as source.
- `recursive=true` requires a directory as source.
- The source must exist; the destination may be missing and is created.
- `conflict=overwrite` replaces existing regular files; existing directories are
  merged. **No** destination-side files that do not occur in the source are
  deleted.

### 3.3 Output

Common fields:

| Field | Type | Meaning |
|---|---|---|
| `host` | String | resolved host alias |
| `action` | String | action executed |
| `durationMs` | Integer | duration in milliseconds |
| `truncated` | Boolean | whether the model output was truncated |

`exec`:

```json
{
  "host": "gcp-prod",
  "action": "exec",
  "exitCode": 0,
  "stdout": "...",
  "stderr": "",
  "durationMs": 123,
  "truncated": false
}
```

`read`:

```json
{
  "host": "gcp-prod",
  "action": "read",
  "path": "/var/log/qoc.log",
  "content": "...",
  "encoding": "utf-8",
  "bytes": 1234,
  "truncated": true
}
```

`write`:

```json
{
  "host": "gcp-prod",
  "action": "write",
  "path": "/opt/qoc/config.yml",
  "bytesWritten": 456
}
```

`list`:

```json
{
  "host": "gcp-prod",
  "action": "list",
  "path": "/var/log",
  "entries": [
    { "name": "qoc.log", "type": "file", "size": 1234, "mtime": "..." }
  ],
  "truncated": false
}
```

`stat`:

```json
{
  "host": "gcp-prod",
  "action": "stat",
  "path": "/opt/qoc/qoc-runner",
  "type": "file",
  "size": 12345678,
  "mtime": "...",
  "mode": "0755"
}
```

`download`:

```json
{
  "host": "gcp-prod",
  "action": "download",
  "remotePath": "/var/log/qoc",
  "localPath": "logs/qoc",
  "filesCopied": 12,
  "directoriesCreated": 3,
  "bytesCopied": 456789,
  "overwritten": 1,
  "skipped": 2,
  "failed": [],
  "partial": false,
  "durationMs": 1234,
  "truncated": false
}
```

`upload` has the same result shape; `remotePath` is the destination and
`localPath` is the local source.

`failed` contains, in case of partial errors, at least `path` and `reason` per
entry. `skipped` counts skipped symlinks and unsupported special files.
`partial=true` indicates that at least one entry was not transferred.
`truncated` for copy actions refers to the truncated `failed`/`skipped` list,
not to the transferred data.

### 3.4 Semantics of Exit Codes

A remote command with an exit code other than `0` is **not a tool error**. It
is a normal result:

- `exitCode` is returned,
- `stdout`/`stderr` are returned,
- the model decides how to react.

Only connection, authentication, policy, timeout, limit, and protocol errors
are tool errors.

## 4. Host Resolution

### 4.1 Aliases

Configuration defines aliases:

```text
gcp-prod  -> hostname: 34.x.x.x, port: 22, user: opencode
gcp-dev   -> hostname: 10.x.x.x, port: 22, user: opencode
```

The alias is the only host identifier permitted to the model. An unknown
alias leads to an error.

### 4.2 Host Configuration

Minimum fields:

| Field | Required | Meaning |
|---|---|---|
| `alias` | yes | unique name, case-insensitive |
| `hostname` | yes | DNS name or IP |
| `port` | no | default `22` |
| `username` | yes | remote user |
| `credential` | yes | reference to an SSH key in the credential store |
| `knownHosts` | yes | known-hosts file or host-key fingerprint |
| `strictHostKeyChecking` | yes | default `true` |
| `allowedActions` | yes | subset of `exec`, `read`, `write`, `list`, `stat`, `download`, `upload` |
| `allowedCommands` | only `exec` | wildcard allowlist |
| `deniedCommands` | no | wildcard denylist; wins against allow |
| `allowedPaths` | only file actions | wildcard allowlist for remote paths |
| `cwd` | no | permitted working directory |
| `timeoutMs` | no | default and maximum |
| `maxOutputBytes` | no | maximum model output |
| `maxFileBytes` | no | maximum file size for `read`/`write` |
| `maxEntries` | no | maximum entries for `list` |
| `maxTransferFiles` | no | maximum file count per copy action |
| `maxTransferBytes` | no | maximum total size per copy action |
| `maxTransferFileBytes` | no | maximum single file size per copy action |
| `maxTransferDepth` | no | maximum directory depth per copy action |
| `maxConcurrent` | no | maximum parallel connections per host |

Secrets (private keys, passphrases) are **not** stored in configuration, but
in the credential store or in a file with restrictive permissions.

The local workspace root is configured globally by the runtime and is not part
of the host configuration. The model can neither set nor override it.
`localPath` is always resolved relative to this root.

## 5. Permissions and Policy

### 5.1 Permission Actions

| Action | Permission Action |
|---|---|
| `exec` | `remote.exec` |
| `read` | `remote.read` |
| `write` | `remote.write` |
| `list` | `remote.list` |
| `stat` | `remote.stat` |
| `download` | `remote.download` |
| `upload` | `remote.upload` |

### 5.2 Resource

The permission resource is:

```text
<host-alias>:<target>
```

Where `<target>` is:

- for `exec` the remote command,
- for `read`/`write`/`list`/`stat` the normalized remote path,
- for `download` the normalized remote source path,
- for `upload` the normalized remote destination path.

The local path is **not** part of the permission resource. It is checked
separately against the workspace root and tracked as metadata in the audit
log.

Examples:

```text
gcp-prod:systemctl status qoc --no-pager
gcp-prod:/var/log/qoc.log
gcp-dev:/opt/qoc/config.yml
gcp-prod:/var/log/qoc
gcp-prod:/opt/qoc/releases
```

### 5.3 Decision Order

1. **Policy deny** takes precedence, even over saved approvals.
2. **Deny** in the rules blocks the action.
3. **Saved approvals** are attached.
4. **Ask** dominates **Allow**.
5. Without a match, **ASK** applies.
6. Without rules, **DENY** applies.

Remote actions may **never** be allowed implicitly through a general `*`
allow rule. The wiring layer must ensure that for `remote.*` at least one
explicit rule or explicit host scoping exists.

### 5.4 Recommended Defaults

| Action | Default |
|---|---|
| `remote.read` | `ASK` |
| `remote.list` | `ASK` |
| `remote.stat` | `ASK` |
| `remote.exec` | `ASK` |
| `remote.write` | `ASK` |
| `remote.download` | `ASK` |
| `remote.upload` | `ASK` |

Production hosts should always use `ASK` or `DENY` for `exec`, `write`,
`download`, and `upload`. `ALLOW` is only sensible for explicitly approved,
harmless read operations.

For copy actions with `conflict=overwrite`, an explicit approval is required,
even if the general rule says `ALLOW`. Overwriting existing files must not be
silently permitted by a broad `remote.*` rule.

## 6. Remote Policy

The permission engine decides **whether** an action is allowed. The remote
policy decides **which** commands/paths are permissible on the host. Both
checks are mandatory.

### 6.1 Command Allowlist

- `allowedCommands` is a list of wildcard patterns.
- An empty list means: **no `exec` allowed**.
- `deniedCommands` always wins against `allowedCommands`.
- The command is checked before the connection is established.
- The command is passed as a single string to the remote shell.
- No PTY, no interactive input, no `stdin`.

Examples:

```text
allowedCommands:
  - "systemctl status *"
  - "journalctl *"
  - "ls *"
  - "cat /var/log/*"
deniedCommands:
  - "rm *"
  - "sudo *"
  - "reboot"
  - "shutdown"
  - "mkfs*"
  - "dd *"
```

### 6.2 Remote Path Allowlist

- `allowedPaths` is a list of wildcard patterns.
- The rule applies to `read`, `write`, `list`, `stat`, `download`, and
  `upload`.
- Remote paths are normalized before the action.
- `..` traversal, NUL characters, and empty paths are rejected.
- For `read`/`write`/`list`/`stat`, symlinks are resolved before the policy
  check; the target must lie within the allowed paths.
- For `download`/`upload`, symlinks are not followed on either side
  (see 6.3 and 7.1).
- A symlink as the remote copy root is rejected.
- For copy actions, both the root path and every traversed child path must
  lie within the allowed paths.
- Relative remote paths are resolved against the configured `cwd`.
- The remote path `/` is not allowed for copy actions.
- An empty list means: **no file action allowed**.

### 6.3 Local Paths and Symlinks (Copy)

- `localPath` is always relative to the workspace root.
- Absolute paths, `..`, `.` as source or destination, NUL characters, and
  empty paths are rejected.
- The normalized destination path must lie within the workspace root.
- The local source path is also realpath-checked and must lie within the
  workspace root; symlink components in the path lead to abort.
- Existing symlinks in any component of the local destination path are **not**
  followed and lead to abort.
- A symlink as the local copy root is rejected.
- Symlinks inside a source are skipped and counted in `skipped`.
- Special files (sockets, FIFOs, devices) are skipped.
- The source must be a regular file or a directory; a special file as source
  is rejected.
- The source must exist. The destination may be missing; missing parent
  directories are created within the workspace root.
- A directory is only copied with `recursive=true`; a file only with
  `recursive=false`.
- Newly created local files are created with `0600`, new local directories
  with `0700`. Permissions and ownership are not taken over from the remote
  host in V1.
- On upload, no remote permissions are set; the SFTP server's defaults apply.
- The workspace root itself may not be used as `localPath`.
- No destination-side files that do not occur in the source are deleted.

### 6.4 Working Directory

- `cwd` must lie within the allowed paths.
- The working directory is set before the command.
- A `cwd` supplied by the model is also checked against the policy.

## 7. Execution Algorithm

```text
remote(host, action, target, options):
    1. Validate input (exactly one action, required fields, limits).
    2. Resolve host alias. Unknown → error.
    3. Check whether the action is allowed for the host.
    4. Permission.assert(action, "<alias>:<target>").
    5. Check remote policy (command or remote path).
    6. For copy actions, check the local path against the workspace root.
    7. Open SSH connection:
       - key authentication,
       - host-key verification,
       - timeout.
    8. Execute action:
       - exec: command in cwd,
       - read: read file,
       - write: write file atomically,
       - list: read directory,
       - stat: read metadata,
       - download: copy remote file/directory into workspace,
       - upload: copy local file/directory to remote host.
    9. Bound output (bytes, entries, file size, transfer limits).
   10. Close connection.
   11. Return structured result; partial failures with partial=true.
```

Order is binding: **permission and policy before connection establishment**.
This prevents unauthorized actions from being executed even partially.

### 7.1 Copy Flow

Copy actions use an SFTP channel. They neither load the entire source nor the
entire destination into memory; each file is streamed.

```text
copy(host, direction, remotePath, localPath, recursive, limits):
    1. Validate source and destination (type, existence, path boundaries).
    2. Open an SSH connection and an SFTP channel.
    3. Determine the source:
       - file: a single entry,
       - directory: full recursive walk if recursive=true.
    4. Check each entry:
       - remote path in allowedPaths,
       - local path in workspace,
       - no symlinks, no special files,
       - file count, total size, single size, and depth.
       On limit exceeded: abort before the first write.
    5. Create missing directories.
    6. Copy each regular file:
       - Download: SFTP read → local temp file → rename,
       - Upload: local file → SFTP temp file → rename.
    7. Count copied, overwritten, skipped, and failed.
    8. Close SFTP channel and SSH connection.
    9. Return CopyResult.
```

The walk is deterministic (sorted lexicographically by relative path), so
audit and error lists are reproducible.

### 7.2 Directory Merge

For `recursive=true` and `conflict=overwrite`:

- The destination directory is created if missing.
- The source contents are copied directly into the destination directory; the
  source's base name is not appended additionally.
- For a file, `localPath` or `remotePath` is the exact destination path.
- Existing directories are entered and merged.
- Existing regular files are replaced.
- Destination-side files not present in the source remain unchanged.
- A type conflict (file vs. directory) leads to an error for the affected
  entry; the rest may continue and is reported as `partial=true`.
- A symlink in the destination path leads to abort, not to following.
- There is **no** deletion and **no** synchronization.

## 8. Error Handling

| Error | Effect |
|---|---|
| Unknown host alias | `Tool.Error` (visible to the model) |
| Action not allowed for host | `PermissionDenied` |
| Permission denied | `PermissionDenied` |
| Command not in allowlist | `PermissionDenied` |
| Command in denylist | `PermissionDenied` |
| Path outside allowlist | `PermissionDenied` |
| Local path outside workspace | `PermissionDenied` |
| Local path absolute or with traversal | `PermissionDenied` |
| Symlink in local destination path | `Tool.Error`, no following |
| Copy source missing | `Tool.Error` |
| Copy source has wrong type | `Tool.Error` |
| Type conflict file/directory | entry in `failed`, `partial=true` |
| Copy limit exceeded | `Tool.Error` with `partial=true` and counters |
| Partial error during copy | result with `partial=true`; caller treats it as an error |
| Host-key mismatch | `Tool.Error`, not retryable |
| Authentication error | `Tool.Error`, not retryable |
| Connection error | `Tool.Error`, possibly retryable by orchestrator |
| Timeout | `Tool.Error` with timeout hint |
| Exit code not equal to 0 | normal result, not a tool error |
| Output too large | result is truncated, `truncated=true` |
| File too large | `Tool.Error` or truncated result per policy |

The tool adapter performs **no automatic retries**. Retries are the
responsibility of the orchestrator or error classification, so that
side effects are not executed twice.

## 9. Security

### 9.1 Authentication

- Only SSH key authentication.
- No passwords.
- Private keys lie in the credential store or in files with `0600`.
- Key contents never appear in the prompt, in tool outputs, or in logs.
- Key passphrases come from a secure source.

### 9.2 Host Verification

- `strictHostKeyChecking=true` is the default.
- Host-key mismatch always leads to abort.
- Known hosts are checked via `known_hosts` or configured fingerprints.
- Automatically accepting new host keys is disabled by default.

### 9.3 Permissions

- No `sudo` by default.
- No SSH agent forwarding.
- No port forwarding.
- No X11 forwarding.
- No PTY in V1.
- No arbitrary hosts from the model.
- Production hosts aliased separately and treated more restrictively.
- `download` writes only into the local workspace.
- `upload` reads only from the local workspace and writes only into
  `allowedPaths` of the remote host.
- Copy actions do not follow symlinks and skip special files.
- `overwrite` replaces only files; it does not delete destination-side files.

### 9.4 Data

- No secrets in command arguments when avoidable.
- No secrets in logs.
- Sensitive outputs only bounded to the model.
- Full-text outputs stored in the `tool_output` store with retention.
- Redaction rules for known secret patterns.
- Copy files are not embedded in the prompt, but summarized only as path,
  size, and result.
- Local copies inherit neither remote permissions nor remote ownership.

## 10. Output, Bounding, and Audit

### 10.1 Bounding

- `stdout` and `stderr` are bounded separately.
- The model output is limited to `maxOutputBytes`.
- Full text is stored in the tool output store.
- `truncated=true` indicates that truncation occurred.
- `list` is limited to `maxEntries`.
- `read`/`write` are limited to `maxFileBytes`.
- `download`/`upload` are limited to `maxTransferFiles`, `maxTransferBytes`,
  `maxTransferFileBytes`, and `maxTransferDepth`.
- For copy actions, not file contents but counters and error lists are
  returned to the model.
- `truncated` for copy actions refers to the truncated `failed`/`skipped`
  list.

### 10.2 Audit

Every remote action produces an audit record with:

- session ID and tool-call ID,
- host alias,
- action,
- remote target (command/path) as hash or truncated,
- local path for copy actions,
- exit code,
- duration,
- output size,
- `truncated`,
- error class (if present).

Copy actions additionally include:

- `filesCopied`, `directoriesCreated`, `bytesCopied`,
- `overwritten`, `skipped`,
- `partial`,
- number and categories of errors (without sensitive file contents).

The audit record contains **no** private keys, passphrases, or complete
sensitive file contents.

## 11. Limits and Concurrency

| Limit | Recommendation |
|---|---|
| Maximum command length | 4096 characters |
| Connection timeout | 10 s |
| Default timeout | 10 s |
| Maximum timeout | 60 s |
| Default timeout `download`/`upload` | 60 s |
| Maximum timeout `download`/`upload` | 300 s |
| Maximum model output | 64 KiB |
| Maximum file size `read` | 1 MiB |
| Maximum file size `write` | 1 MiB |
| Maximum `list` entries | 1000 |
| Maximum file count per copy | 10,000 |
| Maximum total size per copy | 256 MiB |
| Maximum single file size per copy | 64 MiB |
| Maximum directory depth per copy | 32 |
| Parallel connections per host | 2 |
| Parallel recursive copy actions per host | 1 |
| Parallel remote actions total | 4 |

The values are configurable but capped upward. A model cannot override the
limits.

## 12. Invariants

| # | Invariant | Verification |
|---|---|---|
| R-01 | The host is addressed exclusively via a configured alias. | Unit test |
| R-02 | Unknown aliases are rejected. | Unit test |
| R-03 | Host-key verification is strict by default. | Integration test |
| R-04 | Authentication occurs only via key. | Integration test |
| R-05 | Permission and remote policy are checked before connection establishment. | Unit test |
| R-06 | An empty `allowedCommands` list allows no `exec`. | Unit test |
| R-07 | `deniedCommands` wins against `allowedCommands`. | Unit test |
| R-08 | Path traversal and symlink escape are rejected. | Unit test |
| R-09 | An exit code not equal to 0 does not cause a crash. | Integration test |
| R-10 | Outputs are bounded; `truncated` is set. | Unit test |
| R-11 | Timeout terminates the remote action. | Integration test |
| R-12 | Private keys do not appear in prompt, logs, or tool outputs. | Security test |
| R-13 | Without configured hosts, the tool is not registered. | Unit test |
| R-14 | V1 offers no PTY, no port forwarding, and no agent forwarding. | Spec review |
| R-15 | Write actions require explicit approval per policy. | Integration test |
| R-16 | `localPath` is relative to the workspace; absolute paths and traversal are rejected. | Unit test |
| R-17 | `download` writes only into the workspace; `upload` reads only from the workspace. | Integration test |
| R-18 | Copy actions skip symlinks and special files. | Integration test |
| R-19 | Recursive copy actions respect file, byte, single-file, and depth limits. | Integration test |
| R-20 | `overwrite` replaces files, merges directories, and does not delete destination-side files. | Integration test |
| R-21 | Type conflicts lead to a partial result, not a crash. | Integration test |
| R-22 | Copy audit contains counters, `partial`, and error categories. | Unit test |
| R-23 | The remote path `/` is not allowed for copy actions. | Unit test |

## 13. Adapter and Runtime

### 13.1 Port

The runtime must not depend directly on an SSH library. What is needed is a
port, e.g.:

```text
RemoteShell
  exec(host, command, cwd, timeout) -> ExecResult
  read(host, path, maxBytes) -> ReadResult
  write(host, path, bytes) -> WriteResult
  list(host, path, maxEntries) -> ListResult
  stat(host, path) -> StatResult
  download(host, remotePath, localPath, options) -> CopyResult
  upload(host, localPath, remotePath, options) -> CopyResult
```

`CopyOptions` contains `recursive`, `conflict`, `symlinks`, and the transfer
limits enforced by the host. The port knows no host aliases as free text, but
receives the already resolved host configuration.

The port lives in `core.port.world` or a comparable infrastructure port. The
runtime uses only this port.

### 13.2 Adapter

- The adapter implements the port with a Java SSH library.
- It requires no external `ssh` or `scp` binary.
- It supports key auth, host-key verification, exec, and SFTP.
- Copy actions stream files over SFTP and do not load them fully into
  memory.
- Recursive copy actions run with one SSH connection and one SFTP channel.
- Files are written via temp file + rename, where the target system supports
  it.
- It is identical for JVM and native image.
- Reflection/proxy configuration for the native image is maintained.

### 13.3 Registration

- The tool is registered only when at least one host is configured.
- The tool snapshot is filtered per agent.
- Agents without `remote.*` permissions do not see the tool.
- The tool description contains no host IPs, keys, or secrets.

## 14. Best Practices

1. **Least privilege:** allow only the necessary actions per host.
2. **Read-only first:** approve `read`/`list`/`stat` before `exec`/`write`.
3. **Production separate:** own aliases and stricter policies.
4. **Narrow allowlists:** no `*` commands for production hosts.
5. **Approval:** always confirm `exec`, `write`, `download`, and `upload` on
   production hosts.
6. **No secrets in commands:** prefer files/environment variables.
7. **Timeouts:** always set them, even when the agent does not specify one.
8. **Bounding:** never put large outputs fully into the context.
9. **Audit:** log every action traceably.
10. **No interactive sessions:** single actions instead of a terminal.
11. **Workspace stays workspace:** never allow `localPath` outside the
    workspace.
12. **Skip symlinks:** copy follows no symlinks and skips special files.
13. **Overwrite deliberately:** `overwrite` only with explicit approval; do
    not expect deletion of destination-side files.
14. **Limits before copy:** check file count, total size, single size, and
    depth before starting, not only during the transfer.

## 15. References

- [`01-types.md`](./01-types.md) – Tool contract
- [`02-filesystem.md`](./02-filesystem.md) – Path boundaries, bounding, file semantics
- [`03-bash-hitl.md`](./03-bash-hitl.md) – Command scanner, HITL, timeout
- [`05-contract-invariants.md`](./05-contract-invariants.md) – Execution chain and error channels
- [`07-subagent.md`](./07-subagent.md) – Delegation and child sessions
- `../permissions/` – rule system, Ask/Deny/Allow, saved approvals
- `../betrieb/` – HTTP/SSE, operations, and configuration
- `../persistence/` – tool output store and retention
- `../qualitaet/` – architecture and security tests