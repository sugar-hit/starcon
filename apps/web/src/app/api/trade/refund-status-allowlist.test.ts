import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST } from './[...path]/route';

/**
 * 11.3.1.2 退款状态的 api-proxy 白名单**双向**断言（按模块一份，与同目录
 * `aftersales-allowlist.test.ts` / `aftersales-detail-allowlist.test.ts` 同一手法）。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest 全绿。所以这里逐条钉住，
 * 而且判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p>⚠️ 本文件的存在理由比同批两个更硬：上一任 worker **没有**碰 `api-proxy.ts`，
 *   于是后端 11 个端点与页面都已存在，浏览器这一侧却一条都放行不了 ——
 *   页面永远停在错误态而后端日志里一次请求都没有。所以这里是「交付完整性」的前置闸。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从本模块自己的模型源码里<b>反向抽取真实调用点</b>再逐条断言被放行 ——
 *       写死一份清单只会验证「清单与白名单一致」，而真正会出事的是
 *       「源码加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：刻意不放行的形状仍然 404。11.3.1.1 退款申请与 11.2 退回旧货
 *       **未交付**，放行它们会让界面以为功能已接通。</li>
 * </ul>
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/aftersales/refund-status-model.ts', import.meta.url)),
  'utf8',
);

const ID = '11111111-1111-1111-1111-111111111111';
const DIFF = '22222222-2222-2222-2222-222222222222';

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

/** 反向抽取模型源码里真实存在的调用点（query 尾巴剥掉）。 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/refunds';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      // ⚠️ **先**剥掉「函数调用型」插值（`${refundListQuery(params)}` 那种拼在 base 后面、
      //    本身不是路径段的），再把 `${id}` 填成 UUID。顺序反了会得到
      //    `trade/refunds1111...` —— 少一个斜杠、看起来像白名单漏登记，其实是断言自己错了。
      .replace(/\$\{[a-zA-Z_$][\w$]*\([^)]*\)\}/g, '')
      // 嵌套模板字面量会被正则截断成 `${base}/discrepancies${suffix ` 这样带**未闭合** `${`
      //  的尾巴；把它截掉，剩下的才是真正的路径段。
      .replace(/\$\{[^}]*$/, '')
      .replace(/\$\{discrepancyId\}/g, DIFF)
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
  expect(sites).toEqual(
    expect.arrayContaining([
      'trade/refunds/capabilities',
      'trade/refunds/filters',
      'trade/refunds/discrepancies',
      `trade/refunds/${ID}`,
    ]),
  );
});

it('写形状（POST）被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // 登记、派发/重试、查证、回报、平台同步、登记差异、裁定差异 —— 七件写形状逐条钉住。
  for (const path of [
    'trade/refunds',
    `trade/refunds/${ID}/dispatch`,
    `trade/refunds/${ID}/verifications`,
    `trade/refunds/${ID}/reports`,
    `trade/refunds/${ID}/platform-sync`,
    `trade/refunds/${ID}/discrepancies`,
    `trade/refunds/${ID}/discrepancies/${DIFF}/resolve`,
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/refunds/'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('刻意不放行的形状仍然被拒 —— 放行后端不存在的映射比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 11.3.1.1 退款申请未交付：放行会让界面以为「可退余额校验 + 审批」已接通。
    `trade/refunds/${ID}/refund-request`,
    // 11.2 退回旧货未交付
    `trade/refunds/${ID}/returns`,
    // 单字符不是 uuid：`[0-9a-f-]+` 会把它放行，而症状是有人 GET
    // .../refunds/c 会把一个「退款 id 是 c」的请求打到后端。
    'trade/refunds/c',
    // 子资源上再挂一层没有的形状
    `trade/refunds/${ID}/dispatch/force`,
    // 差别 id 位置放错段
    `trade/refunds/discrepancies/${DIFF}/resolve`,
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被放行了，但它在后端不存在`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

it('代理只按路径放行 —— 方法级的保护在后端，不在这里', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  // 刻意**不**断言「PUT/DELETE 被代理挡下」：allowlist 只匹配路径，不看方法，
  // 同路径的 PUT 会被照常转发（实测返回上游不可达）。退款执行与其派发/查证/回报/差异
  // 全部是留痕，改写与删除的拒绝发生在**后端**（只映射 GET/POST，写库走留痕表的
  // 列级白名单）。在这里编一条「代理挡住了」的断言，等于给后来的人一个假的安全感。
  await GET(request(`trade/refunds/${ID}`, 'PUT'), {
    params: Promise.resolve({ path: `trade/refunds/${ID}`.split('/') }),
  });
  expect(fetcher).toHaveBeenCalled();
});

it('售后侧的形状没有被本模块的正则吃走', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  // 本条元素排在 11.1.1.2 / 11.1.1.1 那两条**之前**被匹配，形状互不相交是它能并排的前提。
  for (const path of [
    'trade/aftersales/capabilities',
    'trade/aftersales/filters',
    'trade/aftersales/cases',
    `trade/aftersales/cases/${ID}`,
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被本模块的正则误伤了`).toBe(200);
  }
});
