import {describe, it, expect, vi, beforeEach} from 'vitest';
import {api, apiUrl, API_BASE} from './api';

describe('api client and url helper', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
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
        headers: expect.objectContaining({
          'Content-Type': 'application/json',
          'Idempotency-Key': 'idem-123'
        }),
        body: JSON.stringify({foo: 'bar'})
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
});
