import { ApiError, api } from '../inventory/api';

/**
 * 5.1.2.1 优先级与冲突检测前端模型。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：定义多张价目表的适用优先级，保存时检测冲突（同 SKU + 同店铺 + 同站点 + 同时段生效的多个价目），冲突即拒绝并列出冲突明细。
 *
 * **输入**：适用范围配置（法人/店铺/站点）；现有生效价目集合。
 *
 * **输出**：冲突检测结果；最终的生效价目解析规则。
 *
 * **关系**：上游 = 5.1.1.1；下游 = 5.3.1.1、5.4 调价。**这是价格正确性的守门员**：允许多价目但必须能唯一解析出"这个 SKU 在这个店铺此刻该卖多少"。
 *
 * </blockquote>
 *
 * <h2>🔴 数字越小 = 优先级越高</h2>
 *
 * <p>1 是最高优先级（与后端 {@code PriceListPriorityRules} 同一口径）。
 * 同一上下文里数字相同 ⇒ 不可唯一解析 ⇒ 保存被拒、解析报错，
 * **绝不**由前端替用户随机挑一条。
 *
 * <h2>🔴 「同 SKU」维度未覆盖</h2>
 *
 * <p>价目项表 {@code price_list_item} 属 **5.1.1.2**，尚未交付 ⇒ 本模块只做**价目级**检测。
 * 界面必须把这件事显示出来（{@link skuCoverage} / {@link DimensionCoverage.covered}），
 * 不许显示成「没有冲突」。
 */

/** 优先级规则的上下文类型。 */
export type PriorityScopeType = 'GLOBAL' | 'LEGAL_ENTITY' | 'SHOP' | 'SITE';

/** 冲突检测的维度。 */
export type ConflictDimension = 'SKU' | 'SHOP' | 'SITE' | 'LEGAL_ENTITY' | 'TIME' | 'PRIORITY';

/** 价目表被排除在解析之外的原因。 */
export type ExclusionReason = 'NO_PRIORITY' | 'NOT_EFFECTIVE' | 'DISABLED';

/** 能力声明里的一条口径说明。 */
export interface PriorityNote {
  title: string;
  content: string;
}

/** 一个维度的覆盖说明。🔴 covered=false 时 detail 必须写清原因。 */
export interface DimensionCoverage {
  dimension: ConflictDimension;
  label: string;
  covered: boolean;
  detail: string;
}

/** 能力声明。 */
export interface PriceListPriorityCapabilities {
  scopeTypes: PriorityScopeType[];
  notes: PriorityNote[];
  dimensionCoverage: DimensionCoverage[];
  prioritySemantics: string;
  tieNote: string;
  scopeNote: string;
  upstreamNote: string;
  downstreamNote: string;
  skuNote: string;
}

/** 一条优先级规则（输入）。 */
export interface PriorityRuleInput {
  scopeType: PriorityScopeType;
  scopeRef: string;
  priority: number;
  note: string;
}

/** 保存优先级规则的请求体。 */
export interface PriorityRulesInput {
  rules: PriorityRuleInput[];
}

/** 一条优先级规则（读）。 */
export interface PriorityRuleView {
  id: string;
  priceListId: string;
  priceListCode: string;
  priceListName: string;
  scopeType: PriorityScopeType;
  scopeTypeLabel: string | null;
  scopeRef: string;
  priority: number;
  note: string;
  createdAt: string;
  updatedAt: string;
}

/** 一条冲突明细。 */
export interface ConflictDetail {
  dimensions: ConflictDimension[];
  leftPriceListId: string;
  leftPriceListCode: string;
  leftPriceListName: string;
  rightPriceListId: string;
  rightPriceListCode: string;
  rightPriceListName: string;
  shopCode: string | null;
  siteCode: string | null;
  legalEntityCode: string | null;
  overlapFrom: string;
  overlapTo: string | null;
  leftPriority: number | null;
  rightPriority: number | null;
  reason: string;
  blocking: boolean;
}

/** 冲突检测结果。 */
export interface ConflictReport {
  priceListId: string | null;
  priceListCode: string | null;
  conflictCount: number;
  blockingCount: number;
  conflicts: ConflictDetail[];
  dimensionCoverage: DimensionCoverage[];
  skuDimensionNote: string;
  rankedPriceListCount: number;
  truncated: boolean;
  pairCount: number;
  note: string;
}

/** 全租户冲突概览。 */
export interface TenantConflictOverview {
  rankedPriceListCount: number;
  priceListCount: number;
  conflictPairCount: number;
  blockingPairCount: number;
  conflicts: ConflictDetail[];
  dimensionCoverage: DimensionCoverage[];
  skuDimensionNote: string;
  note: string;
}

/** 参与解析的候选价目表。 */
export interface ResolutionCandidate {
  priceListId: string;
  priceListCode: string;
  priceListName: string;
  currency: string;
  priority: number;
  ruleScopeType: PriorityScopeType;
  ruleScopeLabel: string;
  ruleScopeRef: string;
  validFrom: string;
  validTo: string | null;
  selected: boolean;
}

