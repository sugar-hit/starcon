import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST, PUT, DELETE } from './[...path]/route';

/**
 * 8.3.2.2 波次的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest / Playwright 全绿。
 * 所以判据是**真跑 proxy handler 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比（这两种方法在本仓库已连续骗过多版）。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从本模块自己的模型源码里<b>反向抽取真实调用点</b>再逐条断言被放行 ——
 *       写死一份清单只会验证「清单与白名单一致」，而真正会出事的是
 *       「源码加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：未交付 / 不该存在的形状仍然 404。<b>反向用例要能证明「多一层后缀」
 *       仍然 404</b> —— {id}/release 放行不代表 {id}/release/force 也该放行。</li>
 * </ul>
 *
 * <p><b>累积型文件的求并纪律</b>：白名单是多车道共用的，本车道只**追加**分支，绝不收窄既有分支。
 * 最后一组「不误伤其他车道」就是为此存在 —— 取错一侧会静默删掉已交付模块的路由，
 * 而表现是页面 404 且所有静态检查全绿。
 */

const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/warehouse/pick-wave-model.ts', import.meta.url)),
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

function ok() {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  return fetcher;
}

/**
 * 从模型源码里反向抽出**真实存在**的路径。
 *
 * <p>模型里所有端点都是 `` `${base}/xxx` `` 拼出来的（base 是常量 'warehouse/pick-waves'），
 * 所以抽取必须先把模板串**按 base 展开**再匹配 —— 只认字面量会把调用点全部漏成零条，
 * 然后「一个都没断言」也算通过，那正是这类守卫最典型的失效方式。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'warehouse/pick-waves';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      // **只有 id 引用才替换成 ID，其余插值（查询串）整段剥掉**。
      // 一律替换的话，`${query}` 会变成 ID 常量，拼出「…/rules1111…」这种
      // 不存在的形状，然后断言它被放行 —— 而它本来就该 404。
      // 这类守卫最典型的自伤：抽取器自己造出一个假路径，再被假路径的失败
      // 掩盖成「白名单漏登记」。
      .replace(/\$\{[^}]*\}/g, (m) => (m.includes('encodeURIComponent') ? ID : ''))
      .replace(/\?.*$/, '');
    if (cleaned.includes('${')) continue;
    found.add(cleaned);
  }
  for (const match of SOURCE.matchAll(/'([^']*)'/g)) {
    const raw = match[1];
    if (!raw || !raw.startsWith(base) || raw === base) continue;
    found.add(raw);
  }
  return [...found].sort();
}

it('模型源码里真实存在的每个调用点都被放行', async () => {
  ok();
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
      'warehouse/pick-waves/capabilities',
      'warehouse/pick-waves/rules',
      `warehouse/pick-waves/rules/${ID}/retire`,
      'warehouse/pick-waves/candidates',
      `warehouse/pick-waves/${ID}`,
      `warehouse/pick-waves/${ID}/release`,
      `warehouse/pick-waves/${ID}/complete`,
      `warehouse/pick-waves/${ID}/cancel`,
      `warehouse/pick-waves/${ID}/tasks/${ID}/detach`,
    ]),
  );
});

it('写动作的 POST 形状被放行，且真的转发到 8084', async () => {
  const fetcher = ok();
  for (const path of [
    'warehouse/pick-waves',
    'warehouse/pick-waves/rules',
    `warehouse/pick-waves/rules/${ID}/retire`,
    `warehouse/pick-waves/${ID}/release`,
    `warehouse/pick-waves/${ID}/complete`,
    `warehouse/pick-waves/${ID}/cancel`,
    `warehouse/pick-waves/${ID}/tasks/${ID}/detach`,
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8084/warehouse/pick-waves'),
    expect.objectContaining({ method: 'POST' }),
  );
});

/**
 * **波次是独立顶层分支，不挂在 8.3.1.1 的 outbound-orders 底下，也不挂在
 * 8.3.2.1 的 pick-tasks 底下。**
 *
 * <p>一个波次横跨多张出库单，它不属于任何一张出库单，也不属于任何一张拣货任务。
 * 挂成 `outbound-orders/{id}/waves` 或 `pick-tasks/{id}/waves` 那种子资源形状，
 * 会让契约与路由都以为波次从属于某一张单 —— 而实际上八张单的排班聚成一波时，
 * 任取一张都代表不了这一波。静态检查全绿，所以反向钉住。
 *
 * <p><b>注意路径前缀</b>：8.3.1.1 的 outbound-orders 是 <b>inventory/ 组内</b>的分支
 * （真实代理路径是 {@code warehouse/inventory/outbound-orders/...}）；
 * 而本模块的 pick-waves 与 pick-tasks 一样是<b>顶层</b>分支。
 */
