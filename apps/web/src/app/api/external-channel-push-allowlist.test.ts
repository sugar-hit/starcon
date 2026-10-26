import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET as iamGet } from './iam/[...path]/route';

/**
 * 1.3.2.2 外部渠道推送在 api-proxy 白名单里的**双向**断言。
 *
 * <p>🔴 **本车道改了 {@code api-proxy.ts}**（新增一条独立的
 * {@code prefix: 'identity/'} 元素，首 token {@code external-channel-push}）：
 * 外部渠道推送的读写需要新的端点，而白名单只在**浏览器端**生效 ——
 * 漏登记的表现是「typecheck / lint / vitest / Playwright **全部全绿**，页面却 404」。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从本模块自己的前端源码里<b>反向抽取真实调用点</b>再逐条断言被放行 ——
 *       写死一份清单只会验证「清单与白名单一致」，而真正会出事的是
 *       「源码加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：刻意不放行的形状仍然 404。最要紧的是
 *       {@code external-channel-push/templates} —— 18.5.1.1 模板管理未交付，
 *       本模块**不造模板表**顶替它，放行了就等于凭空多出一个后端不存在的映射。</li>
 * </ul>
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
// 本文件在 `app/api/` 下（比 `app/api/iam/` 浅一层），所以是 `../../components/...`。
const SOURCE = readFileSync(
  fileURLToPath(
    new URL('../../components/notifications/external-channel-push-workspace.tsx', import.meta.url),
  ),
  'utf8',
);

/**
 * 模板字面量里的 id 占位符替换成**真 UUID 形状**。
 *
 * <p>🔴 不能用 `'id'` 之类的占位：本模块的白名单把 id 段钉死成
 * **完整 UUID 形状**（`[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`），
 * 用非 UUID 占位会得到一个**本来就不该放行**的路径，断言会误判成「白名单坏了」。
 */
const UUID_PLACEHOLDER = '3f9a2b1c-4d5e-6f70-8192-a3b4c5d6e7f8';

afterEach(() => vi.unstubAllGlobals());

/** 走 identity 的出口发一次 GET，返回 HTTP 状态码。 */
async function call(path: string): Promise<number> {
  const [withoutQuery = ''] = path.split('?');
  const response = await iamGet(
    new NextRequest(`http://localhost:3000/api/iam/${path}`, {
      method: 'GET',
      headers: {
        origin: 'http://localhost:3000',
        cookie: 'starcon_session=private-session',
        // 伪造的租户头与内部键：代理必须忽略它们，转发时不带。
        'X-Tenant-Id': 'forged',
        'X-Internal-Key': 'forged',
      },
    }),
    { params: Promise.resolve({ path: withoutQuery.split('/') }) },
  );
  return response.status;
}

/** 把模板字面量展开成一条**具体路径**（id 位展开成真 UUID 形状，query 截掉）。 */
function expandTemplate(raw: string): string {
  return raw.replace(/\$\{[^}]*\}/g, UUID_PLACEHOLDER).replace(/\?.*$/, '').trim();
}

/**
 * 从源码里抽出真实调用点。
 *
 * <p>形如 {@code api<...>('identity/external-channel-push/channels')} 的字面量 ——
 * query 部分截掉（白名单判的是 path，query 由代理单独带上）。
 */
