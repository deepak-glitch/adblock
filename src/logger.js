'use strict';

const { createLogger, format, transports } = require('winston');
const config = require('./config');

const logger = createLogger({
  level: config.log.level,
  format: format.combine(
    format.timestamp({ format: 'YYYY-MM-DD HH:mm:ss' }),
    format.colorize(),
    format.printf(({ timestamp, level, message, ...meta }) => {
      const extra = Object.keys(meta).length ? ' ' + JSON.stringify(meta) : '';
      return `${timestamp} [${level}] ${message}${extra}`;
    })
  ),
  transports: [new transports.Console()],
});

module.exports = logger;
