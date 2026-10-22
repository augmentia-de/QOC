# 04 – Tool family: MCP

> MCP servers bring **foreign** tools. They run through the same tool
> contract, but get their own namespace and their own permission action.

## 1. Basic concept

> **MCP** (Model Context Protocol) is a standard through which a process
> (local) or a service (remote) offers the model **tools**, **resources**
> and **prompts**. OpenCode is the **client** here, not the server.

```
OpenCode  ──►  MCP server  ──►  tools of the server
(Client)      (counterpart)      (e.g. filesystem, database, ticket system)
```

> **Trust boundary:** A configured MCP server lies **outside** the
> system's trust boundary (`SECURITY.md:32`). Its tools therefore get
> their **own permission action** and no special treatment.

## 2. Server configuration

```
type ServerConfig =
  | Local {
      type: "local"
      command: List<Text>          # command + arguments
      cwd?: Path
      environment?: Map
      disabled?: Boolean
      codemode?: Boolean            # default true
      timeout?: TimeoutConfig
      protocol?: Protocol
    }
  | Remote {
      type: "remote"
      url: Text
      headers?: Map
      oauth?: OAuthConfig | false
      disabled?: Boolean
      codemode?: Boolean
      timeout?: TimeoutConfig
      protocol?: Protocol
    }

type TimeoutConfig =
  { startup?: Milliseconds      # handshake
  , catalog?: Milliseconds      # tools/list, prompts/list
  , execution?: Milliseconds }  # tool execution

type Protocol = "legacy" | "auto" | "2026-07-28"
```

### Protocol negotiation

| Value | Meaning |
|---|---|
| `legacy` | **Default.** Opens with the initialization handshake, speaks revisions up to `2025-11-25`. |
| `auto` | Checks revision `2026-07-28` and falls back to `legacy` if the server does not know it. |
| `2026-07-28` | Requires exactly this revision and **fails** otherwise. |

> **“auto" is the only setting that allows an “either/or".** The other
> two are commitments made consciously – and then kept.

### Time limits

| Phase | Default | Effect on overrun |
|---|---|---|
| `startup` | – | server is considered **failed** |
| `catalog` | – | this server's catalog is **incomplete** (known tools remain) |
| `execution` | – | the tool call **fails** |

> **Important:** A failed server must not affect **other** servers. Each
> server is independent.

## 3. Server status

```
type ServerStatus =
  | Connected
  | Pending        # connection being established
  | Disabled       # disabled in configuration
  | Failed { error: Text }
  | NeedsAuth { error: Text }   # OAuth required
```

> **`NeedsAuth` is its own status**, not an error: the server is not
> broken, it is waiting for a login. The UI must be able to turn this
> into a login flow – an error status is not enough.

## 4. Tool registration

### Namespace

```
namespace(server)  = normalize(server)          # invalid characters → "_"
toolname(server, tool) = namespace(server) + "_" + normalize(tool)
```

Example: server `github`, tool `create_issue` → **`github_create_issue`**.

> **The namespace is the collision avoidance.** Two servers may offer
> tools with the same name. The composite name is at the same time the
> **permission action** – a rule for `github_create_issue` applies only
> to this server.

### Registration

```
type McpTool =
  { server: Text
  , name: Text                # name at the server
  , description: Text
  , inputSchema: JsonSchema
  , outputSchema?: JsonSchema
  , codemode: Boolean }       # default true
```

**Rules:**

1. **One** stable registration that reads the most recently discovered
   tool set. Tool-list changes update the source and reload the registry
   state – they do **not** re-register at the end of the transform
   order.
2. **After the first discovery**, registration happens. Thereafter, on
   change, **debounced reload** (100 ms), because servers announce their
   tools in bursts.
3. **The first catalog blocks the first step of a session:** the engine
   waits until initial registration completes (`flush`).
4. A server with no tools registers **nothing** – that is not an error.

