/// <reference lib="webworker" />
/* eslint-disable no-restricted-globals */
const SW_VERSION = 'v1.0.3';

const STATIC_CACHE = `mediafactory-static-${SW_VERSION}`;
const HTML_CACHE = `mediafactory-html-${SW_VERSION}`;

const STATIC_ASSETS = [
  '/media-factory/',
  '/media-factory/index.html',
  '/media-factory/favicon.ico'
];

self.addEventListener('install', (event) => {
  event.waitUntil((async () => {
    try {
      const cache = await caches.open(STATIC_CACHE);
      await Promise.all(
        STATIC_ASSETS.map(async (url) => {
          try {
            await cache.add(new Request(url, {cache: 'reload'}));
          } catch (e) {
            console.warn('[SW] skip caching', url, e);
          }
        })
      );
    } catch (e) {
      console.warn('[SW] install cache error:', e);
    }
  })());
  self.skipWaiting?.();
});

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    try {
      const keys = await caches.keys();
      await Promise.all(
        keys.filter(k => ![STATIC_CACHE, HTML_CACHE].includes(k)).map(k => caches.delete(k))
      );
    } catch (e) {
      console.warn('[SW] activate cleanup error:', e);
    }
    self.clients?.claim?.();
  })());
});

function isHttp(req) {
  const u = typeof req === 'string' ? req : req.url;
  const p = new URL(u, self.location.origin).protocol;
  return p === 'http:' || p === 'https:';
}

function isNav(req) {
  return req.mode === 'navigate' || (req.method === 'GET' && req.headers.get('accept')?.includes('text/html'));
}

/**
 * Determine if a request should bypass the Service Worker completely (network-only).
 * Never intercept or cache:
 * - /api/ or /media-factory-api/ or any endpoint containing /api/
 * - Result ZIP downloads, attachments, or dynamic media streams
 * - Images/uploads dynamically managed by backend
 */
export function isBypassRoute(url) {
  const p = url.pathname.toLowerCase();
  return (
    p.startsWith('/api/') ||
    p.startsWith('/media-factory-api/') ||
    p.includes('/api/') ||
    p.startsWith('/images/') ||
    p.startsWith('/uploads/') ||
    p.endsWith('.zip') ||
    p.includes('/results.zip') ||
    url.searchParams.has('download')
  );
}

/**
 * Safe check before attempting to put any response into Cache Storage.
 * Do NOT cache:
 * - Responses with Content-Disposition: attachment
 * - application/zip or binary download octet-streams
 * - Responses with Cache-Control: no-store or private dynamic responses
 * - Non-200 responses or partial (206) responses
 */
export function shouldCacheResponse(res) {
  if (!res || res.status !== 200) {
    return false;
  }

  const disposition = res.headers.get('content-disposition');
  if (disposition && disposition.toLowerCase().includes('attachment')) {
    return false;
  }

  const contentType = (res.headers.get('content-type') || '').toLowerCase();
  if (
    contentType.includes('application/zip') ||
    contentType.includes('application/x-zip') ||
    contentType.includes('application/octet-stream')
  ) {
    return false;
  }

  const cacheControl = (res.headers.get('cache-control') || '').toLowerCase();
  if (cacheControl.includes('no-store') || cacheControl.includes('no-cache')) {
    return false;
  }

  return true;
}

/**
 * Defensive cache write: A failure in cache.put() must NEVER break
 * the original network response or cause an unhandled promise rejection.
 */
export async function safeCachePut(cacheName, req, res) {
  if (!shouldCacheResponse(res)) {
    return;
  }
  try {
    const cache = await caches.open(cacheName);
    await cache.put(req, res.clone());
  } catch (err) {
    console.warn('[SW] Cache.put ignored error:', err);
  }
}

// SWR for static assets
async function swr(req, cacheName) {
  let cached = null;
  try {
    const cache = await caches.open(cacheName);
    cached = await cache.match(req);
  } catch {
    cached = null;
  }
  try {
    const net = await fetch(req);
    if (net && net.ok) {
      safeCachePut(cacheName, req, net).catch(() => {});
    }
    return net;
  } catch {
    if (cached) return cached;
    return new Response('Offline', {status: 503, headers: {'Content-Type': 'text/plain; charset=utf-8'}});
  }
}

// Network-first for HTML navigation
async function networkFirstHtml(req) {
  try {
    const net = await fetch(req);
    if (net && net.ok) {
      safeCachePut(HTML_CACHE, req, net).catch(() => {});
    }
    return net;
  } catch {
    try {
      const cache = await caches.open(HTML_CACHE);
      const cached = await cache.match(req);
      if (cached) return cached;
      const index = await caches.match('/media-factory/index.html') || await caches.match('/index.html');
      return index || new Response('Offline', {status: 503});
    } catch {
      return new Response('Offline', {status: 503});
    }
  }
}

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (!isHttp(req) || req.method !== 'GET') return;

  const url = new URL(req.url);

  // Bypass API routes, ZIP downloads, and media endpoints completely (network-only)
  if (isBypassRoute(url)) {
    return;
  }

  if (isNav(req)) {
    event.respondWith(networkFirstHtml(req));
    return;
  }

  // Static assets (js/css/fonts/img)
  if (/\.(?:js|css|woff2?|ttf|otf|png|jpg|jpeg|webp|svg|gif|ico)$/i.test(url.pathname)) {
    event.respondWith(swr(req, STATIC_CACHE));
    return;
  }

  // Fallback: match cache, else fetch
  event.respondWith(caches.match(req).then(c => c || fetch(req)).catch(() => fetch(req)));
});
