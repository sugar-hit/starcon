import { afterEach, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import { DELETE, GET, POST, PUT } from './route';
afterEach(() => vi.unstubAllGlobals());
function request(path: string, method = 'GET', origin = 'http://localhost:3000') {
  return new NextRequest(`http://localhost:3000/api/catalog/${path}`, {
    method,
    headers: {
      origin,
      cookie: 'starcon_catalog_session=private-session',
      'X-Tenant-Id': 'forged',
      'X-Internal-Key': 'forged',
    },
    ...(method === 'GET' ? {} : { body: '{}' }),
  });
}
it('管理 API 与租户切换路由转发 identity，仅传服务端 cookie 身份', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('[]')));
  vi.stubGlobal('fetch', fetcher);
  for (const path of [
    'identity/roles',
    'identity/members',
    'identity/departments',
    'identity/children',
    'identity/permissions',
    'auth/memberships',
  ]) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(200);
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining(`:8081/${path}`),
      expect.objectContaining({
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer private-session' },
      }),
    );
  }
  for (const path of ['auth/switch', 'identity/owner-transfer'])
    expect((await POST(request(path, 'POST'), { params: { path: path.split('/') } })).status).toBe(
      200,
    );
  expect(
    (
      await PUT(request('identity/roles/abcdef', 'PUT'), {
        params: { path: ['identity', 'roles', 'abcdef'] },
      })
    ).status,
  ).toBe(200);
});
it('跨站写入和非白名单接口被拒绝且不发出请求', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  expect(
    (
      await POST(request('identity/children', 'POST', 'https://attacker.example'), {
        params: { path: ['identity', 'children'] },
      })
    ).status,
  ).toBe(403);
  expect(
    (await GET(request('internal/access'), { params: { path: ['internal', 'access'] } })).status,
  ).toBe(404);
  expect(
    (
      await GET(request('identity/children/abc/members'), {
        params: { path: ['identity', 'children', 'abc', 'members'] },
      })
    ).status,
  ).toBe(404);
  expect(fetcher).not.toHaveBeenCalled();
});

/**
 * 3.1.3.1 规格轴：catalog allowlist 必须放行 spec-axis 全路径。
 *
 * <p>背景见 docs/backlog/2026-10/2026-10-07-sku-code-api-proxy-allowlist.md：allowlist 是
 * 锚定正则，落不在分支里的路径一律被拦成 404 —— 页面只进错误态，看起来像「后端没实现」。
 * 现有测试只覆盖了 skus/export，所以 3.1.2.3 交付时漏放行没被发现。这组用例把
 * spec-axis 的每条路由钉住，防止下次再加子路径时静默 404。
 */
it('catalog/spec-axis 的全部路由被放行且转发到 catalog 服务', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('[]')));
  vi.stubGlobal('fetch', fetcher);
  const id = '11111111-1111-1111-1111-111111111111';
  for (const path of [
    'catalog/spec-axis/axes',
    'catalog/spec-axis/overview',
    `catalog/spec-axis/axes/${id}/options`,
    `catalog/spec-axis/spus/${id}`,
    `catalog/spec-axis/spus/${id}/axes`,
    'catalog/spec-axis/category-rules',
  ]) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(200);
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining(`:8082/${path}`),
      expect.objectContaining({
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer private-session' },
      }),
    );
  }
  const unbind = `catalog/spec-axis/spus/${id}/axes/22222222-2222-2222-2222-222222222222`;
  expect(
    (await DELETE(request(unbind, 'DELETE'), { params: { path: unbind.split('/') } })).status,
  ).toBe(200);
});