/** 被排除的价目表。 */
export interface ExcludedPriceList {
  priceListId: string;
  priceListCode: string;
  priceListName: string;
  reason: ExclusionReason;
  reasonLabel: string;
  detail: string;
}

/** SKU 维度的覆盖情况。🔴 covered=false 时 reason 说明为什么。 */
export interface SkuCoverage {
  covered: boolean;
  sku: string | null;
  reason: string;
}

/** 解析命中的价目表。 */
export interface ResolvedPriceList {
  id: string;
  code: string;
  name: string;
  currency: string;
}

/** 解析依据：命中了哪条优先级规则。 */
export interface ResolutionBasis {
  priority: number;
  ruleId: string | null;
  ruleScopeType: PriorityScopeType;
  ruleScopeLabel: string;
  ruleScopeRef: string;
  explanation: string;
}

/** 解析结果。 */
export interface Resolution {
  resolved: boolean;
  reason: string;
  priceList: ResolvedPriceList | null;
  basis: ResolutionBasis | null;
  candidates: ResolutionCandidate[];
  excluded: ExcludedPriceList[];
  skuCoverage: SkuCoverage;
  shopCode: string;
  siteCode: string | null;
  legalEntityCode: string | null;
  at: string;
}

/** 保存优先级规则的结果。 */
export interface PriorityRulesSaved {
  priceListId: string;
  priceListCode: string;
  rules: PriorityRuleView[];
  report: ConflictReport;
}

/** 表单校验错误。 */
export interface ValidationError {
  field: string;
  message: string;
}

/** 解析请求。 */
export interface ResolveInput {
  shopCode: string;
  siteCode?: string | null;
  sku?: string | null;
  at?: string | null;
}

// ================================================================ 词表

const SCOPE_LABELS: Record<PriorityScopeType, string> = {
  GLOBAL: '全局',
  LEGAL_ENTITY: '法人',
  SHOP: '店铺',
  SITE: '站点',
};

const DIMENSION_LABELS: Record<ConflictDimension, string> = {
  SKU: '同 SKU',
  SHOP: '同店铺',
  SITE: '同站点',
  LEGAL_ENTITY: '同法人',
  TIME: '同时段生效',
  PRIORITY: '同优先级',
};

/** 上下文类型的显示名。 */
export function priorityScopeLabel(type: PriorityScopeType | null | undefined): string {
  if (!type) return '未知';
  return SCOPE_LABELS[type] ?? type;
}

/** 维度的显示名。 */
export function dimensionLabel(dimension: ConflictDimension | null | undefined): string {
  if (!dimension) return '未知';
  return DIMENSION_LABELS[dimension] ?? dimension;
}

/** 上下文取值的占位提示（表单里提示该填什么）。 */
export function scopeRefPlaceholder(type: PriorityScopeType): string {
  switch (type) {
    case 'GLOBAL':
      return '*';
    case 'LEGAL_ENTITY':
      return '法人编码';
    case 'SHOP':
      return '店铺编码';
    case 'SITE':
      return '站点码';
  }
}

/** 🔴 优先级的显示：数字越小优先级越高（1 最高）。 */
export function priorityText(priority: number | null | undefined): string {
  if (priority === null || priority === undefined) return '未配置优先级';
  return `优先级 ${priority}`;
}

/** 一条空规则（新增时用）。 */
export function emptyPriorityRule(type: PriorityScopeType = 'GLOBAL'): PriorityRuleInput {
  return {
    scopeType: type,
    scopeRef: type === 'GLOBAL' ? '*' : '',
    priority: 10,
    note: '',
  };
}

/** 🔴 校验一套优先级规则（与服务层同一套口径）。 */
export function validatePriorityRules(rules: PriorityRuleInput[]): ValidationError | null {
  if (!rules.length) {
    return {
      field: 'rules',
      message: '至少要有一条优先级规则 —— 没有优先级的价目表不参与解析',
    };
  }
  let globals = 0;
  const seen = new Set<string>();
  for (const rule of rules) {
    if (rule.scopeType === 'GLOBAL') globals += 1;
    const ref = rule.scopeType === 'GLOBAL' ? '*' : rule.scopeRef.trim();
    if (rule.scopeType !== 'GLOBAL' && !ref) {
      return {
        field: 'rules',
        message: `${priorityScopeLabel(rule.scopeType)}优先级规则必须给编码（法人编码 / 店铺编码 / 站点码）`,
      };
    }
    if (!Number.isInteger(rule.priority) || rule.priority < 0 || rule.priority > 9999) {
      return { field: 'rules', message: '优先级必须是 0 到 9999 之间的整数（数字越小优先级越高）' };
    }
    // 🔴 先说「只能有一条全局」，再说「重复登记」—— 两条全局规则是前者，不是后者。
    if (globals > 1) {
      return { field: 'rules', message: '只能有一条全局（GLOBAL）优先级规则' };
    }
    const key = `${rule.scopeType}|${ref.toUpperCase()}`;
    if (seen.has(key)) {
      return {
        field: 'rules',
        message: `优先级规则重复：${priorityScopeLabel(rule.scopeType)} ${ref}（同一张价目表里只登记一次）`,
      };
    }
    seen.add(key);
  }
  if (globals === 0) {
    return {
      field: 'rules',
      message: '必须有一条全局（GLOBAL）优先级规则 —— 没有它，价目表在某些上下文里就没有优先级',
    };
  }
  return null;
}

