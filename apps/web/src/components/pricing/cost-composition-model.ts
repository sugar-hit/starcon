import { api } from '../inventory/api';

/**
 * 5.2.1.1 成本构成前端模型。
 *
 * <p>蓝图加粗句是本模块唯一不可让步的一条：
 *
 * <blockquote>
 *
 * **成本口径必须在页面上明示用了哪种成本法**，否则同一 SKU 今天显示毛利 30% 明天 25% 无法解释。
 *
 * </blockquote>
 *
 * <p>⇒ 这一条在���面上有**两个**落点，少一个都不算数：
 *
 * <ol>
 *   <li><b>台账与明细的每一行都带着成本法</b>（{@link CostRow.costMethodLabel} /
 *       {@link CostDetail.costMethodLabel}），并且详情区把成本法 + 生效时间 + 计价单位
 *       摆在最显眼的位置 —— 运营问「这个成本是怎么算出来的」时，答案不用去别的页面找。
 *   <li><b>成本法真的参与算术</b>：三种成本法作用在同一份到货事实上，给出三个不同的单位成本。
 *       前端不自己算，但**必须把后端给的口径串显示出来**（{@link CostDetail.costMethodBasis}），
 *       否则用户看到 12.00 仍然不知道为什么今天是 12.00、昨天是 12.50。
 * </ol>
 *
 * <h2>🔴 null 绝不能渲染成 0</h2>
 *
 * <p>「取不到」与「确实是 0」是两件事：包装成本没维护 ≠ 包装成本是 0；上游读不到 ≠ 上游没有这笔钱。
 * 后端对这两种情况分别给了 {@code available=false} 与 {@code amount=null}，
 * 前端这里用 {@link moneyText} 把它们渲染成「未维护」/「取不到」而不是 0.00。
 * 渲染成 0.00 的后果是单位成本偏低、毛利偏高，而页面上看不出任何异常。
 *
 * <h2>🔴 采购成本的来源必须写在脸上</h2>
 *
 * <p>6.3 采购单未交付 ⇒ 采购成本取的是 catalog 同库的**实际到货金额**，
 * 不是采购单成交价；供应商报价只作只读参考，**不计入合计**。
 * 所以 {@link sourceLabel} 把四种来源分开标：实际到货金额 / 报价 / 上游事实 / 人工参数。
 */

/** 成本法。与后端 {@code CostMethod} 逐字对应。 */
export type CostMethod = 'MOVING_AVERAGE' | 'FIFO' | 'LOT';

/** 五个成本分项。与后端 {@code CostComponent} 逐字对应。 */
export type CostComponentCode = 'PURCHASE' | 'FIRST_MILE' | 'PACKAGING' | 'DUTY' | 'OTHER_DIRECT';

/** 分项的来源类型。与后端 {@code ComponentCapability.kind} 逐字对应。 */
export type ComponentKind = 'DERIVED' | 'PARAMETER' | 'MANUAL' | 'REFERENCE';

/** 能力声明里的一个分项。 */
export interface ComponentCapability {
  code: string;
  label: string;
  source: string;
  kind: string;
  available: boolean;
  ownerModule: string;
  impact: string;
  basis: string;
  note: string;
}

/** 成本法选项。 */
export interface MethodOption {
  value: string;
  label: string;
  basis: string;
}

/** 能力声明。页面顶部固定显示它。 */
export interface CostCapabilities {
  components: ComponentCapability[];
  costMethods: MethodOption[];
  currencies: string[];
  costBasisNote: string;
  upstreamNote: string;
  downstreamNote: string;
  sourceNote: string;
  actionsNote: string;
}

/** 列表一行 = 一个 (SKU, 计价币种) 的成本构成条目。 */
export interface CostRow {
  skuId: string;
  skuCode: string;
  skuName: string;
  spuCode: string;
  baseUnit: string;
  skuStatus: string;
  profileId: string | null;
  maintained: boolean;
  costMethod: string;
  costMethodLabel: string;
  currency: string | null;
  packagingCost: string | null;
  otherDirectCost: string | null;
  purchaseCost: string | null;
  purchaseDeliveryCount: number | null;
  revisionCount: number;
  effectiveFrom: string | null;
  updatedAt: string | null;
}

/** 列表分页结果。 */
export interface CostListPage {
  items: CostRow[];
  total: number;
  page: number;
  size: number;
  windowNote: string;
  note: string;
}

/** 一个币种上的一个金额。 */
export interface AmountRow {
  currency: string;
  amount: string | null;
}

