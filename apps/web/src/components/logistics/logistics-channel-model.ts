import { api } from '../inventory/api';

/**
 * 10.1.1.2 渠道配置前端模型。
 *
 * <p>蓝图：「配置具体物流渠道：所属承运商、渠道代码、适用店铺/国家/品类、计费方式
 * （按重/按件/按体积）、附加费（燃油、偏远、旺季）、申报限制、保价规则」，
 * 输出「渠道配置；供运费试算与自动匹配使用」。
 *
 * <p>🔴 这个模块的界面上最容易做成假活的一件事，是把「渠道限制」画成一列
 * 勾选框然后让页面自己判断能不能发。**限制判定在服务端**（POST eligibility），
 * 界面上那个试算面板调的就是它，拿到的是服务端给的结论与逐条理由。页面里
 * **没有**任何一处拿 restrictions 数组自己比对出「可以发 / 不能发」——
 * 那样做的规则会散落到每个调用方，而总有一个调用方会漏掉磁力这一条。
 */

export interface ChannelCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface LogisticsChannelCapabilities {
  dimensions: ChannelCapabilityDimension[];
  billingModes: string[];
  surchargeKinds: string[];
  restrictionKinds: string[];
  restrictionModes: string[];
  statusCodes: string[];
  readinessCodes: string[];
  scopeKinds: string[];
  boundaryNote: string;
  restrictionNote: string;
}

export interface ChannelScopeItem {
  scopeKind: string;
  scopeValue: string;
}

export interface ChannelSurchargeItem {
  surchargeKind: string;
  chargeMode: string;
  /** 金额一律走 number = JSON number，**不做前端浮点运算**，只原样提交。 */
  amount: number;
  effectiveFrom: string | null;
  effectiveTo: string | null;
  note: string;
}

export interface ChannelRestrictionItem {
  restrictionKind: string;
  subject: string;
  restrictionMode: string;
  carrierReason: string;
}

