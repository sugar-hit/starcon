import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { DELETE, GET, POST, PUT } from './[...path]/route';

/**
 * 10.5.1.1 报关单的 api-proxy 白名单断言。
 *
 * <p>白名单**只在浏览器端生效**，`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 CI 全绿，看起来就像「后端没实现这个功能」。
 *
 * <p>所以这里不 grep 正则、不自写一份正则去试，而是**调真正的 route handler**，
 * 断言真正的 HTTP 状态码：放行 = 转发（200），拦截 = 404。
 *
 * <p>两个方向都必须断言，否则一个「全部拒绝」的正则也能让放行用例全绿：
 *
 * <ul>
 *   <li>**放行**：从本模块模型源码里**反向抽取真实调用点**，逐条断言被放行。
 *       手抄清单会漏掉源码里实际存在的形状，所以真的去读源码。
 *   <li>**拒绝**：不存在的子路径、以及兄弟模块（10.4.1.1 头程发运单）的相邻形状仍然被拒。
 * </ul>
 */

// 相对**本文件**定位而不是 process.cwd()：vitest 从仓库根跑时 cwd 是仓库根，
// 从 apps/web 跑时又是 apps/web —— 用 cwd 会在「换个地方跑测试」时静默读不到文件。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/logistics/customs-declaration-model.ts', import.meta.url)),
  'utf8',
);

const BASE = 'warehouse/customs-declarations';
const ID = '11111111-1111-1111-1111-111111111111';
const DOC_ID = '22222222-2222-2222-2222-222222222222';

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
 * 从模型源码里反向抽出**真实存在**的调用路径。
 *
 * <p>模型把所有路径写成 `${BASE}…` 模板串（BASE = `warehouse/customs-declarations`），
 * 所以这里抽的是模板串里 BASE 之后的那一段，再拼回前缀。
 * 刻意不写死清单：写死的清单只会验证「清单和白名单一致」，而真正会出事的是
 * 「源码加了新端点、白名单忘了登记」—— 直接读源码，漏登记会以测试失败暴露出来。
 */
function realCallSites(): string[] {
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/\$\{BASE\}([^'`]*)['"`]/g)) {
    const tail = (match[1] ?? '').trim();
    const cleaned = tail
      .replace(/\$\{encodeURIComponent\(id\)\}/g, ID)
      .replace(/\$\{encodeURIComponent\(documentId\)\}/g, DOC_ID);
    if (cleaned.includes('${')) continue;
    found.add(`${BASE}${cleaned}`);
  }
  // 裸集合路径（建单 POST 走的就是它）
  found.add(BASE);
  return [...found].sort();
}

it('源码里真实存在的每个报关单调用点都被放行并转发', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const sites = realCallSites();
  // 一个都没抽到 = 断言本身失效了（源码改了写法），必须先失败而不是静默通过。
  expect(sites.length, '模型源码里一个调用点都没抽到 —— 断言本身失效了').toBeGreaterThan(0);

  for (const path of sites) {
    const segments = path.split('/');
    const response = await GET(request(path), { params: { path: segments } });
    expect(response.status, `${path} 未被放行`).toBe(200);
    expect(fetcher).toHaveBeenCalledWith(
      expect.stringContaining(':8084/' + path),
      expect.anything(),
    );
  }
});

/**
 * 抽到的路径必须**真的**覆盖本模块的端点，否则「只放行了一个」也算通过。
 * 这条断言是把上面那条用例钉死的关键。
 */
it('放行的形状覆盖本模块全部端点（含三单据定稿这个两段 uuid 路径）', () => {
  expect(realCallSites()).toEqual(
    expect.arrayContaining([
      `${BASE}`,
      `${BASE}/capabilities`,
      `${BASE}/${ID}`,
      `${BASE}/${ID}/documents`,
      `${BASE}/${ID}/results`,
      `${BASE}/${ID}/events`,
      `${BASE}/${ID}/submit-for-review`,
      `${BASE}/${ID}/review`,
      `${BASE}/${ID}/reject-review`,
      `${BASE}/${ID}/withdraw-review`,
      `${BASE}/${ID}/submit`,
      `${BASE}/${ID}/amend`,
      `${BASE}/${ID}/cancel`,
      // 两段 uuid：documents/{docId}/finalize。它最容易被单段 {id} 组吃掉半截。
      `${BASE}/${ID}/documents/${DOC_ID}/finalize`,
    ]),
  );
});

