import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST, PATCH, DELETE } from './[...path]/route';

/**
 * 11.1.2.1 售后原因分类的 api-proxy 白名单**双向**断言。
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
 *   <li><b>拒绝</b>：刻意不放行的形状仍然 404。本模块最要紧的一条是
 *       {@code categories/{uuid}/move} —— 提升为根需要一个 {@code rootKind}，
 *       而 rootKind 是**不可改列**；放行它会让界面以为「换个口径」是个受支持的按钮，
 *       而点下去只会 404。</li>
 * </ul>
 *
 * <p>⚠️ 还要断言<b>不误吃邻居的路径</b>：11.1.1.1 的
 * {@code aftersales/(capabilities|filters|cases)} 与本模块的
 * {@code aftersales/reasons/…} 都在同一个 {@code prefix: 'trade/'} 下。
 * 两条 services[] 元素是「按顺序逐条匹配、首个命中即返回」，
 * 所以正则必须互斥 —— 一旦其中一条写松了（比如漏了结尾的 {@code $}），
 * 11.1.1.1 的已交付路由会**静默消失**，页面 404 而所有检查全绿。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/aftersales/reason-taxonomy-model.ts', import.meta.url)),
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
    ...(method === 'GET' || method === 'DELETE' ? {} : { body: '{}' }),
  });
}

async function call(path: string, method: 'GET' | 'POST' | 'PATCH' | 'DELETE' = 'GET') {
  const handler = { GET, POST, PATCH, DELETE }[method];
  return handler(request(path, method), {
    params: Promise.resolve({ path: path.split('/') }),
  });
}

/**
 * 从模型源码里反向抽取真实调用点。
 *
 * <p>抽的是 `${base}/xxx` 这种字面量模板：`base` 替换成真实前缀，
 * `${…}` 插值一律换成 ID，query string 去掉（白名单只判 path）。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/aftersales/reasons';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      // ⚠️ 查询串构造器的插值必须**整个去掉**，不能当成普通 `${…}` 替换成一个 ID：
      //   那样会拼出 `…/tree11111111-…` 这种根本不存在的路径，
      //   断言会以「白名单漏登记」的名义失败，而真正的原因是抽取器自己写错了。
      //   （照抄 11.1.1.1 的 aftersalesListQuery 同一处理。）
      .replace(/\$\{aftersalesReasonTreeQuery\([^)]*\)\}/g, '')
      .replace(/\?.*$/, '')
      .replace(/\$\{[^}]*\}/g, ID);
    if (cleaned.includes('${')) continue;
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
    const response = await call(path);
    expect(response.status, `${path} 未被放行（白名单漏登记 = 浏览器里静默 404）`).toBe(200);
  }
  expect(sites).toEqual(
    expect.arrayContaining([
      'trade/aftersales/reasons/capabilities',
      'trade/aftersales/reasons/tree',
      'trade/aftersales/reasons/reports',
    ]),
  );
});

it('写形状（POST / PATCH / DELETE）被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const writes: Array<[string, 'POST' | 'PATCH' | 'DELETE']> = [
    ['trade/aftersales/reasons/categories', 'POST'],
    [`trade/aftersales/reasons/categories/${ID}`, 'PATCH'],
    [`trade/aftersales/reasons/categories/${ID}`, 'DELETE'],
    ['trade/aftersales/reasons/attributions', 'POST'],
    ['trade/aftersales/reasons/mappings', 'POST'],
    [`trade/aftersales/reasons/mappings/${ID}`, 'PATCH'],
    [`trade/aftersales/reasons/mappings/${ID}`, 'DELETE'],
  ];
  for (const [path, method] of writes) {
    const response = await call(path, method);
    expect(response.status, `${method} ${path} 未被放行`).toBe(200);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/aftersales/reasons/categories'),
    expect.objectContaining({ method: 'PATCH' }),
  );
});

it('刻意不放行的形状仍然被拒 —— 放行后端不存在的映射比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 🔴 提升为根要一个 rootKind，而 rootKind 是**不可改列**（改了会把全部历史归因
    //   重新归到另一个口径）。本模块刻意不提供这个端点。
    `trade/aftersales/reasons/categories/${ID}/move`,
    // 导出未交付：导出的可用性取决于能导出哪些维度，而当前报表的口径由 /reports
    // 原样报出就够了。给一个半成品出口比不给更危险。
    'trade/aftersales/reasons/export',
    // 批量归因：逐单判定 + 逐单回报的成本远高于「打开这张单、选主因、次因、给权重」
    // 本身，批量入口只会诱导乱归因。
    'trade/aftersales/reasons/attributions/bulk',
    // 裸路径没有后端映射。
    'trade/aftersales/reasons',
  ]) {
    const response = await call(path);
    expect(response.status, `${path} 被放行了，但它在后端不存在`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

it('不误吃邻居 11.1.1.1 的已交付路由 —— 白名单写松会静默删掉别人的页面', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // ⚠️ 这四条属于 11.1.1.1（售后单列表），**不是**本模块的。
  //   本模块的白名单如果没写结尾的 $ 或写松了，会把它们吃掉 ⇒ 浏览器里 404 而全绿。
  for (const path of [
    'trade/aftersales/capabilities',
    'trade/aftersales/filters',
    'trade/aftersales/cases',
    'trade/aftersales/cases/bulk',
  ]) {
    const response = await call(path);
    expect(response.status, `${path} 被本模块的白名单误伤了（它归 11.1.1.1）`).toBe(200);
  }
  // 反向：11.1.1.1 的白名单也不能吃本模块的路径。
  for (const path of ['trade/aftersales/reasons/tree', 'trade/aftersales/reasons/reports']) {
    const response = await call(path);
    expect(response.status, `${path} 被 11.1.1.1 的白名单误吃了`).toBe(200);
  }
  // 带查询串的读形状也要放行（白名单只判 path，不判 query）。
  const withQuery = await GET(
    new NextRequest(
      `http://localhost:3000/api/trade/trade/aftersales/reasons/tree?rootKind=QUALITY_DEFECT&assignableOnly=true`,
      { headers: { origin: 'http://localhost:3000', cookie: 'starcon_session=private-session' } },
    ),
    { params: Promise.resolve({ path: 'trade/aftersales/reasons/tree'.split('/') }) },
  );
  expect(withQuery.status, '带查询串的分类树读取被拒了').toBe(200);
});
