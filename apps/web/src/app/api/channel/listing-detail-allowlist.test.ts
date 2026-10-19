import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 4.1.1.2 Listing 详情 的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest / Playwright 全绿。
 * 所以判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比（这两种方法在本仓库已连续骗过多版）。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从**自己生成的 OpenAPI 契约**里反向抽出本模块的真实 path，再逐条断言被放行 ——
 *       写死一份清单只会验证「清单与白名单一致」，而真正会出事的是「后端加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：未交付 / 不该存在的形状仍然 404。放行不该存在的路径比 404 更糟 ——
 *       它会让界面以为「已经可用」。</li>
 * </ul>
 *
 * <p><b>不误伤其他车道已登记的分支</b>：白名单是多车道共用的**累积型**文件。
 * 本车道只**新增一条** `listing-details/` 元素，4.1.1.1 的 `listings/`、14.1.2.2 的 `ad-type-diffs/`、
 * 10.1.2.2 的 `rate-comparison/`、10.2.2.2 的 `label-templates/`、2.5.2.1 的回写分支等一字未动 ——
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
const MODULE = '/channel/listing-details';

afterEach(() => vi.unstubAllGlobals());

/**
 * 从**本模块自己生成的契约**里抽出真实存在的 path。
 *
 * <p>⚠️ 刻意**不削掉** `/channel/` 前缀：`proxy()` 拿 `params.path.join('/')`
 * 去比对 `services[].prefix`，prefix 就是 `channel/`。削掉前缀会让每一条都 404，
 * 症状是「白名单明明登记了却全被拒」，很容易被误判成正则写错。
 */
function realCallSites(): string[] {
  const found = new Set<string>();
  for (const path of Object.keys(SPEC.paths)) {
    if (!path.startsWith(MODULE)) continue;
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
      // 🔴 能力声明：漏登记它，四个未接入维度的明账就没了，页面会开始画假价格 / 假草稿
      'channel/listing-details/capabilities',
      // 全景
      `channel/listing-details/${ID}`,
      // 🔴 差异对照是本模块的核心价值
      `channel/listing-details/${ID}/diff`,
      // 内容对照
      `channel/listing-details/${ID}/content`,
      // 发布历史 / 平台操作记录
      `channel/listing-details/${ID}/publish-history`,
      `channel/listing-details/${ID}/platform-operations`,
      // 两个写入端点
      `channel/listing-details/${ID}/content-snapshots`,
      `channel/listing-details/${ID}/publish-records`,
    ]),
  );
});

it('写动作真的转发到 8083（不是只判了正则没转发）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    `channel/listing-details/${ID}/content-snapshots`,
    `channel/listing-details/${ID}/publish-records`,
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
    expect.stringContaining(':8083/channel/listing-details'),
    expect.objectContaining({ method: 'POST' }),
  );
  // 伪造的租户头不得被转发到上游 —— 租户只来自已认证成员关系（AGENTS.md）
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  expect(new Headers(init.headers).get('X-Tenant-Id')).toBeNull();
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const base = 'channel/listing-details';
  for (const path of [
    // 七态状态机与平台回写归 4.1.1.3
    `${base}/${ID}/lifecycle`,
    // 改价 / 改库存归 05 / 07，本服务不建影子价格表
    `${base}/${ID}/reprice`,
    `${base}/${ID}/restock`,
    // 内容草稿归 4.2.1.1、内容回写归 4.3.2.1，都尚未交付
    `${base}/${ID}/draft`,
    `${base}/${ID}/writeback`,
    // 发布流程归 4.3.1，本模块只留痕不发起
    `${base}/${ID}/publish`,
    // 字面量子路径不得带 id 段，也不得带可选尾巴
    `${base}/capabilities/${ID}`,
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
    // 4.1.1.1 Listing 列表（同一服务，本车道插入锚点的紧邻前一条）
    'channel/listings',
    'channel/listings/capabilities',
    'channel/listings/filter-options',
    'channel/listings/batch',
    'channel/listings/batch-tasks',
    `channel/listings/${ID}`,
    // 14.1.2.2 广告类型差异
    'channel/ad-type-diffs/capabilities',
    'channel/ad-type-diffs/profiles',
    // 10.1.2.2 渠道比价 / 10.2.2.2 面单模板
    'channel/rate-comparison/compare',
    'channel/label-templates',
    // 10.1.2.1 运费计算器
    'channel/freight/estimate',
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 被本车道误伤`).not.toBe(404);
  }
});

it('本车道没有动 4.1.1.1 的白名单分支：它显式拒绝的形状仍然被拒', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  // 4.1.1.1 把 `listings/{uuid}/diff` 登记成「不放行」（那是本模块的形状）。
  // 本车道另起 `listing-details` 前缀正是为了不碰它的拒绝清单 —— 这里把它钉住。
  for (const path of [
    `channel/listings/${ID}/diff`,
    `channel/listings/${ID}/lifecycle`,
    `channel/listings/${ID}/mapping`,
    `channel/listings/${ID}/publish`,
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 不该被本车道放行`).toBe(404);
  }
});
