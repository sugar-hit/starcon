import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 14.5.1.2 建联与样品 的 api-proxy 白名单**双向**断言。
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
 *       尤其是**批量群发**：蓝图红线是「建联必须走官方接口，批量私信是平台风控重点」，
 *       批量能力只到「批量生成草稿」，放行一个群发入口等于把红线在代理层撕开。</li>
 * </ul>
 *
 * <p><b>不误伤其他车道已登记的分支</b>：白名单是多车道共用的**累积型**文件。
 * 本车道只**新增一条** `outreach/` 元素（独立 services[] 条目，不是往既有
 * `channel/` alternation 里并分支 —— 那在多车道并发下必然 git 冲突），
 * 既有的 `influencers/`（14.5.1.1）、`ad-campaigns/`（14.1.1.1）、`listing-*`
 * 一字未动 —— 但那必须被断言钉住，否则下一次并入取错一侧时会静默删掉已交付模块的路由。
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
const MODULE = '/channel/outreach';

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
      // 🔴 能力声明 / 下拉项 / 概览
      'channel/outreach/capabilities',
      'channel/outreach/options',
      'channel/outreach/summary',
      // 🔴 逐平台的达人私信官方接口接入情况
      'channel/outreach/outreach-availability',
      // 🔴 筛选条件与按规则筛出的候选达人
      'channel/outreach/segments',
      'channel/outreach/segments/' + ID,
      'channel/outreach/segments/' + ID + '/candidates',
      // 🔴 邀约模板与真渲染预览
      'channel/outreach/templates',
      'channel/outreach/templates/' + ID,
      'channel/outreach/templates/' + ID + '/preview',
      // 🔴 风控策略（每日上限 + 最小间隔）
      'channel/outreach/policies',
      // 🔴 建联记录本身
      'channel/outreach/outreachs',
      'channel/outreach/outreachs/' + ID,
      // 🔴 批量**只生成草稿**
      'channel/outreach/outreachs/batch',
      // 🔴 手动登记「已发送 + 平台回执」
      'channel/outreach/outreachs/' + ID + '/receipt',
      // 🔴 走官方接口发送（适配器未接入 ⇒ 明确失败且不写数据）
      'channel/outreach/outreachs/' + ID + '/send',
      // 🔴 推进建联状态 / 登记合作跟进
      'channel/outreach/outreachs/' + ID + '/status',
      'channel/outreach/outreachs/' + ID + '/followups',
      'channel/outreach/followups',
      // 🔴 样品单 + 包裹
      'channel/outreach/samples',
      'channel/outreach/samples/' + ID,
      'channel/outreach/samples/' + ID + '/status',
      'channel/outreach/samples/' + ID + '/shipments',
      'channel/outreach/shipments',
      'channel/outreach/shipments/' + ID + '/status',
      // 🔴 佣金规则与计提
      'channel/outreach/commission-rules',
      'channel/outreach/commission-rules/' + ID,
      'channel/outreach/commission-accruals',
    ]),
  );
});

