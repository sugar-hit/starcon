import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 4.5.2.2 上新与变更监控 的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest / Playwright 全绿。
 * 所以判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比（这两种方法在本仓库已连续骗过多版）。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从**自己生成的 OpenAPI 契约**里反向抽出本模块的真实 path 再逐条断言 ——
 *       写死一份清单只会验证「清单与白名单一致」，而真正会出事的是「后端加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：未交付 / 不该存在的形状仍然 404。放行不该存在的路径比 404 更糟 ——
 *       它会让界面以为「已经可用」。</li>
 * </ul>
 *
 * <p>🔴 <b>负向断言只能用「自己前缀下」的形状。</b>
 * 兄弟车道的前缀（如 {@code listing-keyword-rankings}）在同一批并入后就是
 * **已登记的合法前缀**，拿它断言 404 必然假红（batch-32 的 4.5.2.1 踩过这个坑）。
 * 更危险的是反向：若那条白名单被取错一侧而静默删掉，这条负向断言反而会「绿」——
 * 「已交付的路由被误删」这个真事故会被测试盖住。断言方向搞反时，测试会替错误背书。
 *
 * <p><b>不误伤其他车道已登记的分支</b>：白名单是多车道共用的**累积型**文件。
 * 本车道只**新增一条** {@code listing-changes} 元素，既有分支一字未动 ——
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
const MODULE = '/channel/listing-changes';

afterEach(() => vi.unstubAllGlobals());

/**
 * 从**本模块自己生成的契约**里抽出真实存在的 path。
 *
 * <p>⚠️ 刻意**不削掉** `/channel/` 前缀：`proxy()` 拿 `params.path.join('/')` 去比对
 * `services[].prefix`，prefix 就是 `channel/`。削掉前缀会让每一条都 404，
 * 症状是「白名单明明登记了却全被拒」，很容易被误判成正则写错。
 */
function realCallSites(): string[] {
  const found = new Set<string>();
  for (const path of Object.keys(SPEC.paths)) {
    if (!path.startsWith(MODULE)) continue;
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
      // 🔴 能力声明：漏登记它，02 / 13 / 1 未交付能力的明账就没了
      'channel/listing-changes/capabilities',
      // 筛选项词表
      'channel/listing-changes/filter-options',
      // 监控对象（列表 + 新建）
      'channel/listing-changes/watches',
      // 监控对象（改 / 删）
      `channel/listing-changes/watches/${ID}`,
      // 观测历史 + 历史对比
      `channel/listing-changes/watches/${ID}/history`,
      `channel/listing-changes/watches/${ID}/comparison`,
      // 接收抓取任务结果 + 抓取批次列表
      'channel/listing-changes/crawls',
      // 变更事件流
      'channel/listing-changes/events',
      // 市场机会 + 标记已阅
      'channel/listing-changes/opportunities',
      `channel/listing-changes/opportunities/${ID}/acknowledge`,
    ]),
  );
});

it('写动作真的转发到 8083（不是只判了正则没转发）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const response = await callProxy('channel/listing-changes/crawls', {
    method: 'POST',
    headers: {
      origin: 'http://localhost:3000',
      cookie: 'starcon_session=private-session',
      'X-Tenant-Id': 'forged',
      'X-Internal-Key': 'forged',
    },
    body: '{}',
  });
  expect(response.status, 'POST channel/listing-changes/crawls 未被放行').not.toBe(404);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8083/channel/listing-changes/crawls'),
    expect.objectContaining({ method: 'POST' }),
  );
  // 伪造的租户头不得被转发到上游 —— 租户只来自已认证成员关系（AGENTS.md）
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  expect(new Headers(init.headers).get('X-Tenant-Id')).toBeNull();
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const base = 'channel/listing-changes';
  for (const path of [
    // 🔴 本模块**确定没有登记**的形状（只能选自己前缀下的）
    `${base}/undeclared-action`,
    // 🔴 抓取走集合入口，不按监控对象寻址
    `${base}/${ID}/crawls`,
    // 🔴 事件是集合查询，不按监控对象寻址
    `${base}/${ID}/events`,
    // 🔴 机会是集合查询，不按监控对象寻址
    `${base}/${ID}/opportunities`,
    // 🔴 05 定价 / 07 库存是两条独立通道
    `${base}/${ID}/prices`,
    `${base}/${ID}/stocks`,
    // 🔴 本模块**不提供**抢占 / 调价 / 铺货动作 —— 竞品数据只做参考指标
    `${base}/actions/undercut`,
    `${base}/${ID}/undercut`,
    // 🔴 1 工作台通知未交付（capabilities 里 available=false）
    `${base}/notifications`,
    // 🔴 13 分析未交付（capabilities 里 available=false）
    `${base}/analytics`,
    // 🔴 02 抓取调度器未交付（capabilities 里 available=false）
    `${base}/schedulers`,
  ]) {
    const response = await callProxy(path, {
      method: 'POST',
      headers: {
        origin: 'http://localhost:3000',
        cookie: 'starcon_session=private-session',
      },
      body: '{}',
    });
    expect(response.status, `${path} 被放行了，但后端没有这个形状`).toBe(404);
  }
});

it('🔴 不误伤其他车道已登记的分支 —— 白名单是多车道共用的累积型文件', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // 本车道只**新增**一条元素，既有分支一字未动。下面每一条都必须仍然放行 ——
  // 取错一侧时会静默删掉别人已交付的路由，症状是那些页面 404 而本仓全绿。
  for (const path of [
    // 4.5.1.2 关键词排名（**本条目的插入锚点紧挨着它** —— 最容易被误伤）
    'channel/listing-keyword-rankings/capabilities',
    'channel/listing-keyword-rankings/filter-options',
    'channel/listing-keyword-rankings/crawls',
    'channel/listing-keyword-rankings/report',
    'channel/listing-keyword-rankings/alerts',
    `channel/listing-keyword-rankings/alerts/${ID}/acknowledge`,
    `channel/listing-keyword-rankings/${ID}/history`,
    `channel/listing-keyword-rankings/${ID}/trend`,
    // 4.5.2.1 跟卖监控（同 L3 的兄弟模块）
    'channel/listing-hijackers/capabilities',
    'channel/listing-hijackers/filter-options',
    'channel/listing-hijackers/watches',
    'channel/listing-hijackers/records',
    'channel/listing-hijackers/alerts',
    'channel/listing-hijackers/comparison',
    // 4.4.1.3 批量采集（本批另一条车道的锚点）
    'channel/listing-bulk-collects/capabilities',
    'channel/listing-bulk-collects/plugin-reports',
    // 4.4.2.1 商品认领
    'channel/listing-claims/capabilities',
    'channel/listing-claims',
    `channel/listing-claims/${ID}`,
    // 4.5.1.1 Listing 表现
    'channel/listing-performance/capabilities',
    'channel/listing-performance',
    `channel/listing-performance/${ID}`,
    // 4.1.1.1 Listing 列表
    'channel/listings/capabilities',
    `channel/listings/${ID}`,
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 被本车道的改动误伤成 404`).not.toBe(404);
  }
});
