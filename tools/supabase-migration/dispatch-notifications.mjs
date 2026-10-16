#!/usr/bin/env node

/**
 * One-shot server-side notification outbox worker.
 *
 * This script intentionally has no dry-run mode: calling it claims and may
 * deliver queued notifications. Run it from a trusted scheduler/worker only.
 * The dispatcher lease and sender idempotency make overlapping invocations
 * safe; a timed-out invocation can be retried after the server lease expires.
 */

const SUPABASE_URL = process.env.SUPABASE_URL?.replace(/\/$/, "");
const DISPATCH_SECRET = process.env.NOTIFICATION_DISPATCH_SECRET;
const FUNCTION_URL = process.env.NOTIFICATION_DISPATCH_FUNCTION_URL
  ?? (SUPABASE_URL ? `${SUPABASE_URL}/functions/v1/dispatch-notification-outbox` : undefined);
const timeoutMs = Number(process.env.NOTIFICATION_DISPATCH_TIMEOUT_MS ?? 30_000);
const batchSize = Number(process.env.NOTIFICATION_DISPATCH_BATCH_SIZE ?? 20);

function fail(message) {
  console.error(`notification dispatch not run: ${message}`);
  process.exitCode = 2;
}

if (!SUPABASE_URL) fail("SUPABASE_URL is missing");
else if (!DISPATCH_SECRET) fail("NOTIFICATION_DISPATCH_SECRET is missing");
else if (!FUNCTION_URL) fail("NOTIFICATION_DISPATCH_FUNCTION_URL could not be resolved");
else if (!Number.isInteger(timeoutMs) || timeoutMs < 1_000 || timeoutMs > 120_000) fail("NOTIFICATION_DISPATCH_TIMEOUT_MS is invalid");
else if (!Number.isInteger(batchSize) || batchSize < 1 || batchSize > 100) fail("NOTIFICATION_DISPATCH_BATCH_SIZE is invalid");

if (process.exitCode) process.exit();

const controller = new AbortController();
const timeout = setTimeout(() => controller.abort(), timeoutMs);

try {
  const response = await fetch(FUNCTION_URL, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-notification-dispatch-secret": DISPATCH_SECRET,
    },
    body: JSON.stringify({ limit: batchSize }),
    signal: controller.signal,
  });
  const text = await response.text();
  let body;
  try {
    body = JSON.parse(text);
  } catch {
    body = null;
  }

  if (!response.ok || !body || typeof body !== "object") {
    console.error(`notification dispatch failed: HTTP ${response.status}`);
    process.exitCode = 1;
  } else {
    const summary = {
      run_id: typeof body.run_id === "string" ? body.run_id : null,
      duration_ms: Number.isFinite(body.duration_ms) ? body.duration_ms : null,
      claimed: Number.isInteger(body.claimed) ? body.claimed : null,
      sent: Number.isInteger(body.sent) ? body.sent : null,
      retried: Number.isInteger(body.retried) ? body.retried : null,
      terminal: Number.isInteger(body.terminal) ? body.terminal : null,
      skipped: Number.isInteger(body.skipped) ? body.skipped : null,
    };
    console.log(JSON.stringify(summary));
    if (typeof body.run_id !== "string" || !Number.isInteger(body.claimed)) process.exitCode = 1;
  }
} catch (error) {
  console.error(`notification dispatch failed: ${error?.name === "AbortError" ? "timeout" : "request error"}`);
  process.exitCode = 1;
} finally {
  clearTimeout(timeout);
}
