import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST } from './[...path]/route';

/**
 * 1.5.2.1 服务与任务状态的 api-proxy 白名单**双向**断言。
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
    new URL('../../../components/system-health/service-task-status-model.ts', import.meta.url),
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
 * <p>模型里的端点既有字面量（{@code 'channel/service-task-status/capabilities'}）也有模板串
 * （{@code `channel/service-task-status/tasks/${taskId}`}），两种都要抽 ——
 * 只认字面量会把带 id 的那一半漏成零条，然后「只放行了几个」也算通过。
 */
function realCallSites(): string[] {
  const base = 'channel/service-task-status';
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
    if (!expanded.startsWith(base)) continue;
    const cleaned = expanded
      // `${taskId}` 是任务 id，展开成一段 UUID 形状
      .replace(/\$\{taskId\}/g, ID)
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
  // 抽到的路径必须**真的**覆盖本模块的形状，否则「只放行了三个」也算通过。
  expect(sites).toEqual(
    expect.arrayContaining([
      'channel/service-task-status/capabilities',
      'channel/service-task-status/options',
      'channel/service-task-status/summary',
      'channel/service-task-status/tasks',
      `channel/service-task-status/tasks/${ID}`,
      'channel/service-task-status/alerts',
    ]),
  );
});

/**
 * 🔴 **只读这件事由后端挡，代理按路径放行。**
 *
 * <p>api-proxy 的白名单是**路径级**的（不分方法）—— 这是本文件的既有形状：
 * 同一个路径 POST / PUT 都转发，由后端 Spring 的方法映射决定放不放行。
 * 所以本模块「只读」的保证是：**后端只有 @GetMapping**，
 * 写方法落到后端会拿到 **405 Method Not Allowed**（`ServiceTaskStatusIntegrationTest.moduleIsReadOnly` 实跑断言）。
 *
 * <p>这里断言的是**代理不要凭空发明写路径** —— 放行一个后端不存在的写形状，
 * 界面会以为「重跑同步 / 取消任务」在这儿能做，比 404 更糟。
 */
it('写形状不被代理凭空放行（本模块只读，后端用 405 挡）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    `channel/service-task-status/tasks/${ID}/retry`,
    `channel/service-task-status/tasks/${ID}/cancel`,
    `channel/service-task-status/tasks/${ID}/rerun`,
    'channel/service-task-status/tasks/refresh',
    'channel/service-task-status/alerts/resolve',
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 不应被放行（后端没有这个写形状）`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

it('未交付 / 不存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 裸路径：后端没有「一条汇总列表接口」，一切入口都带子路径。
    'channel/service-task-status',
    // 汇总段只有这五个子路径。
    'channel/service-task-status/overview',
    'channel/service-task-status/stats',
    // 非 UUID 段不应被 uuid 分支吃掉。
    'channel/service-task-status/tasks/not-a-uuid',
    // tasks 段下只有列表与单条详情。
    `channel/service-task-status/tasks/${ID}/extra`,
    // 🔴 「重跑同步」没有归属系统的写端点，工作台不写业务数据 ⇒ 这个形状不存在
    `channel/service-task-status/tasks/${ID}/retry`,
    // 🔴 「取消任务」归归属系统，本模块只提供跳转
    `channel/service-task-status/tasks/${ID}/cancel`,
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 裸路径 `service-task-status` 本身**必须被拒**：后端没有这个映射，
 * 放行它会让界面以为「一条不存在的汇总接口」可用。
 */
it('裸路径 service-task-status 被拒：既不转发，也不冒充成上游错误', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const path = 'channel/service-task-status';
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
    // 16.1.1.1 账号健康总览（首 token 与 service-task-status 不同，但同属 channel/）
    'channel/account-health/capabilities',
    // 2.2.1.1 连接列表
    'channel/connections',
    // 2.4.1.1 同步任务列表
    'channel/sync-cursors',
    // 2.4.2.1 失败队列
    'channel/sync-failures',
    // 1.5.1.1 同步与 API 健康（**同属 channel/**，首 token 是 sync-health）
    'channel/sync-health/capabilities',
    'channel/sync-health/connections',
    // 1.2.1.1 全局搜索（channel 侧）
    'channel/global-search',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被本车道新增的白名单条目误伤了`).toBe(200);
  }
});
