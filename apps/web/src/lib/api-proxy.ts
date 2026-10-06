import { NextRequest, NextResponse } from 'next/server';

const cookieName = 'starcon_session';
const legacyCookieName = 'starcon_catalog_session';

/**
 * Upstream routing.
 *
 * <p>Each backend service owns a prefix and its own database; the browser never talks to them
 * directly. A path is only proxied when it matches a service's allowlist — unknown paths must 404
 * here rather than being forwarded, so a typo cannot reach a service that would treat it as valid.
 *
 * <p>The full path is forwarded upstream, prefix included: the services expose their own namespace
 * (`/identity/roles`, `/catalog/products`) and the browser's /api mount is only a local alias.
 */
const services: readonly {
  prefix: string;
  pattern: RegExp;
  env: string;
  fallback: string;
}[] = [
  {
    prefix: 'auth/',
    env: 'IDENTITY_URL',
    fallback: 'http://127.0.0.1:8081',
    pattern: /^(login|logout|session|memberships|switch)$/,
  },
  {
    prefix: 'identity/',
    env: 'IDENTITY_URL',
    fallback: 'http://127.0.0.1:8081',
    pattern:
      // audit 是 17.5.3.1 审计日志：列表、变更详情与 CSV 导出。
      // 导出走 GET + query（与列表同一套条件），所以 allowlist 只需放行读路径。
      /^(permissions|owner-transfer|legal-entities(?:\/[0-9a-f-]+)?|legal-entities\/references|(?:roles|members|departments|children)(?:\/[0-9a-f-]+)?|audit(?:\/(?:export|[0-9a-f-]+))?)$/,
  },
  {
    prefix: 'catalog/',
    env: 'CATALOG_URL',
    fallback: 'http://127.0.0.1:8082',
    pattern:
      // skus 是 3.1.2.1 SKU 列表：分页检索、能力声明、批量操作与 CSV 导出。
      // unit-conversion 是 3.1.2.4 单位与换算：单位字典、SKU 换算版本、基础数量换算与单据快照。
      //   units 后面可选一段 id（字典行的更新/删除）；skus/{id} 取面板，skus/{id}/conversions 建换算版本；
      //   snapshots 后面不接 id —— 按 ?documentRef= 查询，快照是按单据号寻址的不可变行。
      /^(products(?:\/[0-9a-f-]+(?:\/(?:attributes|skus)(?:\/[0-9a-f-]+)?)?)?|records\/(research|compliance|assets)(?:\/[0-9a-f-]+)?|skus(?:\/(?:capabilities|batch|export))?|unit-conversion(?:\/(?:units(?:\/[0-9a-f-]+)?|overview|skus\/[0-9a-f-]+(?:\/conversions)?|convert|snapshots))?)$/,
  },
  {
    // 02 渠道与店铺：平台目录、授权连接、店铺与站点。
    // health/reminders 属于连接健康，marketplace 挂在店铺下，transfer 是负责人转让。
    // platforms/{code}/enums 后面可选一段 id：租户映射覆盖按 id 删除。
    // oauth/* 是 2.2.1.2 授权向导：callback 由平台重定向直接打进来（浏览器导航，GET），
    // sessions 后面可选一段 id 与 shops/confirm/cancel。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^(platforms(?:\/[A-Za-z0-9_]+\/enums(?:\/[0-9a-f-]+)?)?|connections(?:\/(?:reminders|[0-9a-f-]+(?:\/(?:credential|health|revoke))?))?|shops(?:\/[0-9a-f-]+(?:\/(?:marketplaces(?:\/[0-9a-f-]+)?|transfer))?)?|sync-cursors(?:\/[0-9a-f-]+(?:\/(?:pause|resume|rewind|trigger))?)?|sync-runs(?:\/[0-9a-f-]+(?:\/(?:complete|resolve))?)?|sync-failures(?:\/summary|[0-9a-f-]+\/replay)?|platform-operations(?:\/[0-9a-f-]+(?:\/(?:start|succeed|fail|unknown|verify))?)?|oauth\/(?:callback|sessions(?:\/[0-9a-f-]+(?:\/(?:shops|confirm|cancel))?)?))$/,
  },
];

function resolve(path: string) {
  for (const service of services) {
    if (!path.startsWith(service.prefix)) continue;
    if (service.pattern.test(path.slice(service.prefix.length)))
      return { base: process.env[service.env] ?? service.fallback };
  }
  return null;
}

export async function proxy(request: NextRequest, context: { params: { path: string[] } }) {
  const path = context.params.path.join('/');
  const target = resolve(path);
  if (!target) return NextResponse.json({ message: '接口不存在' }, { status: 404 });
  if (
    !['GET', 'HEAD'].includes(request.method) &&
    request.headers.get('origin') !== request.nextUrl.origin
  ) {
    return NextResponse.json({ message: '请求来源不正确' }, { status: 403 });
  }
  const { base } = target;
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
    const contentType = upstream.headers.get('content-type') ?? '';
    // CSV 导出必须原样透传：走 JSON.parse 会把 CSV 解析失败变成 503，而且即使解析成功
    // 也会被重新序列化成 JSON，下载到的是一串引号转义的文本。
    // 只认 text/csv：JSON 接口一律走下面的正常分支，不受这里影响。
    if (text && contentType.includes('text/csv')) {
      const raw = new NextResponse(text, { status: upstream.status, headers: {} });
      raw.headers.set('Content-Type', contentType);
      const disposition = upstream.headers.get('content-disposition');
      if (disposition) raw.headers.set('Content-Disposition', disposition);
      raw.headers.set('Cache-Control', 'no-store');
      if (upstream.status === 401) {
        raw.cookies.delete(cookieName);
        raw.cookies.delete(legacyCookieName);
      }
      return raw;
    }
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
