const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_user_123";
const BOB_UID = "bob_user_456";
const ADMIN_UID_1 = "xuonHbFXXrPQEJVZPjyJy9itC7x1";
const ADMIN_UID_2 = "jACOpeQtRie82vw5S20AUWfZxD53";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validUserSummary(uid, ts) {
  return {
    userId: uid,
    displayName: "TopProxy User",
    email: `${uid}@example.com`,
    proxyCount: 3,
    workingProxyCount: 2,
    firewallEnabled: true,
    privateRoutingEnabled: false,
    themeMode: "SYSTEM_TIME",
    languageTag: "vi",
    lastDeviceModel: "Pixel 8",
    appVersion: "3.6",
    syncCount: 1,
    createdAt: ts,
    updatedAt: ts,
    lastSyncAt: ts,
  };
}

function validProxyPayload(ts) {
  return {
    name: "SG Fast Node",
    protocol: "SOCKS5",
    host: "103.10.10.5",
    port: 1080,
    authMethod: "USER_PASS",
    username: "user1",
    password: "secret",
    dnsMode: "CLOUDFLARE",
    customDns: "",
    enabled: true,
    latencyMs: 48,
    exitIp: "103.10.10.5",
    country: "SG",
    sortOrder: 0,
    createdAt: ts,
    updatedAt: ts,
  };
}

function validPrefsPayload(ts) {
  return {
    themeMode: "DARK",
    languageTag: "vi",
    autoStopOnDisconnect: true,
    vpnTimerMinutes: 60,
    firewallEnabled: false,
    privateRoutingEnabled: true,
    privateRoutingRanges: "192.168.1.0/24",
    createdAt: ts,
    updatedAt: ts,
  };
}

function validSyncEventPayload(uid, ts) {
  return {
    userId: uid,
    userLabel: "TopProxy User",
    deviceModel: "Pixel 8",
    uploadedProxies: 2,
    downloadedProxies: 1,
    totalProxies: 3,
    appVersion: "3.6",
    createdAt: ts,
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated users cannot read or write any collection", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const ts = new Date(Date.now() - 2000);

  await assertFails(unauthDb.collection("users").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).set(validUserSummary(ALICE_UID, ts)));
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("proxies").get());
  await assertFails(unauthDb.collection("sync_events").get());
  await assertFails(unauthDb.collection("sync_events").doc("evt_1").set(validSyncEventPayload(ALICE_UID, ts)));
});

test("Authenticated user can create/update own sync profile, proxies, preferences, and sync events", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 10000);
  const updated = new Date(Date.now() - 1000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set(validUserSummary(ALICE_UID, created))
  );
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      proxyCount: 5,
      syncCount: 2,
      updatedAt: updated,
      lastSyncAt: updated,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("proxies").doc("proxy_1").set(validProxyPayload(created))
  );
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("proxies").doc("proxy_1").update({
      latencyMs: 32,
      updatedAt: updated,
    })
  );
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("proxies").get()
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("sync_state").doc("preferences").set(validPrefsPayload(created))
  );
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("sync_state").doc("preferences").update({
      themeMode: "LIGHT",
      updatedAt: updated,
    })
  );

  await assertSucceeds(
    aliceDb.collection("sync_events").doc("evt_alice_1").set(validSyncEventPayload(ALICE_UID, updated))
  );
  await assertSucceeds(
    aliceDb.collection("sync_events").where("userId", "==", ALICE_UID).get()
  );
});

test("Regular user cannot list all users, read another user's data, or list all sync_events", async () => {
  const created = new Date(Date.now() - 5000);
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("users").doc(BOB_UID).set(validUserSummary(BOB_UID, created));
    await db.collection("users").doc(BOB_UID).collection("proxies").doc("p1").set(validProxyPayload(created));
    await db.collection("sync_events").doc("evt_bob_1").set(validSyncEventPayload(BOB_UID, created));
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(aliceDb.collection("users").get());
  await assertFails(aliceDb.collection("users").doc(BOB_UID).get());
  await assertFails(aliceDb.collection("users").doc(BOB_UID).collection("proxies").get());
  await assertFails(aliceDb.collection("sync_events").get());
  await assertFails(aliceDb.collection("sync_events").doc("evt_bob_1").get());
});

test("Validation rejects shadow fields, tampered userId, immutable createdAt changes, and invalid port/future timestamps", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 10000);
  const future = new Date(Date.now() + 60000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set(validUserSummary(ALICE_UID, created))
  );

  // Shadow field
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      isAdmin: true,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Immutable createdAt mutation
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      createdAt: new Date(Date.now() - 1000),
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Future timestamp
  await assertFails(
    aliceDb.collection("sync_events").doc("evt_future").set(validSyncEventPayload(ALICE_UID, future))
  );

  // Invalid proxy port
  const badProxy = validProxyPayload(created);
  badProxy.port = 70000;
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("proxies").doc("bad_port").set(badProxy)
  );

  // Sync event is immutable
  await assertSucceeds(
    aliceDb.collection("sync_events").doc("evt_1").set(validSyncEventPayload(ALICE_UID, created))
  );
  await assertFails(
    aliceDb.collection("sync_events").doc("evt_1").update({ uploadedProxies: 99 })
  );
  await assertFails(
    aliceDb.collection("sync_events").doc("evt_1").delete()
  );
});

test("Both designated Admin UIDs can list users and sync_events for Firebase statistics", async () => {
  const created = new Date(Date.now() - 5000);
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("users").doc(ALICE_UID).set(validUserSummary(ALICE_UID, created));
    await db.collection("users").doc(BOB_UID).set(validUserSummary(BOB_UID, created));
    await db.collection("sync_events").doc("evt_1").set(validSyncEventPayload(ALICE_UID, created));
    await db.collection("sync_events").doc("evt_2").set(validSyncEventPayload(BOB_UID, created));
  });

  for (const adminUid of [ADMIN_UID_1, ADMIN_UID_2]) {
    const adminDb = testEnv.authenticatedContext(adminUid).firestore();
    await assertSucceeds(adminDb.collection("users").get());
    await assertSucceeds(adminDb.collection("users").doc(ALICE_UID).get());
    await assertSucceeds(
      adminDb.collection("sync_events").orderBy("createdAt", "desc").limit(30).get()
    );
  }
});

test("Verified owner admin email can access stats with a project-scoped UID, unverified email is rejected", async () => {
  const created = new Date(Date.now() - 5000);
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("users").doc(ALICE_UID).set(validUserSummary(ALICE_UID, created));
    await db.collection("sync_events").doc("evt_1").set(validSyncEventPayload(ALICE_UID, created));
  });

  const verifiedOwnerDb = testEnv.authenticatedContext("new_project_uid_999", {
    email: "resetall1st@gmail.com",
    email_verified: true,
  }).firestore();
  await assertSucceeds(verifiedOwnerDb.collection("users").get());
  await assertSucceeds(verifiedOwnerDb.collection("sync_events").orderBy("createdAt", "desc").limit(30).get());

  const spoofedOwnerDb = testEnv.authenticatedContext("spoof_uid_999", {
    email: "resetall1st@gmail.com",
    email_verified: false,
  }).firestore();
  await assertFails(spoofedOwnerDb.collection("users").get());
  await assertFails(spoofedOwnerDb.collection("sync_events").get());
});
