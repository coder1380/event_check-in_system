const express = require('express');
const pool = require('../db/pool');
const { createError } = require('../errors');
const { requireAuth } = require('../middleware/auth');
const { checkinLimiter } = require('../middleware/rateLimit');
const { broadcastCheckin } = require('../websocket');

const router = express.Router();
router.use(requireAuth, checkinLimiter);

function validateScan(body) {
	if (typeof body?.token !== 'string' || !body.token.trim() || typeof body?.station_id !== 'string' || !body.station_id.trim()) {
		throw createError(400, 'VALIDATION_ERROR', 'Token and station_id are required.');
	}
	return { token: body.token.trim(), stationId: body.station_id.trim() };
}

async function findToken(client, token) {
	const result = await client.query(
		`SELECT t.id AS token_id, t.registration_id, t.used_at, t.expires_at,
						r.event_id, u.full_name AS attendee_name, e.capacity,
						e.organizer_id
		 FROM check_in_tokens t
		 JOIN registrations r ON r.id = t.registration_id
		 JOIN users u ON u.id = r.attendee_id
		 JOIN events e ON e.id = r.event_id
		 WHERE t.token = $1 FOR UPDATE`, [token],
	);
	return result.rows[0];
}

async function insertCheckin(client, scan, stationId, source, enforceExpiry) {
	const token = await findToken(client, scan);
	if (!token) throw createError(404, 'TOKEN_INVALID', 'The QR token is invalid.');
	
	// Check FIRST if this registration is already checked in
	const existing = await client.query('SELECT checked_in_at, station_id FROM checkins WHERE registration_id = $1', [token.registration_id]);
	if (existing.rowCount) {
		return { accepted: false, token, existing: existing.rows[0] };
	}

	if (token.used_at) {
		throw createError(404, 'TOKEN_INVALID', 'The QR token is invalid.');
	}
	if (enforceExpiry && new Date(token.expires_at) < new Date()) throw createError(410, 'TOKEN_EXPIRED', 'The QR token has expired.');
	const inserted = await client.query(
		`INSERT INTO checkins (registration_id, token_id, station_id, source)
		 VALUES ($1, $2, $3, $4) ON CONFLICT (registration_id) DO NOTHING
		 RETURNING id, registration_id, checked_in_at, station_id, source`,
		[token.registration_id, token.token_id, stationId, source],
	);
	if (!inserted.rowCount) {
		const recheckExisting = await client.query('SELECT checked_in_at, station_id FROM checkins WHERE registration_id = $1', [token.registration_id]);
		return { accepted: false, token, existing: recheckExisting.rows[0] };
	}
	await client.query('UPDATE check_in_tokens SET used_at = now() WHERE id = $1', [token.token_id]);
	return { accepted: true, token, checkin: inserted.rows[0] };
}

async function eventCounts(client, eventId) {
	const result = await client.query(
		`SELECT e.capacity, e.registered_count, COUNT(c.id)::int AS checked_in_count
		 FROM events e LEFT JOIN registrations r ON r.event_id = e.id
		 LEFT JOIN checkins c ON c.registration_id = r.id WHERE e.id = $1
		 GROUP BY e.id`, [eventId],
	);
	return result.rows[0];
}

router.post('/', async (req, res, next) => {
	try {
		if (req.user.role !== 'organizer') throw createError(403, 'FORBIDDEN', 'Organizer access is required.');
		const { token, stationId } = validateScan(req.body);
		const client = await pool.connect();
		try {
			await client.query('BEGIN');
			const outcome = await insertCheckin(client, token, stationId, 'online', true);
			if (!outcome.accepted) {
				await client.query('ROLLBACK');
				return next(createError(409, 'ALREADY_CHECKED_IN', `Already checked in at ${outcome.existing.checked_in_at.toISOString()} (${outcome.existing.station_id}).`));
			}
			await client.query('COMMIT');
			const counts = await pool.query(`SELECT e.capacity, e.registered_count, COUNT(c.id)::int AS checked_in_count FROM events e LEFT JOIN registrations r ON r.event_id = e.id LEFT JOIN checkins c ON c.registration_id = r.id WHERE e.id = $1 GROUP BY e.id`, [outcome.token.event_id]);
			broadcastCheckin({ registration_id: outcome.checkin.registration_id, attendee_name: outcome.token.attendee_name, checked_in_at: outcome.checkin.checked_in_at, station_id: outcome.checkin.station_id, source: outcome.checkin.source, checked_in_count: counts.rows[0].checked_in_count, spots_remaining: counts.rows[0].capacity - counts.rows[0].registered_count });
			res.status(201).json({ checkin: { registration_id: outcome.checkin.registration_id, checked_in_at: outcome.checkin.checked_in_at, station_id: outcome.checkin.station_id } });
		} catch (error) { await client.query('ROLLBACK').catch(() => {}); next(error); } finally { client.release(); }
	} catch (error) { next(error); }
});

router.post('/sync-batch', async (req, res, next) => {
	try {
		if (req.user.role !== 'organizer') throw createError(403, 'FORBIDDEN', 'Organizer access is required.');
		const stationId = req.body?.station_id;
		const scans = req.body?.scans;
		if (typeof stationId !== 'string' || !stationId.trim() || !Array.isArray(scans) || scans.length > 100) throw createError(400, 'VALIDATION_ERROR', 'station_id and up to 100 scans are required.');
		const results = [];
		for (const scan of scans) {
			if (typeof scan?.token !== 'string' || Number.isNaN(new Date(scan.client_scanned_at).getTime())) {
				results.push({ token: scan?.token ?? null, outcome: 'rejected_duplicate', error: 'VALIDATION_ERROR' }); continue;
			}
			const client = await pool.connect();
			try {
				await client.query('BEGIN');
				const outcome = await insertCheckin(client, scan.token.trim(), stationId.trim(), 'offline_sync', false);
				let result;
				if (outcome.accepted) {
					await client.query(`INSERT INTO offline_sync_log (registration_id, station_id, client_scanned_at, outcome, resulting_checkin_id) VALUES ($1, $2, $3, 'accepted', $4)`, [outcome.token.registration_id, stationId, new Date(scan.client_scanned_at), outcome.checkin.id]);
					result = { token: scan.token, outcome: 'accepted', checked_in_at: outcome.checkin.checked_in_at };
				} else {
					await client.query(`INSERT INTO offline_sync_log (registration_id, station_id, client_scanned_at, outcome) VALUES ($1, $2, $3, 'rejected_duplicate')`, [outcome.token.registration_id, stationId, new Date(scan.client_scanned_at)]);
					result = { token: scan.token, outcome: 'rejected_duplicate', checked_in_at: outcome.existing.checked_in_at, station_id: outcome.existing.station_id };
				}
				await client.query('COMMIT'); results.push(result);
				if (outcome.accepted) {
					const counts = await eventCounts(pool, outcome.token.event_id);
					broadcastCheckin({ registration_id: outcome.checkin.registration_id, attendee_name: outcome.token.attendee_name, checked_in_at: outcome.checkin.checked_in_at, station_id: outcome.checkin.station_id, source: 'offline_sync', checked_in_count: counts.checked_in_count, spots_remaining: counts.capacity - counts.registered_count });
				}
			} catch (error) { await client.query('ROLLBACK').catch(() => {}); results.push({ token: scan.token, outcome: 'rejected_duplicate', error: error.code || 'TOKEN_INVALID' }); } finally { client.release(); }
		}
		res.json({ results });
	} catch (error) { next(error); }
});

module.exports = router;