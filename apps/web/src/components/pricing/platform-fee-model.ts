import { api } from '../inventory/api';

/**
 * 5.2.3.1 平台费用前端模型。
 *
 * <p>蓝图加粗句是本模块唯一不可让步的一条：
 *
 * <blockquote>
 *
 * **规则参数用于事前测算，实际费用来自 12 的结算对账**——两者差额要在利润报表中显性展示为"费用差异"，否则测算永远不准却不知道为什么。
 *
 * </blockquote>
 *
 * <p>⇒ 这一条在页面上有**三个**落点，少一个都不算数：
 *
 * <ol>
 *   <li><b>两个口径并排显示</b>：规则（事前测算）与实际费用（事后对账）是两块独立的区域，
 *       各自的来源写在脸上（{@link FeeTypeOption.label} / {@link FeeActualRow.sourceLabel}）。
 *   <li><b>差额必须能算出来并被展示</b>：{@link FeeVarianceReport} 有 `available` 与
 *       `varianceAmount` 两个字段，页面按 {@link varianceHeadline} 渲染。
 *   <li><b>🔴 算不出来就说算不出来</b>：没有实际费用时整份差异报告 `available=false`，
 *       页面显示「无法计算差异」+ 原因；某笔实际费用找不到生效规则时该组
 *       `varianceAmount=null`。**任何情况下都不显示「差异 0.00」冒充「没有差异」**。
 * </ol>
 *
 * <h2>🔴 null 绝不能渲染成 0</h2>
 *
 * <p>「算不出来」与「确实是 0」是两件事：规则没配 ≠ 这笔费用是 0；
 * 阶梯落不进档 ≠ 平台没收钱。后端对这两种情况分别给了
 * {@code applicable=false} 与 {@code amount=null}，前端用 {@link moneyText}
 * 把它们渲染成「算不出来」而不是 0.00。渲染成 0.00 会让费用偏低、毛利偏高，
 * 而页面上看不出任何异常。
 *
 * <h2>🔴 实际费用的来源必须写在脸上</h2>
 *
 * <p>12 财务的结算对账**尚未交付**（finance 服务在本仓不存在），
 * 所以实际费用当前只有**人工登记的平台账单观察值**一种来源。
 * {@link actualSourceLabel} 把它原样显示出来，绝不写成「结算对账结果」。
 */

/** 能力声明里的一个维度。 */
export interface FeeCapabilityDimension {
  code: string;
  label: string;
  available: boolean;
  ownerModule: string;
  impact: string;
  basis: string;
  note: string;
}

/** 词表项（费用类型 / 计价形态 / 分摊口径共用）。 */
export interface FeeTypeOption {
  value: string;
  label: string;
  basis: string;
}

/** 能力声明。页面顶部固定显示它。 */
export interface PlatformFeeCapabilities {
  feeTypes: FeeTypeOption[];
  chargeBases: FeeTypeOption[];
  allocationBases: FeeTypeOption[];
  dimensions: FeeCapabilityDimension[];
  ruleBasis: string;
  actualBasis: string;
  varianceBasis: string;
  upstreamNote: string;
  downstreamNote: string;
  actionsNote: string;
}

/** 阶梯的一档。 */
export interface FeeTierRow {
  id: string;
  seq: number;
  minAmount: string | null;
  maxAmount: string | null;
  rate: string | null;
}

/** 费用规则台账的一行。 */
export interface FeeRuleRow {
  id: string;
  platformCode: string;
  siteCode: string | null;
  internalCategoryId: string | null;
  internalCategoryName: string | null;
  platformCategoryId: string | null;
  platformCategoryName: string | null;
  feeType: string;
  feeTypeLabel: string;
  chargeBasis: string;
  chargeBasisLabel: string;
  rate: string | null;
  fixedAmount: string | null;
  currency: string | null;
  periodTotalAmount: string | null;
  periodCurrency: string | null;
  allocationBasis: string | null;
  allocationDivisor: string | null;
  effectiveFrom: string | null;
  effectiveTo: string | null;
  enabled: boolean;
  note: string;
  version: number;
  tierCount: number;
  createdAt: string | null;
  updatedAt: string | null;
}

