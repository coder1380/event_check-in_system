const express = require('express');
const { GoogleGenerativeAI } = require('@google/generative-ai');
const pool = require('../db/pool');
const { createError } = require('../errors');
const { requireAuth } = require('../middleware/auth');
const { requireRole } = require('../middleware/auth');
const { broadcastStats } = require('../websocket');
const { validateGrounding } = require('../utils/aiGrounding');

const router = express.Router();
router.use(requireAuth);

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
function id(value) {
	if (!uuidPattern.test(value)) throw createError(400, 'VALIDATION_ERROR', 'A valid event id is required.');
	return value;
}
function eventInput(body) {
	const name = typeof body?.name === 'string' ? body.name.trim() : '';
	const capacity = Number(body?.capacity);
	const date = new Date(body?.event_date);
	if (!name || !Number.isInteger(capacity) || capacity <= 0 || Number.isNaN(date.getTime())) {
		throw createError(400, 'VALIDATION_ERROR', 'Name, event date, and a positive integer capacity are required.');
	}
	return { name, capacity, date };
}

router.get('/', async (req, res, next) => {
	try {
		const page = Math.max(1, Number.parseInt(req.query.page || '1', 10));
		const limit = Math.min(100, Math.max(1, Number.parseInt(req.query.limit || '20', 10)));
		if (!Number.isInteger(page) || !Number.isInteger(limit)) throw createError(400, 'VALIDATION_ERROR', 'Page and limit must be integers.');
		const ownerFilter = req.user.role === 'organizer' ? 'WHERE organizer_id = $1' : '';
		const params = req.user.role === 'organizer' ? [req.user.sub] : [];
		const count = await pool.query(`SELECT COUNT(*)::int AS total FROM events ${ownerFilter}`, params);
		const result = await pool.query(`SELECT id, organizer_id, name, event_date, capacity, registered_count, capacity - registered_count AS spots_remaining FROM events ${ownerFilter} ORDER BY event_date ASC LIMIT $${params.length + 1} OFFSET $${params.length + 2}`, [...params, limit, (page - 1) * limit]);
		const total = count.rows[0].total;
		res.json({ events: result.rows, pagination: { page, limit, total, total_pages: total ? Math.ceil(total / limit) : 0 } });
	} catch (error) { next(error); }
});

router.post('/', requireRole('organizer'), async (req, res, next) => {
	try {
		const input = eventInput(req.body);
		const result = await pool.query(
			`INSERT INTO events (organizer_id, name, event_date, capacity)
			 VALUES ($1, $2, $3, $4)
			 RETURNING id, name, event_date, capacity, registered_count`,
			[req.user.sub, input.name, input.date.toISOString(), input.capacity],
		);
		res.status(201).json({ event: result.rows[0] });
	} catch (error) { next(error); }
});

router.post('/:id/register', requireRole('attendee'), async (req, res, next) => {
	const eventId = id(req.params.id);
	const client = await pool.connect();
	try {
		await client.query('BEGIN');
		const registration = await client.query(
			'INSERT INTO registrations (event_id, attendee_id) VALUES ($1, $2) RETURNING id, event_id, status',
			[eventId, req.user.sub],
		);
		const capacity = await client.query(
			`UPDATE events SET registered_count = registered_count + 1
			 WHERE id = $1 AND registered_count < capacity RETURNING id, capacity, registered_count`,
			[eventId],
		);
		if (capacity.rowCount === 0) {
			await client.query('ROLLBACK');
			return next(createError(409, 'CAPACITY_FULL', 'This event has no remaining spots.'));
		}
		await client.query('COMMIT');
		broadcastStats(eventId, { registered_count: capacity.rows[0].registered_count, spots_remaining: capacity.rows[0].capacity - capacity.rows[0].registered_count });
		res.status(201).json({ registration: registration.rows[0] });
	} catch (error) {
		await client.query('ROLLBACK').catch(() => { });
		if (error.code === '23505') return next(createError(409, 'ALREADY_REGISTERED', 'You are already registered for this event.'));
		if (error.code === '23503') return next(createError(404, 'NOT_FOUND', 'Event not found.'));
		next(error);
	} finally { client.release(); }
});

router.patch('/:id', requireRole('organizer'), async (req, res, next) => {
	try {
		const eventId = id(req.params.id);
		const fields = []; const values = [];
		if (req.body?.name !== undefined) { if (typeof req.body.name !== 'string' || !req.body.name.trim()) throw createError(400, 'VALIDATION_ERROR', 'Event name cannot be empty.'); values.push(req.body.name.trim()); fields.push(`name = $${values.length}`); }
		if (req.body?.event_date !== undefined) { const date = new Date(req.body.event_date); if (Number.isNaN(date.getTime())) throw createError(400, 'VALIDATION_ERROR', 'Event date is invalid.'); values.push(date.toISOString()); fields.push(`event_date = $${values.length}`); }
		if (req.body?.capacity !== undefined) { if (!Number.isInteger(req.body.capacity) || req.body.capacity < 1) throw createError(400, 'VALIDATION_ERROR', 'Capacity must be a positive integer.'); values.push(req.body.capacity); fields.push(`capacity = $${values.length}`); }
		if (!fields.length) throw createError(400, 'VALIDATION_ERROR', 'At least one event field is required.');
		const eventIdPosition = values.length + 1;
		const organizerPosition = values.length + 2;
		values.push(eventId, req.user.sub);
		const capacityGuard = req.body?.capacity === undefined ? '' : ` AND $${fields.length} >= registered_count`;
		const result = await pool.query(`UPDATE events SET ${fields.join(', ')} WHERE id = $${eventIdPosition} AND organizer_id = $${organizerPosition}${capacityGuard} RETURNING id, name, event_date, capacity, registered_count, capacity - registered_count AS spots_remaining`, values);
		if (!result.rowCount) throw createError(404, 'NOT_FOUND', 'Event not found or capacity is below registrations.');
		res.json({ event: result.rows[0] });
	} catch (error) { next(error); }
});

