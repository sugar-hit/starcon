import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { DELETE, GET, POST, PUT } from './[...path]/route';

/**
 * 9.3.3.2 履约流程监控的 api-proxy 白名单**双向**断言。
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
 *   <li><b>拒绝</b>：未交付的形状仍然 404。放行不该存在的路径比 404 更糟 ——
 *       它会让界面以为「已经可用」，比如把释放登记当成「货已释放」。</li>
 * </ul>
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const SOURCE = readFileSync(
  fileURLToPath(
    new URL('../../../components/orders/fulfillment-monitor-model.ts', import.meta.url),
  ),
  'utf8',
);

const ID = '11111111-1111-1111-1111-111111111111';
const OTHER = '22222222-2222-2222-2222-222222222222';

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
 * {@code trade/fulfillment/monitor}），所以抽取必须先把模板串**按 base 展开**
 * 再匹配 —— 只认字面量会把全部调用点漏成一条，然后「放行了三个」也算通过。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/fulfillment/monitor';
  const found = new Set<string>();
  for (const match of SOURCE.matchAll(/`([^`]*)`/g)) {
    const raw = match[1];
    if (!raw || !raw.includes('${base}')) continue;
    const cleaned = raw
      .replace(/\$\{base\}/g, base)
      .replace(/\$\{query\([^)]*\)\}/g, '')
      .replace(/\$\{(caseId|requestId|todoId|dispositionId|releaseId|input)\}/g, ID)
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
      'trade/fulfillment/monitor/capabilities',
      'trade/fulfillment/monitor/cases',
      `trade/fulfillment/monitor/cases/${ID}`,
      'trade/fulfillment/monitor/scan',
      `trade/fulfillment/monitor/cases/${ID}/verify`,
      `trade/fulfillment/monitor/cases/${ID}/disposition`,
      `trade/fulfillment/monitor/cases/${ID}/close`,
      `trade/fulfillment/monitor/cases/${ID}/release/${ID}/ack`,
      `trade/fulfillment/monitor/cases/${ID}/todos`,
      'trade/fulfillment/monitor/thresholds',
      'trade/fulfillment/monitor/todos',
      `trade/fulfillment/monitor/todos/${ID}/action`,
      'trade/fulfillment/monitor/events',
    ]),
  );
});

it('写形状（POST）与阈值 PUT 被放行，且真的转发到 8085', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'trade/fulfillment/monitor/scan',
    `trade/fulfillment/monitor/cases/${ID}/verify`,
    `trade/fulfillment/monitor/cases/${ID}/disposition`,
    `trade/fulfillment/monitor/cases/${ID}/close`,
    `trade/fulfillment/monitor/cases/${ID}/todos`,
    `trade/fulfillment/monitor/todos/${ID}/action`,
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  const put = await PUT(request('trade/fulfillment/monitor/thresholds', 'PUT'), {
    params: Promise.resolve({ path: 'trade/fulfillment/monitor/thresholds'.split('/') }),
  });
  expect(put.status, 'PUT thresholds 未被放行').toBe(200);

  await expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/fulfillment/monitor'),
    expect.objectContaining({ method: 'PUT' }),
  );
});

it('处置状态推进与释放回执这两个多段形状也被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    `trade/fulfillment/monitor/cases/${ID}/disposition/${OTHER}/status`,
    `trade/fulfillment/monitor/cases/${ID}/release/${OTHER}/ack`,
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 未被放行`).toBe(200);
  }
});

it('未交付的形状仍然被拒 —— 放行不该存在的路径比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    // 裸路径：后端没有对应映射（@RequestMapping 下没有裸 GET）。
    'trade/fulfillment/monitor',
    'trade/fulfillment/monitor/',
    // 字面量子路径之下多接一段。
    'trade/fulfillment/monitor/capabilities/extra',
    'trade/fulfillment/monitor/scan/extra',
    'trade/fulfillment/monitor/thresholds/extra',
    'trade/fulfillment/monitor/events/extra',
    'trade/fulfillment/monitor/todos/extra',
    // 非 UUID 段不应被 uuid 分支吃掉。
    'trade/fulfillment/monitor/cases/not-a-uuid',
    'trade/fulfillment/monitor/todos/not-a-uuid/action',
    // 案例下的动作段只放行本模块真实存在的那些。
    `trade/fulfillment/monitor/cases/${ID}/retry`,
    `trade/fulfillment/monitor/cases/${ID}/cancel`,
    `trade/fulfillment/monitor/cases/${ID}/advance`,
    `trade/fulfillment/monitor/cases/${ID}/release`,
    `trade/fulfillment/monitor/cases/${ID}/release/${OTHER}`,
    `trade/fulfillment/monitor/cases/${ID}/exceptions`,
    `trade/fulfillment/monitor/cases/${ID}/verify/extra`,
    `trade/fulfillment/monitor/cases/${ID}/disposition/${OTHER}/status/extra`,
    // 动作段位置错了（disposition 在 release 之后）。
    `trade/fulfillment/monitor/cases/${ID}/release/${OTHER}/disposition/${OTHER}/status`,
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 不应被放行`).toBe(404);
  }
  expect(fetcher, '被拒的路径不得触发任何上游调用').not.toHaveBeenCalled();
});

it('本模块的路径不会被 9.3.3.1 那条正则误吃', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  // 两组前缀并列（monitor vs requests），放错顺序会让其中一组被另一组的代理吃掉。
  const response = await GET(request('trade/fulfillment/monitor/cases'), {
    params: Promise.resolve({ path: 'trade/fulfillment/monitor/cases'.split('/') }),
  });
  expect(response.status).toBe(200);
  await expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8085/trade/fulfillment/monitor'),
    expect.anything(),
  );
});

/**
 * DELETE 的真实拦截点不在白名单，而在**后端**。
 *
 * <p>白名单是**按 path 判别、不看 HTTP 方法**的，所以 DELETE 打到已放行形状仍会被转发。
 * 真正的拦截在后端：控制器里没有任何 DELETE 映射，且 V154 第 10 节把
 * UPDATE/DELETE 从 {@code trade_app} 收回 —— 留痕表（verification / event）
 * 连特权连接都删不掉。
 */
