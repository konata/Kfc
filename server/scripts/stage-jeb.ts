#!/usr/bin/env bun

import { copyFileSync, existsSync, mkdirSync, readdirSync, rmSync } from "node:fs";
import { basename, dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const server = resolve(here, "..");
const repo = resolve(server, "..");
const out = join(server, "jeb");
const fail = (message: string): never => {
  console.error(message);
  process.exit(1);
};

const libs = join(repo, "extension", "build", "libs");
const jars = existsSync(libs) ? readdirSync(libs).filter((name) => /^kfc-.+\.jar$/.test(name)).sort() : [];
const jar = jars.at(-1) ?? fail("missing extension jar; run: bun run build:jeb");

mkdirSync(out, { recursive: true });
for (const name of readdirSync(out).filter((name) => /^kfc-.+\.jar$/.test(name))) rmSync(join(out, name));
copyFileSync(join(libs, jar), join(out, basename(jar)));
console.log(`staged ${jar} into ${out}`);
