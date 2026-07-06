# KFC

```javascript
const caesar = function* (s) {
  while (true) (yield s, (s = String.fromCharCode(...[...s].map((c) => c.charCodeAt() + 1))))
}

;; [...caesar('IDA').take(3)].join(' ~> ')
// ▶  IDA ~> JEB ~> KFC
```

## Install

```bash
bun add -g @beriru/kfc
```

Install the JEB side plugin:

```bash
kfc install --jeb-home /path/to/jeb
```

Start the JEB bridge when you want agents to use JEB:

```bash
kfc bridge
```

Configure MCP in your AI client:

**Codex**

```bash
codex mcp add kfc --env KFC_API_HOST=http://localhost:9527 -- kfc mcp
```

**Claude Code**

```bash
claude mcp add kfc --env KFC_API_HOST=http://localhost:9527 -- kfc mcp
```

**Cursor**

```json
{
  "mcpServers": {
    "kfc": {
      "command": "kfc",
      "args": ["mcp"],
      "env": { "KFC_API_HOST": "http://localhost:9527" }
    }
  }
}
```

You can print client-specific setup snippets:

```bash
kfc config codex
kfc config claude
kfc config cursor
```

Check local wiring:

```bash
kfc doctor
```

## Development

From a checkout:

```bash
cd server && bun install
bun run build:jeb
bun run stage:jeb
bun link
```

Publishing uses the normal NPM registry and token:

```bash
cd server
bun publish --access public
```

## Tools

KFC now exposes two layers of tools:

- task-oriented tools for AI agents
- low-level primitives for direct reverse engineering

This split is intentional. In practice, AI clients often miss raw primitives like `get_xrefs`, even when those primitives are powerful. The high-level tools package common analysis flows into a form that is easier for an agent to discover and call. The low-level tools remain available for precise inspection and custom workflows.

### Task-Oriented

| Tool                       | Description                                                       |
| -------------------------- | ----------------------------------------------------------------- |
| `find_exported_components` | Find exported manifest components with permissions and intent filters |
| `inspect_component`        | Resolve a component to its class, hierarchy, and likely entry methods |
| `find_callers`             | Turn raw xrefs into caller methods that are easier for AI to inspect |

### Primitives

| Tool                  | Description                                    |
| --------------------- | ---------------------------------------------- |
| `load_apk`            | Load or switch APK/DEX for analysis            |
| `get_project_info`    | Project overview and current target            |
| `get_manifest`        | AndroidManifest.xml                            |
| `get_permissions`     | Declared permissions                           |
| `get_components`      | Activities, services, receivers, providers     |
| `list_units`          | All analysis units                             |
| `list_classes`        | List/filter classes                            |
| `decompile_class`     | Decompile class to Java                        |
| `decompile_method`    | Decompile method to Java                       |
| `get_class_hierarchy` | Superclasses, interfaces, subclasses           |
| `get_class_methods`   | Declared methods for class + superclass chain  |
| `get_overrides`       | Method overrides (children/parents)            |
| `get_xrefs`           | Raw cross-references                           |
| `search_strings`      | Regex search on string constants               |
| `search_bytecode`     | Regex search on Dalvik bytecode (FQN types)    |
| `get_method_cfg`      | Method instructions and control flow           |
| `rename`              | Rename a class, method, or field               |
