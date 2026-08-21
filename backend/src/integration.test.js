import { describe, expect, it, beforeAll, afterAll } from 'vitest';
import express from 'express';
import request from 'supertest';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const pool = require('./db/pool.js');
const app = require('./app.js');

describe('Integration Tests — Full Check-in & Sync Flow', () => {
  let organizerToken;
  let attendeeToken;
  let eventId;
  let qrToken;
  let registrationId;

  beforeAll(async () => {
    // Check if Postgres is reachable
    try {
      await pool.query('SELECT 1');
    } catch {
      console.warn('Database connection unavailable, skipping integration suite');
      return;
    }

    // Create test organizer and attendee
    const orgRes = await request(app).post('/api/v1/auth/register').send({
      email: `org_${Date.now()}@example.com`,
      password: 'password123',
      full_name: 'Test Organizer',
      role: 'organizer',
    });
    organizerToken = orgRes.body.access_token;

    const attRes = await request(app).post('/api/v1/auth/register').send({
      email: `att_${Date.now()}@example.com`,
      password: 'password123',
      full_name: 'Test Attendee',
      role: 'attendee',
    });
    attendeeToken = attRes.body.access_token;
  });

  afterAll(async () => {
    await pool.end().catch(() => {});
  });

  it('creates an event successfully', async () => {
    if (!organizerToken) return;
    const res = await request(app)
      .post('/api/v1/events')
      .set('Authorization', `Bearer ${organizerToken}`)
      .send({
        name: 'Integration Test Party',
        event_date: new Date(Date.now() + 86400000).toISOString(),
        capacity: 2,
      });

    expect(res.status).toBe(201);
    expect(res.body.event).toBeDefined();
    eventId = res.body.event.id;
  });

  it('registers attendee and receives QR token', async () => {
    if (!attendeeToken || !eventId) return;
    const regRes = await request(app)
      .post(`/api/v1/events/${eventId}/register`)
      .set('Authorization', `Bearer ${attendeeToken}`)
      .send({});

    expect(regRes.status).toBe(201);
    expect(regRes.body.registration).toBeDefined();
    registrationId = regRes.body.registration.id;

    // Get QR token
    const qrRes = await request(app)
      .get(`/api/v1/registrations/${registrationId}/qr-token`)
      .set('Authorization', `Bearer ${attendeeToken}`);

    expect(qrRes.status).toBe(200);
    expect(qrRes.body.token).toBeDefined();
    qrToken = qrRes.body.token;
  });

  it('performs online check-in successfully', async () => {
    if (!organizerToken || !qrToken) return;
    const checkinRes = await request(app)
      .post('/api/v1/checkins')
      .set('Authorization', `Bearer ${organizerToken}`)
      .send({
        token: qrToken,
        station_id: 'gate-a',
      });

    expect(checkinRes.status).toBe(201);
    expect(checkinRes.body.checkin).toBeDefined();
  });

  it('rejects duplicate check-in with 409 ALREADY_CHECKED_IN', async () => {
    if (!organizerToken || !qrToken) return;
    const checkinRes = await request(app)
      .post('/api/v1/checkins')
      .set('Authorization', `Bearer ${organizerToken}`)
      .send({
        token: qrToken,
        station_id: 'gate-b',
      });

    expect(checkinRes.status).toBe(409);
    expect(checkinRes.body.error.code).toBe('ALREADY_CHECKED_IN');
  });

  it('processes sync-batch idempotently', async () => {
    if (!organizerToken || !qrToken) return;
    const syncRes = await request(app)
      .post('/api/v1/checkins/sync-batch')
      .set('Authorization', `Bearer ${organizerToken}`)
      .send({
        station_id: 'gate-c',
        scans: [
          { token: qrToken, client_scanned_at: new Date().toISOString() },
        ],
      });

    expect(syncRes.status).toBe(200);
    expect(syncRes.body.results).toBeDefined();
    expect(syncRes.body.results[0].outcome).toBe('rejected_duplicate');
  });
});
