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
/** 路由参数只吃路径段；带查询串（`?a=b`）的用例先剥离查询串再拆段。 */
function segments(path: string) {
  return (path.split('?')[0] ?? path).split('/');
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
    expect(
      (await DELETE(request(path, 'DELETE'), { params: { path: [...segments] } })).status,
    ).toBe(200);
  }
  // 不在白名单里的子路径仍然 404：放行不能是前缀通配。
  expect(
    (
      await GET(request(`catalog/unit-groups/${group}/unknown`, 'GET'), {
        params: { path: ['catalog', 'unit-groups', group, 'unknown'] },
      })
    ).status,
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

it('catalog/translations 的全部路由被放行且转发到 catalog 服务（3.4.3.1 多语言内容）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('[]')));
  vi.stubGlobal('fetch', fetcher);
  const spu = '11111111-1111-1111-1111-111111111111';
  const sku = '22222222-2222-2222-2222-222222222222';
  const locale = '33333333-3333-3333-3333-333333333333';
  const entry = '44444444-4444-4444-4444-444444444444';
  const suggestion = '55555555-5555-5555-5555-555555555555';
  const batch = '66666666-6666-6666-6666-666666666666';

  // GET：概览、语言需求（人工维护 + 合并视图）、商品工作台、列表、完整度、批次、建议。
  for (const path of [
    'catalog/translations/overview',
    'catalog/translations/locales',
    'catalog/translations/locales/requirements',
    `catalog/translations/locales/${locale}`,
    `catalog/translations/spus/${spu}`,
    `catalog/translations/skus/${sku}`,
    'catalog/translations/entries',
    `catalog/translations/entries/${entry}`,
    'catalog/translations/coverage',
    'catalog/translations/suggestions',
    `catalog/translations/suggestions/${suggestion}`,
    'catalog/translations/batches',
    `catalog/translations/batches/${batch}`,
  ]) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(200);
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining(`:8082/${path}`),
      expect.objectContaining({
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer private-session' },
      }),
    );
  }

  // 建语言需求 / 建译文 / 从店铺站点同步 / 登记建议 / 批量采纳 是 POST。
  // sync 走 POST 是因为它是「重新推导一次」而不是「新建一条」。
  for (const path of [
    'catalog/translations/locales',
    'catalog/translations/locales/sync',
    `catalog/translations/spus/${spu}/entries`,
    `catalog/translations/skus/${sku}/entries`,
    'catalog/translations/suggestions',
    `catalog/translations/suggestions/${suggestion}/adopt`,
    `catalog/translations/suggestions/${suggestion}/reject`,
    'catalog/translations/batches',
  ]) {
    expect((await POST(request(path, 'POST'), { params: { path: path.split('/') } })).status).toBe(
      200,
    );
  }

  // 启停语言需求 / 改译文 / 状态流转（人工校对）是 PUT。
  for (const path of [
    `catalog/translations/locales/${locale}`,
    `catalog/translations/locales/${locale}/enabled`,
    `catalog/translations/entries/${entry}`,
    `catalog/translations/entries/${entry}/status`,
  ]) {
    expect((await PUT(request(path, 'PUT'), { params: { path: path.split('/') } })).status).toBe(
      200,
    );
  }

  // CSV 导出与删除都带 ?version=，走 DELETE。
  expect(
    (
      await GET(request(`catalog/translations/entries/export?version=0`), {
        params: { path: 'catalog/translations/entries/export'.split('/') },
      })
    ).status,
  ).toBe(200);
  for (const [prefix, id] of [
    ['locales', locale],
    ['entries', entry],
    ['suggestions', suggestion],
  ] as const) {
    expect(
      (
        await DELETE(request(`catalog/translations/${prefix}/${id}?version=0`, 'DELETE'), {
          params: { path: ['catalog', 'translations', prefix, id] },
        })
      ).status,
    ).toBe(200);
  }
});

it('translations 放行不等于放行任意 catalog 子路径', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const entry = '44444444-4444-4444-4444-444444444444';
  // allowlist 是精确路径：多一层未声明的子路径、或把 SPU 工作台当成任意资源读，仍应 404，
  // 否则拼错的路径会被静默转发到后端，后端给出的错误会与真正的参数错误混在一起。
  for (const path of [
    'catalog/translations',
    'catalog/translations/unknown',
    'catalog/translations/locales/requirements/extra',
    `catalog/translations/entries/${entry}/unknown`,
    `catalog/translations/suggestions/${entry}/adopt/extra`,
    'catalog/translations/batches/abc',
  ]) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(404);
  }
  expect(fetcher).not.toHaveBeenCalled();
});

