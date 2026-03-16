import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

const api = process.env.KFC_API_HOST ?? "http://localhost:9527";
const server = new McpServer({ name: "kfc", version: "0.1.0" });

const out = (text: string) => ({ content: [{ type: "text" as const, text }] });

async function jeb(path: string, query: Record<string, string | number | undefined> = {}) {
  const url = new URL(path, api);
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== "") url.searchParams.set(key, String(value));
  }

  const res = await fetch(url);
  const body = await res.text().catch(() => "");
  if (!res.ok) throw new Error(`KFC bridge returned ${res.status}: ${body}`);
  return body;
}

function tool<T extends z.ZodRawShape>(
  name: string,
  description: string,
  shape: T,
  path: string,
  map?: (input: z.infer<z.ZodObject<T>>) => Record<string, string | number | undefined>,
) {
  server.tool(name, description, shape, async (input) => out(await jeb(path, map?.(input))));
}

tool(
  "load_apk",
  "Load an APK/DEX file into JEB for analysis. Can be called at any time to switch targets.",
  { path: z.string().describe("Absolute path to the APK or DEX file on the server machine") },
  "/api/load",
  ({ path }) => ({ path }),
);

tool("get_project_info", "Get overview of the loaded project: artifact names, DEX count, unit count.", {}, "/api/meta/project");
tool("get_manifest", "Get the full AndroidManifest.xml content of the loaded APK.", {}, "/api/meta/manifest");
tool("list_units", "List all analysis units (DEX, resources, native libs, etc.) in the project.", {}, "/api/meta/units");
tool("get_permissions", "Extract all Android permissions declared in the manifest.", {}, "/api/meta/permissions");
tool("get_components", "List Android components: activities, services, receivers, providers.", {}, "/api/meta/components");

tool(
  "list_classes",
  "List classes in the DEX. Supports filtering by package/class name substring. Returns signature, supertype, interfaces, method/field counts. Use offset/limit for pagination.",
  {
    filter: z.string().optional().describe("Substring to filter class signatures (e.g. 'com.example')"),
    offset: z.number().optional().describe("Pagination offset (default 0)"),
    limit: z.number().optional().describe("Max results to return (default 200)"),
  },
  "/api/classes",
  ({ filter, offset, limit }) => ({ filter, offset, limit }),
);

tool(
  "decompile_class",
  "Decompile a class to Java source code. Provide the full Dalvik signature.",
  { cls: z.string().describe("Dalvik class signature, e.g. Lcom/example/MainActivity;") },
  "/api/decompile/class",
  ({ cls }) => ({ cls }),
);

tool(
  "decompile_method",
  "Decompile a specific method to Java source code.",
  {
    sig: z.string().describe("Full method signature, e.g. Lcom/example/Utils;->decrypt(Ljava/lang/String;)Ljava/lang/String;"),
  },
  "/api/decompile/method",
  ({ sig }) => ({ sig }),
);

tool(
  "get_class_hierarchy",
  "Get the inheritance chain (superclasses), implemented interfaces, and direct subclasses of a class.",
  { cls: z.string().describe("Dalvik class signature") },
  "/api/hierarchy",
  ({ cls }) => ({ cls }),
);

tool(
  "get_class_methods",
  "Get the queried class plus each superclass in order, with only the methods declared on each class and basic override metadata.",
  { cls: z.string().describe("Dalvik class signature") },
  "/api/class/methods",
  ({ cls }) => ({ cls }),
);

tool(
  "get_overrides",
  "Get method overrides: children (classes overriding this method) and parents (methods this one overrides). Uses JEB's native type hierarchy analysis.",
  {
    sig: z.string().describe("Full method signature, e.g. Lcom/example/Base;->doAction(Ljava/lang/String;)V"),
  },
  "/api/overrides",
  ({ sig }) => ({ sig }),
);

tool(
  "get_xrefs",
  "Get cross-references for a method, field, or class. Shows which methods reference the target.",
  {
    sig: z.string().describe("Signature of method, field, or class to look up xrefs for"),
    limit: z.number().optional().describe("Max references to return (default 100)"),
  },
  "/api/xrefs",
  ({ sig, limit }) => ({ sig, limit }),
);

tool(
  "search_strings",
  "Search for string constants in DEX matching a regex pattern. Also returns which methods reference each matched string.",
  {
    pattern: z.string().describe("Regex pattern to match against string constants"),
    limit: z.number().optional().describe("Max results (default 200)"),
  },
  "/api/strings",
  ({ pattern, limit }) => ({ pattern, limit }),
);

tool(
  "get_method_cfg",
  "Get the control flow information for a method: instruction list with offsets, opcodes, and textual representation.",
  { sig: z.string().describe("Full method signature") },
  "/api/method/cfg",
  ({ sig }) => ({ sig }),
);

tool(
  "search_bytecode",
  `Search Dalvik bytecode across all methods using a regex pattern matched against formatted instructions.
Type names use full Dalvik signatures (e.g., Landroid/content/Intent; not Intent).

Examples:
  "invoke.*getLaunchedFromPackage" - any invoke calling getLaunchedFromPackage
  "const-string.*encrypt" - const-string loading something with "encrypt"
  "invoke.*Landroid/telephony" - any invoke into android.telephony package
  "sget.*Landroid/os/Build;" - any static field read from android.os.Build
  "check-cast.*L.*Intent;" - check-cast to any Intent type
  "check-cast.*Landroid/content/Intent;" - check-cast to android.content.Intent`,
  {
    pattern: z.string().describe("Regex pattern to match against formatted Dalvik instructions (case-insensitive)"),
    limit: z.number().optional().describe("Max results (default 100)"),
  },
  "/api/bytecode/search",
  ({ pattern, limit }) => ({ pattern, limit }),
);

tool(
  "rename",
  "Rename a class, method, or field in JEB's analysis. The new name will be reflected in subsequent decompilation output. Provide the original Dalvik signature and the desired new name.",
  {
    sig: z.string().describe("Dalvik signature of the item to rename, e.g. Lcom/example/a; or Lcom/example/a;->b(I)V or Lcom/example/a;->c:I"),
    new_name: z.string().describe("New name (simple name only, e.g. 'MainActivity', 'decryptData', 'mCounter')"),
  },
  "/api/rename",
  ({ sig, new_name }) => ({ sig, new_name }),
);

async function main() {
  await server.connect(new StdioServerTransport());
}

main();