it('spec-axis 放行不等于放行任意 catalog 子路径', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const id = '11111111-1111-1111-1111-111111111111';
  // allowlist 是精确路径：多一层未声明的子路径仍应 404，否则拼错的路径会被静默转发。
  for (const path of ['catalog/spec-axis/unknown', `catalog/spec-axis/axes/${id}/options/extra`]) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

/**
 * 3.1.3.2 组合唯一性校验：catalog allowlist 必须放行 variant-combination 全路径。
 *
 * <p>同一个坑：allowlist 是锚定正则，没放进分支的路径一律 404，页面只会进错误态，
 * 看起来像"后端没实现"。这里把每条路由钉住。
 */
it('catalog/variant-combination 的全部路由被放行且转发到 catalog 服务', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('[]')));
  vi.stubGlobal('fetch', fetcher);
  const id = '11111111-1111-1111-1111-111111111111';
  const draftId = '22222222-2222-2222-2222-222222222222';
  for (const path of [
    'catalog/variant-combination/overview',
    `catalog/variant-combination/spus/${id}`,
    `catalog/variant-combination/spus/${id}/combinations`,
    `catalog/variant-combination/spus/${id}/drafts`,
    `catalog/variant-combination/spus/${id}/drafts/${draftId}`,
  ]) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(200);
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining(`:8082/${path}`),
      expect.objectContaining({
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer private-session' },
      }),
    );
  }
  for (const path of [
    `catalog/variant-combination/spus/${id}/check`,
    `catalog/variant-combination/spus/${id}/drafts`,
  ]) {
    expect((await POST(request(path), { params: { path: path.split('/') } })).status).toBe(200);
  }
  const discard = `catalog/variant-combination/spus/${id}/drafts/${draftId}`;
  expect(
    (await DELETE(request(discard, 'DELETE'), { params: { path: discard.split('/') } })).status,
  ).toBe(200);
});

it('variant-combination 放行不等于放行任意 catalog 子路径', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const id = '11111111-1111-1111-1111-111111111111';
  for (const path of [
    'catalog/variant-combination/unknown',
    `catalog/variant-combination/spus/${id}/drafts/22222222-2222-2222-2222-222222222222/extra`,
  ]) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

/**
 * 3.2.1.1 属性字典：catalog allowlist 必须放行 attributes 全路径。
 *
 * <p>同一个坑：allowlist 是锚定正则，没放进分支的路径一律 404，页面只会进错误态，
 * 看起来像"后端没实现"。这里把每条路由钉住。
 *
 * <p>特别要防的是 `products/{id}/attributes`（3.2.2.1 的 SPU 属性）与顶层 `attributes`
 * （本模块的租户级字典）被混为一谈：前者第一段是 UUID，后者第一段是字面量 attributes。
 * 少写这一条会让整个字典页只进错误态，而 POST 依然被 405/404 拦掉。
 */
it('catalog/categories 的全部路由被放行且转发到 catalog 服务', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('[]')));
  vi.stubGlobal('fetch', fetcher);
  const id = '11111111-1111-1111-1111-111111111111';
  const tree = '22222222-2222-2222-2222-222222222222';
  for (const path of [
    'catalog/categories/overview',
    'catalog/categories/trees',
    'catalog/categories/trees/22222222-2222-2222-2222-222222222222',
    `catalog/categories/trees/${tree}/nodes`,
    `catalog/categories/nodes/${id}`,
    `catalog/categories/spus/${id}`,
  ]) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(200);
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining(`:8082/${path}`),
      expect.objectContaining({
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer private-session' },
      }),
    );
  }
  // 建树 / 建节点 / 拖拽落点 / 启用停用 / 写 SPU 分类都是写方法。
  for (const path of [
    'catalog/categories/trees',
    `catalog/categories/trees/${tree}/nodes`,
    `catalog/categories/spus/${id}`,
  ]) {
    expect((await POST(request(path, 'POST'), { params: { path: path.split('/') } })).status).toBe(
      200,
    );
  }
  expect(
    (
      await PUT(request(`catalog/categories/trees/${tree}`), {
        params: { path: ['catalog', 'categories', 'trees', tree] },
      })
    ).status,
  ).toBe(200);
  for (const path of [
    `catalog/categories/nodes/${id}`,
    `catalog/categories/nodes/${id}/move`,
    `catalog/categories/nodes/${id}/enabled`,
  ]) {
    expect((await PUT(request(path, 'PUT'), { params: { path: path.split('/') } })).status).toBe(
      200,
    );
  }
  for (const path of [`catalog/categories/trees/${tree}`, `catalog/categories/nodes/${id}`]) {
    expect(
      (await DELETE(request(path, 'DELETE'), { params: { path: path.split('/') } })).status,
    ).toBe(200);
  }
});

