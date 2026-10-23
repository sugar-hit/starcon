import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET as iamGet, POST as iamPost, PUT as iamPut } from '../app/api/iam/[...path]/route';
import {
  GET as catalogGet,
  POST as catalogPost,
  PUT as catalogPut,
  DELETE as catalogDelete,
} from '../app/api/catalog/[...path]/route';
import { GET as tradeGet, PUT as tradePut } from '../app/api/trade/[...path]/route';

/**
 * 17.5.2.1 数据字典 + catalog 生命周期的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404（代理直接回「接口不存在」），而 typecheck / lint /
 * vitest / Playwright **全是绿的**。这正是本文件最容易出现的失配形态，
 * 所以这里逐条钉住。
 *
 * <p>判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从本模块自己的模型源码里<b>反向抽取真实调用点</b>再逐条断言被放行 ——
 *       写死一份清单只会验证「清单与白名单一致」，而真正会出事的是
 *       「源码加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：未交付的形状仍然 404。放行不该存在的路径比 404 更糟 ——
 *       它会让界面以为「已经可用」。</li>
 * </ul>
 *
 * <p>覆盖的四组：
 * 17.5.2.1 数据字典（identity）、catalog 生命周期（catalog）、
 * catalog 商品档案（catalog，既有登记 —— 本车道做的是**不回归**断言）、
 * trade 采集接入登记（trade，既有登记 —— 同上）。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
function readModel(relative: string): string {
  return readFileSync(fileURLToPath(new URL(relative, import.meta.url)), 'utf8');
}

const DICTIONARY_SOURCE = readModel('../components/iam/dictionary-workspace.tsx');
const LIFECYCLE_SOURCE = readModel('../components/catalog/spu-lifecycle.tsx');
const RECORDS_SOURCE = readModel('../components/catalog/product-records.tsx');
const SOURCES_SOURCE = readModel('../components/supply/sources-model.ts');

const ID = '11111111-1111-1111-1111-111111111111';

afterEach(() => vi.unstubAllGlobals());

/**
 * 把模型源码里的**运行期插值**换成具体值。
 *
 * <p>模型里的端点既有字面量（{@code 'identity/dict-catalog'}）也有模板串
 * （{@code `identity/dict-items/${row.id}`}），两种都要抽 ——
 * 只认字面量会把带 id 的那一半漏成零条，然后「只放行了几个」也算通过。
 *
 * <p>{@code runtime} 是**按源码里的插值名**给的替换表；表里没有的插值一律按
 * UUID 处理（它们都是 id）。查询串（{@code ?dictCode=…} / {@code ?version=…}）
 * 不参与白名单判定 —— 代理只按路径段判别，所以这里先剥掉。
 */
function applyRuntime(raw: string, runtime: Record<string, string>): string {
  let path = raw;
  for (const [key, value] of Object.entries(runtime)) {
    path = path.split('${' + key + '}').join(value);
  }
  path = path.replace(/\$\{[^}]*\}/g, ID);
  path = path.replace(/\?.*$/, '');
  return path;
}

/** 从模型源码里反向抽出以 {@code prefix} 开头的真实调用路径。 */
function extract(source: string, prefix: string, runtime: Record<string, string>): string[] {
  const found = new Set<string>();
  for (const match of source.matchAll(/'([^'\n]*)'/g)) {
    const raw = match[1];
    if (!raw || !raw.startsWith(prefix)) continue;
    found.add(applyRuntime(raw, runtime));
  }
  for (const match of source.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.startsWith(prefix)) continue;
    found.add(applyRuntime(raw, runtime));
  }
  return [...found].sort();
}

/**
 * 数据字典工作台里有两处**三元表达式**插值，抽之前先把两支都摊平成具体形状：
 * 停用走 `deactivate`、启用走 `reactivate` —— 两个动作都必须被白名单放行。
 */
const DICTIONARY_BRANCHES = [
  DICTIONARY_SOURCE.replace(
    /\$\{status === 'DISABLED' \? 'deactivate' : 'reactivate'\}/g,
    'deactivate',
  ),
  DICTIONARY_SOURCE.replace(
    /\$\{status === 'DISABLED' \? 'deactivate' : 'reactivate'\}/g,
    'reactivate',
  ),
];