it('translations 放行不放行 3.4.2.2 的模板译文路径（两套 locale 不能混用）', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  // descriptions 自己的 translations/{id}（模板译文删除）仍然放行，
  // 但它必须挂在 descriptions 之下；裸的 translations/{id} 不是合法路径 ——
  // 否则「模板译文」与「商品译文」会在代理层就变成同一条规则。
  const suggestion = '55555555-5555-5555-5555-555555555555';
  expect(
    (
      await GET(request(`catalog/translations/${suggestion}`), {
        params: { path: ['catalog', 'translations', suggestion] },
      })
    ).status,
  ).toBe(404);
  expect(fetcher).not.toHaveBeenCalled();
});

it('catalog/boms、catalog/bom-versions 与 catalog/bom-components 的全部路由被放行（3.5.2.1 BOM 版本管理）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('[]')));
  vi.stubGlobal('fetch', fetcher);
  const bom = '55555555-5555-5555-5555-555555555555';
  const version = '66666666-6666-6666-6666-666666666666';
  const sku = '77777777-7777-7777-7777-777777777777';
  // 读：BOM 概览、列表、详情、当前生效版本（规则 R4 的时刻查询）、版本列表、组件清单、
  // 单层需求量（规则 R7）、倒排查询。
  for (const path of [
    'catalog/boms/overview',
    'catalog/boms',
    `catalog/boms/${bom}`,
    `catalog/boms/${bom}/effective`,
    `catalog/boms/${bom}/versions`,
    'catalog/bom-versions',
    `catalog/bom-versions/${version}`,
    `catalog/bom-versions/${version}/components`,
    `catalog/bom-versions/${version}/requirement?outputQuantity=100`,
    `catalog/bom-components/usage?skuId=${sku}`,
  ]) {
    expect((await GET(request(path), { params: { path: segments(path) } })).status).toBe(200);
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining(`:8082/${path}`),
      expect.objectContaining({
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer private-session' },
      }),
    );
  }
  // POST：建 BOM、建草稿版本、发布、版本复制。
  for (const path of [
    'catalog/boms',
    `catalog/boms/${bom}/versions`,
    `catalog/bom-versions/${version}/publish`,
    `catalog/bom-versions/${version}/copy?versionNo=v2`,
  ]) {
    expect((await POST(request(path, 'POST'), { params: { path: segments(path) } })).status).toBe(
      200,
    );
  }
  // PUT：改 BOM、改草稿版本、向前关闭生效区间、整体替换组件清单。
  for (const path of [
    `catalog/boms/${bom}`,
    `catalog/boms/${bom}/status?status=ARCHIVED`,
    `catalog/bom-versions/${version}`,
    `catalog/bom-versions/${version}/close?effectiveTo=2026-04-01T00:00:00Z`,
    `catalog/bom-versions/${version}/components`,
  ]) {
    expect((await PUT(request(path, 'PUT'), { params: { path: segments(path) } })).status).toBe(
      200,
    );
  }
  // DELETE：删 BOM、删草稿版本。
  for (const path of [`catalog/boms/${bom}`, `catalog/bom-versions/${version}`]) {
    expect(
      (await DELETE(request(path, 'DELETE'), { params: { path: segments(path) } })).status,
    ).toBe(200);
  }
  // 不在白名单里的相邻路径仍然 404：放行不能是前缀通配。
  // 尤其 bom-components 只放行 usage —— 它按 ?skuId= 寻址，不接 id 段。
  for (const path of [
    'catalog/boms/unknown',
    `catalog/boms/${bom}/unknown`,
    `catalog/bom-versions/${version}/unknown`,
    'catalog/bom-components',
    `catalog/bom-components/${sku}`,
  ]) {
    expect((await GET(request(path), { params: { path: segments(path) } })).status).toBe(404);
  }
  // 与 3.5.1.1 并列而不合并：放行 boms 绝不代表放行 bundles（反之亦然）。
  for (const path of ['catalog/bundles', `catalog/versions/${version}`]) {
    expect((await GET(request(path), { params: { path: segments(path) } })).status).toBe(200);
  }
  expect(
    (await GET(request('catalog/bomsx'), { params: { path: ['catalog', 'bomsx'] } })).status,
  ).toBe(404);
});