/** 规则台账分页结果。 */
export interface FeeRuleListPage {
  items: FeeRuleRow[];
  total: number;
  page: number;
  size: number;
  note: string;
}

/** 规则明细。 */
export interface FeeRuleDetail {
  rule: FeeRuleRow;
  tiers: FeeTierRow[];
  scopeBasis: string;
  note: string;
}

/** 保存规则的结果。 */
export interface FeeRuleSaved {
  id: string;
  feeType: string;
  chargeBasis: string;
  effectiveFrom: string;
  version: number;
}

/** 规则入参。 */
export interface FeeRuleInput {
  platformCode: string;
  siteCode: string | null;
  internalCategoryId: string | null;
  platformCategoryId: string | null;
  feeType: string;
  chargeBasis: string;
  rate: string | null;
  fixedAmount: string | null;
  currency: string | null;
  periodTotalAmount: string | null;
  periodCurrency: string | null;
  allocationBasis: string | null;
  allocationDivisor: string | null;
  effectiveFrom: string;
  effectiveTo: string | null;
  enabled: boolean;
  note: string;
  version: number | null;
}

/** 阶梯的一档（入参）。 */
export interface FeeTierInput {
  seq: number;
  minAmount: string;
  maxAmount: string | null;
  rate: string;
}

/** 整体替换阶梯的入参。 */
export interface TierListInput {
  tiers: FeeTierInput[];
}

/** 实际费用的一行。 */
export interface FeeActualRow {
  id: string;
  platformCode: string;
  siteCode: string | null;
  internalCategoryId: string | null;
  platformCategoryId: string | null;
  feeType: string;
  feeTypeLabel: string;
  amount: string | null;
  currency: string | null;
  saleAmount: string | null;
  quantity: string | null;
  occurredOn: string | null;
  source: string;
  sourceLabel: string;
  sourceRef: string;
  note: string;
  isVoid: boolean;
  voidReason: string | null;
  voidedAt: string | null;
  createdAt: string | null;
}

/** 实际费用台账分页结果。 */
export interface FeeActualListPage {
  items: FeeActualRow[];
  total: number;
  page: number;
  size: number;
  note: string;
}

/** 登记实际费用的入参。 */
export interface FeeActualInput {
  platformCode: string;
  siteCode: string | null;
  internalCategoryId: string | null;
  platformCategoryId: string | null;
  feeType: string;
  amount: string;
  currency: string;
  saleAmount: string;
  quantity: string;
  occurredOn: string;
  sourceRef: string;
  note: string;
}

/** 登记 / 作废实际费用的结果。 */
export interface FeeActualSaved {
  id: string;
  feeType: string;
  amount: string | null;
  currency: string | null;
  occurredOn: string | null;
}

/** 测算出的一个费用项。 */
export interface FeeEstimateItem {
  feeType: string;
  feeTypeLabel: string;
  chargeBasis: string | null;
  chargeBasisLabel: string | null;
  ruleId: string | null;
  scopeBasis: string | null;
  amount: string | null;
  currency: string | null;
  rate: string | null;
  basis: string;
  applicable: boolean;
  note: string;
}

/** 一次测算的完整结果。 */
export interface FeeEstimate {
  platformCode: string;
  siteCode: string | null;
  saleAmount: string;
  quantity: string;
  evaluatedAt: string;
  items: FeeEstimateItem[];
  totalAmount: string | null;
  currency: string | null;
  complete: boolean;
  note: string;
}

/** 一个费用类型的差异对照行。 */
export interface FeeVarianceGroup {
  feeType: string;
  feeTypeLabel: string;
  currency: string | null;
  actualAmount: string | null;
  estimatedAmount: string | null;
  varianceAmount: string | null;
  actualCount: number;
  comparableCount: number;
  note: string;
}