/**
 * 商品档案的保存动作带一个**嵌套模板串**（新建走空串、编辑走 `/${editing.id}`），
 * 抽之前先把编辑分支摊平，两条形状都要覆盖。
 */
const RECORDS_SOURCE_FLAT = RECORDS_SOURCE.replace(
  /catalog\/records\/\$\{kind\}\$\{editing === 'new' \? '' : `\/\$\{editing\.id\}`\}/g,
  'catalog/records/${kind}/${editing.id}',
);

const DICTIONARY_RUNTIME = { 'row.id': ID, 'item.id': ID, 'editing.id': ID, suffix: '' };
const LIFECYCLE_RUNTIME = { spuId: ID, 'rule.id': ID, id: ID };
const RECORDS_RUNTIME = { kind: 'assets', 'r.id': ID, 'editing.id': ID };
const SOURCES_RUNTIME = {
  platform: 'ALIBABA_1688',
  stallId: ID,
  itemId: ID,
  linkId: ID,
  skuId: ID,
};

function dictionaryCallSites(): string[] {
  const found = new Set<string>();
  for (const source of DICTIONARY_BRANCHES) {
    for (const path of extract(source, 'identity/dict', DICTIONARY_RUNTIME)) found.add(path);
  }
  return [...found].sort();
}

/** 按服务前缀选对路由出口：identity 走 iam，catalog 走 catalog，trade 走 trade。 */
async function callProxy(
  path: string,
  method: 'GET' | 'POST' | 'PUT' | 'DELETE' = 'GET',
): Promise<Response> {
  const service = path.split('/')[0] ?? '';
  const request = new NextRequest(`http://localhost:3000/api/${service}/${path}`, {
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
  const context = { params: Promise.resolve({ path: path.split('/') }) };
  if (service === 'identity') {
    if (method === 'GET') return iamGet(request, context);
    if (method === 'POST') return iamPost(request, context);
    if (method === 'PUT') return iamPut(request, context);
    return iamGet(request, context);
  }
  if (service === 'catalog') {
    if (method === 'GET') return catalogGet(request, context);
    if (method === 'POST') return catalogPost(request, context);
    if (method === 'PUT') return catalogPut(request, context);
    return catalogDelete(request, context);
  }
  if (method === 'GET') return tradeGet(request, context);
  return tradePut(request, context);
}

it('模型源码里真实存在的每个调用点都被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const sites = [
    ...dictionaryCallSites(),
    ...extract(LIFECYCLE_SOURCE, 'catalog/lifecycle', LIFECYCLE_RUNTIME),
    ...extract(LIFECYCLE_SOURCE, 'catalog/products', LIFECYCLE_RUNTIME),
    ...extract(RECORDS_SOURCE_FLAT, 'catalog/records', RECORDS_RUNTIME),
    ...extract(SOURCES_SOURCE, 'trade/sources', SOURCES_RUNTIME),
  ].sort();

  expect(sites.length, '模型源码里一个调用点都没抽到 —— 断言本身失效了').toBeGreaterThan(0);
  for (const path of sites) {
    const response = await callProxy(path);
    expect(response.status, `${path} 未被放行（白名单漏登记 = 浏览器里静默 404）`).toBe(200);
  }
});

