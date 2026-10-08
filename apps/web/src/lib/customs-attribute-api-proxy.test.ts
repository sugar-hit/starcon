import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

/**
 * 3.7.1.1 HS 编码与申报要素的 api-proxy 白名单回归。
 *
 * <p><b>为什么必须打真 HTTP 状态码，而不是把正则抄出来自己测</b>：
 * 白名单**只在浏览器端生效**（`pnpm test` 直连服务层，永远测不出代理 404），
 * 漏登记一条 = 整个报关属性页面在浏览器里 404，而 typecheck / lint / vitest /
 * Playwright 全绿。这个文件历史上已经因此咬过 4 次
 * （8.2.1.2 / 10.4.1.1 / 10.4.2.1 / 10.5.1.3），每次都是「取错一侧静默删掉已交付路由」。
 *
 * <p>所以这里做两件事，且**两件都必须有**：
 * <ol>
 *   <li><b>放行用例</b>：真实路径必须被转发（≠ 404）；
 *   <li><b>反向用例</b>：未知形状必须仍是 404 —— 没有它，一条「全部拒绝」的
 *       正义正则能让所有放行用例都变绿。
 * </ol>
 *
 * <p>放行用例的路径**不是手抄的**：它们由 {@code customs-attribute-model} 里
 * 真正被调用的函数生成（fetchProfile / saveProfile / searchProfiles /
 * submitForReview / reviewProfile / fetchDeclarationReady），
 * 捕获浏览器实际发出的 URL，再把这条 URL 原样喂给真 proxy handler。
 * 组件改了路径这里就会红 —— 手抄的清单做不到这一点。
 */

const UUID = '0192f3c4-5d6e-7a8b-9c0d-1e2f3a4b5c6d';

/** 放行：本模块六个端点的真实形状（GET 与 POST 都算）。 */
const ALLOW = [
  'customs-attributes/profiles',
  `customs-attributes/skus/${UUID}`,
  `customs-attributes/skus/${UUID}/submit`,
  `customs-attributes/skus/${UUID}/review`,
  'customs-attributes/declaration-ready',
];

/**
 * 仍须拦截：放行一个后端不存在的形状，比 404 更糟 ——
 * 界面会以为功能已交付，而现场看到的是代理把请求送出去后端给一个更费解的 405。
 */
const BLOCK = [
  'customs-attribute', // 单数拼写
  'customs-attributes', // 裸前缀：后端没有这个映射
  'customs-attributes/skus', // 少一段 skuId
  `customs-attributes/skus/${UUID}/extra`, // 多一层子路径
  `customs-attributes/skus/${UUID}/submit/extra`,
  `customs-attributes/skus/${UUID}/restore`, // 不存在的动作段
  `customs-attributes/skus/${UUID}/review/extra`,
  `customs-attributes/skus/${UUID}/delete`, // 没有删除形状：要素是随保存整体替换的
  'customs-attributes/declaration-ready/extra',
  'customs-attributes/profiles/extra',
  `customs-attributes/skus/not-a-uuid`, // 位段必须是 uuid 形状
];

describe('3.7.1.1 api-proxy 白名单（真 proxy 状态码）', () => {
  beforeEach(() => {
    vi.resetModules();
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  /**
   * 跑一次真 proxy：命中白名单 → 转发给上游（stub 返回 200）；
   * 未命中 → 代理自己返 404。**判据是 HTTP 状态码本身**，不是我们复刻的正则。
   */
  async function proxyStatus(url: string, method = 'GET'): Promise<number> {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(JSON.stringify({ ok: true }), { status: 200 })),
    );
    const { proxy } = await import('./api-proxy');
    const { NextRequest } = await import('next/server');
    const req = new NextRequest(url, {
      method,
      headers: { cookie: 'starcon_session=test', origin: 'http://localhost' },
    });
    // /api/catalog/<...> 的 catch-all 把段原样交给 proxy
    const segments = new URL(url).pathname.split('/').filter(Boolean).slice(2);
    const res = await proxy(req, { params: { path: segments } } as never);
    return res.status;
  }

  /**
   * 浏览器实际请求的形状：`api()` 拼的是 `/api/<route>/<path>`，而 path 本身
   * 已带服务前缀，所以 catalog 的真实地址是 `/api/catalog/catalog/...` ——
   * 服务名出现两次不是笔误，少写一段会让测试测到一个不存在的路由。
   */
  const asUrl = (path: string) => `http://localhost/api/catalog/catalog/${path}`;

  it.each(ALLOW)('放行 %s（转发，不是 404）', async (path) => {
    const status = await proxyStatus(asUrl(path));
    expect(status, `${path} 被白名单拦截了（${status}）—— 页面在浏览器里会 404`).not.toBe(404);
    expect(status).toBe(200);
  });

  it.each(BLOCK)('仍拦截 %s（404）', async (path) => {
    const status = await proxyStatus(asUrl(path));
    expect(status, `${path} 不该被放行：后端没有这个端点`).toBe(404);
  });

  it('POST 动作段同样放行（提交 / 审核不是 GET 形状）', async () => {
    // 代理对非 GET 会校验 origin；带上正确 origin 才测得到「转发」而不是 403。
    expect(await proxyStatus(asUrl(`customs-attributes/skus/${UUID}/submit`), 'POST')).toBe(200);
    expect(await proxyStatus(asUrl(`customs-attributes/skus/${UUID}/review`), 'POST')).toBe(200);
  });

  it('查询串从不进入匹配：代理只拿 path，query 走 nextUrl.search', async () => {
    const status = await proxyStatus(
      asUrl('customs-attributes/declaration-ready') + `?skuId=${UUID}`,
    );
    expect(status).toBe(200);
  });

  // ---------------------------------------------------------- 不误伤已交付的 catalog 路由

  it.each([
    'products',
    `products/${UUID}`,
    `products/${UUID}/skus`,
    'skus/capabilities',
    'attributes',
    'attributes/overview',
    `attributes/${UUID}/options`,
    'brands',
    'brands/overview',
    'categories/overview',
    'categories/trees',
    'completeness',
    'completeness/overview',
    'completeness/gaps/summary',
    `completeness/customs/skus/${UUID}`,
    'sku-attribute-override/skus/' + UUID,
    'media',
    'titles',
    'boms',
    'parties',
    'translations/overview',
    'sku-merges/overview',
  ])('不误伤已交付的 catalog 路由 %s', async (path) => {
    const status = await proxyStatus(asUrl(path));
    expect(status, `白名单是求并，加 customs-attributes 不该删掉 ${path}`).not.toBe(404);
  });

  it.each(['customs', 'customs/profiles', 'customs-attribute-profiles', 'customs-profiles'])(
    '不误放行不存在的兄弟路径 %s',
    async (path) => {
      expect(await proxyStatus(asUrl(path))).toBe(404);
    },
  );
});

