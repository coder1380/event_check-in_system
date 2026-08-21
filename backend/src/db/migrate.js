const fs = require('fs');
const path = require('path');
const pool = require('./pool');

async function migrate() {
  if (!process.env.DATABASE_URL) {
    throw new Error('DATABASE_URL is missing. Create the project root .env file from .env.example and set your PostgreSQL connection string.');
  }
  const migrationPath = path.join(__dirname, '../../migrations/001_init.sql');
  const sql = fs.readFileSync(migrationPath, 'utf8');
  await pool.query(sql);
  console.log('Migration complete');
}

migrate()
  .catch((error) => {
    console.error(error);
    process.exitCode = 1;
  })
  .finally(() => pool.end());