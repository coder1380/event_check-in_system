const express = require('express');
const helmet = require('helmet');
const cors = require('cors');
const pinoHttp = require('pino-http');
const logger = require('./logger');

const app = express();

app.use(helmet());
app.use(cors({
  origin: process.env.CORS_ORIGIN,
  credentials: true,
  methods: ['GET', 'POST', 'PATCH', 'DELETE'],
}));
app.use(express.json({ limit: '1mb' }));
app.use(pinoHttp({ logger }));

app.use('/api/v1/auth', require('./routes/auth'));
app.use('/api/v1/events', require('./routes/events'));
app.use('/api/v1/registrations', require('./routes/registrations'));
app.use('/api/v1/checkins', require('./routes/checkins'));
app.use('/api/v1/health', require('./routes/health'));

app.use((err, req, res, next) => {
  req.log?.error(err);
  const status = err.status || 500;
  const code = err.code || 'INTERNAL_ERROR';
  res.status(status).json({ error: { code, message: status === 500 ? 'Internal server error.' : err.message } });
});

module.exports = app;