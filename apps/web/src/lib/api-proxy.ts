import { NextRequest, NextResponse } from 'next/server';

const cookieName = 'starcon_session';
const legacyCookieName = 'starcon_catalog_session';
const allowed =
  /^(auth\/(login|logout|session|memberships|switch)|identity\/(?:permissions|owner-transfer|(?:roles|members|departments|children)(?:\/[0-9a-f-]+)?)|catalog\/(products(?:\/[0-9a-f-]+(?:\/(?:attributes|skus)(?:\/[0-9a-f-]+)?)?)?|records\/(research|compliance|assets)(?:\/[0-9a-f-]+)?))$/;

export async function proxy(request: NextRequest, context: { params: { path: string[] } }) {
  const path = context.params.path.join('/');
  if (!allowed.test(path)) return NextResponse.json({ message: '接口不存在' }, { status: 404 });
  if (
    !['GET', 'HEAD'].includes(request.method) &&
    request.headers.get('origin') !== request.nextUrl.origin
  ) {
    return NextResponse.json({ message: '请求来源不正确' }, { status: 403 });
  }
  const identity = path.startsWith('auth/') || path.startsWith('identity/');
  const base = identity
    ? (process.env.IDENTITY_URL ?? 'http://127.0.0.1:8081')
    : (process.env.CATALOG_URL ?? 'http://127.0.0.1:8082');
  const body = request.method === 'GET' ? undefined : await request.text();
  if (body && body.length > 200_000)
    return NextResponse.json({ message: '请求内容过大' }, { status: 413 });
  try {
    const upstream = await fetch(`${base}/${path}${request.nextUrl.search}`, {
      method: request.method,
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${request.cookies.get(cookieName)?.value ?? request.cookies.get(legacyCookieName)?.value ?? ''}`,
      },
      ...(body ? { body } : {}),
      cache: 'no-store',
      signal: AbortSignal.timeout(15_000),
    });
    const text = await upstream.text();
    const data = text ? JSON.parse(text) : null;
    let token: string | undefined;
    if (path === 'auth/login' && upstream.ok && data?.token) {
      token = data.token;
      delete data.token;
    }
    const response = NextResponse.json(data, {
      status: upstream.status,
      headers: { 'Cache-Control': 'no-store' },
    });
    if (token) {
      response.cookies.delete(legacyCookieName);
      response.cookies.set(cookieName, token, {
        httpOnly: true,
        sameSite: 'strict',
        secure: request.nextUrl.protocol === 'https:',
        path: '/',
        maxAge: 8 * 3600,
      });
    }
    if ((path === 'auth/logout' && upstream.ok) || upstream.status === 401) {
      response.cookies.delete(cookieName);
      response.cookies.delete(legacyCookieName);
    }
    return response;
  } catch {
    return NextResponse.json({ message: '服务暂时无法连接，请稍后重试' }, { status: 503 });
  }
}