export interface LogisticsChannelRow {
  id: string;
  carrierProfileId: string;
  /** 承运商编码 / 名称是 10.1.1.1 的**只读快照** —— 本页不可编辑，改身份回 /logistics/carriers。 */
  carrierCode: string;
  carrierName: string;
  carrierAvailability: string;
  channelCode: string;
  channelName: string;
  status: string;
  billingMode: string;
  billingCurrency: string;
  baseRate: number;
  chargeDivisor: number;
  minCharge: number;
  declarationMinValue: number | null;
  declarationMaxValue: number | null;
  declarationCurrency: string | null;
  insuranceMode: string;
  insuranceRatePct: number;
  insuranceMinFee: number;
  insuranceMaxValue: number | null;
  scopes: ChannelScopeItem[];
  surcharges: ChannelSurchargeItem[];
  /** 按读取时刻算出来的**当前生效**附加费（旺季费只在区间内才在这里）。 */
  activeSurcharges: ChannelSurchargeItem[];
  restrictions: ChannelRestrictionItem[];
  readiness: string;
  readinessMessage: string;
  peakSeasonActive: boolean;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface LogisticsChannelSummary {
  total: number;
  ready: number;
  noBaseRate: number;
  noScope: number;
  suspended: number;
  draft: number;
  withRestrictions: number;
  peakSeasonActive: number;
}

export interface LogisticsChannelPage {
  items: LogisticsChannelRow[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
  summary: LogisticsChannelSummary;
}

export interface LogisticsChannelCount {
  code: string;
  label: string;
  count: number;
}

export interface LogisticsChannelOverview {
  summary: LogisticsChannelSummary;
  byBillingMode: LogisticsChannelCount[];
  byRestrictionKind: LogisticsChannelCount[];
  note: string;
}

export interface SaveChannelInput {
  channelId: string | null;
  carrierProfileId: string | null;
  channelCode: string;
  channelName: string;
  status: string;
  billingMode: string;
  billingCurrency: string;
  baseRate: number;
  chargeDivisor: number;
  minCharge: number;
  declarationMinValue: number | null;
  declarationMaxValue: number | null;
  declarationCurrency: string;
  insuranceMode: string;
  insuranceRatePct: number;
  insuranceMinFee: number;
  insuranceMaxValue: number | null;
  scopes: ChannelScopeItem[];
  surcharges: ChannelSurchargeItem[];
  restrictions: ChannelRestrictionItem[];
  note: string;
  version: number;
}

export interface ChannelEligibilityInput {
  channelIds: string[] | null;
  countryCode: string;
  shopCode: string;
  categoryCodes: string[];
  containsBattery: boolean;
  containsLiquid: boolean;
  containsMagnet: boolean;
  declaredValue: number | null;
  insuredValue: number | null;
}

export interface ChannelEligibilityVerdict {
  channelId: string;
  channelCode: string;
  channelName: string;
  carrierName: string;
  /** PASS 可承运 · REVIEW 需人工确认 · BLOCK 硬拦截 */
  verdict: string;
  blockingReasons: string[];
  reviewReasons: string[];
}

export interface ChannelEligibilityResult {
  verdicts: ChannelEligibilityVerdict[];
  pass: number;
  review: number;
  block: number;
  note: string;
}

/** 列表筛选条件（全部可选，空串按不过滤处理）。 */
export interface ChannelFilters {
  keyword: string;
  carrierProfileId: string;
  billingMode: string;
  status: string;
  readiness: string;
  scopeKind: string;
  scopeValue: string;
  restrictionKind: string;
  sort: string;
  page: number;
  size: number;
}

export const EMPTY_CHANNEL_FILTERS: ChannelFilters = {
  keyword: '',
  carrierProfileId: '',
  billingMode: '',
  status: '',
  readiness: '',
  scopeKind: '',
  scopeValue: '',
  restrictionKind: '',
  sort: 'NAME_ASC',
  page: 0,
  size: 20,
};

/** 表单初值（新建 / 编辑共用）。 */
export const EMPTY_CHANNEL_FORM: SaveChannelInput = {
  channelId: null,
  carrierProfileId: null,
  channelCode: '',
  channelName: '',
  status: 'DRAFT',
  billingMode: 'BY_WEIGHT',
  billingCurrency: 'USD',
  baseRate: 0,
  chargeDivisor: 1,
  minCharge: 0,
  declarationMinValue: null,
  declarationMaxValue: null,
  declarationCurrency: '',
  insuranceMode: 'NONE',
  insuranceRatePct: 0,
  insuranceMinFee: 0,
  insuranceMaxValue: null,
  scopes: [],
  surcharges: [],
  restrictions: [],
  note: '',
  version: 0,
};

// ================================================================== 文案

export function billingModeLabel(code: string): string {
  switch (code) {
    case 'BY_WEIGHT':
      return '按重';
    case 'BY_PIECE':
      return '按件';
    case 'BY_VOLUME':
      return '按体积';
    default:
      return code;
  }
}

export function channelStatusLabel(code: string): string {
  switch (code) {
    case 'DRAFT':
      return '草稿';
    case 'ACTIVE':
      return '启用';
    case 'SUSPENDED':
      return '停用';
    default:
      return code;
  }
}

export function scopeKindLabel(code: string): string {
  switch (code) {
    case 'SHOP':
      return '店铺';
    case 'COUNTRY':
      return '国家';
    case 'CATEGORY':
      return '品类';
    default:
      return code;
  }
}

export function surchargeKindLabel(code: string): string {
  switch (code) {
    case 'FUEL':
      return '燃油附加费';
    case 'REMOTE_AREA':
      return '偏远附加费';
    case 'PEAK_SEASON':
      return '旺季附加费';
    default:
      return code;
  }
}

export function surchargeChargeModeLabel(code: string): string {
  switch (code) {
    case 'PERCENT':
      return '按基础运费百分比';
    case 'FIXED':
      return '固定额';
    default:
      return code;
  }
}

export function restrictionKindLabel(code: string): string {
  switch (code) {
    case 'PROHIBITED_CATEGORY':
      return '禁限品类';
    case 'BATTERY':
      return '电池';
    case 'LIQUID':
      return '液体';
    case 'MAGNET':
      return '磁力';
    default:
      return code;
  }
}

export function restrictionModeLabel(code: string): string {
  switch (code) {
    case 'BLOCK':
      return '硬拦截（拒收）';
    case 'MANUAL_REVIEW':
      return '需人工确认';
    default:
      return code;
  }
}

export function insuranceModeLabel(code: string): string {
  switch (code) {
    case 'NONE':
      return '不承保';
    case 'DECLARED_VALUE':
      return '按申报价值投保';
    case 'FIXED':
      return '固定保价费';
    default:
      return code;
  }
}

/**
 * 可用性状态的展示元数据。
 *
 * <p>刻意把「还没配齐」（NO_BASE_RATE / NO_SCOPE，灰）与「已停用」（SUSPENDED，橙）
 * 分开：前者是运营待补的活，后者是一个有意识的决定，处置方式不一样。
 */
export function readinessMeta(readiness: string): {
  label: string;
  tone: 'success' | 'warning' | 'neutral';
} {
  switch (readiness) {
    case 'READY':
      return { label: '配置齐备', tone: 'success' };
    case 'NO_BASE_RATE':
      return { label: '缺基础单价', tone: 'neutral' };
    case 'NO_SCOPE':
      return { label: '缺适用范围', tone: 'neutral' };
    case 'SUSPENDED':
      return { label: '已停用', tone: 'warning' };
    default:
      return { label: readiness, tone: 'neutral' };
  }
}

export function verdictMeta(verdict: string): {
  label: string;
  tone: 'success' | 'warning' | 'danger';
} {
  switch (verdict) {
    case 'PASS':
      return { label: '可承运', tone: 'success' };
    case 'REVIEW':
      return { label: '需人工确认', tone: 'warning' };
    case 'BLOCK':
      return { label: '硬拦截', tone: 'danger' };
    default:
      return { label: verdict, tone: 'neutral' as 'warning' };
  }
}

/** 按体积计费的行业通用折算基数（cm³/kg）。后端同样按这个缺省。 */
export const VOLUME_DIVISOR = 6000;

// ================================================================== 校验

/** 空串转 null：后端对「未填」与「填了 0」的处理不同（最低收费 0 与未登记是两回事）。 */
export function optionalNumber(raw: string): number | null {
  const trimmed = raw.trim();
  if (trimmed === '') return null;
  const value = Number(trimmed);
  return Number.isFinite(value) ? value : null;
}

/**
 * 整张渠道配置的本地校验，逐条返回理由。
 *
 * <p>前端这道校验是为了**早点告诉人**，不是安全边界 —— 后端有 DB 层 CHECK 与
 * 写入冲突校验兜底。但它能在提交前就标出「按件计费却填了 0.5 的折算基数」
 * 这类看着合理、实际系统性算错运费的配置。
 */
export function validateChannelForm(input: SaveChannelInput): string[] {
  const problems: string[] = [];
  if (!input.carrierProfileId) problems.push('必须选择所属承运商：渠道挂在承运商之下');
  if (!input.channelCode.trim()) problems.push('渠道代码必填：它是下游运单对齐这条渠道配置的口径');
  if (!input.channelName.trim()) problems.push('渠道名称必填');
  if (!/^[A-Z]{3}$/.test(input.billingCurrency.trim().toUpperCase())) {
    problems.push('计费币种必须是三位大写字母（如 USD / CNY）');
  }
  if (!(input.baseRate > 0)) {
    problems.push('基础单价必须大于 0：为 0 时试算算不出运费（可用性会停在「缺基础单价」）');
  }
  if (input.minCharge < 0) problems.push('最低收费不能为负');

  // 按件计费时折算基数必须为 1，否则「按件」就不是按件（DB 层也会拒）
  if (input.billingMode === 'BY_PIECE' && input.chargeDivisor !== 1) {
    problems.push('按件计费时折算基数必须是 1：填别的数会让每件按半个公斤算，运费系统性偏低');
  }
  if (input.billingMode === 'BY_VOLUME' && !(input.chargeDivisor > 0)) {
    problems.push(`按体积计费需要大于 0 的折算基数（行业通用 ${VOLUME_DIVISOR} cm³/kg）`);
  }

  // 申报上下限
  const dMin = input.declarationMinValue;
  const dMax = input.declarationMaxValue;
  if (dMin !== null && dMax !== null && !(dMin < dMax)) {
    problems.push('申报价值下限必须小于上限');
  }
  if ((dMin !== null || dMax !== null) && !input.declarationCurrency.trim()) {
    problems.push('登记了申报价值上下限就必须填申报币种：没填的话试算没法换算');
  }

  // 保价规则：不承保就不该有费率（那是幽灵配置）
  if (input.insuranceMode === 'NONE' && (input.insuranceRatePct > 0 || input.insuranceMinFee > 0)) {
    problems.push('不承保就不该填费率 / 最低保费：那是算不出来的幽灵配置');
  }
  if (input.insuranceMode === 'DECLARED_VALUE' && !(input.insuranceRatePct > 0)) {
    problems.push('按申报价值投保必须填费率，否则保费恒为 0');
  }
  if (input.insuranceMode !== 'DECLARED_VALUE' && input.insuranceRatePct < 0) {
    problems.push('费率不能为负');
  }

  // 适用维度
  const countryScopes = input.scopes.filter((s) => s.scopeKind === 'COUNTRY');
  if (countryScopes.some((s) => !/^[A-Za-z]{2}$/.test(s.scopeValue.trim()))) {
    problems.push('适用国家必须是两位字母（如 DE / US）');
  }
  if (input.scopes.length === 0) {
    problems.push('至少登记一条适用范围：一条都没有的渠道对谁都不可用');
  }

  // 🔴 限制：禁限品类不得同时登记成适用品类（后端会拒，这里提前标红）
  const blockedCategories = new Set(
    input.restrictions
      .filter((r) => r.restrictionKind === 'PROHIBITED_CATEGORY' && r.restrictionMode !== 'ALLOWED')
      .map((r) => r.subject.trim().toUpperCase()),
  );
  for (const scope of input.scopes) {
    if (scope.scopeKind !== 'CATEGORY') continue;
    if (blockedCategories.has(scope.scopeValue.trim().toUpperCase())) {
      problems.push(
        `品类「${scope.scopeValue}」既是适用品类又被列为禁限：下单会被承运商拒收并产生额外费用，必须去掉一项`,
      );
    }
  }

  // 旺季费必须带生效区间（半配置状态写库那一刻就会被拒）
  for (const surcharge of input.surcharges) {
    if (
      surcharge.surchargeKind === 'PEAK_SEASON' &&
      (!surcharge.effectiveFrom || !surcharge.effectiveTo)
    ) {
      problems.push('旺季附加费必须填生效起止时刻：没有区间的旺季费无法回答「现在要不要加」');
    }
    if (
      surcharge.surchargeKind === 'PEAK_SEASON' &&
      surcharge.effectiveFrom &&
      surcharge.effectiveTo
    ) {
      if (
        new Date(surcharge.effectiveFrom).getTime() >= new Date(surcharge.effectiveTo).getTime()
      ) {
        problems.push('旺季附加费的生效起点必须早于终点');
      }
    }
  }

  return problems;
}

/** 从一行渠道配置铺出表单初值（编辑时用）。 */
export function toChannelForm(row: LogisticsChannelRow): SaveChannelInput {
  return {
    channelId: row.id,
    carrierProfileId: row.carrierProfileId,
    channelCode: row.channelCode,
    channelName: row.channelName,
    status: row.status,
    billingMode: row.billingMode,
    billingCurrency: row.billingCurrency,
    baseRate: row.baseRate,
    chargeDivisor: row.chargeDivisor,
    minCharge: row.minCharge,
    declarationMinValue: row.declarationMinValue,
    declarationMaxValue: row.declarationMaxValue,
    declarationCurrency: row.declarationCurrency ?? '',
    insuranceMode: row.insuranceMode,
    insuranceRatePct: row.insuranceRatePct,
    insuranceMinFee: row.insuranceMinFee,
    insuranceMaxValue: row.insuranceMaxValue,
    scopes: row.scopes.map((s) => ({ ...s })),
    surcharges: row.surcharges.map((s) => ({ ...s })),
    restrictions: row.restrictions.map((r) => ({ ...r })),
    note: row.note ?? '',
    version: row.version,
  };
}

/** 把逗号 / 空格 / 分号分隔的输入拆成去重的大写列表。 */
export function parseList(raw: string): string[] {
  return Array.from(
    new Set(
      raw
        .split(/[,;\s]+/)
        .map((v) => v.trim().toUpperCase())
        .filter(Boolean),
    ),
  );
}

/** 生效区间是否命中给定时刻。区间是左闭右开 [from, to)。 */
export function isEffectiveAt(surcharge: ChannelSurchargeItem, at: Date): boolean {
  if (!surcharge.effectiveFrom || !surcharge.effectiveTo) return true;
  const t = at.getTime();
  return (
    t >= new Date(surcharge.effectiveFrom).getTime() &&
    t < new Date(surcharge.effectiveTo).getTime()
  );
}

/** 把附加费渲染成一句人话，空集显示「无附加费」而不是空白。 */
export function surchargeText(surcharge: ChannelSurchargeItem): string {
  const mode = surcharge.chargeMode === 'PERCENT' ? '%' : '';
  return `${surchargeKindLabel(surcharge.surchargeKind)} ${surcharge.amount}${mode}`;
}

// ================================================================== 端点

export function fetchChannelCapabilities(): Promise<LogisticsChannelCapabilities> {
  return api<LogisticsChannelCapabilities>('channel/logistics-channels/capabilities');
}

export function fetchChannelOverview(): Promise<LogisticsChannelOverview> {
  return api<LogisticsChannelOverview>('channel/logistics-channels/overview');
}

export function fetchChannels(filters: ChannelFilters): Promise<LogisticsChannelPage> {
  const query = new URLSearchParams();
  if (filters.keyword) query.set('keyword', filters.keyword);
  if (filters.carrierProfileId) query.set('carrierProfileId', filters.carrierProfileId);
  if (filters.billingMode) query.set('billingMode', filters.billingMode);
  if (filters.status) query.set('status', filters.status);
  if (filters.readiness) query.set('readiness', filters.readiness);
  if (filters.scopeKind && filters.scopeValue) {
    query.set('scopeKind', filters.scopeKind);
    query.set('scopeValue', filters.scopeValue);
  }
  if (filters.restrictionKind) query.set('restrictionKind', filters.restrictionKind);
  if (filters.sort) query.set('sort', filters.sort);
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return api<LogisticsChannelPage>(`channel/logistics-channels?${query.toString()}`);
}

export function saveChannel(input: SaveChannelInput): Promise<LogisticsChannelRow> {
  return api<LogisticsChannelRow>('channel/logistics-channels', 'POST', input);
}

export function removeChannel(id: string): Promise<void> {
  return api<void>(`channel/logistics-channels/${id}`, 'DELETE');
}

/**
 * 🔴 可承运性判定 —— **服务端**结论。
 *
 * <p>这个函数是界面上唯一的判定入口。**不要**在组件里自己读 restrictions 去比对
 * 品类 / 带电 / 带液 / 带磁：那会把「会被承运商拒收」这件事交给一个可以被绕过、
 * 也可以被漏掉的判断，而规则只有服务端那一份是对的。
 */
export function judgeChannels(input: ChannelEligibilityInput): Promise<ChannelEligibilityResult> {
  return api<ChannelEligibilityResult>('channel/logistics-channels/eligibility', 'POST', input);
}
