#!/usr/bin/env node
/**
 * Concurrency proof script — Event Check-In System (V1)
 *
 * Fires concurrent registration and check-in requests across TWO backend
 * processes (ports 3001 and 3002) pointed at the SAME database, proving the
 * DB-level guarantees hold under multi-process load.
 *
 * Prerequisites:
 *   Terminal 1: PORT=3001 npm run dev   (from backend/)
 *   Terminal 2: PORT=3002 npm run dev   (from backend/)
 *   Terminal 3: node test-results/concurrency-test.js
 *
 * Output is written to test-results/concurrency-proof.log AND printed.
 */

const fs = require('fs');
const path = require('path');

const BASE_URLS = ['http://localhost:3001', 'http://localhost:3002'];
const CAPACITY = 10;
const CONCURRENT = 120;
const LOG_PATH = path.join(__dirname, 'concurrency-proof.log');

const logLines = [];
function log(line = '') {
  console.log(line);
  logLines.push(line);
}

function pickServer(i) {
  return BASE_URLS[i % BASE_URLS.length];
}

async function api(base, pathname, { method = 'GET', token, body } = {}) {
  const response = await fetch(`${base}/api/v1${pathname}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const data = await response.json().catch(() => ({}));
  return { status: response.status, data };
}

async function registerUser(base, email, fullName, role) {
  const { status, data } = await api(base, '/auth/register', {
    method: 'POST',
    body: { email, password: 'password1', full_name: fullName, role },
  });
  if (status !== 201) throw new Error(`Failed to register ${email}: ${status} ${JSON.stringify(data)}`);
  return data;
}

async function main() {
  const startedAt = new Date().toISOString();
  log(`Concurrency proof run started at ${startedAt}`);
  log(`Servers: ${BASE_URLS.join(', ')}`);
  log(`Capacity: ${CAPACITY}, concurrent requests: ${CONCURRENT}`);
  log('');

  // ---- Setup: organizer + event ----
  log('--- Setup ---');
  const org = await registerUser(BASE_URLS[0], `proof-org-${Date.now()}@test.com`, 'Proof Organizer', 'organizer');
  const eventRes = await api(BASE_URLS[0], '/events', {
    method: 'POST',
    token: org.access_token,
    body: { name: 'Concurrency Proof Event', event_date: '2027-12-01T18:00:00Z', capacity: CAPACITY },
  });
  if (eventRes.status !== 201) throw new Error(`Failed to create event: ${eventRes.status} ${JSON.stringify(eventRes.data)}`);
  const eventId = eventRes.data.event.id;
  log(`Created event ${eventId} with capacity ${CAPACITY}`);

  // Register CONCURRENT attendees (each gets a unique token)
  const attendeeTokens = [];
  for (let i = 0; i < CONCURRENT; i++) {
    const user = await registerUser(pickServer(i), `proof-att-${Date.now()}-${i}@test.com`, `Attendee ${i}`, 'attendee');
    attendeeTokens.push(user.access_token);
  }
  log(`Registered ${CONCURRENT} attendees`);

  // ---- Test 1: Concurrent registrations ----
  log('');
  log(`--- Test 1: ${CONCURRENT} concurrent registrations, capacity = ${CAPACITY} ---`);
  const regResults = await Promise.allSettled(
    attendeeTokens.map((token, i) =>
      api(pickServer(i), `/events/${eventId}/register`, { method: 'POST', token, body: {} }).then((r) => r.status),
    ),
  );
  const regSuccesses = regResults.filter((r) => r.status === 'fulfilled' && r.value === 201).length;
  const regFull = regResults.filter((r) => r.status === 'fulfilled' && r.value === 409).length;
  const regOther = regResults.filter((r) => r.status === 'fulfilled' && r.value !== 201 && r.value !== 409).length;
  const regRejected = regResults.filter((r) => r.status === 'rejected').length;
  log(`  Successes (201):        ${regSuccesses} (expected: ${CAPACITY})`);
  log(`  Capacity-full (409):    ${regFull} (expected: ${CONCURRENT - CAPACITY})`);
  log(`  Other statuses:         ${regOther}`);
  log(`  Network rejections:     ${regRejected}`);
  const regPass = regSuccesses === CAPACITY && regFull === CONCURRENT - CAPACITY && regOther === 0 && regRejected === 0;
  log(`  ${regPass ? 'PASS ✓' : 'FAIL ✗'}`);

  // ---- Test 2: Concurrent check-ins with the same token ----
  log('');
  log(`--- Test 2: ${CONCURRENT} concurrent check-ins with the same token ---`);

  // Find the first attendee who successfully registered, get their registration id + QR token
  const firstSuccessIdx = regResults.findIndex((r) => r.status === 'fulfilled' && r.value === 201);
  if (firstSuccessIdx === -1) throw new Error('No successful registration to test check-in against.');

  // Get the attendee's registrations to find the registration id
  const regList = await api(BASE_URLS[0], '/registrations', { token: attendeeTokens[firstSuccessIdx] });
  const registration = regList.data.registrations.find((r) => r.event_id === eventId);
  if (!registration) throw new Error('Could not find registration for the first successful attendee.');
  const registrationId = registration.id;

  // Mint a fresh QR token
  const qrRes = await api(BASE_URLS[0], `/registrations/${registrationId}/qr-token`, { token: attendeeTokens[firstSuccessIdx] });
  if (qrRes.status !== 200) throw new Error(`Failed to mint QR token: ${qrRes.status} ${JSON.stringify(qrRes.data)}`);
  const qrToken = qrRes.data.token;
  log(`Minted QR token for registration ${registrationId}`);

  const ciResults = await Promise.allSettled(
    Array.from({ length: CONCURRENT }, (_, i) =>
      api(pickServer(i), '/checkins', {
        method: 'POST',
        token: org.access_token,
        body: { token: qrToken, station_id: `proof-scanner-${(i % 2) + 1}` },
      }).then((r) => r.status),
    ),
  );
  const ciSuccesses = ciResults.filter((r) => r.status === 'fulfilled' && r.value === 201).length;
  const ciDupes = ciResults.filter((r) => r.status === 'fulfilled' && r.value === 409).length;
  const ciOther = ciResults.filter((r) => r.status === 'fulfilled' && r.value !== 201 && r.value !== 409).length;
  const ciRejected = ciResults.filter((r) => r.status === 'rejected').length;
  log(`  Successes (201):        ${ciSuccesses} (expected: 1)`);
  log(`  Already checked-in (409): ${ciDupes} (expected: ${CONCURRENT - 1})`);
  log(`  Other statuses:         ${ciOther}`);
  log(`  Network rejections:     ${ciRejected}`);
  const ciPass = ciSuccesses === 1 && ciDupes === CONCURRENT - 1 && ciOther === 0 && ciRejected === 0;
  log(`  ${ciPass ? 'PASS ✓' : 'FAIL ✗'}`);

  // ---- Summary ----
  log('');
  log('--- Summary ---');
  log(`Registration: ${regPass ? 'PASS' : 'FAIL'} (${regSuccesses}/${CAPACITY} successes)`);
  log(`Check-in:     ${ciPass ? 'PASS' : 'FAIL'} (${ciSuccesses}/1 successes)`);
  const overall = regPass && ciPass;
  log(`Overall:      ${overall ? 'PASS ✓' : 'FAIL ✗'}`);
  log(`Run finished at ${new Date().toISOString()}`);

  fs.writeFileSync(LOG_PATH, logLines.join('\n') + '\n');
  console.log(`\nLog written to ${LOG_PATH}`);

  process.exitCode = overall ? 0 : 1;
}

main().catch((error) => {
  console.error('Concurrency test failed:', error);
  log(`FATAL: ${error.message}`);
  fs.writeFileSync(LOG_PATH, logLines.join('\n') + '\n');
  process.exitCode = 1;
});