/** 费用差异对照报告。 */
export interface FeeVarianceReport {
  available: boolean;
  reason: string;
  platformCode: string;
  siteCode: string | null;
  from: string;
  to: string;
  groups: FeeVarianceGroup[];
  note: string;
}

/** 平台目录里的一个站点。 */
export interface DirectoryMarketplace {
  code: string;
  name: string;
  countryCode: string;
  defaultCurrency: string;
  currencyScale: number;
  vatRequired: boolean;
}

/** 平台目录里的一个平台。 */
export interface DirectoryPlatform {
  code: string;
  name: string;
  marketplaces: DirectoryMarketplace[];
}

/** 平台 / 站点目录（上游 2 平台目录）。 */
export interface PlatformDirectory {
  platforms: DirectoryPlatform[];
  sourceNote: string;
}

// ================================================================== 页面分区

/**
 * 5.2.3 的两个分区 —— 兄弟模块共用 L3 路由 {@code /pricing/fees}。
 *
 * <p>🔴 **未交付的分区必须显式标成「未交付」**，不能留一个空白框让人以为已交付
 * （照 11.2.3.3 供应商索赔的先例）。5.2.3.2 物流与头程费用与本模块
 * **不共用表、不共用端点、不共用口径**：它上游是 10 物流与头程，
 * 下游是 5.3.1.1，与平台费用规则是两套数据。
 */
export interface FeeSection {
  id: string;
  title: string;
  moduleId: string;
  moduleName: string;
  delivered: boolean;
  summary: string;
}

export const PLATFORM_FEE_SECTIONS: readonly FeeSection[] = [
  {
    id: 'platform-fee',
    title: '平台费用',
    moduleId: '5.2.3.1',
    moduleName: '平台费用',
    delivered: true,
    summary:
      '平台侧费用的计算规则：佣金比例或阶梯、订阅费分摊、Referral Fee、交易费、罚款类型。' +
      '规则按「平台 / 站点 / 类目」维护，用于利润测算的扣项；实际费用并列成另一个事实，' +
      '两者差额即「费用差异」。',
  },
  {
    id: 'logistics-fee',
    title: '物流与头程费用',
    moduleId: '5.2.3.2',
    moduleName: '物流与头程费用',
    delivered: false,
    summary:
      '物流渠道的计费规则（按重量 / 体积 / 件数、燃油附加、旺季附加、偏远附加）与头程费用结构。' +
      '🔴 5.2.3.2 **未交付**：它上游 = 10 物流与头程，与本模块的平台费用规则不是同一套数据，' +
      '本模块**不代它开形状**。',
  },
];

// ================================================================== 筛选

/** 规则台账筛选。 */
export interface FeeRuleFilters {
  keyword: string;
  platformCode: string;
  siteCode: string;
  feeType: string;
  /** 三态：'' = 不筛，'true' = 只看启用，'false' = 只看停用。 */
  enabled: string;
  page: number;
  size: number;
}

export const EMPTY_RULE_FILTERS: FeeRuleFilters = {
  keyword: '',
  platformCode: '',
  siteCode: '',
  feeType: '',
  enabled: '',
  page: 0,
  size: 20,
};

/** 实际费用台账筛选。 */
export interface FeeActualFilters {
  platformCode: string;
  siteCode: string;
  feeType: string;
  from: string;
  to: string;
  page: number;
  size: number;
}

export const EMPTY_ACTUAL_FILTERS: FeeActualFilters = {
  platformCode: '',
  siteCode: '',
  feeType: '',
  from: '',
  to: '',
  page: 0,
  size: 20,
};

// ================================================================== 文案