it('catalog/bom-explosions 被放行且只放行展开与读回（3.5.2.2 BOM 展开）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('[]')));
  vi.stubGlobal('fetch', fetcher);
  const run = '88888888-8888-8888-8888-888888888888';
  const bom = '55555555-5555-5555-5555-555555555555';
  // 读：展开运行记录列表、读回一次展开（含完整展开树）。
  for (const path of [
    'catalog/bom-explosions',
    `catalog/bom-explosions?bomId=${bom}`,
    `catalog/bom-explosions/${run}`,
  ]) {
    expect((await GET(request(path), { params: { path: segments(path) } })).status).toBe(200);
  }
  // POST：执行一次展开。
  expect(
    (
      await POST(request('catalog/bom-explosions', 'POST'), {
        params: { path: ['catalog', 'bom-explosions'] },
      })
    ).status,
  ).toBe(200);
  // 放行不能是前缀通配：展开只有 {id} 段，没有子资源。
  for (const path of [`catalog/bom-explosions/${run}/unknown`, 'catalog/bom-explosions/abc']) {
    expect((await GET(request(path), { params: { path: segments(path) } })).status).toBe(404);
  }
  // 注意：代理只按**路径**放行，不按 HTTP 方法。所以「展开只有 POST、没有 PUT/DELETE」
  // 不是这里的断言范围 —— 那是后端的映射问题（未映射的方法回 405），
  // 由 packages/api-contracts 里 codegen 出来的 OpenAPI 快照证明。
  // 代理这一层要保证的是：路径不被前缀通配放开（上面那两条 404）。
});

it('catalog/parties 的全部路由被放行（3.6.1.1 往来单位档案）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('[]')));
  vi.stubGlobal('fetch', fetcher);
  const party = '77777777-7777-7777-7777-777777777777';
  const role = '99999999-9999-9999-9999-999999999999';
  // 读：列表（含查询串）、概览、引用候选、详情、角色清单。
  for (const path of [
    'catalog/parties',
    `catalog/parties?keyword=ACME&roleType=SUPPLIER&usable=true`,
    'catalog/parties/overview',
    'catalog/parties/candidates?roleType=CUSTOMER',
    `catalog/parties/${party}`,
    `catalog/parties/${party}/roles`,
  ]) {
    expect((await GET(request(path), { params: { path: segments(path) } })).status).toBe(200);
  }
  // 写：新建、编辑、状态流转、指派角色、改角色、解除角色、软删除。
  expect(
    (await POST(request('catalog/parties', 'POST'), { params: { path: ['catalog', 'parties'] } }))
      .status,
  ).toBe(200);
  expect(
    (
      await PUT(request(`catalog/parties/${party}`, 'PUT'), {
        params: { path: ['catalog', 'parties', party] },
      })
    ).status,
  ).toBe(200);
  expect(
    (
      await PUT(request(`catalog/parties/${party}/status`, 'PUT'), {
        params: { path: ['catalog', 'parties', party, 'status'] },
      })
    ).status,
  ).toBe(200);
  expect(
    (
      await POST(request(`catalog/parties/${party}/roles`, 'POST'), {
        params: { path: ['catalog', 'parties', party, 'roles'] },
      })
    ).status,
  ).toBe(200);
  expect(
    (
      await PUT(request(`catalog/parties/${party}/roles/${role}`, 'PUT'), {
        params: { path: ['catalog', 'parties', party, 'roles', role] },
      })
    ).status,
  ).toBe(200);
  expect(
    (
      await DELETE(request(`catalog/parties/${party}/roles/${role}`, 'DELETE'), {
        params: { path: ['catalog', 'parties', party, 'roles', role] },
      })
    ).status,
  ).toBe(200);
  expect(
    (
      await DELETE(request(`catalog/parties/${party}`, 'DELETE'), {
        params: { path: ['catalog', 'parties', party] },
      })
    ).status,
  ).toBe(200);

  // 3.6.1.2 角色档案：汇总读出口 + 三个字面量档案路径（GET / PUT / DELETE）。
  expect(
    (
      await GET(request(`catalog/parties/${party}/role-profiles`), {
        params: { path: ['catalog', 'parties', party, 'role-profiles'] },
      })
    ).status,
  ).toBe(200);
  for (const kind of ['supplier', 'customer', 'carrier']) {
    const segments4 = ['catalog', 'parties', party, `${kind}-profile`];
    expect((await GET(request(`catalog/parties/${party}/${kind}-profile`), { params: { path: segments4 } })).status).toBe(200);
    expect((await PUT(request(`catalog/parties/${party}/${kind}-profile`, 'PUT'), { params: { path: segments4 } })).status).toBe(200);
    expect((await DELETE(request(`catalog/parties/${party}/${kind}-profile`, 'DELETE'), { params: { path: segments4 } })).status).toBe(200);
  }
  // 档案台账：overview / options / 列表
  for (const suffix of ['overview', 'options']) {
    expect(
      (
        await GET(request(`catalog/role-profiles/${suffix}`), {
          params: { path: ['catalog', 'role-profiles', suffix] },
        })
      ).status,
    ).toBe(200);
  }
  expect(
    (await GET(request('catalog/role-profiles'), { params: { path: ['catalog', 'role-profiles'] } }))
      .status,
  ).toBe(200);

  // 放行仍然不能是前缀通配：3.6.1.2 只开了上面那几条**字面量**路径，
  // 任意子段仍然拒绝 —— 放行 {id}/xxx 任意组合会让「一个还没实现的子路径」看起来像已交付。
  for (const path of [
    `catalog/parties/${party}/vendor-profile`,
    `catalog/parties/${party}/supplier-profiles`,
    `catalog/parties/${party}/role-profile`,
    `catalog/parties/${party}/status/extra`,
    `catalog/parties/${party}/roles/${role}/profiles`,
    // id 段用宽松的 [0-9a-f-]+（与 brands / categories 同一口径），所以 abc 这种
    // 「碰巧是 hex」的串会被放行；这里用一定不是 hex 的串验证非 id 段会被拒。
    'catalog/parties/not-a-uuid',
    'catalog/partiesx',
  ]) {
    expect((await GET(request(path), { params: { path: segments(path) } })).status).toBe(404);
  }
});

