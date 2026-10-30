import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { DELETE, GET, POST } from './[...path]/route';

/**
 * 18.1.2.1 事件订阅 的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest / Playwright 全绿。
 * 所以判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从本模块自己的模型源码里<b>反向抽取真实调用点</b>再逐条断言被放行 ——
 *       写死一份清单只会验证「清单与白名单一致」，而真正会出事的是
 *       「源码加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：未交付 / 不该存在的形状仍然 404。放行不该存在的路径比 404 更糟 ——
 *       它会让界面以为「有投递记录 / 能改事件 schema」。</li>
 * </ul>
 *
 * <p>🔴 负向样本**全部取自本模块自己的前缀** {@code platform/webhooks/...}：
 * 拿兄弟车道的前缀当「未交付形状」是假红。
 *
 * <p><b>不误伤其他车道已放行的分支</b>：白名单是多车道共用的**累积型**文件。
 * 本车道只**新增**一条 platform/ services 条目（18.1.2.1 事件订阅），
 * 不动既有任何一条 —— 但那必须被断言钉住，否则下一次并入取错一侧时
 * 会静默删掉已交付模块的路由（18.1.1.1 的 {@code platform/apps}、
 * 18.4.2.1 的 {@code platform/text}、18.2.1.1 的 {@code platform/finance-software}）。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/integrations/webhooks/webhooks-model.ts', import.meta.url)),
  'utf8',
);

/** id 段的形状：完整 UUID（与白名单正则里的那一段一致）。 */
const ID = '00000000-0000-4000-8000-000000000000';

afterEach(() => vi.unstubAllGlobals());

function request(path: string, method = 'GET') {
  return new NextRequest(`http://localhost:3000/api/${path}`, {
    method,
    headers: {
      origin: 'http://localhost:3000',
      cookie: 'starcon_session=private-session',
      // 伪造的租户头与内部键：代理必须忽略它们，转发时不带。
      'X-Tenant-Id': 'forged',
      'X-Internal-Key': 'forged',
    },
    ...(method === 'GET' ? {} : { body: '{}' }),
  });
}

/**
 * 从模型源码里反向抽出**真实存在**的路径。
 *
 * <p>模型里所有端点都是 `` `${base}/xxx` `` 拼出来的（base 是常量 'platform/webhooks'），
 * 所以抽取必须先把模板串**按 base 展开**再匹配 —— 只认字面量会把调用点全部漏成零条，
 * 然后「一个都没断言」也算通过，那正是这类守卫最典型的失效方式。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'platform/webhooks';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    // ⚠️ 判据是「**以 base 开头且后面跟一段路径**」，不是「包含 base」——
    //   包含会把非路径的模板串（文案里带 base 的）也当成调用点。
    if (!raw || !raw.startsWith('${base}/')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      // 查询串插值整段去掉 —— 它不是路径的一段。
      .replace(/\$\{[^}]*query\([^)]*\)\}/g, '')
      // 其余插值（通常是 id）换成 uuid。
      .replace(/\$\{[^}]*\}/g, ID)
      .replace(/\?.*$/, '');
    if (cleaned.includes('${')) continue;
    found.add(cleaned);
  }
  return [...found].sort();
}

it('模型源码里真实存在的每个调用点都被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const sites = realCallSites();
  expect(sites.length, '模型源码里一个调用点都没抽到 —— 断言本身失效了').toBeGreaterThan(0);
  for (const path of sites) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 未被放行（白名单漏登记 = 浏览器里静默 404）`).toBe(200);
  }
  // 抽到的路径必须**真的**覆盖本模块的形状，否则「只放行了一条」也算通过。
  expect(sites).toEqual(
    expect.arrayContaining([
      'platform/webhooks/capabilities',
      'platform/webhooks/overview',
      'platform/webhooks/event-types',
      `platform/webhooks/event-types/${ID}/status`,
      'platform/webhooks/subscriptions',
      `platform/webhooks/subscriptions/${ID}`,
      `platform/webhooks/subscriptions/${ID}/revoke`,
      `platform/webhooks/subscriptions/${ID}/events`,
      `platform/webhooks/subscriptions/${ID}/events/${ID}`,
      `platform/webhooks/subscriptions/${ID}/signing-secrets`,
      `platform/webhooks/subscriptions/${ID}/signing-secrets/rotate`,
      `platform/webhooks/subscriptions/${ID}/signing-secrets/revoke`,
      `platform/webhooks/subscriptions/${ID}/signature-preview`,
      'platform/webhooks/policies',
      `platform/webhooks/policies/${ID}`,
      'platform/webhooks/audit-events',
    ]),
  );
});

it('写动作的 POST 形状被放行，且真的转发到 8089', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'platform/webhooks/event-types',
    `platform/webhooks/event-types/${ID}/status`,
    'platform/webhooks/subscriptions',
    `platform/webhooks/subscriptions/${ID}/revoke`,
    `platform/webhooks/subscriptions/${ID}/events`,
    `platform/webhooks/subscriptions/${ID}/signing-secrets`,
    `platform/webhooks/subscriptions/${ID}/signing-secrets/rotate`,
    `platform/webhooks/subscriptions/${ID}/signing-secrets/revoke`,
    `platform/webhooks/subscriptions/${ID}/signature-preview`,
    // 投递**当前恒 409**（执行器未接入，归 18.1.2.2）—— 路径要放行，
    // 由后端如实答 409，前端据此显示「未接入」，不是由白名单替它撒谎。
    `platform/webhooks/subscriptions/${ID}/deliver`,
    'platform/webhooks/policies',
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }

  const response = await POST(
    request(`platform/webhooks/subscriptions/${ID}/signature-preview`, 'POST'),
    { params: Promise.resolve({ path: path2('signature-preview') }) },
  );
  expect(response.status).toBe(200);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8089/platform/webhooks/subscriptions'),
    expect.objectContaining({ method: 'POST' }),
  );
});

/** 把 `platform/webhooks/subscriptions/{id}/xxx` 拆成路径段。 */
function path2(last: string): string[] {
  return ['platform', 'webhooks', 'subscriptions', ID, last];
}

