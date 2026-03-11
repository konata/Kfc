# KFC

> IDA → JEB → KFC

AI-powered reverse engineering assistant. Bridges JEB's analysis engine to AI via MCP, letting AI navigate decompiled code, query cross-references, inspect class hierarchies, and more — just like a human analyst would in JEB.

## Architecture

```
AI (Cursor/Claude Code) ←stdio→ MCP Server (TS/Bun) ←HTTP→ JEB Headless + KFC Extension (Kotlin)
```

- **extension/** — Kotlin JEB script that starts an HTTP server inside JEB headless, exposing analysis capabilities
- **server/** — TypeScript MCP server (runs on Bun) that translates AI tool calls into HTTP requests to the extension

## Prerequisites

- JEB Pro with headless support
- JDK 17+
- [Bun](https://bun.sh)

## Setup

### 1. Build the extension

Set your JEB installation path in `gradle.properties`:

```properties
jebHome=/Applications/JEB Pro
```

Then build:

```bash
./gradlew build
```

This produces `extension/build/libs/kfc-0.1.0.jar`.

### 2. Install MCP server dependencies

```bash
cd server && bun install
```

### 3. Deploy to JEB

Copy the built JAR and launcher script to JEB's `coreplugins/` directory:

```bash
cp extension/build/libs/kfc-0.1.0.jar /path/to/jeb/coreplugins/
cp kfc.py /path/to/jeb/coreplugins/
```

### 4. Start JEB with KFC

```bash
/path/to/jeb/jeb_macos.sh -c --srv2 --script=/path/to/jeb/coreplugins/kfc.py -- /path/to/target.apk
```

You should see:

```
[kfc] Project: target
[kfc] Starting bridge on port 8199 ...
[kfc] Ready at http://localhost:8199
```

### 5. Configure your AI client

**Cursor** — add to `.cursor/mcp.json` (project-level) or Cursor Settings > MCP (global):

```json
{
  "mcpServers": {
    "kfc": {
      "command": "bun",
      "args": ["/path/to/kfc/server/src/index.ts"],
      "env": { "KFC_API_HOST": "http://localhost:8199" }
    }
  }
}
```

**Claude Code:**

```bash
claude mcp add kfc -- bun /path/to/kfc/server/src/index.ts
```

Or add the same JSON block to `~/.claude.json` for global access.

## Shell Alias

Add to your shell rc file (e.g. `~/.zshrc` or a sourced rc):

```bash
kfc() { /path/to/jeb/jeb_macos.sh -c --srv2 --script=/path/to/jeb/coreplugins/kfc.py -- "$(realpath "$1")"; }
```

Then simply:

```bash
kfc /path/to/target.apk
```

## Available Tools

| Tool | Description |
|---|---|
| `get_project_info` | Project overview: artifacts, DEX count, unit count |
| `get_manifest` | Full AndroidManifest.xml content |
| `get_permissions` | Extracted Android permissions |
| `get_components` | Activities, services, receivers, providers |
| `list_units` | All analysis units in the project |
| `list_classes` | List/filter classes with pagination |
| `decompile_class` | Decompile a class to Java source |
| `decompile_method` | Decompile a specific method |
| `get_class_hierarchy` | Superclass chain, interfaces, subclasses |
| `get_xrefs` | Cross-references for methods, fields, or classes |
| `search_strings` | Regex search on string constants with xref info |
| `search_bytecode` | Glob search across all Dalvik bytecode instructions |
| `get_method_cfg` | Control flow: instructions, opcodes, offsets |

## Custom Port

```bash
# JEB side
java -Dkfc.port=9000 -jar jeb.jar -c --srv2 --script=kfc.py -- app.apk

# MCP server side
KFC_API_HOST=http://localhost:9000 bun server/src/index.ts
```
