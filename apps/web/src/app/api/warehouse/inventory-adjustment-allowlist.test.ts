import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { DELETE, GET, POST } from './[...path]/route';

/**
 * 7.4.1.1 调整单管理的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest / Playwright 全绿。
 * 所以判据是**真跑 proxy handler 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从本模块自己的模型源码里<b>反向抽取真实调用点</b>再逐条断言被放行 ——
 *       写死一份清单只会验证「清单与白名单一致」，而真正会出事的是
 *       「源码加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：未交付 / 不该存在的形状仍然 404。<b>反向用例要能证明「多一层后缀」仍然 404</b>
 *       ——{id}/post 放行不代表 {id}/post/force 也该放行，那是不存在的动作。</li>
 * </ul>
 *
 * <p><b>累积型文件的求并纪律</b>：白名单是多车道共用的，本车道只**追加**分支，绝不收窄既有分支。
 * 最后一组「不误伤其他车道」就是为此存在 —— 取错一侧会静默删掉已交付模块的路由，
 * 而表现是页面 404 且所有静态检查全绿。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(
    new URL('../../../components/inventory/inventory-adjustment-model.ts', import.meta.url),
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
 * 从模型源码里反向抽出**真实存在**的路径。
 *
 * <p>模型里所有端点都是 `` `${base}/xxx` `` 拼出来的（base 是常量
 * 'warehouse/inventory/adjustments'），所以抽取必须先把模板串**按 base 展开**再匹配 ——
 * 只认字面量会把调用点全部漏成零条，然后「一个都没断言」也算通过，
 * 那正是这类守卫最典型的失效方式。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'warehouse/inventory/adjustments';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw) continue;
    if (!raw.includes('${base}') && !raw.startsWith('warehouse/')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      // ${query(params)} 是查询串，整段去掉；其余插值（通常是 id）换成 uuid。
      .replace(/\$\{query\([^)]*\)\}/g, '')
      .replace(/\$\{[^}]*\}/g, ID)
      .replace(/\?.*$/, '');
    if (!cleaned.startsWith('warehouse/')) continue;
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
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 未被放行（白名单漏登记 = 浏览器里静默 404）`).toBe(200);
  }
  // 抽到的路径必须**真的**覆盖本模块的形状，否则「只放行了一条」也算通过。
  expect(sites).toEqual(
    expect.arrayContaining([
      'warehouse/inventory/adjustments',
      'warehouse/inventory/adjustments/capabilities',
      `warehouse/inventory/adjustments/${ID}`,
      `warehouse/inventory/adjustments/${ID}/lines`,
      `warehouse/inventory/adjustments/${ID}/lines/${ID}/remove`,
      `warehouse/inventory/adjustments/${ID}/attachments`,
      `warehouse/inventory/adjustments/${ID}/attachments/${ID}/remove`,
      `warehouse/inventory/adjustments/${ID}/submit`,
      `warehouse/inventory/adjustments/${ID}/approve`,
      `warehouse/inventory/adjustments/${ID}/reject`,
      `warehouse/inventory/adjustments/${ID}/cancel`,
      `warehouse/inventory/adjustments/${ID}/post`,
      'warehouse/inventory/adjustment-sources/defective-dispositions',
      `warehouse/inventory/adjustment-sources/defective-dispositions/${ID}/instructions`,
    ]),
  );
});

it('写动作的 POST 形状被放行，且真的转发到 8084', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'warehouse/inventory/adjustments',
    `warehouse/inventory/adjustments/${ID}/lines`,
    `warehouse/inventory/adjustments/${ID}/lines/${ID}/remove`,
    `warehouse/inventory/adjustments/${ID}/attachments`,
    `warehouse/inventory/adjustments/${ID}/attachments/${ID}/remove`,
    `warehouse/inventory/adjustments/${ID}/submit`,
    `warehouse/inventory/adjustments/${ID}/approve`,
    `warehouse/inventory/adjustments/${ID}/reject`,
    `warehouse/inventory/adjustments/${ID}/cancel`,
    `warehouse/inventory/adjustments/${ID}/post`,
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8084/warehouse/inventory/adjustments'),
    expect.objectContaining({ method: 'POST' }),
  );
});

/**
 * **字面量子路径必须排在 uuid 通配分支之前**。
 *
 * <p>alternation 是**首个命中即返回**：如果 `adjustments\/capabilities`
 * 排在 `adjustments\/[0-9a-f-]+` 之后，`capabilities` 不是 uuid 本该落空，
 * 但一旦有人图省事把动作段写成 `[a-z-]+`，`/capabilities` 就会被当成
 * 「id=capabilities」凭空放行一个**后端根本不存在的单据读取** ——
 * 而这种错误在浏览器里表现为一个莫名其妙的 500，不是干净的 404，最难查。
 */
