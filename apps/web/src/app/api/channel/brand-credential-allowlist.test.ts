import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { DELETE, GET, POST, PUT } from './[...path]/route';

/**
 * 16.4.2.2 授权凭证的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest 全绿。所以这里逐条钉住，
 * 而且判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从本模块自己的模型源码里<b>反向抽取真实调用点</b>再逐条断言被放行 ——
 *       写死一份清单只会验证「清单与白名单一致」，而真正会出事的是
 *       「源码加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：未交付 / 不存在的形状仍然 404。放行不该存在的路径比 404 更糟 ——
 *       它会让界面以为「已经可用」。</li>
 * </ul>
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(
    new URL('../../../components/brand-credential/brand-credential-model.ts', import.meta.url),
  ),
  'utf8',
);

const ID = '11111111-1111-1111-1111-111111111111';

afterEach(() => vi.unstubAllGlobals());

function request(path: string, method = 'GET') {
  return new NextRequest(`http://localhost:3000/api/channel/${path}`, {
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
 * 从模型源码里反向抽出**真实存在**的调用点。
 *
 * <p>模型里的端点既有字面量（{@code 'channel/brand-credentials/capabilities'}）也有模板串
 * （{@code `channel/brand-credentials/credentials/${id}`}），两种都要抽 ——
 * 只认字面量会把带 id 的那一半漏成零条，然后「只放行了几个」也算通过。
 */
function realCallSites(): string[] {
  const base = 'channel/brand-credentials';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/'([^']*)'/g)) {
    const raw = match[1];
    if (!raw || !raw.startsWith(base)) continue;
    // 🔴 裸前缀常量（BASE 本身）**不是调用点**：后端没有「一条汇总列表接口」，
    //    裸路径刻意不放行（下面单独有一条用例钉住它必须 404）。
    if (raw === base) continue;
    found.add(raw);
  }
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw) continue;
    // 模型里用 `${BASE}` 插值拼路径（BASE 是导出的前缀常量），先把它还原成真实前缀
    const expanded = raw.replace(/\$\{BASE\}/g, base);
    if (!expanded || !expanded.startsWith(base)) continue;
    const cleaned = expanded
      // `${query}` 是「可选的查询串」，展开后为空（不是一段路径）
      .replace(/\$\{query\}/g, '')
      .replace(/\$\{[^}]*\}/g, ID)
      .replace(/\?.*$/, '');
    if (cleaned.includes('${')) continue;
    if (cleaned === base) continue;
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
  // 抽到的路径必须**真的**覆盖本模块的形状，否则「只放行了几个」也算通过。
  expect(sites).toEqual(
    expect.arrayContaining([
      'channel/brand-credentials/capabilities',
      'channel/brand-credentials/overview',
      'channel/brand-credentials/credentials',
      `channel/brand-credentials/credentials/${ID}`,
      `channel/brand-credentials/credentials/${ID}/revoke`,
      `channel/brand-credentials/credentials/${ID}/verify`,
      `channel/brand-credentials/credentials/${ID}/signatories`,
      `channel/brand-credentials/credentials/${ID}/documents`,
      'channel/brand-credentials/credentials/verify-batch',
      'channel/brand-credentials/verifications',
      'channel/brand-credentials/authorizations',
    ]),
  );
  // 🔴 `documents/{id}` 与 `documents/{id}/file` **不在上面的清单里** ——
  //    后端确实实现了这两个端点（给直连的归档任务 / 争议取证用），白名单也放行了，
  //    但**页面的模型不调它们**：浏览器同源代理不透传二进制（只透传 text/csv、
  //    text/vnd.zpl、text/html），页面上的文件清单显示的是元数据 + sha256 摘要。
  //    把它们写进「模型真实调用点」的清单里，就是让断言变成假的。
});

it('写形状（POST / PUT / DELETE）被放行，且真的转发到 8083', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'channel/brand-credentials/credentials',
    'channel/brand-credentials/credentials/verify-batch',
    `channel/brand-credentials/credentials/${ID}/verify`,
    `channel/brand-credentials/credentials/${ID}/revoke`,
    `channel/brand-credentials/credentials/${ID}/documents`,
    `channel/brand-credentials/credentials/${ID}/signatories`,
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  await PUT(request(`channel/brand-credentials/credentials/${ID}`, 'PUT'), {
    params: Promise.resolve({
      path: `channel/brand-credentials/credentials/${ID}`.split('/'),
    }),
  });
  // 移除签署方走 DELETE（凭证本身与文件版本、核验记录都不受影响）
  await DELETE(request(`channel/brand-credentials/credentials/${ID}/signatories/${ID}`, 'DELETE'), {
    params: Promise.resolve({
      path: `channel/brand-credentials/credentials/${ID}/signatories/${ID}`.split('/'),
    }),
  });
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8083/channel/brand-credentials/credentials'),
    expect.objectContaining({ method: 'POST' }),
  );
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8083/channel/brand-credentials/credentials'),
    expect.objectContaining({ method: 'DELETE' }),
  );
});

it('未交付 / 不存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 裸路径：本模块没有裸路径资源，列表在 credentials 上。
    'channel/brand-credentials',
    // 合规台账不做物理删除，撤销走 revoke。
    `channel/brand-credentials/credentials/${ID}/delete`,
    // 撤销是终态，不提供「恢复」。
    `channel/brand-credentials/credentials/${ID}/restore`,
    // 文件版本只追加，没有删除。
    `channel/brand-credentials/documents/${ID}/delete`,
    // 核验记录只有列表，没有单条详情端点。
    `channel/brand-credentials/verifications/${ID}`,
    // 文件本体在 documents/{id} 上，不在 credentials/{id}/documents/{docId}。
    `channel/brand-credentials/credentials/${ID}/documents/${ID}`,
    // 非 UUID 段不应被 uuid 分支吃掉。
    'channel/brand-credentials/credentials/not-a-uuid',
    // verify 是一个动作，不是一段资源的子路径。
    `channel/brand-credentials/credentials/${ID}/verify/extra`,
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 裸路径 `brand-credentials` 本身**必须被拒**：后端没有这个映射，
 * 放行它会让界面以为「一条不存在的汇总接口」可用。
 */
it('裸路径 brand-credentials 被拒：既不转发，也不冒充成上游错误', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const path = 'channel/brand-credentials';
  const response = await GET(request(path), { params: Promise.resolve({ path: path.split('/') }) });
  expect(response.status, '裸路径不应被白名单放行').toBe(404);
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 🔴 本车道往 `services[]` 里**新增了一条 channel/ 元素**（不并既有 channel 分支的 alternation），
 * 所以必须钉住「既有 channel 模块的入口没有被本条吃掉」——
 * 同一前缀下多条元素，谁先命中取决于顺序，这里逐条反查既有模块的入口。
 */
it('既有 channel 模块的入口没有被本车道新增的元素误伤', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 🔴 16.4.2.1 品牌授权（同一 L3 下的兄弟模块，根路径只差一个字）
    'channel/brand-authorizations/capabilities',
    `channel/brand-authorizations/authorizations/${ID}`,
    // 16.2.2.1 合规预检（卷 16 的同系统模块，根路径不同）
    'channel/compliance-precheck/capabilities',
    // 4.2.3.2 合规预检（卷 04 的同系统模块，根路径不同）
    'channel/listing-compliance-precheck/capabilities',
    // 16.2.2.2 认证与资质
    'channel/product-certifications/capabilities',
    // 4.1.1.1 listing 列表
    'channel/listings',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被本车道新增的白名单条目误伤了`).toBe(200);
  }
});
