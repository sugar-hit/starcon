import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 4.3.1.1 一键刊登的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest / Playwright 全绿。
 * 所以判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从**自己生成的 OpenAPI 契约**
 *       （{@code packages/api-contracts/src/channel.openapi.json}）里反向抽出本模块的真实 path，
 *       再逐条断言被放行 —— 写死一份清单只会验证「清单与白名单一致」，
 *       而真正会出事的是「后端加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：未交付 / 不该存在的形状仍然 404。放行不该存在的路径比 404 更糟 ——
 *       它会让界面以为「已经可用」。</li>
 * </ul>
 *
 * <p><b>不误伤其他车道已登记的分支</b>：白名单是多车道共用的**累积型**文件。
 * 本车道只**新增**一条 channel/ services 条目（插在既有 `listing-migrations` 之后），
 * 既有那一条一字未动 —— 但那必须被断言钉住，否则下一次并入取错一侧时会静默删掉
 * 已交付模块的路由（症状是页面 404，而所有测试仍然全绿）。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SPEC = JSON.parse(
  readFileSync(
    fileURLToPath(
      new URL('../../../../../../packages/api-contracts/src/channel.openapi.json', import.meta.url),
    ),
    'utf8',
  ),
) as { paths: Record<string, Record<string, unknown>> };

const ID = '11111111-1111-1111-1111-111111111111';
/** 本模块在契约里的 path 前缀（OpenAPI 写的是完整的 /channel/one-click-publish...）。 */
const MODULE = '/channel/one-click-publish';

afterEach(() => vi.unstubAllGlobals());

/**
 * 从**本模块自己生成的契约**里抽出真实存在的 path。
 *
 * <p>契约里的 path 是 `/channel/one-click-publish/...`，<b>原样</b>传给 proxy：
 * `proxy()` 拿的是 `params.path.join('/')`，而 services 条目的 prefix 就是 `channel/`，
 * 削掉前缀会让 `startsWith('channel/')` 为假，**每一条**都会 404。
 */
function realCallSites(): string[] {
  const found = new Set<string>();
  for (const path of Object.keys(SPEC.paths)) {
    if (!path.startsWith(MODULE)) continue;
    const resolved = path
      .replace(/^\//, '')
      .replace(/\{jobId\}/g, ID)
      .replace(/\{targetId\}/g, ID);
    if (resolved.includes('{')) continue;
    found.add(resolved);
  }
  return [...found].sort();
}

/**
 * 走一次真实 proxy。
 *
 * <p>{@code fullPath} 是**含服务段**的完整路径（`channel/one-click-publish/...`），
 * URL 与 `params.path` 都由它派生 —— 两者必须同源。
 */
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
      'channel/one-click-publish/capabilities',
      'channel/one-click-publish/target-options',
      'channel/one-click-publish/content-sources',
      'channel/one-click-publish/jobs',
      `channel/one-click-publish/jobs/${ID}`,
      `channel/one-click-publish/jobs/${ID}/targets`,
      `channel/one-click-publish/jobs/${ID}/steps`,
      `channel/one-click-publish/jobs/${ID}/dispatch`,
      `channel/one-click-publish/targets/${ID}`,
      `channel/one-click-publish/targets/${ID}/report`,
      `channel/one-click-publish/targets/${ID}/retry`,
    ]),
  );
});

