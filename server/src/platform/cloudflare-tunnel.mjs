import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { spawn } from 'node:child_process';
import { EventEmitter } from 'node:events';

/**
 * Cloudflare Tunnel Manager for Streamify Relay.
 *
 * Capabilities:
 * - Zero-config Ephemeral Quick Tunnels (*.trycloudflare.com).
 * - Persistent Named Tunnels using CLOUDFLARE_TUNNEL_TOKEN.
 * - Architecture resolution and binary discovery.
 * - Non-blocking child process management with auto-recovery and graceful shutdown.
 */
export class CloudflareTunnel extends EventEmitter {
  constructor({
    port = 3000,
    host = '127.0.0.1',
    token = process.env.CLOUDFLARE_TUNNEL_TOKEN || null,
    binPath = process.env.CLOUDFLARED_PATH || null,
    dataDir = null,
    logger = console,
    autoRestart = true,
  } = {}) {
    super();
    this.port = port;
    this.host = host;
    this.token = token;
    this.binPath = binPath;
    this.dataDir = dataDir;
    this.logger = logger;
    this.autoRestart = autoRestart;

    this.childProcess = null;
    this.status = 'disconnected'; // 'disconnected' | 'starting' | 'connected' | 'error'
    this.url = null;
    this.lastError = null;
    this.restartAttempts = 0;
    this.maxRestartAttempts = 3;
    this._stopping = false;
  }

  /**
   * Resolves the official download URL for the host machine architecture.
   */
  static getDownloadUrl(platform = process.platform, arch = process.arch) {
    const base = 'https://github.com/cloudflare/cloudflared/releases/latest/download';
    if (platform === 'linux' || platform === 'android') {
      if (arch === 'arm64') return `${base}/cloudflared-linux-arm64`;
      if (arch === 'arm') return `${base}/cloudflared-linux-arm`;
      if (arch === 'x64') return `${base}/cloudflared-linux-amd64`;
      if (arch === 'ia32') return `${base}/cloudflared-linux-386`;
    } else if (platform === 'darwin') {
      return `${base}/cloudflared-darwin-amd64.tgz`;
    } else if (platform === 'win32') {
      if (arch === 'x64') return `${base}/cloudflared-windows-amd64.exe`;
      return `${base}/cloudflared-windows-386.exe`;
    }
    return null;
  }

  /**
   * Discovers the cloudflared binary location on the host.
   */
  resolveBinary() {
    if (this.binPath && fs.existsSync(this.binPath)) {
      return this.binPath;
    }

    const systemNames = process.platform === 'win32' ? ['cloudflared.exe'] : ['cloudflared'];

    // Check Termux PREFIX bin ($PREFIX/bin/cloudflared)
    if (process.env.PREFIX) {
      const termuxBin = path.join(process.env.PREFIX, 'bin', systemNames[0]);
      if (fs.existsSync(termuxBin)) {
        try {
          fs.accessSync(termuxBin, fs.constants.X_OK);
          return termuxBin;
        } catch {}
      }
    }

    // Check system PATH
    const pathDirs = (process.env.PATH || '').split(path.delimiter);
    for (const p of pathDirs) {
      for (const name of systemNames) {
        const full = path.join(p, name);
        if (fs.existsSync(full)) {
          try {
            fs.accessSync(full, fs.constants.X_OK);
            return full;
          } catch {}
        }
      }
    }

    // Check cache in dataDir or local .cache
    if (this.dataDir) {
      const cached = path.join(this.dataDir, 'bin', systemNames[0]);
      if (fs.existsSync(cached)) {
        return cached;
      }
    }

    return null;
  }

  /**
   * Ensures the cloudflared executable is available on disk.
   */
  async ensureBinary() {
    const existing = this.resolveBinary();
    if (existing) return existing;

    const downloadUrl = CloudflareTunnel.getDownloadUrl();
    if (!downloadUrl) {
      throw new Error(`Unsupported platform/architecture for auto-download: ${process.platform}/${process.arch}`);
    }

    const targetDir = this.dataDir ? path.join(this.dataDir, 'bin') : path.resolve('.cache', 'bin');
    fs.mkdirSync(targetDir, { recursive: true, mode: 0o755 });
    const targetFile = path.join(targetDir, process.platform === 'win32' ? 'cloudflared.exe' : 'cloudflared');

    this.logger.log(`[cloudflare-tunnel] Downloading cloudflared binary from ${downloadUrl}...`);
    const res = await fetch(downloadUrl);
    if (!res.ok) {
      throw new Error(`Failed to download cloudflared: HTTP ${res.status}`);
    }

    const buffer = Buffer.from(await res.arrayBuffer());
    fs.writeFileSync(targetFile, buffer, { mode: 0o755 });
    this.logger.log(`[cloudflare-tunnel] cloudflared installed to ${targetFile}`);
    return targetFile;
  }