/** 一个分项。 */
export interface CostComponentRow {
  code: string;
  label: string;
  source: string;
  kind: string;
  available: boolean;
  partial: boolean;
  unavailableReason: string | null;
  amount: string | null;
  amounts: AmountRow[];
  basis: string;
  note: string;
}

/** 供应商报价参考（**只读参考，不是采购成本，不计入合计**）。 */
export interface QuoteReference {
  available: boolean;
  currency: string | null;
  unitPrice: string | null;
  minQuantity: string | null;
  purchaseUnitCode: string | null;
  supplierRoleId: string | null;
  status: string | null;
  validFrom: string | null;
  validTo: string | null;
  reason: string | null;
  note: string;
}

/** 上游其它币种的金额：未折算，不计入合计。 */
export interface ForeignAmount {
  component: string;
  componentLabel: string;
  currency: string;
  amount: string | null;
  reason: string;
}

/** 手工调整项。 */
export interface AdjustmentRow {
  id: string;
  component: string;
  componentLabel: string;
  amount: string;
  currency: string;
  reason: string;
  effectiveFrom: string;
  isVoid: boolean;
  voidReason: string | null;
  voidedAt: string | null;
  createdAt: string;
}

/** 成本变动历史的一版。 */
export interface RevisionRow {
  id: string;
  revisionNo: number;
  costMethod: string;
  costMethodLabel: string;
  currency: string;
  purchaseCost: string | null;
  firstMileAllocation: string | null;
  packagingCost: string | null;
  dutyCost: string | null;
  otherDirectCost: string | null;
  totalUnitCost: string | null;
  snapshotComplete: boolean;
  missingComponents: string;
  effectiveFrom: string;
  changeReason: string;
  createdAt: string;
}

/** 批次（成本法 = 批次时的批次清单）。 */
export interface LotRow {
  lotRef: string;
  receivedDate: string;
  quantity: string;
  baseQuantity: string;
  amount: string;
  unitCost: string | null;
  currency: string;
  selected: boolean;
}

/** 成本构成明细。 */
export interface CostDetail {
  skuId: string;
  skuCode: string;
  skuName: string;
  spuCode: string;
  baseUnit: string;
  maintained: boolean;
  costMethod: string;
  costMethodLabel: string;
  costMethodBasis: string;
  currency: string;
  effectiveFrom: string | null;
  components: CostComponentRow[];
  totals: AmountRow[];
  totalAmount: string | null;
  totalComplete: boolean;
  missingComponents: string[];
  partialComponents: string[];
  excludedDeliveryCount: number;
  foreignAmounts: ForeignAmount[];
  adjustments: AdjustmentRow[];
  recentRevisions: RevisionRow[];
  lots: LotRow[];
  quoteReference: QuoteReference;
  windowFrom: string;
  windowTo: string;
  basis: string;
  note: string;
}

/** 保存成本构成的入参。 */
export interface ProfileInput {
  costMethod: string;
  currency: string;
  effectiveFrom: string;
  packagingCost: string | null;
  changeReason: string;
  version: number;
}

/** 追加手工调整项的入参。 */
export interface AdjustmentInput {
  component: string;
  amount: string;
  currency: string;
  reason: string;
  effectiveFrom: string;
}

/** 保存结果。 */
export interface CostSaved {
  profileId: string;
  revisionId: string;
  revisionNo: number;
  currency: string;
  effectiveFrom: string;
  version: number;
}

// ================================================================== 页面分区

/**
 * 5.2 的成本分区 —— 三个兄弟模块共用 L3 路由 {@code /pricing/cost}。
 *
 * <p>🔴 **这是本模块给兄弟模块留的扩展位约定**（会话日志里同款）：
 *
 * <ul>
 *   <li>每个分区是一个独立可渲染的区块，**有自己的 id、标题与端点命名空间**；
 *   <li>兄弟模块（5.2.1.2 到岸成本、5.2.1.3 成本分摊）**各自新增一个分区**，
 *       不重写本模块的页面骨架 —— 本模块只负责成本构成这一块；
 *   <li>分区之间不共享数据口径：本模块是 **SKU 级单位成本构成**，
 *       到岸成本是**单据级**（一批货的 CIF + 关税 + 到港），成本分摊是**规则表**
 *       （按重量/体积/金额摊到订单行）—— 三者不合并成一个「总成本」数字。
 * </ul>
 *
 * <p>未交付的分区**必须显式标成「未交付」**，不能留一个空白框让人以为已交付
 * （照 11.2.3.3 供应商索赔的先例：未交付的事实要写在页面上，而不是藏起来）。
 */
