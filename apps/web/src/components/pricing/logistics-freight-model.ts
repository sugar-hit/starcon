import { api } from '../inventory/api';

/**
 * 5.2.3.2 物流与头程费用前端模型。
 *
 * <p>蓝图：
 *
 * <blockquote>
 *
 * **职责**：维护物流渠道的计费规则（按重量 / 体积 / 件数、燃油附加、旺季附加、偏远附加）与头程费用结构，用于测算运费占成本比。
 *
 * **输入**：渠道计费规则 / 附加费参数 / 头程运输方式费用。
 *
 * **输出**：费用规则集 + 测算输入。
 *
 * **关系**：上游 = 10 物流与头程（卷级引用）；下游 = 5.3.1.1 测算。
 *
 * </blockquote>
 *
 * <p>⇒ 页面上有**三个**落点，少一个都不算数：
 *
 * <ol>
 *   <li><b>两个口径并排显示</b>：渠道计费规则（事前测算）与实际运费（事后对账）
 *       是两块独立的区域，各自带自己的来源标注（{@link FreightActualRow.sourceLabel}）。
 *   <li><b>差额必须能算出来并被展示</b>：{@link FreightVarianceReport} 有 `available` 与
 *       `varianceAmount` 两个字段，页面按 {@link varianceHeadline} 渲染。
 *   <li><b>🔴 算不出来就说算不出来</b>：没有实际运费时整份差异报告 `available=false`；
 *       没有生效规则 / 规则落不进档时测算步骤 `applicable=false`、`amount=null`。
 *       **任何情况下都不显示「运费 0.00」冒充「没有费用」**。
 * </ol>
 *
 * <h2>🔴 null 绝不能渲染成 0</h2>
 *
 * <p>「算不出来」与「确实是 0」是两件事：规则没配 ≠ 这票运费是 0；
 * 阶梯落不进档 ≠ 物流商没收钱。后端对这两种情况分别给了
 * {@code applicable=false} 与 {@code amount=null}，前端用 {@link moneyText}
 * 把它们渲染成「算不出来」而不是 0.00。渲染成 0.00 会让运费偏低、成本占比看起来很健康，
 * 而页面上看不出任何异常。
 *
 * <h2>🔴 实际运费的来源必须写在脸上</h2>
 *
 * <p>上游（10 物流与头程）当前**没有**可读的实际运费结算事实 ——
 * channel 的 {@code freight_quote.cost_basis} 恒为 {@code RULE_ESTIMATE}
 * （那是按规则估的报价，不是物流商的结算单）。
 * 所以实际运费当前只有**人工登记的物流商账单观察值**一种来源。
 * {@link actualSourceLabel} 把它原样显示出来，绝不写成「结算对账结果」。
 */

/** 能力声明里的一个维度。 */
export interface LogisticsFreightCapabilityDimension {
  code: string;
  label: string;
  available: boolean;
  ownerModule: string;
  impact: string;
  basis: string;
  note: string;
}

/** 词表项。 */
export interface FreightOption {
  value: string;
  label: string;
  basis: string;
}

/** 能力声明。页面顶部固定显示它。 */
export interface LogisticsFreightCapabilities {
  chargeBases: FreightOption[];
  surchargeTypes: FreightOption[];
  surchargeBases: FreightOption[];
  priceModes: FreightOption[];
  headComponentTypes: FreightOption[];
  headCalcBases: FreightOption[];
  dimensions: LogisticsFreightCapabilityDimension[];
  ruleBasis: string;
  surchargeBasis: string;
  headBasis: string;
  actualBasis: string;
  varianceBasis: string;
  upstreamNote: string;
  downstreamNote: string;
  actionsNote: string;
}

/** 阶梯的一档。 */
export interface FreightTierRow {
  id: string;
  seq: number;
  minValue: string | null;
  maxValue: string | null;
  priceMode: string;
  priceModeLabel: string | null;
  flatPrice: string | null;
  firstLimit: string | null;
  firstPrice: string | null;
  additionalPrice: string | null;
}

/** 渠道计费规则台账的一行。 */
export interface FreightRuleRow {
  id: string;
  channelCode: string;
  channelName: string | null;
  carrierCode: string | null;
  carrierName: string | null;
  shippingMethodCode: string;
  shippingMethodName: string | null;
  chargeBasis: string;
  chargeBasisLabel: string | null;
  currency: string;
  volumetricDivisor: string | null;
  minimumCharge: string | null;
  effectiveFrom: string | null;
  effectiveTo: string | null;
  versionNo: number;
  supersedesId: string | null;
  supersededById: string | null;
  status: string;
  statusLabel: string | null;
  disableReason: string | null;
  note: string;
  tierCount: number;
  createdAt: string | null;
}

/** 规则台账分页结果。 */
export interface FreightRuleListPage {
  items: FreightRuleRow[];
  total: number;
  page: number;
  size: number;
  note: string;
}

