export type Row = { id: string; [key: string]: string | number | null | string[] };

export const API_BASE = (import.meta.env.VITE_API_BASE_URL ?? '/api').replace(/\/+$/, '');

export function apiUrl(path: string): string {
  const cleanPath = path.startsWith('/') ? path : `/${path}`;

  // If API_BASE ends with /api (e.g. /api or /media-factory-api/api)
  if (API_BASE.endsWith('/api')) {
    if (cleanPath.startsWith('/api/')) {
      return `${API_BASE}${cleanPath.substring(4)}`;
    }
    return `${API_BASE}${cleanPath}`;
  }

  // If API_BASE does not end with /api (e.g. /media-factory-api)
  if (cleanPath.startsWith('/api/') || cleanPath === '/api') {
    return `${API_BASE}${cleanPath}`;
  }
  return `${API_BASE}/api${cleanPath}`;
}

export async function api<T>(path: string, body?: unknown, key?: string, method?: string): Promise<T> {
  const response = await fetch(apiUrl(path), {
    method: method ?? (body === undefined ? 'GET' : 'POST'),
    headers: {'Content-Type': 'application/json', ...(key ? {'Idempotency-Key': key} : {})},
    ...(body === undefined ? {} : {body: JSON.stringify(body)})
  });
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error(error.detail || error.message || `Request failed (${response.status})`);
  }
  return response.json();
}
