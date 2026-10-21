import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 4.2.3.2 合规预检 的 api-proxy 白名单**双向**断言。
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
 * <p>🔴 本车道在白名单里用的是 {@code listing-compliance-precheck} 前缀，
 * **不是** {@code compliance-precheck} —— 那是同批并行那条车道（16.2.2.1 合规体检报告，
 * 合规域 D7）的前缀。两边名字几乎一样但**不共表、不共端点、不共页面**。
 *
 * <p>⚠️ **两条车道同批并入集成头之后，两个前缀都真实存在** ⇒
 * {@code compliance-precheck/...} 不再是「不存在的东西」，它由 16.2.2.1
 * **自己那条**白名单元素放行（转发 ⇒ 上游响应 / 上游不可达时 503）。
 * 所以这里的负向断言只能用 16.2.2.1 **没有登记**的形状
 * （{@code precheck} / {@code score}），
 * 而「16.2.2.1 已登记的形状被放行」另立一条**独立**断言来钉（见下方两个 🔴 用例）。
 *
 * <p><b>不误伤其他车道已登记的分支</b>：白名单是多车道共用的**累积型**文件。
 * 本车道只**新增一条** {@code listing-compliance-precheck/} 元素，其余分支一字未动 ——
 * 但那必须被断言钉住，否则下一次并入取错一侧时会静默删掉已交付模块的路由。
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
const MODULE = '/channel/listing-compliance-precheck';

afterEach(() => vi.unstubAllGlobals());

