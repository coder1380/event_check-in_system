require('dotenv').config({ path: require('path').join(__dirname, '../../.env') });
const { Pool } = require('pg');

// Managed Postgres providers (e.g. Render) require TLS/SSL. Enable it in
// production only so local/dev/test (e.g. docker-compose Postgres) is unaffected.
const isProduction = process.env.NODE_ENV === 'production';

const pool = new Pool({
  connectionString: process.env.DATABASE_URL,
  max: 10,
  idleTimeoutMillis: 30_000,
  connectionTimeoutMillis: 2_000,
  options: '-c timezone=UTC',
  ssl: isProduction ? { rejectUnauthorized: false } : false,
});

pool.on('error', (error) => {
  console.error('Unexpected DB pool error', error);
});

module.exports = pool;