/** 提交前的归一化：GLOBAL 的取值固定成 {@code *}，取值去空白。 */
export function toPriorityRulesPayload(rules: PriorityRuleInput[]): PriorityRulesInput {
  return {
    rules: rules.map((rule) => ({
      scopeType: rule.scopeType,
      scopeRef: rule.scopeType === 'GLOBAL' ? '*' : rule.scopeRef.trim(),
      priority: rule.priority,
      note: rule.note.trim(),
    })),
  };
}

/** 🔴 生效区间的重叠文本（半开区间 [起点, 终点)）。 */
export function overlapText(
  from: string | null | undefined,
  to: string | null | undefined,
): string {
  if (!from) return '未知';
  return `[${formatInstant(from)}, ${to ? formatInstant(to) : '长期有效'})`;
}

/** 一瞬间的可读文本。 */
export function formatInstant(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${parsed.getFullYear()}-${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())} ${pad(
    parsed.getHours(),
  )}:${pad(parsed.getMinutes())}`;
}

/** 一条冲突的标题（哪两张表）。 */
export function conflictTitle(detail: ConflictDetail): string {
  return `${detail.leftPriceListCode} ↔ ${detail.rightPriceListCode}`;
}

/** 一条冲突的上下文文本（哪个店铺 / 哪个站点 / 哪个法人）。 */
export function conflictContext(detail: ConflictDetail): string {
  const parts: string[] = [];
  if (detail.shopCode) parts.push(`店铺 ${detail.shopCode}`);
  if (detail.siteCode) parts.push(`站点 ${detail.siteCode}`);
  if (detail.legalEntityCode) parts.push(`法人 ${detail.legalEntityCode}`);
  return parts.length ? parts.join(' / ') : '全租户（未限定范围）';
}

// ================================================================ 端点

export function fetchPriorityCapabilities(): Promise<PriceListPriorityCapabilities> {
  return api<PriceListPriorityCapabilities>('catalog/price-lists/priority/capabilities');
}

export function fetchPriorityRules(): Promise<PriorityRuleView[]> {
  return api<PriorityRuleView[]>('catalog/price-lists/priority/rules');
}

export function fetchPriceListPriorityRules(priceListId: string): Promise<PriorityRuleView[]> {
  return api<PriorityRuleView[]>(`catalog/price-lists/lists/${priceListId}/priority`);
}

export function savePriceListPriorityRules(
  priceListId: string,
  rules: PriorityRuleInput[],
): Promise<PriorityRulesSaved> {
  return api<PriorityRulesSaved>(
    `catalog/price-lists/lists/${priceListId}/priority`,
    'POST',
    toPriorityRulesPayload(rules),
  );
}

export function fetchPriceListConflicts(priceListId: string): Promise<ConflictReport> {
  return api<ConflictReport>(`catalog/price-lists/lists/${priceListId}/conflicts`);
}

export function fetchPriorityConflicts(): Promise<TenantConflictOverview> {
  return api<TenantConflictOverview>('catalog/price-lists/conflicts');
}

export function resolvePriceList(input: ResolveInput): Promise<Resolution> {
  return api<Resolution>('catalog/price-lists/resolve', 'POST', {
    shopCode: input.shopCode.trim(),
    siteCode: input.siteCode?.trim() || null,
    sku: input.sku?.trim() || null,
    at: input.at || null,
  });
}

// ================================================================ 错误里的结构化明细

/**
 * 🔴 从 409 的 ProblemDetail 里取出**冲突明细**。
 *
 * <p>保存被拒时后端把整份冲突报告挂在 `conflicts` 属性上（与 3.1.3.2 组合唯一性、
 * 3.6.2.1 报价优先级歧义同一手法）。只回一句 message 的话，界面会退化成
 * 「有冲突」三个字，运营没法按条改。
 *
 * @return 冲突明细；不是冲突类错误时返回 null
 */
export function conflictDetailsFromError(error: unknown): ConflictDetail[] | null {
  if (!(error instanceof ApiError) || error.status !== 409) return null;
  const body = error.body as { conflicts?: ConflictDetail[] } | null;
  if (!body || !Array.isArray(body.conflicts)) return null;
  return body.conflicts;
}

/**
 * 🔴 从 409 的 ProblemDetail 里取出**并列候选**（解析不可解析时用）。
 *
 * <p>蓝图 5.1.1.2 关系句：「同优先级多条有效价目必须报错让用户选」——
 * 前半句是「报错」，后半句是「让用户选」⇒ 候选必须**全部**退回给界面。
 */
export function ambiguityCandidatesFromError(error: unknown): ResolutionCandidate[] | null {
  if (!(error instanceof ApiError) || error.status !== 409) return null;
  const body = error.body as { candidates?: ResolutionCandidate[] } | null;
  if (!body || !Array.isArray(body.candidates)) return null;
  return body.candidates;
}