it('波次是独立顶层分支，不挂在 outbound-orders 或 pick-tasks 底下', async () => {
  ok();
  for (const path of [
    `warehouse/inventory/outbound-orders/${ID}/waves`,
    `warehouse/pick-tasks/${ID}/waves`,
    'warehouse/pick-tasks/waves',
    'warehouse/inventory/outbound-orders/pick-waves',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行：波次必须用自己的顶层分支`).toBe(404);
  }
  // 反向钉住：8.3.1.x 与 8.3.2.1 的原有形状一个都没被本车道吃掉
  for (const path of [
    'warehouse/inventory/outbound-orders/capabilities',
    'warehouse/inventory/outbound-orders/pick-requests',
    'warehouse/pick-tasks/capabilities',
    `warehouse/pick-tasks/${ID}/picks`,
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `已交付模块的 ${path} 被本车道误伤 —— 白名单必须求并`).toBe(200);
  }
});

/** 刻意没有 PUT / DELETE 端点：成员归属是排班事实，流水是 append-only 凭据。 */
it('PUT / DELETE 不由代理拦：路径在白名单内就会被转发，方法由后端 405 兜住', async () => {
  const fetcher = ok();
  for (const path of [`warehouse/pick-waves/${ID}`, 'warehouse/pick-waves']) {
    expect(
      (await PUT(request(path, 'PUT'), { params: Promise.resolve({ path: path.split('/') }) }))
        .status,
      `PUT ${path} 应转发给后端`,
    ).toBe(200);
    expect(
      (
        await DELETE(request(path, 'DELETE'), {
          params: Promise.resolve({ path: path.split('/') }),
        })
      ).status,
      `DELETE ${path} 应转发给后端`,
    ).toBe(200);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8084/warehouse/pick-waves'),
    expect.objectContaining({ method: 'PUT' }),
  );
});

it('未交付 / 不该存在的形状仍然被拒，且「多一层后缀」也仍然 404', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 已放行的动作后面再多一层 —— 必须仍然 404（放行一个不存在的动作比 404 更糟）。
    `warehouse/pick-waves/${ID}/release/force`,
    `warehouse/pick-waves/${ID}/complete/force`,
    `warehouse/pick-waves/${ID}/cancel/reason`,
    `warehouse/pick-waves/${ID}/tasks/${ID}/detach/undo`,
    `warehouse/pick-waves/${ID}/tasks/assign`,
    // 未登记的规则动作。
    `warehouse/pick-waves/rules/${ID}/delete`,
    `warehouse/pick-waves/rules/${ID}`,
    // 字面量子路径后面多一层。
    'warehouse/pick-waves/capabilities/advanced',
    'warehouse/pick-waves/rules/advanced',
    // 非 UUID 段不应被 [0-9a-f-]+ 分支吃掉。
    'warehouse/pick-waves/not-a-uuid',
    // 拼错的顶层分支。
    'warehouse/pick-wave',
    'warehouse/pick_waves/capabilities',
    'warehouse/pickwaves',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * **字面量子路径必须排在 UUID 通配之前。**
 *
 * <p>alternation 是首个命中即返回，而 `candidates` 里的 c/a/n/d/i/t/e/s **全部**落在
 * [0-9a-f-] 里。一旦把 `\/candidates` 排到 `\/[0-9a-f-]+` 之后，
 * `/candidates` 会被先命中 UUID 分支、再因多出后缀而整条落空 —— 表现为
 * 候选端点静默 404，而所有静态检查全绿。这里显式钉住它的可放行性。
 */
it('字面量子路径（capabilities / rules / candidates）不会被 UUID 通配分支吃掉', async () => {
  ok();
  for (const leaf of ['capabilities', 'rules', 'candidates']) {
    const path = ['warehouse', 'pick-waves', leaf];
    const response = await GET(request(path.join('/')), { params: Promise.resolve({ path }) });
    expect(response.status, `${leaf} 被 UUID 通配分支吃掉了`).toBe(200);
  }
});

/** 不误伤其他车道已登记的分支。白名单是多车道共用的**累积型**文件。 */
it('不误伤本车道之前已放行的仓库分支（求并，不是二选一）', async () => {
  ok();
  for (const path of [
    'warehouse/inventory/overview',
    'warehouse/inventory/outbound-orders/capabilities',
    'warehouse/location-trees/capabilities',
    'warehouse/assembly-orders/capabilities',
    'warehouse/disassembly-orders/capabilities',
    'warehouse/putaway-tasks/capabilities',
    'warehouse/pick-tasks/capabilities',
    `warehouse/pick-tasks/${ID}/complete`,
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
  const path = `warehouse/pick-waves/${ID}/release`;
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
