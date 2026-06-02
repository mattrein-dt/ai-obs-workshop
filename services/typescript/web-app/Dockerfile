FROM node:20-alpine AS builder
WORKDIR /app
COPY services/typescript/web-app/package.json services/typescript/web-app/package-lock.json* ./
RUN npm ci || npm install
COPY services/typescript/web-app/ .
RUN npm run build

FROM node:20-alpine AS runner
WORKDIR /app
ENV NODE_ENV=production

COPY --from=builder /app/.next/standalone ./
COPY --from=builder /app/.next/static ./.next/static

EXPOSE 3000
ENV PORT=3000
CMD ["node", "server.js"]
