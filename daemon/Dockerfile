FROM node:22-alpine

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --omit=dev

COPY src ./src
COPY config.example.json ./config.example.json

EXPOSE 8765

CMD ["npm", "start"]