/**
 * 从**本模块自己生成的契约**里抽出真实存在的 path。
 *
 * <p>⚠️ 刻意**不削掉** `/channel/` 前缀：`proxy()` 拿 `params.path.join('/')` 去比对
 * `services[].prefix`，prefix 就是 `channel/`。
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
      // 🔴 能力声明：漏登记它，「16.2.2.2 认证未交付 / 16.2.2.1 同批并行」的明账就没了
      'channel/listing-compliance-precheck/capabilities',
      // 筛选项词表
      'channel/listing-compliance-precheck/filter-options',
      // 可扫描的编辑器草稿
      'channel/listing-compliance-precheck/drafts',
      // 🔴 服务端发布闸门
      `channel/listing-compliance-precheck/drafts/${ID}/publish-gate`,
      // 触发扫描 / 扫描历史
      'channel/listing-compliance-precheck/scans',
      // 单次扫描详情
      `channel/listing-compliance-precheck/scans/${ID}`,
      // 风险批量处置
      'channel/listing-compliance-precheck/risks/decisions',
      // 词库
      'channel/listing-compliance-precheck/words',
      `channel/listing-compliance-precheck/words/${ID}`,
      // 启停词
      `channel/listing-compliance-precheck/words/${ID}/enabled`,
    ]),
  );
});

it('触发扫描真的转发到 8083（不是只判了正则没转发）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const response = await callProxy('channel/listing-compliance-precheck/scans', {
    method: 'POST',
    headers: {
      origin: 'http://localhost:3000',
      cookie: 'starcon_session=private-session',
      'X-Tenant-Id': 'forged',
      'X-Internal-Key': 'forged',
    },
    body: '{}',
  });
  expect(response.status, 'POST channel/listing-compliance-precheck/scans 未被放行').not.toBe(404);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8083/channel/listing-compliance-precheck/scans'),
    expect.objectContaining({ method: 'POST' }),
  );
  // 伪造的租户头不得被转发到上游 —— 租户只来自已认证成员关系（AGENTS.md）
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  expect(new Headers(init.headers).get('X-Tenant-Id')).toBeNull();
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const base = 'channel/listing-compliance-precheck';
  for (const path of [
    // 🔴 发布归 4.3.1.1 一键刊登 —— 本模块只出闸门结论，没有「直接发布」入口
    `${base}/drafts/${ID}/publish`,
    `${base}/drafts/${ID}/dispatch`,
    `${base}/scans/${ID}/publish`,
    // 4.3.1.1 是同批并行车道，集成头此刻尚未落地；它的派发形状一律不放行
    `${base}/dispatch`,
    `${base}/publish-queue`,
    // 逐条风险随扫描详情返回，不单开一条
    `${base}/scans/${ID}/risks`,
    // 扫描是针对草稿的，不是针对词的
    `${base}/words/${ID}/scan`,
    // 词库的删除是裸路径上的 DELETE，不带动作后缀
    `${base}/words/${ID}/delete`,
    // 单字符不是合法 id
    `${base}/words/c`,
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

it('🔴 同批另一条车道（16.2.2.1）：已登记的形状由它自己的白名单元素放行，没登记的形状仍然被拒（404）', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 16.2.2.1 合规预检（体检报告，合规域 D7）的白名单只登记了
    // `capabilities` / `drafts` / `drafts/{uuid}/(check|precheck)` / `reports` / `reports/{uuid}`。
    // 下面两条是它**没有登记**的形状 —— 合并之后依然必须是 404。
    'channel/compliance-precheck/precheck',
    'channel/compliance-precheck/score',
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 被放行了 —— 16.2.2.1 并未登记这个形状`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

it('🔴 合并没有把同批另一条车道的白名单条目弄丢 —— 它的形状现在被放行并转发到 channel 上游', async () => {
  // ⚠️ 必须**独立成块**：上一块的判据是「被拒 ⇒ fetch 一次都不许被调用」。
  // `compliance-precheck/capabilities` 是 16.2.2.1 的**真实端点**，合并后由它自己那条
  // 白名单元素放行 ⇒ 代理**会**转发 ⇒ 单元测试里上游 channel 服务没在跑 ⇒ 503
  //（「服务暂时不可连接」）。503 恰恰证明「白名单命中并转发」这个行为是对的，
  // 所以它**不能**再留在负向清单里，只能在这里被正向断言。
  //
  // 🔴 为什么会有这个坑：两条车道**同批并行**并入集成头后，两个前缀都真实存在了。
  // 各自车道的负向断言在**自己 worktree 里单跑是绿的**（那时另一条车道的前缀还不存在，
  // 代理返回 404），**合并后才红** —— 这正是 L2 批量闸存在的意义：
  // 它抓到的是「每条车道单跑都看不见、只在集成头才成立」的那类缺陷。
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const response = await callProxy('channel/compliance-precheck/capabilities', {
    headers: { cookie: 'starcon_session=private-session' },
  });
  expect(
    response.status,
    'channel/compliance-precheck/capabilities 被 404 了 —— 合并没有把 16.2.2.1 的白名单条目弄丢',
  ).not.toBe(404);
  // 钉住「放行 = 真的转发到 channel 上游」，而不只是「没被拦」。
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8083/channel/compliance-precheck/capabilities'),
    expect.anything(),
  );
});

it('🔴 不误伤其他车道已登记的分支 —— 白名单是多车道共用的累积型文件', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // 本车道只**新增**一条元素，既有分支一字未动。下面每一条都必须仍然放行 ——
  // 取错一侧时会静默删掉别人已交付的路由，症状是那些页面 404 而本仓全绿。
  for (const path of [
    // 4.2.1.1 刊登编辑器（本车道的插入锚点邻居）
    'channel/listing-editor/capabilities',
    `channel/listing-editor/drafts/${ID}`,
    // 4.4.2.1 商品认领
    'channel/listing-claims/capabilities',
    `channel/listing-claims/${ID}`,
    // 4.2.2.1 刊登模板
    'channel/listing-templates/capabilities',
    // 4.3.2.1 内容回写
    'channel/listing-content-writeback/capabilities',
    // 4.3.1.2 批量发布
    'channel/listing-publish/capabilities',
    // 4.2.1.2 批量编辑
    'channel/listing-bulk-edit/capabilities',
    // 4.1.1.1 Listing 列表
    'channel/listings/capabilities',
    `channel/listings/${ID}`,
    // 4.1.1.2 Listing 详情
    'channel/listing-details/capabilities',
    // 4.1.2.x 映射
    `channel/listing-mappings/${ID}`,
    // 2.3 店铺
    'channel/shops',
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 被本车道的改动误伤成 404`).not.toBe(404);
  }
});