/** 规则明细。 */
export interface FreightRuleDetail {
  rule: FreightRuleRow;
  tiers: FreightTierRow[];
  scopeBasis: string;
  note: string;
}

/** 保存规则的结果。 */
export interface FreightRuleSaved {
  id: string;
  versionNo: number;
  status: string;
  effectiveFrom: string;
}

/** 阶梯的一档（入参）。 */
export interface FreightTierInput {
  seq: number;
  minValue: string;
  maxValue: string | null;
  priceMode: string;
  flatPrice: string | null;
  firstLimit: string | null;
  firstPrice: string | null;
  additionalPrice: string | null;
}

/** 规则入参（规则与阶梯同事务写入）。 */
export interface FreightRuleInput {
  channelCode: string;
  channelName: string | null;
  carrierCode: string | null;
  carrierName: string | null;
  shippingMethodCode: string;
  shippingMethodName: string | null;
  chargeBasis: string;
  currency: string;
  volumetricDivisor: string | null;
  minimumCharge: string | null;
  effectiveFrom: string;
  effectiveTo: string | null;
  note: string;
  tiers: FreightTierInput[];
}

/** 停用理由入参。 */
export interface FreightDisableInput {
  reason: string;
}

/** 附加费台账的一行。 */
export interface FreightSurchargeRow {
  id: string;
  channelCode: string;
  channelName: string | null;
  carrierCode: string | null;
  carrierName: string | null;
  shippingMethodCode: string;
  shippingMethodName: string | null;
  surchargeType: string;
  surchargeTypeLabel: string | null;
  calcBasis: string;
  calcBasisLabel: string | null;
  rate: string | null;
  fixedAmount: string | null;
  currency: string;
  effectiveFrom: string | null;
  effectiveTo: string | null;
  versionNo: number;
  supersedesId: string | null;
  supersededById: string | null;
  status: string;
  statusLabel: string | null;
  disableReason: string | null;
  note: string;
  createdAt: string | null;
}

/** 附加费台账分页结果。 */
export interface FreightSurchargeListPage {
  items: FreightSurchargeRow[];
  total: number;
  page: number;
  size: number;
  note: string;
}

/** 附加费入参。 */
export interface FreightSurchargeInput {
  channelCode: string;
  channelName: string | null;
  carrierCode: string | null;
  carrierName: string | null;
  shippingMethodCode: string;
  shippingMethodName: string | null;
  surchargeType: string;
  calcBasis: string;
  rate: string | null;
  fixedAmount: string | null;
  currency: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  note: string;
}

/** 保存附加费的结果。 */
export interface FreightSurchargeSaved {
  id: string;
  surchargeType: string;
  versionNo: number;
  status: string;
  effectiveFrom: string;
}

/** 头程成本项的一行。 */
export interface HeadFreightComponentRow {
  id: string;
  seq: number;
  componentType: string;
  componentTypeLabel: string | null;
  label: string | null;
  calcBasis: string;
  calcBasisLabel: string | null;
  unitAmount: string | null;
  currency: string;
}

/** 头程费用结构台账的一行。 */
export interface HeadFreightStructureRow {
  id: string;
  shippingMethodCode: string;
  shippingMethodName: string | null;
  originCode: string;
  originName: string | null;
  destinationCode: string;
  destinationName: string | null;
  currency: string;
  effectiveFrom: string | null;
  effectiveTo: string | null;
  versionNo: number;
  supersedesId: string | null;
  supersededById: string | null;
  status: string;
  statusLabel: string | null;
  disableReason: string | null;
  note: string;
  componentCount: number;
  createdAt: string | null;
}

/** 头程费用结构台账分页结果。 */
export interface HeadFreightStructureListPage {
  items: HeadFreightStructureRow[];
  total: number;
  page: number;
  size: number;
  note: string;
}

/** 头程费用结构明细。 */
export interface HeadFreightStructureDetail {
  structure: HeadFreightStructureRow;
  components: HeadFreightComponentRow[];
  note: string;
}

/** 头程成本项（入参）。 */
export interface HeadFreightComponentInput {
  seq: number;
  componentType: string;
  label: string | null;
  calcBasis: string;
  unitAmount: string;
  currency: string;
}

/** 头程费用结构入参（结构与成本项同事务写入）。 */
export interface HeadFreightStructureInput {
  shippingMethodCode: string;
  shippingMethodName: string | null;
  originCode: string;
  originName: string | null;
  destinationCode: string;
  destinationName: string | null;
  currency: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  note: string;
  components: HeadFreightComponentInput[];
}

/** 保存头程费用结构的结果。 */
export interface HeadFreightStructureSaved {
  id: string;
  versionNo: number;
  status: string;
  effectiveFrom: string;
}