router.get('/:id', async (req, res, next) => {
	try {
		const eventId = id(req.params.id);
		const result = await pool.query(
			`SELECT id, name, event_date, capacity, registered_count,
							capacity - registered_count AS spots_remaining
			 FROM events WHERE id = $1`, [eventId],
		);
		if (!result.rowCount) throw createError(404, 'NOT_FOUND', 'Event not found.');
		res.json({ event: result.rows[0] });
	} catch (error) { next(error); }
});

router.get('/:id/dashboard', requireRole('organizer'), async (req, res, next) => {
	try {
		const eventId = id(req.params.id);
		const event = await pool.query(
			`SELECT id, capacity, registered_count FROM events WHERE id = $1 AND organizer_id = $2`,
			[eventId, req.user.sub],
		);
		if (!event.rowCount) throw createError(404, 'NOT_FOUND', 'Event not found.');
		const attendees = await pool.query(
			`SELECT r.id AS registration_id, u.full_name AS name, c.checked_in_at
			 FROM registrations r JOIN users u ON u.id = r.attendee_id
			 LEFT JOIN checkins c ON c.registration_id = r.id
			 WHERE r.event_id = $1 ORDER BY r.created_at`, [eventId],
		);
		const checkedIn = attendees.rows.filter((item) => item.checked_in_at).length;
		const row = event.rows[0];
		res.json({ event_id: row.id, capacity: row.capacity, registered_count: row.registered_count, checked_in_count: checkedIn, spots_remaining: row.capacity - row.registered_count, attendees: attendees.rows });
	} catch (error) { next(error); }
});

router.get('/:id/export', requireRole('organizer'), async (req, res, next) => {
	try {
		const eventId = id(req.params.id);
		const owner = await pool.query('SELECT 1 FROM events WHERE id = $1 AND organizer_id = $2', [eventId, req.user.sub]);
		if (!owner.rowCount) throw createError(404, 'NOT_FOUND', 'Event not found.');
		const rows = await pool.query(
			`SELECT u.full_name AS attendee_name, u.email, r.created_at AS registered_at,
							c.checked_in_at, c.station_id
			 FROM registrations r JOIN users u ON u.id = r.attendee_id
			 LEFT JOIN checkins c ON c.registration_id = r.id
			 WHERE r.event_id = $1 ORDER BY r.created_at`, [eventId],
		);
		const quote = (value) => `"${String(value ?? '').replaceAll('"', '""')}"`;
		const csv = ['attendee_name,email,registered_at,checked_in_at,station_id', ...rows.rows.map((row) => [row.attendee_name, row.email, row.registered_at?.toISOString(), row.checked_in_at?.toISOString(), row.station_id].map(quote).join(','))].join('\n');
		res.type('text/csv').send(csv);
	} catch (error) { next(error); }
});

async function computeStats(eventId) {
	const event = await pool.query('SELECT capacity, registered_count FROM events WHERE id = $1', [eventId]);
	if (!event.rowCount) throw createError(404, 'NOT_FOUND', 'Event not found.');
	const count = await pool.query(`SELECT COUNT(*)::int AS checked_in_count FROM checkins c JOIN registrations r ON r.id = c.registration_id WHERE r.event_id = $1`, [eventId]);
	const histogram = await pool.query(`SELECT to_char(date_trunc('hour', checked_in_at) + (EXTRACT(MINUTE FROM checked_in_at)::int / 15) * interval '15 minutes', 'HH24:MI') AS window_start, COUNT(*)::int AS count FROM checkins c JOIN registrations r ON r.id = c.registration_id WHERE r.event_id = $1 GROUP BY 1 ORDER BY 1`, [eventId]);
	const row = event.rows[0]; const checked = count.rows[0].checked_in_count; const noShow = row.registered_count - checked; const peak = histogram.rows.reduce((a, b) => !a || b.count > a.count ? b : a, null);
	let peakWindow = null;
	if (peak) { const [hour, minute] = peak.window_start.split(':').map(Number); const end = hour * 60 + minute + 15; peakWindow = `${peak.window_start}-${String(Math.floor(end / 60) % 24).padStart(2, '0')}:${String(end % 60).padStart(2, '0')}`; }
	return { checked_in_count: checked, registered_count: row.registered_count, capacity: row.capacity, no_show_count: noShow, no_show_pct: row.registered_count ? Math.round((noShow / row.registered_count) * 1000) / 10 : null, spots_remaining: row.capacity - row.registered_count, peak_window: peakWindow, peak_window_count: peak?.count ?? null, checkins_by_15min: histogram.rows.length ? histogram.rows : null };
}