it('catalog/brands 的全部路由被放行且转发到 catalog 服务（3.3.2.1 品牌管理）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('[]')));
  vi.stubGlobal('fetch', fetcher);
  const brand = '11111111-1111-1111-1111-111111111111';
  const credential = '22222222-2222-2222-2222-222222222222';
  for (const path of [
    'catalog/brands',
    'catalog/brands/overview',
    'catalog/brands/candidates',
    'catalog/brands/listing-eligibility',
    `catalog/brands/${brand}`,
    `catalog/brands/${brand}/listing-eligibility`,
    `catalog/brands/${brand}/authorizations`,
    `catalog/brands/${brand}/authorizations/${credential}`,
  ]) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(200);
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining(`:8082/${path}`),
      expect.objectContaining({
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer private-session' },
      }),
    );
  }
  // 建品牌 / 登记凭证 / 批量刊登判定是 POST；批量判定的 id 列表放请求体而不是 URL。
  for (const path of [
    'catalog/brands',
    `catalog/brands/${brand}/authorizations`,
    'catalog/brands/listing-eligibility',
  ]) {
    expect((await POST(request(path, 'POST'), { params: { path: path.split('/') } })).status).toBe(
      200,
    );
  }
  for (const path of [
    `catalog/brands/${brand}`,
    `catalog/brands/${brand}/enabled`,
    `catalog/brands/${brand}/authorizations/${credential}`,
    `catalog/brands/${brand}/authorizations/${credential}/enabled`,
  ]) {
    expect((await PUT(request(path, 'PUT'), { params: { path: path.split('/') } })).status).toBe(
      200,
    );
  }
  expect(
    (
      await DELETE(request(`catalog/brands/${brand}`, 'DELETE'), {
        params: { path: ['catalog', 'brands', brand] },
      })
    ).status,
  ).toBe(200);
});

it('catalog/unit-groups 的全部路由被放行且转发到 catalog 服务（3.3.3.1 计量单位）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('[]')));
  vi.stubGlobal('fetch', fetcher);
  const group = '11111111-1111-1111-1111-111111111111';
  const version = '22222222-2222-2222-2222-222222222222';
  for (const path of [
    'catalog/unit-groups',
    'catalog/unit-groups/overview',
    `catalog/unit-groups/${group}`,
  ]) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(200);
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining(`:8082/${path}`),
      expect.objectContaining({
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer private-session' },
      }),
    );
  }
  // 建组、建换算版本与换算试算都是 POST；试算的数量与基准日放请求体而不是 URL。
  for (const path of [
    'catalog/unit-groups',
    'catalog/unit-groups/convert',
    `catalog/unit-groups/${group}/conversions`,
  ]) {
    expect((await POST(request(path, 'POST'), { params: { path: path.split('/') } })).status).toBe(
      200,
    );
  }
  expect(
    (
      await PUT(request(`catalog/unit-groups/${group}/enabled`, 'PUT'), {
        params: { path: ['catalog', 'unit-groups', group, 'enabled'] },
      })
    ).status,
  ).toBe(200);
  expect(
    (
      await PUT(request(`catalog/unit-groups/${group}`, 'PUT'), {
        params: { path: ['catalog', 'unit-groups', group] },
      })
    ).status,
  ).toBe(200);
  // 删组 / 删已闭合的历史换算版本。
  for (const [path, segments] of [
    [`catalog/unit-groups/${group}`, ['catalog', 'unit-groups', group]],
    [
      `catalog/unit-groups/${group}/conversions/${version}`,
      ['catalog', 'unit-groups', group, 'conversions', version],
    ],
  ] as const) {
    expect((await DELETE(request(path, 'DELETE'), { params: { path: [...segments] } })).status).toBe(
      200,
    );
  }
  // 不在白名单里的子路径仍然 404：放行不能是前缀通配。
  expect(
    (await GET(request(`catalog/unit-groups/${group}/unknown`, 'GET'), {
      params: { path: ['catalog', 'unit-groups', group, 'unknown'] },
    })).status,
  ).toBe(404);
});