/** 费用类型 → 中文名（后端已给 label，这里只做兜底）。 */
export function feeTypeLabel(value: string | null | undefined): string {
  switch (value) {
    case 'COMMISSION':
      return '平台佣金';
    case 'REFERRAL':
      return 'Referral Fee';
    case 'TRANSACTION':
      return '交易费';
    case 'SUBSCRIPTION':
      return '订阅费';
    case 'PENALTY':
      return '罚款';
    case 'OTHER':
      return '其它平台费用';
    default:
      return value || '未维护';
  }
}

/** 计价形态 → 中文名。 */
export function chargeBasisLabel(value: string | null | undefined): string {
  switch (value) {
    case 'PERCENTAGE_OF_SALE':
      return '销售额比例';
    case 'FIXED_PER_ORDER':
      return '每单固定额';
    case 'FIXED_PER_UNIT':
      return '每件固定额';
    case 'TIERED_PERCENTAGE':
      return '阶梯比例';
    default:
      return value || '未维护';
  }
}

/** 分摊口径 → 中文名。 */
export function allocationBasisLabel(value: string | null | undefined): string {
  switch (value) {
    case 'PER_ORDER':
      return '按单分摊';
    case 'PER_UNIT':
      return '按件分摊';
    default:
      return value || '未维护';
  }
}

/**
 * 费率（小数口径）→ 百分比串。
 *
 * <p>后端存的是**小数**（0.15 = 15%）。页面上要显示 15% 而不是 0.15 ——
 * 平台费率都是按百分比谈的，写成 0.15 会让人怀疑是不是少了一个零。
 */
export function rateText(value: string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  const parsed = Number(value);
  if (Number.isNaN(parsed)) return value;
  const percent = parsed * 100;
  // 去掉多余的尾随零：15% / 12.5% / 8.25%。
  return `${Number(percent.toFixed(6))}%`;
}

/**
 * 金额 → 展示串。
 *
 * <p>🔴 null 显示「算不出来」而**不是 0.00**。
 */
export function moneyText(value: string | null | undefined, unavailable = '算不出来'): string {
  if (value === null || value === undefined || value === '') return unavailable;
  return value;
}

/** 带币种的金额串。 */
export function moneyWithCurrency(
  value: string | null | undefined,
  currency: string | null | undefined,
  unavailable = '算不出来',
): string {
  const text = moneyText(value, unavailable);
  if (text === unavailable) return text;
  return currency ? `${currency} ${text}` : text;
}

/** ISO 时刻 → 只到秒的串。 */
export function timeText(value: string | null | undefined, empty = '—'): string {
  if (!value) return empty;
  return value.replace('T', ' ').replace('Z', 'Z');
}

/** ISO 时刻 → 只到日的串。 */
export function dayText(value: string | null | undefined, empty = '—'): string {
  if (!value) return empty;
  return value.slice(0, 10);
}

/** 实际费用的来源 → 人话标签。🔴 绝不写成「结算对账结果」（12 尚未交付）。 */
export function actualSourceLabel(value: string | null | undefined): string {
  switch (value) {
    case 'MANUAL_OBSERVATION':
      return '人工登记的平台账单观察值';
    default:
      return value || '未知来源';
  }
}

/**
 * 差异的头条。
 *
 * <p>🔴 **不可用时必须说「算不出来」**，不可用时显示「差异 0.00」——
 * 那是在说「平台完全按规则收了钱」，而真实情况是「没有可比的实际费用」。
 */
