FROM mcr.microsoft.com/playwright:v1.61.1-noble

WORKDIR /app

COPY package*.json ./
RUN npm ci --omit=dev

COPY dist/ ./dist/

EXPOSE 3000

HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
  CMD node -e "fetch('http://localhost:3000/health').then(r => r.json()).then(d => process.exit(d.status === 'ok' ? 0 : 1)).catch(() => process.exit(1))"

CMD ["node", "dist/index.js"]
