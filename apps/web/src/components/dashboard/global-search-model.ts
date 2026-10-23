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

/**
 * 一个业务域的命中集合。
 *
 * <p>{@code dataScope} 是这一组**实际生效**的数据范围（ALL / DEPARTMENT / SELF / TENANT），
 * {@code dataScopeNote} 是它的说明文案 —— 界面靠它们标注「这一组在你的数据范围内吗」。
 * 两个字段由后端给出（各服务按自己那张表的归属维度判定），前端不另算一套。
 */
export interface GlobalSearchGroup {
  domain: string;
  label: string;
  hits: GlobalSearchHit[];
  dataScope: string;
  dataScopeNote: string;
}

/**
 * 因**权限**未返回的一个子域。
 *
 * <p>域码与响应里的 {@code unavailableDomains} 一致（catalog 侧是 {@code PRODUCT_SKU} /
 * {@code PRODUCT_SPU} 这种带域内实体前缀的码），额外带上它归属的业务域 ——
 * 前端要按**业务域**计数（「N 个业务域因权限未返回」）并把提示挂到对应的组上，
 * 光有一串子域码是算不出业务域数的。
 */
export interface SuppressedDomain {
  domain: string;
  label: string;
  group: string;
  groupLabel: string;
}

/** 一个服务的检索响应。 */
export interface GlobalSearchResponse {
  keyword: string;
  matchMode: string;
  minKeywordLength: number;
  groups: GlobalSearchGroup[];
  unavailableDomains: string[];
  suppressedDomains: SuppressedDomain[];
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
 *
 * <p><b>被权限抑制的子域要带归属业务域</b>（`suppressedDomains`）：catalog 侧的域码是
 * `PRODUCT_SKU` / `PRODUCT_SPU` 这种带域内实体前缀的码，光有一串码算不出「几个业务域没返回」。
 */
export function mergeServiceResults(outcomes: ServiceSearchOutcome[]): {
  groups: GlobalSearchGroup[];
  unavailableDomains: string[];
  failedServices: string[];
  suppressedDomains: SuppressedDomain[];
} {
  const byDomain = new Map<string, GlobalSearchGroup>();
  const unavailable = new Set<string>();
  const failed: string[] = [];
  const seen = new Set<string>();
  const suppressed = new Map<string, SuppressedDomain>();

  for (const outcome of outcomes) {
    if (!outcome.response) {
      if (outcome.error) failed.push(outcome.service);
      continue;
    }
    for (const domain of outcome.response.unavailableDomains ?? []) unavailable.add(domain);
    for (const item of outcome.response.suppressedDomains ?? []) {
      suppressed.set(`${item.group}:${item.domain}`, item);
    }
    for (const group of outcome.response.groups ?? []) {
      const existing = byDomain.get(group.domain);
      if (!existing) {
        byDomain.set(group.domain, {
          domain: group.domain,
          label: group.label,
          hits: [],
          dataScope: group.dataScope,
          dataScopeNote: group.dataScopeNote,
        });
      }
      const bucket = byDomain.get(group.domain)!;
      // 同一域被多个服务返回时取**更严**的一侧（每个域实际只有一个归属服务，这里是防御）。
      const winner = narrowerScope(bucket.dataScope, group.dataScope);
      if (winner !== bucket.dataScope) bucket.dataScopeNote = group.dataScopeNote;
      bucket.dataScope = winner;
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
  return {
    groups,
    unavailableDomains: [...unavailable].sort(),
    failedServices: failed.sort(),
    suppressedDomains: [...suppressed.values()].sort(
      (a, b) => a.group.localeCompare(b.group) || a.domain.localeCompare(b.domain),
    ),
  };
}

/**
 * 数据范围的中文短标签。
 *
 * <p>未知取值原样回显 —— 不假装它属于某个已知口径（界面上写「范围未声明」比写错强）。
 */
export function scopeLabel(scope: string | null | undefined): string {
  switch (scope) {
    case 'ALL':
      return '全范围';
    case 'DEPARTMENT':
      return '本部门';
    case 'SELF':
      return '仅本人';
    case 'TENANT':
      return '租户级';
    default:
      return scope?.trim() ? scope : '范围未声明';
  }
}

/** 范围的收窄程度：数字越大越窄（ALL / TENANT 都不收窄）。 */
export function scopeRank(scope: string | null | undefined): number {
  switch (scope) {
    case 'SELF':
      return 3;
    case 'DEPARTMENT':
      return 2;
    case 'ALL':
    case 'TENANT':
      return 1;
    default:
      return 0;
  }
}

/** 两个范围取**更严**的一侧。 */
export function narrowerScope(left: string | null | undefined, right: string | null | undefined) {
  return scopeRank(left) >= scopeRank(right) ? (left ?? '') : (right ?? '');
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