  /**
   * Spawns the cloudflared process and tracks connection state.
   */
  async start() {
    if (this.childProcess) {
      return this.url;
    }

    this._stopping = false;
    this.status = 'starting';

    let executable;
    try {
      executable = await this.ensureBinary();
    } catch (err) {
      this.status = 'error';
      this.lastError = err;
      this.logger.warn(`[cloudflare-tunnel] Cannot start tunnel: ${err.message}`);
      return null;
    }

    const args = ['tunnel', '--no-autoupdate'];
    if (this.token) {
      args.push('run', '--token', this.token);
    } else {
      args.push('--url', `http://${this.host}:${this.port}`);
    }

    const child = spawn(executable, args, {
      stdio: ['ignore', 'pipe', 'pipe'],
      env: { ...process.env },
    });

    this.childProcess = child;

    const urlRegex = /https:\/\/[a-zA-Z0-9-]+\.trycloudflare\.com/;

    const handleOutput = (chunk) => {
      const text = chunk.toString();
      if (!this.url && !this.token) {
        const match = text.match(urlRegex);
        if (match) {
          this.url = match[0];
          this.status = 'connected';
          this.logger.log(`[cloudflare-tunnel] Quick Tunnel connected: ${this.url}`);
          this.emit('connected', this.url);
        }
      }
      if (this.token && text.includes('Registered tunnel connection')) {
        this.status = 'connected';
        this.emit('connected', null);
      }
    };

    child.stdout.on('data', handleOutput);
    child.stderr.on('data', handleOutput);

    child.on('error', (err) => {
      this.status = 'error';
      this.lastError = err;
      this.emit('error', err);
    });

    child.on('exit', (code, signal) => {
      this.childProcess = null;
      this.status = 'disconnected';
      const prevUrl = this.url;
      this.url = null;

      if (!this._stopping && this.autoRestart && this.restartAttempts < this.maxRestartAttempts) {
        this.restartAttempts++;
        const backoff = Math.min(1000 * Math.pow(2, this.restartAttempts), 10000);
        this.logger.warn(`[cloudflare-tunnel] Process exited (${signal || code}). Restarting in ${backoff}ms...`);
        setTimeout(() => {
          if (!this._stopping) this.start();
        }, backoff).unref();
      }

      this.emit('exit', { code, signal, prevUrl });
    });

    return new Promise((resolve) => {
      if (this.token) {
        this.status = 'connected';
        return resolve(null);
      }

      const timer = setTimeout(() => {
        resolve(this.url);
      }, 10000);

      this.once('connected', (url) => {
        clearTimeout(timer);
        resolve(url);
      });
    });
  }

  /**
   * Gracefully terminates the tunnel.
   */
  async stop() {
    this._stopping = true;
    if (!this.childProcess) {
      this.status = 'disconnected';
      return;
    }

    return new Promise((resolve) => {
      const child = this.childProcess;
      this.childProcess = null;
      this.status = 'disconnected';
      this.url = null;

      const killTimeout = setTimeout(() => {
        try {
          child.kill('SIGKILL');
        } catch {}
        resolve();
      }, 3000);

      child.once('exit', () => {
        clearTimeout(killTimeout);
        resolve();
      });

      try {
        child.kill('SIGINT');
      } catch {
        clearTimeout(killTimeout);
        resolve();
      }
    });
  }

  /**
   * Health and telemetry snapshot.
   */
  getStatus() {
    return {
      enabled: Boolean(this.token || process.env.STREAMIFY_TUNNEL === '1' || process.env.CLOUDFLARE_TUNNEL === '1'),
      status: this.status,
      url: this.url,
      mode: this.token ? 'named' : 'quick',
      pid: this.childProcess?.pid || null,
      error: this.lastError?.message || null,
    };
  }
}