/** 建单走 POST 裸路径（没有子段）。外层组一旦缺 `?`，裸路径连同新增一起被拒。 */
it('建单走 POST 裸路径也被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const segments = BASE.split('/');
  const response = await POST(request(BASE, 'POST'), { params: { path: segments } });
  expect(response.status, 'POST 报关单裸路径未被放行').toBe(200);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8084/' + BASE),
    expect.objectContaining({ method: 'POST' }),
  );
});

/** 改单走 PUT {id}：白名单是**按路径**登记的，不区分方法。 */
it('改单走 PUT {id} 被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const path = `${BASE}/${ID}`;
  const segments = path.split('/');
  const response = await PUT(request(path, 'PUT'), { params: { path: segments } });
  expect(response.status, 'PUT 报关单改单未被放行').toBe(200);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8084/' + path),
    expect.objectContaining({ method: 'PUT' }),
  );
});

/**
 * 报关单是海关举证凭据，**刻意没有 DELETE 段**。
 *
 * <p>真实情况：白名单按路径登记、不区分方法，所以对一条已登记路径发 DELETE，
 * 代理会照样转发。真正挡住删除的是**服务端没有 DELETE 端点**（405）与
 * **V85 的 append-only 触发器**。所以这里断言的是「代理确实会转发」，
 * 并把防线归属写清楚 —— 说清楚比写一句好看的假防线有用。
 */
it('白名单按路径登记：已登记路径的 DELETE 会被转发，防线在服务端与 DB', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const path = `${BASE}/${ID}`;
  const response = await DELETE(request(path, 'DELETE'), { params: { path: path.split('/') } });
  expect(response.status).toBe(200);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8084/' + path),
    expect.objectContaining({ method: 'DELETE' }),
  );
});

it('不存在的报关单子路径仍然被拒 —— 放行不该存在的形状比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 本模块没写的动作：没交付的形状不许出现在界面上。
    `${BASE}/${ID}/delete`,
    `${BASE}/${ID}/force-clear`,
    `${BASE}/${ID}/documents/${DOC_ID}/revoke`,
    `${BASE}/${ID}/documents/${DOC_ID}/finalize/again`,
    // 少一段的 finalize 变体：定稿是 POST .../finalize，不是 .../finalize/xxx
    `${BASE}/${ID}/finalize`,
    // 清单里没有的集合段
    `${BASE}/statistics`,
  ]) {
    const segments = path.split('/');
    const response = await GET(request(path), { params: { path: segments } });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

/**
 * 报关单的白名单没有吃掉兄弟模块 10.4.1.1 头程发运单的形状。
 *
 * <p>两条路径只差一个词（`warehouse/customs-declarations/...` vs
 * `warehouse/first-mile-shipments/{id}/customs`），正则交替一旦写错就会互相吃掉。
 * 头程发运单**没有** `/customs` 子路径（报关资料走的是报关单模块），
 * 这条负向用例在 route.test.ts 里也钉着，两边都不许被放行。
 */
it('头程发运单的形状没有被报关单的白名单吃掉，且它自己没有 /customs 子路径', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'warehouse/first-mile-shipments',
    'warehouse/first-mile-shipments/capabilities',
    `warehouse/first-mile-shipments/${ID}`,
    `warehouse/first-mile-shipments/${ID}/transit-legs`,
  ]) {
    const segments = path.split('/');
    const response = await GET(request(path), { params: { path: segments } });
    expect(response.status, `${path} 未被放行（被报关单段吃掉了？）`).toBe(200);
  }

  for (const path of [
    `warehouse/first-mile-shipments/${ID}/customs`,
    `warehouse/first-mile-shipments/${ID}/declarations`,
  ]) {
    const segments = path.split('/');
    const response = await GET(request(path), { params: { path: segments } });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
});