export function varianceHeadline(report: FeeVarianceReport | null): {
  tone: 'success' | 'warning' | 'danger';
  text: string;
} {
  if (!report) return { tone: 'warning', text: '尚未加载' };
  if (!report.available) {
    return { tone: 'warning', text: `🔴 无法计算费用差异 —— ${report.reason}` };
  }
  const computable = report.groups.filter((g) => g.varianceAmount !== null);
  if (!computable.length) {
    return {
      tone: 'warning',
      text: '🔴 无法计算费用差异 —— 有实际费用，但找不到对应的生效规则（规则没配或规则不适用该金额）。',
    };
  }
  const over = computable.filter((g) => Number(g.varianceAmount) > 0);
  const under = computable.filter((g) => Number(g.varianceAmount) < 0);
  if (over.length) {
    return {
      tone: 'danger',
      text: `有 ${over.length} 项费用类型**实际比规则测算多收**了钱 —— 规则口径定低了，或平台另收了费。`,
    };
  }
  if (under.length) {
    return {
      tone: 'warning',
      text: `有 ${under.length} 项费用类型**实际比规则测算少收**了钱 —— 规则口径定高了。`,
    };
  }
  return { tone: 'success', text: '全部费用类型的实际金额与规则测算一致（差异 0.00）。' };
}

/** 差异一行的色：正数（多收）= danger，负数（少收）= warning，零 = success。 */
export function varianceTone(group: FeeVarianceGroup): 'success' | 'warning' | 'danger' {
  if (group.varianceAmount === null) return 'warning';
  const value = Number(group.varianceAmount);
  if (value > 0) return 'danger';
  if (value < 0) return 'warning';
  return 'success';
}

/** 差异一行的人话说明（null 时说明为什么算不出来）。 */
export function varianceText(group: FeeVarianceGroup): string {
  if (group.varianceAmount === null) return group.note || '差异算不出来';
  const value = Number(group.varianceAmount);
  if (value > 0) return `实际比规则测算多收 ${Math.abs(value)}`;
  if (value < 0) return `实际比规则测算少收 ${Math.abs(value)}`;
  return '与规则测算一致';
}

/** 规则的作用域一句话（页面上要看得懂这条规则管到哪）。 */
export function ruleScopeText(rule: FeeRuleRow): string {
  const parts = [`平台 ${rule.platformCode}`];
  parts.push(rule.siteCode ? `站点 ${rule.siteCode}` : '全部站点');
  if (rule.internalCategoryName) parts.push(`内部分类「${rule.internalCategoryName}」及其子类`);
  if (rule.platformCategoryName) parts.push(`平台类目「${rule.platformCategoryName}」`);
  return parts.join(' / ');
}

// ================================================================== 本地校验

