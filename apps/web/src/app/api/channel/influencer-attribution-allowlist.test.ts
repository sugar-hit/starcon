import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 14.5.2.1 带货归因 的 api-proxy 白名单**双向**断言。
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
 *       它会让界面以为「佣金成本已经进利润表」。</li>
 * </ul>
 *
 * <p><b>不误伤其他车道已登记的分支</b>：白名单是多车道共用的**累积型**文件。
 * 本车道只**新增一条** `attributions/` 元素，既有的 `influencers/`（14.5.1.1）、
 * `ad-campaigns/`（14.1.1.1）、`listing-*` 一字未动 —— 但那必须被断言钉住，
 * 否则下一次并入取错一侧时会静默删掉已交付模块的路由。
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
const MODULE = '/channel/attributions';

afterEach(() => vi.unstubAllGlobals());

/**
 * 从**本模块自己生成的契约**里抽出真实存在的 path。
 *
 * <p>⚠️ 刻意**不削掉** `/channel/` 前缀：`proxy()` 是拿 `params.path.join('/')`
 * 去比对 `services[].prefix` 的，prefix 就是 `channel/`。削掉前缀会让每一条都 404，
 * 症状是「白名单明明登记了却全被拒」，很容易被误判成正则写错。
 */
function realCallSites(): string[] {
  const found = new Set<string>();
  for (const path of Object.keys(SPEC.paths)) {
    if (!path.startsWith(MODULE)) continue;
    // 只削**开头那一个斜杠**；`channel/` 这段服务前缀必须保留。
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
      'channel/attributions/capabilities',
      'channel/attributions/options',
      // 🔴 逐平台的带货数据同步接入情况与同步入口
      'channel/attributions/sync-status',
      'channel/attributions/sync',
      // 🔴 归因规则（配置 + 生效口径）
      'channel/attributions/rules',
      'channel/attributions/rules/current',
      // 🔴 归因批次
      'channel/attributions/run',
      // 🔴 归因记录与佣金计提
      'channel/attributions/records',
      'channel/attributions/records/' + ID,
      'channel/attributions/records/' + ID + '/accrual',
      // 🔴 达人效果报表
      'channel/attributions/report',
    ]),
  );
});

it('写动作真的转发到 8083（不是只判了正则没转发）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'channel/attributions/sync',
    'channel/attributions/rules',
    'channel/attributions/run',
    'channel/attributions/records/' + ID + '/accrual',
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
    expect.stringContaining(':8083/channel/attributions'),
    expect.objectContaining({ method: 'POST' }),
  );
  // 伪造的租户头不得被转发到上游 —— 租户只来自已认证成员关系（AGENTS.md）
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  const headers = new Headers(init.headers);
  expect(headers.get('X-Tenant-Id')).toBeNull();
  expect(headers.get('X-Internal-Key')).toBeNull();
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const base = 'channel/attributions';
  for (const path of [
    // 🔴 裸路径刻意不放行：后端没有「一条不存在的汇总接口」
    base,
    // 🔴 13.4 利润（reporting 服务）**未交付** —— 放行它会让界面以为「佣金成本已进利润表」
    `${base}/profit`,
    // 本模块不提供导出
    `${base}/export`,
    // 归因是事实，不提供删除
    `${base}/records/${ID}/delete`,
    // 没有「按 id 取单条」这个形状（详情走 records/{uuid}）
    `${base}/${ID}`,
    // 非 UUID 段不应被 uuid 分支吃掉
    `${base}/records/not-a-uuid`,
    // 字面量子路径刻意不接 id 段
    `${base}/capabilities/extra`,
    `${base}/report/extra`,
    // 裸路径 + 尾斜杠也不行
    `${base}/`,
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 不该被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

it('不误伤其他车道已登记的分支：既有 channel 路由仍然放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 14.5.1.1 达人库（**同 L3 附近**）
    'channel/influencers/capabilities',
    'channel/influencers/profiles',
    `channel/influencers/profiles/${ID}`,
    // 14.1.1.1 广告活动列表
    'channel/ad-campaigns/capabilities',
    'channel/ad-campaigns/campaigns',
    `channel/ad-campaigns/campaigns/${ID}`,
    // 14.2.2.1 预算分配
    'channel/ad-budgets/capabilities',
    // 14.3.1.2 广告组与词报表
    'channel/ad-group-keyword-reports/capabilities',
    // 4.5.1.1 Listing 表现
    'channel/listing-performance/capabilities',
    // 10.1.1.2 渠道配置（本车道锚点附近的那一条）
    'channel/logistics-channels/capabilities',
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `其他车道的 ${path} 被本车道误伤 —— 白名单必须求并`).not.toBe(404);
  }
});

it('本车道没有把 attributions 放到别的服务前缀下', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  // 带货归因落在 channel 服务，不在 trade / catalog 上开第二条映射
  for (const path of ['trade/attributions', 'catalog/attributions']) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 不该被放行`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

it('首 token「attributions」不被既有分支的前缀吃掉：trade 侧的 attributions 形状仍归它们', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // 既有的含 `attributions` 字样的两条分支，首 token 分别是 product-attribution 与
  // aftersales —— 它们**必须**仍然归自己，不能被本条目的 `^attributions` 抢走。
  for (const path of [
    'trade/product-attribution/attributions',
    'trade/aftersales/reasons/attributions',
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 被本车道误伤`).not.toBe(404);
  }

  // 而本条目的首 token 仍然只认自己这一棵树。
  const rejected = await callProxy('channel/product-attribution/attributions', {
    headers: { cookie: 'starcon_session=private-session' },
  });
  expect(rejected.status, '别的模块的路径不该被本条目的首 token 放行').toBe(404);
});
