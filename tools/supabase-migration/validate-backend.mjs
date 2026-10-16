#!/usr/bin/env node
import { access, mkdtemp, rm } from "node:fs/promises";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { join } from "node:path";
import { tmpdir } from "node:os";

const root = new URL("../../", import.meta.url);
const scripts = [
  "validate-orders.mjs",
  "validate-staff-settings.mjs",
  "validate-payments.mjs",
  "validate-subscriptions.mjs",
];
const strict = process.argv.includes("--strict");
let fixtureDir = null;
let fixturePath = process.env.SUPABASE_ORDER_FIXTURES ?? null;
let fixtureSetupSucceeded = false;

async function exists(name) {
  try { await access(new URL(`tools/supabase-migration/${name}`, root)); return true; }
  catch { return false; }
}

async function runScript(name, extraArgs = []) {
  return await new Promise((resolve) => {
    const args = [fileURLToPath(new URL(`tools/supabase-migration/${name}`, root)), ...extraArgs];
    if (strict) args.push("--strict");
    const child = spawn(process.execPath, args, { stdio: "inherit", env: process.env });
    child.on("error", () => resolve(false));
    child.on("exit", (code) => resolve(code === 0));
  });
}

async function run(name) {
  if (!(await exists(name))) {
    console.log(`${name}: SKIPPED_NOT_PRESENT`);
    return true;
  }
  const usesOrderFixture = name === "validate-orders.mjs" || name === "validate-payments.mjs";
  return await runScript(name, usesOrderFixture && fixturePath ? ["--fixtures", fixturePath] : []);
}

if (!fixturePath && (await exists("validate-orders.mjs") || await exists("validate-payments.mjs"))) {
  fixtureDir = await mkdtemp(join(tmpdir(), "aquahub-order-validation-"));
  fixturePath = join(fixtureDir, "fixtures.json");
  fixtureSetupSucceeded = await runScript("setup-order-fixtures.mjs", ["--output", fixturePath]);
  if (!fixtureSetupSucceeded) console.error("ORDER_AUTHENTICATED_VALIDATION_BLOCKED_BY_TEST_SETUP: disposable fixture setup failed");
}

let ok = fixtureSetupSucceeded || Boolean(process.env.SUPABASE_ORDER_FIXTURES) || (!fixtureDir && !(await exists("validate-orders.mjs")) && !(await exists("validate-payments.mjs")));
try {
  for (const script of scripts) ok = (await run(script)) && ok;
} finally {
  if (fixtureSetupSucceeded && fixturePath) await runScript("setup-order-fixtures.mjs", ["--cleanup", fixturePath]);
  if (fixtureDir) await rm(fixtureDir, { recursive: true, force: true });
}
if (!ok) process.exitCode = 1;
