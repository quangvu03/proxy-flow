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
const RBAC_ADMIN_UID = "rbac_admin_user_001";
const INACTIVE_ADMIN_UID = "rbac_inactive_admin_002";

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

test("Unauthenticated users cannot read or write any collection including roles and app_config", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const ts = new Date(Date.now() - 2000);

  await assertFails(unauthDb.collection("users").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).set(validUserSummary(ALICE_UID, ts)));
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("proxies").get());
  await assertFails(unauthDb.collection("sync_events").get());
  await assertFails(unauthDb.collection("sync_events").doc("evt_1").set(validSyncEventPayload(ALICE_UID, ts)));
  await assertFails(unauthDb.collection("roles").get());
  await assertFails(unauthDb.collection("roles").doc(ALICE_UID).get());
  await assertFails(
    unauthDb.collection("roles").doc(ALICE_UID).set({ role: "admin", active: true })
  );
  await assertFails(unauthDb.collection("app_config").doc("ads").get());
  await assertFails(unauthDb.collection("app_config").doc("ads").set({ ads_enabled: false }));
});

test("Authenticated user can sync own data and read app_config/ads, but cannot read/write roles or write app_config", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("app_config").doc("ads").set({ ads_enabled: true });
    await db.collection("roles").doc(ALICE_UID).set({ role: "user", active: true });
    await db.collection("roles").doc(BOB_UID).set({ role: "admin", active: true });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 10000);
  const updated = new Date(Date.now() - 1000);

  // Authenticated user can read /app_config/ads
  await assertSucceeds(aliceDb.collection("app_config").doc("ads").get());

  // Authenticated regular user CANNOT write /app_config/ads or read/write /roles
  await assertFails(aliceDb.collection("app_config").doc("ads").set({ ads_enabled: false }));
  await assertFails(aliceDb.collection("app_config").doc("ads").update({ ads_enabled: false }));
  await assertFails(aliceDb.collection("roles").doc(ALICE_UID).get());
  await assertFails(aliceDb.collection("roles").doc(BOB_UID).get());
  await assertFails(aliceDb.collection("roles").get());
  await assertFails(aliceDb.collection("roles").doc(ALICE_UID).set({ role: "admin", active: true }));
  await assertFails(aliceDb.collection("roles").doc(ALICE_UID).update({ role: "admin", active: true }));
  await assertFails(aliceDb.collection("roles").doc(ALICE_UID).delete());

  // Own sync collections work normally
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
    aliceDb.collection("users").doc(ALICE_UID).collection("sync_state").doc("preferences").set(validPrefsPayload(created))
  );
  await assertSucceeds(
    aliceDb.collection("sync_events").doc("evt_alice_1").set(validSyncEventPayload(ALICE_UID, updated))
  );
});

test("Inactive admin (active: false) is denied roles read, dashboard collections, and app_config/ads writes", async () => {
  const created = new Date(Date.now() - 5000);
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("roles").doc(INACTIVE_ADMIN_UID).set({
      role: "admin",
      active: false,
    });
    await db.collection("users").doc(ALICE_UID).set(validUserSummary(ALICE_UID, created));
    await db.collection("app_config").doc("ads").set({ ads_enabled: true });
  });

  const inactiveAdminDb = testEnv.authenticatedContext(INACTIVE_ADMIN_UID).firestore();
  await assertFails(inactiveAdminDb.collection("roles").doc(INACTIVE_ADMIN_UID).get());
  await assertFails(inactiveAdminDb.collection("roles").get());
  await assertFails(inactiveAdminDb.collection("users").get());
  await assertFails(inactiveAdminDb.collection("sync_events").get());
  await assertFails(inactiveAdminDb.collection("app_config").doc("ads").set({ ads_enabled: false }));
  await assertFails(inactiveAdminDb.collection("app_config").doc("ads").update({ ads_enabled: false }));
});

test("Active admin (role: 'admin', active: true) can toggle app_config/ads and read roles/stats, while roles write remains denied", async () => {
  const created = new Date(Date.now() - 10000);
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("users").doc(ALICE_UID).set(validUserSummary(ALICE_UID, created));
    await db.collection("sync_events").doc("evt_1").set(validSyncEventPayload(ALICE_UID, created));
    await db.collection("roles").doc(RBAC_ADMIN_UID).set({
      role: "admin",
      active: true,
    });
  });

  const adminDb = testEnv.authenticatedContext(RBAC_ADMIN_UID).firestore();
  await assertSucceeds(adminDb.collection("roles").doc(RBAC_ADMIN_UID).get());
  await assertSucceeds(adminDb.collection("roles").get());
  await assertSucceeds(adminDb.collection("users").get());
  await assertSucceeds(
    adminDb.collection("sync_events").orderBy("createdAt", "desc").limit(30).get()
  );

  // Admin cannot modify /roles/{uid} from client (managed strictly on Firebase Console / Admin SDK)
  await assertFails(adminDb.collection("roles").doc(ALICE_UID).set({ role: "admin", active: true }));
  await assertFails(adminDb.collection("roles").doc(RBAC_ADMIN_UID).delete());

  // Admin can create/update /app_config/ads with valid schema
  const updated = new Date(Date.now() - 1000);
  await assertSucceeds(
    adminDb.collection("app_config").doc("ads").set({
      ads_enabled: false,
      updatedAt: updated,
      updatedBy: RBAC_ADMIN_UID,
    })
  );
  await assertSucceeds(
    adminDb.collection("app_config").doc("ads").update({
      ads_enabled: true,
      updatedAt: new Date(Date.now() - 500),
      updatedBy: RBAC_ADMIN_UID,
    })
  );

  // Admin cannot delete /app_config/ads, write invalid types, spoof updatedBy, or write other configId
  await assertFails(adminDb.collection("app_config").doc("ads").delete());
  await assertFails(adminDb.collection("app_config").doc("ads").set({ ads_enabled: "false" }));
  await assertFails(
    adminDb.collection("app_config").doc("ads").set({
      ads_enabled: false,
      updatedBy: ALICE_UID,
    })
  );
  await assertFails(
    adminDb.collection("app_config").doc("other_config").set({ ads_enabled: false })
  );

  // Deactivating admin role immediately revokes ads write and dashboard read
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("roles").doc(RBAC_ADMIN_UID).update({ active: false });
  });
  await assertFails(adminDb.collection("app_config").doc("ads").update({ ads_enabled: false }));
  await assertFails(adminDb.collection("roles").doc(RBAC_ADMIN_UID).get());
  await assertFails(adminDb.collection("users").get());
});