it('catalog/parties/{id}/addresses 与 /contacts 的全部路由被放行（3.6.3.1 地址与联系人）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('[]')));
  vi.stubGlobal('fetch', fetcher);
  const party = '77777777-7777-7777-7777-777777777777';
  const address = '88888888-8888-8888-8888-888888888888';
  const contact = '99999999-9999-9999-9999-999999999999';
  // 读：地址列表（含筛选查询串）、默认地址、引用候选、地址详情、版本链与某一版、
  // 联系人列表、默认联系人、引用候选、联系人详情。
  for (const path of [
    `catalog/parties/${party}/addresses`,
    `catalog/parties/${party}/addresses?keyword=仓库&usage=PICKUP&includeDeleted=true`,
    `catalog/parties/${party}/addresses/defaults?usage=SHIP_TO`,
    `catalog/parties/${party}/addresses/candidates?usage=PICKUP&includeBlocked=true`,
    `catalog/parties/${party}/addresses/${address}`,
    `catalog/parties/${party}/addresses/${address}/versions`,
    `catalog/parties/${party}/addresses/${address}/versions/3`,
    `catalog/parties/${party}/contacts`,
    `catalog/parties/${party}/contacts?keyword=张三&primaryOnly=true`,
    `catalog/parties/${party}/contacts/default`,
    `catalog/parties/${party}/contacts/candidates`,
    `catalog/parties/${party}/contacts/${contact}`,
  ]) {
    expect((await GET(request(path), { params: { path: segments(path) } })).status).toBe(200);
  }
  // 写：新建 / 改地址（内容变了才追加版本）、只改用途、设默认、软删除；
  // 新建 / 改联系人、设默认、删除。
  expect(
    (
      await POST(request(`catalog/parties/${party}/addresses`, 'POST'), {
        params: { path: ['catalog', 'parties', party, 'addresses'] },
      })
    ).status,
  ).toBe(200);
  expect(
    (
      await PUT(request(`catalog/parties/${party}/addresses/${address}`, 'PUT'), {
        params: { path: ['catalog', 'parties', party, 'addresses', address] },
      })
    ).status,
  ).toBe(200);
  expect(
    (
      await PUT(request(`catalog/parties/${party}/addresses/${address}/usages`, 'PUT'), {
        params: { path: ['catalog', 'parties', party, 'addresses', address, 'usages'] },
      })
    ).status,
  ).toBe(200);
  expect(
    (
      await PUT(request(`catalog/parties/${party}/addresses/${address}/default`, 'PUT'), {
        params: { path: ['catalog', 'parties', party, 'addresses', address, 'default'] },
      })
    ).status,
  ).toBe(200);
  expect(
    (
      await DELETE(request(`catalog/parties/${party}/addresses/${address}`, 'DELETE'), {
        params: { path: ['catalog', 'parties', party, 'addresses', address] },
      })
    ).status,
  ).toBe(200);
  expect(
    (
      await POST(request(`catalog/parties/${party}/contacts`, 'POST'), {
        params: { path: ['catalog', 'parties', party, 'contacts'] },
      })
    ).status,
  ).toBe(200);
  expect(
    (
      await PUT(request(`catalog/parties/${party}/contacts/${contact}`, 'PUT'), {
        params: { path: ['catalog', 'parties', party, 'contacts', contact] },
      })
    ).status,
  ).toBe(200);
  expect(
    (
      await PUT(request(`catalog/parties/${party}/contacts/${contact}/primary`, 'PUT'), {
        params: { path: ['catalog', 'parties', party, 'contacts', contact, 'primary'] },
      })
    ).status,
  ).toBe(200);
  expect(
    (
      await DELETE(request(`catalog/parties/${party}/contacts/${contact}`, 'DELETE'), {
        params: { path: ['catalog', 'parties', party, 'contacts', contact] },
      })
    ).status,
  ).toBe(200);

  // 放行不能是前缀通配：
  //  * 版本号段是 [0-9]+ 而不是宽松的 hex —— 一条比整数更长的版本号要 404，
  //    否则「取回第 N 版」这条读出口的入参边界就没有约束了；
  //  * 用途/默认/版本都是**动作**子路径，不能被当成资源 id 之后再挂子路径。
  for (const path of [
    `catalog/parties/${party}/addresses/${address}/versions/1/extra`,
    `catalog/parties/${party}/addresses/${address}/versions/not-a-number`,
    `catalog/parties/${party}/addresses/${address}/usages/extra`,
    `catalog/parties/${party}/addresses/${address}/default/extra`,
    `catalog/parties/${party}/addresses/not-a-uuid`,
    `catalog/parties/${party}/contacts/${contact}/primary/extra`,
    `catalog/parties/${party}/contacts/not-a-uuid`,
    `catalog/parties/${party}/addressesx`,
  ]) {
    expect((await GET(request(path), { params: { path: segments(path) } })).status).toBe(404);
  }
});