it('写动作真的转发到 8083（不是只判了正则没转发）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'channel/one-click-publish/jobs',
    `channel/one-click-publish/jobs/${ID}/dispatch`,
    `channel/one-click-publish/targets/${ID}/retry`,
  ]) {
    const response = await callProxy(path, {
      method: 'POST',
      headers: {
        origin: 'http://localhost:3000',
        cookie: 'starcon_session=private-session',
        // 伪造的租户头与内部键：代理必须忽略它们，转发时不带。
        'X-Tenant-Id': 'forged',
        'X-Internal-Key': 'forged',
      },
      body: '{}',
    });
    expect(response.status, `POST ${path} 未被放行`).not.toBe(404);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8083/channel/one-click-publish'),
    expect.objectContaining({ method: 'POST' }),
  );
  // 伪造的租户头不得被转发到上游 —— 租户只来自已认证成员关系（AGENTS.md）
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  expect(new Headers(init.headers).get('X-Tenant-Id')).toBeNull();
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const base = 'channel/one-click-publish';
  for (const path of [
    // 裸路径：后端没有「列出全部」的映射在裸路径上
    base,
    // 4.3.1.3 发布任务未交付：单次发布任务的时间线页不是本模块的
    `${base}/jobs/${ID}/timeline`,
    // 撤销未实现
    `${base}/jobs/${ID}/cancel`,
    // 🔴 05 定价 / 07 库存是两条独立通道，不挂在发布命名空间下
    `${base}/jobs/${ID}/prices`,
    `${base}/jobs/${ID}/stocks`,
    // 没有「按 id 直接取作业」的裸形状
    `${base}/${ID}`,
    // 字面量子路径刻意不接 id 段，也不得带可选尾巴
    `${base}/capabilities/${ID}`,
    `${base}/target-options/extra`,
    `${base}/content-sources/extra`,
    `${base}/jobs/${ID}/extra`,
    // 非 UUID 段不应被 id 分支吃掉（完整 UUID 形状，不是 [0-9a-f-]+）
    `${base}/jobs/not-a-uuid`,
    `${base}/jobs/11111111111111111111`,
    // 单字符：c / e / f 都是合法 hex，写成 `[0-9a-f-]+` 就会被凭空放行
    `${base}/jobs/c`,
    `${base}/jobs/e`,
    `${base}/jobs/f`,
    `${base}/jobs/p`,
    // 拼写错误与尾斜杠
    'channel/one-click-pubish',
    `${base}/`,
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
});

it('兄弟组没有被挤掉或污染（取错一侧 = 静默删掉别人已交付的路由）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // 4.3.1.2 批量发布（listing-publish）—— 与本模块是**两条独立通道**，一个字都不许改。
  for (const path of [
    'channel/listing-publish/capabilities',
    'channel/listing-publish/rate-limits',
    'channel/listing-publish/batches',
    `channel/listing-publish/batches/${ID}/tasks`,
    `channel/listing-publish/batches/${ID}/dispatch`,
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `兄弟组 ${path} 被本车道的白名单改动挤掉了`).not.toBe(404);
  }

  // 4.4.2.2 批量搬迁（listing-migrations）—— 本车道的插入锚点就在它之后。
  for (const path of [
    'channel/listing-migrations/capabilities',
    'channel/listing-migrations/filter-options',
    `channel/listing-migrations/${ID}/items`,
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `兄弟组 ${path} 被本车道的白名单改动挤掉了`).not.toBe(404);
  }

  // 4.2.1.1 刊登编辑器（listing-editor）—— 一键刊登的**内容来源**就是它的入队记录。
  for (const path of [
    'channel/listing-editor/capabilities',
    'channel/listing-editor/submissions',
    `channel/listing-editor/drafts/${ID}`,
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `兄弟组 ${path} 被本车道的白名单改动挤掉了`).not.toBe(404);
  }
});

it('本模块没有并进兄弟组：批量发布的路径仍然只放行它自己的形状', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);

  // 一键刊登的形状挂到批量发布的命名空间上 = 两个模块的权限与日志糊在一起，
  // 而它们是**两个模块**（4.3.1.1 一个 SKU 多店 / 4.3.1.2 批量队列）。
  for (const path of [
    'channel/listing-publish/one-click',
    `channel/listing-publish/jobs/${ID}`,
    `channel/listing-publish/targets/${ID}/retry`,
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 被误放行了（本模块不占批量发布的命名空间）`).toBe(404);
  }
});
