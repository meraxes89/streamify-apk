import { DatabaseSync } from 'node:sqlite';
import path from 'node:path';
import { resolveDataDir } from '../../platform/data-dir.js';

export class StreamStore {
  /**
   * @param {Object} [options]
   * @param {string} [options.dbPath] - Path to SQLite database file.
   * @param {boolean} [options.inMemory=false] - Use in-memory SQLite (for tests).
   */
  constructor({ dbPath = null, inMemory = false } = {}) {
    this.inMemory = inMemory;
    if (inMemory) {
      this.dbPath = ':memory:';
    } else if (dbPath) {
      this.dbPath = path.resolve(dbPath);
    } else {
      const dirs = resolveDataDir();
      this.dbPath = path.join(dirs.root, 'store.sqlite');
    }

    this.db = new DatabaseSync(this.dbPath);
    this.initPragmas();
    this.initSchema();
    this.prepareStatements();
  }

  initPragmas() {
    if (!this.inMemory) {
      this.db.exec('PRAGMA journal_mode = WAL;');
      this.db.exec('PRAGMA synchronous = NORMAL;');
    }
    this.db.exec('PRAGMA temp_store = MEMORY;');
  }

  initSchema() {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS resolved_urls (
        videoId TEXT PRIMARY KEY,
        itag INTEGER NOT NULL,
        targetUrl TEXT NOT NULL,
        expiresAt INTEGER NOT NULL,
        client TEXT NOT NULL,
        mimeType TEXT,
        durationMs INTEGER,
        contentLength INTEGER,
        title TEXT,
        artist TEXT,
        createdAt INTEGER NOT NULL
      );
      CREATE INDEX IF NOT EXISTS idx_resolved_expires ON resolved_urls(expiresAt);

      CREATE TABLE IF NOT EXISTS player_cache (
        hash TEXT PRIMARY KEY,
        sigFn TEXT NOT NULL,
        nsigFn TEXT NOT NULL,
        updatedAt INTEGER NOT NULL
      );

      CREATE TABLE IF NOT EXISTS visitor_tokens (
        id TEXT PRIMARY KEY,
        visitorData TEXT NOT NULL,
        expiresAt INTEGER NOT NULL,
        updatedAt INTEGER NOT NULL
      );

      CREATE TABLE IF NOT EXISTS nsig_lru (
        input TEXT PRIMARY KEY,
        output TEXT NOT NULL,
        lastUsedAt INTEGER NOT NULL
      );
      CREATE INDEX IF NOT EXISTS idx_nsig_last_used ON nsig_lru(lastUsedAt);

      CREATE TABLE IF NOT EXISTS po_tokens (
        videoId TEXT PRIMARY KEY,
        token TEXT NOT NULL,
        mintedAt INTEGER NOT NULL,
        expiresAt INTEGER NOT NULL
      );
      CREATE INDEX IF NOT EXISTS idx_po_expires ON po_tokens(expiresAt);
    `);
  }

  prepareStatements() {
    this.stmts = {
      // resolved_urls
      getResolved: this.db.prepare('SELECT * FROM resolved_urls WHERE videoId = ?'),
      upsertResolved: this.db.prepare(`
        INSERT INTO resolved_urls (videoId, itag, targetUrl, expiresAt, client, mimeType, durationMs, contentLength, title, artist, createdAt)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(videoId) DO UPDATE SET
          itag=excluded.itag,
          targetUrl=excluded.targetUrl,
          expiresAt=excluded.expiresAt,
          client=excluded.client,
          mimeType=excluded.mimeType,
          durationMs=excluded.durationMs,
          contentLength=excluded.contentLength,
          title=excluded.title,
          artist=excluded.artist,
          createdAt=excluded.createdAt
      `),
      deleteResolved: this.db.prepare('DELETE FROM resolved_urls WHERE videoId = ?'),
      purgeExpiredResolved: this.db.prepare('DELETE FROM resolved_urls WHERE expiresAt <= ?'),

      // player_cache
      getPlayer: this.db.prepare('SELECT * FROM player_cache WHERE hash = ?'),
      upsertPlayer: this.db.prepare(`
        INSERT INTO player_cache (hash, sigFn, nsigFn, updatedAt)
        VALUES (?, ?, ?, ?)
        ON CONFLICT(hash) DO UPDATE SET
          sigFn=excluded.sigFn,
          nsigFn=excluded.nsigFn,
          updatedAt=excluded.updatedAt
      `),

      // visitor_tokens
      getVisitor: this.db.prepare('SELECT * FROM visitor_tokens WHERE id = ?'),
      upsertVisitor: this.db.prepare(`
        INSERT INTO visitor_tokens (id, visitorData, expiresAt, updatedAt)
        VALUES (?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET
          visitorData=excluded.visitorData,
          expiresAt=excluded.expiresAt,
          updatedAt=excluded.updatedAt
      `),

      // nsig_lru
      getNsig: this.db.prepare('SELECT output FROM nsig_lru WHERE input = ?'),
      touchNsig: this.db.prepare('UPDATE nsig_lru SET lastUsedAt = ? WHERE input = ?'),
      upsertNsig: this.db.prepare(`
        INSERT INTO nsig_lru (input, output, lastUsedAt)
        VALUES (?, ?, ?)
        ON CONFLICT(input) DO UPDATE SET
          output=excluded.output,
          lastUsedAt=excluded.lastUsedAt
      `),
      countNsig: this.db.prepare('SELECT COUNT(*) as count FROM nsig_lru'),
      evictOldestNsig: this.db.prepare(`
        DELETE FROM nsig_lru WHERE input IN (
          SELECT input FROM nsig_lru ORDER BY lastUsedAt ASC LIMIT ?
        )
      `),

      // po_tokens
      getPoToken: this.db.prepare('SELECT token, expiresAt FROM po_tokens WHERE videoId = ?'),
      upsertPoToken: this.db.prepare(`
        INSERT INTO po_tokens (videoId, token, mintedAt, expiresAt)
        VALUES (?, ?, ?, ?)
        ON CONFLICT(videoId) DO UPDATE SET
          token=excluded.token,
          mintedAt=excluded.mintedAt,
          expiresAt=excluded.expiresAt
      `),
      deletePoToken: this.db.prepare('DELETE FROM po_tokens WHERE videoId = ?'),
      purgeExpiredPo: this.db.prepare('DELETE FROM po_tokens WHERE expiresAt <= ?'),
    };
  }

  /**
   * Get resolved URL descriptor if valid and not expiring within safetyMarginMs.
   * @param {string} videoId
   * @param {number} [safetyMarginMs=60000] - Default 60s safety buffer.
   * @returns {Object|null}
   */
  getResolvedUrl(videoId, safetyMarginMs = 60_000) {
    if (!videoId) return null;
    try {
      const row = this.stmts.getResolved.get(videoId);
      if (!row) return null;

      const now = Date.now();
      if (row.expiresAt <= now + safetyMarginMs) {
        this.deleteResolvedUrl(videoId);
        return null;
      }

      return {
        videoId: row.videoId,
        itag: row.itag,
        targetUrl: row.targetUrl,
        expiresAt: row.expiresAt,
        client: row.client,
        mimeType: row.mimeType,
        durationMs: row.durationMs,
        contentLength: row.contentLength,
        title: row.title,
        artist: row.artist,
        cached: true,
        cachedAt: row.createdAt,
      };
    } catch {
      return null;
    }
  }

  /**
   * Cache a resolved URL descriptor.
   * @param {Object} desc
   */
  setResolvedUrl(desc) {
    if (!desc || !desc.videoId || !desc.targetUrl) return;
    try {
      const now = Date.now();
      const expiresAt = desc.expiresAt || now + 5 * 3600 * 1000;
      this.stmts.upsertResolved.run(
        desc.videoId,
        desc.itag || 251,
        desc.targetUrl,
        expiresAt,
        desc.client || 'UNKNOWN',
        desc.mimeType || null,
        desc.durationMs || null,
        desc.contentLength || null,
        desc.title || null,
        desc.artist || null,
        now
      );
    } catch (err) {
      console.warn('[StreamStore] Failed to setResolvedUrl:', err.message);
    }
  }

  deleteResolvedUrl(videoId) {
    if (!videoId) return;
    try {
      this.stmts.deleteResolved.run(videoId);
    } catch {}
  }

  purgeExpired(now = Date.now()) {
    try {
      this.stmts.purgeExpiredResolved.run(now);
      this.stmts.purgeExpiredPo.run(now);
    } catch {}
  }

  getPlayer(hash) {
    if (!hash) return null;
    try {
      return this.stmts.getPlayer.get(hash) || null;
    } catch {
      return null;
    }
  }

  setPlayer(hash, sigFn, nsigFn) {
    if (!hash || (!sigFn && !nsigFn)) return;
    try {
      this.stmts.upsertPlayer.run(hash, sigFn || '', nsigFn || '', Date.now());
    } catch (err) {
      console.warn('[StreamStore] Failed to setPlayer:', err.message);
    }
  }

  getVisitor(id = 'default') {
    try {
      const row = this.stmts.getVisitor.get(id);
      if (!row) return null;
      if (row.expiresAt <= Date.now()) {
        return null;
      }
      return row.visitorData;
    } catch {
      return null;
    }
  }

  setVisitor(id = 'default', visitorData, ttlMs = 24 * 3600 * 1000) {
    if (!visitorData) return;
    try {
      const now = Date.now();
      this.stmts.upsertVisitor.run(id, visitorData, now + ttlMs, now);
    } catch (err) {
      console.warn('[StreamStore] Failed to setVisitor:', err.message);
    }
  }

  getNsig(input) {
    if (!input) return null;
    try {
      const row = this.stmts.getNsig.get(input);
      if (!row) return null;
      this.stmts.touchNsig.run(Date.now(), input);
      return row.output;
    } catch {
      return null;
    }
  }

  setNsig(input, output, maxEntries = 512) {
    if (!input || !output) return;
    try {
      const now = Date.now();
      this.stmts.upsertNsig.run(input, output, now);

      const countRow = this.stmts.countNsig.get();
      if (countRow && countRow.count > maxEntries) {
        const excess = countRow.count - maxEntries;
        this.stmts.evictOldestNsig.run(excess);
      }
    } catch (err) {
      console.warn('[StreamStore] Failed to setNsig:', err.message);
    }
  }

  getPoToken(videoId) {
    if (!videoId) return null;
    try {
      const row = this.stmts.getPoToken.get(videoId);
      if (!row) return null;
      if (row.expiresAt <= Date.now()) {
        this.stmts.deletePoToken.run(videoId);
        return null;
      }
      return row.token;
    } catch {
      return null;
    }
  }

  setPoToken(videoId, token, ttlSecs = 6 * 3600) {
    if (!videoId || !token) return;
    try {
      const now = Date.now();
      const expiresAt = now + ttlSecs * 1000;
      this.stmts.upsertPoToken.run(videoId, token, now, expiresAt);
    } catch (err) {
      console.warn('[StreamStore] Failed to setPoToken:', err.message);
    }
  }

  close() {
    try {
      this.db.close();
    } catch {}
  }
}
