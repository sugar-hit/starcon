/**
 * 1.2.1.2 搜索结果分组 —— 前端模型（纯函数，可单测）。
 *
 * <p>1.2.1.1 已经把「命中集」准备好了（跨服务并发 + 合并）。本模块在它之上做三件事：
 *
 * <ul>
 *   <li><b>按业务域分组</b>：每组带**条数**与**数据范围**标注；</li>
 *   <li><b>只看某一组</b>：纯前端过滤，不重新发请求、不改上游契约；</li>
 *   <li><b>无权限结果计数提示</b>：因权限未返回的**业务域**数与名字，
 *       并说清「命中的具体条数未返回」。</li>
 * </ul>
 *
 * <p><b>三条不可混淆的口径</b>（蓝图原文 + 上游 1.2.1.1 的约定）：
 *
 * <ol>
 *   <li><b>没权限的域整组不返回</b>，不是返回后再置灰 —— 所以界面里看不到的组，
 *       只能在「无权限」提示里点名，不能画一个空壳。</li>
 *   <li><b>没权限 ≠ 没有数据 ≠ 服务挂了</b>：三件事分开说。</li>
 *   <li><b>组计数与清单同源</b>：计数就是这一组**实际渲染**的命中条数，
 *       不另算一套（否则组头写着 8、清单里只有 5 条）。</li>
 * </ol>
 */

/** 一条命中（与 1.2.1.1 的模型同形）。 */
import { domainLabel, scopeLabel, type GlobalSearchHit } from './global-search-model';

/** 被权限抑制的子域（后端给的归属业务域 + 中文名）。 */
export interface SuppressedDomain {
  domain: string;
  label: string;
  group: string;
  groupLabel: string;
}

/** 合并后的检索结果（1.2.1.1 的合并产物）。 */
export interface MergedSearchResult {
  groups: {
    domain: string;
    label: string;
    hits: GlobalSearchHit[];
    dataScope: string;
    dataScopeNote: string;
  }[];
  unavailableDomains: string[];
  failedServices: string[];
  suppressedDomains: SuppressedDomain[];
}

/** 一个业务域在分组视图里的样子。 */
export interface SearchGroupView {
  domain: string;
  label: string;
  hits: GlobalSearchHit[];
  /** 组计数：与清单**同源**（就是这一组实际渲染的命中条数）。 */
  count: number;
  /** 该组实际生效的数据范围（ALL / DEPARTMENT / SELF / TENANT）。 */
  dataScope: string;
  /** 数据范围的短标签（全范围 / 本部门 / 仅本人 / 租户级）。 */
  dataScopeLabel: string;
  /** 数据范围的说明文案（后端原文）。 */
  dataScopeNote: string;
  /** 这一组里因权限未返回的子域（如商品组里的 SKU）。 */
  suppressedParts: string[];
}

/** 因权限未返回的**业务域**（子域按归属业务域归并后）。 */
export interface SuppressedGroupView {
  group: string;
  label: string;
  parts: string[];
}

/** 分组视图整体。 */
export interface SearchGroupedView {
  groups: SearchGroupView[];
  /** 全部组的命中总数。 */
  totalHits: number;
  /** 因权限未返回的业务域（整组都没有返回）。 */
  suppressedGroups: SuppressedGroupView[];
  /** 因权限未返回的子域条数（同一业务域下可能有多个子域）。 */
  suppressedDomainCount: number;
  /** 本次没响应的服务（服务挂了 ≠ 没权限）。 */
  failedServices: string[];
}

/** 空视图：还没检索、或关键词太短时用。 */
export function emptyGroupedView(): SearchGroupedView {
  return {
    groups: [],
    totalHits: 0,
    suppressedGroups: [],
    suppressedDomainCount: 0,
    failedServices: [],
  };
}

/**
 * 把合并结果整理成分组视图。
 *
 * <p>被权限抑制的子域按**归属业务域**归并：前端说的是「N 个业务域因权限未返回」，
 * 而不是「N 个 SKU / SPU 子域」—— 后者对使用者没有意义。
 */
