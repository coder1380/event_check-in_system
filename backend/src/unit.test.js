import { describe, expect, it } from 'vitest';
import { validateGrounding } from './utils/aiGrounding.js';
import express from 'express';
import request from 'supertest';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const jwt = require('jsonwebtoken');
const { requireAuth, requireRole } = require('./middleware/auth.js');

process.env.JWT_ACCESS_SECRET = process.env.JWT_ACCESS_SECRET || 'test-access-secret';

describe('AI Grounding Validation Utility', () => {
  const sampleStats = {
    checked_in_count: 42,
    registered_count: 50,
    capacity: 100,
    no_show_count: 8,
    no_show_pct: 16,
    spots_remaining: 50,
    peak_window: '14:15-14:30',
    peak_window_count: 15,
  };

  it('passes when all numbers in response exist in rawStats payload', () => {
    const answer = 'So far 42 of 50 registered attendees have checked in (16% no-show).';
    expect(validateGrounding(answer, sampleStats)).toBe(true);
  });

  it('passes when response contains no numbers at all', () => {
    const answer = 'No attendees have checked in yet.';
    expect(validateGrounding(answer, sampleStats)).toBe(true);
  });

  it('fails when response includes hallucinated numbers not present in rawStats', () => {
    const answer = '99 attendees checked in out of 50 registered.';
    expect(validateGrounding(answer, sampleStats)).toBe(false);
  });

  it('handles empty or invalid inputs gracefully', () => {
    expect(validateGrounding('', sampleStats)).toBe(false);
    expect(validateGrounding(null, sampleStats)).toBe(false);
    expect(validateGrounding('42 check-ins', null)).toBe(false);
  });
});

describe('Role & Ownership Middleware', () => {
  const app = express();
  app.use(express.json());
  app.get('/api/v1/organizer-only', requireAuth, requireRole('organizer'), (req, res) => {
    res.json({ ok: true });
  });

  app.use((err, req, res, next) => {
    const status = err.status || 500;
    const code = err.code || 'INTERNAL_ERROR';
    res.status(status).json({ error: { code, message: err.message } });
  });

  it('rejects unauthenticated requests with 401 UNAUTHORIZED', async () => {
    const res = await request(app).get('/api/v1/organizer-only');
    expect(res.status).toBe(401);
    expect(res.body.error.code).toBe('UNAUTHORIZED');
  });

  it('rejects attendee users with 403 FORBIDDEN on organizer endpoints', async () => {
    const token = jwt.sign({ sub: 'user-1', role: 'attendee' }, process.env.JWT_ACCESS_SECRET);
    const res = await request(app)
      .get('/api/v1/organizer-only')
      .set('Authorization', `Bearer ${token}`);
    expect(res.status).toBe(403);
    expect(res.body.error.code).toBe('FORBIDDEN');
  });

  it('allows organizer users to access organizer endpoints', async () => {
    const token = jwt.sign({ sub: 'user-2', role: 'organizer' }, process.env.JWT_ACCESS_SECRET);
    const res = await request(app)
      .get('/api/v1/organizer-only')
      .set('Authorization', `Bearer ${token}`);
    expect(res.status).toBe(200);
    expect(res.body.ok).toBe(true);
  });
});