/** 规则表单的本地校验。前端这道校验是为了**早点告诉人**，不是安全边界。 */
export function validateRule(input: FeeRuleInput): string[] {
  const problems: string[] = [];
  const text = (value: string | null | undefined) => (value ?? '').trim();
  if (!text(input.platformCode)) {
    problems.push('平台必填：费用规则挂在「平台 / 站点 / 类目」三个维度上');
  }
  if (!input.feeType) problems.push('费用类型必填');
  if (!input.chargeBasis) problems.push('计价形态必填：这条费率到底怎么算必须有唯一答案');
  if (!text(input.effectiveFrom)) problems.push('生效起点必填：这条费率从哪一刻开始算数');
  if (text(input.effectiveTo) && !text(input.effectiveFrom)) {
    problems.push('填了生效终点就必填生效起点');
  }

  const subscription = input.feeType === 'SUBSCRIPTION';
  if (subscription) {
    if (!['FIXED_PER_ORDER', 'FIXED_PER_UNIT'].includes(input.chargeBasis)) {
      problems.push('订阅费的计价形态只能是「每单固定额」或「每件固定额」');
    }
    if (!input.periodTotalAmount || Number(input.periodTotalAmount) <= 0) {
      problems.push('订阅费必须填周期总额（大于 0）：订阅费是一笔周期总额，不是每单的钱');
    }
    if (!input.periodCurrency || !/^[A-Za-z]{3}$/.test(input.periodCurrency.trim())) {
      problems.push('订阅费必须填周期总额的币种（ISO 4217 三位字母码）');
    }
    if (!input.allocationBasis) problems.push('订阅费必须填分摊口径（按单分摊 / 按件分摊）');
    if (!input.allocationDivisor || Number(input.allocationDivisor) <= 0) {
      problems.push('订阅费必须填分摊基数（期间单量 / 件量）：没有基数就摊不出来');
    }
    if (input.rate && input.rate.trim()) problems.push('订阅费不填费率：它的金额来自周期总额分摊');
    if (input.fixedAmount && input.fixedAmount.trim()) {
      problems.push('订阅费不填固定额：它的金额来自周期总额分摊');
    }
  } else if (input.chargeBasis === 'PERCENTAGE_OF_SALE') {
    if (!input.rate || !input.rate.trim()) problems.push('销售额比例形态必须填费率');
    else {
      const rate = Number(input.rate);
      if (Number.isNaN(rate)) problems.push(`费率请填数字：${input.rate}`);
      else if (rate < 0 || rate > 1) {
        problems.push('费率必须在 0 到 1 之间（小数口径：0.15 = 15%）：' + input.rate);
      }
    }
    if (input.fixedAmount && input.fixedAmount.trim()) {
      problems.push('销售额比例形态不填固定额：两个都填时「怎么算」没有答案');
    }
  } else if (input.chargeBasis === 'TIERED_PERCENTAGE') {
    if (input.rate && input.rate.trim())
      problems.push('阶梯形态不填费率（费率在阶梯的每一档里填）');
    if (input.fixedAmount && input.fixedAmount.trim()) {
      problems.push('阶梯形态不填固定额（金额在阶梯的每一档里按比例算）');
    }
  } else {
    if (!input.fixedAmount || !input.fixedAmount.trim()) problems.push('固定额形态必须填固定额');
    else if (Number(input.fixedAmount) < 0) problems.push('固定额不能为负');
    if (!input.currency || !/^[A-Za-z]{3}$/.test(input.currency.trim())) {
      problems.push('固定额必须填币种（ISO 4217 三位字母码）');
    }
  }
  return problems;
}

/** 实际费用表单的本地校验。**销售额基数与数量基数必填**（没有它们算不出差异）。 */
export function validateActual(input: FeeActualInput): string[] {
  const problems: string[] = [];
  if (!input.platformCode.trim()) problems.push('平台必填');
  if (!input.feeType) problems.push('费用类型必填');
  if (!input.amount.trim()) problems.push('实际发生额必填');
  else if (!/^-?\d+(\.\d+)?$/.test(input.amount.trim())) {
    problems.push(`实际发生额请填数字：${input.amount}`);
  }
  if (!input.currency || !/^[A-Za-z]{3}$/.test(input.currency.trim())) {
    problems.push('币种必填（ISO 4217 三位字母码）');
  }
  if (!input.saleAmount.trim()) {
    problems.push('销售额基数必填：没有它就按规则重算不出预期值，差异算不出来');
  } else if (!/^\d+(\.\d+)?$/.test(input.saleAmount.trim())) {
    problems.push(`销售额基数请填非负数字：${input.saleAmount}`);
  }
  if (!input.quantity.trim()) {
    problems.push('数量基数必填：按件规则与订阅费按件分摊都要它');
  } else if (!/^\d+(\.\d+)?$/.test(input.quantity.trim())) {
    problems.push(`数量基数请填非负数字：${input.quantity}`);
  }
  if (!input.occurredOn) problems.push('费用发生日必填：差异对照按这一天的生效规则算');
  return problems;
}

/** 作废理由必填：没有理由的作废会让「平台费为什么变了」无法解释。 */
export function validateVoidReason(reason: string): string[] {
  return reason.trim() ? [] : ['作废必须填理由：没有理由的作废会让「平台费为什么变了」无法解释'];
}

// ================================================================== 端点

export function fetchPlatformFeeCapabilities(): Promise<PlatformFeeCapabilities> {
  return api<PlatformFeeCapabilities>('catalog/platform-fees/capabilities');
}