/** 实际运费的一行。 */
export interface FreightActualRow {
  id: string;
  channelCode: string;
  channelName: string | null;
  carrierCode: string | null;
  carrierName: string | null;
  shippingMethodCode: string;
  shippingMethodName: string | null;
  chargeBasis: string;
  chargeBasisLabel: string | null;
  amount: string | null;
  baseAmount: string | null;
  surchargeAmount: string | null;
  currency: string;
  chargeableQuantity: string | null;
  quantity: string | null;
  occurredOn: string | null;
  source: string;
  sourceLabel: string | null;
  sourceRef: string;
  note: string;
  isVoid: boolean;
  voidReason: string | null;
  voidedAt: string | null;
  createdAt: string | null;
}

/** 实际运费台账分页结果。 */
export interface FreightActualListPage {
  items: FreightActualRow[];
  total: number;
  page: number;
  size: number;
  note: string;
}

/** 登记实际运费的入参。 */
export interface FreightActualInput {
  channelCode: string;
  channelName: string | null;
  carrierCode: string | null;
  carrierName: string | null;
  shippingMethodCode: string;
  shippingMethodName: string | null;
  chargeBasis: string;
  amount: string;
  baseAmount: string | null;
  surchargeAmount: string | null;
  currency: string;
  chargeableQuantity: string;
  quantity: string;
  occurredOn: string;
  sourceRef: string;
  note: string;
}

/** 登记 / 作废实际运费的结果。 */
export interface FreightActualSaved {
  id: string;
  amount: string | null;
  currency: string | null;
  occurredOn: string | null;
}

/** 测算里的一步。 */
export interface FreightEstimateStep {
  step: string;
  label: string;
  basis: string;
  amount: string | null;
  currency: string | null;
  applicable: boolean;
  note: string;
}

/** 一次渠道运费测算的完整结果。 */
export interface FreightEstimate {
  channelCode: string;
  carrierCode: string | null;
  shippingMethodCode: string;
  chargeBasis: string | null;
  currency: string | null;
  evaluatedAt: string;
  chargeableQuantity: string | null;
  quantityUnit: string | null;
  quantity: string | null;
  steps: FreightEstimateStep[];
  baseAmount: string | null;
  surchargeAmount: string | null;
  totalAmount: string | null;
  complete: boolean;
  note: string;
}

/** 头程费测算里的一步。 */
export interface HeadFreightEstimateStep {
  step: string;
  label: string;
  basis: string;
  amount: string | null;
  currency: string | null;
  applicable: boolean;
  note: string;
}

/** 一次头程费测算的完整结果。 */
export interface HeadFreightEstimate {
  shippingMethodCode: string;
  originCode: string;
  destinationCode: string;
  evaluatedAt: string;
  steps: HeadFreightEstimateStep[];
  totalAmount: string | null;
  currency: string | null;
  complete: boolean;
  note: string;
}

/** 一组运费差异对照。 */
export interface FreightVarianceGroup {
  channelCode: string;
  shippingMethodCode: string;
  currency: string | null;
  actualAmount: string | null;
  estimatedAmount: string | null;
  varianceAmount: string | null;
  actualCount: number;
  comparableCount: number;
  note: string;
}

/** 运费差异对照报告。 */
export interface FreightVarianceReport {
  available: boolean;
  reason: string;
  from: string;
  to: string;
  groups: FreightVarianceGroup[];
  note: string;
}

// ================================================================== 筛选

/** 渠道计费规则筛选。 */
export interface FreightRuleFilters {
  keyword: string;
  channelCode: string;
  carrierCode: string;
  shippingMethodCode: string;
  chargeBasis: string;
  status: string;
  page: number;
  size: number;
}

export const EMPTY_FREIGHT_RULE_FILTERS: FreightRuleFilters = {
  keyword: '',
  channelCode: '',
  carrierCode: '',
  shippingMethodCode: '',
  chargeBasis: '',
  status: '',
  page: 0,
  size: 20,
};

/** 附加费筛选。 */
export interface FreightSurchargeFilters {
  keyword: string;
  channelCode: string;
  shippingMethodCode: string;
  surchargeType: string;
  status: string;
  page: number;
  size: number;
}

export const EMPTY_FREIGHT_SURCHARGE_FILTERS: FreightSurchargeFilters = {
  keyword: '',
  channelCode: '',
  shippingMethodCode: '',
  surchargeType: '',
  status: '',
  page: 0,
  size: 20,
};

/** 头程费用结构筛选。 */
export interface HeadStructureFilters {
  keyword: string;
  shippingMethodCode: string;
  originCode: string;
  destinationCode: string;
  status: string;
  page: number;
  size: number;
}

export const EMPTY_HEAD_STRUCTURE_FILTERS: HeadStructureFilters = {
  keyword: '',
  shippingMethodCode: '',
  originCode: '',
  destinationCode: '',
  status: '',
  page: 0,
  size: 20,
};

/** 实际运费筛选。 */
export interface FreightActualFilters {
  channelCode: string;
  shippingMethodCode: string;
  from: string;
  to: string;
  page: number;
  size: number;
}

