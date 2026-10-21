import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { DELETE, GET, POST } from './[...path]/route';

/**
 * 7.4.1.2 调整审批的 api-proxy 白名单**双向**断言。
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
 *       ——{id}/approve 放行不代表 {id}/approve/force 也该放行，那是不存在的动作。</li>
 * </ul>
 *
 * <p><b>累积型文件的求并纪律</b>：白名单是多车道共用的，本车道只**追加**分支，绝不收窄既有分支。
 * 最后一组「不误伤其他车道」就是为此存在 —— 取错一侧会静默删掉已交付模块的路由，
 * 而表现是页面 404 且所有静态检查全绿。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(
    new URL('../../../components/inventory/inventory-adjustment-approval-model.ts', import.meta.url),
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
 * 'warehouse/inventory/adjustment-approvals'），所以抽取必须先把模板串**按 base 展开**再匹配 ——
 * 只认字面量会把调用点全部漏成零条，然后「一个都没断言」也算通过，
 * 那正是这类守卫最典型的失效方式。
 */
function realCallSites(): string[] {
  const base =
    SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'warehouse/inventory/adjustment-approvals';
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
      'warehouse/inventory/adjustment-approvals/capabilities',
      'warehouse/inventory/adjustment-approvals/rules',
      `warehouse/inventory/adjustment-approvals/rules/${ID}/update`,
      `warehouse/inventory/adjustment-approvals/rules/${ID}/status`,
      'warehouse/inventory/adjustment-approvals/approvers',
      `warehouse/inventory/adjustment-approvals/approvers/${ID}/status`,
      'warehouse/inventory/adjustment-approvals/pending',
      'warehouse/inventory/adjustment-approvals/evaluate',
      `warehouse/inventory/adjustment-approvals/${ID}/submit`,
      `warehouse/inventory/adjustment-approvals/${ID}/approve`,
      `warehouse/inventory/adjustment-approvals/${ID}/reject`,
      'warehouse/inventory/adjustment-approvals/batch/approve',
      'warehouse/inventory/adjustment-approvals/batch/reject',
      'warehouse/inventory/adjustment-approvals/valuations',
    ]),
  );
});

it('写动作的 POST 形状被放行，且真的转发到 8084', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'warehouse/inventory/adjustment-approvals/rules',
    `warehouse/inventory/adjustment-approvals/rules/${ID}/update`,
    `warehouse/inventory/adjustment-approvals/rules/${ID}/status`,
    'warehouse/inventory/adjustment-approvals/approvers',
    `warehouse/inventory/adjustment-approvals/approvers/${ID}/status`,
    'warehouse/inventory/adjustment-approvals/evaluate',
    `warehouse/inventory/adjustment-approvals/${ID}/submit`,
    `warehouse/inventory/adjustment-approvals/${ID}/approve`,
    `warehouse/inventory/adjustment-approvals/${ID}/reject`,
    'warehouse/inventory/adjustment-approvals/batch/approve',
    'warehouse/inventory/adjustment-approvals/batch/reject',
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8084/warehouse/inventory/adjustment-approvals'),
    expect.objectContaining({ method: 'POST' }),
  );
});

/**
 * **字面量子路径必须排在 uuid 通配分支之前**。
 *
 * <p>alternation 是**首个命中即返回**：如果 `adjustment-approvals\/capabilities`
 * 排在 `adjustment-approvals\/[uuid]` 之后，`capabilities` 不是 uuid 本该落空；
 * 但一旦有人图省事把 id 段写成 `[a-z-]+`，`/capabilities` 就会被当成
 * 「id=capabilities」凭空放行一个**后端根本不存在的读取** ——
 * 而这种错误在浏览器里表现为一个莫名其妙的 500，不是干净的 404，最难查。
 */
