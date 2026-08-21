const http = require('http');
const app = require('./app');
const { validateConfig } = require('./config');
const { initSocketIO } = require('./websocket');

validateConfig();
const server = http.createServer(app);
initSocketIO(server);

const port = process.env.PORT || 3000;
const host = process.env.HOST || '0.0.0.0';
server.listen(port, host, () => console.log(`Backend listening on ${host}:${port}`));