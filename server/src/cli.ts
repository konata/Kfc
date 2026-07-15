#!/usr/bin/env bun

import { spawn, spawnSync } from "node:child_process";
import { existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { delimiter, dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

type Config = { jebHome?: string };

const src = dirname(fileURLToPath(import.meta.url));
const server = resolve(src, "..");
const repo = resolve(server, "..");
const configFile = join(homedir(), ".config", "kfc", "config.json");
const usage = `
Usage:
  kfc use <jeb-home>
  kfc bridge [--jeb-home <path>] [--java <path>] [--port 9527]
  kfc mcp [--host http://localhost:9527]
  kfc doctor [--jeb-home <path>] [--java <path>] [--host http://localhost:9527]
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
const home = () => resolve(option("--jeb-home") ?? process.env.JEB_HOME ?? read().jebHome ?? die("Missing JEB home. Run 'kfc use <jeb-home>', pass --jeb-home, or set JEB_HOME."));
const file = (path: string) => existsSync(path) && statSync(path).isFile();
const dir = (path: string) => existsSync(path) && statSync(path).isDirectory();
const first = (paths: string[]) => paths.find(file);
const bridgeJar = () => {
  const dirs = [join(server, "jeb"), join(repo, "extension", "build", "libs")].filter(dir);
  return dirs.flatMap((path) => readdirSync(path).filter((name) => /^kfc-.+\.jar$/.test(name)).map((name) => join(path, name))).sort().at(-1);
};
const jar = () => bridgeJar() ?? die("Cannot find kfc jar. Run: cd server && bun run build:jeb");
const java = (jeb: string) => option("--java") ?? first([
  join(jeb, "bin", "runtime", "bin", "java"),
  ...(process.env.JAVA_HOME ? [join(process.env.JAVA_HOME, "bin", "java")] : []),
]) ?? "java";
const bridgeHost = () => option("--host") ?? process.env.KFC_API_HOST ?? "http://localhost:9527";

function use() {
  const jeb = resolve(args[0] ?? die("Missing JEB home. Usage: kfc use <jeb-home>"));
  const core = join(jeb, "bin", "app", "jeb.jar");
  if (!file(core)) die(`Missing JEB core jar: ${core}`);

  save({ jebHome: jeb });
  console.log(`Configured KFC for JEB at ${jeb}`);
}

async function bridge() {
  const jeb = home();
  const core = join(jeb, "bin", "app", "jeb.jar");
  if (!file(core)) die(`Missing JEB core jar: ${core}`);

  const port = option("--port") ?? "9527";
  const child = spawn(java(jeb), [`-Dkfc.port=${port}`, "-cp", [join(jeb, "bin", "app", "*"), jar()].join(delimiter), "kfc.mcp.Main"], {
    stdio: "inherit",
    cwd: jeb,
  });
  child.on("error", (error) => die(`Cannot start JEB: ${error.message}`));
  child.on("exit", (code) => process.exit(code ?? 0));
}

async function doctor() {
  const jeb = option("--jeb-home") || process.env.JEB_HOME || read().jebHome;
  console.log(`config: ${configFile}${file(configFile) ? "" : " (missing)"}`);
  console.log(`jeb: ${jeb ?? "(unset)"}`);
  if (jeb) {
    const executable = java(jeb);
    console.log(`JEB core: ${file(join(jeb, "bin", "app", "jeb.jar")) ? "ok" : "missing"}`);
    console.log(`Java: ${spawnSync(executable, ["-version"], { stdio: "ignore" }).status === 0 ? "ok" : `missing (${executable})`}`);
  }
  console.log(`KFC bridge jar: ${bridgeJar() ? "ok" : "missing"}`);
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

if (command === "use") use();
else if (command === "bridge") await bridge();
else if (command === "mcp") await mcp();
else if (command === "doctor") await doctor();
else if (command === "config") config();
else console.log(usage);