it('catalog/components 与 catalog/bundle-skus 的全部路由被放行（3.5.1.2 组件维护）', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response('[]')));
  vi.stubGlobal('fetch', fetcher);
  const bundle = '11111111-1111-1111-1111-111111111111';
  const version = '22222222-2222-2222-2222-222222222222';
  const component = '33333333-3333-3333-3333-333333333333';
  const link = '44444444-4444-4444-4444-444444444444';
  // 读：组件工作台概览、跨版本清单、单行详情、指向列表、指向单行。
  for (const path of [
    'catalog/components/overview',
    'catalog/components',
    `catalog/components/${component}`,
    'catalog/bundle-skus',
    `catalog/bundle-skus/${link}`,
  ]) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(200);
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining(`:8082/${path}`),
      expect.objectContaining({
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer private-session' },
      }),
    );
  }
  // 结构化校验结果：一次列全循环/数量非法/组件不存在，与保存报错分开。
  expect(
    (
      await GET(request(`catalog/versions/${version}/component-check`), {
        params: { path: ['catalog', 'versions', version, 'component-check'] },
      })
    ).status,
  ).toBe(200);
  // 加一行组件（POST）与新建指向都是 POST。
  for (const path of [`catalog/versions/${version}/components`, 'catalog/bundle-skus']) {
    expect((await POST(request(path, 'POST'), { params: { path: path.split('/') } })).status).toBe(
      200,
    );
  }
  // 改单行组件与改指向都是 PUT。
  for (const [path, segments] of [
    [`catalog/components/${component}`, ['catalog', 'components', component]],
    [`catalog/bundle-skus/${link}`, ['catalog', 'bundle-skus', link]],
  ] as const) {
    expect((await PUT(request(path, 'PUT'), { params: { path: [...segments] } })).status).toBe(200);
  }
  // 删单行组件与解指向都是 DELETE。
  for (const [path, segments] of [
    [`catalog/components/${component}`, ['catalog', 'components', component]],
    [`catalog/bundle-skus/${link}`, ['catalog', 'bundle-skus', link]],
  ] as const) {
    expect(
      (await DELETE(request(path, 'DELETE'), { params: { path: [...segments] } })).status,
    ).toBe(200);
  }
  // 不在白名单里的相邻路径仍然 404：放行不能是前缀通配。
  for (const path of [
    'catalog/components/unknown',
    `catalog/components/${component}/unknown`,
    'catalog/bundle-skus/unknown',
    `catalog/bundle-skus/${link}/unknown`,
    `catalog/versions/${version}/component-checks`,
  ]) {
    expect((await GET(request(path), { params: { path: path.split('/') } })).status).toBe(404);
  }
  // 3.5.1.1 的整体替换与 3.5.1.2 的加一行是两条不同语义：
  // components/{id} 放行不代表 components/{id}/components 也放行。
  expect(
    (
      await GET(request(`catalog/components/${component}/components`), {
        params: { path: ['catalog', 'components', component, 'components'] },
      })
    ).status,
  ).toBe(404);
});