export interface CostSection {
  id: string;
  title: string;
  moduleId: string;
  moduleName: string;
  delivered: boolean;
  summary: string;
}

export const COST_SECTIONS: readonly CostSection[] = [
  {
    id: 'composition',
    title: '成本构成',
    moduleId: '5.2.1.1',
    moduleName: '成本构成',
    delivered: true,
    summary:
      'SKU 的单位成本构成：采购成本、头程分摊、包装成本、关税、其他直接成本。' +
      '采购成本按所选成本法从实际到货事实加权，其余分项来自参数或手工调整。',
  },
  {
    id: 'landed',
    title: '到岸成本',
    moduleId: '5.2.1.2',
    moduleName: '到岸成本',
    delivered: false,
    summary:
      '货到目标仓可售的总成本（采购价 + 国际头程 + 关税 + 清关 + 目的地操作费 + 尾程预备）。' +
      '🔴 5.2.1.2 **未交付**：它与本模块共用 /pricing/cost 路由，但它是**单据级**口径，' +
      '与本模块的 SKU 级单位成本不合并 —— 定价时必须选对口径。',
  },
  {
    id: 'allocation',
    title: '成本分摊',
    moduleId: '5.2.1.3',
    moduleName: '成本分摊',
    delivered: true,
    summary:
      '把混合成本的货件（一次采购多个 SKU 共用头程）按重量/体积/金额/数量规则分摊到各 SKU，' +
      '支持人工微调并留痕。**分摊规则变更会导致历史利润变化**，因此换规则是**追加一个新版本**，' +
      '旧版本原样留着 —— 没有「就地覆盖」的入口。逐行分摊额之和严格等于货件成本总额。',
  },
];

// ================================================================== 筛选

export interface CostFilters {
  keyword: string;
  costMethod: string;
  currency: string;
  /** 三态：'' = 不筛，'true' = 只看已维护，'false' = 只看未维护。 */
  maintained: string;
  /** 成本口径窗口（ISO 串）；空串 = 用后端默认的最近 90 天。 */
  windowFrom: string;
  windowTo: string;
  page: number;
  size: number;
}

export const EMPTY_COST_FILTERS: CostFilters = {
  keyword: '',
  costMethod: '',
  currency: '',
  maintained: '',
  windowFrom: '',
  windowTo: '',
  page: 0,
  size: 20,
};

// ================================================================== 文案

/** 成本法 → 中文名。后端已给 label，这里只做兜底。 */
export function costMethodLabel(value: string | null | undefined): string {
  switch (value) {
    case 'MOVING_AVERAGE':
      return '移动加权';
    case 'FIFO':
      return '先进先出';
    case 'LOT':
      return '批次';
    default:
      return value || '未维护';
  }
}

/**
 * 成本法的**口径一句话**。
 *
 * <p>页面上必须能看到「移动加权到底是怎么算的」—— 只给一个名字不够，
 * 名字背后的算法才是「今天 12.00、昨天 12.50」的唯一解释。
 */
export function costMethodBasis(method: string | null | undefined): string {
  switch (method) {
    case 'MOVING_AVERAGE':
      return '窗口内全部有效到货按「金额 ÷ 基础数量」加权平均。';
    case 'FIFO':
      return '按到货日升序消耗到目标数量后的加权平均单价（取的是最早一批还在动的价格）。';
    case 'LOT':
      return '每一条到货记录即一个批次，单位成本取目标批次的单价（不做加权）。';
    default:
      return '';
  }
}

/**
 * 分项来源 → 人话标签。
 *
 * <p>🔴 四种来源必须分得开（蓝图的硬要求）：**实际到货金额** / **报价** / **上游事实** / **人工参数**。
 * 把「实际到货金额」写成「采购成本」四个字就是在骗人：6.3 采购单当前未交付，
 * 用户会以为那是采购单的成交价。
 */
export function sourceLabel(source: string | null | undefined): string {
  switch (source) {
    case 'CATALOG_VENDOR_DELIVERY':
      return '实际到货金额';
    case 'CATALOG_QUOTE':
      return '供应商报价（未成交）';
    case 'WAREHOUSE_FIRST_MILE':
      return '头程发运单（上游事实）';
    case 'WAREHOUSE_DUTY_TAX':
      return '关税估算单（上游事实）';
    case 'CATALOG_PARAMETER':
      return '人工参数';
    case 'CATALOG_ADJUSTMENT':
      return '手工调整（带原因）';
    default:
      return source || '未知来源';
  }
}

