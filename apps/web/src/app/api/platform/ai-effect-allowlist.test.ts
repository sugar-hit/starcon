import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST } from './[...path]/route';

/**
 * 18.4.3.2 效果评估 的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest / Playwright 全绿。
 * 所以判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从本模块自己的模型源码里<b>反向抽取真实调用点</b>再逐条断言被放行 ——
 *       写死一份清单只会验证「清单与白名单一致」，而真正会出事的是
 *       「源码加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：未交付 / 不该存在的形状仍然 404。放行不该存在的路径比 404 更糟 ——
 *       它会让界面以为「能点一下就下线」「能按 id 取单条阈值」。</li>
 * </ul>
 *
 * <p>🔴 负向样本**全部取自本模块自己的前缀** {@code platform/ai/effects/...}：
 * 拿兄弟车道的前缀当「未交付形状」是假红 ——
 * 本仓的 {@code ai} 命名空间是兄弟车道共用的（18.4.2.2 预测、18.4.3.1 用量与配额、
 * 18.5.1.1 模板管理各自落在 {@code /platform/ai/…} 的其它第二段上），
 * 它们交付后本断言就会变红。
 *
 * <p><b>不误伤其他车道已放行的分支</b>：白名单是多车道共用的**累积型**文件。
 * 本车道只**新增**一条 platform/ services 条目，不动既有任何一条 ——
 * 但那必须被断言钉住，否则下一次并入取错一侧时会静默删掉已交付模块的路由。
 */

/** 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。 */
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/ai/ai-effect-model.ts', import.meta.url)),
  'utf8',
);

/** id 段的形状：完整 UUID（与白名单正则里的那一段一致）。 */
const ID = '00000000-0000-4000-8000-000000000000';

afterEach(() => vi.unstubAllGlobals());

function request(path: string, method = 'GET') {
  return new NextRequest(`http://localhost:3000/api/${path}`, {
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
 * <p>模型里所有端点都是 `` `${base}/xxx` `` 拼出来的（base 是常量 'platform/ai/effects'），
 * 所以抽取必须先把模板串**按 base 展开**再匹配 —— 只认字面量会把调用点全部漏成零条，
 * 然后「一个都没断言」也算通过，那正是这类守卫最典型的失效方式。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'platform/ai/effects';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      // 查询串插值整段去掉 —— 它不是路径的一段。
      .replace(/\$\{[^}]*query\([^)]*\)\}/g, '')
      // 其余插值（通常是 id）换成 uuid。
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
  // 抽到的路径必须**真的**覆盖本模块的形状，否则「只放行了一条」也算通过。
  expect(sites).toEqual(
    expect.arrayContaining([
      'platform/ai/effects/capabilities',
      'platform/ai/effects/overview',
      'platform/ai/effects/adoptions',
      `platform/ai/effects/adoptions/${ID}/withdraw`,
      'platform/ai/effects/adoption-report',
      'platform/ai/effects/feedback',
      `platform/ai/effects/feedback/${ID}/withdraw`,
      'platform/ai/effects/backtests',
      'platform/ai/effects/backtests/run',
      'platform/ai/effects/thresholds',
      'platform/ai/effects/suggestions',
      'platform/ai/effects/suggestions/generate',
      `platform/ai/effects/suggestions/${ID}/decision`,
    ]),
  );
});

it('写动作的 POST 形状被放行，且真的转发到 8089', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const adoption = await POST(request('platform/ai/effects/adoptions', 'POST'), {
    params: Promise.resolve({ path: ['platform', 'ai', 'effects', 'adoptions'] }),
  });
  expect(adoption.status, 'POST platform/ai/effects/adoptions 未被放行').toBe(200);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8089/platform/ai/effects/adoptions'),
    expect.objectContaining({ method: 'POST' }),
  );

  // 🔴 阈值走 **POST**（局部更新，不是整份替换）：浏览器侧的 platform 代理
  //   刻意不导出 PUT ⇒ PUT 端点在浏览器里是 405，功能等于没交付。
  const threshold = await POST(request('platform/ai/effects/thresholds', 'POST'), {
    params: Promise.resolve({ path: ['platform', 'ai', 'effects', 'thresholds'] }),
  });
  expect(threshold.status, 'POST platform/ai/effects/thresholds 未被放行').toBe(200);

  const backtest = await POST(request('platform/ai/effects/backtests/run', 'POST'), {
    params: Promise.resolve({ path: ['platform', 'ai', 'effects', 'backtests', 'run'] }),
  });
  expect(backtest.status, 'POST platform/ai/effects/backtests/run 未被放行').toBe(200);

  const generate = await POST(request('platform/ai/effects/suggestions/generate', 'POST'), {
    params: Promise.resolve({ path: ['platform', 'ai', 'effects', 'suggestions', 'generate'] }),
  });
  expect(generate.status, 'POST platform/ai/effects/suggestions/generate 未被放行').toBe(200);

  const decision = await POST(request(`platform/ai/effects/suggestions/${ID}/decision`, 'POST'), {
    params: Promise.resolve({
      path: ['platform', 'ai', 'effects', 'suggestions', ID, 'decision'],
    }),
  });
  expect(decision.status, 'POST platform/ai/effects/suggestions/{uuid}/decision 未被放行').toBe(
    200,
  );
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 裸路径：后端**没有**「一条不存在的汇总端点」。
    'platform/ai/effects',
    // 未实现的子路径。
    'platform/ai/effects/unknown',
    // 非 UUID 段不应被 uuid 分支吃掉。
    'platform/ai/effects/adoptions/not-a-uuid',
    'platform/ai/effects/feedback/not-a-uuid',
    'platform/ai/effects/suggestions/not-a-uuid',
    // 多一段仍然 404。
    `platform/ai/effects/adoptions/${ID}/extra`,
    // 后端没有「按 id 取单条阈值」的端点（阈值是租户级一行）。
    `platform/ai/effects/thresholds/${ID}`,
    // 后端没有「按 id 取单条采纳记录 / 反馈」的端点。
    `platform/ai/effects/adoptions/${ID}`,
    // 🔴 本模块**没有下线端点**：本仓没有按能力粒度的 AI 功能开关。
    `platform/ai/effects/suggestions/${ID}/disable`,
    `platform/ai/effects/suggestions/${ID}/accept`,
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

it('不误伤同服务已交付的模块 —— 白名单是累积型的', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // 🔴 四条都是 `prefix: 'platform/'`：必须靠**正则不相交**分开，
  //   而不是靠「谁先匹配上」—— 白名单里既有那支的 `^apps…$` / `^text…$`
  //   吃不到 `ai/effects/…`，而 `ai` 那三支的第二 token 各不相同。
  for (const path of [
    'platform/apps/capabilities',
    'platform/apps/overview',
    'platform/text/capabilities',
    'platform/text/overview',
    'platform/ai/usage/capabilities',
    'platform/ai/forecast/capabilities',
    'platform/ai/templates/capabilities',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被本车道的改动误伤了`).toBe(200);
  }
});
