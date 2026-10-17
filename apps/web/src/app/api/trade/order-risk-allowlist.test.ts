import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST, PUT } from './[...path]/route';

/**
 * 9.2.2.2 风控校验的 api-proxy 白名单**双向**断言。
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
 *       蓝图加粗句「风控分数不能单独作为拒绝依据」，所以**改最终动作的入口
 *       只能有 /review 一个**，多放行一条 reject 形状就等于在 API 层开了个后门。</li>
 * </ul>
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/orders/order-risk-model.ts', import.meta.url)),
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
 * {@code trade/orders/risk-validation}），所以抽取必须先把模板串**按 base 展开**
 * 再匹配 —— 只认字面量会把全部调用点漏成一条，然后「放行了三个」也算通过。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/orders/risk-validation';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      // ${query(...)} 整段去掉，${assessmentId} 换成 uuid。
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
      'trade/orders/risk-validation/capabilities',
      'trade/orders/risk-validation/overview',
      'trade/orders/risk-validation/rule',
      'trade/orders/risk-validation/signals',
      'trade/orders/risk-validation/assessments',
      `trade/orders/risk-validation/assessments/${ID}`,
      `trade/orders/risk-validation/assessments/${ID}/review`,
    ]),
  );
});

it('写形状（POST / PUT）被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/orders/risk-validation/signals',
    'trade/orders/risk-validation/assessments',
    `trade/orders/risk-validation/assessments/${ID}/review`,
  ]) {
    const segments = path.split('/');
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: segments }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  await PUT(request('trade/orders/risk-validation/rule', 'PUT'), {
    params: Promise.resolve({ path: 'trade/orders/risk-validation/rule'.split('/') }),
  });
  await expect(
    PUT(request('trade/orders/risk-validation/rule', 'PUT'), {
      params: Promise.resolve({ path: 'trade/orders/risk-validation/rule'.split('/') }),
    }),
  ).resolves.toMatchObject({ status: 200 });
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/orders/risk-validation/assessments'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('刻意不放行的形状仍然被拒 —— 放行后端不存在的映射比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 🔴 蓝图加粗句的直接对手：只凭分数拒单的后门。
    // 改最终动作的唯一合法入口是 /review（要人工身份），
    // 放行 reject / decision 会让「分数单独作为拒绝依据」在 API 层重新成立。
    `trade/orders/risk-validation/assessments/${ID}/reject`,
    `trade/orders/risk-validation/assessments/${ID}/decision`,
    `trade/orders/risk-validation/assessments/${ID}/final-action`,
    // 信号是时间序列，没有「改一条」的语义。
    `trade/orders/risk-validation/signals/${ID}`,
    `trade/orders/risk-validation/signals/${ID}/status`,
    // 一租户一条规则，没有集合读。
    'trade/orders/risk-validation/rules',
    'trade/orders/risk-validation/signal-codes',
    // 下游 9.2.1.3 审单拦截未交付，不在本服务的命名空间里。
    `trade/orders/risk-validation/assessments/${ID}/block`,
    // 裸路径 / 假形状 / 非 UUID 段。
    'trade/orders/risk-validation',
    'trade/orders/risk-validation/assessments/not-a-uuid',
    `trade/orders/risk-validation/assessments/${ID}/review/extra`,
    `trade/orders/risk-validation/assessments/${ID}x`,
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
    'trade/orders/risk-validation/capabilities',
    'trade/orders/risk-validation/overview',
    'trade/orders/risk-validation/rule',
    'trade/orders/risk-validation/signals',
    'trade/orders/risk-validation/assessments',
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
    // 9.3.1.2 合单
    'trade/fulfillment/consolidation/capabilities',
    'trade/fulfillment/consolidation/rule',
    // 9.3.2.2 履约路由
    'trade/fulfillment/routing/capabilities',
    // 9.1.1.1 订单列表与手工单（真实形状从各车道的模型里取，不靠猜）
    'trade/sales-orders/list/capabilities',
    'trade/sales-orders/detail/capabilities',
    'trade/dealers/capabilities',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被本模块的条目误伤了`).toBe(200);
  }
});
