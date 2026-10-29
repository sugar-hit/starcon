import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 13.4.3.1 利润预警 的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest / Playwright 全绿。
 * 所以判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比（这两种方法在本仓库已连续骗过多版）。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从**自己生成的 OpenAPI 契约**里反向抽出本模块的真实 path，
 *       再逐条断言被放行 —— 写死一份清单只会验证「清单与白名单一致」，而真正会出事
 *       的是「后端加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：未交付 / 不该存在的形状仍然 404。放行不该存在的路径比 404 更糟 ——
 *       它会让界面以为「利润预警」已经可用。</li>
 * </ul>
 *
 * <p><b>不误伤其他车道已登记的分支</b>：白名单是多车道共用的**累积型**文件。
 * 本车道只**新增一条** `reporting/`（profit-alert）元素，13.1.1.1 的
 * `reporting/overview`、13.4.1.1 的 `reporting/profit-report`、13.4.2.1 的
 * `reporting/cost-structure` 一字未动 —— 但那必须被断言钉住，否则下一次并入取错一侧时
 * 会静默删掉已交付模块的路由。
 */

const SPEC = JSON.parse(
  readFileSync(
    fileURLToPath(
      new URL('../../../../../../packages/api-contracts/src/reporting.openapi.json', import.meta.url),
    ),
    'utf8',
  ),
) as { paths: Record<string, Record<string, unknown>> };

const ID = '11111111-1111-1111-1111-111111111111';
/** 本模块在契约里的 path 前缀。 */
const MODULE = '/reporting/profit-alert';
/** 路径参数：预警类型（规则只认五个）。 */
const ALERT_TYPE = 'SKU_NEGATIVE_GROSS_PROFIT';

afterEach(() => vi.unstubAllGlobals());

/**
 * 从**本模块自己生成的契约**里抽出真实存在的 path。
 *
 * <p>⚠️ 刻意**不削掉** `/reporting/` 前缀：`proxy()` 是拿 `params.path.join('/')`
 * 去比对 `services[].prefix` 的，prefix 就是 `reporting/`。削掉前缀会让每一条都 404，
 * 症状是「白名单明明登记了却全被拒」，很容易被误判成正则写错。
 */
