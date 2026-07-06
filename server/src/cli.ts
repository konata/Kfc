#!/usr/bin/env bun

import { spawn } from "node:child_process";
import { copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { basename, dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

type Config = { jebHome?: string };

const src = dirname(fileURLToPath(import.meta.url));
const server = resolve(src, "..");
const repo = resolve(server, "..");
const configFile = join(homedir(), ".config", "kfc", "config.json");
const usage = `
Usage:
  kfc install --jeb-home <path>
  kfc bridge [--jeb-home <path>] [--port 9527]
  kfc mcp [--host http://localhost:9527]
  kfc doctor [--jeb-home <path>] [--host http://localhost:9527]
  kfc config codex|cursor|claude
`.trim();

const args = process.argv.slice(2);
const command = args.shift() ?? "help";
const option = (name: string) => {
  const index = args.indexOf(name);
  return index >= 0 ? args[index + 1] : undefined;
};
const die = (message: string): never => {
  console.error(message);
  process.exit(1);
};
const read = (): Config => {
  try { return JSON.parse(readFileSync(configFile, "utf8")) as Config; } catch { return {}; }
};
const save = (config: Config) => {
  mkdirSync(dirname(configFile), { recursive: true });
  writeFileSync(configFile, `${JSON.stringify({ ...read(), ...config }, null, 2)}\n`);
};
const home = () => resolve(option("--jeb-home") ?? process.env.JEB_HOME ?? read().jebHome ?? die("Missing JEB home. Pass --jeb-home once or set JEB_HOME."));
const file = (path: string) => existsSync(path) && statSync(path).isFile();
const dir = (path: string) => existsSync(path) && statSync(path).isDirectory();
const first = (paths: string[]) => paths.find(file);
const script = () => first([join(server, "jeb", "kfc.py"), join(repo, "kfc.py")]) ?? die("Cannot find kfc.py. Build from a source checkout or package with jeb assets.");
const jar = () => {
  const dirs = [join(server, "jeb"), join(repo, "extension", "build", "libs")].filter(dir);
  return dirs.flatMap((path) => readdirSync(path).filter((name) => /^kfc-.+\.jar$/.test(name)).map((name) => join(path, name))).sort().at(-1)
    ?? die("Cannot find kfc jar. Run: cd server && bun run build:jeb");
};
const bridgeHost = () => option("--host") ?? process.env.KFC_API_HOST ?? "http://localhost:9527";

async function install() {
  const jeb = home();
  const core = join(jeb, "coreplugins");
  if (!dir(core)) die(`Missing JEB coreplugins directory: ${core}`);

  for (const name of readdirSync(core).filter((name) => /^kfc-.+\.jar$/.test(name))) rmSync(join(core, name));
  copyFileSync(script(), join(core, "kfc.py"));
  copyFileSync(jar(), join(core, basename(jar())));
  save({ jebHome: jeb });
  console.log(`Installed KFC into ${core}`);
}

async function bridge() {
  const jeb = home();
  const launcher = option("--jeb") ?? join(jeb, "jeb_macos.sh");
  const script = join(jeb, "coreplugins", "kfc.py");
  if (!file(launcher)) die(`Missing JEB launcher: ${launcher}`);
  if (!file(script)) die(`Missing installed script: ${script}. Run: kfc install --jeb-home ${jeb}`);

  const port = option("--port") ?? "9527";
  const extra = `-Dkfc.port=${port}`;
  const env = { ...process.env, JAVA_TOOL_OPTIONS: [process.env.JAVA_TOOL_OPTIONS, extra].filter(Boolean).join(" ") };
  const child = spawn(launcher, ["-c", "--srv2", `--script=${script}`], { stdio: "inherit", env });
  child.on("exit", (code) => process.exit(code ?? 0));
}

async function doctor() {
  const jeb = option("--jeb-home") || process.env.JEB_HOME || read().jebHome;
  console.log(`config: ${configFile}${file(configFile) ? "" : " (missing)"}`);
  console.log(`jeb: ${jeb ?? "(unset)"}`);
  if (jeb) {
    console.log(`launcher: ${file(join(jeb, "jeb_macos.sh")) ? "ok" : "missing"}`);
    console.log(`plugin script: ${file(join(jeb, "coreplugins", "kfc.py")) ? "ok" : "missing"}`);
    console.log(`plugin jar: ${dir(join(jeb, "coreplugins")) && readdirSync(join(jeb, "coreplugins")).some((name) => /^kfc-.+\.jar$/.test(name)) ? "ok" : "missing"}`);
  }
  try {
    const res = await fetch(new URL("/api/health", bridgeHost()));
    console.log(`bridge: ${res.ok ? "ok" : `http ${res.status}`}`);
  } catch {
    console.log("bridge: offline");
  }
}

function config() {
  const host = bridgeHost();
  const kind = args[0] ?? "codex";
  if (kind === "codex") console.log(`codex mcp add kfc --env KFC_API_HOST=${host} -- kfc mcp`);
  else if (kind === "claude") console.log(`claude mcp add kfc --env KFC_API_HOST=${host} -- kfc mcp`);
  else if (kind === "cursor") console.log(JSON.stringify({ mcpServers: { kfc: { command: "kfc", args: ["mcp"], env: { KFC_API_HOST: host } } } }, null, 2));
  else die(`Unknown config target: ${kind}`);
}

async function mcp() {
  process.env.KFC_API_HOST = bridgeHost();
  await import("./index.ts");
}

if (command === "install") await install();
else if (command === "bridge") await bridge();
else if (command === "mcp") await mcp();
else if (command === "doctor") await doctor();
else if (command === "config") config();
else console.log(usage);
