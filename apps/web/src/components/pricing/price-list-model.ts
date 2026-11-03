import { api } from '../inventory/api';

/**
 * 5.1.1.1 价目表列表前端模型。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：管理价目表（如"北美自发货零售价"），每张表绑定一个币种、适用法人/店铺/站点、生效区间，展示表内价目项数量与最近更新时间。
 *
 * **输入**：价目表增删改；币种与适用范围选择；生效区间设置。
 *
 * **输出**：价目表记录 + 覆盖范围摘要。
 *
 * **关系**：上游 = —；下游 = 5.1.1.2 价目项、5.3.2.1 定价模板。**一价目一币种**是硬约束——混币种的价目表会让利润计算失效。
 *
 * </blockquote>
 *
 * <h2>🔴 一价目一币种</h2>
 *
 * <p>表单上只有<b>一个</b>币种输入框（{@link PriceListInput.currency}），没有「多币种」
 * 这种形状。{@link validatePriceList} 在提交前就按同一条纪律拦一次（三个大写字母），
 * 但**真正的闸在数据库**：命名约束 {@code price_list_one_currency}。
 *
 * <h2>🔴 生效区间是半开区间</h2>
 *
 * <p>[起点, 终点)：起点含、终点不含。{@link intervalText} 把这条约定写在界面上，
 * 免得运营以为「终点那天还生效」。
 *
 * <h2>🔴 null 绝不渲染成 0</h2>
 *
 * <p>「取不到」与「确实是 0」是两件事：价目项数量没维护 ≠ 价目项数量是 0。
 * 本模块里数量是**真实计数**（未交付价目项模块时恒为 0），所以直接显示数字，
 * 但生效状态、覆盖范围这些「没有值」的场合一律显示可读文案而不是 0。
 */

/** 适用范围类型。与后端 {@code ScopeType} 逐字对应。 */
export type ScopeType = 'LEGAL_ENTITY' | 'SHOP' | 'SITE';

/** 能力声明里的一条口径说明。 */
export interface CapabilityNote {
  title: string;
  content: string;
}

/** 能力声明。页面顶部固定显示它。 */
export interface PriceListCapabilities {
  scopeTypes: ScopeType[];
  notes: CapabilityNote[];
  currencyNote: string;
  validityNote: string;
  itemCountNote: string;
  scopeNote: string;
  upstreamNote: string;
  downstreamNote: string;
  conflictNote: string;
}

/** 一个法人（identity 的 legal_entity）。 */
export interface PriceListLegalEntity {
  id: string;
  code: string;
  name: string;
  baseCurrency: string | null;
  status: string;
}

/** 一个店铺（channel 的 shop）。 */
export interface PriceListShop {
  code: string;
  name: string;
  platformCode: string | null;
  legalEntityCode: string | null;
  legalEntityName: string | null;
  status: string;
  siteCodes: string[];
}

/** 一个站点（channel 的 marketplace）。 */
export interface PriceListSite {
  platformCode: string;
  platformName: string;
  siteCode: string;
  siteName: string;
  countryCode: string | null;
  defaultCurrency: string | null;
}

/** 适用范围目录。 */
export interface ScopeDirectory {
  legalEntities: PriceListLegalEntity[];
  shops: PriceListShop[];
  sites: PriceListSite[];
  sourceNote: string;
}

/** 覆盖范围摘要。 */
export interface CoverageSummary {
  scopeTotal: number;
  legalEntityCount: number;
  shopCount: number;
  siteCount: number;
  appliesToWholeTenant: boolean;
  text: string;
}

/** 一条适用对象（读）。 */
export interface ScopeView {
  id: string;
  type: ScopeType;
  typeLabel: string;
  ref: string;
  name: string;
  platformCode: string | null;
}

/** 价目表列表的一行。 */
export interface PriceListSummary {
  id: string;
  code: string;
  name: string;
  currency: string;
  validFrom: string;
  validTo: string | null;
  enabled: boolean;
  itemCount: number;
  coverage: CoverageSummary;
  effectiveNow: boolean;
  createdBy: string | null;
  updatedAt: string;
}

/** 价目表详情。 */
export interface PriceListDetail {
  summary: PriceListSummary;
  description: string;
  currency: string;
  validFrom: string;
  validTo: string | null;
  enabled: boolean;
  note: string;
  version: number;
  scopes: ScopeView[];
  coverage: CoverageSummary;
  createdAt: string;
  updatedAt: string;
}

