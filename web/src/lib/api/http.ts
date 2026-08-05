export const MOCK = import.meta.env.VITE_MOCK === '1';

export class ApiError extends Error {
  constructor(
    public status: number,
    public key: string,
    public detail: string,
  ) {
    super(`${status} ${key}${detail ? `: ${detail}` : ''}`);
  }
}

let onUnauthorized: () => void = () => {};

export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler;
}

/** Fetch wrapper: CSRF header on mutations, 401 → session expiry, error envelope decoding. */
export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const method = init?.method ?? 'GET';
  const headers = new Headers(init?.headers);
  if (method !== 'GET' && method !== 'HEAD') headers.set('X-Realism-Csrf', '1');
  const response = await fetch(path, { ...init, headers });
  if (response.status === 401) {
    onUnauthorized();
    throw new ApiError(401, 'unauthorized', '');
  }
  if (!response.ok) {
    let body: { error?: string; detail?: string } = {};
    try {
      body = await response.json();
    } catch {
      /* non-JSON error body */
    }
    throw new ApiError(response.status, body.error ?? 'error', body.detail ?? '');
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}
