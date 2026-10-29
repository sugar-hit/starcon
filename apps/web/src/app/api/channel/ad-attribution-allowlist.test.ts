import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 14.3.2.1 归因分析 的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest / Playwright 全绿。
 * 所以判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比（这两种方法在本仓库已连续骗过多版）。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从**自己生成的 OpenAPI 契约**里反向抽出本模块的真实 path，
 *       再逐条断言被放行 —— 写死一份清单只会验证「清单与白名单一致」，而真正会出事
 *       的是「后端加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：未交付 / 不该存在的形状仍然 404。放行不该存在的路径比 404 更糟 ——
 *       它会让界面以为「已经可用」。</li>
 * </ul>
 *
 * <p><b>不误伤其他车道已登记的分支</b>：白名单是多车道共用的**累积型**文件。
 * 本车道只**新增一条** `ad-attributions/` 元素，既有的 `ad-reports`（14.3.1.1）、
 * `ad-product-reports`（14.3.1.3）、`attributions`（14.5.2.1 带货归因）等一字未动 ——
 * 但那必须被断言钉住，否则下一次并入取错一侧时会静默删掉已交付模块的路由。
 */

const SPEC = JSON.parse(
  readFileSync(
    fileURLToPath(
      new URL('../../../../../../packages/api-contracts/src/channel.openapi.json', import.meta.url),
    ),
    'utf8',
  ),
) as { paths: Record<string, Record<string, unknown>> };

const ID = '11111111-1111-1111-1111-111111111111';
/** 本模块在契约里的 path 前缀。 */
const MODULE = '/channel/ad-attributions';

afterEach(() => vi.unstubAllGlobals());

/**
 * 从**本模块自己生成的契约**里抽出真实存在的 path。
 *
 * <p>⚠️ 刻意**不削掉** `/channel/` 前缀：`proxy()` 是拿 `params.path.join('/')`
 * 去比对 `services[].prefix` 的，prefix 就是 `channel/`。
 *
 * <p>这份清单**不手写**：手写就退化成「验证白名单与我自己抄的清单一致」，
 * 而真正会出事的是「后端加了新端点、前端白名单忘了登记」。
 */
function realCallSites(): string[] {
  const found = new Set<string>();
  for (const path of Object.keys(SPEC.paths)) {
    if (!path.startsWith(MODULE)) continue;
    // 只削**开头那一个斜杠**；`channel/` 这段服务前缀必须保留。
    const resolved = path.replace(/^\//, '').replace('{id}', ID);
    if (resolved.includes('{')) continue;
    found.add(resolved);
  }
  return [...found].sort();
}

function callProxy(fullPath: string, init?: ConstructorParameters<typeof NextRequest>[1]) {
  return proxy(new NextRequest(`http://localhost:3000/api/${fullPath}`, init), {
    params: Promise.resolve({ path: fullPath.split('/') }),
  } as never);
}

it('契约里本模块的每个真实 path 都被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const sites = realCallSites();
  expect(sites.length, '契约里一个本模块 path 都没抽到 —— 断言本身失效了').toBeGreaterThan(0);
  for (const path of sites) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 未被放行（白名单漏登记 = 浏览器里静默 404）`).not.toBe(404);
  }

  // 抽到的路径必须**真的**覆盖本模块的形状，否则「只放行了一条」也算通过。
  expect(sites).toEqual(
    expect.arrayContaining([
      'channel/ad-attributions/capabilities',
      'channel/ad-attributions/options',
      'channel/ad-attributions/rules',
      'channel/ad-attributions/rules/current',
      'channel/ad-attributions/import',
      'channel/ad-attributions/run',
      'channel/ad-attributions/records',
      `channel/ad-attributions/records/${ID}`,
      'channel/ad-attributions/report',
      // 🔴 导出走 GET + query：allowlist 只需放行读路径（text/csv 由代理原样透传）
      'channel/ad-attributions/export',
      'channel/ad-attributions/exports',
    ]),
  );
});

it('导出真的转发到 8083（不是只判了正则没转发）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const response = await callProxy('channel/ad-attributions/export', {
    headers: { cookie: 'starcon_session=private-session' },
  });
  expect(response.status, '导出未被放行').not.toBe(404);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8083/channel/ad-attributions/export'),
    expect.objectContaining({ method: 'GET' }),
  );
  // 伪造的租户头不得被转发到上游 —— 租户只来自已认证成员关系（AGENTS.md）
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  expect(new Headers(init.headers).get('X-Tenant-Id')).toBeNull();
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const base = 'channel/ad-attributions';
  for (const path of [
    // 裸集合路径刻意不放行：本模块没有裸集合读，一切入口都带子路径
    base,
    // 🔴 本模块**没有**趋势端点（蓝图的输出句是「归因明细 + 广告后利润报表」）
    `${base}/trend`,
    `${base}/trend/${ID}`,
    // 🔴 本模块**没有**同步端点：平台归因数据走 `POST /import`，
    //    平台适配器未接入 —— 放行一个不存在的「同步」会让界面以为能拉到平台数据
    `${base}/sync`,
    // 改规则走 `POST /rules`（带 id 在请求体里），没有 `rules/{id}` 这个形状
    `${base}/rules/${ID}`,
    // 字面量子路径刻意不接 id 段
    `${base}/capabilities/${ID}`,
    `${base}/options/${ID}`,
    `${base}/exports/${ID}`,
    // 单条详情下没有子动作
    `${base}/records/${ID}/metrics`,
    // id 段必须是合法 UUID
    `${base}/records/not-a-uuid`,
    // 🔴 `attributions` 是 14.5.2.1 带货归因的命名空间，不是本模块
    `${base}/attributions`,
    // 首 token 拼错也必须被拒
    `${base}/recods`,
    `channel/ad-attribution/records`,
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 不该被放行`).toBe(404);
  }
});

it('不误伤其他车道已登记的分支：既有 channel 路由仍然放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 14.3.1.1 活动报表（同 L3 锚点，同服务）
    'channel/ad-reports/capabilities',
    'channel/ad-reports/rows',
    'channel/ad-reports/trend',
    // 14.3.1.3 商品维度报表（同 L3 锚点，同服务）
    'channel/ad-product-reports/capabilities',
    'channel/ad-product-reports/rows',
    // 14.5.2.1 带货归因（同卷，同服务；**与本模块都叫「归因」但口径完全不同**）
    'channel/attributions/capabilities',
    // 14.1.1.1 广告活动列表（同卷，同服务）
    'channel/ad-campaigns/capabilities',
    'channel/ad-campaigns/campaigns',
    `channel/ad-campaigns/campaigns/${ID}`,
    // 14.1.3.1 关键词管理（同卷，同服务）
    'channel/ad-keywords/capabilities',
    'channel/ad-keywords/report',
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 被本车道误伤`).not.toBe(404);
  }
});

it('本车道没有把 ad-attributions 放到别的服务前缀下', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  // 归因分析归 channel 服务（02 渠道与店铺），不在 identity / catalog / trade 上开第二条映射
  for (const path of [
    'identity/ad-attributions/records',
    'trade/ad-attributions/records',
    'catalog/ad-attributions/report',
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status).toBe(404);
  }
});
