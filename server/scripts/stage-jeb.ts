#!/usr/bin/env bun

import { copyFileSync, existsSync, mkdirSync, readdirSync, rmSync, statSync } from "node:fs";
import { basename, dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const server = resolve(here, "..");
const repo = resolve(server, "..");
const out = join(server, "jeb");
const file = (path: string) => existsSync(path) && statSync(path).isFile();
const dir = (path: string) => existsSync(path) && statSync(path).isDirectory();
const fail = (message: string): never => {
  console.error(message);
  process.exit(1);
};

const script = join(repo, "kfc.py");
const jars = dir(join(repo, "extension", "build", "libs"))
  ? readdirSync(join(repo, "extension", "build", "libs")).filter((name) => /^kfc-.+\.jar$/.test(name)).sort()
  : [];
const jar = jars.at(-1) ?? fail("missing extension jar; run: bun run build:jeb");

if (!file(script)) fail(`missing ${script}`);

mkdirSync(out, { recursive: true });
for (const name of readdirSync(out).filter((name) => /^kfc-.+\.jar$/.test(name))) rmSync(join(out, name));
copyFileSync(script, join(out, "kfc.py"));
copyFileSync(join(repo, "extension", "build", "libs", jar), join(out, basename(jar)));
console.log(`staged ${jar} and kfc.py into ${out}`);
