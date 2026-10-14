import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { DELETE, GET, POST, PUT } from './[...path]/route';

/**
 * 10.5.2.1 贸易单证的 api-proxy 白名单断言。
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
 *   <li>**拒绝**：不存在的子路径、以及兄弟模块（10.5.1.1 报关单 / 10.5.1.3 关税 /
 *       10.4.1.1 头程发运单）的相邻形状仍然被拒。
 * </ul>
 */

// 相对**本文件**定位而不是 process.cwd()：vitest 从仓库根跑时 cwd 是仓库根，
// 从 apps/web 跑时又是 apps/web —— 用 cwd 会在「换个地方跑测试」时静默读不到文件。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/logistics/trade-document-model.ts', import.meta.url)),
  'utf8',
);

const BASE = 'warehouse/trade-documents';
const ID = '11111111-1111-1111-1111-111111111111';
const DOC_ID = '22222222-2222-2222-2222-222222222222';
const RULE_ID = '33333333-3333-3333-3333-333333333333';

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
 * <p>模型把所有路径写成 `${BASE}…` 模板串（BASE = `warehouse/trade-documents`），
 * 所以这里抽的是模板串里 BASE 之后的那一段，再拼回前缀。
 * 刻意不写死清单：写死的清单只会验证「清单和白名单一致」，而真正会出事的是
 * 「源码加了新端点、白名单忘了登记」—— 直接读源码，漏登记会以测试失败暴露出来。
 */