/** 来源类型的色：派生 / 参数 / 手工 / 参考。 */
export function sourceTone(kind: string | null | undefined): 'success' | 'warning' | 'info' | 'neutral' {
  switch (kind) {
    case 'DERIVED':
      return 'success';
    case 'PARAMETER':
      return 'warning';
    case 'MANUAL':
      return 'info';
    default:
      return 'neutral';
  }
}

/**
 * 金额 → 展示串。
 *
 * <p>🔴 null 显示「取不到」而**不是 0.00**：
 * 后端把「该分项没有事实 / 上游不可达」表达成 {@code available=false} + {@code amount=null}，
 * 渲染成 0.00 会让单位成本偏低、毛利偏高，而页面上看不出任何异常。
 */
export function moneyText(value: string | null | undefined, unavailable = '取不到'): string {
  if (value === null || value === undefined || value === '') return unavailable;
  return value;
}

/** 带币种的金额串。 */
export function moneyWithCurrency(
  value: string | null | undefined,
  currency: string | null | undefined,
  unavailable = '取不到',
): string {
  const text = moneyText(value, unavailable);
  if (text === unavailable) return text;
  return currency ? `${currency} ${text}` : text;
}

/** ISO 时刻 → 只到秒的串；空值显示「未维护」。 */
export function timeText(value: string | null | undefined, empty = '未维护'): string {
  if (!value) return empty;
  return value.replace('T', ' ').replace('Z', 'Z');
}

/** ISO 时刻 → 只到日的串。 */
export function dayText(value: string | null | undefined, empty = '—'): string {
  if (!value) return empty;
  return value.slice(0, 10);
}

/** 逐币种金额明细 → 展示串（跨币种绝不相加）。 */
export function amountListText(amounts: AmountRow[]): string {
  if (!amounts.length) return '—';
  return amounts.map((a) => `${a.currency} ${moneyText(a.amount)}`).join(' / ');
}

/**
 * 成本口径一句话：成本法 + 币种 + 单位 + 生效时间。
 *
 * <p>这是页面上**必须一眼看到**的那一行。
 */
export function costBasisText(detail: CostDetail | null): string {
  if (!detail) return '';
  const effective = detail.effectiveFrom ? timeText(detail.effectiveFrom) : '未维护';
  return `成本口径 = ${detail.costMethodLabel}｜计价币种 ${detail.currency}｜单位 每 ${detail.baseUnit}｜生效时间 ${effective}`;
}

/**
 * 合计的完整度提示。
 *
 * <p>🔴 合计**不完整**时不能只给一个数 —— 那个数是「已知部分合计」，
 * 直接拿去算利润会算高。提示语要写明缺哪几项。
 */
export function totalCompleteness(detail: CostDetail | null): {
  tone: 'success' | 'warning' | 'danger';
  text: string;
} {
  if (!detail) return { tone: 'warning', text: '尚未加载' };
  if (detail.totalComplete) return { tone: 'success', text: '五个分项齐全，合计是完整成本。' };
  const missing = detail.missingComponents ?? [];
  const partial = detail.partialComponents ?? [];
  const parts: string[] = [];
  if (missing.length) parts.push(`取不到 ${missing.join('、')}`);
  if (partial.length) parts.push(`只覆盖了一部分 ${partial.join('、')}`);
  return {
    tone: missing.length ? 'danger' : 'warning',
    text: `🔴 合计是**已知部分合计**（${parts.join('；')}）—— 不要直接拿去算利润。`,
  };
}

/** 手工调整项可作用在哪些分项上（其余三项是派生值 / 上游事实）。 */
export const ADJUSTABLE_COMPONENTS: readonly { value: string; label: string; hint: string }[] = [
  { value: 'OTHER_DIRECT', label: '其他直接成本', hint: '手工调整，每笔必须带原因' },
  { value: 'PACKAGING', label: '包装成本', hint: '人工参数的人工微调，每笔必须带原因' },
];

// ================================================================== 本地校验

/** 成本构成的本地校验。前端这道校验是为了**早点告诉人**，不是安全边界。 */
export function validateProfile(input: ProfileInput): string[] {
  const problems: string[] = [];
  if (!input.costMethod) problems.push('必须选一种成本法：成本口径要在页面上明示，不能留空');
  if (!input.currency.trim()) problems.push('计价币种必填');
  else if (!/^[A-Za-z]{3}$/.test(input.currency.trim()))
    problems.push(`计价币种请用 ISO 4217 三位字母码：${input.currency}`);
  if (!input.effectiveFrom.trim()) problems.push('生效时间必填：这一版构成从哪一刻开始算数');
  if (input.packagingCost !== null && input.packagingCost.trim() !== '') {
    const value = Number(input.packagingCost.trim());
    if (Number.isNaN(value)) problems.push(`包装成本请填数字：${input.packagingCost}`);
    else if (value < 0) problems.push('包装成本不能为负');
  }
  if (!input.changeReason.trim())
    problems.push('变更原因必填：没有理由的历史等于没有历史，半年后没人知道当时为什么换口径');
  return problems;
}

