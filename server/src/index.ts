import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

const api = process.env.KFC_API_HOST ?? "http://localhost:9527";
const server = new McpServer({ name: "kfc", version: "0.1.0" });

type Query = Record<string, string | number | undefined>;
type Shape = z.ZodRawShape;

const ref = z.object({ address: z.string() });
const artifact = z.object({ name: z.string() });
const unit = z.object({ name: z.string(), type: z.string(), description: z.string(), status: z.string() });
const listedClass = z.object({
  signature: z.string(),
  flags: z.number(),
  supertype: z.string(),
  interfaces: z.array(z.string()),
  method_count: z.number(),
  field_count: z.number(),
});
const classMethod = z.object({
  signature: z.string(),
  declared_in: z.string(),
  name: z.string(),
  sub_signature: z.string(),
  generic_flags: z.number(),
  access_flags: z.number(),
  public: z.boolean(),
  protected: z.boolean(),
  private: z.boolean(),
  static: z.boolean(),
  final: z.boolean(),
  abstract: z.boolean(),
  native: z.boolean(),
  synthetic: z.boolean(),
  constructor: z.boolean(),
  has_code: z.boolean(),
  overrides: z.string().nullable(),
});
const classChain = z.object({
  class: z.string(),
  resolved: z.boolean(),
  super: z.string(),
  interfaces: z.array(z.string()),
  methods: z.array(classMethod),
});
const stringMatch = z.object({ value: z.string(), index: z.number(), referenced_by: z.array(ref) });
const instruction = z.object({ offset: z.number(), opcode: z.string(), text: z.string() });
const bytecodeMatch = z.object({ method: z.string(), class: z.string(), offset: z.number(), instruction: z.string() });

const pretty = (value: unknown) => JSON.stringify(value, null, 2);

const parse = (path: string, body: string) => {
  try {
    return JSON.parse(body) as unknown;
  } catch {
    throw new Error(`Invalid JSON from KFC bridge at ${path}: ${body}`);
  }
};

const bridgeError = (value: unknown): value is { error: string } =>
  typeof value === "object" && value !== null && "error" in value && typeof (value as Record<string, unknown>).error === "string";

async function jeb(path: string, query: Query = {}) {
  const url = new URL(path, api);
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== "") url.searchParams.set(key, String(value));
  }

  const res = await fetch(url);
  const body = await res.text().catch(() => "");
  if (!res.ok) throw new Error(`KFC bridge returned ${res.status}: ${body}`);
  return body;
}

async function call(
  path: string,
  query: Query,
  output: z.AnyZodObject,
  project: (value: unknown) => unknown = (value) => value,
) {
  const value = parse(path, await jeb(path, query));
  if (bridgeError(value)) return { content: [{ type: "text" as const, text: value.error }], isError: true as const };

  const structuredContent = output.parse(project(value));
  return { content: [{ type: "text" as const, text: pretty(structuredContent) }], structuredContent };
}

function tool<I extends Shape, O extends Shape>(
  name: string,
  description: string,
  inputSchema: I,
  outputSchema: O,
  path: string,
  map: (input: z.infer<z.ZodObject<I>>) => Query = () => ({}),
  project?: (value: unknown) => unknown,
) {
  const output = z.object(outputSchema);
  server.registerTool(name, { description, inputSchema, outputSchema }, (async (input: unknown) =>
    call(path, map(input as z.infer<z.ZodObject<I>>), output, project)) as any);
}

tool(
  "load_apk",
  "Load an APK/DEX file into JEB for analysis. Can be called at any time to switch targets.",
  { path: z.string().describe("Absolute path to the APK or DEX file on the server machine") },
  { success: z.boolean(), path: z.string(), units: z.number(), dex_count: z.number(), previous: z.string().nullable() },
  "/api/load",
  ({ path }) => ({ path }),
);

tool(
  "get_project_info",
  "Get overview of the loaded project: artifact names, DEX count, unit count.",
  {},
  {
    loaded: z.boolean(),
    path: z.string().nullable(),
    project_name: z.string().nullable(),
    apk_detected: z.boolean(),
    dex_count: z.number(),
    total_units: z.number(),
    artifacts: z.array(artifact),
    message: z.string().nullable(),
  },
  "/api/meta/project",
  () => ({}),
  (value) => {
    const item = value as Record<string, unknown>;
    return {
      loaded: item.loaded,
      path: item.path ?? null,
      project_name: item.project_name ?? null,
      apk_detected: item.apk_detected ?? false,
      dex_count: item.dex_count ?? 0,
      total_units: item.total_units ?? 0,
      artifacts: item.artifacts ?? [],
      message: item.message ?? null,
    };
  },
);

