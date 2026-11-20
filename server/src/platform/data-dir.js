import os from 'node:os';
import path from 'node:path';
import fs from 'node:fs';

export function resolveDataDir(env = process.env) {
  if (env.STREAMIFY_DATA_DIR) {
    const dir = path.resolve(env.STREAMIFY_DATA_DIR);
    ensureDir(dir);
    return initSubdirs(dir);
  }

  const home = os.homedir();
  const platform = process.platform;
  const isTermux = Boolean(env.TERMUX_VERSION || (env.PREFIX && env.PREFIX.includes('com.termux')));

  let baseDir;
  if (isTermux) {
    baseDir = path.join(home, '.cache', 'streamify', 'server');
  } else if (platform === 'win32') {
    const localAppData = env.LOCALAPPDATA || path.join(home, 'AppData', 'Local');
    baseDir = path.join(localAppData, 'Streamify', 'server');
  } else if (platform === 'darwin') {
    baseDir = path.join(home, 'Library', 'Caches', 'Streamify', 'server');
  } else {
    // Linux / BSD / XDG
    const xdgCache = env.XDG_CACHE_HOME || path.join(home, '.cache');
    baseDir = path.join(xdgCache, 'streamify', 'server');
  }

  ensureDir(baseDir);
  return initSubdirs(baseDir);
}

function ensureDir(dir) {
  try {
    fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  } catch (err) {
    if (err.code !== 'EEXIST') {
      console.error(`[data-dir] Failed to create directory: ${dir}`, err);
    }
  }
}

function initSubdirs(baseDir) {
  const dirs = {
    root: baseDir,
    streams: path.join(baseDir, 'streams'),
    thumbs: path.join(baseDir, 'thumbs'),
    metadata: path.join(baseDir, 'metadata'),
  };
  ensureDir(dirs.streams);
  ensureDir(dirs.thumbs);
  ensureDir(dirs.metadata);
  return dirs;
}
