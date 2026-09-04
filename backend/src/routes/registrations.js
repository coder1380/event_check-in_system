const express = require('express');
const crypto  = require('crypto');
const pool    = require('../db/pool');
const { createError } = require('../errors');
const { requireAuth }  = require('../middleware/auth');

const router = express.Router();
router.use(requireAuth);

const MAX_REFRESHES = 3; // client may auto-refresh up to 3 times (4 QR codes total, ~4 min)

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
function registrationId(value) {
	if (!uuidPattern.test(value)) throw createError(400, 'VALIDATION_ERROR', 'A valid registration id is required.');
	return value;
}

router.get('/', async (req, res, next) => {
	try {
		if (req.user.role !== 'attendee') throw createError(403, 'FORBIDDEN', 'Attendee access is required.');
		const result = await pool.query(`SELECT r.id, r.event_id, e.name AS event_name, e.event_date, r.status, r.created_at, c.checked_in_at FROM registrations r JOIN events e ON e.id = r.event_id LEFT JOIN checkins c ON c.registration_id = r.id WHERE r.attendee_id = $1 ORDER BY e.event_date DESC`, [req.user.sub]);
		res.json({ registrations: result.rows, pagination: { page: 1, limit: result.rowCount, total: result.rowCount, total_pages: result.rowCount ? 1 : 0 } });
	} catch (error) { next(error); }
});

router.post('/:id/cancel', async (req, res, next) => {
	try {
		if (req.user.role !== 'attendee') throw createError(403, 'FORBIDDEN', 'Attendee access is required.');
		const id = registrationId(req.params.id); const client = await pool.connect();
		try {
			await client.query('BEGIN');
			const current = await client.query('SELECT event_id, status FROM registrations WHERE id = $1 AND attendee_id = $2 FOR UPDATE', [id, req.user.sub]);
			if (!current.rowCount) throw createError(404, 'NOT_FOUND', 'Registration not found.');
			if (current.rows[0].status === 'cancelled') throw createError(409, 'ALREADY_CANCELLED', 'Registration is already cancelled.');
			const checkin = await client.query('SELECT 1 FROM checkins WHERE registration_id = $1', [id]);
			if (checkin.rowCount) throw createError(409, 'ALREADY_CHECKED_IN', 'Cannot cancel after check-in.');
			await client.query(`UPDATE registrations SET status = 'cancelled' WHERE id = $1`, [id]);
			await client.query('UPDATE events SET registered_count = registered_count - 1 WHERE id = $1', [current.rows[0].event_id]);
			await client.query('COMMIT'); res.json({ registration: { id, status: 'cancelled' } });
		} catch (error) { await client.query('ROLLBACK').catch(() => {}); next(error); } finally { client.release(); }
	} catch (error) { next(error); }
});

router.get('/:id', async (req, res, next) => {
	try {
		const id = registrationId(req.params.id);
		const result = await pool.query(
			`SELECT r.id, r.event_id, r.attendee_id, r.status, c.checked_in_at,
						e.organizer_id
			 FROM registrations r JOIN events e ON e.id = r.event_id
			 LEFT JOIN checkins c ON c.registration_id = r.id
			 WHERE r.id = $1`, [id],
		);
		const row = result.rows[0];
		if (!row || (row.attendee_id !== req.user.sub && row.organizer_id !== req.user.sub)) {
			throw createError(404, 'NOT_FOUND', 'Registration not found.');
		}
		res.json({ registration: { id: row.id, event_id: row.event_id, status: row.status, checked_in_at: row.checked_in_at } });
	} catch (error) { next(error); }
});

