import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { proxy } from '../../../lib/api-proxy';

/**
 * 14.2.1.2 分时策略 的 api-proxy 白名单**双向**断言。
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
 *       的 是「后端加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：未交付 / 不该存在的形状仍然 404。放行不该存在的路径比 404 更糟 ——
 *       它会让界面以为「已经可用」。</li>
 * </ul>
 *
 * <p><b>不误伤其他车道已登记的分支</b>：白名单是多车道共用的**累积型**文件。
 * 本车道只**新增一条** `ad-dayparts/` 元素，既有的 `ad-campaigns/`（14.1.1.1）、
 * `ad-rules/`（14.2.2.2）、`ad-diagnostics/`（14.3.2.2）、`ad-budgets/`（14.2.2.1）
 * 一字未动 —— 但那必须被断言钉住，否则下一次并入取错一侧时会静默删掉已交付模块的路由。
 */

const SPEC = JSON.parse(
  readFileSync(
    fileURLToPath(
      new URL('../../../../../../packages/api-contracts/src/channel.openapi.json', import.meta.url),
    ),
    'utf8',
  ),
) as { paths: Record<string, Record<string, unknown>> };

const ID = '11111111-1111-1111-1111-111111111111';
/** 本模块在契约里的 path 前缀。 */
const MODULE = '/channel/ad-dayparts';

afterEach(() => vi.unstubAllGlobals());

/**
 * 从**本模块自己生成的契约**里抽出真实存在的 path。
 *
 * <p>⚠️ 刻意**不削掉** `/channel/` 前缀：`proxy()` 是拿 `params.path.join('/')`
 * 去比对 `services[].prefix` 的，prefix 就是 `channel/`。削掉前缀会让每一条都 404，
 * 症状是「白名单明明登记了却全被拒」，很容易被误判成正则写错。
 */
function realCallSites(): string[] {
  const found = new Set<string>();
  for (const path of Object.keys(SPEC.paths)) {
    if (!path.startsWith(MODULE)) continue;
    // 只削**开头那一个斜杠**；`channel/` 这段服务前缀必须保留。
    const resolved = path.replace(/^\//, '').replace('{id}', ID).replace('{shopCode}', 'US-EAST');
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
      'channel/ad-dayparts/capabilities',
      // 🔴 店铺与时区：漏登记它，页面就看不出「这个店铺定不了时区」
      'channel/ad-dayparts/shops',
      'channel/ad-dayparts/shops/US-EAST/timezone',
      // 🔴 策略增删改查
      'channel/ad-dayparts/strategies',
      'channel/ad-dayparts/strategies/' + ID,
      'channel/ad-dayparts/strategies/' + ID + '/curve',
      'channel/ad-dayparts/strategies/' + ID + '/status',
      // 🔴 推荐曲线（上游 13.2.1.2）
      'channel/ad-dayparts/recommendations',
      'channel/ad-dayparts/recommendations/' + ID,
      'channel/ad-dayparts/recommendations/' + ID + '/apply',
      // 🔴 定时调整任务：漏登记「运行入口」，外部调度根本调不动
      'channel/ad-dayparts/tasks',
      'channel/ad-dayparts/tasks/run',
      'channel/ad-dayparts/tasks/' + ID,
      // 🔴 给下游 14.2.2.2 自动规则取用的读取形状
      'channel/ad-dayparts/caps',
    ]),
  );
});

it('写动作真的转发到 8083（不是只判了正则没转发）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'channel/ad-dayparts/strategies',
    'channel/ad-dayparts/strategies/' + ID + '/curve',
    'channel/ad-dayparts/strategies/' + ID + '/status',
    'channel/ad-dayparts/recommendations',
    'channel/ad-dayparts/recommendations/' + ID + '/apply',
    'channel/ad-dayparts/tasks/run',
  ]) {
    const response = await callProxy(path, {
      method: 'POST',
      headers: {
        origin: 'http://localhost:3000',
        cookie: 'starcon_session=private-session',
        'X-Tenant-Id': 'forged',
        'X-Internal-Key': 'forged',
      },
      body: '{}',
    });
    expect(response.status, `POST ${path} 未被放行`).not.toBe(404);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8083/channel/ad-dayparts'),
    expect.objectContaining({ method: 'POST' }),
  );
  // 伪造的租户头不得被转发到上游 —— 租户只来自已认证成员关系（AGENTS.md）
  const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
  expect(new Headers(init.headers).get('X-Tenant-Id')).toBeNull();
});

it('未交付 / 不该存在的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const base = 'channel/ad-dayparts';
  for (const path of [
    // 裸路径刻意不放行：本模块没有裸集合读，一切入口都带子路径
    base,
    // 策略是配置事实，撤下走 status=ARCHIVED，不物理删除
    `${base}/strategies/${ID}/delete`,
    // 任务状态由服务端推进，页面没有「取消任务」这个动作
    `${base}/tasks/${ID}/cancel`,
    // 变更预览属于 14.1.1.3 批量操作（未交付）
    `${base}/recommendations/${ID}/preview`,
    // 🔴 按星期分曲线属 14.2.1.3（未交付）：本模块的曲线是**按小时的**
    `${base}/weekdays`,
    // 🔴 平台回写队列未交付：放行它等于让界面以为「已经回写平台」
    `${base}/platform-sync`,
    // 字面量子路径刻意不接 id 段
    `${base}/capabilities/extra`,
    `${base}/shops/US-EAST/timezone/extra`,
    // 伪造的 uuid 形状必须被拒：短前缀与多一位都不是 uuid
    `${base}/strategies/not-a-uuid`,
    `${base}/strategies/11111111-1111-1111-1111-1111111111111`,
    // 店铺编码段不是 uuid 段：不能被 uuid 分支吃掉，也不能放行空段
    `${base}/shops//timezone`,
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 不该被放行`).toBe(404);
  }
});

it('不误伤其他车道已登记的分支：既有 channel 路由仍然放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 14.1.1.1 广告活动列表 / 14.2.2.2 自动规则 / 14.2.2.1 预算分配（同卷，**紧邻的命名空间**）
    'channel/ad-campaigns/capabilities',
    'channel/ad-campaigns/campaigns',
    'channel/ad-rules/capabilities',
    'channel/ad-rules/rules',
    'channel/ad-budgets/capabilities',
    // 14.3.2.2 效率诊断（**首 token 与本模块只差一个连字符，必须不被吃掉**）
    'channel/ad-diagnostics/capabilities',
    'channel/ad-diagnostics/diagnoses',
    // 14.1.3.1 关键词管理 / 14.1.3.2 投放定位
    'channel/ad-keywords/capabilities',
    'channel/ad-targetings/capabilities',
    // 10.4.2.2 备货预报 / 16.2.2.1 合规预检
    'channel/stock-forecasts/capabilities',
    'channel/compliance-precheck/capabilities',
    // 11.4.2.1 平台纠纷（trade 侧）
    'trade/platform-disputes',
  ]) {
    const response = await callProxy(path, {
      headers: { cookie: 'starcon_session=private-session' },
    });
    expect(response.status, `${path} 被本车道误伤`).not.toBe(404);
  }
});

it('本车道没有把 ad-dayparts 放到别的服务前缀下', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  // 分时策略落在 channel 服务，不在 trade / identity 上开第二条映射
  const response = await callProxy('trade/ad-dayparts', {
    headers: { cookie: 'starcon_session=private-session' },
  });
  expect(response.status).toBe(404);
});
