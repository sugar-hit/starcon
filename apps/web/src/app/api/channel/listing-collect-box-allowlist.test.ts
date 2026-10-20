import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 4.4.1.3 采集箱 的 api-proxy 白名单**双向**断言。
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
 * <p>🔴 <b>负向样本一律取【自己前缀】下的形状</b>（`channel/listing-collect-box/...`）。
 * 上一批（batch-32）踩过的坑：4.5.2.1 拿兄弟车道 4.5.1.2 关键词排名的前缀
 * `channel/listing-keyword-rankings` 当「未交付形状」要求 404 —— 而 4.5.1.2
 * <b>在同一批里已经交付</b>，那个前缀是**已登记的合法前缀** ⇒ 并入后 L2 必然假红
 * （`expected 503 to be 404`：503 是被转发到未启动的 channel 后端，404 才是白名单拒绝）。
 * 更危险的是反向：若兄弟车道那条白名单被取错一侧而**静默删掉**，这条负向断言反而会**绿** ——
 * 「已交付的路由被误删」这个真事故被测试**盖住**。⇒ 断言方向必须自己盯住。
 *
 * <p><b>不误伤其他车道已登记的分支</b>：白名单是多车道共用的**累积型**文件。
 * 本车道只**新增一条** `listing-collect-box/` 元素，4.4.1.1 的 `listing-collects/`、
 * 4.4.1.2 的 `listing-bulk-collects/` 等一字未动 —— 但那必须被断言钉住，
 * 否则下一次并入取错一侧时会静默删掉已交付模块的路由。
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
const MODULE = '/channel/listing-collect-box';

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
    const resolved = path.replace(/^\//, '').replace(/\{[^}]+\}/g, ID);
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
      // 🔴 能力声明：漏登记它，「认领只产出草稿」「品牌与分类映射是硬要求」等边界明账就没了
      'channel/listing-collect-box/capabilities',
      // 采集箱条目 + 收录
      'channel/listing-collect-box/items',
      // 三个批量动作
      `channel/listing-collect-box/items/${ID}`,
      'channel/listing-collect-box/items/claim',
      'channel/listing-collect-box/items/tags',
      'channel/listing-collect-box/items/remove',
      // 分组与候选
      'channel/listing-collect-box/groups',
      'channel/listing-collect-box/candidates',
      // 标签与认领规则
      'channel/listing-collect-box/tags',
      `channel/listing-collect-box/tags/${ID}`,
      'channel/listing-collect-box/rules',
      `channel/listing-collect-box/rules/${ID}`,
    ]),
  );
});

it('写动作真的转发到 8083（不是只判了正则没转发）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const response = await callProxy('channel/listing-collect-box/items/claim', {
    method: 'POST',
    headers: {
      origin: 'http://localhost:3000',
      cookie: 'starcon_session=private-session',
      'X-Tenant-Id': 'forged',
      'X-Internal-Key': 'forged',
    },
    body: '{}',
  });
  expect(response.status, 'POST channel/listing-collect-box/items/claim 未被放行').not.toBe(404);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8083/channel/listing-collect-box/items/claim'),
    expect.objectContaining({ method: 'POST' }),
  );
  // 伪造的租户头不得被转发到上游 —— 租户只来自已认证成员关系（AGENTS.md）
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  expect(new Headers(init.headers).get('X-Tenant-Id')).toBeNull();
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const base = 'channel/listing-collect-box';
  for (const path of [
    // 🔴 本任务书指定的拒绝形状：只能选**自己前缀**下的形状
    `${base}/undeclared-action`,
    // 认领 / 打标 / 删除都是**批量**动作，勾选后一次提交 —— 不按单条寻址
    `${base}/items/${ID}/claim`,
    `${base}/items/${ID}/tags`,
    `${base}/items/${ID}/remove`,
    // 草稿内容随条目详情一起返回，不单开一条
    `${base}/items/${ID}/draft`,
    // 认领走集合入口（/items/claim），不单开一条
    `${base}/claim`,
    // 本模块自己的前缀，不该再套一层
    `${base}/box`,
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
    // 4.4.1.1 链接采集（本车道的插入锚点紧挨着它 —— 最容易被误伤）
    'channel/listing-collects/capabilities',
    'channel/listing-collects',
    `channel/listing-collects/${ID}`,
    // 4.4.1.2 批量/插件采集（插入锚点就是它那一条之后）
    'channel/listing-bulk-collects/capabilities',
    'channel/listing-bulk-collects',
    `channel/listing-bulk-collects/${ID}`,
    'channel/listing-bulk-collects/plugin-reports',
    // 4.4.2.1 商品认领
    'channel/listing-claims/capabilities',
    'channel/listing-claims',
    `channel/listing-claims/${ID}`,
    // 4.5.1.2 关键词排名（**上一批已经交付** —— 拿它当「未交付形状」会假红）
    'channel/listing-keyword-rankings/capabilities',
    'channel/listing-keyword-rankings',
    `channel/listing-keyword-rankings/${ID}`,
    // 4.5.2.1 跟卖监控（同上）
    'channel/listing-hijackers/capabilities',
    'channel/listing-hijackers/watches',
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
