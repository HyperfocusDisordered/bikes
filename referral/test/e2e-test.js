#!/usr/bin/env node
/**
 * Karma Rent — E2E Flow Tests
 *
 * Тестирует реальные API endpoints на production (karmarent.app)
 * Покрывает: QR scan, admin API, role-based access, Telegram webhook flows
 *
 * Usage: node referral/test/e2e-test.js
 */

const https = require('https');

const BASE = 'karmarent.app';
const ADMIN_TOKEN = 'kr-admin-2026';
const TS = Date.now(); // unique suffix for test data

let passed = 0;
let failed = 0;
let skipped = 0;
const failures = [];

// ── HTTP helpers ──────────────────────────────────────

function request(method, path, { body, token, followRedirect = false } = {}) {
  return new Promise((resolve, reject) => {
    const options = {
      hostname: BASE,
      path,
      method,
      headers: { 'Content-Type': 'application/json' },
    };
    if (token) options.headers['Authorization'] = `Bearer ${token}`;

    const req = https.request(options, (res) => {
      let data = '';
      res.on('data', (chunk) => (data += chunk));
      res.on('end', () => {
        let parsed;
        try {
          parsed = JSON.parse(data);
        } catch {
          parsed = data;
        }
        resolve({ status: res.statusCode, headers: res.headers, body: parsed });
      });
    });

    req.on('error', reject);
    req.setTimeout(15000, () => { req.destroy(); reject(new Error('Timeout')); });

    if (body) req.write(JSON.stringify(body));
    req.end();
  });
}

const GET = (path, opts) => request('GET', path, opts);
const POST = (path, opts) => request('POST', path, opts);
const PATCH = (path, opts) => request('PATCH', path, opts);
const DELETE = (path, opts) => request('DELETE', path, opts);

// ── Test framework ────────────────────────────────────

function assert(condition, msg) {
  if (!condition) throw new Error(msg);
}