it('改订阅与改策略被放行（🔴 刻意用 POST —— platform 的 catch-all 路由只导出 GET/POST/DELETE）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    `platform/webhooks/subscriptions/${ID}`,
    `platform/webhooks/policies/${ID}`,
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
});

it('DELETE 形状被放行（删一条事件过滤）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const path = `platform/webhooks/subscriptions/${ID}/events/${ID}`;
  const response = await DELETE(request(path, 'DELETE'), {
    params: Promise.resolve({ path: path.split('/') }),
  });
  expect(response.status, `DELETE ${path} 未被放行`).toBe(200);
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 裸路径：后端**没有**「一条不存在的汇总列表接口」。
    'platform/webhooks',
    // 🔴 **投递记录**归 18.1.2.2，本车道不实现 ——
    //   放行它会让界面以为有投递流水表 / 死信箱。
    'platform/webhooks/deliveries',
    'platform/webhooks/dead-letters',
    // 事件定义**只改状态**：没有「取单条 / 改结构」的端点。
    `platform/webhooks/event-types/${ID}`,
    `platform/webhooks/event-types/${ID}/schema`,
    // 非 UUID 段不应被 uuid 分支吃掉。
    'platform/webhooks/subscriptions/not-a-uuid',
    // 多一段仍然 404。
    `platform/webhooks/subscriptions/${ID}/not-a-shape`,
    // 订阅级专属策略按 id 改，不按 id 取单条。
    'platform/webhooks/policies/not-a-uuid',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

it('不误伤同服务已交付的 18.1.1.1 / 18.4.2.1 / 18.2.1.1 —— 白名单是累积型的', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // 🔴 四条都是 `prefix: 'platform/'`：必须靠**正则不相交**分开，
  //   而不是靠「谁先匹配上」。
  for (const path of [
    'platform/apps',
    'platform/apps/capabilities',
    'platform/apps/overview',
    `platform/apps/${ID}`,
    `platform/apps/${ID}/credentials`,
    'platform/text/capabilities',
    'platform/text/overview',
    'platform/text/glossary',
    `platform/text/glossary/${ID}`,
    'platform/text/tasks',
    `platform/text/tasks/${ID}/publish`,
    'platform/finance-software/capabilities',
    'platform/finance-software/overview',
    'platform/finance-software/accounts',
    `platform/finance-software/accounts/${ID}/disable`,
    'platform/finance-software/push',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被本车道的改动误伤了`).toBe(200);
  }
});