export function buildGroupedView(merged: MergedSearchResult): SearchGroupedView {
  const suppressedByGroup = new Map<string, SuppressedGroupView>();
  for (const item of merged.suppressedDomains ?? []) {
    const key = item.group || item.domain;
    const existing = suppressedByGroup.get(key);
    if (existing) {
      if (!existing.parts.includes(item.label)) existing.parts.push(item.label);
      continue;
    }
    suppressedByGroup.set(key, {
      group: key,
      label: item.groupLabel || item.label || domainLabel(key),
      parts: [item.label],
    });
  }

  const groups = (merged.groups ?? []).map((group) => {
    const hits = group.hits ?? [];
    const suppressed = suppressedByGroup.get(group.domain);
    return {
      domain: group.domain,
      label: group.label || domainLabel(group.domain),
      hits,
      // 组计数与清单同源：就是这一组实际渲染的条数。
      count: hits.length,
      dataScope: group.dataScope,
      dataScopeLabel: scopeLabel(group.dataScope),
      dataScopeNote: group.dataScopeNote,
      suppressedParts: suppressed?.parts ?? [],
    };
  });

  return {
    groups,
    totalHits: groups.reduce((sum, group) => sum + group.count, 0),
    suppressedGroups: [...suppressedByGroup.values()],
    suppressedDomainCount: (merged.suppressedDomains ?? []).length,
    failedServices: merged.failedServices ?? [],
  };
}

/**
 * 「只看某一组」——纯前端过滤。
 *
 * <p>传 {@code null} 表示看全部。过滤**不重新发请求**：关键词与范围都不变，
 * 只是把不看的组从视图里摘掉。
 */
export function filterGroups(
  groups: SearchGroupView[],
  selected: string | null,
): SearchGroupView[] {
  if (!selected) return groups;
  return groups.filter((group) => group.domain === selected);
}

/** 分组筛选条的选项：全部 + 每一组（计数与清单同源）。 */
export function groupOptions(groups: SearchGroupView[]): {
  value: string;
  label: string;
  count: number;
}[] {
  return [
    { value: 'ALL', label: '全部', count: groups.reduce((sum, group) => sum + group.count, 0) },
    ...groups.map((group) => ({ value: group.domain, label: group.label, count: group.count })),
  ];
}

/**
 * 状态行：把「命中」「没权限」「服务挂了」分开说。
 *
 * <p>没权限的域数报的是**业务域**数（不是子域数）：使用者看到的是「商品、订单」，
 * 不是「PRODUCT_SKU、PRODUCT_SPU」。
 */
export function describeGroupedOutcome(view: SearchGroupedView): string {
  const parts: string[] = [];
  if (view.totalHits) parts.push(`命中 ${view.totalHits} 条`);
  if (view.suppressedGroups.length) {
    const names = view.suppressedGroups.map((group) => group.label).join('、');
    parts.push(`${view.suppressedGroups.length} 个业务域无权限（${names}）`);
  }
  if (view.failedServices.length) parts.push(`${view.failedServices.join('、')} 服务未响应`);
  if (!parts.length) return '没有匹配的记录';
  return parts.join(' · ');
}

/**
 * 无权限结果的计数提示。
 *
 * <p><b>只报域数，不报命中条数</b>：后端只报「哪些域因权限没返回」，
 * 被抑制的命中条数**没有**返回（要报它就得绕过数据范围去数，那是越权）。
 * 所以这里明确写清「具体条数未返回」，而不是编一个数字出来。
 */
export function permissionNotice(view: SearchGroupedView): string {
  if (!view.suppressedGroups.length) return '';
  const names = view.suppressedGroups
    .map((group) =>
      group.parts.length > 1 ? `${group.label}（${group.parts.join('、')}）` : group.label,
    )
    .join('、');
  return `${view.suppressedGroups.length} 个业务域因权限未返回结果：${names}。其中命中的具体条数未返回。`;
}

/** 组内的部分子域被抑制时的提示（整组被抑制的组根本不会出现）。 */
export function suppressedPartsNotice(group: SearchGroupView): string {
  if (!group.suppressedParts.length) return '';
  return `「${group.label}」中的${group.suppressedParts.join('、')}因权限未返回。`;
}
