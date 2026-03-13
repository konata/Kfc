# KFC

> IDA → JEB → KFC

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

4. Start the bridge, then let AI load the target:

```bash
kfc
```

## Tools

| Tool | Description |
|---|---|
| `load_apk` | Load or switch APK/DEX for analysis |
| `get_project_info` | Project overview and current target |
| `get_manifest` | AndroidManifest.xml |
| `get_permissions` | Declared permissions |
| `get_components` | Activities, services, receivers, providers |
| `list_units` | All analysis units |
| `list_classes` | List/filter classes |
| `decompile_class` | Decompile class to Java |
| `decompile_method` | Decompile method to Java |
| `get_class_hierarchy` | Superclasses, interfaces, subclasses |
| `get_overrides` | Method overrides (children/parents) |
| `get_xrefs` | Cross-references |
| `search_strings` | Regex search on string constants |
| `search_bytecode` | Regex search on Dalvik bytecode (FQN types) |
| `get_method_cfg` | Method instructions and control flow |
| `rename` | Rename a class, method, or field |
