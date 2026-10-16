import {
  collectReachPreflight,
  inspectReachContact,
} from "./reach-preflight.ts";
import type {
  ReachProfile,
  ReachSegment,
  ReachTag,
} from "./hostinger-reach.ts";

function assertEquals(actual: unknown, expected: unknown): void {
  if (actual !== expected) {
    throw new Error(
      "expected " + String(expected) + ", received " + String(actual),
    );
  }
}

Deno.test("Reach preflight verifies profile, required tags, and segment rules", async () => {
  const profileUuid = "12345678-1234-1234-1234-123456789abc";
  const profiles: ReachProfile[] = [{
    uuid: profileUuid,
    domain: "aquahub.aesprt.com",
    status: "active",
  }];
  const tags: ReachTag[] = [{
    uuid: "newsletter-tag-id",
    value: "aquahub-newsletter",
  }];
  const segment: ReachSegment = {
    uuid: "segment-id",
    name: "AquaHub Newsletter Subscribers",
    query: {
      logic: "and",
      conditions: [
        {
          attribute: "tag",
          operator: "is",
          value: "aquahub-newsletter",
        },
        {
          attribute: "subscription_status",
          operator: "is",
          value: "subscribed",
        },
      ],
    },
  };
  const report = await collectReachPreflight(
    {
      listProfiles: async () => profiles,
      listTags: async () => tags,
      listSegments: async () => [{
        uuid: segment.uuid,
        name: segment.name,
      }],
      getSegmentDetails: async () => segment,
    },
    profileUuid,
    true,
  );

  assertEquals(report.providerAuthenticated, true);
  assertEquals(report.configuredProfileFound, true);
  assertEquals(report.configuredDoubleOptIn, true);
  assertEquals(report.hostingerDoubleOptIn, "not_exposed_by_profile_list_api");
  assertEquals(report.tags.present.includes("aquahub-newsletter"), true);
  assertEquals(report.tags.missing.includes("aquahub-owner"), true);
  assertEquals(report.newsletterSegments.length, 1);
  assertEquals(report.newsletterSegments[0]?.hasNewsletterTagCondition, true);
  assertEquals(
    report.newsletterSegments[0]?.hasSubscribedOrConfirmedCondition,
    true,
  );
});

Deno.test("Reach preflight redacts email values from segment conditions", async () => {
  const report = await collectReachPreflight(
    {
      listProfiles: async () => [{
        uuid: "12345678-1234-1234-1234-123456789abc",
        domain: "aquahub.aesprt.com",
        status: "active",
      }],
      listTags: async () => [],
      listSegments: async () => [{
        uuid: "segment-id",
        name: "AquaHub Newsletter Subscribers",
      }],
      getSegmentDetails: async () => ({
        uuid: "segment-id",
        name: "AquaHub Newsletter Subscribers",
        query: {
          logic: "and",
          conditions: [{
            attribute: "email",
            operator: "equals",
            value: "test@example.com",
          }],
        },
      }),
    },
    "12345678-1234-1234-1234-123456789abc",
    true,
  );

  assertEquals(
    report.newsletterSegments[0]?.conditions[0]?.value,
    "[redacted]",
  );
});

Deno.test("Reach preflight does not inspect a profile that does not match", async () => {
  let profileScopedReads = 0;
  const report = await collectReachPreflight(
    {
      listProfiles: async () => [],
      listTags: async () => {
        profileScopedReads += 1;
        return [];
      },
      listSegments: async () => {
        profileScopedReads += 1;
        return [];
      },
      getSegmentDetails: async () => null,
    },
    "12345678-1234-1234-1234-123456789abc",
    true,
  );

  assertEquals(report.providerAuthenticated, true);
  assertEquals(report.configuredProfileFound, false);
  assertEquals(profileScopedReads, 0);
});

Deno.test("test-contact inspection reports status and segment membership without returning email", async () => {
  const email = "authorized-test@example.com";
  const report = await inspectReachContact({
    findContact: async () => ({
      uuid: "contact-id",
      email,
      subscription_status: "subscribed",
    }),
    getContactDetails: async () => ({
      uuid: "contact-id",
      email,
      subscription_status: "subscribed",
      tags: [{ uuid: "newsletter-id", value: "aquahub-newsletter" }],
    }),
    listSegments: async () => [{
      uuid: "segment-id",
      name: "AquaHub Newsletter Subscribers",
    }],
    getSegmentDetails: async () => null,
    listSegmentContacts: async () => [{
      uuid: "contact-id",
      email,
      subscription_status: "subscribed",
    }],
  }, email);

  assertEquals(report.contactFound, true);
  assertEquals(report.subscriptionStatus, "subscribed");
  assertEquals(report.newsletterTagAssigned, true);
  assertEquals(report.newsletterSegments[0]?.membership, true);
  assertEquals(JSON.stringify(report).includes(email), false);
});
