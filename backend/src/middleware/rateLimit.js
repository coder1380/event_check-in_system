const rateLimit = require('express-rate-limit');

const loginLimiter = rateLimit({ windowMs: 60 * 1000, limit: 10 });
const checkinLimiter = rateLimit({ windowMs: 60 * 1000, limit: 300 });

module.exports = { loginLimiter, checkinLimiter };