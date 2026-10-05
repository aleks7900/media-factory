export type Row = { id: string; [key: string]: string | number | null | string[] };

export type AuthUser = {
  username: string;
  role: string;
  token?: string;
  expiresAt?: string;
};

export const API_BASE = (import.meta.env.VITE_API_BASE_URL ?? '/api').replace(/\/+$/, '');

const AUTH_TOKEN_KEY = 'media_factory_jwt_token';
const AUTH_USER_KEY = 'media_factory_auth_user';

let currentToken: string | null = (typeof window !== 'undefined' && window.sessionStorage)
  ? window.sessionStorage.getItem(AUTH_TOKEN_KEY)
  : null;

let currentUser: AuthUser | null = (typeof window !== 'undefined' && window.sessionStorage)
  ? (() => {
      try {
        const raw = window.sessionStorage.getItem(AUTH_USER_KEY);
        return raw ? JSON.parse(raw) : null;
      } catch {
        return null;
      }
    })()
  : null;

type UnauthorizedHandler = () => void;
const unauthorizedHandlers: Set<UnauthorizedHandler> = new Set();

export function subscribeUnauthorized(handler: UnauthorizedHandler): () => void {
  unauthorizedHandlers.add(handler);
  return () => {
    unauthorizedHandlers.delete(handler);
  };
}

function notifyUnauthorized() {
  clearAuth();
  unauthorizedHandlers.forEach(handler => {
    try {
      handler();
    } catch (err) {
      console.error('Error in unauthorized handler:', err);
    }
  });
}

export function getAuthToken(): string | null {
  return currentToken;
}

export function getCurrentUser(): AuthUser | null {
  return currentUser;
}

export function setAuth(token: string | null, user: AuthUser | null) {
  currentToken = token;
  currentUser = user;
  if (typeof window !== 'undefined' && window.sessionStorage) {
    if (token) {
      window.sessionStorage.setItem(AUTH_TOKEN_KEY, token);
    } else {
      window.sessionStorage.removeItem(AUTH_TOKEN_KEY);
    }
    if (user) {
      window.sessionStorage.setItem(AUTH_USER_KEY, JSON.stringify(user));
    } else {
      window.sessionStorage.removeItem(AUTH_USER_KEY);
    }
  }
}

export function clearAuth() {
  currentToken = null;
  currentUser = null;
  if (typeof window !== 'undefined' && window.sessionStorage) {
    window.sessionStorage.removeItem(AUTH_TOKEN_KEY);
    window.sessionStorage.removeItem(AUTH_USER_KEY);
  }
}

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
  const isFormData = typeof FormData !== 'undefined' && body instanceof FormData;

  const headers: Record<string, string> = {};
  if (!isFormData) {
    headers['Content-Type'] = 'application/json';
  }
  if (key) {
    headers['Idempotency-Key'] = key;
  }
  if (currentToken) {
    headers['Authorization'] = `Bearer ${currentToken}`;
  }

  const response = await fetch(apiUrl(path), {
    method: method ?? (body === undefined ? 'GET' : 'POST'),
    headers,
    credentials: 'include',
    ...(body === undefined ? {} : {body: isFormData ? body : JSON.stringify(body)})
  });

  if (response.status === 401) {
    if (!path.includes('/auth/login')) {
      notifyUnauthorized();
    }
    const error = await response.json().catch(() => ({}));
    throw new Error(error.detail || error.message || 'Unauthorized: Please log in with admin credentials');
  }

  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error(error.detail || error.message || `Request failed (${response.status})`);
  }
  return response.json();
}

export async function login(username: string, password: string): Promise<AuthUser> {
  const result = await api<{ token: string; username: string; role: string; expiresAt: string }>(
    '/auth/login',
    { username, password },
    undefined,
    'POST'
  );
  const user: AuthUser = {
    username: result.username,
    role: result.role,
    token: result.token,
    expiresAt: result.expiresAt
  };
  setAuth(result.token, user);
  return user;
}

export async function logout(): Promise<void> {
  try {
    await api('/auth/logout', {}, undefined, 'POST');
  } finally {
    clearAuth();
    notifyUnauthorized();
  }
}

export async function checkAuth(): Promise<AuthUser> {
  const result = await api<{ username: string; role: string }>('/auth/me');
  const user: AuthUser = {
    username: result.username,
    role: result.role,
    token: currentToken ?? undefined
  };
  setAuth(currentToken, user);
  return user;
}