/** 价目表列表（分页）。 */
export interface PriceListPage {
  items: PriceListSummary[];
  page: number;
  size: number;
  total: number;
}

/** 一条适用对象（输入）。 */
export interface ScopeInput {
  type: ScopeType;
  ref: string;
  platformCode: string | null;
}

/** 价目表定义（新建与修改共用）。 */
export interface PriceListInput {
  code: string;
  name: string;
  description: string;
  currency: string;
  validFrom: string;
  validTo: string | null;
  enabled: boolean;
  note: string;
  scopes: ScopeInput[];
  version: number | null;
}

/** 表单校验错误。 */
export interface ValidationError {
  field: string;
  message: string;
}

/** 按币种分布的一档。 */
export interface CurrencyCoverage {
  currency: string;
  listCount: number;
}

/** 按范围类型分布的一档。 */
export interface ScopeTypeCoverage {
  type: ScopeType;
  typeLabel: string;
  listCount: number;
}

/** 覆盖范围摘要里的一张价目表。 */
export interface CoverageEntry {
  id: string;
  code: string;
  name: string;
  currency: string;
  enabled: boolean;
  effectiveNow: boolean;
  itemCount: number;
  coverage: CoverageSummary;
}

/** 模块级覆盖范围摘要。 */
export interface CoverageOverview {
  listCount: number;
  enabledCount: number;
  effectiveNowCount: number;
  totalItemCount: number;
  currencies: CurrencyCoverage[];
  scopeTypes: ScopeTypeCoverage[];
  lists: CoverageEntry[];
  note: string;
}

// ================================================================ 纯函数

/** 适用范围类型的中文名。 */
export function scopeTypeLabel(type: ScopeType | null | undefined): string {
  if (type === 'LEGAL_ENTITY') return '法人';
  if (type === 'SHOP') return '店铺';
  if (type === 'SITE') return '站点';
  return '未指定';
}

/**
 * 币种归一化：去空白 + 转大写。
 *
 * <p>🔴 混币种写法（{@code USD,CNY}）在这里就被拒 —— 表单上只有**一个**币种输入框。
 */
export function normalizeCurrency(raw: string | null | undefined): string {
  return (raw ?? '').trim().toUpperCase();
}

/** 币种形状是否合法（三个大写字母）。 */
export function isCurrencyShape(raw: string | null | undefined): boolean {
  return /^[A-Z]{3}$/.test(normalizeCurrency(raw));
}

/**
 * 目录里出现过的币种（法人本位币 + 站点默认币种），去重后按字母排。
 *
 * <p>🔴 这**不是**权威币种表（本仓没有 ISO 4217 字典）：它只是把上游主数据里
 * 真正用到的币种摆出来，省得运营去猜；表单仍然接受任意三个大写字母。
 */
export function currencyHints(directory: ScopeDirectory | null | undefined): string[] {
  if (!directory) return [];
  const found = new Set<string>();
  for (const entity of directory.legalEntities ?? []) {
    if (entity.baseCurrency) found.add(entity.baseCurrency.toUpperCase());
  }
  for (const site of directory.sites ?? []) {
    if (site.defaultCurrency) found.add(site.defaultCurrency.toUpperCase());
  }
  return [...found].sort();
}

/**
 * 生效区间的文字描述（半开区间）。
 *
 * <p>🔴 终点为空 = 长期有效，**不是**「没填」。
 */
export function intervalText(
  validFrom: string | null | undefined,
  validTo: string | null | undefined,
): string {
  const from = validFrom ? formatInstant(validFrom) : '（未设置）';
  if (!validTo) return `${from} → 长期有效`;
  return `${from} → ${formatInstant(validTo)}（不含）`;
}

/** 时刻 → 本地可读文本（保留时区偏移，跨时区对账时要看得出是哪个时区）。 */
export function formatInstant(value: string | null | undefined): string {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const pad = (n: number) => String(n).padStart(2, '0');
  const offset = -date.getTimezoneOffset();
  const sign = offset >= 0 ? '+' : '-';
  const absolute = Math.abs(offset);
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}` +
    ` UTC${sign}${pad(Math.floor(absolute / 60))}:${pad(absolute % 60)}`
  );
}

/** 表内价目项数量的文字（真实计数，不是「取不到」）。 */
export function itemCountText(count: number | null | undefined): string {
  if (count === null || count === undefined) return '未维护';
  return `${count} 个价目项`;
}

/**
 * ISO 时刻 → {@code <input type="datetime-local">} 的本地值（{@code YYYY-MM-DDTHH:mm}）。
 *
 * <p>🔴 半开区间的两端都是**时刻**：界面用本地时间录入，提交时换算成带偏移的 ISO 串，
 * 服务端按 {@code timestamptz} 落库 —— 不在浏览器里做时区猜测。
 */
export function toLocalInput(value: string | null | undefined): string {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  const pad = (n: number) => String(n).padStart(2, '0');
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}`
  );
}

