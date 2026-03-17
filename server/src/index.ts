import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { XMLParser } from "fast-xml-parser";
import { z } from "zod";

const api = process.env.KFC_API_HOST ?? "http://localhost:9527";
const server = new McpServer({ name: "kfc", version: "0.1.0" });
const xml = new XMLParser({ ignoreAttributes: false, attributeNamePrefix: "", parseTagValue: false, trimValues: true });
const componentKinds = ["activity", "service", "receiver", "provider"] as const;

type Query = Record<string, string | number | undefined>;
type Shape = z.ZodRawShape;
type Json = Record<string, unknown>;
type ComponentKind = (typeof componentKinds)[number];
type ManifestComponent = {
  name: string;
  manifestName: string;
  signature: string | null;
  kind: ComponentKind;
  exported: boolean | null;
  exportedSource: "explicit" | "intent_filter" | "unknown";
  permission: string | null;
  readPermission: string | null;
  writePermission: string | null;
  authorities: string | null;
  process: string | null;
  enabled: boolean | null;
  directBootAware: boolean | null;
  actions: string[];
  categories: string[];
  data: string[];
};

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
  overrides: z.string().nullish(),
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
const componentKind = z.enum(componentKinds);
const componentInfo = z.object({
  name: z.string(),
  manifest_name: z.string(),
  signature: z.string().nullable(),
  kind: componentKind,
  exported: z.boolean().nullable(),
  exported_source: z.enum(["explicit", "intent_filter", "unknown"]),
  permission: z.string().nullable(),
  read_permission: z.string().nullable(),
  write_permission: z.string().nullable(),
  authorities: z.string().nullable(),
  process: z.string().nullable(),
  enabled: z.boolean().nullable(),
  direct_boot_aware: z.boolean().nullable(),
  actions: z.array(z.string()),
  categories: z.array(z.string()),
  data: z.array(z.string()),
});
const classMethodsOut = z.object({
  query_class: z.string(),
  resolved: z.boolean(),
  class_count: z.number(),
  class_chain: z.array(classChain),
});
const decompiledMethodOut = z.object({
  method_signature: z.string(),
  class_signature: z.string(),
  source: z.string(),
  note: z.string().nullish(),
});
const xrefsOut = z.object({
  target: z.string(),
  type: z.string(),
  references_to: z.array(ref),
  reference_count: z.number(),
});
const entryPoint = z.object({
  name: z.string(),
  signature: z.string(),
  declared_in: z.string(),
  overrides: z.string().nullish(),
  source: z.string().nullable(),
});
const caller = z.object({
  method_signature: z.string(),
  class_signature: z.string().nullable(),
  reference_hits: z.number(),
  addresses: z.array(z.string()),
  source: z.string().nullable(),
});

const pretty = (value: unknown) => JSON.stringify(value, null, 2);
const list = <T>(value: T | T[] | null | undefined) => value == null ? [] : Array.isArray(value) ? value : [value];
const unique = <T>(value: T[]) => [...new Set(value)];
const message = (error: unknown) => error instanceof Error ? error.message : String(error);
const record = (value: unknown): Json => typeof value === "object" && value !== null ? (value as Json) : {};
const attr = (value: unknown, key: string) => {
  const item = record(value)[key];
  return typeof item === "string" ? item : typeof item === "number" || typeof item === "boolean" ? String(item) : undefined;
};
const bool = (value?: string) => value == null ? null : value === "true" ? true : value === "false" ? false : null;
const clip = (value: string, limit: number) => value.length <= limit ? value : `${value.slice(0, limit)}\n...`;

const parseJson = (path: string, body: string) => {
  try {
    return JSON.parse(body) as unknown;
  } catch {
    throw new Error(`Invalid JSON from KFC bridge at ${path}: ${body}`);
  }
};

const bridgeError = (value: unknown): value is { error: string } =>
  typeof value === "object" && value !== null && "error" in value && typeof (value as Json).error === "string";

