import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { GET, POST } from './[...path]/route';

/**
 * 5.1.2.1 优先级与冲突检测的 api-proxy 白名单**双向**断言
 * （手法与同目录其余 `*-allowlist.test.ts` 同一套）。
 *
 * <p>白名单只在浏览器端生效：`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest 全绿。
 * 所以这里逐条钉住，判据是**真跑 proxy 断言 HTTP 状态码**（拦截 = 404，放行 = 转发）。
 *
 * <p>两个方向都要断言：
 *
 * <ul>
 *   <li><b>放行</b>：从本模块模型与工作区源码里<b>反向抽取真实调用点</b>再逐条断言被放行 ——
 *       写死一份清单只会验证「清单与白名单一致」，而真正会出事的是
 *       「源码加了新形状、白名单忘了登记」。</li>
 *   <li><b>拒绝</b>：刻意不放行的形状仍然 404。价目项（`price_list_item`）是
 *       **5.1.1.2 的写入所有权**，**5.1.1.2 已交付**（2026-11-02）—— 它自己的
 *       `services[]` 元素放行 `…/items`；本模块不实现价目项，也不重复放行 ⇒
 *       负向样本只留**确实不存在**的形状（`…/items/batch`）。
 *       `…/items` 本身由**正向**断言钉住（必须被放行）—— 断言它 404 是过期断言。</li>
 * </ul>
 */

// 相对**本文件**定位：vitest 从仓库根或 apps/web 跑时 cwd 都不同，用 cwd 会静默读空。
const MODEL_SOURCE = readFileSync(
  fileURLToPath(
    new URL('../../../components/pricing/price-list-priority-model.ts', import.meta.url),
  ),
  'utf8',
);
const WORKSPACE_SOURCE = readFileSync(
  fileURLToPath(
    new URL('../../../components/pricing/price-list-priority-workspace.tsx', import.meta.url),
  ),
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

/** 反向抽取模型与工作区源码里真实存在的调用点（query 尾巴剥掉）。 */
function realCallSites(): string[] {
  const found = new Set<string>();
  for (const source of [MODEL_SOURCE, WORKSPACE_SOURCE]) {
    for (const match of source.matchAll(/'(catalog\/price-lists[^']*)'/g)) {
      const literal = match[1];
      if (literal) found.add(literal.replace(/\?.*$/, ''));
    }
    for (const match of source.matchAll(/`(catalog\/price-lists[^`]*)`/g)) {
      const template = match[1];
      if (template) {
        found.add(template.replace(/\$\{[^}]*\}/g, ID).replace(/\?.*$/, ''));
      }
    }
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
      'catalog/price-lists/priority/capabilities',
      'catalog/price-lists/priority/rules',
      `catalog/price-lists/lists/${ID}/priority`,
      `catalog/price-lists/lists/${ID}/conflicts`,
      'catalog/price-lists/conflicts',
      'catalog/price-lists/resolve',
    ]),
  );
});

it('写形状（POST）被放行：保存优先级与解析', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [`catalog/price-lists/lists/${ID}/priority`, 'catalog/price-lists/resolve']) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `POST ${path} 未被放行`).toBe(200);
  }
  expect(fetcher).toHaveBeenCalledWith(
    expect.stringContaining(':8082/catalog/price-lists/lists/'),
    expect.objectContaining({ method: 'POST' }),
  );
});

it('刻意不放行的形状仍然被拒 —— 放行后端不存在的映射比 404 更糟', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    // 🔴 价目项（price_list_item）是 **5.1.1.2 的写入所有权**，**5.1.1.2 已交付**：
    //   它自己的 `services[]` 元素放行 `…/items` ⇒ 那条形状**不是**「刻意不放行」的，
    //   本模块不实现价目项、也不重复放行它。
    //   `…/items/batch` 这个形状**确实不存在**（5.1.1.2 的批量形状叫 preview / apply）⇒ 仍应 404。
    `catalog/price-lists/lists/${ID}/items/batch`,
    // 非 UUID 形状不该被 uuid 分支吃掉。
    `catalog/price-lists/lists/not-a-uuid/priority`,
    // 本模块没有裸的 priority 资源（只有 capabilities / rules 两条子路径）。
    'catalog/price-lists/priority',
    // 多一段仍然 404。
    `catalog/price-lists/lists/${ID}/priority/extra`,
    'catalog/price-lists/conflicts/extra',
    'catalog/price-lists/resolve/extra',
  ]) {
    const response = await POST(request(path, 'POST'), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 本该被拒`).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

it('5.1.1.1 自己的形状仍然归它自己放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  for (const path of [
    'catalog/price-lists/capabilities',
    'catalog/price-lists/scope-directory',
    'catalog/price-lists/coverage',
    'catalog/price-lists/lists',
    `catalog/price-lists/lists/${ID}`,
  ]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 未被放行（5.1.1.1 的形状被本车道挤掉了）`).toBe(200);
  }
});

it('🔴 兄弟车道（5.1.1.2）已交付的价目项形状仍然被放行 —— 断言它 404 是过期断言', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);

  // 🔴 `price-lists/lists/{uuid}/items` 是 **5.1.1.2 价目项批量维护**的形状，**已交付**
  //   （2026-11-02）—— 由 5.1.1.2 自己那条独立的 `services[]` 元素放行。
  //   本模块（5.1.2.1）不实现价目项、也不重复放行它；但「它已被放行」这件事必须被断言钉住 ——
  //   batch-134 L2 的假红正是「断言它 404」这条**过期断言**造成的（实测 503/200，不是 404）。
  for (const path of [`catalog/price-lists/lists/${ID}/items`]) {
    const response = await GET(request(path), {
      params: Promise.resolve({ path: path.split('/') }),
    });
    expect(response.status, `${path} 未被放行（5.1.1.2 已交付的形状）`).toBe(200);
  }
});
