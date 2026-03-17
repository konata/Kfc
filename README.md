# KFC

```javascript
const caesar = function* (s) {
  while (true) (yield s, (s = String.fromCharCode(...[...s].map((c) => c.charCodeAt() + 1))))
}

;; [...caesar('IDA').take(3)].join(' ~> ')
// ▶  IDA ~> JEB ~> KFC
```

Bridge JEB's static analysis to AI via MCP.

```
AI (Cursor / Claude Code) ←stdio→ MCP Server (Bun) ←HTTP→ JEB Headless + KFC Extension (Kotlin)
```

## Setup

1. Set `jebHome` in `gradle.properties`, then build and deploy:

```bash
./gradlew build
cp extension/build/libs/kfc-0.1.0.jar /path/to/jeb/coreplugins/
cp kfc.py /path/to/jeb/coreplugins/
cd server && bun install
```

2. Add a shell alias:

```bash
kfc() { /path/to/jeb/jeb_macos.sh -c --srv2 --script=/path/to/jeb/coreplugins/kfc.py; }
```

3. Configure MCP in your AI client:

**Cursor** — `.cursor/mcp.json`:

```json
{
  "mcpServers": {
    "kfc": {
      "command": "bun",
      "args": ["/path/to/kfc/server/src/index.ts"],
      "env": { "KFC_API_HOST": "http://localhost:9527" }
    }
  }
}
```

**Claude Code:**

```bash
claude mcp add kfc -- bun /path/to/kfc/server/src/index.ts
```

**Codex:**

```bash
codex mcp add kfc --env KFC_API_HOST=http://localhost:9527 -- bun /path/to/kfc/server/src/index.ts
codex mcp list
```

4. Start the bridge, then let AI load the target:

```bash
kfc
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
