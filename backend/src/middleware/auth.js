const jwt = require('jsonwebtoken');
const { createError } = require('../errors');

function requireAuth(req, res, next) {
  const header = req.get('authorization');
  const token = header && header.startsWith('Bearer ') ? header.slice(7) : null;
  if (!token) return next(createError(401, 'UNAUTHORIZED', 'Authentication is required.'));

  try {
    req.user = jwt.verify(token, process.env.JWT_ACCESS_SECRET, { algorithms: ['HS256'] });
    next();
  } catch {
    next(createError(401, 'UNAUTHORIZED', 'Authentication is required.'));
  }
}

function requireRole(role) {
  return (req, res, next) => {
    if (req.user?.role !== role) {
      return next(createError(403, 'FORBIDDEN', 'You do not have permission to perform this action.'));
    }
    next();
  };
}

module.exports = { requireAuth, requireRole };