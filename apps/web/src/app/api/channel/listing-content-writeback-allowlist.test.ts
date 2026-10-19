import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 4.3.2.1 内容回写 的 api-proxy 白名单**双向**断言。
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
 * <p><b>不误伤其他车道已登记的分支</b>：白名单是多车道共用的**累积型**文件。
 * 本车道只**新增一条** `listing-content-writeback/` 元素，4.3.1.2 的 `listing-publish/`、
 * 4.1.1.1 的 `listings/`、4.1.1.2 的 `listing-details/` 等一字未动 —— 但那必须被断言钉住，
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
const MODULE = '/channel/listing-content-writeback';

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
      // 🔴 能力声明：漏登记它，「改价归 05 定价」与四个未交付维度的明账就没了
      'channel/listing-content-writeback/capabilities',
      // 建回写 + 列表
      'channel/listing-content-writeback/writebacks',
      `channel/listing-content-writeback/writebacks/${ID}`,
      `channel/listing-content-writeback/writebacks/${ID}/tasks`,
      // 🔴 派发（按平台配额 + 经 02 队列）
      `channel/listing-content-writeback/writebacks/${ID}/dispatch`,
      // 🔴 只重试失败项
      `channel/listing-content-writeback/writebacks/${ID}/retry`,
      `channel/listing-content-writeback/writebacks/${ID}/cancel`,
      // 🔴 逐字段改前 / 改后
      `channel/listing-content-writeback/tasks/${ID}/changes`,
      `channel/listing-content-writeback/tasks/${ID}/report`,
      // 商品维度的变更历史
      `channel/listing-content-writeback/listings/${ID}/content-history`,
    ]),
  );
});

it('写动作真的转发到 8083（不是只判了正则没转发）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'channel/listing-content-writeback/writebacks',
    `channel/listing-content-writeback/writebacks/${ID}/dispatch`,
    `channel/listing-content-writeback/writebacks/${ID}/retry`,
    `channel/listing-content-writeback/tasks/${ID}/report`,
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
    expect.stringContaining(':8083/channel/listing-content-writeback'),
    expect.objectContaining({ method: 'POST' }),
  );
  // 伪造的租户头不得被转发到上游 —— 租户只来自已认证成员关系（AGENTS.md）
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  expect(new Headers(init.headers).get('X-Tenant-Id')).toBeNull();
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const base = 'channel/listing-content-writeback';
  for (const path of [
    // 🔴 改价 / 改库存归 05 定价 / 07 库存两条独立通道，本通道**不碰**
    `${base}/prices`,
    `${base}/stocks`,
    `${base}/writebacks/${ID}/prices`,
    `${base}/writebacks/${ID}/stocks`,
    // 🔴 回写不是编辑，没有「确认前先看看」这一步
    `${base}/writebacks/${ID}/preview`,
    // 单条任务详情随任务列表返回，不单开一条
    `${base}/tasks/${ID}`,
    // 一次触发只回写一次，没有嵌套触发
    `${base}/writebacks/${ID}/writebacks`,
    // 类目回写由 4.3.1.2 发布流程随草稿上线，不在本通道
    `${base}/writebacks/${ID}/categories`,
    // 4.3.2.2 平台状态回流尚未交付，它的形状一律不放行
    `${base}/status-reflows`,
    `${base}/writebacks/${ID}/status-reflow`,
    // 裸路径：本模块没有裸路径资源
    base,
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
    // 4.3.1.2 批量发布（本条目插在它之后，最容易被误伤）
    'channel/listing-publish/capabilities',
    'channel/listing-publish/rate-limits',
    'channel/listing-publish/batches',
    `channel/listing-publish/batches/${ID}`,
    `channel/listing-publish/batches/${ID}/tasks`,
    `channel/listing-publish/batches/${ID}/dispatch`,
    `channel/listing-publish/batches/${ID}/retry`,
    `channel/listing-publish/tasks/${ID}/report`,
    // 4.1.1.1 Listing 列表
    'channel/listings/capabilities',
    `channel/listings/${ID}`,
    // 4.1.1.2 Listing 详情
    'channel/listing-details/capabilities',
    `channel/listing-details/${ID}/diff`,
    // 4.1.2.x 映射
    `channel/listing-mappings/${ID}`,
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 被本车道的改动误伤成 404`).not.toBe(404);
  }
});
