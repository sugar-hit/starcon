import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST } from './[...path]/route';

/**
 * 9.2.1.1 待审队列的 api-proxy 白名单**双向**断言。
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
 *   <li><b>拒绝</b>：刻意不放行的形状仍然 404。放行不该存在的路径比 404 更糟 ——
 *       它会让界面以为「已经可用」。本模块尤其要紧：
 *       蓝图加粗句「批量通过必须基于明确的规则，而不是默认全过」，
 *       所以**批量动作只能有 POST /batches 一个入口**（带幂等键与规则），
 *       多放行一条「一键全过」形状就等于在 API 层开了个后门。</li>
 * </ul>
 *
 * <p>🔴 **负向样本全部用本模块自己的前缀**（{@code trade/order-pending-review/...}）：
 * 拿兄弟车道的当前缀当「未交付形状」会两重出错 ——
 * ① 那个前缀若同批已交付，断言会与白名单互相矛盾（假红）；
 * ② 若那条白名单被取错一侧而静默删掉，断言反而会绿，把真事故盖住。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/orders/order-pending-review-model.ts', import.meta.url)),
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
    ...(method === 'GET' ? {} : { body: '{}' }),
  });
}

/**
 * 从模型源码里反向抽出**真实存在**的路径。
 *
 * <p>模型里所有端点都是 `` `${base}/xxx` `` 拼出来的（base 是常量
 * {@code trade/order-pending-review}），所以抽取必须先把模板串**按 base 展开**
 * 再匹配 —— 只认字面量会把全部调用点漏成一条，然后「放行了三个」也算通过。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/order-pending-review';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      // ${query(...)} 整段去掉，${orderId} 换成 uuid。
      .replace(/\$\{query\([^)]*\)\}/g, '')
      .replace(/\$\{[^}]*\}/g, ID)
      .replace(/\?.*$/, '');
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
  // 抽到的路径必须**真的**覆盖本模块的形状，否则「只放行了三个」也算通过。
  expect(sites).toEqual(
    expect.arrayContaining([
      'trade/order-pending-review/capabilities',
      'trade/order-pending-review/summary',
      'trade/order-pending-review/queue',
      'trade/order-pending-review/batches',
      `trade/order-pending-review/queue/${ID}`,
      `trade/order-pending-review/queue/${ID}/evaluate`,
      `trade/order-pending-review/batches/${ID}`,
    ]),
  );
});

it('写形状（POST）被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/order-pending-review/batches',
    `trade/order-pending-review/queue/${ID}/evaluate`,
  ]) {
    const segments = path.split('/');
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: segments }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  await expect(
    POST(request('trade/order-pending-review/batches', 'POST'), {
      params: Promise.resolve({ path: 'trade/order-pending-review/batches'.split('/') }),
    }),
  ).resolves.toMatchObject({ status: 200 });
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/order-pending-review/batches'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('刻意不放行的形状仍然被拒 —— 放行后端不存在的映射比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 🔴 蓝图加粗句的直接对手：一条「一键全过」的后门。
    // 批量动作的唯一合法入口是 POST /batches（要幂等键 + 规则），
    // 放行 bulk-approve 就等于在 API 层把「必须基于明确的规则」这个闸门拆了。
    'trade/order-pending-review/bulk-approve',
    'trade/order-pending-review/decisions',
    'trade/order-pending-review/approve-all',
    // 单条「改状态」的裸端点不存在：改审单结论只有批量端点。
    `trade/order-pending-review/queue/${ID}/auto-approve`,
    `trade/order-pending-review/queue/${ID}/bulk`,
    `trade/order-pending-review/queue/${ID}/status`,
    // 逐条结果在 batches/{uuid} 里，不另开一个形状。
    `trade/order-pending-review/batches/${ID}/items`,
    // 裸路径 / 假形状 / 非 UUID 段 / 多一层。
    'trade/order-pending-review',
    'trade/order-pending-review/queue/not-a-uuid',
    `trade/order-pending-review/queue/${ID}/evaluate/extra`,
    `trade/order-pending-review/queue/${ID}x`,
    `trade/order-pending-review/batches/${ID}x`,
    // 不属于本模块的形状（本模块没有「列出全部订单」这种读口）。
    'trade/order-pending-review/orders',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 竟被放行了`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

it('字面量子路径不被 uuid 分支吃掉', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 全部以字母开头且不是十六进制：写反判别顺序就会被 uuid 分支提前吃掉半截。
    'trade/order-pending-review/capabilities',
    'trade/order-pending-review/summary',
    'trade/order-pending-review/queue',
    'trade/order-pending-review/batches',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 未被放行（判别顺序写反了？）`).toBe(200);
  }
});

it('不误伤其它 trade 车道已登记的形状', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 9.2.1.2 审单规则
    'trade/order-review/capabilities',
    'trade/order-review/rules',
    // 9.2.1.3 异常拦截
    'trade/order-intercept/capabilities',
    'trade/order-intercept/checks',
    // 9.2.2.2 风控校验
    'trade/orders/risk-validation/capabilities',
    // 9.3 履约（真实形状从各车道的模型里取，不靠猜）
    'trade/fulfillment/consolidation/capabilities',
    'trade/fulfillment/monitor/capabilities',
    // 9.1.1.1 订单列表与 9.1.2.1 平台订单同步
    'trade/sales-orders/list/capabilities',
    'trade/platform-orders/capabilities',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被本模块的条目误伤了`).toBe(200);
  }
});