it('写动作真的转发到 8083（不是只判了正则没转发）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'channel/outreach/segments',
    'channel/outreach/templates',
    'channel/outreach/policies',
    'channel/outreach/outreachs',
    'channel/outreach/outreachs/' + ID,
    'channel/outreach/outreachs/batch',
    'channel/outreach/outreachs/' + ID + '/receipt',
    'channel/outreach/outreachs/' + ID + '/send',
    'channel/outreach/outreachs/' + ID + '/status',
    'channel/outreach/outreachs/' + ID + '/followups',
    'channel/outreach/samples',
    'channel/outreach/samples/' + ID,
    'channel/outreach/samples/' + ID + '/status',
    'channel/outreach/samples/' + ID + '/shipments',
    'channel/outreach/shipments/' + ID + '/status',
    'channel/outreach/commission-rules',
    'channel/outreach/commission-rules/' + ID,
    'channel/outreach/commission-accruals',
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
    expect.stringContaining(':8083/channel/outreach'),
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
  const base = 'channel/outreach';
  for (const path of [
    // 🔴 裸路径刻意不放行：后端没有「一条不存在的汇总接口」，末尾刻意不带 `?`
    base,
    `${base}/`,
    // 🔴 **批量群发不存在**：蓝图红线是「建联必须走官方接口，批量私信是平台风控重点」。
    // 批量能力只到 `POST /outreachs/batch`（批量生成**草稿**），
    // 放行一个群发入口等于在代理层把红线撕开。
    `${base}/outreachs/${ID}/batch-send`,
    `${base}/outreachs/batch-send`,
    // 🔴 带货归因（14.5.2.1）**未交付**
    `${base}/attributions`,
    // 🔴 佣金**结算**（12 财务）**未交付**：本模块只到「计提登记」，
    // 放行结算入口会让界面以为财务侧已接。
    `${base}/commission-accruals/${ID}/settle`,
    `${base}/commission-settlements`,
    // 🔴 包裹是**样品单的子资源**，读包裹走 `shipments`（带 sampleOrderId 筛选）
    `${base}/samples/${ID}/shipments/${ID}`,
    // 🔴 已发出的私信是举证凭据，删不掉（DELETE 打在 `{uuid}` 上本身就是删整条）
    `${base}/outreachs/${ID}/delete`,
    // 🔴 样品签收由包裹推导，人工不能直接推进到 DELIVERED / SIGNED
    `${base}/samples/${ID}/sign`,
    `${base}/samples/${ID}/deliver`,
    // 非 UUID 段不应被 uuid 分支吃掉
    `${base}/outreachs/not-a-uuid`,
    `${base}/segments/not-a-uuid`,
    `${base}/templates/not-a-uuid`,
    `${base}/samples/not-a-uuid`,
    `${base}/shipments/not-a-uuid`,
    `${base}/commission-rules/not-a-uuid`,
    // 字面量子路径刻意不接 id 段
    `${base}/capabilities/extra`,
    `${base}/options/extra`,
    `${base}/summary/extra`,
    `${base}/policies/extra`,
    `${base}/followups/extra`,
    // 字面量子路径刻意不接动作段
    `${base}/outreach-availability/sync`,
    `${base}/segments/${ID}/export`,
    // 🔴 14.5.1.1 达人库那条条目里**刻意不放行**的形状：本模块的端点在前缀 `outreach/` 下，
    // 不在 `influencers/` 下 —— 放行它会让界面以为达人库里内建了建联。
    'channel/influencers/outreach',
    'channel/influencers/outreachs',
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
    // 🔴 14.5.1.1 达人库（**上一格，挨着本模块**）：漏登记它，达人库整页静默 404
    'channel/influencers/capabilities',
    'channel/influencers/profiles',
    'channel/influencers/profiles/' + ID,
    'channel/influencers/blacklist',
    // 14.1.1.1 广告活动列表
    'channel/ad-campaigns/capabilities',
    'channel/ad-campaigns/campaigns',
    `channel/ad-campaigns/campaigns/${ID}`,
    // 14.2.2.1 预算分配
    'channel/ad-budgets/capabilities',
    'channel/ad-budgets/rules',
    // 14.1.3.1 关键词管理
    'channel/ad-keywords/capabilities',
    'channel/ad-keywords',
    // 14.4.1.1 促销列表（**同 L3 附近**）
    'channel/ad-promotions/capabilities',
    'channel/ad-promotions/promotions',
    'channel/ad-promotions/promotions/' + ID,
    // 14.3.1.2 广告组与词报表
    'channel/ad-group-keyword-reports/capabilities',
    // 2.x 刊登侧的既有分支
    'channel/listing-collects/capabilities',
    // 10.1.2.2 渠道比价
    'channel/rate-comparison/capabilities',
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `其他车道的 ${path} 被本车道误伤 —— 白名单必须求并`).not.toBe(404);
  }
});

it('本车道没有把 outreach 放到别的服务前缀下', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  // 建联与样品落在 channel 服务，不在 trade / catalog 上开第二条映射
  for (const path of ['trade/outreach/outreachs', 'catalog/outreach/outreachs']) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 不该被放行`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});
