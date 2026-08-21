const express = require('express');
const crypto = require('crypto');
const pool = require('../db/pool');
const { createError } = require('../errors');
const { requireAuth } = require('../middleware/auth');

const router = express.Router();
router.use(requireAuth);

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

router.get('/:id/qr-token', async (req, res, next) => {
	try {
		const id = registrationId(req.params.id);
		const owner = await pool.query(
			`SELECT 1 FROM registrations WHERE id = $1 AND attendee_id = $2`, [id, req.user.sub],
		);
		if (!owner.rowCount) throw createError(404, 'NOT_FOUND', 'Registration not found.');
		const client = await pool.connect();
		try {
			await client.query('BEGIN');
			await client.query('UPDATE check_in_tokens SET used_at = now() WHERE registration_id = $1 AND used_at IS NULL', [id]);
			const token = crypto.randomBytes(32).toString('base64url');
			const result = await client.query(
				`INSERT INTO check_in_tokens (registration_id, token, expires_at)
				 VALUES ($1, $2, now() + interval '60 seconds') RETURNING token, expires_at`, [id, token],
			);
			await client.query('COMMIT');
			res.json(result.rows[0]);
		} catch (error) {
			await client.query('ROLLBACK').catch(() => {}); next(error);
		} finally { client.release(); }
	} catch (error) { next(error); }
});

module.exports = router;