// ── GET /:id/qr-token ─────────────────────────────────────────────────────────
// Query params (for auto-refresh):
//   session_id   – the session UUID returned from the initial token request
//   refresh_count – the current refresh_count on the client (server will increment to this+1)
//
// New session: no query params → issues a fresh token (session_id = new UUID, refresh_count = 0)
// Query params:
//   invalidate_session_id  – (optional) session to atomically kill before issuing new token.
//                            Client sends this when "Close Pass" was tapped on the same device.
//                            The server deletes the matching active token in the SAME transaction
//                            so there is zero window between invalidation and new issuance.
//   session_id + refresh_count – auto-refresh continuation (same device only).
router.get('/:id/qr-token', async (req, res, next) => {
	try {
		const id = registrationId(req.params.id);
		const owner = await pool.query(
			`SELECT 1 FROM registrations WHERE id = $1 AND attendee_id = $2`, [id, req.user.sub],
		);
		if (!owner.rowCount) throw createError(404, 'NOT_FOUND', 'Registration not found.');

		// Parse params
		const invalidateSessionId  = req.query.invalidate_session_id || null;  // atomic self-close
		const incomingSessionId    = req.query.session_id   || null;           // auto-refresh
		const incomingRefreshCount = parseInt(req.query.refresh_count ?? '', 10);
		const isRefresh = incomingSessionId && !Number.isNaN(incomingRefreshCount);

		// Retry loop for SERIALIZABLE isolation failures
		const MAX_RETRIES = 3;
		let lastError = null;
		for (let attempt = 0; attempt < MAX_RETRIES; attempt++) {
			const client = await pool.connect();
			try {
				await client.query('BEGIN ISOLATION LEVEL SERIALIZABLE');

				// ── Step 0: Clean up expired tokens ───────────────────────────
				// Remove any expired tokens so they don't block new token creation
				await client.query(
					`DELETE FROM check_in_tokens WHERE registration_id = $1 AND used_at IS NULL AND expires_at < now()`,
					[id],
				);

				// ── Step 1: Atomic self-invalidation ─────────────────────────────
			// If the client is re-opening after a close, it passes the old session_id.
			// We kill that token here, inside the same transaction, so the SELECT below
			// will not see it as "active" — no race condition, no 409 on same device.
			if (invalidateSessionId) {
				await client.query(
					`UPDATE check_in_tokens
					 SET used_at = now()
					 WHERE registration_id = $1
					   AND session_id      = $2
					   AND used_at IS NULL`,
					[id, invalidateSessionId],
				);
			}

			// ── Step 2: Check for any remaining active token ──────────────────
			const existing = await client.query(
				`SELECT id, session_id, refresh_count, expires_at
				 FROM check_in_tokens
				 WHERE registration_id = $1 AND used_at IS NULL
				 FOR UPDATE`,
				[id],
			);

			if (existing.rowCount) {
				const row       = existing.rows[0];
				const expiresAt = new Date(row.expires_at);
				const now       = new Date();
				const alive     = expiresAt > now;

				if (alive) {
					if (isRefresh && row.session_id === incomingSessionId) {
						// Auto-refresh from the same device — validate count
						const expectedPrev = incomingRefreshCount - 1;
						if (row.refresh_count !== expectedPrev) {
							await client.query('ROLLBACK');
							return res.status(409).json({ error: { code: 'SESSION_MISMATCH', message: 'Refresh count mismatch — possible replay from another device.' } });
						}
						if (row.refresh_count >= MAX_REFRESHES) {
							await client.query('ROLLBACK');
							return res.status(409).json({ error: { code: 'SESSION_REFRESH_LIMIT', message: `Maximum of ${MAX_REFRESHES} auto-refreshes reached. Please request a new QR manually.` } });
						}
						// Falls through: delete old + issue new below
					} else {
						// A DIFFERENT active session exists (another device, or no invalidate_session_id sent)
						await client.query('ROLLBACK');
						const expiresIn = Math.ceil((expiresAt - now) / 1000);
						return res.status(409).json({
							error: {
								code: 'TOKEN_ACTIVE',
								message: `An active QR pass already exists. It expires in ${expiresIn} second(s).`,
								expires_in: expiresIn,
								expires_at: row.expires_at,
							},
						});
					}
				}
				// Delete the old token (expired or being replaced by refresh)
				await client.query('DELETE FROM check_in_tokens WHERE id = $1', [row.id]);
			}

			// ── Step 3: Issue fresh token ─────────────────────────────────────
			let newSessionId    = crypto.randomUUID();
			let newRefreshCount = 0;

			if (isRefresh && existing.rowCount) {
				// Continuing an auto-refresh chain — preserve session identity
				newSessionId    = incomingSessionId;
				newRefreshCount = incomingRefreshCount;
			}

			const token = crypto.randomBytes(32).toString('base64url');
			const result = await client.query(
				`INSERT INTO check_in_tokens (registration_id, token, expires_at, session_id, refresh_count)
				 VALUES ($1, $2, now() + interval '60 seconds', $3, $4)
				 RETURNING token, expires_at, session_id, refresh_count`,
				[id, token, newSessionId, newRefreshCount],
			);

			await client.query('COMMIT');
			const row = result.rows[0];
			return res.json({
				token:               row.token,
				expires_at:          row.expires_at,
				session_id:          row.session_id,
				refresh_count:       row.refresh_count,
				refreshes_remaining: MAX_REFRESHES - row.refresh_count,
			});
		} catch (error) {
				await client.query('ROLLBACK').catch(() => {});
				// Retry on SERIALIZABLE isolation failure (PostgreSQL error code 40001)
				if (error.code === '40001' && attempt < MAX_RETRIES - 1) {
					lastError = error;
					continue;
				}
				next(error);
				return;
			} finally {
				client.release();
			}
		}
		// If we exhausted all retries, pass the last error to the error handler
		next(lastError);
	} catch (error) { next(error); }
});

// ── DELETE /:id/qr-token/active ───────────────────────────────────────────────
// Called by the client when the user closes the QR panel.
// Body: { session_id: "..." }
// Immediately marks the active token used_at = now() so:
//   1. The user can open a new QR pass immediately (exclusion constraint cleared)
//   2. The old QR can no longer be scanned
router.delete('/:id/qr-token/active', async (req, res, next) => {
	try {
		const id        = registrationId(req.params.id);
		const sessionId = req.body?.session_id;

		if (!sessionId) throw createError(400, 'VALIDATION_ERROR', 'session_id is required.');

		const owner = await pool.query(
			`SELECT 1 FROM registrations WHERE id = $1 AND attendee_id = $2`, [id, req.user.sub],
		);
		if (!owner.rowCount) throw createError(404, 'NOT_FOUND', 'Registration not found.');

		// Mark the token belonging to this session as consumed (used_at = now).
		// If the session_id doesn't match or token is already consumed, this is a no-op.
		const result = await pool.query(
			`UPDATE check_in_tokens
			 SET used_at = now()
			 WHERE registration_id = $1
			   AND session_id      = $2
			   AND used_at IS NULL
			 RETURNING id`,
			[id, sessionId],
		);

		// Always respond 204 — even if nothing was updated (idempotent close)
		res.status(204).end();
	} catch (error) { next(error); }
});

module.exports = router;