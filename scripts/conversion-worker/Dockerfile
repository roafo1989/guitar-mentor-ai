FROM node:22.16.0-bookworm-slim
ENV NODE_ENV=production
WORKDIR /opt/converter
COPY scripts/research/catalog-169/package.json scripts/research/catalog-169/package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts --no-audit --no-fund
COPY scripts/conversion-worker/worker.mjs ./worker.mjs
RUN mkdir -p /input /out && chown 10001:10001 /input /out
USER 10001:10001
ENTRYPOINT ["node", "/opt/converter/worker.mjs"]