const dalvik = (name: string) => `L${name.replace(/\./g, "/")};`;
const javaName = (signature: string) => signature.startsWith("L") && signature.endsWith(";") ? signature.slice(1, -1).replace(/\//g, ".") : signature;
const normalizeName = (pkg: string, name: string) => !name ? name : name.startsWith(".") ? `${pkg}${name}` : name.includes(".") ? name : `${pkg}.${name}`;
const methodFromAddress = (address: string) => address.includes("+") ? address.slice(0, address.lastIndexOf("+")) : address;
const classFromMethod = (signature: string) => signature.includes("->") ? signature.slice(0, signature.indexOf("->")) : signature.endsWith(";") ? signature : null;
const entryNames = (kind: ComponentKind) =>
  ({
    activity: ["onCreate", "onStart", "onResume", "onNewIntent", "onActivityResult", "onCreatePreferences"],
    service: ["onCreate", "onStartCommand", "onBind", "onHandleIntent", "onDestroy"],
    receiver: ["onReceive"],
    provider: ["onCreate", "query", "insert", "update", "delete", "call", "openFile", "openAssetFile", "openTypedAssetFile"],
  })[kind];

function intentData(filter: unknown) {
  return list(record(filter).data).map((item) => {
    const fields = ["android:scheme", "android:host", "android:path", "android:pathPrefix", "android:mimeType"]
      .flatMap((key) => {
        const value = attr(item, key);
        return value ? [`${key.replace("android:", "")}=${value}`] : [];
      });
    return fields.join(", ");
  }).filter(Boolean);
}

function toComponent(kind: ComponentKind, node: unknown, pkg: string): ManifestComponent {
  const manifestName = attr(node, "android:name") ?? "";
  const name = normalizeName(pkg, manifestName);
  const signature = name ? dalvik(name) : null;
  const filters = list(record(node)["intent-filter"]);
  const explicit = bool(attr(node, "android:exported"));
  const inferred = explicit == null && kind !== "provider" && filters.length > 0 ? true : null;

  return {
    name,
    manifestName,
    signature,
    kind,
    exported: explicit ?? inferred,
    exportedSource: explicit != null ? "explicit" : inferred != null ? "intent_filter" : "unknown",
    permission: attr(node, "android:permission") ?? null,
    readPermission: attr(node, "android:readPermission") ?? null,
    writePermission: attr(node, "android:writePermission") ?? null,
    authorities: attr(node, "android:authorities") ?? null,
    process: attr(node, "android:process") ?? null,
    enabled: bool(attr(node, "android:enabled")),
    directBootAware: bool(attr(node, "android:directBootAware")),
    actions: unique(filters.flatMap((filter) => list(record(filter).action).map((item) => attr(item, "android:name")).filter(Boolean) as string[])),
    categories: unique(filters.flatMap((filter) => list(record(filter).category).map((item) => attr(item, "android:name")).filter(Boolean) as string[])),
    data: unique(filters.flatMap(intentData)),
  };
}

function parseManifest(content: string) {
  const manifest = record(record(xml.parse(content)).manifest);
  const pkg = attr(manifest, "package") ?? "";
  const app = record(manifest.application);
  const components = componentKinds.flatMap((kind) => list(app[kind]).map((node) => toComponent(kind, node, pkg)));
  return { packageName: pkg, components };
}

function describeComponent(component: ManifestComponent) {
  return {
    name: component.name,
    manifest_name: component.manifestName,
    signature: component.signature,
    kind: component.kind,
    exported: component.exported,
    exported_source: component.exportedSource,
    permission: component.permission,
    read_permission: component.readPermission,
    write_permission: component.writePermission,
    authorities: component.authorities,
    process: component.process,
    enabled: component.enabled,
    direct_boot_aware: component.directBootAware,
    actions: component.actions,
    categories: component.categories,
    data: component.data,
  };
}

function findComponent(components: ManifestComponent[], value: string) {
  const exact = components.find((component) => [component.name, component.manifestName, component.signature].includes(value));
  if (exact) return exact;

  const matches = components.filter((component) => component.name.endsWith(`.${value}`) || component.manifestName.endsWith(value));
  if (matches.length === 1) return matches[0];
  if (matches.length > 1) throw new Error(`Ambiguous component '${value}': ${matches.slice(0, 5).map((item) => item.name).join(", ")}`);
  throw new Error(`Component not found in manifest: ${value}`);
}

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

async function bridge(path: string, query: Query = {}) {
  const value = parseJson(path, await jeb(path, query));
  if (bridgeError(value)) throw new Error(value.error);
  return value;
}

async function respond<O extends z.AnyZodObject>(output: O, run: () => Promise<unknown>) {
  try {
    const structuredContent = output.parse(await run());
    return { content: [{ type: "text" as const, text: pretty(structuredContent) }], structuredContent };
  } catch (error) {
    return { content: [{ type: "text" as const, text: message(error) }], isError: true as const };
  }
}

function tool<I extends Shape, O extends Shape>(
  name: string,
  description: string,
  inputSchema: I,
  outputSchema: O,
  path: string,
  map: (input: z.infer<z.ZodObject<I>>) => Query = () => ({}),
  project: (value: unknown) => unknown = (value) => value,
) {
  const input = z.object(inputSchema);
  const output = z.object(outputSchema);
  server.registerTool(name, { description, inputSchema, outputSchema }, (async (raw: unknown) =>
    respond(output, () => bridge(path, map(input.parse(raw))).then(project))) as any);
}

function task<I extends Shape, O extends Shape>(
  name: string,
  description: string,
  inputSchema: I,
  outputSchema: O,
  run: (input: z.infer<z.ZodObject<I>>) => Promise<unknown>,
) {
  const input = z.object(inputSchema);
  const output = z.object(outputSchema);
  server.registerTool(name, { description, inputSchema, outputSchema }, (async (raw: unknown) =>
    respond(output, () => run(input.parse(raw)))) as any);
}

tool(
  "load_apk",
  "Load an APK/DEX file into JEB for analysis. Can be called at any time to switch targets.",
  { path: z.string().describe("Absolute path to the APK or DEX file on the server machine") },
  { success: z.boolean(), path: z.string(), units: z.number(), dex_count: z.number(), previous: z.string().nullable() },
  "/api/load",
  ({ path }) => ({ path }),
  (value) => {
    const item = record(value);
    return {
      success: item.success ?? true,
      path: item.path,
      units: item.units,
      dex_count: item.dex_count,
      previous: item.previous ?? null,
    };
  },
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
    const item = record(value);
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
  { method_signature: z.string(), class_signature: z.string(), source: z.string(), note: z.string().nullish() },
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

task(
  "find_exported_components",
  "Find exported Android components and surface their declared permissions, authorities, and intent filters. Prefer this over raw manifest parsing when you want the app's exposed entry points.",
  { kind: componentKind.optional().describe("Optional component type filter: activity, service, receiver, or provider") },
  { package_name: z.string(), count: z.number(), components: z.array(componentInfo) },
  async ({ kind }) => {
    const manifest = z.object({ content: z.string() }).parse(await bridge("/api/meta/manifest"));
    const parsed = parseManifest(manifest.content);
    const components = parsed.components
      .filter((component) => component.exported === true && (!kind || component.kind === kind))
      .map(describeComponent);
    return { package_name: parsed.packageName, count: components.length, components };
  },
);

task(
  "inspect_component",
  "Inspect a manifest component and summarize the metadata and likely entry methods the AI should read first.",
  {
    component: z.string().describe("Component name, manifest name, or Dalvik class signature"),
    include_source: z.boolean().optional().describe("Include decompiled source for likely entry methods (default true)"),
    source_limit: z.number().optional().describe("Maximum characters per entry method source snippet (default 2000)"),
  },
  {
    component: componentInfo,
    resolved: z.boolean(),
    class_signature: z.string().nullable(),
    superclass_chain: z.array(z.string()),
    interfaces: z.array(z.string()),
    entry_point_count: z.number(),
    entry_points: z.array(entryPoint),
    notes: z.array(z.string()),
  },
  async ({ component, include_source = true, source_limit = 2000 }) => {
    const manifest = z.object({ content: z.string() }).parse(await bridge("/api/meta/manifest"));
    const selected = findComponent(parseManifest(manifest.content).components, component);
    const notes: string[] = [];
    const declared = componentInfo.parse(describeComponent(selected));

    if (!selected.signature) {
      return {
        component: declared,
        resolved: false,
        class_signature: null,
        superclass_chain: [],
        interfaces: [],
        entry_point_count: 0,
        entry_points: [],
        notes: ["Component name could not be resolved to a Dalvik class signature."],
      };
    }

    const classMethods = classMethodsOut.parse(await bridge("/api/class/methods", { cls: selected.signature }));
    const primary = classMethods.class_chain.find((item) => item.class === selected.signature) ?? classMethods.class_chain[0];
    const preferred = entryNames(selected.kind);
    const preferredSet = new Set(preferred);
    const declaredMethods = primary?.methods.filter((method) => method.declared_in === selected.signature) ?? [];
    const matched = declaredMethods.filter((method) => preferredSet.has(method.name));
    const methods = (matched.length ? matched : declaredMethods.filter((method) => method.has_code && !method.constructor).slice(0, 5))
      .sort((left, right) => preferred.indexOf(left.name) - preferred.indexOf(right.name));

    if (!matched.length) notes.push("No standard lifecycle entry methods were found; returned the first declared code methods instead.");

    const entryPoints = await Promise.all(methods.map(async (method) => {
      const source = !include_source ? null : await bridge("/api/decompile/method", { sig: method.signature })
        .then((value) => decompiledMethodOut.parse(value))
        .then((value) => clip(value.source, source_limit))
        .catch(() => null);
      return {
        name: method.name,
        signature: method.signature,
        declared_in: method.declared_in,
        overrides: method.overrides,
        source,
      };
    }));

    return {
      component: declared,
      resolved: true,
      class_signature: selected.signature,
      superclass_chain: classMethods.class_chain.map((item) => item.class),
      interfaces: primary?.interfaces ?? [],
      entry_point_count: entryPoints.length,
      entry_points: entryPoints,
      notes,
    };
  },
);

task(
  "find_callers",
  "Resolve a method, field, or class xref result into caller methods that are easier for the AI to inspect than raw addresses.",
  {
    sig: z.string().describe("Target method, field, or class signature"),
    limit: z.number().optional().describe("Max xref addresses to inspect (default 100)"),
    include_source: z.boolean().optional().describe("Include decompiled caller source snippets (default false)"),
    source_limit: z.number().optional().describe("Maximum characters per caller source snippet (default 1200)"),
  },
  {
    target: z.string(),
    type: z.string(),
    reference_count: z.number(),
    caller_count: z.number(),
    callers: z.array(caller),
  },
  async ({ sig, limit = 100, include_source = false, source_limit = 1200 }) => {
    const refs = xrefsOut.parse(await bridge("/api/xrefs", { sig, limit }));
    const groups = new Map<string, string[]>();

    for (const item of refs.references_to) {
      const method = methodFromAddress(item.address);
      groups.set(method, [...(groups.get(method) ?? []), item.address]);
    }

    const callers = await Promise.all([...groups.entries()]
      .sort((left, right) => right[1].length - left[1].length)
      .map(async ([methodSignature, addresses]) => {
        const classSignature = classFromMethod(methodSignature);
        const source = !include_source || !methodSignature.includes("->") ? null : await bridge("/api/decompile/method", { sig: methodSignature })
          .then((value) => decompiledMethodOut.parse(value))
          .then((value) => clip(value.source, source_limit))
          .catch(() => null);
        return {
          method_signature: methodSignature,
          class_signature: classSignature,
          reference_hits: addresses.length,
          addresses,
          source,
        };
      }));

    return {
      target: refs.target,
      type: refs.type,
      reference_count: refs.reference_count,
      caller_count: callers.length,
      callers,
    };
  },
);

async function main() {
  await server.connect(new StdioServerTransport());
}

main();