function assertEq(actual, expected, msg) {
  if (actual !== expected)
    throw new Error(`${msg}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
}

function assertIncludes(str, substr, msg) {
  if (!String(str).includes(substr))
    throw new Error(`${msg}: "${String(str).substring(0, 100)}" does not include "${substr}"`);
}

async function test(name, fn) {
  try {
    await fn();
    passed++;
    console.log(`  ✅ ${name}`);
  } catch (e) {
    failed++;
    failures.push({ name, error: e.message });
    console.log(`  ❌ ${name}: ${e.message}`);
  }
}

function skip(name, reason) {
  skipped++;
  console.log(`  ⏭️  ${name} (${reason})`);
}

// ── Test Data ─────────────────────────────────────────

const TEST_BIKE_NAME = `TestBike_${TS}`;
const TEST_PERSON_NAME = `TestPerson_${TS}`;
const TEST_TG_ID = `test_${TS}`;
let testBikeId = null;
let testPersonId = null;

// ══════════════════════════════════════════════════════
// TESTS
// ══════════════════════════════════════════════════════

async function runTests() {
  console.log('\n🏍 KARMA RENT — E2E TESTS\n');
  console.log(`  Target: ${BASE}`);
  console.log(`  Timestamp: ${TS}\n`);

  // ── 1. Health / connectivity ────────────────────────

  console.log('── 1. Connectivity ──');

  await test('Server responds', async () => {
    const r = await GET('/api/qr/NONEXISTENT_TEST');
    assert(r.status === 404, `Expected 404, got ${r.status}`);
  });

  // ── 2. Public QR scan flow ──────────────────────────

  console.log('\n── 2. QR Scan Flow (public) ──');

  await test('Non-existent QR → 404', async () => {
    const r = await GET('/api/qr/DOES_NOT_EXIST');
    assertEq(r.status, 404, 'Status');
    assertEq(r.body?.status, 'error', 'Body status');
  });

  await test('Existing unactivated QR → 302 to Telegram', async () => {
    // First create a test QR code
    const gen = await POST('/api/admin/qrcodes', {
      token: ADMIN_TOKEN,
      body: { prefix: `TEST${TS}`, count: 1 },
    });
    assertEq(gen.status, 201, 'QR generation status');

    const code = gen.body?.data?.codes?.[0];
    assert(code, 'QR code was generated');

    // Scan it — should redirect to Telegram (no partner yet)
    const r = await GET(`/api/qr/${code}`);
    assertEq(r.status, 302, 'Redirect status');
    assertIncludes(r.headers.location, 't.me/', 'Telegram redirect');
    assertIncludes(r.headers.location, code, 'Code in redirect URL');
  });

  // ── 3. Auth: Unauthorized / Forbidden ───────────────

  console.log('\n── 3. Authentication ──');

  await test('No token → 401', async () => {
    const r = await GET('/api/admin/bikes');
    assertEq(r.status, 401, 'Status');
  });

  await test('Bad token → 403', async () => {
    const r = await GET('/api/admin/bikes', { token: 'wrong-token-123' });
    assertEq(r.status, 403, 'Status');
  });

  await test('Admin token → 200', async () => {
    const r = await GET('/api/admin/bikes', { token: ADMIN_TOKEN });
    assertEq(r.status, 200, 'Status');
    assertEq(r.body?.status, 'success', 'Body status');
  });

  // ── 4. Admin CRUD: Bikes ────────────────────────────

  console.log('\n── 4. Admin: Bikes CRUD ──');

  await test('Create bike', async () => {
    const r = await POST('/api/admin/bikes', {
      token: ADMIN_TOKEN,
      body: {
        name: TEST_BIKE_NAME,
        plate_number: `T${TS}`,
        daily_rate: 250,
        notes: 'e2e test bike',
      },
    });
    assertEq(r.status, 201, 'Status');
  });

  await test('List bikes contains test bike', async () => {
    const r = await GET('/api/admin/bikes', { token: ADMIN_TOKEN });
    assertEq(r.status, 200, 'Status');
    const bikes = r.body?.data?.bikes || [];
    const found = bikes.find((b) => b.name === TEST_BIKE_NAME);
    assert(found, 'Test bike found in list');
    testBikeId = found.id;
  });

  await test('Update bike status to hold', async () => {
    assert(testBikeId, 'Need test bike ID');
    const r = await PATCH(`/api/admin/bikes/${testBikeId}`, {
      token: ADMIN_TOKEN,
      body: { status: 'hold' },
    });
    assertEq(r.status, 200, 'Status');
  });

  await test('Verify bike status updated', async () => {
    const r = await GET('/api/admin/bikes?status=hold', { token: ADMIN_TOKEN });
    const bikes = r.body?.data?.bikes || [];
    const found = bikes.find((b) => b.id === testBikeId);
    assert(found, 'Test bike found in hold list');
    assertEq(found.status, 'hold', 'Status is hold');
  });

  await test('Delete test bike', async () => {
    assert(testBikeId, 'Need test bike ID');
    const r = await DELETE(`/api/admin/bikes/${testBikeId}`, { token: ADMIN_TOKEN });
    assertEq(r.status, 200, 'Status');
  });

  await test('Deleted bike not in list', async () => {
    const r = await GET('/api/admin/bikes', { token: ADMIN_TOKEN });
    const bikes = r.body?.data?.bikes || [];
    const found = bikes.find((b) => b.id === testBikeId);
    assert(!found, 'Test bike should not be in list after deletion');
  });

  // ── 5. Admin CRUD: Persons ──────────────────────────

  console.log('\n── 5. Admin: Persons ──');

  await test('Create person (client)', async () => {
    const r = await POST('/api/admin/persons', {
      token: ADMIN_TOKEN,
      body: {
        name: TEST_PERSON_NAME,
        phone: '+84' + TS,
        telegram_id: TEST_TG_ID,
        role: 'client',
      },
    });
    assertEq(r.status, 201, 'Status');
  });

  await test('List persons contains test person', async () => {
    const r = await GET('/api/admin/persons?role=client', { token: ADMIN_TOKEN });
    const persons = r.body?.data?.persons || [];
    const found = persons.find((p) => p.name === TEST_PERSON_NAME);
    assert(found, 'Test person found');
    testPersonId = found.id;
  });

  await test('Create person without name → 400', async () => {
    const r = await POST('/api/admin/persons', {
      token: ADMIN_TOKEN,
      body: { phone: '123' },
    });
    assertEq(r.status, 400, 'Status');
  });

  // ── 6. Admin: QR Codes ──────────────────────────────

  console.log('\n── 6. Admin: QR Codes ──');

  await test('List QR codes', async () => {
    const r = await GET('/api/admin/qrcodes', { token: ADMIN_TOKEN });
    assertEq(r.status, 200, 'Status');
    assert(Array.isArray(r.body?.data?.qrcodes), 'QR codes is array');
  });

  await test('Generate QR codes', async () => {
    const r = await POST('/api/admin/qrcodes', {
      token: ADMIN_TOKEN,
      body: { prefix: `E2E${TS}`, count: 3 },
    });
    assertEq(r.status, 201, 'Status');
    assertEq(r.body?.data?.count, 3, 'Count');
    assert(r.body?.data?.codes?.length === 3, '3 codes returned');
  });

  // ── 7. Admin: Reports & Audit ───────────────────────

  console.log('\n── 7. Admin: Reports & Audit ──');

  await test('Monthly report', async () => {
    const r = await GET('/api/admin/report', { token: ADMIN_TOKEN });
    assertEq(r.status, 200, 'Status');
    assert(r.body?.data?.period, 'Has period');
    assert(Array.isArray(r.body?.data?.partners), 'Has partners array');
  });

  await test('Audit log', async () => {
    const r = await GET('/api/admin/audit-log?limit=5', { token: ADMIN_TOKEN });
    assertEq(r.status, 200, 'Status');
    assert(Array.isArray(r.body?.data?.log), 'Has log array');
    // Should contain our bike deletion
    const delEntry = (r.body?.data?.log || []).find(
      (e) => e.action === 'bike.delete' && e.details?.includes(TEST_BIKE_NAME)
    );
    assert(delEntry, 'Bike deletion in audit log');
  });

  // ── 8. Moderator (limited access) ──────────────────

  console.log('\n── 8. Role-based access (moderator) ──');

  // First create a moderator person to get a token
  const MOD_TG_ID = `mod_${TS}`;
  await POST('/api/admin/persons', {
    token: ADMIN_TOKEN,
    body: { name: `Mod_${TS}`, telegram_id: MOD_TG_ID, role: 'moderator' },
  });

  await test('Moderator can list bikes', async () => {
    const r = await GET('/api/admin/bikes', { token: MOD_TG_ID });
    assertEq(r.status, 200, 'Status');
  });

  await test('Moderator cannot delete bikes (403)', async () => {
    // Create a bike to attempt deletion
    await POST('/api/admin/bikes', {
      token: ADMIN_TOKEN,
      body: { name: `ModDelTest_${TS}`, plate_number: `MD${TS}` },
    });
    const list = await GET('/api/admin/bikes', { token: ADMIN_TOKEN });
    const bike = (list.body?.data?.bikes || []).find((b) => b.name === `ModDelTest_${TS}`);
    if (!bike) { skip('Moderator cannot delete bikes', 'bike not created'); return; }

    const r = await DELETE(`/api/admin/bikes/${bike.id}`, { token: MOD_TG_ID });
    assertEq(r.status, 403, 'Status');

    // Cleanup: delete with admin
    await DELETE(`/api/admin/bikes/${bike.id}`, { token: ADMIN_TOKEN });
  });

  await test('Moderator cannot list persons (403)', async () => {
    const r = await GET('/api/admin/persons', { token: MOD_TG_ID });
    assertEq(r.status, 403, 'Status');
  });

  await test('Moderator cannot list QR codes (403)', async () => {
    const r = await GET('/api/admin/qrcodes', { token: MOD_TG_ID });
    assertEq(r.status, 403, 'Status');
  });

  await test('Moderator cannot generate QR codes (403)', async () => {
    const r = await POST('/api/admin/qrcodes', {
      token: MOD_TG_ID,
      body: { prefix: 'MOD', count: 1 },
    });
    assertEq(r.status, 403, 'Status');
  });

  await test('Moderator can access report', async () => {
    const r = await GET('/api/admin/report', { token: MOD_TG_ID });
    assertEq(r.status, 200, 'Status');
  });

  await test('Moderator cannot access audit log (403)', async () => {
    const r = await GET('/api/admin/audit-log', { token: MOD_TG_ID });
    assertEq(r.status, 403, 'Status');
  });

  // ── 9. Client role (minimal access) ────────────────

  console.log('\n── 9. Client role (minimal access) ──');

  await test('Client cannot list bikes (403)', async () => {
    const r = await GET('/api/admin/bikes', { token: TEST_TG_ID });
    assertEq(r.status, 403, 'Status');
  });

  await test('Client cannot access report (403)', async () => {
    const r = await GET('/api/admin/report', { token: TEST_TG_ID });
    assertEq(r.status, 403, 'Status');
  });

  // ── 10. Partner stats ──────────────────────────────

  console.log('\n── 10. Partner stats ──');

  // Create a partner for testing
  const PARTNER_TG_ID = `partner_${TS}`;
  await POST('/api/admin/persons', {
    token: ADMIN_TOKEN,
    body: { name: `Partner_${TS}`, telegram_id: PARTNER_TG_ID, role: 'partner' },
  });
  const partnerList = await GET('/api/admin/persons?role=partner', { token: ADMIN_TOKEN });
  const testPartner = (partnerList.body?.data?.persons || []).find(
    (p) => p.telegram_id === PARTNER_TG_ID
  );

  if (testPartner) {
    await test('Partner can view own stats', async () => {
      const r = await GET(`/api/partners/${testPartner.id}/stats`, { token: PARTNER_TG_ID });
      assertEq(r.status, 200, 'Status');
      assert(r.body?.data?.revenue !== undefined, 'Has revenue');
    });

    await test('Partner cannot view other partner stats (403)', async () => {
      const r = await GET('/api/partners/99999/stats', { token: PARTNER_TG_ID });
      assertEq(r.status, 403, 'Status');
    });

    await test('Admin can view any partner stats', async () => {
      const r = await GET(`/api/partners/${testPartner.id}/stats`, { token: ADMIN_TOKEN });
      assertEq(r.status, 200, 'Status');
    });
  } else {
    skip('Partner stats tests', 'partner not created');
  }

  // ── 11. Telegram Webhook (bot flow) ─────────────────

  console.log('\n── 11. Telegram Bot Webhook ──');

  const FAKE_CHAT_ID = 999000000 + (TS % 1000000);
  const FAKE_USER_ID = FAKE_CHAT_ID;

  function makeMessage(text, chatId = FAKE_CHAT_ID, userId = FAKE_USER_ID) {
    return {
      update_id: TS,
      message: {
        message_id: TS,
        from: { id: userId, is_bot: false, first_name: 'E2ETest', username: `e2e_${TS}` },
        chat: { id: chatId, type: 'private' },
        date: Math.floor(Date.now() / 1000),
        text,
      },
    };
  }

  function makeCallback(data, chatId = FAKE_CHAT_ID, userId = FAKE_USER_ID) {
    return {
      update_id: TS,
      callback_query: {
        id: String(TS),
        from: { id: userId, is_bot: false, first_name: 'E2ETest', username: `e2e_${TS}` },
        message: {
          message_id: TS,
          chat: { id: chatId, type: 'private' },
          date: Math.floor(Date.now() / 1000),
        },
        data,
      },
    };
  }

  await test('Webhook accepts /start', async () => {
    const r = await POST('/api/telegram/webhook', { body: makeMessage('/start') });
    assertEq(r.status, 200, 'Status');
  });

  await test('Webhook accepts /menu', async () => {
    const r = await POST('/api/telegram/webhook', { body: makeMessage('/menu') });
    assertEq(r.status, 200, 'Status');
  });

  await test('Webhook accepts /bikes', async () => {
    const r = await POST('/api/telegram/webhook', { body: makeMessage('/bikes') });
    assertEq(r.status, 200, 'Status');
  });

  await test('Webhook accepts /stats', async () => {
    const r = await POST('/api/telegram/webhook', { body: makeMessage('/stats') });
    assertEq(r.status, 200, 'Status');
  });

  await test('Webhook accepts callback query', async () => {
    const r = await POST('/api/telegram/webhook', { body: makeCallback('menu') });
    assertEq(r.status, 200, 'Status');
  });

  await test('Webhook handles /start with QR deep link', async () => {
    const r = await POST('/api/telegram/webhook', {
      body: makeMessage('/start KR_001'),
    });
    assertEq(r.status, 200, 'Status');
  });

  await test('Webhook handles unknown text gracefully', async () => {
    const r = await POST('/api/telegram/webhook', {
      body: makeMessage('random gibberish xyz 123'),
    });
    assertEq(r.status, 200, 'Status');
  });

  await test('Webhook handles empty body', async () => {
    const r = await POST('/api/telegram/webhook', { body: {} });
    assertEq(r.status, 200, 'Status');
  });

  // ── 12. Admin HTML page ─────────────────────────────

  console.log('\n── 12. Admin HTML Page ──');

  await test('GET /admin returns HTML', async () => {
    const r = await GET('/admin');
    assertEq(r.status, 200, 'Status');
    assert(typeof r.body === 'string' || r.headers['content-type']?.includes('text/html'),
      'Response is HTML');
  });

  // ── 13. Rental flow (API-level) ─────────────────────

  console.log('\n── 13. Rental Flow (API) ──');

  await test('Create rental record', async () => {
    if (!testPersonId) { skip('Create rental', 'no test person'); return; }
    const r = await POST('/api/rentals', {
      token: ADMIN_TOKEN,
      body: {
        client_id: testPersonId,
        amount: 500,
        date: '2026-02-08',
        notes: 'e2e test rental',
      },
    });
    assertEq(r.status, 200, 'Status');
  });

  await test('Rental requires client_id', async () => {
    const r = await POST('/api/rentals', {
      token: ADMIN_TOKEN,
      body: { amount: 100 },
    });
    assertEq(r.status, 400, 'Status');
  });

  await test('Rental requires auth', async () => {
    const r = await POST('/api/rentals', { body: { client_id: 1, amount: 100 } });
    assertEq(r.status, 401, 'Status');
  });

  // ── 14. Booking flow (full cycle) ──────────────────

  console.log('\n── 14. Booking Flow (full cycle) ──');

  // Create a fresh bike for booking
  let bookingBikeId = null;
  let bookingId = null;

  await test('Setup: create bike for booking', async () => {
    const r = await POST('/api/admin/bikes', {
      token: ADMIN_TOKEN,
      body: {
        name: `BookTest_${TS}`,
        plate_number: `BK${TS}`,
        daily_rate: 300,
      },
    });
    assertEq(r.status, 201, 'Status');
    const list = await GET('/api/admin/bikes', { token: ADMIN_TOKEN });
    const bike = (list.body?.data?.bikes || []).find((b) => b.name === `BookTest_${TS}`);
    assert(bike, 'Booking test bike found');
    bookingBikeId = bike.id;
  });

  // Simulate client booking via webhook — the real flow is through Telegram callbacks
  // but we can test the webhook doesn't crash with booking-related callbacks
  if (bookingBikeId && testPersonId) {
    await test('Webhook: client browse bikes (clist callback)', async () => {
      const r = await POST('/api/telegram/webhook', { body: makeCallback('clist:0') });
      assertEq(r.status, 200, 'Status');
    });
  }

  // ── Summary ─────────────────────────────────────────

  console.log('\n' + '═'.repeat(50));
  console.log(`\n  Results: ${passed} passed, ${failed} failed, ${skipped} skipped`);
  if (failures.length > 0) {
    console.log('\n  Failures:');
    failures.forEach((f) => console.log(`    ❌ ${f.name}: ${f.error}`));
  }
  console.log();

  // ── Cleanup ─────────────────────────────────────────

  console.log('── Cleanup ──');
  // Delete test bike if still exists
  if (bookingBikeId) {
    await DELETE(`/api/admin/bikes/${bookingBikeId}`, { token: ADMIN_TOKEN });
    console.log('  🧹 Deleted booking test bike');
  }
  // Note: test persons & QR codes remain (harmless, useful for debugging)
  console.log('  🧹 Done (test persons/QR codes preserved for inspection)\n');

  process.exit(failed > 0 ? 1 : 0);
}

runTests().catch((e) => {
  console.error('Fatal error:', e);
  process.exit(1);
});