tool("get_manifest", "Get the full AndroidManifest.xml content of the loaded APK.", {}, { content: z.string() }, "/api/meta/manifest");

tool(
  "list_units",
  "List all analysis units (DEX, resources, native libs, etc.) in the project.",
  {},
  { units: z.array(unit) },
  "/api/meta/units",
  () => ({}),
  (value) => ({ units: value }),
);

tool("get_permissions", "Extract all Android permissions declared in the manifest.", {}, { permissions: z.array(z.string()) }, "/api/meta/permissions");

tool(
  "get_components",
  "List Android components: activities, services, receivers, providers.",
  {},
  {
    activities: z.array(z.string()),
    services: z.array(z.string()),
    receivers: z.array(z.string()),
    providers: z.array(z.string()),
  },
  "/api/meta/components",
);

tool(
  "list_classes",
  "List classes in the DEX. Supports filtering by package/class name substring. Returns signature, supertype, interfaces, method/field counts. Use offset/limit for pagination.",
  {
    filter: z.string().optional().describe("Substring to filter class signatures (e.g. 'com.example')"),
    offset: z.number().optional().describe("Pagination offset (default 0)"),
    limit: z.number().optional().describe("Max results to return (default 200)"),
  },
  { total: z.number(), offset: z.number(), limit: z.number(), classes: z.array(listedClass) },
  "/api/classes",
  ({ filter, offset, limit }) => ({ filter, offset, limit }),
);

tool(
  "decompile_class",
  "Decompile a class to Java source code. Provide the full Dalvik signature.",
  { cls: z.string().describe("Dalvik class signature, e.g. Lcom/example/MainActivity;") },
  { signature: z.string(), source: z.string() },
  "/api/decompile/class",
  ({ cls }) => ({ cls }),
);

tool(
  "decompile_method",
  "Decompile a specific method to Java source code.",
  { sig: z.string().describe("Full method signature, e.g. Lcom/example/Utils;->decrypt(Ljava/lang/String;)Ljava/lang/String;") },
  { method_signature: z.string(), class_signature: z.string(), source: z.string(), note: z.string().nullable() },
  "/api/decompile/method",
  ({ sig }) => ({ sig }),
);

tool(
  "get_class_hierarchy",
  "Get the inheritance chain (superclasses), implemented interfaces, and direct subclasses of a class.",
  { cls: z.string().describe("Dalvik class signature") },
  { signature: z.string(), superclass_chain: z.array(z.string()), interfaces: z.array(z.string()), subclasses: z.array(z.string()) },
  "/api/hierarchy",
  ({ cls }) => ({ cls }),
);

tool(
  "get_class_methods",
  "Get the queried class plus each superclass in order, with only the methods declared on each class and basic override metadata.",
  { cls: z.string().describe("Dalvik class signature") },
  { query_class: z.string(), resolved: z.boolean(), class_count: z.number(), class_chain: z.array(classChain) },
  "/api/class/methods",
  ({ cls }) => ({ cls }),
);

tool(
  "get_overrides",
  "Get method overrides: children (classes overriding this method) and parents (methods this one overrides). Uses JEB's native type hierarchy analysis.",
  { sig: z.string().describe("Full method signature, e.g. Lcom/example/Base;->doAction(Ljava/lang/String;)V") },
  { method_signature: z.string(), children: z.array(z.string()), parents: z.array(z.string()), children_count: z.number(), parents_count: z.number() },
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
  { target: z.string(), type: z.string(), references_to: z.array(ref), reference_count: z.number() },
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
  { pattern: z.string(), count: z.number(), limit: z.number(), matches: z.array(stringMatch) },
  "/api/strings",
  ({ pattern, limit }) => ({ pattern, limit }),
);

tool(
  "get_method_cfg",
  "Get the control flow information for a method: instruction list with offsets, opcodes, and textual representation.",
  { sig: z.string().describe("Full method signature") },
  { method_signature: z.string(), instruction_count: z.number(), register_count: z.number(), instructions: z.array(instruction) },
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
  { pattern: z.string(), count: z.number(), limit: z.number(), matches: z.array(bytecodeMatch) },
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
  { type: z.string(), signature: z.string(), new_name: z.string(), success: z.boolean() },
  "/api/rename",
  ({ sig, new_name }) => ({ sig, new_name }),
);

async function main() {
  await server.connect(new StdioServerTransport());
}

main();
