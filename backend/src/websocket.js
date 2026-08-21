const { Server } = require('socket.io');
const jwt = require('jsonwebtoken');
const pool = require('./db/pool');

let io;

function initSocketIO(server) {
  io = new Server(server, {
    cors: { origin: process.env.CORS_ORIGIN, methods: ['GET', 'POST'], credentials: true },
  });
  io.use((socket, next) => {
    const token = socket.handshake.auth?.token;
    if (!token) return next(new Error('UNAUTHORIZED'));
    try {
      socket.user = jwt.verify(token, process.env.JWT_ACCESS_SECRET, { algorithms: ['HS256'] });
      next();
    } catch {
      next(new Error('UNAUTHORIZED'));
    }
  });
  io.on('connection', (socket) => {
    socket.on('join:event', async ({ event_id: eventId } = {}) => {
      if (socket.user.role !== 'organizer') return;
      const result = await pool.query('SELECT 1 FROM events WHERE id = $1 AND organizer_id = $2', [eventId, socket.user.sub]);
      if (result.rowCount > 0) socket.join(`event:${eventId}`);
    });
    socket.on('leave:event', ({ event_id: eventId } = {}) => socket.leave(`event:${eventId}`));
  });
  return io;
}

function broadcastCheckin(checkin) {
  if (io) io.to(`event:${checkin.event_id}`).emit('checkin:new', checkin);
}

function broadcastStats(eventId, stats) {
  if (io) io.to(`event:${eventId}`).emit('event:stats_update', { event_id: eventId, ...stats });
}

module.exports = { initSocketIO, broadcastCheckin, broadcastStats };