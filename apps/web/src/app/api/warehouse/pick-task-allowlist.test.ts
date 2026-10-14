import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { DELETE, GET, POST, PUT } from './[...path]/route';

/**
 * 8.3.2.1 拣货任务的 api-proxy 白名单**双向**断言。
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
 *       仍然 404</b> ——{id}/picks 放行不代表 {id}/picks/confirm 也该放行。</li>
 * </ul>
 *
 * <p><b>累积型文件的求并纪律</b>：白名单是多车道共用的，本车道只**追加**分支，绝不收窄既有分支。
 * 最后一组「不误伤其他车道」就是为此存在 —— 取错一侧会静默删掉已交付模块的路由，
 * 而表现是页面 404 且所有静态检查全绿。
 */

const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/warehouse/pick-task-model.ts', import.meta.url)),
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
 * <p>模型里所有端点都是 `` `${base}/xxx` `` 拼出来的（base 是常量 'warehouse/pick-tasks'），
 * 所以抽取必须先把模板串**按 base 展开**再匹配 —— 只认字面量会把调用点全部漏成零条，
 * 然后「一个都没断言」也算通过，那正是这类守卫最典型的失效方式。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'warehouse/pick-tasks';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      .replace(/\$\{pickTaskQuery\([^)]*\)\}/g, '')
      .replace(/\$\{[^}]*\}/g, ID)
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
    const response = await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status, `${path} 未被放行（白名单漏登记 = 浏览器里静默 404）`).toBe(200);
  }
  expect(sites).toEqual(
    expect.arrayContaining([
      'warehouse/pick-tasks/capabilities',
      `warehouse/pick-tasks/${ID}`,
      `warehouse/pick-tasks/${ID}/assign`,
      `warehouse/pick-tasks/${ID}/picks`,
      `warehouse/pick-tasks/${ID}/exceptions`,
      `warehouse/pick-tasks/${ID}/exceptions/${ID}/resolve`,
      `warehouse/pick-tasks/${ID}/complete`,
      `warehouse/pick-tasks/${ID}/reopen`,
      `warehouse/pick-tasks/${ID}/cancel`,
    ]),
  );
});

it('写动作的 POST 形状被放行，且真的转发到 8084', async () => {
  const fetcher = ok();
  for (const path of [
    'warehouse/pick-tasks',
    `warehouse/pick-tasks/${ID}/assign`,
    `warehouse/pick-tasks/${ID}/picks`,
    `warehouse/pick-tasks/${ID}/exceptions`,
    `warehouse/pick-tasks/${ID}/exceptions/${ID}/resolve`,
    `warehouse/pick-tasks/${ID}/complete`,
    `warehouse/pick-tasks/${ID}/reopen`,
    `warehouse/pick-tasks/${ID}/cancel`,
  ]) {
    const response = await POST(request(path, 'POST'), { params: { path: path.split('/') } });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8084/warehouse/pick-tasks'),
    expect.objectContaining({ method: 'POST' }),
  );
});

/**
 * **8.3.2.1 不挂在 8.3.1.1 底下**：拣货任务用**自己的顶层分支**，不是 outbound-orders 的子路径。
 *
 * <p>出库单是履约单据、拣货任务是现场作业工单，两者生命周期不同（单据取消后工单还在）。
 * 挂成 `outbound-orders/{id}/pick-tasks` 那种子资源形状，会让契约与路由都以为拣货
 * 从属于某一张出库单 —— 而实际上一张出库单也可能先取消再重开。静态检查全绿，所以反向钉住。
 *
 * <p><b>注意路径前缀</b>：8.3.1.1 的 outbound-orders 是 <b>inventory/ 组内</b>的分支，
 * 真实代理路径是 {@code warehouse/inventory/outbound-orders/...}；
 * 而本模块的 pick-tasks 与 location-trees / assembly-orders 一样是<b>顶层</b>分支
 * （{@code warehouse/pick-tasks/...}）。这个位置差别是仓库既有约定，不是笔误。
 */
it('拣货任务是独立顶层分支，不挂在 8.3.1.1 的 outbound-orders 底下', async () => {
  ok();
  for (const path of [
    `warehouse/inventory/outbound-orders/${ID}/pick-tasks`,
    `warehouse/inventory/outbound-orders/${ID}/picks`,
    'warehouse/inventory/outbound-orders/pick-tasks',
  ]) {
    const response = await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status, `${path} 不应被放行：拣货任务必须用自己的顶层分支`).toBe(404);
  }
  // 反向钉住：8.3.1.x 的原有形状一个都没被本车道吃掉
  for (const path of [
    'warehouse/inventory/outbound-orders/capabilities',
    'warehouse/inventory/outbound-orders/detail-capabilities',
    'warehouse/inventory/outbound-orders/pick-requests',
    `warehouse/inventory/outbound-orders/${ID}`,
  ]) {
    const response = await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status, `8.3.1.x 的 ${path} 被本车道误伤 —— 白名单必须求并`).toBe(200);
  }
});