it('catalog/categories/attribute-requirements 的全部路由被放行（3.3.1.2 类目属性要求）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('[]')));
  vi.stubGlobal('fetch', fetcher);
  const category = '11111111-1111-1111-1111-111111111111';
  const attribute = '22222222-2222-2222-2222-222222222222';
  for (const path of [
    'catalog/categories/attribute-requirements/overview',
    `catalog/categories/attribute-requirements/${category}`,
    `catalog/categories/attribute-requirements/${category}/effective`,
  ]) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(200);
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining(`:8082/${path}`),
      expect.objectContaining({
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer private-session' },
      }),
    );
  }
  // 保存规则、生成录入表单 schema、保存前校验：三个都是 POST，且落在同一批路径上。
  for (const path of [
    `catalog/categories/attribute-requirements/${category}`,
    `catalog/categories/attribute-requirements/${category}/entry-form-schema`,
    `catalog/categories/attribute-requirements/${category}/validate`,
  ]) {
    expect((await POST(request(path, 'POST'), { params: { path: path.split('/') } })).status).toBe(
      200,
    );
  }
  expect(
    (
      await PUT(
        request(`catalog/categories/attribute-requirements/${category}/${attribute}/enabled`),
        {
          params: {
            path: [
              'catalog',
              'categories',
              'attribute-requirements',
              category,
              attribute,
              'enabled',
            ],
          },
        },
      )
    ).status,
  ).toBe(200);
  expect(
    (
      await DELETE(request(`catalog/categories/attribute-requirements/${category}/${attribute}`), {
        params: {
          path: ['catalog', 'categories', 'attribute-requirements', category, attribute],
        },
      })
    ).status,
  ).toBe(200);
});

it('attribute-requirements 放行不等于放行任意 attribute-requirements 子路径', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const category = '11111111-1111-1111-1111-111111111111';
  const attribute = '22222222-2222-2222-2222-222222222222';
  for (const path of [
    // overview 是唯一的字面量第三段，其余第三段必须是 UUID。
    'catalog/categories/attribute-requirements/unknown',
    `catalog/categories/attribute-requirements/${category}/unknown`,
    `catalog/categories/attribute-requirements/${category}/${attribute}/unknown`,
    `catalog/categories/attribute-requirements/${category}/effective/extra`,
  ]) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

it('categories 放行不等于放行任意 catalog 子路径', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const id = '11111111-1111-1111-1111-111111111111';
  const tree = '22222222-2222-2222-2222-222222222222';
  for (const path of [
    'catalog/categories/unknown',
    'catalog/categories/nodes',
    `catalog/categories/nodes/${id}/unknown`,
    // 整棵树一次取回（结构 + 路径串 + 商品数来自同一次查询），后面不接第三段 id。
    `catalog/categories/trees/${tree}/nodes/${id}`,
    `catalog/categories/spus/${id}/extra`,
    'catalog/categoriesx',
  ]) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

it('catalog/attributes 的全部路由被放行且转发到 catalog 服务', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('[]')));
  vi.stubGlobal('fetch', fetcher);
  const id = '11111111-1111-1111-1111-111111111111';
  for (const path of [
    'catalog/attributes',
    'catalog/attributes/overview',
    'catalog/attributes/category-rules',
    `catalog/attributes/${id}`,
    `catalog/attributes/${id}/options`,
    `catalog/attributes/${id}/category-rules`,
  ]) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(200);
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining(`:8082/${path}`),
      expect.objectContaining({
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer private-session' },
      }),
    );
  }
  for (const path of ['catalog/attributes', `catalog/attributes/${id}/options`]) {
    expect((await POST(request(path), { params: { path: path.split('/') } })).status).toBe(200);
  }
  expect(
    (
      await PUT(request(`catalog/attributes/${id}`), {
        params: { path: [`catalog`, 'attributes', id] },
      })
    ).status,
  ).toBe(200);
  const rule = `catalog/attributes/${id}/category-rules`;
  expect(
    (await DELETE(request(rule, 'DELETE'), { params: { path: rule.split('/') } })).status,
  ).toBe(200);
});

