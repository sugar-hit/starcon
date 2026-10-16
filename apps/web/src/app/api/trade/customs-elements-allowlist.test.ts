import { afterEach, describe, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST, PUT } from './[...path]/route';

/**
 * 10.5.1.2 申报要素的 api-proxy 白名单**双向**断言。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest 全绿。所以这里逐条钉住，
 * 而且判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发），
 * 不是眼看正则、也不是自己再写一份正则去比。
 *
 * <p><b>本车道在 trade 既有组旁边新起一组</b>：本模块新增
 * {@code logistics/customs-elements}，<b>没有并进</b> waybills / waybill-applications /
 * fulfillment 任何一组 —— 「申报要素齐不齐」与「这票货报出去了没有」是两种事实，
 * 混在一组里权限与日志分不开。所以除了「我的形状被放行」，
 * 还必须钉住那些组仍然只放行它们自己的形状（这个文件历史上是冲突高发点）。
 */

const SOURCE = readFileSync(
  fileURLToPath(new URL('../../../components/logistics/customs-elements-model.ts', import.meta.url)),
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

function call(path: string, method: 'GET' | 'POST' | 'PUT' = 'GET') {
  const ctx = { params: Promise.resolve({ path: path.split('/') }) };
  if (method === 'POST') return POST(request(path, 'POST'), ctx);
  if (method === 'PUT') return PUT(request(path, 'PUT'), ctx);
  return GET(request(path), ctx);
}

function stubFetch() {
  const spy = vi.fn(async () => new Response('{}'));
  vi.stubGlobal('fetch', spy);
  return spy;
}

/**
 * 从模型源码里反向抽出**真实存在**的调用点。
 *
 * <p>只写死一份清单会验证「清单与白名单一致」，而真正会出事的是
 * 「源码加了新形状、白名单忘了登记」—— 所以这里从源码反向抽。
 */
function realCallSites(): string[] {
  const base = SOURCE.match(/const base = '([^']+)'/)?.[1] ?? 'trade/logistics/customs-elements';
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

describe('10.5.1.2 申报要素 api-proxy 白名单', () => {
  it('模型源码里真实存在的每个调用点都被放行', async () => {
    stubFetch();
    const sites = realCallSites();
    expect(sites.length, '模型源码里一个调用点都没抽到 —— 断言本身失效了').toBeGreaterThan(0);
    for (const path of sites) {
      const method = path.endsWith('customs-elements') ? 'POST' : 'GET';
      const response = await call(path, method);
      expect(response.status, `${path} 未被放行（白名单漏登记 = 浏览器里静默 404）`).toBe(200);
    }
    expect(sites).toEqual(
      expect.arrayContaining([
        'trade/logistics/customs-elements/capabilities',
        'trade/logistics/customs-elements/filter-options',
        'trade/logistics/customs-elements/list',
        'trade/logistics/customs-elements/upstream-preview',
      ]),
    );
  });

  it('写形状用 PUT / POST 且同样被放行', async () => {
    stubFetch();
    const base = 'trade/logistics/customs-elements';
    for (const [path, method] of [
      [`${base}/${ID}`, 'PUT'],
      [`${base}/${ID}/publish`, 'POST'],
      [`${base}/${ID}/close`, 'POST'],
      [base, 'POST'],
    ] as const) {
      const response = await call(path, method);
      expect(response.status, `${method} ${path}`).toBe(200);
    }
  });

  it('尾斜杠那一支不放行：后端没有那个映射', async () => {
    stubFetch();
    // @PostMapping 刻意用无值写法，只匹配不带尾斜杠的基路径。
    // 放行尾斜杠会让界面以为有一条等价的建集路径，而它其实 404。
    expect((await call('trade/logistics/customs-elements', 'POST')).status).toBe(200);
    expect((await call('trade/logistics/customs-elements/', 'POST')).status).toBe(404);
  });

  it('未登记的形状被拒：拼错 / 后端没有的写形状 / 与 10.5.1.1 混组', async () => {
    stubFetch();
    const base = 'trade/logistics/customs-elements';
    for (const bad of [
      'trade/logistics/customs-element/list', // 单数拼错
      `${base}/${ID}/retract`, // 后端没有「撤回发布」（已发布不可逆）
      `${base}/${ID}/edit`, // 已发布不可原地改，没有这个写形状
      'trade/logistics/customs/list', // 与 10.5.1.1 报关单那一组混了
    ]) {
      const response = await call(bad);
      expect(response.status, `${bad} 不该被放行`).toBe(404);
    }
  });

  it('既有各组仍然只放行它们自己的形状（历史冲突高发点）', async () => {
    stubFetch();
    for (const keep of [
      'trade/logistics/waybills/list',
      'trade/logistics/waybills/capabilities',
      'trade/logistics/waybill-applications/requests',
      'trade/fulfillment/pending-shipment/units',
    ]) {
      const response = await call(keep);
      expect(response.status, `GET ${keep}`).toBe(200);
    }
  });
});