/**
 * 刻意没有 PUT / DELETE 端点：拣货明细与实拣记录是事实（V110 的 append-only 守卫会直接拒），
 * 已完成的任务是交给复核的凭据。
 *
 * <p>白名单按**路径**放行、**不按方法**过滤，所以 PUT / DELETE 会被照常转发，
 * 最终由后端回 405。写成「代理应该返回 404」会是一条永远失败的断言。
 */
it('PUT / DELETE 不由代理拦：路径在白名单内就会被转发，方法由后端 405 兜住', async () => {
  const fetcher = ok();
  for (const path of [`warehouse/pick-tasks/${ID}`, 'warehouse/pick-tasks']) {
    expect(
      (await PUT(request(path, 'PUT'), { params: { path: path.split('/') } })).status,
      `PUT ${path} 应转发给后端`,
    ).toBe(200);
    expect(
      (await DELETE(request(path, 'DELETE'), { params: { path: path.split('/') } })).status,
      `DELETE ${path} 应转发给后端`,
    ).toBe(200);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8084/warehouse/pick-tasks'),
    expect.objectContaining({ method: 'PUT' }),
  );
});

it('未交付 / 不该存在的形状仍然被拒，且「多一层后缀」也仍然 404', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 已放行的动作后面再多一层 —— 必须仍然 404（放行一个不存在的动作比 404 更糟）。
    `warehouse/pick-tasks/${ID}/picks/confirm`,
    `warehouse/pick-tasks/${ID}/complete/force`,
    `warehouse/pick-tasks/${ID}/reopen/again`,
    `warehouse/pick-tasks/${ID}/exceptions/${ID}/resolve/undo`,
    // 8.3.2.2 波次与合并拣货未交付。
    `warehouse/pick-tasks/${ID}/waves`,
    'warehouse/pick-tasks/waves',
    // capabilities 后面多一层。
    'warehouse/pick-tasks/capabilities/advanced',
    // 非 UUID 段不应被 [0-9a-f-]+ 分支吃掉。
    'warehouse/pick-tasks/not-a-uuid',
    // 拼错的顶层分支。
    'warehouse/pick-task',
    'warehouse/pick_task/capabilities',
    'warehouse/picktasks',
  ]) {
    const response = await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * **字面量子路径必须排在 UUID 通配之前。**
 *
 * <p>alternation 是首个命中即返回，而 "capabilities" 里的 c/a/p/b/l/t/i/e/s 中有大量
 * 字符落在 [0-9a-f-] 里。一旦把 `\/capabilities` 排到 `\/[0-9a-f-]+` 之后，
 * `/capabilities` 会被先命中 UUID 分支、再因多出后缀而整条落空 —— 表现为
 * capabilities 端点静默 404，而所有静态检查全绿。这里显式钉住它的可放行性。
 */
it('capabilities 不会被 UUID 通配分支吃掉（字面量在前的实测）', async () => {
  ok();
  const response = await GET(request('warehouse/pick-tasks/capabilities'), {
    params: { path: 'warehouse/pick-tasks/capabilities'.split('/') },
  });
  expect(response.status).toBe(200);
});

/** 不误伤其他车道已登记的分支。白名单是多车道共用的**累积型**文件。 */
it('不误伤本车道之前已放行的仓库分支（求并，不是二选一）', async () => {
  ok();
  for (const path of [
    'warehouse/inventory/overview',
    'warehouse/inventory/outbound-orders/capabilities',
    'warehouse/location-trees/capabilities',
    'warehouse/location-utilization/capabilities',
    'warehouse/location-allocations/capabilities',
    'warehouse/assembly-orders/capabilities',
    'warehouse/disassembly-orders/capabilities',
    `warehouse/disassembly-orders/${ID}/execute`,
  ]) {
    const response = await GET(request(path), { params: { path: path.split('/') } });
    expect(response.status, `已交付模块的 ${path} 被本车道误伤 —— 白名单必须求并`).toBe(200);
  }
});

/** 跨源写请求必须被拒（CSRF 闸），与白名单无关但同一份代理代码。 */
it('跨源写请求被 403 拒绝，不转发', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const path = `warehouse/pick-tasks/${ID}/picks`;
  const response = await POST(
    new NextRequest(`http://localhost:3000/api/warehouse/${path}`, {
      method: 'POST',
      headers: { origin: 'http://evil.example', cookie: 'starcon_session=x' },
      body: '{}',
    }),
    { params: { path: path.split('/') } },
  );
  expect(response.status).toBe(403);
  expect(fetcher).not.toHaveBeenCalled();
});