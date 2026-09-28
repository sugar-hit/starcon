export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}
export async function api<T>(path: string, method = 'GET', data?: unknown): Promise<T> {
  const response = await fetch(
    `${path.startsWith('catalog/') ? '/api/catalog' : '/api/iam'}/${path}`,
    {
      method,
      headers: { 'Content-Type': 'application/json' },
      ...(data === undefined ? {} : { body: JSON.stringify(data) }),
      cache: 'no-store',
    },
  );
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
