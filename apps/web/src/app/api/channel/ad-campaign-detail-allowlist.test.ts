import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 14.1.1.2 活动详情 的 api-proxy 白名单**双向**断言。
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
 *       它会让界面以为「已经可用」。本模块尤其危险：平台侧修改历史**没有同步源**
 *       （capabilities 里 available=false），放行了界面就会以为「平台侧改历史」已交付。</li>
 * </ul>
 *
 * <p><b>不误伤其他车道已登记的分支</b>：白名单是多车道共用的**累积型**文件。
 * 本车道只**新增一条** `ad-campaign-details/` 元素，既有的 `ad-campaigns`（14.1.1.1）、
 * `ad-keywords`（14.1.3.1）、`ad-rules`（14.2.2.2）等一字未动 —— 但那必须被断言钉住，
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
const MODULE = '/channel/ad-campaign-details';

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
      'channel/ad-campaign-details/capabilities',
      'channel/ad-campaign-details/campaigns/' + ID,
      'channel/ad-campaign-details/campaigns/' + ID + '/groups',
      'channel/ad-campaign-details/campaigns/' + ID + '/products',
      'channel/ad-campaign-details/campaigns/' + ID + '/creatives',
      'channel/ad-campaign-details/campaigns/' + ID + '/keywords',
      'channel/ad-campaign-details/campaigns/' + ID + '/change-history',
      'channel/ad-campaign-details/campaigns/' + ID + '/structure',
      'channel/ad-campaign-details/campaigns/' + ID + '/bids',
      'channel/ad-campaign-details/campaigns/' + ID + '/copy',
    ]),
  );
});

it('写动作真的转发到 8083（不是只判了正则没转发）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    `channel/ad-campaign-details/campaigns/${ID}/structure`,
    `channel/ad-campaign-details/campaigns/${ID}/bids`,
    `channel/ad-campaign-details/campaigns/${ID}/copy`,
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
    expect.stringContaining(':8083/channel/ad-campaign-details'),
    expect.objectContaining({ method: 'POST' }),
  );
  // 伪造的租户头不得被转发到上游 —— 租户只来自已认证成员关系（AGENTS.md）
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  expect(new Headers(init.headers).get('X-Tenant-Id')).toBeNull();
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const base = 'channel/ad-campaign-details';
  for (const path of [
    // 裸路径刻意不放行：本模块没有裸路径资源
    base,
    // 活动列表归 14.1.1.1 的 ad-campaigns 元素，本模块不重开一条
    `${base}/campaigns`,
    // 预算曲线随全景一起返回，不单独开一条
    `${base}/campaigns/${ID}/budget`,
    // 🔴 平台侧改历史**没有同步源**（capabilities 里 available=false）：
    //    放行它会让界面以为「平台侧改历史」已交付
    `${base}/campaigns/${ID}/platform-history`,
    // 平台回写队列未交付（平台适配层）
    `${base}/campaigns/${ID}/writeback`,
    // 广告组的单条详情与增删改归 14.1.2.1（未交付）
    `${base}/campaigns/${ID}/groups/${ID}`,
    // 变更预览属 14.1.1.3（未交付）
    `${base}/campaigns/${ID}/copy/preview`,
    // 字面量子路径刻意不接 id 段
    `${base}/capabilities/${ID}`,
    // 伪造的 uuid 形状必须被拒：短前缀与多一位都不是 uuid
    `${base}/campaigns/not-a-uuid`,
    `${base}/campaigns/11111111-1111-1111-1111-1111111111111`,
    // 首 token 拼错也必须被拒
    `${base}/campaign-detail`,
    `channel/ad-campaign-detail/campaigns/${ID}`,
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
    // 14.1.1.1 广告活动列表（同卷，本模块的直接上游）
    'channel/ad-campaigns/capabilities',
    'channel/ad-campaigns/campaigns',
    `channel/ad-campaigns/campaigns/${ID}`,
    // 14.1.3.1 关键词管理（本模块的关键词读取来源）
    'channel/ad-keywords/capabilities',
    'channel/ad-keywords/report',
    'channel/ad-keywords/metrics',
    // 14.2.2.2 自动规则
    'channel/ad-rules/capabilities',
    // 14.1.2.2 广告类型差异
    'channel/ad-type-diffs/capabilities',
    // 16.2.2.1 合规预检（本车道插入锚点旁边那条）
    'channel/compliance-precheck/capabilities',
    // 10.1.2.2 渠道比价 / 10.1.2.1 运费计算器
    'channel/rate-comparison/capabilities',
    'channel/freight/estimate',
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 被本车道误伤`).not.toBe(404);
  }
});

it('本车道没有把 ad-campaign-details 放到别的服务前缀下', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  // 广告活动详情归 channel 服务（02 渠道与店铺），不在 identity / catalog / trade 上开第二条映射
  for (const path of [
    `identity/ad-campaign-details/campaigns/${ID}`,
    `trade/ad-campaign-details/campaigns/${ID}`,
    `catalog/ad-campaign-details/campaigns/${ID}`,
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status).toBe(404);
  }
});
