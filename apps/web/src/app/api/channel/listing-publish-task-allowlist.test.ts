import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 4.3.1.3 发布任务 的 api-proxy 白名单**双向**断言。
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
 * 本车道只**新增一条** `listing-publish-tasks/` 元素，4.3.1.1 的 `one-click-publish/`、
 * 4.3.1.2 的 `listing-publish/`、4.3.2.2 的 `listing-status-events/` 等一字未动 ——
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
const MODULE = '/channel/listing-publish-tasks';

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
    // 🔴 `{stepKey}` 不是 id，它取的是 4.3.1.1 落的**步骤键**（TEMPLATE / CONTENT / …）——
    //    换成 UUID 会得到一个后端根本不存在的形状，断言就失去意义了。
    const resolved = path
      .replace(/^\//, '')
      .replace(/\{stepKey\}/g, 'SUBMIT')
      .replace(/\{[^}]+\}/g, ID);
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
      // 🔴 能力声明：漏登记它，阶段映射与未交付段明账就没了
      'channel/listing-publish-tasks/capabilities',
      // 任务列表 + 详情 + 步骤时间线
      'channel/listing-publish-tasks/tasks',
      `channel/listing-publish-tasks/tasks/${ID}`,
      `channel/listing-publish-tasks/tasks/${ID}/steps`,
      // 任务级重试 + 步骤级重试
      `channel/listing-publish-tasks/tasks/${ID}/retry`,
      `channel/listing-publish-tasks/tasks/${ID}/steps/SUBMIT/retry`,
    ]),
  );
});

it('写动作真的转发到 8083（不是只判了正则没转发）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    `channel/listing-publish-tasks/tasks/${ID}/retry`,
    `channel/listing-publish-tasks/tasks/${ID}/steps/MEDIA/retry`,
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
    expect.stringContaining(':8083/channel/listing-publish-tasks'),
    expect.objectContaining({ method: 'POST' }),
  );
  // 伪造的租户头不得被转发到上游 —— 租户只来自已认证成员关系（AGENTS.md）
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  expect(new Headers(init.headers).get('X-Tenant-Id')).toBeNull();
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const base = 'channel/listing-publish-tasks';
  for (const path of [
    // 裸路径：本模块没有「列出全部」的映射在裸路径上
    base,
    // 🔴 作业归 4.3.1.1（一键刊登），不在本模块的前缀下
    `${base}/jobs`,
    // 🔴 批次归 4.3.1.2（批量发布），不在本模块的前缀下
    `${base}/batches`,
    // 平台回执归 4.3.1.1 / 4.3.2.2
    `${base}/tasks/${ID}/report`,
    // 撤销未实现
    `${base}/tasks/${ID}/cancel`,
    // 🔴 05 定价 / 07 库存两条独立通道
    `${base}/tasks/${ID}/prices`,
    `${base}/tasks/${ID}/stocks`,
    // 步骤键不在 4.3.1.1 落的八个里 —— 不该被放行
    `${base}/tasks/${ID}/steps/UNKNOWN/retry`,
    // 本模块没有「预览」这个动作
    `${base}/tasks/${ID}/preview`,
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
    // 4.3.1.1 一键刊登（本条目的插入锚点紧挨着它 —— 最容易被误伤）
    'channel/one-click-publish/capabilities',
    'channel/one-click-publish/jobs',
    `channel/one-click-publish/jobs/${ID}`,
    `channel/one-click-publish/jobs/${ID}/steps`,
    `channel/one-click-publish/targets/${ID}/retry`,
    // 4.3.1.2 批量发布
    'channel/listing-publish/capabilities',
    'channel/listing-publish/batches',
    // 4.3.2.2 平台状态回流
    'channel/listing-status-events/capabilities',
    'channel/listing-status-events/events',
    // 4.4.2.1 商品认领
    'channel/listing-claims/capabilities',
    `channel/listing-claims/${ID}`,
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