it('字面量 capabilities 不会被 uuid 通配分支吃掉（alternation 首个命中即返回）', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    'warehouse/inventory/adjustments/not-a-uuid',
    'warehouse/inventory/adjustments/capabilities/advanced',
    'warehouse/inventory/adjustments/ADJ-INV-20261021',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行：只有 uuid 与已知动作可以过`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 7.4.1.1 的调整单是**独立顶层分支**，不挂在 7.4.2.2 的 stocktake-variances 底下。
 *
 * <p>这条要单独钉住：盘点差异的调整单要求先有盘点表，通用调整单不要求 ——
 * 两套单据挂在同一个前缀下会让「这是盘出来的还是盘不出来的」在契约上分不清，
 * 而审批人恰恰要靠这个判断风险（盘出来的有账面数兜底，盘不出来的没有）。
 *
 * <p>判据是**「通用调整单自己的能力面在顶层」**：
 * `inventory/adjustments/capabilities` 放行，而它**没有**在 7.4.2.2 的前缀下再出现一份
 * （`inventory/stocktake-variances/adjustments/capabilities` 必须 404）。
 *
 * <p>注意这里**不能**断言 `inventory/stocktake-variances/adjustments` 是 404 ——
 * 那是 7.4.2.2 **自己**的既有端点（本车道之前就放行），断言 404 会把「独立分支」
 * 误写成「掐掉兄弟模块的路由」。两套单据是**两处独立的契约**，
 * 所以那一条必须 200（见下一条「不误伤」的断言）。
 */
it('通用调整单是独立顶层分支，不挂在 7.4.2.2 的 stocktake-variances 底下', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // 通用调整单自己的能力面在顶层。
  const top = await GET(request('warehouse/inventory/adjustments/capabilities'), {
    params: Promise.resolve({ path: 'warehouse/inventory/adjustments/capabilities'.split('/') }),
  });
  expect(top.status, '通用调整单的能力面必须在顶层可读').toBe(200);

  // 同一份能力面**没有**在 7.4.2.2 的前缀下再挂一份 —— 两套单据不能混在一个契约里。
  for (const path of [
    'warehouse/inventory/stocktake-variances/adjustments/capabilities',
    'warehouse/inventory/stocktake-variances/capabilities/adjustments',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(
      response.status,
      `${path} 不应被放行：通用调整单的能力面只在顶层，7.4.2.2 前缀下不得再挂一份`,
    ).toBe(404);
  }
});

it('未交付 / 不该存在的形状仍然被拒，且「多一层后缀」也仍然 404', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 已放行的动作后面再多一层 —— 必须仍然 404（放行一个不存在的动作比 404 更糟）。
    `warehouse/inventory/adjustments/${ID}/post/force`,
    `warehouse/inventory/adjustments/${ID}/submit/again`,
    `warehouse/inventory/adjustments/${ID}/cancel/undo`,
    // 裸路径 + 多一层：后端没有「一条不存在的列表子路径」。
    'warehouse/inventory/adjustments/summary',
    `warehouse/inventory/adjustments/${ID}/entries`,
    // 拼错的顶层分支不放行。
    'warehouse/inventory/adjustment',
    'warehouse/inventory/adjustment-orders',
    'warehouse/inventory/adjustments-sources/defective-dispositions',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 刻意没有 DELETE 端点：调整单不可删（作废走状态机留痕）。
 *
 * <p>这里断言的是代理的**真实语义**而不是它本该有的语义：白名单按**路径**放行、
 * **不按方法**过滤，DELETE 会被照常转发，最终由后端回 405 / 终态守卫拒。
 */
it('DELETE 不由代理拦：路径在白名单内就会被转发，方法由后端兜住', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [`warehouse/inventory/adjustments/${ID}`, 'warehouse/inventory/adjustments']) {
    const response = await DELETE(request(path, 'DELETE'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(
      response.status,
      `DELETE ${path} 应转发给后端（单据不可删，交给后端 405 / 终态守卫）`,
    ).toBe(200);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8084/warehouse/inventory/adjustments'),
    expect.objectContaining({ method: 'DELETE' }),
  );
});

/**
 * 不误伤其他车道已登记的分支。
 *
 * <p>白名单是多车道共用的**累积型**文件。并入时若取错一侧，会静默删掉已交付模块的路由 ——
 * 表现是页面 404，而 typecheck / lint / vitest / Playwright 全绿，因为白名单只在浏览器端生效。
 * 特别要覆盖**紧邻本车道插入点**的几条：求并脚本最容易在这里错一刀。
 */
it('不误伤本车道之前已放行的仓库分支（求并，不是二选一）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 同 L3 的兄弟模块 7.4.2.2 盘点差异处理
    'warehouse/inventory/stocktake-variances/capabilities',
    'warehouse/inventory/stocktake-variances/adjustments',
    `warehouse/inventory/stocktake-variances/adjustments/${ID}/post`,
    // 直接上游 7.1.1.2 库存明细与下游 7.1.2.1 流水台账
    'warehouse/inventory/buckets',
    'warehouse/inventory/ledger',
    'warehouse/inventory/ledger/ref-types',
    // 同一 L2 下的其它已交付模块（**必须是后端真实存在的端点**，
    // 否则这条断言测的是「代理有没有放行一个不存在的路径」，
    // 而那永远是 404 —— 断言会红，却和「本车道有没有误伤」毫无关系）
    'warehouse/inventory/capabilities',
    'warehouse/inventory/stocktakes/capabilities',
    'warehouse/inventory/aging/buckets',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `已交付模块的 ${path} 被本车道误伤 —— 白名单必须求并`).toBe(200);
  }
});

/** 跨源写请求必须被拒（CSRF 闸），与白名单无关但同一份代理代码。 */
it('跨源写请求被 403 拒绝，不转发', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const path = `warehouse/inventory/adjustments/${ID}/post`;
  const response = await POST(
    new NextRequest(`http://localhost:3000/api/warehouse/${path}`, {
      method: 'POST',
      headers: { origin: 'http://evil.example', cookie: 'starcon_session=x' },
      body: '{}',
    }),
    { params: Promise.resolve({ path: path.split('/') }) },
  );
  expect(response.status).toBe(403);
  expect(fetcher).not.toHaveBeenCalled();
});
