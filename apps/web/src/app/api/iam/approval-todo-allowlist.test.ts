import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET } from './[...path]/route';

/**
 * 1.1.1.3 审批待办在 identity 代理白名单里的**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest 全绿。
 * 而且 `api-proxy.ts` 是**多车道共用的累积型白名单**，取错一侧会静默删掉
 * 别人已交付的路由（表现同样是页面 404，而所有检查全绿）。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从本模块自己的前端源码里<b>反向抽取真实调用点</b>再逐条断言被放行 ——
 *       写死一份清单只会验证「清单与白名单一致」，而真正会出事的是
 *       「源码加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：刻意不放行的形状仍然 404。最要紧的是那几条<b>写形状</b> ——
 *       蓝图 1.1.1.3 原文「工作台不执行审批动作，避免两处审批逻辑不一致」，
 *       放行一个后端不存在的写入口，界面就会以为「审批待办页能直接批单」。</li>
 * </ul>
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(
    new URL('../../../components/dashboard/approval-todo-workspace.tsx', import.meta.url),
  ),
  'utf8',
);

const ID = '22222222-2222-2222-2222-222222222222';

afterEach(() => vi.unstubAllGlobals());

function request(path: string) {
  return new NextRequest(`http://localhost:3000/api/iam/${path}`, {
    method: 'GET',
    headers: {
      origin: 'http://localhost:3000',
      cookie: 'starcon_session=private-session',
      // 伪造的租户头与内部键：代理必须忽略它们，转发时不带。
      'X-Tenant-Id': 'forged',
      'X-Internal-Key': 'forged',
    },
  });
}

/**
 * 从源码里抽出真实调用点。
 *
 * <p>形如 `api<...>(\`identity/approval-todos?${query}\`)` 的字面量 ——
 * 把 {@code ${...}} 换成 UUID 形状，query 部分截掉。
 */
function realCallSites(): string[] {
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/api<[^>]*>\(\s*`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw) continue;
    const cleaned = raw
      .replace(/\$\{[^}]*\}/g, ID)
      .replace(/\?.*$/, '')
      .trim();
    if (!cleaned) continue;
    found.add(cleaned);
  }
  for (const match of SOURCE.matchAll(/api<[^>]*>\(\s*'([^']*)'/g)) {
    const raw = match[1];
    if (!raw) continue;
    const cleaned = raw.replace(/\?.*$/, '').trim();
    if (!cleaned) continue;
    found.add(cleaned);
  }
  return [...found].sort();
}

it('前端源码里真实存在的每个调用点都被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const sites = realCallSites();
  expect(sites.length, '前端源码里一个调用点都没抽到 —— 断言本身失效了').toBeGreaterThan(0);
  for (const path of sites) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 未被放行（白名单漏登记 = 浏览器里静默 404）`).toBe(200);
  }
  expect(sites).toEqual(expect.arrayContaining(['identity/approval-todos']));
});

it('查询串不影响放行判定', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // 筛选条件不会让路径落到白名单之外。
  const response = await GET(request('identity/approval-todos?sort=WAITING&size=50&page=0'), {
    params: Promise.resolve({ path: ['identity', 'approval-todos'] }),
  });
  expect(response.status).toBe(200);
});

it('刻意不放行的形状仍然被拒 —— 放行后端不存在的映射比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 能力声明与分档都在**同一个响应体**里，后端没有这两个子路径。
    'identity/approval-todos/capabilities',
    'identity/approval-todos/options',
    // 没有单条待办详情端点。
    `identity/approval-todos/${ID}`,
    // 🔴 审批动作归 17.4.2.1，工作台不执行审批动作 ⇒ 这些写形状不存在。
    `identity/approval-todos/${ID}/decide`,
    `identity/approval-todos/${ID}/approve`,
    `identity/approval-todos/${ID}/reject`,
    `identity/approval-todos/${ID}/transfer`,
    // 复制单号是页内动作，没有端点。
    `identity/approval-todos/${ID}/copy`,
    // 非 UUID 段不应被放行。
    'identity/approval-todos/not-a-uuid',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被放行了，但它在后端不存在`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

it('不误伤上游 17 的既有形状', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 17.4.2.1 待我审批（审批动作的归属系统）
    'identity/my-approvals',
    'identity/my-approvals/capabilities',
    `identity/my-approvals/${ID}`,
    `identity/my-approvals/${ID}/decide`,
    `identity/my-approvals/${ID}/transfer`,
    // 17.4.2.2 审批记录
    'identity/approval-records',
    'identity/approval-records/statistics',
    // 17.4.1.x 审批流与审批规则
    'identity/approval-flows',
    'identity/approval-rules',
    // identity 其余页面
    'identity/roles',
    'identity/members',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 必须仍然放行`).toBe(200);
  }
});
