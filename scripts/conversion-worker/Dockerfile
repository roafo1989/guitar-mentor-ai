FROM node:22.16.0-bookworm-slim@sha256:048ed02c5fd52e86fda6fbd2f6a76cf0d4492fd6c6fee9e2c463ed5108da0e34
ENV NODE_ENV=production
WORKDIR /opt/converter
COPY scripts/research/catalog-169/package.json scripts/research/catalog-169/package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts --no-audit --no-fund
COPY scripts/conversion-worker/worker.mjs ./worker.mjs
COPY scripts/conversion-worker/entrypoint.sh ./entrypoint.sh
RUN mkdir -p /input /scratch && rm -rf /tmp && ln -s /scratch/tmp /tmp && ln -s /scratch/out /out && chmod 755 entrypoint.sh
USER 10001:10001
ENTRYPOINT ["/opt/converter/entrypoint.sh"]