it('attributes 放行不等于放行任意 catalog 子路径', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const id = '11111111-1111-1111-1111-111111111111';
  for (const path of [
    'catalog/attributes/unknown',
    `catalog/attributes/${id}/options/extra`,
    `catalog/attributes/${id}/category-rules/22222222-2222-2222-2222-222222222222`,
  ]) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

/**
 * 3.2.1.2 属性选项管理：跨属性聚合视图的三条路由必须放行。
 *
 * <p>要防的坑是 `attributes/options`（字面量，跨属性总览）与 `attributes/{uuid}/options`
 * （单属性取值，3.2.1.1）被混为一谈：前者第一段是字面量 options，后者是 UUID。
 * 只放行后者的话，聚合页只会一直进错误态，看起来像后端没实现。
 */
it('catalog/attributes/options 的三条路由被放行', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('[]')));
  vi.stubGlobal('fetch', fetcher);
  expect(
    (
      await GET(request('catalog/attributes/options'), {
        params: { path: ['catalog', 'attributes', 'options'] },
      })
    ).status,
  ).toBe(200);
  expect(fetcher).toHaveBeenLastCalledWith(
    expect.stringContaining(':8082/catalog/attributes/options'),
    expect.objectContaining({
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer private-session' },
    }),
  );
  for (const path of ['catalog/attributes/options/lifecycle', 'catalog/attributes/options/order']) {
    expect((await PUT(request(path), { params: { path: path.split('/') } })).status).toBe(200);
  }
  // 放行 options 不等于放行 options 底下的任意子路径
  expect(
    (
      await GET(request('catalog/attributes/options/unknown'), {
        params: { path: ['catalog', 'attributes', 'options', 'unknown'] },
      })
    ).status,
  ).toBe(404);
});

/**
 * 3.2.2.2 SKU 属性覆写：catalog allowlist 必须放行 sku-attribute-override 全路径。
 *
 * <p>同一个坑：allowlist 是锚定正则，落不在分支里的路径一律 404，页面只会进错误态，
 * 看起来像「后端没实现」（见 docs/backlog/2026-10/2026-10-07-sku-code-api-proxy-allowlist.md）。
 * 这里把四条路由都钉住：读面板、覆写、显式清空、取消覆写。
 *
 * <p>要防的混用是「sku-attribute-override」（第一段是字面量）与 `products/{id}/...`
 *（第一段是 UUID）：本模块刻意按 SKU 寻址，路径里没有商品 id。
 */
it('catalog/sku-attribute-override 的全部路由被放行且转发到 catalog 服务', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('{}')));
  vi.stubGlobal('fetch', fetcher);
  const sku = '11111111-1111-1111-1111-111111111111';
  const attribute = '22222222-2222-2222-2222-222222222222';
  const panel = `catalog/sku-attribute-override/skus/${sku}`;
  for (const path of [
    panel,
    `${panel}/attribute-overrides/${attribute}`,
    `${panel}/attribute-overrides/${attribute}/clear`,
  ]) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(200);
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining(`:8082/${path}`),
      expect.objectContaining({
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer private-session' },
      }),
    );
  }
  // 覆写与显式清空都用 PUT，取消覆写用 DELETE
  for (const path of [
    `${panel}/attribute-overrides/${attribute}`,
    `${panel}/attribute-overrides/${attribute}/clear`,
  ]) {
    expect((await PUT(request(path, 'PUT'), { params: { path: path.split('/') } })).status).toBe(
      200,
    );
  }
  expect(
    (
      await DELETE(request(`${panel}/attribute-overrides/${attribute}`, 'DELETE'), {
        params: { path: `${panel}/attribute-overrides/${attribute}`.split('/') },
      })
    ).status,
  ).toBe(200);
});

it('sku-attribute-override 放行不等于放行任意 catalog 子路径', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const sku = '11111111-1111-1111-1111-111111111111';
  const attribute = '22222222-2222-2222-2222-222222222222';
  // allowlist 是精确路径：多一层未声明的子路径、或少了 SKU 段，仍应 404，
  // 否则拼错的路径会被静默转发到后端，后端给出的错误会与真正的参数错误混在一起。
  for (const path of [
    'catalog/sku-attribute-override',
    `catalog/sku-attribute-override/skus/${sku}/unknown`,
    `catalog/sku-attribute-override/skus/${sku}/attribute-overrides/${attribute}/clear/extra`,
  ]) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});
