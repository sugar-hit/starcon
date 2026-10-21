import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 14.1.3.1 关键词管理 的 api-proxy 白名单**双向**断言。
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
 * 本车道只**新增一条** `ad-keywords/` 元素，既有的 `ad-type-diffs/`（14.1.2.2）、
 * `listing-keyword-rankings/`（4.5.1.2）、`landed-costs/`（5.2.1.2）一字未动 ——
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
const MODULE = '/channel/ad-keywords';

afterEach(() => vi.unstubAllGlobals());

/**
 * 从**本模块自己生成的契约**里抽出真实存在的 path。
 *
 * <p>⚠️ 刻意**不削掉** `/channel/` 前缀：`proxy()` 是拿 `params.path.join('/')`
 * 去比对 `services[].prefix` 的，prefix 就是 `channel/`。削掉前缀会让每一条都 404，
 * 症状是「白名单明明登记了却全被拒」，很容易被误判成正则写错。
 *
 * <p>这份清单**不手写**：手写就退化成「验证白名单与我自己抄的清单一致」，
 * 而真正会出事的是「后端加了新端点、前端白名单忘了登记」。
 */
function realCallSites(): string[] {
  const found = new Set<string>();
  for (const path of Object.keys(SPEC.paths)) {
    if (!path.startsWith(MODULE)) continue;
    // 只削**开头那一个斜杠**；`channel/` 这段服务前缀必须保留。
    // 🔴 占位符名字各模块不同（本模块是 {keywordId} / {negativeId} / {actionId}）⇒
    //    统一替换**任意** `{...}`，不能只替 `{id}` —— 只替 `{id}` 会把带占位符的
    //    path 全部跳过，断言就退化成「只验证了字面量那几条」。
    const resolved = path.replace(/^\//, '').replace(/\{[^}]+\}/g, ID);
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
      'channel/ad-keywords/capabilities',
      'channel/ad-keywords/filter-options',
      'channel/ad-keywords',
      'channel/ad-keywords/' + ID,
      // 🔴 指标同步与搜索词同步是**真实写入口**，漏登记等于这一页没有数据来源
      'channel/ad-keywords/metrics',
      'channel/ad-keywords/search-terms',
      // 🔴 否定建议是「发现浪费」的落点，漏登记等于运营拿不到依据
      'channel/ad-keywords/negative-suggestions',
      'channel/ad-keywords/negatives',
      'channel/ad-keywords/negatives/' + ID + '/result',
      'channel/ad-keywords/bulk-actions',
      'channel/ad-keywords/bulk-actions/' + ID,
      'channel/ad-keywords/report',
      'channel/ad-keywords/frequency',
    ]),
  );
});

it('写动作真的转发到 8083（不是只判了正则没转发）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'channel/ad-keywords',
    'channel/ad-keywords/metrics',
    'channel/ad-keywords/search-terms',
    'channel/ad-keywords/negatives',
    'channel/ad-keywords/bulk-actions',
  ]) {
    const response = await callProxy(path, {
      method: 'POST',
      headers: {
        origin: 'http://localhost:3000',
        cookie: 'starcon_session=private-session',
        'X-Tenant-Id': 'forged',
        'X-Internal-Key': 'forged',
      },
      body: '{}',
    });
    expect(response.status, `POST ${path} 未被放行`).not.toBe(404);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8083/channel/ad-keywords'),
    expect.objectContaining({ method: 'POST' }),
  );
  // 伪造的租户头不得被转发到上游 —— 租户只来自已认证成员关系（AGENTS.md）
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  expect(new Headers(init.headers).get('X-Tenant-Id')).toBeNull();
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const base = 'channel/ad-keywords';
  for (const path of [
    // 🔴 投放定位是 14.1.3.2（另一个未开发模块）—— 本模块**不实现**它
    `${base}/targeting`,
    // 🔴 竞价策略引擎是 14.2.1.1（另一个未开发模块）—— 本模块**不实现**它
    `${base}/bid-strategy`,
    // 指标与否定是**批量**入口，不按关键词下钻
    `${base}/${ID}/metrics`,
    `${base}/${ID}/negatives`,
    // 活动列表是 14.1.1.1（另一条车道）—— 本模块只存外部活动 id
    `${base}/campaigns`,
    // 字面量子路径刻意不接 id 段
    `${base}/capabilities/${ID}`,
    `${base}/report/${ID}`,
    `${base}/frequency/${ID}`,
    `${base}/negative-suggestions/${ID}`,
    // 伪造的 uuid 形状必须被拒：全 0 与短前缀都不是 uuid
    `${base}/not-a-uuid`,
    `${base}/11111111-1111-1111-1111-1111111111111`,
    // 批量结果只有一条动作详情，不按关键词下钻
    `${base}/bulk-actions/${ID}/items`,
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
    // 14.1.2.2 广告类型差异（同一卷，紧邻的模块）
    'channel/ad-type-diffs/capabilities',
    'channel/ad-type-diffs/resolve',
    'channel/ad-type-diffs/profiles',
    `channel/ad-type-diffs/profiles/${ID}`,
    'channel/ad-type-diffs/payloads',
    'channel/ad-type-diffs/comparable',
    'channel/ad-type-diffs/fidelity',
    // 4.5.1.2 关键词排名（本模块的词频与排名口径引用它）
    'channel/listing-keyword-rankings/capabilities',
    'channel/listing-keyword-rankings',
    `channel/listing-keyword-rankings/${ID}`,
    // 5.2.1.2 到岸成本（catalog 服务；插入锚点夹在它那条 channel 元素之前）
    'catalog/landed-costs/capabilities',
    'catalog/landed-costs/comparisons',
    // 02 渠道与店铺（本元素插在它**之前**，不能动它）
    'channel/platforms',
    'channel/shops',
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 被本车道误伤`).not.toBe(404);
  }
});

it('本车道没有把 ad-keywords 放到别的服务前缀下', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  // 关键词管理是 14.1.3.1，它落在 channel 服务，不在 identity / catalog 上开第二条映射
  const response = await callProxy('identity/ad-keywords', {
    headers: { cookie: 'starcon_session=private-session' },
  });
  expect(response.status).toBe(404);
});