describe('3.7.1.1 组件真实调用路径都在白名单里', () => {
  beforeEach(() => {
    vi.resetModules();
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  /**
   * 让模型里**真正被调用的函数**把 URL 发出来，捕获浏览器实际请求的地址。
   *
   * <p>刻意不手抄路径清单：手抄的清单改组件时不会红，而这里会。
   */
  async function urlsFromModel(): Promise<string[]> {
    const urls: string[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init?: RequestInit) => {
        // 方法一起记：GET 与 PUT 打的是同一条路径，只按 URL 去重会把两个端点并成一个。
        urls.push(`${init?.method ?? 'GET'} ${String(url)}`);
        return new Response('{}', { status: 200 });
      }),
    );
    const model = await import('../components/catalog/customs-attribute-model');
    await model.fetchProfile(UUID).catch(() => undefined);
    await model.saveProfile(UUID, {}).catch(() => undefined);
    // 刻意传 preferenceAgreement: '' —— 它在界面上表示「筛不适用任何协定的那些」，
    // 与「不筛」不是一回事，所以不能被当作空值丢掉。
    await model
      .searchProfiles({ keyword: '手机', status: 'DRAFT', preferenceAgreement: '' })
      .catch(() => undefined);
    await model.submitForReview(UUID, 0).catch(() => undefined);
    await model.reviewProfile(UUID, { approve: true, rejectReason: '' }).catch(() => undefined);
    await model.fetchDeclarationReady(UUID).catch(() => undefined);
    return urls;
  }

  it('确实抓到了六个端点的调用（防止抽取器坏掉后静默通过）', async () => {
    const urls = await urlsFromModel();
    expect(urls.length).toBe(6);
    expect(new Set(urls).size).toBe(6);
  });

  it('每条真实调用路径都被放行', async () => {
    const urls = await urlsFromModel();
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(JSON.stringify({ ok: true }), { status: 200 })),
    );
    const { proxy } = await import('./api-proxy');
    const { NextRequest } = await import('next/server');
    const blocked: string[] = [];
    for (const call of urls) {
      const [method = 'GET', rawUrl = ''] = call.split(' ');
      // api() 给的是站点内相对路径，NextRequest 要绝对 URL
      const url = new URL(rawUrl, 'http://localhost');
      const req = new NextRequest(url, {
        method,
        headers: { cookie: 'starcon_session=test', origin: 'http://localhost' },
      });
      const segments = url.pathname.split('/').filter(Boolean).slice(2);
      const res = await proxy(req, { params: { path: segments } } as never);
      if (res.status === 404) blocked.push(call);
    }
    expect(blocked, `这些真实调用路径没有登记进白名单：${blocked.join('、')}`).toEqual([]);
  });

  it('筛选空值语义保留：preferenceAgreement="" 是「筛不适用协定的」，不是不筛', async () => {
    const urls = await urlsFromModel();
    const filtered = urls.find((call) => call.includes('/profiles'));
    expect(filtered, '筛选请求没抓到').toBeDefined();
    // 空串必须真的出现在 query 里：被 `if (value === '') continue` 吞掉的话，
    // 「哪些货完全不走协定」这个问题在界面上就答不了了。
    expect(filtered).toContain('preferenceAgreement=');
  });
});