it('字面量子路径不会被 uuid 通配分支吃掉（alternation 首个命中即返回）', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    'warehouse/inventory/adjustment-approvals/not-a-uuid',
    'warehouse/inventory/adjustment-approvals/capabilities/advanced',
    'warehouse/inventory/adjustment-approvals/ADJ-APP-20261021',
    'warehouse/inventory/adjustment-approvals/bad',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行：只有 uuid 与已知动作可以过`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 「多一层后缀」仍然 404。
 *
 * <p>放行 `{id}/approve` 不代表 `{id}/approve/force` 也该放行 ——
 * 那是不存在的动作。审批链路是追加式的，「强制通过」这种形状压根不该出现在契约里。
 */
it('不存在的动作形状仍然 404（多一层后缀也不行）', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    `warehouse/inventory/adjustment-approvals/${ID}/approve/force`,
    `warehouse/inventory/adjustment-approvals/${ID}/submit/again`,
    `warehouse/inventory/adjustment-approvals/${ID}/reject/reason`,
    'warehouse/inventory/adjustment-approvals/batch/approve/all',
    'warehouse/inventory/adjustment-approvals/rules/advanced',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

/**
 * 审批与单据是**两段契约**：`adjustment-approvals` 不许挂在 `adjustments` 之下。
 *
 * <p>调错前缀时浏览器会 404，看起来像「功能没做」，
 * 而实际上后端有端点 —— 这种错误最难查，所以单独钉一条。
 */
it('审批是独立的一段，不挂在 7.4.1.1 的 adjustments 之下', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const top = await GET(request('warehouse/inventory/adjustment-approvals/capabilities'), {
    params: Promise.resolve({
      path: 'warehouse/inventory/adjustment-approvals/capabilities'.split('/'),
    }),
  });
  expect(top.status, '审批的能力面必须在自己的前缀下可读').toBe(200);

  for (const path of [
    'warehouse/inventory/adjustments/adjustment-approvals/capabilities',
    'warehouse/inventory/adjustments/capabilities/adjustment-approvals',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行：单据与审批是两件事`).toBe(404);
  }
});

/**
 * 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟）：
 *   adjustment-approvals/{id}/delete   —— 审批链路与审批单都不是可物理删除的
 *   adjustment-approvals/{id}/records   —— 链路记录在详情里，没有单独的读端点
 *   adjustment-approvals/summary       —— 汇总在能力声明与列表响应里
 *   adjustment-approvals/rules/{id}/delete —— 规则停用走 status，不物理删
 */
it('刻意不放行的形状：删除、单独的链路记录读端点、汇总', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    `warehouse/inventory/adjustment-approvals/${ID}/delete`,
    `warehouse/inventory/adjustment-approvals/${ID}/records`,
    'warehouse/inventory/adjustment-approvals/summary',
    `warehouse/inventory/adjustment-approvals/rules/${ID}/delete`,
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
  // 白名单是**按路径登记、不区分方法**的：DELETE 到一个已放行的路径会被**转发**，
  // 由**后端**裁决（本仓没有 DELETE 端点 ⇒ 405；审批链路记录另有 DB 的 REVOKE 与触发器）。
  // 所以这里断言的是「转发给了上游」而不是「代理把它拦成 404」——
  // 写成 404 会把「白名单按路径登记」这条纪律误写成「代理按方法过滤」。
  const fetcher2 = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher2);
  const deleted = await DELETE(request(`warehouse/inventory/adjustment-approvals/${ID}`, 'DELETE'), {
    params: Promise.resolve({ path: `warehouse/inventory/adjustment-approvals/${ID}`.split('/') }),
  });
  expect(deleted.status, 'DELETE 不由代理裁决，应转发给后端（后端返 405）').toBe(200);
  expect(fetcher2).toHaveBeenCalledWith(
    expect.stringContaining(':8084/warehouse/inventory/adjustment-approvals'),
    expect.objectContaining({ method: 'DELETE' }),
  );
});

/**
 * **不误伤其他车道**：本车道只**追加**分支，既有已交付模块的路由一条都不许被挤掉。
 *
 * <p>取错一侧（把别人的分支收窄）会静默删掉已交付模块的路由，
 * 表现是页面 404，而 typecheck / lint / vitest / Playwright 全部全绿。
 */
it('不误伤其他车道已交付的 warehouse 路由', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 7.4.1.1 调整单管理（本模块的上游，**不能**被本车道的分支挤掉）
    'warehouse/inventory/adjustments/capabilities',
    `warehouse/inventory/adjustments/${ID}/submit`,
    `warehouse/inventory/adjustments/${ID}/post`,
    'warehouse/inventory/adjustment-sources/defective-dispositions',
    // 7.1.2.1 流水台账
    'warehouse/inventory/ledger',
    // 7.4.2.2 盘点差异处理
    'warehouse/inventory/stocktake-variances/capabilities',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被本车道误伤了`).toBe(200);
  }
});
