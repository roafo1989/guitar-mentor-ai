FROM node:22.16.0-bookworm-slim
WORKDIR /app
COPY . /app
RUN npm ci --prefix scripts/research/catalog-169 --omit=dev --ignore-scripts --no-audit --no-fund
USER 1000:1000
ENTRYPOINT ["node", "scripts/research/catalog-169/runner.mjs"]