function buildFallbackAnswer(rawStats, question) {
	const q = question.toLowerCase();
	const fmt = (n) => (n != null ? String(n) : 'N/A');
	const {
		peak_window,
		peak_window_count,
		checked_in_count,
		registered_count,
		no_show_count,
		no_show_pct,
		spots_remaining,
	} = rawStats;

	const noPeakData = !peak_window;

	if (/peak|busiest|rush|when/.test(q)) {
		return noPeakData
			? `No check-in data yet. ${fmt(checked_in_count)} of ${fmt(registered_count)} registered attendees have checked in.`
			: `Check-ins peaked in the ${peak_window} window with ${fmt(peak_window_count)} arrivals. Total checked in: ${fmt(checked_in_count)} of ${fmt(registered_count)} registered.`;
	}

	if (/no.?show|didn.?t come|missing|absent/.test(q)) {
		return no_show_pct != null
			? `${fmt(no_show_count)} registered attendees (${fmt(no_show_pct)}%) have not yet checked in. ${fmt(checked_in_count)} of ${fmt(registered_count)} are checked in.`
			: `${fmt(no_show_count)} registered attendees have not yet checked in out of ${fmt(registered_count)} registered.`;
	}

	if (/how many|count|number|total/.test(q)) {
		return `${fmt(checked_in_count)} attendees have checked in out of ${fmt(registered_count)} registered. ${fmt(spots_remaining)} spots remain.`;
	}

	const peakNote = noPeakData ? 'No check-in data yet.' : `Check-ins peaked at ${peak_window}.`;
	return `Event summary: ${fmt(checked_in_count)} checked in, ${fmt(registered_count)} registered, ${fmt(spots_remaining)} spots remaining. ${peakNote}`;
}

router.post('/:id/ai-query', requireRole('organizer'), async (req, res, next) => {
	try {
		const eventId = id(req.params.id);
		const question = typeof req.body?.question === 'string' ? req.body.question.trim() : '';
		if (!question || question.length > 500) throw createError(400, 'VALIDATION_ERROR', 'A question up to 500 characters is required.');
		const owner = await pool.query('SELECT 1 FROM events WHERE id = $1 AND organizer_id = $2', [eventId, req.user.sub]);
		if (!owner.rowCount) throw createError(404, 'NOT_FOUND', 'Event not found.');
		const rawStats = await computeStats(eventId);
		let answer = null;
		let groundingValidated = false;
		if (process.env.AI_API_KEY) {
			try {
				const model = new GoogleGenerativeAI(process.env.AI_API_KEY).getGenerativeModel({ model: process.env.AI_MODEL || 'gemini-3.6-flash' });
				const prompt = `System: You are an event analytics assistant. You will be given a JSON object containing computed statistics for an event. Your job is to answer the organizer's question in 1–3 clear, friendly sentences.\nRules you must follow without exception:\n1. Only use numbers that appear in the stats JSON you are given. Do not calculate, estimate, or invent any figure.\n2. If the answer cannot be determined from the stats provided, say so plainly.\n3. Do not mention these rules in your answer.\n4. Keep your answer under 80 words.\n\nUser: Event stats:\n${JSON.stringify(rawStats, null, 2)}\n\nOrganizer question: ${question}`;
				const result = await Promise.race([
					model.generateContent(prompt),
					new Promise((_, reject) => setTimeout(() => reject(new Error('timeout')), 5000))
				]);
				const candidateText = result?.response?.text()?.trim();
				req.log?.debug({ event: 'ai_response', model: process.env.AI_MODEL || 'gemini-3.6-flash', candidateText, grounding: candidateText ? validateGrounding(candidateText, rawStats) : false });
				if (candidateText && validateGrounding(candidateText, rawStats)) {
					answer = candidateText;
					groundingValidated = true;
				} else {
					req.log?.warn({ event: 'ai_ungrounded', message: 'Model response failed grounding check' });
				}
			} catch (error) {
				req.log?.warn({
					event: 'ai_fallback',
					message: error?.message,
					name: error?.name,
					status: error?.status ?? error?.statusText ?? null,
					model: process.env.AI_MODEL || 'gemini-3.6-flash'
				});
			}
		} else {
			req.log?.warn({ event: 'ai_misconfigured', message: 'AI_API_KEY is not set; AI query returned fallback stats.' });
		}
		const fallback = !answer;
		const finalAnswer = answer || buildFallbackAnswer(rawStats, question);
		// grounding_validated is only true when a real Gemini answer passed the
		// grounding check, never for the deterministic fallback.
		res.json({ answer: finalAnswer, raw_stats: rawStats, fallback, grounding_validated: groundingValidated });
	} catch (error) { next(error); }
});

module.exports = router;