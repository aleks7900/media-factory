import {describe, it, expect, vi, beforeEach} from 'vitest';
import {
  api,
  apiUrl,
  setAuth,
  clearAuth,
  getAuthToken,
  getCurrentUser,
  subscribeUnauthorized,
  login,
  logout,
  checkAuth
} from './api';

describe('api client and url helper', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    clearAuth();
  });

  it('resolves apiUrl with default /api prefix', () => {
    expect(apiUrl('/v1/publishing/upload')).toBe('/api/v1/publishing/upload');
    expect(apiUrl('v1/publishing/upload')).toBe('/api/v1/publishing/upload');
    expect(apiUrl('/collections')).toBe('/api/collections');
    expect(apiUrl('/api/v1/publishing/upload')).toBe('/api/v1/publishing/upload');
  });

  it('makes fetch requests to apiUrl and parses json', async () => {
    const mockData = {id: '123', status: 'OK'};
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(mockData)
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await api<typeof mockData>('/v1/test', {foo: 'bar'}, 'idem-123', 'POST');
    expect(result).toEqual(mockData);
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/test',
      expect.objectContaining({
        method: 'POST',
        credentials: 'include',
        headers: expect.objectContaining({
          'Content-Type': 'application/json',
          'Idempotency-Key': 'idem-123'
        }),
        body: JSON.stringify({foo: 'bar'})
      })
    );
  });

  it('automatically attaches Authorization header when token is set', async () => {
    setAuth('mock-token-xyz', { username: 'admin', role: 'ROLE_ADMIN' });
    expect(getAuthToken()).toBe('mock-token-xyz');
    expect(getCurrentUser()?.username).toBe('admin');

    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ data: 'ok' })
    });
    vi.stubGlobal('fetch', fetchMock);

    await api('/v1/projects');
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/projects',
      expect.objectContaining({
        credentials: 'include',
        headers: expect.objectContaining({
          Authorization: 'Bearer mock-token-xyz'
        })
      })
    );
  });

  it('throws error with detail or message when response is not ok', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      json: () => Promise.resolve({detail: 'Validation failed'})
    });
    vi.stubGlobal('fetch', fetchMock);

    await expect(api('/v1/test')).rejects.toThrow('Validation failed');
  });

  it('notifies unauthorized subscribers and clears auth on 401', async () => {
    setAuth('expired-token', { username: 'admin', role: 'ROLE_ADMIN' });
    const onUnauthorized = vi.fn();
    const unsub = subscribeUnauthorized(onUnauthorized);

    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 401,
      json: () => Promise.resolve({detail: 'Session expired'})
    });
    vi.stubGlobal('fetch', fetchMock);

    await expect(api('/v1/protected')).rejects.toThrow('Session expired');
    expect(onUnauthorized).toHaveBeenCalled();
    expect(getAuthToken()).toBeNull();
    expect(getCurrentUser()).toBeNull();

    unsub();
  });

  it('performs login and stores authentication state', async () => {
    const loginData = {
      token: 'issued-jwt-123',
      username: 'admin',
      role: 'ROLE_ADMIN',
      expiresAt: '2026-10-06T12:00:00Z'
    };

    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(loginData)
    });
    vi.stubGlobal('fetch', fetchMock);

    const user = await login('admin', 'password123');
    expect(user.username).toBe('admin');
    expect(user.role).toBe('ROLE_ADMIN');
    expect(getAuthToken()).toBe('issued-jwt-123');
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/auth/login',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ username: 'admin', password: 'password123' })
      })
    );
  });

  it('performs logout and clears authentication state', async () => {
    setAuth('token-to-clear', { username: 'admin', role: 'ROLE_ADMIN' });
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ message: 'Logged out' })
    });
    vi.stubGlobal('fetch', fetchMock);

    await logout();
    expect(getAuthToken()).toBeNull();
    expect(getCurrentUser()).toBeNull();
  });

  it('checks auth status with checkAuth()', async () => {
    setAuth('active-token', { username: 'admin', role: 'ROLE_ADMIN' });
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ username: 'admin', role: 'ROLE_ADMIN' })
    });
    vi.stubGlobal('fetch', fetchMock);

    const user = await checkAuth();
    expect(user.username).toBe('admin');
    expect(user.role).toBe('ROLE_ADMIN');
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/auth/me',
      expect.objectContaining({
        headers: expect.objectContaining({
          Authorization: 'Bearer active-token'
        })
      })
    );
  });
});
