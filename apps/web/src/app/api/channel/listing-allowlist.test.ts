import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 4.1.1.1 Listing 列表 的 api-proxy 白名单**双向**断言。
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
 * 本车道只**新增一条** `listings/` 元素，既有的 `ad-type-diffs/`（14.1.2.2）、
 * `rate-comparison/`（10.1.2.2）、`label-templates/`（10.2.2.2）等一字未动 ——
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
const MODULE = '/channel/listings';

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
      // 🔴 裸路径就是列表页：GET /channel/listings?...（query 不参与比对）
      'channel/listings',
      // 🔴 能力声明：漏登记它，「价格未接入」的明账就没了，页面会开始画假价格
      'channel/listings/capabilities',
      // 🔴 筛选词表：不硬编码在前端
      'channel/listings/filter-options',
      'channel/listings/' + ID,
      // ⚠️ batch 与 batch-tasks 共享 batch- 前缀，顺序写反就是「批量任务历史永远 404」
      'channel/listings/batch',
      'channel/listings/batch-tasks',
    ]),
  );
});

it('写动作真的转发到 8083（不是只判了正则没转发）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of ['channel/listings/batch', 'channel/listings']) {
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
    expect.stringContaining(':8083/channel/listings'),
    expect.objectContaining({ method: 'POST' }),
  );
  // 伪造的租户头不得被转发到上游 —— 租户只来自已认证成员关系（AGENTS.md）
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  expect(new Headers(init.headers).get('X-Tenant-Id')).toBeNull();
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const base = 'channel/listings';
  for (const path of [
    // 映射的建立 / 版本化 / 批量匹配归 4.1.2.1 / 4.1.2.2
    `${base}/${ID}/mapping`,
    `${base}/mappings`,
    // 七态状态机与平台回写归 4.1.1.3
    `${base}/${ID}/lifecycle`,
    // 差异对照是 4.1.1.2 的核心价值
    `${base}/${ID}/diff`,
    // 发布流程归 4.3.1，本模块只登记不上线
    `${base}/${ID}/publish`,
    // 状态历史随列表行返回，不单独开一条
    `${base}/${ID}/history`,
    // 本模块没有的写入动词：改价 / 改库存归 05 / 07
    `${base}/${ID}/price`,
    `${base}/${ID}/stock`,
    // 字面量子路径不得带 id 段，也不得带可选尾巴
    `${base}/batch/${ID}`,
    `${base}/capabilities/${ID}`,
    `${base}/filter-options/${ID}`,
    // 伪造的 uuid 形状必须被拒：短前缀 / 多一位都不是 uuid
    `${base}/not-a-uuid`,
    `${base}/11111111-1111-1111-1111-1111111111111`,
    // 多余路径段
    `${base}/a/b`,
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
    // 14.1.2.2 广告类型差异（同一服务，本车道插入锚点的紧邻前一条）
    'channel/ad-type-diffs/capabilities',
    'channel/ad-type-diffs/profiles',
    `channel/ad-type-diffs/profiles/${ID}`,
    // 10.1.2.2 渠道比价
    'channel/rate-comparison/capabilities',
    'channel/rate-comparison/compare',
    // 10.2.2.2 面单模板 / 10.1.1.2 渠道配置 / 10.1.1.3 时效与限制
    'channel/label-templates',
    'channel/label-templates/capabilities',
    'channel/logistics-channels',
    'channel/logistics-transit-limits/precheck',
    // 10.1.2.1 运费计算器
    'channel/freight/capabilities',
    'channel/freight/estimate',
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 被本车道误伤`).not.toBe(404);
  }
});

it('本车道没有把 listings 放到别的服务前缀下', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  // 平台商品是 channel 的事实（上游 02 店铺同库同服务），但 catalog 侧也没有这条映射，
  // 在别的服务前缀上开第二条只会让「这条路由归谁」永远答不出来。
  for (const path of ['catalog/listings', 'trade/listings', 'identity/listings']) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 不该被放行`).toBe(404);
  }
});
