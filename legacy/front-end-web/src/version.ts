/**
 * version.ts — the web build identity (Settings › About / Updates hub).
 *
 * Mirrors the Android BuildConfig surface the SettingsScreen reads. Bump
 * per phase so the "Check Updates" row has something honest to compare.
 */

export const APP_VERSION = '3.0.0-p3a';
export const APP_BUILD = 'P3a';
export const APP_EDITION = 'Flagship Open Source';
export const RELEASES_URL = 'https://github.com/meraxes89/streamify-apk/releases';
export const REPO_API = 'https://api.github.com/repos/meraxes89/streamify-apk';

export interface GithubRelease {
  name: string | null;
  tagName: string;
  publishedAt: string;
  htmlUrl: string;
  author: string;
  prerelease: boolean;
}

/** Latest releases (unauthenticated GitHub API — 60 req/h per IP is plenty). */
export async function fetchGithubReleases(): Promise<GithubRelease[]> {
  const res = await fetch(`${REPO_API}/releases?per_page=20`);
  if (!res.ok) throw new Error(`GitHub API ${res.status}`);
  const rows = (await res.json()) as Array<{
    name: string | null;
    tag_name: string;
    published_at: string;
    html_url: string;
    author?: { login?: string };
    prerelease: boolean;
  }>;
  return rows.map((r) => ({
    name: r.name,
    tagName: r.tag_name,
    publishedAt: r.published_at,
    htmlUrl: r.html_url,
    author: r.author?.login ?? 'ci',
    prerelease: r.prerelease,
  }));
}
