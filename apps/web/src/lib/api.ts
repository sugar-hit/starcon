export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}

/**
 * Backend service routing.
 *
 * <p>Each service is reached through /api/<service> so the browser never holds a backend address.
 * `auth` and `identity` are the identity service, `catalog` is 03 商品主数据, and `channel` is
 * 02 渠道与店铺. Callers pass the path exactly as the backend exposes it (e.g. `identity/roles`).
 */
const backends: readonly { prefix: string; route: string }[] = [
  { prefix: 'auth/', route: 'iam' },
  { prefix: 'identity/', route: 'iam' },
  { prefix: 'catalog/', route: 'catalog' },
  { prefix: 'channel/', route: 'channel' },
];

export async function api<T>(path: string, method = 'GET', data?: unknown): Promise<T> {
  const backend = backends.find((b) => path.startsWith(b.prefix));
  if (!backend) throw new ApiError(0, `未知的接口路径：${path}`);
  const response = await fetch(`/api/${backend.route}/${path}`, {
    method,
    headers: { 'Content-Type': 'application/json' },
    ...(data === undefined ? {} : { body: JSON.stringify(data) }),
    cache: 'no-store',
  });
  const result: unknown = await response.json();
  if (response.status === 401 && typeof window !== 'undefined')
    window.dispatchEvent(new Event('identity-expired'));
  if (!response.ok) {
    const body = result as { detail?: string; message?: string } | null;
    throw new ApiError(
      response.status,
      body?.detail ?? body?.message ?? '保存失败，请检查输入后重试',
    );
  }
  return result as T;
}