export function fetchPlatformDirectory(): Promise<PlatformDirectory> {
  return api<PlatformDirectory>('catalog/platform-fees/directory');
}

export function fetchFeeRuleList(filters: FeeRuleFilters): Promise<FeeRuleListPage> {
  const query = new URLSearchParams();
  if (filters.keyword) query.set('keyword', filters.keyword);
  if (filters.platformCode) query.set('platformCode', filters.platformCode);
  if (filters.siteCode) query.set('siteCode', filters.siteCode);
  if (filters.feeType) query.set('feeType', filters.feeType);
  if (filters.enabled) query.set('enabled', filters.enabled);
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return api<FeeRuleListPage>(`catalog/platform-fees/rules?${query.toString()}`);
}

export function fetchFeeRule(ruleId: string): Promise<FeeRuleDetail> {
  return api<FeeRuleDetail>(`catalog/platform-fees/rules/${ruleId}`);
}

export function createFeeRule(input: FeeRuleInput): Promise<FeeRuleSaved> {
  return api<FeeRuleSaved>('catalog/platform-fees/rules', 'POST', input);
}

export function updateFeeRule(ruleId: string, input: FeeRuleInput): Promise<FeeRuleSaved> {
  return api<FeeRuleSaved>(`catalog/platform-fees/rules/${ruleId}`, 'PUT', input);
}

export function replaceFeeTiers(ruleId: string, tiers: FeeTierInput[]): Promise<FeeRuleDetail> {
  return api<FeeRuleDetail>(`catalog/platform-fees/rules/${ruleId}/tiers`, 'PUT', { tiers });
}

export function fetchFeeEstimate(params: {
  platformCode: string;
  siteCode?: string | null;
  internalCategoryId?: string | null;
  platformCategoryId?: string | null;
  saleAmount: string;
  quantity: string;
  currency: string;
}): Promise<FeeEstimate> {
  const query = new URLSearchParams();
  query.set('platformCode', params.platformCode);
  if (params.siteCode) query.set('siteCode', params.siteCode);
  if (params.internalCategoryId) query.set('internalCategoryId', params.internalCategoryId);
  if (params.platformCategoryId) query.set('platformCategoryId', params.platformCategoryId);
  query.set('saleAmount', params.saleAmount);
  query.set('quantity', params.quantity);
  query.set('currency', params.currency);
  return api<FeeEstimate>(`catalog/platform-fees/estimate?${query.toString()}`);
}

export function fetchFeeActuals(filters: FeeActualFilters): Promise<FeeActualListPage> {
  const query = new URLSearchParams();
  if (filters.platformCode) query.set('platformCode', filters.platformCode);
  if (filters.siteCode) query.set('siteCode', filters.siteCode);
  if (filters.feeType) query.set('feeType', filters.feeType);
  if (filters.from) query.set('from', filters.from);
  if (filters.to) query.set('to', filters.to);
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return api<FeeActualListPage>(`catalog/platform-fees/actuals?${query.toString()}`);
}

export function recordFeeActual(input: FeeActualInput): Promise<FeeActualSaved> {
  return api<FeeActualSaved>('catalog/platform-fees/actuals', 'POST', input);
}

export function voidFeeActual(actualId: string, reason: string): Promise<FeeActualSaved> {
  return api<FeeActualSaved>(`catalog/platform-fees/actuals/${actualId}/void`, 'POST', { reason });
}

export function fetchFeeVariance(params: {
  platformCode: string;
  siteCode?: string | null;
  feeType?: string | null;
  from: string;
  to: string;
}): Promise<FeeVarianceReport> {
  const query = new URLSearchParams();
  query.set('platformCode', params.platformCode);
  if (params.siteCode) query.set('siteCode', params.siteCode);
  if (params.feeType) query.set('feeType', params.feeType);
  query.set('from', params.from);
  query.set('to', params.to);
  return api<FeeVarianceReport>(`catalog/platform-fees/variance?${query.toString()}`);
}
