import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST } from './[...path]/route';

/**
 * 7.8.1.2 优先级与保底的 api-proxy 白名单反向断言。
 *
 * <p>白名单**只在浏览器端生效**，`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 CI 全绿。所以这里逐条钉住。
 *
 * <p>两条方向都要断言：
 *
 * <ul>
 *   <li>**放行**：从本模块自己的前端源码里**反向抽取真实调用点**，逐条断言被放行。
 *       手抄一份路径清单会漏掉源码里实际存在的形状，所以这里真的去读源码。
 *   <li>**拒绝**：未交付模块的路径仍然被拒。放行不该存在的路径比 404 更糟 ——
 *       它会让界面以为「已经可用」。
 * </ul>
 */

// 相对**本文件**定位而不是 process.cwd()：vitest 从仓库根跑时 cwd 是仓库根，
// 从 apps/web 跑时又是 apps/web —— 用 cwd 会在「换个地方跑测试」时静默读不到文件。
const SOURCE = readFileSync(
  fileURLToPath(
    new URL('../../../components/inventory/allocation-priority-model.ts', import.meta.url),
  ),
  'utf8',
);

const ID = '11111111-1111-1111-1111-111111111111';

afterEach(() => vi.unstubAllGlobals());

function request(path: string, method = 'GET') {
  return new NextRequest(`http://localhost:3000/api/warehouse/${path}`, {
    method,
    headers: {
      origin: 'http://localhost:3000',
      cookie: 'starcon_session=private-session',
      // 伪造的租户头与内部键：代理必须忽略它们，转发时不带。
      'X-Tenant-Id': 'forged',
      'X-Internal-Key': 'forged',
    },
    ...(method === 'GET' ? {} : { body: '{}' }),
  });
}

/**
 * 从模型源码里反向抽出**真实存在**的路径字面量。
 *
 * <p>刻意不写死清单：写死的清单只会验证「清单和白名单一致」，
 * 而真正会出事的是「源码加了新形状、白名单忘了登记」。这里直接读源码，
 * 所以漏登记会以测试失败的形式暴露出来。
 */
function realCallSites(): string[] {
  const literal = /['`](warehouse\/inventory\/allocation-priority[^'`]*?)['`]/g;
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(literal)) {
    const raw = match[1];
    if (!raw) continue;
    // 模板串里的 ${id} 换成真实 uuid，$ 开头的查询参数整段去掉。
    const cleaned = raw
      .replace(/\$\{id\}/g, ID)
      .replace(/\$\{query\([^)]*\)\}/g, '')
      .replace(/\?.*$/, '');
    if (cleaned.includes('${')) continue;
    found.add(cleaned);
  }
  return [...found].sort();
}

it('7.8.1.2 源码里真实存在的每个调用点都被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const sites = realCallSites();
  expect(sites.length, '模型源码里一个调用点都没抽到 —— 断言本身失效了').toBeGreaterThan(0);
  for (const path of sites) {
    const segments = path.split('/');
    const response = await GET(request(path), { params: Promise.resolve({ path: segments }) });
    expect(response.status, `${path} 未被放行`).toBe(200);
  }
  // 抽到的路径必须**真的**覆盖本模块的七个形状，否则「放行了三个」也算通过。
  expect(sites).toEqual(
    expect.arrayContaining([
      'warehouse/inventory/allocation-priority/capabilities',
      'warehouse/inventory/allocation-priority/floors',
      `warehouse/inventory/allocation-priority/floors/${ID}`,
      `warehouse/inventory/allocation-priority/floors/${ID}/versions`,
      `warehouse/inventory/allocation-priority/floors/${ID}/retire`,
      'warehouse/inventory/allocation-priority/calculation',
      'warehouse/inventory/allocation-priority/runs',
      'warehouse/inventory/allocation-priority/resolutions',
    ]),
  );
});

/**
 * 写动作全部是 POST，且**新建走裸路径** `…/floors`（没有子段）。
 *
 * <p>7.8.1.1 的外层组一度缺 `?`，强制要求子路径，于是裸列表与新增一并被拒，
 * 新增分配策略在浏览器里静默 404 而 CI 全绿。这条用例把同一个坑钉住。
 */
it('新建保底走 POST 裸路径也被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const segments = ['warehouse', 'inventory', 'allocation-priority', 'floors'];
  const response = await POST(request(segments.join('/'), 'POST'), {
    params: Promise.resolve({ path: segments }),
  });
  expect(response.status, 'POST …/floors 裸路径未被放行').toBe(200);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8084/warehouse/inventory/allocation-priority/floors'),
    expect.objectContaining({ method: 'POST' }),
  );
});

/**
 * 白名单是**按路径**登记的，不区分方法 —— 这一点必须钉住，否则会写出
 * 「白名单挡住了 DELETE」这种**假的防线说明**。
 *
 * <p>真实情况：对一条已登记路径发 DELETE，代理会照样转发给 warehouse 服务，
 * 真正挡住删除的是**服务端没有 DELETE 端点**（405）与 **V76 的 REVOKE DELETE +
 * append-only 触发器**（后端集成测试里逐条验过）。所以这里断言的是
 * 「代理确实会转发」，并把防线归属写在注释里 —— 说清楚比写一句好看的假防线有用。
 */
it('白名单按路径登记：同一条已登记路径的 DELETE 会被转发，防线在服务端与 DB', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const path = `warehouse/inventory/allocation-priority/floors/${ID}`;
  const response = await GET(request(path, 'DELETE'), {
    params: Promise.resolve({ path: path.split('/') }),
  });
  expect(response.status).toBe(200);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8084/' + path),
    expect.objectContaining({ method: 'DELETE' }),
  );
});

it('未交付模块的路径仍然被拒 —— 放行不该存在的形状比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  // 7.8.2.1 分摊结果查看与 7.8.2.2 缺货/饥饿预警都未交付。
  // 本模块只写 resolution 列表，没有单条详情，更没有预警端点。
  for (const path of [
    'warehouse/inventory/allocation-priority/starved',
    'warehouse/inventory/allocation-priority/warnings',
    `warehouse/inventory/allocation-priority/resolutions/${ID}`,
    `warehouse/inventory/allocation-priority/runs/${ID}`,
  ]) {
    const segments = path.split('/');
    const response = await GET(request(path), { params: Promise.resolve({ path: segments }) });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

it('7.8.1.1 的分摊策略路径没有被 7.8.1.2 的白名单吃掉', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  // 两条路径只差一个前缀字母（allocation-polic**y** vs allocation-polic**ies**），
  // 正则交替一旦写错就会互相吃掉。这里把兄弟模块的形状一起钉住。
  for (const path of [
    'warehouse/inventory/allocation-policies',
    'warehouse/inventory/allocation-policies/capabilities',
    `warehouse/inventory/allocation-policies/${ID}`,
    `warehouse/inventory/allocation-policies/${ID}/retire`,
    'warehouse/inventory/allocation-sales',
  ]) {
    const segments = path.split('/');
    const response = await GET(request(path), { params: Promise.resolve({ path: segments }) });
    expect(response.status, `${path} 未被放行`).toBe(200);
  }
});
