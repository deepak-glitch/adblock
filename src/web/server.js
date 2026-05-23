'use strict';

const express = require('express');
const http = require('http');
const { Server: SocketServer } = require('socket.io');
const path = require('path');
const config = require('../config');
const logger = require('../logger');
const apiRouter = require('./routes/api');

let httpServer = null;
let io = null;

function start(dnsEvents) {
  return new Promise((resolve, reject) => {
    const app = express();

    app.use(express.json());
    app.use(express.static(path.join(__dirname, 'public')));
    app.use('/api', apiRouter);

    // Fallback to index.html for SPA
    app.get('/', (req, res) => {
      res.sendFile(path.join(__dirname, 'public', 'index.html'));
    });

    httpServer = http.createServer(app);
    io = new SocketServer(httpServer, {
      cors: { origin: '*' },
    });

    // Bridge DNS events → Socket.io
    dnsEvents.on('query', (event) => {
      io.emit('query', event);
      // Throttle: only broadcast blocked events to 'live-feed' room
      if (event.action === 'BLOCKED') {
        io.emit('blocked', event);
      }
    });

    io.on('connection', (socket) => {
      logger.debug(`Dashboard client connected: ${socket.id}`);
      socket.on('disconnect', () => {
        logger.debug(`Dashboard client disconnected: ${socket.id}`);
      });
    });

    httpServer.on('error', reject);
    httpServer.listen(config.web.port, config.web.address, () => {
      logger.info(`Dashboard available at http://${config.web.address === '0.0.0.0' ? 'localhost' : config.web.address}:${config.web.port}`);
      resolve(httpServer);
    });
  });
}

function stop() {
  return new Promise((resolve) => {
    if (httpServer) {
      httpServer.close(() => resolve());
    } else {
      resolve();
    }
  });
}

module.exports = { start, stop };
