import { createRequire } from 'node:module';
import { describe, expect, it } from 'vitest';

process.env.JWT_ACCESS_SECRET = 'test-access-secret';
process.env.JWT_REFRESH_SECRET = 'test-refresh-secret';
process.env.CORS_ORIGIN = 'http://localhost:5173';

const require = createRequire(import.meta.url);
const request = require('supertest');
const app = require('./app');

describe('API authentication boundary', () => {
  it('rejects protected routes without an access token', async () => {
    const response = await request(app).get('/api/v1/events');

    expect(response.status).toBe(401);
    expect(response.body).toEqual({
      error: {
        code: 'UNAUTHORIZED',
        message: 'Authentication is required.',
      },
    });
  });
});