import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import {
  classifyGuestOrderDatabaseError,
  safeGuestOrderDatabaseMessage,
} from "./guest-order-errors.ts";

Deno.test("reservation-floor rejection is a conflict with a recoverable cart message", () => {
  assertEquals(
    classifyGuestOrderDatabaseError(
      "inventory change would consume reserved stock",
    ),
    {
      status: 409,
      message:
        "This item is no longer available in the requested quantity. Refresh your cart before trying again.",
    },
  );
});

Deno.test("known catalog validation remains a client-correctable error", () => {
  assertEquals(
    classifyGuestOrderDatabaseError("product is unavailable").status,
    422,
  );
  assertEquals(
    safeGuestOrderDatabaseMessage("product is unavailable"),
    "product is unavailable",
  );
});

Deno.test("unknown database errors remain generic server failures without leaking details", () => {
  assertEquals(
    classifyGuestOrderDatabaseError("private table or row details").status,
    503,
  );
  assertEquals(
    safeGuestOrderDatabaseMessage("private table or row details"),
    "We couldn’t submit the order. Your details are still here; please review and try again.",
  );
});