function realCallSites(): string[] {
  const found = new Set<string>();
  for (const path of Object.keys(SPEC.paths)) {
    if (!path.startsWith(MODULE)) continue;
    // 只削**开头那一个斜杠**；`reporting/` 这段服务前缀必须保留。
    const resolved = path
      .replace(/^\//, '')
      .replace('{alertType}', ALERT_TYPE)
      .replace('{id}', ID);
    if (resolved.includes('{')) continue;
    found.add(resolved);
  }
  return [...found].sort();
}

function callProxy(fullPath: string, init?: ConstructorParameters<typeof NextRequest>[1]) {
  return proxy(new NextRequest(`http://localhost:3000/api/${fullPath}`, init), {
    params: Promise.resolve({ path: fullPath.split('/') }),
  } as never);
}

it('契约里本模块的每个真实 path 都被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const sites = realCallSites();
  expect(sites.length, '契约里一个本模块 path 都没抽到 —— 断言本身失效了').toBeGreaterThan(0);
  for (const path of sites) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 未被放行（白名单漏登记 = 浏览器里静默 404）`).not.toBe(404);
  }

  // 抽到的路径必须**真的**覆盖本模块的形状，否则「只放行了一条」也算通过。
  expect(sites).toEqual(
    expect.arrayContaining([
      'reporting/profit-alert/capabilities',
      // 🔴 预警清单：漏登记它，页面永远读不到预警
      'reporting/profit-alert/alerts',
      'reporting/profit-alert/rules',
      'reporting/profit-alert/rules/SKU_NEGATIVE_GROSS_PROFIT',
      'reporting/profit-alert/notifications',
      'reporting/profit-alert/notifications/11111111-1111-1111-1111-111111111111',
    ]),
  );
});

it('写动作真的转发到 8088（不是只判了正则没转发）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const response = await callProxy('reporting/profit-alert/notifications', {
    method: 'POST',
    headers: {
      origin: 'http://localhost:3000',
      cookie: 'starcon_session=private-session',
      'X-Tenant-Id': 'forged',
      'X-Internal-Key': 'forged',
    },
    body: '{}',
  });
  expect(response.status, 'POST notifications 未被放行').not.toBe(404);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8088/reporting/profit-alert'),
    expect.objectContaining({ method: 'POST' }),
  );
  // 伪造的租户头不得被转发到上游 —— 租户只来自已认证成员关系（AGENTS.md）
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  expect(new Headers(init.headers).get('X-Tenant-Id')).toBeNull();
});

it('改阈值的写动作也被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const response = await callProxy(`reporting/profit-alert/rules/${ALERT_TYPE}`, {
    method: 'PUT',
    headers: {
      origin: 'http://localhost:3000',
      cookie: 'starcon_session=private-session',
    },
    body: '{}',
  });
  expect(response.status, 'PUT rules/{alertType} 未被放行').not.toBe(404);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8088/reporting/profit-alert/rules'),
    expect.objectContaining({ method: 'PUT' }),
  );
});

it('处理通知的写动作也被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  const response = await callProxy(`reporting/profit-alert/notifications/${ID}`, {
    method: 'PUT',
    headers: {
      origin: 'http://localhost:3000',
      cookie: 'starcon_session=private-session',
    },
    body: JSON.stringify({ status: 'ACKNOWLEDGED' }),
  });
  expect(response.status, 'PUT notifications/{id} 未被放行').not.toBe(404);
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8088/reporting/profit-alert/notifications/'),
    expect.objectContaining({ method: 'PUT' }),
  );
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const base = 'reporting/profit-alert';
  for (const path of [
    // 裸路径刻意不放行：本模块没有裸集合读，一切入口都带子路径
    base,
    // 预警清单是按查询参数定位的，顶层没有 id 段
    `${base}/${ID}`,
    // 多一层仍然 404
    `${base}/capabilities/extra`,
    `${base}/alerts/extra`,
    // 非本模块的子路径
    `${base}/not-a-shape`,
    // 🔴 规则只认五个预警类型，别的值不放行（后端会 400，但白名单也不该放行）
    `${base}/rules/NOPE`,
    // 🔴 本模块只读 13.4.1.1 的利润投影，不另建第二个投影管道
    `${base}/refresh`,
    // 🔴 利润报表是 13.4.1.1（/reporting/profit-report/**），不是本模块
    `${base}/report`,
    // 🔴 利润下钻是 13.4.1.1 的端点
    `${base}/drilldown`,
    // 🔴 趋势是 13.4.2.1 成本结构的端点
    `${base}/trend`,
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 不该被放行`).toBe(404);
  }
});

it('不误伤其他车道已登记的分支：13.1.1.1 / 13.4.1.1 / 13.4.2.1 的 reporting 路由仍然放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 13.1.1.1 经营总览
    'reporting/overview',
    'reporting/overview/capabilities',
    'reporting/overview/metrics',
    'reporting/overview/trend',
    'reporting/overview/drilldown',
    // 13.1.1.1 投影管道
    'reporting/projection/events',
    'reporting/projection/refresh',
    // 13.4.1.1 多模式利润报表
    'reporting/profit-report/report',
    'reporting/profit-report/capabilities',
    'reporting/profit-report/drilldown',
    // 13.4.2.1 成本结构
    'reporting/cost-structure/structure',
    'reporting/cost-structure/anomalies',
    'reporting/cost-structure/rules/AD_FEE',
    // 13.3.2.2 关联营销效果
    'reporting/affiliate-marketing/metrics',
    'reporting/affiliate-marketing/refresh',
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 被本车道误伤`).not.toBe(404);
  }
});

it('本车道没有把 profit-alert 放到别的服务前缀下', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  // 利润预警落在 reporting 服务，不在 finance / catalog 上开第二条映射
  for (const path of ['finance/profit-alert', 'catalog/profit-alert']) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status).toBe(404);
  }
});
