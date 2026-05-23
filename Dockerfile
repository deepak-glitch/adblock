FROM node:22-alpine

LABEL org.opencontainers.image.title="Stream AdBlock"
LABEL org.opencontainers.image.description="DNS-level ad blocker for Hulu, Netflix, Max, Peacock, and more"

WORKDIR /app

# Copy package files first (layer caching)
COPY package*.json ./
RUN npm ci --omit=dev

# Copy application source
COPY src/ ./src/
COPY lists/ ./lists/

# Create writable directories
RUN mkdir -p /app/data /app/lists/remote

# Expose DNS (UDP) and dashboard (TCP)
EXPOSE 53/udp
EXPOSE 3000/tcp

# Health check: verify DNS port is responding
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD node -e "const d=require('dgram').createSocket('udp4');d.send(Buffer.alloc(12),53,'127.0.0.1',e=>{d.close();process.exit(e?1:0)})"

CMD ["node", "src/index.js"]
