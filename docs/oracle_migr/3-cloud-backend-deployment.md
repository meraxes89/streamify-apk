# 3. Cloud Backend Deployment & Hosting Strategy

## 1. Executive Summary & Platform Assessment

To host the **Architecture v3** Node.js 8 MiB Range Stream Relay and Multi-Profile Resolver without requiring a credit card or incurring monthly costs, we evaluated major container and serverless platforms.

### Platform Evaluation Matrix

| Platform | Free Tier | Credit Card Required? | Docker / Node.js Support | Status / Suitability |
| :--- | :--- | :--- | :--- | :--- |
| **Oracle Cloud Always Free** | 4 OCPU, 24 GB RAM | **YES** (Strict verification; rejects RuPay/FamPay) | Full Root VPS | Blocked by payment gateway |
| **Hugging Face Spaces** | 2 vCPU, 16 GB RAM | **Requires PRO ($9/mo)** for Docker/Gradio | Docker / Python | Deprecated for free tier |
| **Render.com** | 512 MB RAM, 0.1 CPU | **NO (100% Free with GitHub login)** | Native Node.js & Docker | **RECOMMENDED PRIMARY** |
| **Glitch.com** | 512 MB RAM | **NO** | Native Node.js | Secondary / Backup |
| **Cloudflare Tunnel (Home/PC)** | Local Hardware | **NO** | Native Linux/ARM64 | Alternative Residential Egress |

---

## 2. Selected Primary: Render.com Free Web Service

Render provides a completely free, zero-credit-card web service environment with direct GitHub repository integration.

### 2.1. Key Capabilities
- **Zero Payment Method Required:** Sign up directly via GitHub OAuth.
- **Automated CI/CD:** Connected to `meraxes89/streamify-apk`. Any commit to the server directory automatically triggers a zero-downtime rebuild.
- **Public HTTPS Endpoint:** Automatically provisions a secure URL (`https://streamify-relay.onrender.com`).
- **Standard Node.js 20 LTS:** Runs our Fastify/Node.js Architecture v3 stream relay and resolver.
- **Port Handling:** Automatically reads `PORT` environment variable provided by Render.

### 2.2. Sleeping & Keep-Alive Policy
- Render free tier instances spin down after 15 minutes of inactivity.
- **Solution:** A lightweight Cloudflare Worker Cron trigger (or UptimeRobot ping) requests `GET /api/health` every 10 minutes, keeping the relay warm and responsive 24/7.

---

## 3. Server Architecture Specifications

The relay service lives in `server/` with:
- **`package.json`**: Dependencies (`@distube/ytdl-core` / `youtubei.js`, `fastify` or native `http`).
- **`index.js`**:
  - `GET /api/health`: Health status, active profile, memory.
  - `GET /api/resolve`: Multi-profile mobile resolver (`ANDROID_VR` + `VISIONOS`).
  - `GET /api/stream/:videoId`: 8 MiB bounded chunked stream relay with HTTP 206 Partial Content.
  - `POST /api/prefetch`: Asynchronous queue prefetch worker.
  - `GET /api/search`: Catalog autocomplete and search proxy.
