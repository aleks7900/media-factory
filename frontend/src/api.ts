export type Row = { id: string; [key: string]: string | number | null | string[] };

export async function api<T>(path: string, body?: unknown, key?: string, method?: string): Promise<T> {
    const response = await fetch('/api' + path, {
        method: method ?? (body === undefined ? 'GET' : 'POST'),
        headers: {'Content-Type': 'application/json', ...(key ? {'Idempotency-Key': key} : {})}, ...(body === undefined ? {} : {body: JSON.stringify(body)})
    });
    if (!response.ok) {
        const error = await response.json().catch(() => ({}));
        throw new Error(error.detail || error.message || `Request failed (${response.status})`);
    }
    return response.json();
}
