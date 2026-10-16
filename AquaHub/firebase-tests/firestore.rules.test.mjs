import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { initializeTestEnvironment, assertSucceeds, assertFails } from '@firebase/rules-unit-testing';

let env;
before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'aquahub-rules-test',
    firestore: { rules: fs.readFileSync('../firestore.rules', 'utf8') }
  });
  await env.withSecurityRulesDisabled(async context => {
    const db = context.firestore();
    await db.doc('businesses/business-a').set({ ownerUid: 'owner-a', isActive: true });
    await db.doc('businesses/business-a/members/owner-a').set({ uid: 'owner-a', role: 'OWNER', isActive: true, stationIds: [] });
    await db.doc('businesses/business-a/members/staff-a').set({ uid: 'staff-a', role: 'STAFF', isActive: true, stationIds: ['station-a'] });
    await db.doc('businesses/business-a/stations/station-a').set({ id: 'station-a', name: 'A' });
    await db.doc('businesses/business-a/stations/station-a2').set({ id: 'station-a2', name: 'A2' });
    await db.doc('businesses/business-b').set({ ownerUid: 'owner-b', isActive: true });
    await db.doc('businesses/business-b/members/owner-b').set({ uid: 'owner-b', role: 'OWNER', isActive: true, stationIds: [] });
    await db.doc('businesses/business-b/stations/station-b').set({ id: 'station-b', name: 'B' });
  });
});
after(async () => env?.cleanup());

test('owner can read own station', async () => {
  const db = env.authenticatedContext('owner-a').firestore();
  await assertSucceeds(db.doc('businesses/business-a/stations/station-a').get());
});

test('owner cannot read another business', async () => {
  const db = env.authenticatedContext('owner-a').firestore();
  await assertFails(db.doc('businesses/business-b/stations/station-b').get());
});

test('unauthenticated client cannot read a private station', async () => {
  const db = env.unauthenticatedContext().firestore();
  await assertFails(db.doc('businesses/business-a/stations/station-a').get());
});

test('staff can access an assigned station but not an unassigned station', async () => {
  const db = env.authenticatedContext('staff-a').firestore();
  await assertSucceeds(db.doc('businesses/business-a/stations/station-a').get());
  await assertFails(db.doc('businesses/business-a/stations/station-a2').get());
});

test('client cannot write subscription authority state', async () => {
  const db = env.authenticatedContext('owner-a').firestore();
  await assertFails(db.doc('businesses/business-a/subscription/current').set({ status: 'ACTIVE' }));
});
