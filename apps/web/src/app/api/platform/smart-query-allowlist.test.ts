import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST } from './[...path]/route';

/**
 * 18.4.1.2 智能查询 的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest / Playwright 全绿。
 * 所以判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发）。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从本模块自己的前端源码里<b>反向抽取真实调用点</b>再逐条断言被放行 ——
 *       写死一份清单只会验证「清单与白名单一致」。</li>
 *   <li><b>拒绝</b>：未交付 / 不该存在的形状仍然 404。</li>
 * </ul>
 *
 * <p>🔴 负向样本**全部取自本模块自己的前缀** {@code platform/smart-query/...}：
 * 拿兄弟车道的前缀当「未交付形状」是假红。
 *
 * <p><b>不误伤其他车道已放行的分支</b>：白名单是多车道共用的**累积型**文件。
 * 本车道只**新增**一条 platform/ services 条目，不动既有任何一条 ——
 * 但那必须被断言钉住，否则下一次并入取错一侧时会静默删掉已交付模块的路由
 * （表现是页面 404，而 typecheck / lint / vitest 全绿）。
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/platform/smart-query-model.ts', import.meta.url)),
  'utf8',
);
const WORKSPACE = readFileSync(
  fileURLToPath(new URL('../../../components/platform/smart-query-workspace.tsx', import.meta.url)),
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
 * 从前端源码里反向抽出**真实存在**的调用点。
 *
 * <p>模板串要先按 base 展开再匹配 —— 只认字面量会把调用点全部漏成零条，
 * 然后「一个都没断言」也算通过，那正是这类守卫最典型的失效方式。
 */
function realCallSites(): string[] {
  const base = 'platform/smart-query';
  const found = new Set<string>();
  for (const source of [SOURCE, WORKSPACE]) {
    for (const match of source.matchAll(/`([^`]*)`/g)) {
      const raw = match[1];
      if (!raw || !raw.includes('${base}')) continue;
      const cleaned = raw
        .replace(/\$\{base\}/g, base)
        // 其余插值（queryId / 查询串）换成 uuid。
        .replace(/\$\{[^}]*\}/g, ID)
        .replace(/\?.*$/, '');
      if (cleaned.includes('${')) continue;
      found.add(cleaned);
    }
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
  expect(sites).toEqual(
    expect.arrayContaining([
      'platform/smart-query/capabilities',
      'platform/smart-query/semantic-layer',
      'platform/smart-query/query',
      'platform/smart-query/queries',
      `platform/smart-query/queries/${ID}/save`,
      `platform/smart-query/queries/${ID}/run`,
    ]),
  );
});

it('后端真实实现的每个端点都被放行 —— 白名单不许漏登记后端已有的形状', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // 🔴 这份清单必须与 SmartQueryController 的映射**逐条对应**：
  //   后端加了端点而白名单没跟上 ⇒ 浏览器里静默 404。
  for (const path of [
    'platform/smart-query/capabilities',
    'platform/smart-query/semantic-layer',
    'platform/smart-query/metrics',
    'platform/smart-query/dimensions',
    'platform/smart-query/query',
    'platform/smart-query/queries',
    `platform/smart-query/queries/${ID}`,
    `platform/smart-query/queries/${ID}/save`,
    `platform/smart-query/queries/${ID}/run`,
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 未被放行`).toBe(200);
  }
});

it('写动作的 POST / DELETE 形状被放行，且真的转发到 8089', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const ask = await POST(request('platform/smart-query/query', 'POST'), {
    params: Promise.resolve({ path: ['platform', 'smart-query', 'query'] }),
  });
  expect(ask.status, 'POST platform/smart-query/query 未被放行').toBe(200);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8089/platform/smart-query/query'),
    expect.objectContaining({ method: 'POST' }),
  );

  const save = await POST(request(`platform/smart-query/queries/${ID}/save`, 'POST'), {
    params: Promise.resolve({ path: ['platform', 'smart-query', 'queries', ID, 'save'] }),
  });
  expect(save.status, 'POST platform/smart-query/queries/{uuid}/save 未被放行').toBe(200);
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 裸路径：后端**没有**汇总端点。
    'platform/smart-query',
    // 未实现的子路径。
    'platform/smart-query/unknown',
    // 🔴 **没有自由 SQL 入口**：蓝图加粗句「自然语言只映射到受控语义层指标，
    //   不生成任意 SQL」—— 放行一条「自己下 SQL」的路径等于把这条加粗句拆掉。
    'platform/smart-query/sql',
    // 后端没有「执行任意查询」的端点。
    'platform/smart-query/execute',
    // 非 UUID 段不应被 uuid 分支吃掉。
    'platform/smart-query/queries/not-a-uuid',
    // 台账是**只追加**的，删不了。
    `platform/smart-query/queries/${ID}/delete`,
    // 后端没有导出端点。
    `platform/smart-query/queries/${ID}/export`,
    // 多一段仍然 404。
    `platform/smart-query/queries/${ID}/extra`,
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

  // 🔴 都是 `prefix: 'platform/'`：必须靠**正则不相交**分开，
  //   而不是靠「谁先匹配上」。这里逐条覆盖**每一条** platform/ 条目 ——
  //   少钉一条，下一次并入取错一侧时就会静默删掉那个模块的路由。
  for (const path of [
    // 18.3.1.1 规则列表
    'platform/automation-rules/capabilities',
    // 18.3.1.2 规则编辑器
    'platform/automation-rule-editor/capabilities',
    // 18.3.2.1 执行日志
    'platform/automation/runs/capabilities',
    // 18.3.2.2 规则效果
    'platform/automation/effect/capabilities',
    // 18.3.3.1 自动审批
    'platform/auto-approvals/capabilities',
    // 18.1.3.2 数据迁移
    'platform/data-migration/capabilities',
    // 18.1.2.1 Webhook
    'platform/webhooks/capabilities',
    // 18.1.2.2 投递记录
    'platform/deliveries/capabilities',
    // 18.1.3.1 导入导出
    'platform/io/capabilities',
    // 18.4.2.2 选品与需求预测
    'platform/ai/forecast/capabilities',
    // 18.5.1.1 模板管理
    'platform/ai/templates/capabilities',
    // 18.4.3.1 使用与配额
    'platform/ai/usage/capabilities',
    // 18.4.3.2 效果分析
    'platform/ai/effects/capabilities',
    // 18.4.2.3 异常诊断
    'platform/ai/diagnosis/capabilities',
    // 18.4.2.1 文本与翻译
    'platform/text/capabilities',
    // 通知
    'platform/notifications/capabilities',
    // 18.1.1.2 应用与凭证
    'platform/apps/capabilities',
    // 18.2.1.1 财务软件对接
    'platform/finance-software/capabilities',
    // 18.1.4.1 API 目录
    'platform/api-catalog/capabilities',
    // 18.1.5.1 调用监控
    'platform/call-monitor/capabilities',
    // 18.2.2.1 支付与资金
    'platform/payment-funds/capabilities',
    // 18.2.3.1 外部系统对接
    'platform/external-integrations/capabilities',
    // 18.4.1.1 对话助手（本模块的上游紧邻车道）
    'platform/assistant/capabilities',
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 被本车道的改动误伤了`).toBe(200);
  }
});
