import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST } from './[...path]/route';

/**
 * 9.4.2.1 平台回传的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest 全绿。所以这里逐条钉住，
 * 而且判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p><b>本车道是在既有组旁边新起一组</b>：本模块新增
 * {@code fulfillment/shipment-writeback}，<b>没有并进</b> shipment-records /
 * pending-shipment / dropship-tracking 任一组。
 * 这一点不是洁癖：9.4.1.2 发货记录是「已经发生了什么」的事实台账，
 * 本模块是「把它回传给平台、并跟踪回传结果」的执行与状态机，
 * 生命周期不同 —— 合在一支会让「在发货记录详情页里就能管回传」看起来成立。
 * 所以除了「我的形状被放行」，还必须钉住兄弟组仍然只放行它们自己的形状。
 */

const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/orders/shipment-writeback-model.ts', import.meta.url)),
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
      'X-Tenant-Id': 'forged',
      'X-Internal-Key': 'forged',
    },
    ...(method === 'GET' ? {} : { body: '{}' }),
  });
}

/**
 * 从模型源码里反向抽出**真实存在**的路径。
 *
 * <p>只写死一份清单会验证「清单与白名单一致」，而真正会出事的是
 * 「源码加了新形状、白名单忘了登记」—— 所以这里从源码反向抽。
 */
function realCallSites(): string[] {
  const base =
    SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/fulfillment/shipment-writeback';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
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
      'trade/fulfillment/shipment-writeback/capabilities',
      'trade/fulfillment/shipment-writeback/board',
      'trade/fulfillment/shipment-writeback/events',
      'trade/fulfillment/shipment-writeback/tasks',
      'trade/fulfillment/shipment-writeback/retry-batch',
      `trade/fulfillment/shipment-writeback/tasks/${ID}`,
      `trade/fulfillment/shipment-writeback/tasks/${ID}/submit`,
      `trade/fulfillment/shipment-writeback/tasks/${ID}/outcome`,
      `trade/fulfillment/shipment-writeback/tasks/${ID}/verify`,
      `trade/fulfillment/shipment-writeback/tasks/${ID}/retry`,
      `trade/fulfillment/shipment-writeback/tasks/${ID}/corrections`,
    ]),
  );
});

it('写形状被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/fulfillment/shipment-writeback/tasks',
    `trade/fulfillment/shipment-writeback/tasks/${ID}/submit`,
    `trade/fulfillment/shipment-writeback/tasks/${ID}/outcome`,
    `trade/fulfillment/shipment-writeback/tasks/${ID}/verify`,
    `trade/fulfillment/shipment-writeback/tasks/${ID}/retry`,
    `trade/fulfillment/shipment-writeback/tasks/${ID}/corrections`,
    'trade/fulfillment/shipment-writeback/retry-batch',
  ]) {
    const segments = path.split('/');
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: segments }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/fulfillment/shipment-writeback'),
    expect.objectContaining({ method: 'POST' }),
  );
});

/**
 * 未交付的形状必须被拒：放行不该存在的路径比 404 更糟
 * —— 界面会以为存在一条并不存在的接口。
 *
 * <p>凭据行是 append-only 的举证凭据，**没有**改 / 删形状；
 * 要更正就走 corrections（新建一版 + 原任务转 SUPERSEDED）。
 */
it('未交付的形状仍然被拒', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 拼错的兄弟路径：交替写错就会互相吃掉，必须反向钉住。
    'trade/fulfillment/shipment-writeback/task',
    'trade/fulfillment/shipment-writeback/board/filters',
    // 非 UUID 段不应被 [0-9a-f-]+ 分支吃掉。
    `trade/fulfillment/shipment-writeback/tasks/not-a-uuid`,
    // tasks/{id} 下只有这五个两段形状。
    `trade/fulfillment/shipment-writeback/tasks/${ID}/complete`,
    `trade/fulfillment/shipment-writeback/tasks/${ID}/cancel`,
    `trade/fulfillment/shipment-writeback/tasks/${ID}/delete`,
    // append-only：没有 PUT / DELETE 形状，也没有逐条删除。
    `trade/fulfillment/shipment-writeback/tasks/${ID}/corrections/${ID}`,
    // 平台侧动作由 2 队列与查证端点驱动，本模块不直连平台。
    `trade/fulfillment/shipment-writeback/tasks/${ID}/platform-call`,
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 裸路径必须被拒：后端没有「一条 /trade/fulfillment/shipment-writeback 列表」的映射，
 * 放行它会让界面以为存在一条并不存在的列表接口。
 */
it('裸路径 fulfillment/shipment-writeback 被拒：既不转发，也不冒充成上游错误', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const path = 'trade/fulfillment/shipment-writeback';
  const response = await GET(request(path), {
    params: Promise.resolve({ path: path.split('/') }),
  });
  expect(response.status, '裸路径不应被白名单放行').toBe(404);
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

/**
 * 本车道**新加的是一组**，不是往既有组里并 —— 所以这里反向钉住兄弟组
 * 仍然只放行它们自己的形状，且不被新组挤掉或污染。
 */
it('兄弟车道的既有分组未被本车道挤掉或污染', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 9.4.1.2 发货记录（本 L4 上游，最容易被新组挤掉的一组）
    'trade/fulfillment/shipment-records/capabilities',
    'trade/fulfillment/shipment-records/records',
    `trade/fulfillment/shipment-records/records/${ID}`,
    `trade/fulfillment/shipment-records/records/${ID}/callbacks`,
    // 9.4.1.1 待发货
    'trade/fulfillment/pending-shipment/capabilities',
    'trade/fulfillment/pending-shipment/units',
    // 9.6.1.3 代发跟踪 / 9.3.2.2 路由 / 9.3.2.1 分仓
    'trade/fulfillment/dropship-tracking/capabilities',
    'trade/fulfillment/routing/capabilities',
    'trade/fulfillment/allocation/capabilities',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 是兄弟车道的形状，不该被本车道挤掉`).toBe(200);
  }
  // 反向：本模块的形状**不能**从兄弟组漏过去（两组并列，不存在前缀包含关系）
  for (const path of [
    'trade/fulfillment/shipment-records/shipment-writeback',
    'trade/fulfillment/pending-shipment/shipment-writeback',
    'trade/fulfillment/dropship-tracking/shipment-writeback',
    'trade/fulfillment/routing/shipment-writeback',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 属于本模块命名空间，不该走兄弟车道那一组`).toBe(404);
  }
});

/**
 * 结果未知态的补偿入口必须在白名单里，且**只有**它。
 *
 * <p>这条不是形式主义：verify 是未知态唯一合法的出路，而 retry 在未知态会被后端
 * 拒绝。白名单若把两者一起吞进去，前端就会在未知态渲染出一个点了必报错的按钮。
 */
it('未知态的补偿入口（查证）在白名单里，且不与重试混为一谈', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // 查证与重试是**两条独立形状**，各自放行 —— 未知态只走前者，由前端 model 禁用后者。
  for (const path of [
    `trade/fulfillment/shipment-writeback/tasks/${ID}/verify`,
    `trade/fulfillment/shipment-writeback/tasks/${ID}/retry`,
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 应被放行（是否放行由状态闸门决定，不由白名单决定）`).toBe(200);
  }
});