export const EMPTY_FREIGHT_ACTUAL_FILTERS: FreightActualFilters = {
  channelCode: '',
  shippingMethodCode: '',
  from: '',
  to: '',
  page: 0,
  size: 20,
};

// ================================================================== 文案

/** 计费口径 → 中文名。 */
export function chargeBasisLabel(value: string | null | undefined): string {
  switch (value) {
    case 'BY_WEIGHT':
      return '按重量';
    case 'BY_VOLUME':
      return '按体积';
    case 'BY_PIECE':
      return '按件数';
    default:
      return value || '未维护';
  }
}

/** 附加费类型 → 中文名。 */
export function surchargeTypeLabel(value: string | null | undefined): string {
  switch (value) {
    case 'FUEL':
      return '燃油附加';
    case 'PEAK_SEASON':
      return '旺季附加';
    case 'REMOTE_AREA':
      return '偏远附加';
    case 'OTHER':
      return '其它附加费';
    default:
      return value || '未维护';
  }
}

/** 附加费计算口径 → 中文名。 */
export function surchargeBasisLabel(value: string | null | undefined): string {
  switch (value) {
    case 'PERCENT_OF_BASE_FREIGHT':
      return '基础运费比例';
    case 'FIXED_PER_ORDER':
      return '每单固定额';
    case 'FIXED_PER_UNIT':
      return '每件固定额';
    default:
      return value || '未维护';
  }
}

/** 档位计价形态 → 中文名。 */
export function priceModeLabel(value: string | null | undefined): string {
  switch (value) {
    case 'FLAT_PER_TIER':
      return '整档一口价';
    case 'FIRST_PLUS_ADDITIONAL':
      return '首段加续段';
    default:
      return value || '未维护';
  }
}

/** 头程成本项类型 → 中文名。 */
export function headComponentTypeLabel(value: string | null | undefined): string {
  switch (value) {
    case 'LINE_HAUL':
      return '主运费';
    case 'CUSTOMS':
      return '报关费';
    case 'TRUCKING':
      return '拖车费';
    case 'INSURANCE':
      return '保险费';
    case 'HANDLING':
      return '操作费';
    case 'OTHER':
      return '其它成本项';
    default:
      return value || '未维护';
  }
}

/** 头程成本项计价基数 → 中文名。 */
export function headCalcBasisLabel(value: string | null | undefined): string {
  switch (value) {
    case 'PER_SHIPMENT':
      return '每票';
    case 'PER_CBM':
      return '每立方米';
    case 'PER_CONTAINER':
      return '每柜';
    case 'PER_KG':
      return '每公斤';
    default:
      return value || '未维护';
  }
}

/** 版本状态 → 中文名。 */
export function statusLabel(value: string | null | undefined): string {
  switch (value) {
    case 'ACTIVE':
      return '生效中';
    case 'SUPERSEDED':
      return '已被取代';
    case 'DISABLED':
      return '已停用';
    default:
      return value || '未维护';
  }
}

