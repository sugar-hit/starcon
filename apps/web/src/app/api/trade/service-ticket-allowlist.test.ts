import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { DELETE, GET, POST } from './[...path]/route';

/**
 * 11.4.2.1 工单管理的 api-proxy 白名单**双向**断言（**按模块一份**，
 * 与同目录 `platform-dispute-allowlist.test.ts` 同一手法）。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest 全绿。
 * 判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p>两个方向都要断言：
 * <ul>
 *   <li><b>放行</b>：从本模块自己的模型源码里<b>反向抽取真实调用点</b>再逐条断言被放行。</li>
 *   <li><b>拒绝</b>：刻意不放行的形状仍然 404 —— 尤其是
 *       {@code /delete}：工单是纠纷举证材料，**删掉等于毁掉凭据**，
 *       要停止跟单就关闭它（写明原因）。</li>
 * </ul>
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/aftersales/service-ticket-model.ts', import.meta.url)),
  'utf8',
);

const ID = '11111111-1111-1111-1111-111111111111';

afterEach(() => vi.unstubAllGlobals());

function request(path: string, method = 'GET') {
  return new NextRequest(`http://localhost:3000/api/trade/${path}`, {
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
 * 从模型源码里反向抽取**真实调用点**。
 *
 * ⚠️ 只认 `${base}` 与 id 类的占位。带 `${query(...)}` 的调用点**不是一条路径** ——
 *   它的 query 为空时解析成集合路径，非空时多出 `?a=b`，
 *   而把 `${query(params)}` 直接替换成 id 会凭空造出后端根本不存在的形状，
 *   拿它去断言放行等于断言一条假需求。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/service-tickets';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    // ⚠️ **先把 query 段切掉再查占位**。
    const pathPart = raw.split('?')[0] ?? '';
    const unresolved = pathPart.replace(/\$\{base\}|\$\{id\}/g, '');
    if (unresolved.includes('${')) continue;
    const cleaned = pathPart.replace(/\$\{base\}/g, base).replace(/\$\{id\}/g, ID);
    found.add(cleaned);
  }
  return [...found].sort();
}

it('模型源码里真实存在的每个调用点都被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const sites = realCallSites();
  expect(sites.length, '模型源码里一个调用点都没抽到 —— 断言本身失效了').toBeGreaterThan(0);
  for (const path of sites) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 未被放行（白名单漏登记 = 浏览器里静默 404）`).toBe(200);
  }
  expect(sites).toEqual(
    expect.arrayContaining([
      'trade/service-tickets',
      'trade/service-tickets/capabilities',
      'trade/service-tickets/sla-stats',
      `trade/service-tickets/${ID}`,
      `trade/service-tickets/${ID}/assign`,
      `trade/service-tickets/${ID}/auto-assign`,
      `trade/service-tickets/${ID}/transfer`,
      `trade/service-tickets/${ID}/claim`,
      `trade/service-tickets/${ID}/release`,
      `trade/service-tickets/${ID}/first-reply`,
      `trade/service-tickets/${ID}/status`,
      `trade/service-tickets/${ID}/close`,
      `trade/service-tickets/${ID}/satisfaction`,
      `trade/service-tickets/${ID}/escalate`,
    ]),
  );
});

it('写形状（POST）被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/service-tickets',
    `trade/service-tickets/${ID}/assign`,
    `trade/service-tickets/${ID}/auto-assign`,
    `trade/service-tickets/${ID}/transfer`,
    `trade/service-tickets/${ID}/claim`,
    `trade/service-tickets/${ID}/release`,
    `trade/service-tickets/${ID}/first-reply`,
    `trade/service-tickets/${ID}/status`,
    `trade/service-tickets/${ID}/close`,
    `trade/service-tickets/${ID}/satisfaction`,
    `trade/service-tickets/${ID}/escalate`,
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }

  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/service-tickets'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('刻意不放行的形状仍然被拒 —— 放行后端不存在的映射比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 工单是纠纷举证材料：**刻意不提供删除端点** ——
    //   要停止跟单就关闭它（写明原因），不要删。
    `trade/service-tickets/${ID}/delete`,
    // 15 客户与客服的消息模块**未开发** ⇒ 放行消息入口等于让界面以为
    //   买家消息能自动变成工单。
    `trade/service-tickets/${ID}/messages`,
    // 分配规则读的是 11.1.1.3 的责任人名册，本模块不另建一份规则表。
    `trade/service-tickets/${ID}/assign-rules`,
    // 单字符不是 uuid：`[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}` 会把它放行。
    'trade/service-tickets/c',
    // 拼错的字面量子路径与动作段。
    'trade/service-tickets/capabilities/extra',
    `trade/service-tickets/${ID}/closes`,
    `trade/service-tickets/${ID}/satisfactions`,
    `trade/service-tickets/${ID}/auto-assigns`,
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被放行了，但它在后端不存在`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

/**
 * 白名单是**按路径**判的，不按 HTTP 动词 —— 所以 DELETE 会被放行到后端，由后端回 405。
 *
 * <p>⚠️ 这里刻意**不断言** DELETE 404：代理的 `services[].pattern` 里没有动词，
 * 断言 404 是一条永远红的测试。本模块对工单的删除禁令**由后端承担** ——
 * `ServiceTicketController` 没有 DELETE 映射，
 * 附属事实表的 UPDATE / DELETE 权限也已在 V270 里收回。
 */
it('白名单只判路径不判动词：DELETE 会被转发，由后端回 405', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const response = await DELETE(request(`trade/service-tickets/${ID}`, 'DELETE'), {
    params: Promise.resolve({ path: `trade/service-tickets/${ID}`.split('/') }),
  });
  expect(response.status, 'DELETE 走的是同一条路径白名单').toBe(200);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/service-tickets'),
    expect.objectContaining({ method: 'DELETE' }),
  );
});

it('本模块的正则没有误伤相邻车道已交付的形状', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  // 本条元素插在 11.5.2.1 质量分析之后、11.1.1.2 售后单详情之前，
  // 首 token 形状与其它车道互不相交：本条一律以 `service-tickets/` 开头。
  // ⚠️ 同批另有若干车道也在改这个文件 —— 求并时**不要**把它们的分支删掉，
  //   所以这里把它们当前已交付的形状一并断言，作为「求并后仍然全在」的证据。
  for (const path of [
    'trade/aftersales/capabilities',
    'trade/aftersales/cases',
    `trade/aftersales/cases/${ID}`,
    'trade/platform-disputes/capabilities',
    `trade/platform-disputes/${ID}`,
    'trade/quality-analysis/overview',
    'trade/quality-analysis/defect-observations',
    'trade/rma-authorizations/capabilities',
    `trade/rma-authorizations/${ID}/decide`,
    'trade/return-quality/capabilities',
    'trade/return-receipts/capabilities',
    'trade/supplier-claims/capabilities',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被本模块的正则误伤了`).toBe(200);
  }
});