function realCallSites(): string[] {
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/\$\{BASE\}([^'`]*)['"`]/g)) {
    const tail = (match[1] ?? '').trim();
    // 按**位置**而不是按名字替换：模型里单证包与单证两个 id 参数分别叫 setId / documentId，
    // 写死名字列表漏掉一个就会让那条调用点被当成「还带占位符」跳过 ——
    // 结果是断言自己悄悄失效（少断言了一条端点）而不是失败。
    const cleaned = tail
      .replace(/\$\{encodeURIComponent\(setId\)\}/g, ID)
      .replace(/\$\{encodeURIComponent\(documentId\)\}/g, DOC_ID)
      .replace(/\$\{encodeURIComponent\(id\)\}/g, ID);
    if (cleaned.includes('${')) continue;
    found.add(`${BASE}${cleaned}`);
  }
  // 裸集合路径（建包 POST 走的就是它）
  found.add(BASE);
  return [...found].sort();
}

it('源码里真实存在的每个贸易单证调用点都被放行并转发', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const sites = realCallSites();
  // 一个都没抽到 = 断言本身失效了（源码改了写法），必须先失败而不是静默通过。
  expect(sites.length, '模型源码里一个调用点都没抽到 —— 断言本身失效了').toBeGreaterThan(0);

  for (const path of sites) {
    const segments = path.split('/');
    const response = await GET(request(path), { params: Promise.resolve({ path: segments }) });
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
it('放行的形状覆盖本模块全部端点（含三段 uuid 的定稿与优惠确认路径）', () => {
  expect(realCallSites()).toEqual(
    expect.arrayContaining([
      `${BASE}`,
      `${BASE}/capabilities`,
      `${BASE}/${ID}`,
      `${BASE}/${ID}/documents`,
      `${BASE}/${ID}/checks`,
      `${BASE}/${ID}/reminders`,
      `${BASE}/${ID}/events`,
      `${BASE}/${ID}/submit-for-review`,
      `${BASE}/${ID}/review`,
      `${BASE}/${ID}/reject-review`,
      `${BASE}/${ID}/withdraw-review`,
      `${BASE}/${ID}/check`,
      `${BASE}/${ID}/submit`,
      `${BASE}/${ID}/acknowledge`,
      `${BASE}/${ID}/send-back`,
      `${BASE}/${ID}/cancel`,
      // 两段 / 三段 uuid：最容易被单段 {id} 组吃掉半截
      `${BASE}/${ID}/documents/${DOC_ID}/finalize`,
      `${BASE}/${ID}/documents/${DOC_ID}/preference-claim`,
      `${BASE}/${ID}/documents/${DOC_ID}/preference-confirm`,
      // 集合资源与它自己的 {id}
      `${BASE}/rules`,
      `${BASE}/rules/${ID}`,
      `${BASE}/templates`,
      `${BASE}/templates/${ID}`,
      `${BASE}/certifications`,
      `${BASE}/certifications/${ID}`,
      `${BASE}/preference-rules`,
      `${BASE}/preference-rules/${ID}`,
      `${BASE}/reminders/${ID}/resolve`,
    ]),
  );
});

/** 建包走 POST 裸路径（没有子段）。外层组一旦缺 `?`，裸路径连同新增一起被拒。 */
it('建包走 POST 裸路径也被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const segments = BASE.split('/');
  const response = await POST(request(BASE, 'POST'), {
    params: Promise.resolve({ path: segments }),
  });
  expect(response.status, 'POST 单证包裸路径未被放行').toBe(200);
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
  const response = await PUT(request(path, 'PUT'), {
    params: Promise.resolve({ path: path.split('/') }),
  });
  expect(response.status, 'PUT 单证包改单未被放行').toBe(200);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8084/' + path),
    expect.objectContaining({ method: 'PUT' }),
  );
});

/** 规则 / 模板 / 认证 / 优惠规则的 PUT 同样放行 —— 四组都是「集合 + POST + {id} 的 PUT」。 */
it('四组租户可维护资源的 PUT {id} 都被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    `${BASE}/rules/${RULE_ID}`,
    `${BASE}/templates/${RULE_ID}`,
    `${BASE}/certifications/${RULE_ID}`,
    `${BASE}/preference-rules/${RULE_ID}`,
  ]) {
    const response = await PUT(request(path, 'PUT'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 未被放行`).toBe(200);
  }
});

/**
 * 贸易单证是 append-only 的举证凭据，**刻意没有 DELETE 段**。
 *
 * <p>真实情况：白名单按路径登记、不区分方法，所以对一条已登记路径发 DELETE，
 * 代理会照样转发。真正挡住删除的是**服务端没有 DELETE 端点**（405）与
 * **V91 的 append-only 约束**。所以这里断言的是「代理确实会转发」，
 * 并把防线归属写清楚 —— 说清楚比写一句好看的假防线有用。
 */
it('白名单按路径登记：已登记路径的 DELETE 会被转发，防线在服务端与 DB', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const path = `${BASE}/${ID}`;
  const response = await DELETE(request(path, 'DELETE'), {
    params: Promise.resolve({ path: path.split('/') }),
  });
  expect(response.status).toBe(200);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8084/' + path),
    expect.objectContaining({ method: 'DELETE' }),
  );
});

it('不存在的贸易单证子路径仍然被拒 —— 放行不该存在的形状比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 本模块没写的动作：没交付的形状不许出现在界面上。
    `${BASE}/${ID}/delete`,
    `${BASE}/${ID}/force-submit`,
    // 定稿 / 优惠确认都恰好三段，多挂一段就该被拒
    `${BASE}/${ID}/documents/${DOC_ID}/finalize/again`,
    `${BASE}/${ID}/documents/${DOC_ID}/preference-confirm/reject`,
    // 少一段的变体：定稿是三段路径，不是两段
    `${BASE}/${ID}/finalize`,
    `${BASE}/${ID}/preference-claim`,
    // 提醒只有 resolve 一个动作段
    `${BASE}/reminders/${ID}/delete`,
    `${BASE}/reminders/${ID}/resolve/again`,
    // 清单里没有的集合段
    `${BASE}/statistics`,
    // 认证只有登记，没有「自动同步 16 合规」的形状（上游未交付）
    `${BASE}/certifications/sync`,
  ]) {
    const segments = path.split('/');
    const response = await GET(request(path), { params: Promise.resolve({ path: segments }) });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

/**
 * 两条**动态查询串**的集合路径单独断言。
 *
 * <p>它们在模型里写成 `` `${BASE}/reminders${query}` `` —— 查询串是运行时拼的，
 * 所以上面那条「从源码反向抽取」**按设计抽不到**（10.5.1.1 报关单的集合路径同理）。
 * 抽不到就意味着少断言一条路径，所以这里把带查询串的真实形状显式放行一遍。
 */
it('带查询串的集合路径（reminders / preference-confirmations）也被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    `${BASE}/reminders?status=OPEN&severity=BLOCKER&page=0&size=20`,
    `${BASE}/preference-confirmations?claimedOnly=true&page=0&size=20`,
    `${BASE}/rules?countryCode=US&page=0&size=20`,
  ]) {
    // 查询串走 request.nextUrl.search，匹配走 params.path —— 与 route handler 同一套读法。
    // 把 `?…` 混进 params 会让正则看到 `reminders?status=…` 而 404，那是测试写错，不是白名单漏了。
    const pathname = path.split('?')[0] ?? path;
    const response = await GET(request(path), {
      params: Promise.resolve({ path: pathname.split('/') }),
    });
    expect(response.status, `${path} 未被放行`).toBe(200);
    // 查询串必须原样转发到上游，否则筛选条件在服务端会静默丢失
    expect(fetcher).toHaveBeenCalledWith(expect.stringContaining(path), expect.anything());
  }
});

/**
 * 贸易单证的白名单没有吃掉兄弟模块 10.5.1.1 报关单 / 10.5.1.3 关税 / 10.4.1.1 头程发运单的形状。
 *
 * <p>三条路径都只差一个词（`warehouse/trade-documents/...` vs `warehouse/customs-declarations/...`
 * vs `warehouse/duty-taxes/...` vs `warehouse/first-mile-shipments/...`），正则交替一旦写错
 * 就会互相吃掉。所以这里把兄弟模块的**每一个**已登记形状都放行一遍。
 */
it('兄弟模块的形状没有被贸易单证的白名单吃掉', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'warehouse/customs-declarations',
    'warehouse/customs-declarations/capabilities',
    `warehouse/customs-declarations/${ID}`,
    `warehouse/customs-declarations/${ID}/documents`,
    `warehouse/customs-declarations/${ID}/submit`,
    'warehouse/duty-taxes',
    'warehouse/duty-taxes/capabilities',
    'warehouse/duty-taxes/variance-analysis',
    `warehouse/duty-taxes/${ID}`,
    `warehouse/duty-taxes/${ID}/recalculate`,
    'warehouse/first-mile-shipments',
    'warehouse/first-mile-shipments/capabilities',
    `warehouse/first-mile-shipments/${ID}`,
    `warehouse/first-mile-shipments/${ID}/transit-legs`,
  ]) {
    const segments = path.split('/');
    const response = await GET(request(path), { params: Promise.resolve({ path: segments }) });
    expect(response.status, `${path} 未被放行（被贸易单证段吃掉了？）`).toBe(200);
  }

  // 反向用例：兄弟模块没有的贸易单证子路径仍然被拒（报关单里不存在 /trade-documents）
  for (const path of [
    `warehouse/customs-declarations/${ID}/trade-documents`,
    `warehouse/first-mile-shipments/${ID}/trade-documents`,
    `warehouse/duty-taxes/${ID}/trade-documents`,
  ]) {
    const segments = path.split('/');
    const response = await GET(request(path), { params: Promise.resolve({ path: segments }) });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
});