function realCallSites(): string[] {
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/api<[^>]*>\(\s*`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw) continue;
    const cleaned = expandTemplate(raw);
    if (!cleaned) continue;
    found.add(cleaned);
  }
  for (const match of SOURCE.matchAll(/api<[^>]*>\(\s*'([^']*)'/g)) {
    const raw = match[1];
    if (!raw) continue;
    const cleaned = raw.replace(/\?.*$/, '').trim();
    if (!cleaned) continue;
    found.add(cleaned);
  }
  return [...found].sort();
}

it('前端源码里真实存在的每个调用点都被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const sites = realCallSites();
  expect(sites.length, '前端源码里一个调用点都没抽到 —— 断言本身失效了').toBeGreaterThan(0);
  for (const path of sites) {
    expect(await call(path), `${path} 未被放行（白名单漏登记 = 浏览器里静默 404）`).toBe(200);
  }
});

it('本模块的 8 个端点形状逐条被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 能力声明：渠道词表 + secret manager / 投递通道 / 模板管理三个「通没接通」。
    'identity/external-channel-push/capabilities',
    // 渠道清单（GET）与登记渠道（POST）共用同一个集合路径。
    'identity/external-channel-push/channels',
    // 单个渠道的改（PUT）与删（DELETE）。
    `identity/external-channel-push/channels/${UUID_PLACEHOLDER}`,
    // 可推送候选（1.3.2.1 的真命中）。
    'identity/external-channel-push/candidates',
    // 投递记录清单（GET）与发起推送（POST）共用同一个集合路径。
    'identity/external-channel-push/deliveries',
    // 到期扫描（**自动重试**的入口）。
    'identity/external-channel-push/deliveries/retry-due',
    // 手工重试 / 确认告警。
    `identity/external-channel-push/deliveries/${UUID_PLACEHOLDER}/retry`,
    `identity/external-channel-push/deliveries/${UUID_PLACEHOLDER}/acknowledge-alert`,
  ]) {
    expect(await call(path), `${path} 未被放行`).toBe(200);
  }
});

it('刻意不放行的形状仍然被拒 —— 放行后端不存在的映射比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 裸路径：本模块**没有**裸路径资源。
    'identity/external-channel-push',
    // 🔴 18.5.1.1 模板管理**未交付**，本模块**不造模板表**顶替它。
    'identity/external-channel-push/templates',
    // id 段必须是**完整 UUID 形状**：非 UUID 段不该被 uuid 分支吃掉。
    'identity/external-channel-push/channels/not-a-uuid',
    'identity/external-channel-push/deliveries/not-a-uuid/retry',
    // 服务端没有这一层。
    'identity/external-channel-push/capabilities/extra',
    `identity/external-channel-push/channels/${UUID_PLACEHOLDER}/extra`,
    `identity/external-channel-push/deliveries/${UUID_PLACEHOLDER}/extra`,
    // 服务端没有删除投递记录的端点（投递记录是「投过没有」的事实，不可删）。
    `identity/external-channel-push/deliveries/${UUID_PLACEHOLDER}/delete`,
  ]) {
    expect(await call(path), `${path} 被放行了，但它在后端不存在`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

it('不误伤兄弟模块与其它车道已交付的形状', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 兄弟模块 1.3.2.1 预警规则的形状。
    'identity/alert-rules/capabilities',
    'identity/alert-rules/hits',
    // 1.3.1.1 通知列表（收件箱）与 1.3.1.2 通知偏好的形状。
    'identity/notification-inbox/capabilities',
    'identity/notification-inbox/subscriptions',
    'identity/notification-preferences',
    'identity/notification-preferences/capabilities',
    'identity/notification-preferences/resolve',
    // 1.4.1.2 列表与表格偏好 / 1.4.2.1 语言与时区。
    'identity/user-preferences',
    'identity/user-locale',
    // 会话：**唯一**的租户与授权来源。
    'auth/session',
  ]) {
    expect(await call(path), `${path} 必须仍然放行`).toBe(200);
  }
});

it('代理**不转发**客户端伪造的租户头 —— 租户只来自已认证的成员关系', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  await call('identity/external-channel-push/capabilities');

  const init = fetcher.mock.calls[0]?.[1] as RequestInit | undefined;
  const headers = (init?.headers ?? {}) as Record<string, string>;
  expect(Object.keys(headers).map((key) => key.toLowerCase())).not.toContain('x-tenant-id');
  expect(Object.keys(headers).map((key) => key.toLowerCase())).not.toContain('x-internal-key');
  // 只转发会话 cookie 换来的 Authorization。
  expect(headers.Authorization).toBe('Bearer private-session');
});