/** 手工调整项的本地校验。**原因必填**（蓝图输入句「手工调整项及原因」）。 */
export function validateAdjustment(input: AdjustmentInput): string[] {
  const problems: string[] = [];
  if (!input.component) problems.push('必须选一个分项');
  if (!ADJUSTABLE_COMPONENTS.some((c) => c.value === input.component))
    problems.push('只有「其他直接成本」与「包装成本」可以手工调整 —— 其余三项是派生值或上游事实');
  const amount = input.amount.trim();
  if (!amount) problems.push('调整金额必填');
  else if (!/^-?\d+(\.\d+)?$/.test(amount)) problems.push(`调整金额请填数字：${input.amount}`);
  else if (Number(amount) === 0) problems.push('调整金额不能为 0（金额为 0 的调整行没有信息量）');
  if (!input.currency.trim()) problems.push('币种必填');
  else if (!/^[A-Za-z]{3}$/.test(input.currency.trim()))
    problems.push(`币种请用 ISO 4217 三位字母码：${input.currency}`);
  if (!input.reason.trim())
    problems.push('调整原因必填：没有原因的调整会让「成本为什么突然变了」永远答不上来');
  if (!input.effectiveFrom.trim()) problems.push('生效时间必填');
  return problems;
}

/** 作废理由必填：没有理由的作废会让「成本突然降了」无法解释。 */
export function validateVoidReason(reason: string): string[] {
  return reason.trim() ? [] : ['作废必须填理由：没有理由的作废会让「成本突然降了」无法解释'];
}

// ================================================================== 端点

export function fetchCostCapabilities(): Promise<CostCapabilities> {
  return api<CostCapabilities>('catalog/sku-cost-compositions/capabilities');
}

export function fetchCostList(filters: CostFilters): Promise<CostListPage> {
  const query = new URLSearchParams();
  if (filters.keyword) query.set('keyword', filters.keyword);
  if (filters.costMethod) query.set('costMethod', filters.costMethod);
  if (filters.currency) query.set('currency', filters.currency);
  if (filters.maintained) query.set('maintained', filters.maintained);
  if (filters.windowFrom) query.set('windowFrom', filters.windowFrom);
  if (filters.windowTo) query.set('windowTo', filters.windowTo);
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return api<CostListPage>(`catalog/sku-cost-compositions?${query.toString()}`);
}

export function fetchCostDetail(
  skuId: string,
  currency: string | null,
  windowFrom: string | null,
  windowTo: string | null,
): Promise<CostDetail> {
  const query = new URLSearchParams();
  if (currency) query.set('currency', currency);
  if (windowFrom) query.set('windowFrom', windowFrom);
  if (windowTo) query.set('windowTo', windowTo);
  const suffix = query.toString();
  return api<CostDetail>(
    `catalog/sku-cost-compositions/${skuId}${suffix ? `?${suffix}` : ''}`,
  );
}

export function fetchCostRevisions(skuId: string, currency: string | null): Promise<RevisionRow[]> {
  const suffix = currency ? `?currency=${encodeURIComponent(currency)}` : '';
  return api<RevisionRow[]>(`catalog/sku-cost-compositions/${skuId}/revisions${suffix}`);
}

/** 保存成本构成（改成本法 / 计价币种 / 包装成本参数 —— 每次都留一条历史快照）。 */
export function saveCostProfile(skuId: string, input: ProfileInput): Promise<CostSaved> {
  return api<CostSaved>(`catalog/sku-cost-compositions/${skuId}`, 'PUT', input);
}

/** 追加手工调整项（原因必填）。 */
export function addCostAdjustment(skuId: string, input: AdjustmentInput): Promise<CostSaved> {
  return api<CostSaved>(`catalog/sku-cost-compositions/${skuId}/adjustments`, 'POST', input);
}

/** 作废调整项（理由必填；作废不是删除，旧行留在库里）。 */
export function voidCostAdjustment(
  skuId: string,
  adjustmentId: string,
  reason: string,
): Promise<CostSaved> {
  return api<CostSaved>(
    `catalog/sku-cost-compositions/${skuId}/adjustments/${adjustmentId}/void`,
    'POST',
    { reason },
  );
}
