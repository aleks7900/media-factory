import { describe, it, expect, vi } from 'vitest';
import { isBypassRoute, shouldCacheResponse, safeCachePut } from '../public/serviceWorker.js';

describe('Service Worker Cache Isolation & Defensive Downloads', () => {
  describe('isBypassRoute (Network-Only Routing)', () => {
    it('bypasses Media Factory production API download endpoints', () => {
      const url = new URL('https://alex-lab.md/media-factory-api/api/v1/bulk/batches/batch-123/results.zip');
      expect(isBypassRoute(url)).toBe(true);
    });

    it('bypasses standard /api/ download endpoints', () => {
      const url = new URL('https://alex-lab.md/api/v1/bulk/batches/batch-123/results.zip');
      expect(isBypassRoute(url)).toBe(true);
    });

    it('bypasses any ZIP archive or results.zip endpoint', () => {
      expect(isBypassRoute(new URL('https://alex-lab.md/media-factory/downloads/results.zip'))).toBe(true);
      expect(isBypassRoute(new URL('https://alex-lab.md/exports/archive.zip'))).toBe(true);
      expect(isBypassRoute(new URL('https://alex-lab.md/media-factory-api/v1/stock/packages/pkg-1.zip'))).toBe(true);
    });

    it('bypasses URLs with explicit download query parameter', () => {
      expect(isBypassRoute(new URL('https://alex-lab.md/media-factory/file?download=1'))).toBe(true);
    });

    it('does NOT bypass static assets (HTML, CSS, JS, fonts)', () => {
      expect(isBypassRoute(new URL('https://alex-lab.md/media-factory/assets/index.js'))).toBe(false);
      expect(isBypassRoute(new URL('https://alex-lab.md/media-factory/assets/style.css'))).toBe(false);
      expect(isBypassRoute(new URL('https://alex-lab.md/media-factory/index.html'))).toBe(false);
    });
  });

  describe('shouldCacheResponse (Attachment & Binary Protection)', () => {
    it('refuses to cache responses with Content-Disposition: attachment', () => {
      const headers = new Headers({
        'Content-Type': 'application/zip',
        'Content-Disposition': 'attachment; filename="cars-results.zip"',
      });
      const response = new Response(new Blob(['fake zip content']), { status: 200, headers });
      expect(shouldCacheResponse(response)).toBe(false);
    });

    it('refuses to cache application/zip or binary octet-streams', () => {
      const headers = new Headers({
        'Content-Type': 'application/zip',
      });
      const response = new Response(new Blob(['fake zip']), { status: 200, headers });
      expect(shouldCacheResponse(response)).toBe(false);

      const octetHeaders = new Headers({
        'Content-Type': 'application/octet-stream',
      });
      const octetResponse = new Response(new Blob(['data']), { status: 200, headers: octetHeaders });
      expect(shouldCacheResponse(octetResponse)).toBe(false);
    });

    it('refuses to cache responses with Cache-Control: no-store or no-cache', () => {
      const headers = new Headers({
        'Content-Type': 'text/html',
        'Cache-Control': 'no-store, no-cache, must-revalidate',
      });
      const response = new Response('<html></html>', { status: 200, headers });
      expect(shouldCacheResponse(response)).toBe(false);
    });

    it('permits caching of ordinary static 200 responses', () => {
      const headers = new Headers({
        'Content-Type': 'application/javascript',
        'Cache-Control': 'public, max-age=31536000',
      });
      const response = new Response('console.log("ok");', { status: 200, headers });
      expect(shouldCacheResponse(response)).toBe(true);
    });
  });

  describe('safeCachePut (Defensive Cache Failure Isolation)', () => {
    it('never calls Cache.put for attachment responses', async () => {
      const mockPut = vi.fn();
      const mockOpen = vi.fn().mockResolvedValue({ put: mockPut });
      vi.stubGlobal('caches', { open: mockOpen });

      const headers = new Headers({
        'Content-Disposition': 'attachment; filename="results.zip"',
        'Content-Type': 'application/zip',
      });
      const res = new Response(new Blob(['zip data']), { status: 200, headers });
      const req = new Request('https://alex-lab.md/media-factory-api/results.zip');

      await safeCachePut('test-cache', req, res);

      expect(mockOpen).not.toHaveBeenCalled();
      expect(mockPut).not.toHaveBeenCalled();
    });

    it('safely catches and absorbs Cache.put NetworkError without throwing', async () => {
      const mockPut = vi.fn().mockRejectedValue(
        new DOMException("Failed to execute 'put' on 'Cache': Cache.put() encountered a network error", 'NetworkError')
      );
      const mockOpen = vi.fn().mockResolvedValue({ put: mockPut });
      vi.stubGlobal('caches', { open: mockOpen });

      const headers = new Headers({
        'Content-Type': 'application/javascript',
      });
      const res = new Response('/* bundle */', { status: 200, headers });
      const req = new Request('https://alex-lab.md/media-factory/assets/bundle.js');

      // Must not throw or produce unhandled rejection
      await expect(safeCachePut('test-cache', req, res)).resolves.not.toThrow();
      expect(mockPut).toHaveBeenCalled();
    });
  });
});