/**
 * {@code <input type="datetime-local">} 的本地值 → ISO 时刻。
 *
 * @return 空值返回 null（= 长期有效）；认不出来的返回 null
 */
export function fromLocalInput(value: string | null | undefined): string | null {
  if (!value) return null;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  return date.toISOString();
}

/** 生效状态的徽标语气。 */
export function effectiveTone(effectiveNow: boolean | null | undefined): 'success' | 'neutral' {
  return effectiveNow ? 'success' : 'neutral';
}

/** 生效状态的文字。 */
export function effectiveText(effectiveNow: boolean | null | undefined): string {
  if (effectiveNow === null || effectiveNow === undefined) return '生效状态未知';
  return effectiveNow ? '此刻生效' : '此刻不生效';
}

/** 覆盖范围摘要的文字（服务端已算好，前端不重算）。 */
export function coverageText(coverage: CoverageSummary | null | undefined): string {
  if (!coverage) return '—';
  return coverage.text;
}

/**
 * 校验价目表定义。
 *
 * <p>🔴 一价目一币种：币种必须是**一个**三位大写字母的币种。
 */
export function validatePriceList(input: PriceListInput): ValidationError | null {
  if (!input.code.trim()) return { field: 'code', message: '价目表编码必填' };
  if (!/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(input.code.trim())) {
    return { field: 'code', message: '编码只能包含字母、数字、短横线与下划线（最多 64 位）' };
  }
  if (!input.name.trim()) return { field: 'name', message: '价目表名称必填' };
  if (!isCurrencyShape(input.currency)) {
    return {
      field: 'currency',
      message: '币种必须是三个大写字母 —— 一张价目表只能有一个币种',
    };
  }
  if (!input.validFrom) return { field: 'validFrom', message: '生效起点必填' };
  if (input.validTo) {
    const from = new Date(input.validFrom).getTime();
    const to = new Date(input.validTo).getTime();
    if (Number.isNaN(from) || Number.isNaN(to)) {
      return { field: 'validTo', message: '生效区间的时间格式认不出来' };
    }
    if (to <= from) {
      return { field: 'validTo', message: '生效终点必须晚于生效起点（区间是 [起点, 终点)）' };
    }
  }
  const seen = new Set<string>();
  for (const scope of input.scopes) {
    if (!scope.ref.trim()) return { field: 'scopes', message: '适用对象必须给编码' };
    if (scope.type === 'SITE' && !scope.platformCode) {
      return { field: 'scopes', message: '站点必须给平台码（站点一定挂在某个平台下）' };
    }
    if (scope.type === 'LEGAL_ENTITY' && scope.platformCode) {
      return { field: 'scopes', message: '法人没有平台 —— 平台码只属于店铺与站点' };
    }
    const key = `${scope.type}|${scope.ref.trim()}`;
    if (seen.has(key)) {
      return {
        field: 'scopes',
        message: `适用对象重复：${scopeTypeLabel(scope.type)} ${scope.ref}`,
      };
    }
    seen.add(key);
  }
  return null;
}

/** 空表单。 */
export function emptyPriceListInput(): PriceListInput {
  return {
    code: '',
    name: '',
    description: '',
    currency: 'USD',
    validFrom: new Date().toISOString(),
    validTo: null,
    enabled: true,
    note: '',
    scopes: [],
    version: null,
  };
}

/** 详情 → 表单（编辑时用）。 */
export function toPriceListInput(detail: PriceListDetail): PriceListInput {
  return {
    code: detail.summary.code,
    name: detail.summary.name,
    description: detail.description,
    currency: detail.currency,
    validFrom: detail.validFrom,
    validTo: detail.validTo,
    enabled: detail.enabled,
    note: detail.note,
    scopes: detail.scopes.map((scope) => ({
      type: scope.type,
      ref: scope.ref,
      platformCode: scope.platformCode,
    })),
    version: detail.version,
  };
}