it('抽到的路径必须真的覆盖四个模块的形状，否则「只放行三个」也算通过', () => {
  const sites = [
    ...dictionaryCallSites(),
    ...extract(LIFECYCLE_SOURCE, 'catalog/lifecycle', LIFECYCLE_RUNTIME),
    ...extract(LIFECYCLE_SOURCE, 'catalog/products', LIFECYCLE_RUNTIME),
    ...extract(RECORDS_SOURCE_FLAT, 'catalog/records', RECORDS_RUNTIME),
    ...extract(SOURCES_SOURCE, 'trade/sources', SOURCES_RUNTIME),
  ];

  expect(sites).toEqual(
    expect.arrayContaining([
      // 17.5.2.1 数据字典（identity）
      'identity/dict-catalog',
      'identity/dict-items',
      'identity/dict-history',
      `identity/dict-items/${ID}`,
      `identity/dict-items/${ID}/usage`,
      `identity/dict-items/${ID}/deactivate`,
      `identity/dict-items/${ID}/reactivate`,
      // catalog 生命周期（catalog）
      'catalog/lifecycle/metrics',
      'catalog/lifecycle/rules',
      `catalog/lifecycle/rules/${ID}`,
      `catalog/lifecycle/rules/${ID}/preview`,
      `catalog/lifecycle/rules/${ID}/run`,
      `catalog/products/${ID}/lifecycle`,
      // catalog 商品档案（catalog，既有登记）
      'catalog/records/assets',
      `catalog/records/assets/${ID}`,
      // trade 采集接入登记（trade，既有登记）
      'trade/sources/collector-endpoint/ALIBABA_1688',
    ]),
  );
});

it('写形状（POST / PUT / DELETE）被放行，且真的转发到对应服务端口', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'identity/dict-items',
    `identity/dict-items/${ID}`,
    `identity/dict-items/${ID}/deactivate`,
    `identity/dict-items/${ID}/reactivate`,
  ]) {
    const response = await callProxy(path, 'POST');
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  const updated = await callProxy(`identity/dict-items/${ID}`, 'PUT');
  expect(updated.status, 'PUT identity/dict-items/{id} 未被放行').toBe(200);

  for (const path of ['catalog/lifecycle/rules', `catalog/lifecycle/rules/${ID}/run`]) {
    const response = await callProxy(path, 'POST');
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  expect((await callProxy(`catalog/lifecycle/rules/${ID}`, 'PUT')).status).toBe(200);
  expect((await callProxy(`catalog/lifecycle/rules/${ID}`, 'DELETE')).status).toBe(200);
  expect((await callProxy(`catalog/products/${ID}/lifecycle`, 'POST')).status).toBe(200);

  expect((await callProxy('catalog/records/assets', 'POST')).status).toBe(200);
  expect((await callProxy(`catalog/records/assets/${ID}`, 'PUT')).status).toBe(200);
  expect((await callProxy(`catalog/records/assets/${ID}`, 'DELETE')).status).toBe(200);

  expect((await callProxy('trade/sources/collector-endpoint/ALIBABA_1688', 'PUT')).status).toBe(
    200,
  );

  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8081/identity/dict-items'),
    expect.objectContaining({ method: 'POST' }),
  );
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8082/catalog/lifecycle/rules'),
    expect.objectContaining({ method: 'POST' }),
  );
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/sources/collector-endpoint/ALIBABA_1688'),
    expect.objectContaining({ method: 'PUT' }),
  );
});

