# --- Builder stage
FROM node:23-alpine AS builder
WORKDIR /app

# Copy package.json first to cache node_modules
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml .

# Match packageManager in package.json (pnpm is not bundled in the Node image)
RUN corepack enable && corepack prepare pnpm@11.5.2 --activate

RUN pnpm install

# Copy code and build with cached modules
COPY . .
RUN pnpm run build:web

# --- Production stage
FROM nginxinc/nginx-unprivileged:alpine-slim

COPY --chown=nginx:nginx --from=builder /app/out/web /usr/share/nginx/html
COPY --chown=nginx:nginx ./settings.js.template /etc/nginx/templates/settings.js.template
COPY --chown=nginx:nginx ng.conf.template /etc/nginx/templates/default.conf.template

# SERVER_URL default is a non-empty identity key (never dialed — Streamify
# auth goes through /streamify-auth): login-route.tsx marks an empty
# SERVER_URL as isValid:false ("No server selected") when SERVER_LOCK=true,
# so the locked image must ship a valid placeholder URL. Deployments that
# lock to a real stock server override it at runtime as before.
ENV SERVER_LOCK=false SERVER_NAME="" SERVER_TYPE="" SERVER_URL="https://streamify.local" REMOTE_URL=""
ENV LEGACY_AUTHENTICATION="" ANALYTICS_DISABLED="" PUBLIC_PATH="/"
# Fork addition (see PATCHES.md): defined so nginx envsubst substitutes the
# Streamify settings.js knobs even when the deployment leaves them empty.
# P5-A: the ANON_KEY closing quote has been missing since the #186 append
# (the whole line collapsed into one envsubst token), and
# STREAMIFY_DEV_ACCESS_TOKEN — referenced by settings.js.template since P1B
# — was never defined. Both fixed here; relayBase.ts now also rejects any
# unexpanded ${...} placeholder so a missing variable can never leak as a
# live URL.
ENV STREAMIFY_AUTH="" STREAMIFY_SUPABASE_URL="" STREAMIFY_SUPABASE_ANON_KEY="" STREAMIFY_RELAY_BASE="" STREAMIFY_DEV_ACCESS_TOKEN=""

EXPOSE 9180
CMD ["nginx", "-g", "daemon off;"]
