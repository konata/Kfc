import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

const KFC_API = process.env.KFC_API_HOST ?? "http://localhost:9527";

async function jeb(path: string, params?: Record<string, string>): Promise<string> {
  const url = new URL(path, KFC_API);
  if (params) {
    for (const [k, v] of Object.entries(params)) {
      if (v !== undefined && v !== "") url.searchParams.set(k, v);
    }
  }
  const resp = await fetch(url);
  if (!resp.ok) {
    const body = await resp.text().catch(() => "");
    throw new Error(`KFC bridge returned ${resp.status}: ${body}`);
  }
  return resp.text();
}

function text(content: string) {
  return { content: [{ type: "text" as const, text: content }] };
}

// -------------------------------------------------------------------

const server = new McpServer({
  name: "kfc",
  version: "0.1.0",
});

// ---- Load ----

server.tool(
  "load_apk",
  "Load an APK/DEX file into JEB for analysis. Can be called at any time to switch targets.",
  {
    path: z.string().describe("Absolute path to the APK or DEX file on the server machine"),
  },
  async ({ path }) => text(await jeb("/api/load", { path })),
);

// ---- Meta tools ----

server.tool(
  "get_project_info",
  "Get overview of the loaded project: artifact names, DEX count, unit count.",
  {},
  async () => text(await jeb("/api/meta/project")),
);

server.tool(
  "get_manifest",
  "Get the full AndroidManifest.xml content of the loaded APK.",
  {},
  async () => text(await jeb("/api/meta/manifest")),
);

server.tool(
  "list_units",
  "List all analysis units (DEX, resources, native libs, etc.) in the project.",
  {},
  async () => text(await jeb("/api/meta/units")),
);

server.tool(
  "get_permissions",
  "Extract all Android permissions declared in the manifest.",
  {},
  async () => text(await jeb("/api/meta/permissions")),
);

server.tool(
  "get_components",
  "List Android components: activities, services, receivers, providers.",
  {},
  async () => text(await jeb("/api/meta/components")),
);

// ---- Code navigation tools ----

server.tool(
  "list_classes",
  "List classes in the DEX. Supports filtering by package/class name substring. Returns signature, supertype, interfaces, method/field counts. Use offset/limit for pagination.",
  {
    filter: z.string().optional().describe("Substring to filter class signatures (e.g. 'com.example')"),
    offset: z.number().optional().describe("Pagination offset (default 0)"),
    limit: z.number().optional().describe("Max results to return (default 200)"),
  },
  async ({ filter, offset, limit }) =>
    text(await jeb("/api/classes", {
      filter: filter ?? "",
      offset: offset?.toString() ?? "",
      limit: limit?.toString() ?? "",
    })),
);

server.tool(
  "decompile_class",
  "Decompile a class to Java source code. Provide the full Dalvik signature.",
  {
    cls: z.string().describe("Dalvik class signature, e.g. Lcom/example/MainActivity;"),
  },
  async ({ cls }) => text(await jeb("/api/decompile/class", { cls })),
);

server.tool(
  "decompile_method",
  "Decompile a specific method to Java source code.",
  {
    sig: z.string().describe("Full method signature, e.g. Lcom/example/Utils;->decrypt(Ljava/lang/String;)Ljava/lang/String;"),
  },
  async ({ sig }) => text(await jeb("/api/decompile/method", { sig })),
);

server.tool(
  "get_class_hierarchy",
  "Get the inheritance chain (superclasses), implemented interfaces, and direct subclasses of a class.",
  {
    cls: z.string().describe("Dalvik class signature"),
  },
  async ({ cls }) => text(await jeb("/api/hierarchy", { cls })),
);

server.tool(
  "get_xrefs",
  "Get cross-references for a method, field, or class. Shows which methods reference the target.",
  {
    sig: z.string().describe("Signature of method, field, or class to look up xrefs for"),
    limit: z.number().optional().describe("Max references to return (default 100)"),
  },
  async ({ sig, limit }) =>
    text(await jeb("/api/xrefs", { sig, limit: limit?.toString() ?? "" })),
);

server.tool(
  "search_strings",
  "Search for string constants in DEX matching a regex pattern. Also returns which methods reference each matched string.",
  {
    pattern: z.string().describe("Regex pattern to match against string constants"),
    limit: z.number().optional().describe("Max results (default 200)"),
  },
  async ({ pattern, limit }) =>
    text(await jeb("/api/strings", { pattern, limit: limit?.toString() ?? "" })),
);

server.tool(
  "get_method_cfg",
  "Get the control flow information for a method: instruction list with offsets, opcodes, and textual representation.",
  {
    sig: z.string().describe("Full method signature"),
  },
  async ({ sig }) => text(await jeb("/api/method/cfg", { sig })),
);

server.tool(
  "search_bytecode",
  `Search Dalvik bytecode across all methods using a regex pattern matched against formatted instructions.
Type names use full Dalvik signatures (e.g., Landroid/content/Intent; not Intent).

Examples:
  "invoke.*getLaunchedFromPackage" — any invoke calling getLaunchedFromPackage
  "const-string.*encrypt"          — const-string loading something with "encrypt"
  "invoke.*Landroid/telephony"     — any invoke into android.telephony package
  "sget.*Landroid/os/Build;"       — any static field read from android.os.Build
  "check-cast.*L.*Intent;"         — check-cast to any Intent type
  "check-cast.*Landroid/content/Intent;" — check-cast to android.content.Intent`,
  {
    pattern: z.string().describe("Regex pattern to match against formatted Dalvik instructions (case-insensitive)"),
    limit: z.number().optional().describe("Max results (default 100)"),
  },
  async ({ pattern, limit }) =>
    text(await jeb("/api/bytecode/search", { pattern, limit: limit?.toString() ?? "" })),
);

// ---- Rename tools ----

server.tool(
  "rename",
  "Rename a class, method, or field in JEB's analysis. The new name will be reflected in subsequent decompilation output. Provide the original Dalvik signature and the desired new name.",
  {
    sig: z.string().describe("Dalvik signature of the item to rename, e.g. Lcom/example/a; or Lcom/example/a;->b(I)V or Lcom/example/a;->c:I"),
    new_name: z.string().describe("New name (simple name only, e.g. 'MainActivity', 'decryptData', 'mCounter')"),
  },
  async ({ sig, new_name }) =>
    text(await jeb("/api/rename", { sig, new_name })),
);

// -------------------------------------------------------------------

async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
}

main();
