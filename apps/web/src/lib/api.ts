export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
    /**
     * Parsed response body, kept so callers can read structured detail.
     *
     * <p>3.1.3.2 组合唯一性校验要靠它：后端在 409 的 ProblemDetail 上挂 `conflicts` 结构化清单，
     * 只留一句 message 的话界面就退化成"有重复"三个字，运营没法按条改。
     */
    public body: unknown = null,
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
  { prefix: 'warehouse/', route: 'warehouse' },
];

let workspaceBlocked = false;

/** Block stale workspace callbacks while the session's tenant is being confirmed. */
export function blockWorkspaceRequests(blocked: boolean) {
  workspaceBlocked = blocked;
}

export async function api<T>(path: string, method = 'GET', data?: unknown): Promise<T> {
  if (workspaceBlocked && !['auth/session', 'auth/memberships', 'auth/switch'].includes(path))
    throw new ApiError(0, '正在确认工作空间，请稍后重试');
  const backend = backends.find((b) => path.startsWith(b.prefix));
  if (!backend) throw new ApiError(0, `未知的接口路径：${path}`);
  const response = await fetch(`/api/${backend.route}/${path}`, {
    method,
    headers: { 'Content-Type': 'application/json' },
    ...(data === undefined ? {} : { body: JSON.stringify(data) }),
    cache: 'no-store',
  });
  if (response.status === 401 && typeof window !== 'undefined')
    window.dispatchEvent(new Event('identity-expired'));
  const text = await response.text();
  let result: unknown = null;
  try {
    result = text ? JSON.parse(text) : null;
  } catch {
    throw new ApiError(response.ok ? 0 : response.status, '服务响应无法解析，请重试');
  }
  if (!response.ok) {
    const body = result as { detail?: string; message?: string } | null;
    throw new ApiError(
      response.status,
      body?.detail ?? body?.message ?? '保存失败，请检查输入后重试',
      result,
    );
  }
  return result as T;
}