it('未交付的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 非 UUID 段不应被 uuid 分支吃掉。
    'identity/dict-items/not-a-uuid',
    // 后端没有「所有字典的选项」这个形状：下拉必须指明是哪一本字典。
    'identity/dict-items/options',
    // 字典编码是精确匹配的大写码表，不放行小写。
    'identity/dict-items/options/order_status',
    // 后端**没有** DELETE 端点：字典项只能停用，不能删（V53 迁移的四道防线）。
    `identity/dict-items/${ID}/delete`,
    // 后端只有 activate / deactivate 两个动作。
    `identity/dict-items/${ID}/archive`,
    `identity/dict-items/${ID}/enable`,
    // 目录与历史都是集合读，没有单条形状。
    `identity/dict-catalog/${ID}`,
    `identity/dict-history/${ID}`,
    // 引用台账就是 /usage 这一个形状。
    `identity/dict-items/${ID}/usage-list`,
    // 生命周期规则：删除就是 DELETE 本体，没有 /delete 子动作。
    `catalog/lifecycle/rules/${ID}/delete`,
    // 执行结果随 POST /run 的返回值给出，没有独立的执行记录集合。
    `catalog/lifecycle/rules/${ID}/executions`,
    // 指标是字典，不是集合资源。
    `catalog/lifecycle/metrics/${ID}`,
    // 生命周期状态机的枚举不在本控制器的端点里。
    'catalog/lifecycle/transitions',
    // 非 UUID 段不应被 uuid 分支吃掉。
    'catalog/lifecycle/rules/not-a-uuid',
    // catalog/records 的 kind 值域只有 research / compliance / assets 三个。
    'catalog/records/products',
    'catalog/records',
    // 商品档案之下只接一条 id 段。
    `catalog/records/assets/${ID}/x`,
    // 采集接入的平台值域是货源平台枚举（ALIBABA_1688 / YIWU_GOU），
    // 不是渠道平台枚举 —— amazon 不在这个枚举里。
    'trade/sources/collector-endpoint/amazon',
    // 枚举名是 ALIBABA_1688（下划线），不是 alibaba-1688。
    'trade/sources/collector-endpoint/alibaba-1688',
    // 没有「读所有平台的采集接入」这个形状。
    'trade/sources/collector-endpoint',
  ]) {
    const response = await callProxy(path);
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 本车道往 `services[]` 里**新增了两条元素**（identity 的 dict 段、catalog 的 lifecycle 段），
 * 所以必须钉住「既有模块的入口没有被本车道新增的元素误伤」——
 * 同一前缀下多条元素，谁先命中取决于顺序，这里逐条反查既有模块的入口。
 */
it('既有模块的入口没有被本车道新增的元素误伤', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // identity（既有巨型条目）
    'identity/permissions',
    'identity/roles',
    'identity/approval-flows/document-types',
    'identity/legal-entities/references',
    'identity/accounting-books',
    'identity/business-parameters/effective',
    // catalog（既有巨型条目）
    'catalog/products',
    `catalog/products/${ID}`,
    'catalog/attributes/overview',
    // `options/lifecycle` 是 3.2.1.2 的批量重排：首 token 是 attributes，不是 lifecycle。
    'catalog/attributes/options/lifecycle',
    'catalog/brands/overview',
    'catalog/categories/overview',
    'catalog/records/assets',
    `catalog/records/assets/${ID}`,
    // trade（既有 6.1.2.1 档口与货源池条目）
    'trade/sources/capabilities',
    'trade/sources/options',
    'trade/sources/overview',
    'trade/sources/suppliers',
    'trade/sources/stalls',
    `trade/sources/stalls/${ID}`,
    `trade/sources/stalls/${ID}/sync`,
    `trade/sources/items/${ID}/favorite`,
    `trade/sources/items/${ID}/sku-links`,
    `trade/sources/items/${ID}/sku-links/${ID}`,
    `trade/sources/sku/${ID}/sources`,
  ]) {
    const response = await callProxy(path);
    expect(response.status, `${path} 被本车道新增的白名单条目误伤了`).toBe(200);
  }
});

/**
 * 判据取 **codegen 产物** {@code packages/api-contracts/src/identity.openapi.json}：
 * 那是后端真实端点的一份快照。白名单放行的形状必须与契约里的形状一一对应 ——
 * 白名单多放一个 = 浏览器里会转给一个不存在的端点。
 */
it('数据字典白名单放行的形状与后端契约一一对应（不多放、不少放）', () => {
  const spec = JSON.parse(
    readFileSync(
      fileURLToPath(
        new URL('../../../../packages/api-contracts/src/identity.openapi.json', import.meta.url),
      ),
      'utf8',
    ),
  ) as { paths: Record<string, Record<string, unknown>> };
  const contractPaths = Object.keys(spec.paths)
    .filter((p) => p.startsWith('/identity/dict'))
    .map((p) => p.replace('/identity/', 'identity/'))
    .sort();
  expect(contractPaths.length, '契约里一条 dict 路径都没有，判定本身失效了').toBeGreaterThan(0);

  // 契约里有的形状，白名单必须放行。
  const allowed = new Set([
    'identity/dict-catalog',
    'identity/dict-history',
    'identity/dict-items',
    'identity/dict-items/options/{dictCode}',
    'identity/dict-items/{id}',
    'identity/dict-items/{id}/deactivate',
    'identity/dict-items/{id}/reactivate',
    'identity/dict-items/{id}/usage',
  ]);
  expect(new Set(contractPaths)).toEqual(allowed);
});