> **Rule 1 is the decisive point.** Anyone who re-registers on every
> tool-list change shifts the precedence relative to later plugin
> overrides. This is expressly noted in the reference checkout
> (`packages/core/src/tool/AGENTS.md`, section “Registration").

## 5. Execution

```
1. Permission check:
     action    = toolname(server, tool)
     resources = ["*"]
     save      = ["*"]
     metadata  = { input: <the input> }

2. Call at the server: callTool(server, name, args)

3. Evaluate result:
     isError = true  → tool error with the text content of the result
     otherwise       → convert contents (see §6)

4. Determine machine value:
     structured present              → that
     text begins with { or [ and the server declares no schema
                                       → parse JSON
     otherwise                       → the text
```

> **The JSON parsing is a deliberate convenience.** Agents assume that
> a JSON returned as text is already an object. That is why it is
> parsed **if** the server declares no output schema. With a declared
> schema, the server bears the responsibility.

### Errors

| Error | Meaning | Visible |
|---|---|---|
| Server not available | connection not up (`startup` exceeded) | “MCP server \"x\" is not available" |
| Tool call failed | the server reports an error | its message |
| Result is `isError` | the server reports an error result | its text content |

> **All three are tool errors** – they go to the model as text so the
> agent can resolve them (other server, other arguments, rephrase the
> task). They are **not** rejections.

## 6. Converting contents

MCP delivers typed contents. The engine knows only two forms: text and
file.

```
type Content:
  | Text { text }
  | Media { mimeType, data (base64), uri? }
  | Resource { uri, mimeType, text | blob }
```

**Rules:**

| Type | Result |
|---|---|
| Text | text content |
| Media with a **supported** image/PDF MIME | file content (base64, inline) |
| Media with a **textual** MIME | text content: “[Resource <uri> (<mime>)]" + content |
| Media with a **binary** MIME | **write to managed storage**; only the reference in text: “[Binary resource <uri> (<mime>, N bytes) saved to <file>]" |

> **Only the media supported by filesystem reads are passed inline.**
> Other media types would fail on every later request – that is why they
> move into managed storage and are only referenced.

> **Binary contents are the reason for tool-output management.** Without
> them the engine would have to store binary blobs in the message.

## 7. MCP instructions in the prompt

MCP servers can deliver **instructions** for the model (MCP prompts or
server-side hints). They are inserted as a block into the system
context:

```
<mcp_instructions>
  … per server …
</mcp_instructions>
```

**Rules:**

1. **Only servers whose tools are not entirely `deny`** — a blocked
   server brings no instructions.
2. **Delta rendering**: if the block changes, only the change is
   inserted, not the whole block.
3. The block belongs to the **instructions** – it is part of the
   instruction state and is treated like any other instruction source
   value (hash, delta, epoch).

> **Point 1 is the connection between permissions and prompt:** whoever
> blocks a server's tools also prevents its instructions from
> influencing the model. A blocked server is **completely** absent.

## 8. Resources and prompts (optional)

MCP servers may additionally offer:

```
type ResourceCatalog =
  { resources: List<Resource>            # statically addressable
  , templates: List<ResourceTemplate> }  # URI templates (patterns)

type Resource = { server, name, uri, description?, mimeType? }
type ResourceTemplate = { server, name, uriTemplate, description?, mimeType? }
```

These are **not** automatically turned into tools. A client may address
them through its own tools (e.g. `opencode_read_mcp_resource`).

> **The decision not to offer resources automatically as tools is
> important:** resources are **data**, tools are **actions**. Mixing the
> two gives the model read access to things the user may not have wanted
> to grant.

## 9. OAuth

For remote servers that need authorization:

```
type OAuthConfig =
  { client_id?: Text
  , client_secret?: Text
  , scope?: Text
  , callback_port?: Int        # 1..65535
  , redirect_uri?: Text
  , auth_server_metadata_url?: Text }
```

| # | Rule |
|---|---|
| 1 | The client secret is **not** emitted in the tool context or in logs. |
| 2 | `auth_server_metadata_url` is set when the server names no protected
   resource metadata document naming its authorization server. |
| 3 | The token belongs to the **server**, not the tool. |
| 4 | A missing token leads to `NeedsAuth` – **not** to `Failed`. |

## 10. Verified semantics (reference checkout)

| Concept | Location |
|---|---|
| Namespace, action, `resources: ["*"]`, `save: ["*"]` | `packages/core/src/tool/mcp.ts:20-21`, `:56-68` |
| Registration, debounce 100 ms, `reload` instead of re-registration | `packages/core/src/tool/mcp.ts:42-52`, `:121-140` |
| Error mapping (`NotFound`, `ToolCall`, `isError`) | `packages/core/src/tool/mcp.ts:76-90` |
| Content conversion, binary → managed storage | `packages/core/src/tool/mcp.ts:146-161` |
| Machine value: structured, otherwise JSON parsing | `packages/core/src/tool/mcp.ts:93-103` |
| Server configuration, protocol, timeouts, status | `packages/schema/src/mcp.ts:7-97` |
| MCP instructions, only for non-blocked servers | `packages/core/src/mcp/instructions.ts:17-29`, `:90-103` |
| Trust boundary | `SECURITY.md:32` |