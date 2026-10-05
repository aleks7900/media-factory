/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

declare module '*/serviceWorker.js' {
  export function isBypassRoute(url: URL): boolean;
  export function shouldCacheResponse(res: Response): boolean;
  export function safeCachePut(cacheName: string, req: Request, res: Response): Promise<void>;
}
