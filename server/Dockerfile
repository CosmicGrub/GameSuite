# GameSuite relay server. Build context is this directory:
#   docker build -t gamesuite-relay server/
#   docker run -p 8080:8080 -e TRUST_PROXY=1 gamesuite-relay
# Behind a TLS-terminating proxy (Fly.io, Render, nginx, Caddy) set TRUST_PROXY=1 so per-IP limits see real clients.
# The container holds no state on disk: rooms and matchmaking tickets live in memory and vanish on restart.
FROM node:20-alpine

ENV NODE_ENV=production \
    PORT=8080

WORKDIR /app

# Dependencies first so this layer is cached until package*.json changes.
COPY package.json package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force

# The relay itself: index.js, its modules and the two generated/checked-in data files. Tests and the generator are
# excluded by .dockerignore (the generator reads Kotlin sources that are not in this build context).
COPY . .

USER node
EXPOSE 8080

# /health answers 200 JSON while the process is serving; Node 20 ships fetch, so no curl is needed in the image.
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:'+(process.env.PORT||8080)+'/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

CMD ["node", "index.js"]