it('后端控制器里没有任何 @DeleteMapping：凭据不可删', () => {
  const controller = readFileSync(
    fileURLToPath(
      new URL(
        '../../../../../../backend/services/trade/src/main/java/com/starcon/trade/FulfillmentMonitorController.java',
        import.meta.url,
      ),
    ),
    'utf8',
  );
  expect(
    controller.includes('@DeleteMapping'),
    '查证记录与事件流是「先查证再重试」的举证凭据，控制器不得提供任何 DELETE 入口',
  ).toBe(false);
});

it('V154 迁移里必须真的把留痕表的 UPDATE/DELETE 收回', () => {
  const migration = readFileSync(
    fileURLToPath(
      new URL(
        '../../../../../../backend/services/trade/src/main/resources/db/migration/V154__trade_fulfillment_monitor.sql',
        import.meta.url,
      ),
    ),
    'utf8',
  );
  // 授权在前、收回在后：REVOKE 写在 GRANT 之前会被全量覆盖回去。
  expect(migration).toContain('GRANT SELECT,INSERT,UPDATE,DELETE ON');
  // 断言落在**真实的多行片段**上而不是一条正则：两张留痕表写在同一条 REVOKE 的
  // 多行列清单里，写正则时很容易只匹配到第一行就以为过了。
  expect(migration).toContain(
    'REVOKE UPDATE,DELETE ON\n  fulfillment_monitor_verification,\n  fulfillment_monitor_event FROM trade_app;',
  );
  // 当前状态表也不许直接改：推进只经 SECURITY DEFINER 窄函数。
  expect(migration).toContain(
    'REVOKE UPDATE,DELETE ON\n  fulfillment_monitor_case,\n  fulfillment_monitor_disposition,',
  );
  // 七张表一视同仁 ENABLE **且 FORCE** RLS：FORCE 写在 DO 块的 format() 里，
  // 源码里只出现一次，所以要断言 DO 块那张表清单真的列全了七张。
  expect(migration).toContain('FORCE ROW LEVEL SECURITY');
  for (const table of [
    'fulfillment_monitor_threshold',
    'fulfillment_monitor_case',
    'fulfillment_monitor_verification',
    'fulfillment_monitor_disposition',
    'fulfillment_monitor_release',
    'fulfillment_monitor_todo',
    'fulfillment_monitor_event',
  ]) {
    expect(migration, `RLS 的表清单漏了 ${table}`).toContain(`'${table}'`);
  }
  // 加粗句的三道闸门在迁移里必须真的存在。
  expect(migration).toContain('guard_fulfillment_monitor_retry_verified');
  expect(migration).toContain('guard_fulfillment_monitor_disposition_case_ready');
  expect(migration).toContain('fulfillment_monitor_retry_once_uq');
});

it('DELETE 打到已放行形状时仍会转发 —— 白名单不看方法，如实说明而不是假装拦住了', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const response = await DELETE(request('trade/fulfillment/monitor/cases', 'DELETE'), {
    params: Promise.resolve({ path: 'trade/fulfillment/monitor/cases'.split('/') }),
  });
  // 转发 = 上游被调用；后端没有 DELETE 映射，所以真实环境会拿到 405/404。
  // 这里断言的是「确实转发了」，避免留下「白名单挡住了 DELETE」这种漂亮但不成立的话。
  expect(fetcher).toHaveBeenCalled();
  expect([200, 404, 405, 502, 503]).toContain(response.status);
});