/** 实际运费的来源 → 人话标签。🔴 绝不写成「结算对账结果」（上游没有那个事实）。 */
export function actualSourceLabel(value: string | null | undefined): string {
  switch (value) {
    case 'MANUAL_OBSERVATION':
      return '人工登记的物流商账单观察值';
    default:
      return value || '未知来源';
  }
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

/**
 * 费率（小数口径）→ 百分比串。
 *
 * <p>后端存的是**小数**（0.15 = 15%）。页面上要显示 15% 而不是 0.15 ——
 * 费率都是按百分比谈的，写成 0.15 会让人怀疑是不是少了一个零。
 */
export function rateText(value: string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  const parsed = Number(value);
  if (Number.isNaN(parsed)) return value;
  const percent = parsed * 100;
  return `${Number(percent.toFixed(6))}%`;
}

/** ISO 时刻 → 只到秒的串。 */
export function timeText(value: string | null | undefined, empty = '—'): string {
  if (!value) return empty;
  return value.replace('T', ' ');
}

/** ISO 时刻 → 只到日的串。 */
export function dayText(value: string | null | undefined, empty = '—'): string {
  if (!value) return empty;
  return value.slice(0, 10);
}

/** 规则的作用域一句话（页面上要看得懂这条规则管到哪）。 */
export function ruleScopeText(rule: FreightRuleRow): string {
  const parts = [`渠道 ${rule.channelCode}`];
  parts.push(rule.carrierCode ? `承运商 ${rule.carrierCode}` : '通用承运商');
  parts.push(`运输方式 ${rule.shippingMethodCode}`);
  parts.push(chargeBasisLabel(rule.chargeBasis));
  return parts.join(' / ');
}

/** 头程费用结构的作用域一句话。 */
export function headScopeText(structure: HeadFreightStructureRow): string {
  return [
    `运输方式 ${structure.shippingMethodCode}`,
    `起运地 ${structure.originCode}`,
    `目的地 ${structure.destinationCode}`,
  ].join(' / ');
}

// ================================================================== 本地校验

function isNumber(value: string): boolean {
  return /^-?\d+(\.\d+)?$/.test(value.trim());
}

/** 规则表单的本地校验。前端这道校验是为了**早点告诉人**，不是安全边界。 */
export function validateRule(input: FreightRuleInput): string[] {
  const problems: string[] = [];
  if (!input.channelCode.trim()) problems.push('渠道必填：渠道计费规则挂在渠道维度上');
  if (!input.shippingMethodCode.trim()) problems.push('运输方式必填');
  if (!input.chargeBasis) problems.push('计费口径必填');
  if (!input.currency.trim() || !/^[A-Za-z]{3}$/.test(input.currency.trim())) {
    problems.push('币种必填（ISO 4217 三位字母码）');
  }
  if (!input.effectiveFrom.trim()) problems.push('生效起点必填');
  if (input.effectiveTo && input.effectiveTo.trim() && !input.effectiveFrom.trim()) {
    problems.push('填了生效终点就必填生效起点');
  }
  if (input.volumetricDivisor && input.volumetricDivisor.trim()) {
    if (!isNumber(input.volumetricDivisor) || Number(input.volumetricDivisor) <= 0) {
      problems.push('体积重换算系数必须大于 0');
    }
    if (input.chargeBasis && input.chargeBasis !== 'BY_WEIGHT') {
      problems.push('体积重换算系数只对「按重量」计费有意义');
    }
  }
  if (input.minimumCharge && input.minimumCharge.trim() && !isNumber(input.minimumCharge)) {
    problems.push('最低收费请填非负数字');
  }
  if (!input.tiers.length) problems.push('至少要有一档阶梯：没有档位就永远算不出基础运费');
  input.tiers.forEach((tier) => {
    if (tier.seq <= 0) problems.push('档位序号必须从 1 起');
    if (!tier.minValue.trim() || !isNumber(tier.minValue) || Number(tier.minValue) < 0) {
      problems.push(`第 ${tier.seq} 档下界必须是非负数字`);
    }
    if (tier.maxValue && tier.maxValue.trim()) {
      if (!isNumber(tier.maxValue) || Number(tier.maxValue) <= Number(tier.minValue)) {
        problems.push(`第 ${tier.seq} 档上界必须严格大于下界`);
      }
    }
    if (tier.priceMode === 'FLAT_PER_TIER') {
      if (!tier.flatPrice || !tier.flatPrice.trim()) {
        problems.push(`第 ${tier.seq} 档必须填整档价`);
      } else if (!isNumber(tier.flatPrice) || Number(tier.flatPrice) < 0) {
        problems.push(`第 ${tier.seq} 档整档价请填非负数字`);
      }
      if (tier.firstPrice || tier.additionalPrice || tier.firstLimit) {
        problems.push(`第 ${tier.seq} 档是整档一口价，不填首段 / 续段参数`);
      }
    } else if (tier.priceMode === 'FIRST_PLUS_ADDITIONAL') {
      if (!tier.firstLimit || !tier.firstLimit.trim()) {
        problems.push(`第 ${tier.seq} 档必须填首段上限`);
      } else if (
        !isNumber(tier.firstLimit) ||
        Number(tier.firstLimit) <= Number(tier.minValue || '0')
      ) {
        problems.push(`第 ${tier.seq} 档首段上限必须严格大于档位下界`);
      }
      if (!tier.firstPrice || !tier.firstPrice.trim() || !isNumber(tier.firstPrice)) {
        problems.push(`第 ${tier.seq} 档必须填首段单价`);
      }
      if (
        !tier.additionalPrice ||
        !tier.additionalPrice.trim() ||
        !isNumber(tier.additionalPrice)
      ) {
        problems.push(`第 ${tier.seq} 档必须填续段单价`);
      }
      if (tier.flatPrice) problems.push(`第 ${tier.seq} 档是首段加续段，不填整档价`);
    } else {
      problems.push(`第 ${tier.seq} 档计价形态不认识：${tier.priceMode}`);
    }
  });
  const sorted = [...input.tiers].sort((x, y) => x.seq - y.seq);
  for (let i = 1; i < sorted.length; i += 1) {
    const previous = sorted[i - 1]!;
    const current = sorted[i]!;
    if (!previous.maxValue || !previous.maxValue.trim()) {
      problems.push(`第 ${previous.seq} 档没有上界（无上限），后面不能再有档位`);
    } else if (Number(previous.maxValue) !== Number(current.minValue)) {
      problems.push(
        `阶梯必须首尾相接：第 ${previous.seq} 档的上界（${previous.maxValue}）与第 ${current.seq} 档的下界（${current.minValue}）之间不能有缝`,
      );
    }
  }
  return problems;
}

/** 附加费表单的本地校验。 */
export function validateSurcharge(input: FreightSurchargeInput): string[] {
  const problems: string[] = [];
  if (!input.channelCode.trim()) problems.push('渠道必填');
  if (!input.shippingMethodCode.trim()) problems.push('运输方式必填');
  if (!input.surchargeType) problems.push('附加费类型必填');
  if (!input.calcBasis) problems.push('计算口径必填');
  if (!input.currency.trim() || !/^[A-Za-z]{3}$/.test(input.currency.trim())) {
    problems.push('币种必填（ISO 4217 三位字母码）');
  }
  if (!input.effectiveFrom.trim()) problems.push('生效起点必填');
  if (input.calcBasis === 'PERCENT_OF_BASE_FREIGHT') {
    if (!input.rate || !input.rate.trim()) {
      problems.push('按基础运费比例的附加费必须填费率');
    } else if (!isNumber(input.rate) || Number(input.rate) < 0 || Number(input.rate) > 1) {
      problems.push('费率必须在 0 到 1 之间（小数口径：0.15 = 15%）：' + input.rate);
    }
    if (input.fixedAmount) problems.push('按比例的附加费不填固定额');
  } else if (input.calcBasis) {
    if (!input.fixedAmount || !input.fixedAmount.trim()) {
      problems.push('固定额形态的附加费必须填固定额');
    } else if (!isNumber(input.fixedAmount) || Number(input.fixedAmount) < 0) {
      problems.push('固定额请填非负数字');
    }
    if (input.rate) problems.push('固定额形态的附加费不填费率');
  }
  return problems;
}

/** 头程费用结构表单的本地校验。 */
export function validateHeadStructure(input: HeadFreightStructureInput): string[] {
  const problems: string[] = [];
  if (!input.shippingMethodCode.trim()) problems.push('运输方式必填');
  if (!input.originCode.trim()) problems.push('起运地必填');
  if (!input.destinationCode.trim()) problems.push('目的地必填');
  if (!input.currency.trim() || !/^[A-Za-z]{3}$/.test(input.currency.trim())) {
    problems.push('币种必填（ISO 4217 三位字母码）');
  }
  if (!input.effectiveFrom.trim()) problems.push('生效起点必填');
  if (!input.components.length) problems.push('至少要有一个成本项：没有成本项就构不成费用结构');
  input.components.forEach((component) => {
    if (component.seq <= 0) problems.push('成本项序号必须从 1 起');
    if (!component.componentType) problems.push('成本项类型必填');
    if (!component.calcBasis) problems.push('计价基数必填');
    if (!component.unitAmount.trim() || !isNumber(component.unitAmount)) {
      problems.push('单位费用请填非负数字');
    } else if (Number(component.unitAmount) < 0) {
      problems.push('单位费用不能为负');
    }
    if (!component.currency.trim() || !/^[A-Za-z]{3}$/.test(component.currency.trim())) {
      problems.push('成本项币种必填（ISO 4217 三位字母码）');
    }
  });
  return problems;
}

/** 实际运费表单的本地校验。**计费量与件数必填**（没有它们算不出差异）。 */
export function validateActual(input: FreightActualInput): string[] {
  const problems: string[] = [];
  if (!input.channelCode.trim()) problems.push('渠道必填');
  if (!input.shippingMethodCode.trim()) problems.push('运输方式必填');
  if (!input.chargeBasis) problems.push('计费口径必填');
  if (!input.amount.trim() || !isNumber(input.amount)) {
    problems.push('实际发生额请填数字（可负 = 冲回）');
  }
  if (!input.currency.trim() || !/^[A-Za-z]{3}$/.test(input.currency.trim())) {
    problems.push('币种必填（ISO 4217 三位字母码）');
  }
  if (!input.chargeableQuantity.trim()) {
    problems.push('计费量必填：没有它就按规则重算不出预期值，差异算不出来');
  } else if (!isNumber(input.chargeableQuantity) || Number(input.chargeableQuantity) < 0) {
    problems.push('计费量请填非负数字');
  }
  if (!input.quantity.trim()) {
    problems.push('件数必填：每件固定额的附加费要用它');
  } else if (!isNumber(input.quantity) || Number(input.quantity) < 0) {
    problems.push('件数请填非负数字');
  }
  if (!input.occurredOn) problems.push('运费发生日必填：差异对照按这一天的生效规则算');
  return problems;
}

/** 停用 / 作废理由必填。 */
export function validateDisableReason(reason: string): string[] {
  return reason.trim() ? [] : ['必须写理由：没有理由的停用会让「为什么这条费率失效了」无法解释'];
}

// ================================================================== 差异

/**
 * 差异的头条。
 *
 * <p>🔴 **不可用时必须说「算不出来」**，不可用时显示「差异 0.00」——
 * 那是在说「物流商完全按规则收」，而真实情况是「没有可比的实际运费」。
 */
export function varianceHeadline(report: FreightVarianceReport | null): {
  tone: 'success' | 'warning' | 'danger';
  text: string;
} {
  if (!report) return { tone: 'warning', text: '尚未加载' };
  if (!report.available) {
    return { tone: 'warning', text: `🔴 无法计算运费差异 —— ${report.reason}` };
  }
  const computable = report.groups.filter((g) => g.varianceAmount !== null);
  if (!computable.length) {
    return {
      tone: 'warning',
      text: '🔴 无法计算运费差异 —— 有实际运费，但找不到对应的生效规则（规则没配或规则不适用于该计费量）。',
    };
  }
  const over = computable.filter((g) => Number(g.varianceAmount) > 0);
  const under = computable.filter((g) => Number(g.varianceAmount) < 0);
  if (over.length) {
    return {
      tone: 'danger',
      text: `有 ${over.length} 组**实际比规则测算多收**了运费 —— 规则口径定低了，或物流商另收了费。`,
    };
  }
  if (under.length) {
    return {
      tone: 'warning',
      text: `有 ${under.length} 组**实际比规则测算少收**了运费 —— 规则口径定高了。`,
    };
  }
  return { tone: 'success', text: '全部对照组的实际金额与规则测算一致（差异 0.00）。' };
}

/** 差异一行的色：正数（多收）= danger，负数（少收）= warning，零 = success。 */
export function varianceTone(group: FreightVarianceGroup): 'success' | 'warning' | 'danger' {
  if (group.varianceAmount === null) return 'warning';
  const value = Number(group.varianceAmount);
  if (value > 0) return 'danger';
  if (value < 0) return 'warning';
  return 'success';
}

/** 差异一行的人话说明（null 时说明为什么算不出来）。 */
export function varianceText(group: FreightVarianceGroup): string {
  if (group.varianceAmount === null) return group.note || '差异算不出来';
  const value = Number(group.varianceAmount);
  if (value > 0) return `实际比规则测算多收 ${Math.abs(value)}`;
  if (value < 0) return `实际比规则测算少收 ${Math.abs(value)}`;
  return '与规则测算一致';
}

// ================================================================== 端点

export function fetchLogisticsFreightCapabilities(): Promise<LogisticsFreightCapabilities> {
  return api<LogisticsFreightCapabilities>('catalog/logistics-freight/capabilities');
}

export function fetchFreightRuleList(filters: FreightRuleFilters): Promise<FreightRuleListPage> {
  const query = new URLSearchParams();
  if (filters.keyword) query.set('keyword', filters.keyword);
  if (filters.channelCode) query.set('channelCode', filters.channelCode);
  if (filters.carrierCode) query.set('carrierCode', filters.carrierCode);
  if (filters.shippingMethodCode) query.set('shippingMethodCode', filters.shippingMethodCode);
  if (filters.chargeBasis) query.set('chargeBasis', filters.chargeBasis);
  if (filters.status) query.set('status', filters.status);
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return api<FreightRuleListPage>(`catalog/logistics-freight/rules?${query.toString()}`);
}

export function fetchFreightRule(ruleId: string): Promise<FreightRuleDetail> {
  return api<FreightRuleDetail>(`catalog/logistics-freight/rules/${ruleId}`);
}

export function createFreightRule(input: FreightRuleInput): Promise<FreightRuleSaved> {
  return api<FreightRuleSaved>('catalog/logistics-freight/rules', 'POST', input);
}

export function supersedeFreightRule(
  ruleId: string,
  input: FreightRuleInput,
): Promise<FreightRuleSaved> {
  return api<FreightRuleSaved>(
    `catalog/logistics-freight/rules/${ruleId}/supersede`,
    'POST',
    input,
  );
}

export function disableFreightRule(ruleId: string, reason: string): Promise<FreightRuleSaved> {
  return api<FreightRuleSaved>(`catalog/logistics-freight/rules/${ruleId}/disable`, 'POST', {
    reason,
  });
}

export function fetchFreightSurcharges(
  filters: FreightSurchargeFilters,
): Promise<FreightSurchargeListPage> {
  const query = new URLSearchParams();
  if (filters.keyword) query.set('keyword', filters.keyword);
  if (filters.channelCode) query.set('channelCode', filters.channelCode);
  if (filters.shippingMethodCode) query.set('shippingMethodCode', filters.shippingMethodCode);
  if (filters.surchargeType) query.set('surchargeType', filters.surchargeType);
  if (filters.status) query.set('status', filters.status);
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return api<FreightSurchargeListPage>(`catalog/logistics-freight/surcharges?${query.toString()}`);
}

export function createFreightSurcharge(
  input: FreightSurchargeInput,
): Promise<FreightSurchargeSaved> {
  return api<FreightSurchargeSaved>('catalog/logistics-freight/surcharges', 'POST', input);
}

export function disableFreightSurcharge(
  surchargeId: string,
  reason: string,
): Promise<FreightSurchargeSaved> {
  return api<FreightSurchargeSaved>(
    `catalog/logistics-freight/surcharges/${surchargeId}/disable`,
    'POST',
    { reason },
  );
}

export function fetchHeadStructures(
  filters: HeadStructureFilters,
): Promise<HeadFreightStructureListPage> {
  const query = new URLSearchParams();
  if (filters.keyword) query.set('keyword', filters.keyword);
  if (filters.shippingMethodCode) query.set('shippingMethodCode', filters.shippingMethodCode);
  if (filters.originCode) query.set('originCode', filters.originCode);
  if (filters.destinationCode) query.set('destinationCode', filters.destinationCode);
  if (filters.status) query.set('status', filters.status);
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return api<HeadFreightStructureListPage>(
    `catalog/logistics-freight/head-freight/structures?${query.toString()}`,
  );
}

export function fetchHeadStructure(structureId: string): Promise<HeadFreightStructureDetail> {
  return api<HeadFreightStructureDetail>(
    `catalog/logistics-freight/head-freight/structures/${structureId}`,
  );
}

export function createHeadStructure(
  input: HeadFreightStructureInput,
): Promise<HeadFreightStructureSaved> {
  return api<HeadFreightStructureSaved>(
    'catalog/logistics-freight/head-freight/structures',
    'POST',
    input,
  );
}

export function disableHeadStructure(
  structureId: string,
  reason: string,
): Promise<HeadFreightStructureSaved> {
  return api<HeadFreightStructureSaved>(
    `catalog/logistics-freight/head-freight/structures/${structureId}/disable`,
    'POST',
    { reason },
  );
}

export function fetchFreightEstimate(params: {
  channelCode: string;
  carrierCode?: string | null;
  shippingMethodCode: string;
  chargeBasis?: string | null;
  weightKg?: string | null;
  lengthCm?: string | null;
  widthCm?: string | null;
  heightCm?: string | null;
  pieces?: string | null;
}): Promise<FreightEstimate> {
  const query = new URLSearchParams();
  query.set('channelCode', params.channelCode);
  if (params.carrierCode) query.set('carrierCode', params.carrierCode);
  query.set('shippingMethodCode', params.shippingMethodCode);
  if (params.chargeBasis) query.set('chargeBasis', params.chargeBasis);
  if (params.weightKg) query.set('weightKg', params.weightKg);
  if (params.lengthCm) query.set('lengthCm', params.lengthCm);
  if (params.widthCm) query.set('widthCm', params.widthCm);
  if (params.heightCm) query.set('heightCm', params.heightCm);
  if (params.pieces) query.set('pieces', params.pieces);
  return api<FreightEstimate>(`catalog/logistics-freight/estimate?${query.toString()}`);
}

export function fetchHeadFreightEstimate(params: {
  shippingMethodCode: string;
  originCode: string;
  destinationCode: string;
  shipments?: string | null;
  cbm?: string | null;
  containers?: string | null;
  weightKg?: string | null;
}): Promise<HeadFreightEstimate> {
  const query = new URLSearchParams();
  query.set('shippingMethodCode', params.shippingMethodCode);
  query.set('originCode', params.originCode);
  query.set('destinationCode', params.destinationCode);
  if (params.shipments) query.set('shipments', params.shipments);
  if (params.cbm) query.set('cbm', params.cbm);
  if (params.containers) query.set('containers', params.containers);
  if (params.weightKg) query.set('weightKg', params.weightKg);
  return api<HeadFreightEstimate>(
    `catalog/logistics-freight/head-freight/estimate?${query.toString()}`,
  );
}

export function fetchFreightActuals(filters: FreightActualFilters): Promise<FreightActualListPage> {
  const query = new URLSearchParams();
  if (filters.channelCode) query.set('channelCode', filters.channelCode);
  if (filters.shippingMethodCode) query.set('shippingMethodCode', filters.shippingMethodCode);
  if (filters.from) query.set('from', filters.from);
  if (filters.to) query.set('to', filters.to);
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return api<FreightActualListPage>(`catalog/logistics-freight/actuals?${query.toString()}`);
}

export function recordFreightActual(input: FreightActualInput): Promise<FreightActualSaved> {
  return api<FreightActualSaved>('catalog/logistics-freight/actuals', 'POST', input);
}

export function voidFreightActual(actualId: string, reason: string): Promise<FreightActualSaved> {
  return api<FreightActualSaved>(`catalog/logistics-freight/actuals/${actualId}/void`, 'POST', {
    reason,
  });
}

export function fetchFreightVariance(params: {
  channelCode?: string | null;
  carrierCode?: string | null;
  shippingMethodCode?: string | null;
  from: string;
  to: string;
}): Promise<FreightVarianceReport> {
  const query = new URLSearchParams();
  if (params.channelCode) query.set('channelCode', params.channelCode);
  if (params.carrierCode) query.set('carrierCode', params.carrierCode);
  if (params.shippingMethodCode) query.set('shippingMethodCode', params.shippingMethodCode);
  query.set('from', params.from);
  query.set('to', params.to);
  return api<FreightVarianceReport>(`catalog/logistics-freight/variance?${query.toString()}`);
}
