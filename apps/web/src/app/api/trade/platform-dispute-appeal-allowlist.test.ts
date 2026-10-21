import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { DELETE, GET, POST, PUT } from './[...path]/route';

/**
 * 11.4.1.2 证据与申诉的 api-proxy 白名单**双向**断言（**按模块一份**，
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
 *       {@code /upload}：蓝图加粗句「<b>举证材料必须来自系统记录</b>」正是这条规则的落点，
 *       放行一个「上传任意文件」等于让界面以为随便拼的文件也能当证据。</li>
 * </ul>
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(
    new URL('../../../components/aftersales/platform-dispute-appeal-model.ts', import.meta.url),
  ),
  'utf8',
);

const ID = '11111111-1111-1111-1111-111111111111';
const ITEM = '22222222-2222-2222-2222-222222222222';

afterEach(() => vi.unstubAllGlobals());

function request(path: string, method = 'GET') {
  return new NextRequest(`http://localhost:3000/api/${path}`, {
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
 *   它的 query 为空时解析成集合路径（如 `/platform-dispute-appeals`），非空时多出 `?a=b`，
 *   而把 `${query(params)}` 直接替换成 id 会凭空造出后端根本不存在的形状。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/platform-dispute-appeals';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    // ⚠️ **先把 query 段切掉再查占位**：query 里的占位不是路径的一部分。
    const pathPart = raw.split('?')[0] ?? '';
    const unresolved = pathPart.replace(/\$\{base\}|\$\{id\}|\$\{materialId\}/g, '');
    if (unresolved.includes('${')) continue;
    const cleaned = pathPart
      .replace(/\$\{base\}/g, base)
      .replace(/\$\{id\}/g, ID)
      .replace(/\$\{materialId\}/g, ITEM);
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
      'trade/platform-dispute-appeals/capabilities',
      'trade/platform-dispute-appeals',
      `trade/platform-dispute-appeals/${ID}`,
      `trade/platform-dispute-appeals/${ID}/text`,
      `trade/platform-dispute-appeals/${ID}/materials`,
      `trade/platform-dispute-appeals/${ID}/materials/${ITEM}`,
      `trade/platform-dispute-appeals/${ID}/submit`,
      `trade/platform-dispute-appeals/${ID}/retry-submission`,
      `trade/platform-dispute-appeals/${ID}/submission-attempts`,
      `trade/platform-dispute-appeals/${ID}/platform-reply`,
      `trade/platform-dispute-appeals/${ID}/withdraw`,
    ]),
  );
});

it('写形状（POST / PUT / DELETE）被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/platform-dispute-appeals',
    `trade/platform-dispute-appeals/${ID}/materials`,
    `trade/platform-dispute-appeals/${ID}/submit`,
    `trade/platform-dispute-appeals/${ID}/retry-submission`,
    `trade/platform-dispute-appeals/${ID}/submission-attempts`,
    `trade/platform-dispute-appeals/${ID}/platform-reply`,
    `trade/platform-dispute-appeals/${ID}/withdraw`,
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }

  // 改申诉文本是 PUT。
  const marked = await PUT(request(`trade/platform-dispute-appeals/${ID}/text`, 'PUT'), {
    params: Promise.resolve({ path: `trade/platform-dispute-appeals/${ID}/text`.split('/') }),
  });
  expect(marked.status, 'PUT 改申诉文本未被放行').toBe(200);

  // 移除证据材料是 DELETE。
  const removed = await DELETE(
    request(`trade/platform-dispute-appeals/${ID}/materials/${ITEM}`, 'DELETE'),
    {
      params: Promise.resolve({
        path: `trade/platform-dispute-appeals/${ID}/materials/${ITEM}`.split('/'),
      }),
    },
  );
  expect(removed.status, 'DELETE 移除证据材料未被放行').toBe(200);

  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/platform-dispute-appeals'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('刻意不放行的形状仍然被拒 —— 放行后端不存在的映射比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // ⚠️ 蓝图加粗句「**举证材料必须来自系统记录**」：
    //   放行一个「上传任意文件」等于让界面以为随便拼的文件也能当证据 ——
    //   而临时拼凑的证据在申诉中往往不被采信。
    `trade/platform-dispute-appeals/${ID}/upload`,
    `trade/platform-dispute-appeals/${ID}/files`,
    // channel 服务**没有申诉提交端点** ⇒ 放行一个「同步到平台」等于让界面以为平台会自动收到。
    `trade/platform-dispute-appeals/${ID}/sync`,
    `trade/platform-dispute-appeals/${ID}/call-platform`,
    // 超期自动判负是**上游 11.4.1.1** 的系统行为，本模块只呈现判据结果。
    `trade/platform-dispute-appeals/${ID}/auto-lose`,
    // 终态不可逆：申诉凭据不能删。
    `trade/platform-dispute-appeals/${ID}/delete`,
    // 汇总在列表响应里，没有单独的汇总端点。
    'trade/platform-dispute-appeals/summary',
    // 单字符不是 uuid：`[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}` 会把它放行。
    'trade/platform-dispute-appeals/c',
    // 拼错的字面量子路径与动作段。
    'trade/platform-dispute-appeals/capabilities/extra',
    `trade/platform-dispute-appeals/${ID}/submits`,
    `trade/platform-dispute-appeals/${ID}/materials/${ITEM}/extra`,
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被放行了，但它在后端不存在`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

it('上游 11.4.1.1 的白名单条目没有被本模块的插入影响', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // ⚠️ 本模块的 services[] 元素插在 11.4.1.1 那条**之前** ——
  //   插错位置会让上游的纠纷列表在浏览器里静默 404（typecheck / lint / vitest 全绿）。
  for (const path of [
    'trade/platform-disputes',
    'trade/platform-disputes/capabilities',
    `trade/platform-disputes/${ID}`,
    `trade/platform-disputes/${ID}/submit-evidence`,
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `上游 ${path} 未被放行`).toBe(200);
  }
});
