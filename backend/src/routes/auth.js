const crypto = require('crypto');
const express = require('express');
const bcrypt = require('bcryptjs');
const jwt = require('jsonwebtoken');
const pool = require('../db/pool');
const { createError } = require('../errors');
const { loginLimiter } = require('../middleware/rateLimit');

const router = express.Router();
const roles = new Set(['organizer', 'attendee']);

function publicUser(user) {
  return { id: user.id, email: user.email, full_name: user.full_name, role: user.role };
}

function accessToken(user) {
  return jwt.sign({ sub: user.id, role: user.role }, process.env.JWT_ACCESS_SECRET, {
    algorithm: 'HS256',
    expiresIn: '15m',
  });
}

async function issueRefreshToken(client, userId) {
  const rawToken = crypto.randomBytes(48).toString('base64url');
  const tokenHash = crypto.createHash('sha256').update(rawToken).digest('hex');
  await client.query(
    `INSERT INTO refresh_tokens (user_id, token_hash, expires_at)
     VALUES ($1, $2, now() + interval '7 days')`,
    [userId, tokenHash],
  );
  return rawToken;
}

function validateCredentials(body, includeRole) {
  const { email, password, full_name: fullName, role } = body || {};
  if (!email || !password || (includeRole && (!fullName || !roles.has(role)))) {
    throw createError(400, 'VALIDATION_ERROR', 'Required fields are missing or invalid.');
  }
  if (password.length < 8) {
    throw createError(400, 'VALIDATION_ERROR', 'Password must be at least 8 characters.');
  }
  return { email: email.trim().toLowerCase(), password, fullName, role };
}

router.post('/register', async (req, res, next) => {
  try {
    const { email, password, fullName, role } = validateCredentials(req.body, true);
    const passwordHash = await bcrypt.hash(password, 12);
    const client = await pool.connect();
    try {
      await client.query('BEGIN');
      const result = await client.query(
        `INSERT INTO users (email, password_hash, full_name, role)
         VALUES ($1, $2, $3, $4) RETURNING id, email, full_name, role`,
        [email, passwordHash, fullName.trim(), role],
      );
      const user = result.rows[0];
      const refreshToken = await issueRefreshToken(client, user.id);
      await client.query('COMMIT');
      res.status(201).json({ user: publicUser(user), access_token: accessToken(user), refresh_token: refreshToken });
    } catch (error) {
      await client.query('ROLLBACK');
      if (error.code === '23505') return next(createError(409, 'ALREADY_REGISTERED', 'An account with that email already exists.'));
      next(error);
    } finally {
      client.release();
    }
  } catch (error) {
    next(error);
  }
});

router.post('/login', loginLimiter, async (req, res, next) => {
  try {
    const { email, password } = validateCredentials(req.body, false);
    const result = await pool.query('SELECT id, email, password_hash, full_name, role FROM users WHERE email = $1', [email]);
    const user = result.rows[0];
    if (!user || !(await bcrypt.compare(password, user.password_hash))) {
      throw createError(401, 'UNAUTHORIZED', 'Invalid email or password.');
    }
    const refreshToken = await issueRefreshToken(pool, user.id);
    res.json({ user: publicUser(user), access_token: accessToken(user), refresh_token: refreshToken });
  } catch (error) {
    next(error);
  }
});

router.post('/refresh', async (req, res, next) => {
  const rawToken = req.body?.refresh_token;
  if (!rawToken) return next(createError(401, 'UNAUTHORIZED', 'Authentication is required.'));
  const tokenHash = crypto.createHash('sha256').update(rawToken).digest('hex');
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const result = await client.query(
      `SELECT rt.id, rt.user_id, rt.revoked_at, rt.expires_at, u.role
       FROM refresh_tokens rt JOIN users u ON u.id = rt.user_id
       WHERE rt.token_hash = $1 FOR UPDATE`,
      [tokenHash],
    );
    const token = result.rows[0];
    if (!token || token.revoked_at || new Date(token.expires_at) <= new Date()) {
      await client.query('ROLLBACK');
      throw createError(401, 'UNAUTHORIZED', 'Refresh token is invalid or expired.');
    }
    await client.query('UPDATE refresh_tokens SET revoked_at = now() WHERE id = $1', [token.id]);
    const userResult = await client.query('SELECT id, email, full_name, role FROM users WHERE id = $1', [token.user_id]);
    const user = userResult.rows[0];
    const refreshToken = await issueRefreshToken(client, user.id);
    await client.query('COMMIT');
    res.json({ access_token: accessToken(user), refresh_token: refreshToken });
  } catch (error) {
    if (client) await client.query('ROLLBACK').catch(() => {});
    next(error);
  } finally {
    client.release();
  }
});

router.post('/logout', async (req, res, next) => {
  const rawToken = req.body?.refresh_token;
  if (!rawToken) return res.status(204).send();
  try {
    const tokenHash = crypto.createHash('sha256').update(rawToken).digest('hex');
    await pool.query('UPDATE refresh_tokens SET revoked_at = now() WHERE token_hash = $1 AND revoked_at IS NULL', [tokenHash]);
    res.status(204).send();
  } catch (error) { next(error); }
});

module.exports = router;