/** 表单 → 提交载荷（币种归一化、时间补齐时区）。 */
export function toSubmitPayload(input: PriceListInput): PriceListInput {
  return {
    ...input,
    code: input.code.trim().toUpperCase(),
    name: input.name.trim(),
    currency: normalizeCurrency(input.currency),
    scopes: input.scopes.map((scope) => ({
      type: scope.type,
      ref: scope.ref.trim(),
      platformCode: scope.platformCode ? scope.platformCode.trim().toUpperCase() : null,
    })),
  };
}

/** 目录里某个类型的可选项（表单的适用范围选择器用）。 */
export function scopeOptions(
  directory: ScopeDirectory | null | undefined,
  type: ScopeType,
): { ref: string; name: string; platformCode: string | null }[] {
  if (!directory) return [];
  if (type === 'LEGAL_ENTITY') {
    return (directory.legalEntities ?? []).map((entity) => ({
      ref: entity.code,
      name: entity.name,
      platformCode: null,
    }));
  }
  if (type === 'SHOP') {
    return (directory.shops ?? []).map((shop) => ({
      ref: shop.code,
      name: shop.name,
      platformCode: shop.platformCode ?? null,
    }));
  }
  return (directory.sites ?? []).map((site) => ({
    ref: site.siteCode,
    name: site.siteName,
    platformCode: site.platformCode,
  }));
}

/** 覆盖范围摘要的统计（页面上那几个数字）。 */
export function coverageStats(overview: CoverageOverview | null | undefined): {
  label: string;
  value: number;
  hint: string;
}[] {
  if (!overview) return [];
  return [
    { label: '价目表', value: overview.listCount, hint: '本租户的价目表总数' },
    { label: '启用中', value: overview.enabledCount, hint: 'enabled = true 的价目表' },
    { label: '此刻生效', value: overview.effectiveNowCount, hint: '落在生效区间内的价目表' },
    { label: '价目项合计', value: overview.totalItemCount, hint: '各价目表内价目项数量之和' },
  ];
}

// ================================================================ 端点

export function fetchPriceListCapabilities(): Promise<PriceListCapabilities> {
  return api<PriceListCapabilities>('catalog/price-lists/capabilities');
}

export function fetchPriceListScopeDirectory(): Promise<ScopeDirectory> {
  return api<ScopeDirectory>('catalog/price-lists/scope-directory');
}

export function fetchPriceLists(
  query: {
    keyword?: string | undefined;
    currency?: string | null | undefined;
    scopeType?: ScopeType | null | undefined;
    scopeRef?: string | null | undefined;
    enabled?: boolean | null | undefined;
    effectiveNow?: boolean | null | undefined;
    page?: number | undefined;
    size?: number | undefined;
  } = {},
): Promise<PriceListPage> {
  const params = new URLSearchParams();
  if (query.keyword) params.set('keyword', query.keyword);
  if (query.currency) params.set('currency', query.currency);
  if (query.scopeType) params.set('scopeType', query.scopeType);
  if (query.scopeRef) params.set('scopeRef', query.scopeRef);
  if (query.enabled !== null && query.enabled !== undefined) {
    params.set('enabled', String(query.enabled));
  }
  if (query.effectiveNow !== null && query.effectiveNow !== undefined) {
    params.set('effectiveNow', String(query.effectiveNow));
  }
  params.set('page', String(query.page ?? 0));
  params.set('size', String(query.size ?? 20));
  return api<PriceListPage>(`catalog/price-lists/lists?${params.toString()}`);
}

export function fetchPriceList(id: string): Promise<PriceListDetail> {
  return api<PriceListDetail>(`catalog/price-lists/lists/${id}`);
}

export function createPriceList(input: PriceListInput): Promise<PriceListDetail> {
  return api<PriceListDetail>('catalog/price-lists/lists', 'POST', input);
}

export function updatePriceList(id: string, input: PriceListInput): Promise<PriceListDetail> {
  return api<PriceListDetail>(`catalog/price-lists/lists/${id}`, 'PUT', input);
}

export function deletePriceList(id: string): Promise<void> {
  return api<void>(`catalog/price-lists/lists/${id}`, 'DELETE');
}

export function fetchPriceListCoverage(): Promise<CoverageOverview> {
  return api<CoverageOverview>('catalog/price-lists/coverage');
}
