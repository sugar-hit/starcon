/**
 * 1.2.1.1 全局搜索框 —— 前端模型（纯函数，可单测）。
 *
 * <p><b>这一层不碰网络、不碰 React</b>：关键词归一化、跨服务结果合并、
 * 命中 → 目标路由的映射全在这里，前端测试直接测它。
 *
 * <p><b>跨服务的合并在前端做</b>（蓝图：「后端归属 = 前端聚合层 + 各服务的只读投影」）——
 * 每个服务只知道自己库里的表，合并规则（哪个域在前、同一域怎么去重、
 * 命中跳到哪个页面）由前端这一层统一决定。
 */

/** 后端返回的一条命中。四个服务的响应形状一致（各服务各自一份同名 record）。 */
export interface GlobalSearchHit {
  domain: string;
  kind: string;
  id: string;
  primary: string;
  secondary?: string | null;
}

/** 一个业务域的命中集合。 */
export interface GlobalSearchGroup {
  domain: string;
  label: string;
  hits: GlobalSearchHit[];
}

/** 一个服务的检索响应。 */
export interface GlobalSearchResponse {
  keyword: string;
  matchMode: string;
  minKeywordLength: number;
  groups: GlobalSearchGroup[];
  unavailableDomains: string[];
}

/** 单个服务的检索结果（成功或失败）。 */
export interface ServiceSearchOutcome {
  service: string;
  response: GlobalSearchResponse | null;
  error: string | null;
}

/** 蓝图写死的最小关键词长度。后端也校验这条，前端只是先挡一道。 */
export const MIN_KEYWORD_LENGTH = 2;

/**
 * 一个业务域的落点。
 *
 * <p>`param` 是目标页面筛选器的参数名：选中一条命中时把它**回填**到目标页面的
 * 关键字筛选器上（目标页面读 URL 上的这个参数，见 `usePrefillKeyword`）。
 */
export interface GlobalSearchTarget {
  label: string;
  href: string;
  param: string;
}

/**
 * 域 → 目标页面。
 *
 * <p>路由在前端决定而不是后端：后端只知道自己库里的表，不知道本系统的页面路由。
 */
export const GLOBAL_SEARCH_TARGETS: Record<string, GlobalSearchTarget> = {
  PRODUCT: { label: '商品', href: '/products', param: 'keyword' },
  ORDER: { label: '订单', href: '/orders', param: 'keyword' },
  PURCHASE: { label: '采购单', href: '/purchase/orders', param: 'keyword' },
  SHOP: { label: '店铺', href: '/channels', param: 'keyword' },
  SUPPLIER: { label: '供应商', href: '/partners', param: 'keyword' },
  CUSTOMER: { label: '客户', href: '/partners', param: 'keyword' },
  LOGISTICS: { label: '物流', href: '/warehouse/parcels', param: 'keyword' },
  WAREHOUSE: { label: '仓库', href: '/warehouse/facilities', param: 'keyword' },
};

/** 域在结果列表里的显示顺序（蓝图输出句的次序：商品 / 订单 / 采购 / 物流 / 客户 / 仓库）。 */
export const GLOBAL_SEARCH_DOMAIN_ORDER = [
  'PRODUCT',
  'ORDER',
  'PURCHASE',
  'LOGISTICS',
  'CUSTOMER',
  'WAREHOUSE',
  'SHOP',
  'SUPPLIER',
];

/** 关键词归一化：去首尾空白；少于 2 个字符不检索（蓝图写死的约束）。 */
export function normalizeKeyword(raw: string): {
  ok: boolean;
  keyword: string;
  hint: string;
} {
  const keyword = (raw ?? '').trim();
  if (keyword.length < MIN_KEYWORD_LENGTH) {
    return {
      ok: false,
      keyword,
      hint: `至少输入 ${MIN_KEYWORD_LENGTH} 个字符`,
    };
  }
  return { ok: true, keyword, hint: '' };
}

/** 域的中文名；未知域退回域码本身（不假装它属于某个已知业务域）。 */
export function domainLabel(domain: string): string {
  return GLOBAL_SEARCH_TARGETS[domain]?.label ?? domain;
}

/**
 * 命中 → 目标链接。
 *
 * <p>订单有详情页（`/orders/{id}`，蓝图 9.1.1.2 的订单控制塔），所以直接跳详情；
 * 其余的域跳到对应列表页并把主标识**回填**到关键字筛选器上。
 */
export function buildHref(domain: string, hit: GlobalSearchHit): string {
  const target = GLOBAL_SEARCH_TARGETS[domain];
  if (!target) return '/dashboard/search';
  if (domain === 'ORDER' && hit.id) return `/orders/${encodeURIComponent(hit.id)}`;
  return `${target.href}?${target.param}=${encodeURIComponent(hit.primary ?? '')}`;
}

/**
 * 跨服务结果合并。
 *
 * <p>同名域的命中合成一组（商品域在 catalog 里同时有 SKU 与 SPU）；
 * 同一域内按 `id` 去重（同一行可能被两个来源同时命中）。
 *
 * <p><b>「没权限的域」与「失败的域」必须分开</b>：前者是 `unavailableDomains`
 * （这个角色看不到那个域），后者是 `failedServices`（那个服务这次没响应）。
 * 混为一谈，界面就会把「服务挂了」显示成「没有数据」。
 */
export function mergeServiceResults(outcomes: ServiceSearchOutcome[]): {
  groups: GlobalSearchGroup[];
  unavailableDomains: string[];
  failedServices: string[];
} {
  const byDomain = new Map<string, GlobalSearchGroup>();
  const unavailable = new Set<string>();
  const failed: string[] = [];
  const seen = new Set<string>();

  for (const outcome of outcomes) {
    if (!outcome.response) {
      if (outcome.error) failed.push(outcome.service);
      continue;
    }
    for (const domain of outcome.response.unavailableDomains ?? []) unavailable.add(domain);
    for (const group of outcome.response.groups ?? []) {
      const existing = byDomain.get(group.domain);
      if (!existing) {
        byDomain.set(group.domain, { domain: group.domain, label: group.label, hits: [] });
      }
      const bucket = byDomain.get(group.domain)!;
      for (const hit of group.hits ?? []) {
        const key = `${hit.domain}:${hit.id}:${hit.kind}`;
        if (seen.has(key)) continue;
        seen.add(key);
        bucket.hits.push(hit);
      }
    }
  }

  const groups = [...byDomain.values()].sort(
    (a, b) => domainRank(a.domain) - domainRank(b.domain),
  );
  return { groups, unavailableDomains: [...unavailable].sort(), failedServices: failed.sort() };
}

function domainRank(domain: string): number {
  const index = GLOBAL_SEARCH_DOMAIN_ORDER.indexOf(domain);
  return index < 0 ? GLOBAL_SEARCH_DOMAIN_ORDER.length : index;
}

/** 命中总数（跨域）。 */
export function countHits(groups: GlobalSearchGroup[]): number {
  return groups.reduce((sum, group) => sum + group.hits.length, 0);
}

/** 供界面显示的「一条命中的副标题」：后端给了 secondary 就用它，否则退回主标识。 */
export function hitSubtitle(hit: GlobalSearchHit): string {
  return hit.secondary?.trim() ? hit.secondary : hit.kind;
}
