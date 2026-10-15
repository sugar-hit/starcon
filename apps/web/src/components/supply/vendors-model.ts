import { api } from '../inventory/api';

/**
 * 6.1.1.1 供应商列表前端模型。
 *
 * <p>蓝图加粗句：**供应商数据主数据在 03，本模块呈现采购视角的视图与运营动作，
 * 避免同一实体两处可编辑。**
 *
 * <p>这条约束在界面上的直接后果，和 10.1.1.1 承运商列表是同一个形状：
 * 编码 / 名称 / 联系方式 / 合作等级 / 主体状态这一段**只读**，页面上没有编辑它们的入口
 * —— 它们要回往来单位档案（/partners）改。本页能写的只有采购侧那几项：
 * 合作状态、主供类目、采购侧备注，以及到货 / 质检这两张只追加的事实表。
 *
 * <p>**比率列的 null 绝不能渲染成 0%**：后端在窗口内没有记录时返回 null
 * （规则 R3，「还没交货过」与「交货全部延期」是相反的两件事）。前端再补一次
 * 同样的判断 —— 不是不信后端，而是这一列一旦显示 0%，
 * 一个刚建档、正在试单的供应商就会在列表里看起来是最差的那一家。
 */

/** 词表选项（值 / 中文名 / 提示）。 */
export interface VendorOption {
  value: string;
  label: string;
  hint: string;
}

/** 一个可选类目。 */
export interface VendorCategoryOption {
  id: string;
  code: string;
  name: string;
}

/** 一个可排序键。 */
export interface VendorSortOption {
  key: string;
  label: string;
  defaultDesc: boolean;
}

/** 指标窗口 —— 准时率与合格率是**窗口统计**，界面上必须显示是哪一段。 */
export interface VendorMetricWindow {
  from: string;
  to: string;
}

/** 一个币种上的采购金额累计。跨币种不相加（规则 R4）。 */
export interface VendorPurchaseAmount {
  currency: string;
  amount: string;
}

/** 供应商列表的一行。字段分四段，每段的写入所有者都不同。 */
export interface VendorRow {
  // ---- 段一：读自 03 party（本页只读）
  roleId: string;
  partyId: string;
  partyCode: string;
  partyName: string;
  partyType: string;
  countryCode: string | null;
  email: string | null;
  phone: string | null;
  website: string | null;
  partyStatus: string;
  statusReason: string | null;
  // ---- 段二：读自 03 party_supplier_profile（本页只读，规则 R1）
  cooperationLevel: string | null;
  paymentTermsDays: number | null;
  settlementMethod: string | null;
  supplierProfileFound: boolean;
  // ---- 段三：本模块自有（vendor_supply_profile）—— 这一段才可写
  supplyStatus: string | null;
  categoryId: string | null;
  categoryName: string | null;
  supplyNote: string | null;
  supplyProfileFound: boolean;
  supplyProfileVersion: number | null;
  // ---- 段四：四个关键指标，全部是 SQL 聚合（没有一个是存下来的字段）
  supplySkuCount: number;
  purchaseAmounts: VendorPurchaseAmount[];
  purchaseAmount: string | null;
  purchaseAmountCurrency: string | null;
  onTimeDeliveryCount: number | null;
  deliveryCount: number | null;
  onTimeRate: string | null;
  qualifiedQuantity: string | null;
  inspectedQuantity: string | null;
  qualityRate: string | null;
  lastDeliveryAt: string | null;
  lastInspectionAt: string | null;
}

/** 列表分页结果。 */
export interface VendorPage {
  items: VendorRow[];
  total: number;
  page: number;
  size: number;
  metricWindow: VendorMetricWindow;
  note: string;
}

/** 列表顶部的规模指标。 */
export interface VendorOverview {
  supplierCount: number;
  supplyProfileCount: number;
  missingProfileCount: number;
  activeSupplyCount: number;
  suspendedSupplyCount: number;
  withDeliveryRecord: number;
  withQualityRecord: number;
  onTimeRate: string | null;
  qualityRate: string | null;
  metricWindow: VendorMetricWindow;
  note: string;
}

/** 词表选项（前端下拉的数据源，与后端同源）。 */
export interface VendorListOptions {
  supplyStatuses: VendorOption[];
  cooperationLevels: VendorOption[];
  partyStatuses: VendorOption[];
  categories: VendorCategoryOption[];
  sorts: VendorSortOption[];
  note: string;
}

/** 采购侧运营档案（**不含**编码 / 名称 / 联系方式 / 合作等级 —— 那些不是本页的字段）。 */
export interface VendorSupplyProfileInput {
  supplyStatus: string;
  categoryId: string | null;
  note: string;
  version: number;
}

/** 到货记录。 */
export interface VendorDeliveryInput {
  deliveryRef: string;
  skuId: string | null;
  purchaseUnitCode: string | null;
  quantity: string;
  amount: string;
  currency: string;
  orderedAt: string;
  dueDate: string;
  receivedDate: string;
  note: string;
}

/** 质检记录。 */
export interface VendorQualityInspectionInput {
  deliveryId: string | null;
  inspectionRef: string;
  inspectedQuantity: string;
  qualifiedQuantity: string;
  inspectedAt: string;
  note: string;
}

/** 采购视角详情（列表下钻）。 */
export interface VendorDeliveryRow {
  id: string;
  deliveryRef: string;
  skuId: string | null;
  skuCode: string | null;
  skuName: string | null;
  purchaseUnitCode: string | null;
  quantity: string;
  amount: string;
  currency: string;
  orderedAt: string | null;
  dueDate: string;
  receivedDate: string;
  onTime: boolean;
  daysLate: number;
  isVoid: boolean;
  voidReason: string | null;
  note: string | null;
}

export interface VendorQualityInspectionRow {
  id: string;
  deliveryId: string | null;
  inspectionRef: string;
  inspectedQuantity: string;
  qualifiedQuantity: string;
  qualifiedRate: string | null;
  inspectedAt: string | null;
  isVoid: boolean;
  voidReason: string | null;
  note: string | null;
}

export interface VendorPurchaseView {
  summary: VendorRow;
  deliveries: VendorDeliveryRow[];
  inspections: VendorQualityInspectionRow[];
  warnings: string[];
  note: string;
}

/** 追加 / 保存的结果。 */
export interface VendorFactSaved {
  id: string;
  version: number;
}

// ================================================================== 筛选

export interface VendorFilters {
  keyword: string;
  supplyStatus: string;
  categoryId: string;
  cooperationLevel: string;
  partyStatus: string;
  currency: string;
  missingProfile: boolean;
  /** 指标窗口（ISO 串）；空串 = 用后端默认的最近 365 天。 */
  metricFrom: string;
  metricTo: string;
  sort: string;
  desc: boolean | null;
  page: number;
  size: number;
}

export const EMPTY_VENDOR_FILTERS: VendorFilters = {
  keyword: '',
  supplyStatus: '',
  categoryId: '',
  cooperationLevel: '',
  partyStatus: '',
  currency: '',
  missingProfile: false,
  metricFrom: '',
  metricTo: '',
  sort: 'partyCode',
  desc: null,
  page: 0,
  size: 20,
};

// ================================================================== 文案

/** 采购侧合作状态。与主体状态（party.status）是两个不同的决定。 */
export function supplyStatusLabel(code: string | null): string {
  switch (code) {
    case 'PENDING_REVIEW':
      return '待评估';
    case 'ACTIVE':
      return '合作中';
    case 'SUSPENDED':
      return '暂停下单';
    case 'TRIAL':
      return '试单中';
    case 'TERMINATED':
      return '已终止';
    default:
      return '待评估';
  }
}

/** 合作等级（3.6.1.2 的只读词表）。 */
export function cooperationLevelLabel(code: string | null): string {
  switch (code) {
    case 'STRATEGIC':
      return '战略供应商';
    case 'PREFERRED':
      return '优选供应商';
    case 'STANDARD':
      return '常规供应商';
    case 'TRIAL':
      return '试单供应商';
    case 'RISK':
      return '风险供应商';
    default:
      return '未建档';
  }
}

/** 主体状态（3.6.1.1 的只读词表）。 */
export function partyStatusLabel(code: string | null): string {
  switch (code) {
    case 'ACTIVE':
      return '可用';
    case 'INACTIVE':
      return '停用';
    case 'BLACKLISTED':
      return '拉黑';
    default:
      return '未知';
  }
}

export function partyStatusTone(
  code: string | null,
): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (code) {
    case 'ACTIVE':
      return 'success';
    case 'INACTIVE':
      return 'warning';
    case 'BLACKLISTED':
      return 'danger';
    default:
      return 'neutral';
  }
}

export function supplyStatusTone(
  code: string | null,
): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (code) {
    case 'ACTIVE':
      return 'success';
    case 'PENDING_REVIEW':
    case 'TRIAL':
      return 'warning';
    case 'SUSPENDED':
    case 'TERMINATED':
      return 'danger';
    default:
      return 'neutral';
  }
}

/**
 * 比率 → 展示串。
 *
 * <p>null（窗口内没有记录）显示「暂无数据」而**不是 0%** —— 见文件头。
 * 比率是 0~1 的小数（后端 BigDecimal），这里乘 100 才是百分比。
 */
export function rateLabel(rate: string | number | null | undefined): string {
  if (rate === null || rate === undefined || rate === '') return '暂无数据';
  const value = typeof rate === 'number' ? rate : Number(rate);
  if (Number.isNaN(value)) return '暂无数据';
  return `${(value * 100).toFixed(2)}%`;
}

/** 比率的展示色：低于 90% 标警示。只对**有数据**的比率生效。 */
export function rateTone(rate: string | number | null | undefined): 'success' | 'warning' | 'neutral' {
  if (rate === null || rate === undefined || rate === '') return 'neutral';
  const value = typeof rate === 'number' ? rate : Number(rate);
  if (Number.isNaN(value)) return 'neutral';
  if (value < 0.9) return 'warning';
  return 'success';
}

/** 分币种金额明细 → 展示串。跨币种绝不相加（规则 R4）。 */
export function amountListText(amounts: VendorPurchaseAmount[]): string {
  if (!amounts.length) return '暂无到货金额';
  return amounts.map((a) => `${a.currency} ${a.amount}`).join(' / ');
}

/** 指标窗口 → 展示串（只到日，窗口统计不需要精确到秒）。 */
export function windowText(window: VendorMetricWindow | null | undefined): string {
  if (!window?.from || !window?.to) return '默认窗口（最近 365 天）';
  return `${window.from.slice(0, 10)} ~ ${window.to.slice(0, 10)}`;
}

/** ISO 时刻 → 只到日的串；空值显示「无」。 */
export function dateText(value: string | null | undefined): string {
  if (!value) return '无';
  return value.slice(0, 10);
}

/** 逾期天数 → 展示串：0 是「准时 / 提前」，不是「逾期 0 天」。 */
export function daysLateText(days: number): string {
  return days > 0 ? `逾期 ${days} 天` : '准时';
}

// ================================================================== 本地校验

/**
 * 采购侧运营档案的本地校验。
 *
 * <p>前端这道校验是为了**早点告诉人**，不是安全边界 —— 后端有规则层与 DB CHECK 兜底。
 */
export function validateSupplyProfile(input: VendorSupplyProfileInput): string[] {
  const problems: string[] = [];
  if (!input.supplyStatus.trim()) problems.push('合作状态必填：它是采购侧「还跟不跟他做」的判定');
  if (input.note.length > 2000) problems.push('备注最长 2000 字');
  return problems;
}

/** 币种格式：ISO 4217 三位大写。刻意不校验它是不是真实币种（完整词表是 12 财务的资产）。 */
export function validateCurrency(currency: string): string[] {
  const value = currency.trim().toUpperCase();
  if (!value) return ['币种必填'];
  if (!/^[A-Z]{3}$/.test(value)) return [`币种请用 ISO 4217 三位大写字母码：${currency}`];
  return [];
}

/** 金额 / 数量串 → BigDecimal 可解析的非负数校验（前端只做最基础的形态拦截）。 */
function decimalProblem(label: string, raw: string, allowZero: boolean): string[] {
  const value = raw.trim();
  if (!value) return [`${label}必填`];
  if (!/^-?\d+(\.\d+)?$/.test(value)) return [`${label}请填数字：${raw}`];
  const parsed = Number(value);
  if (Number.isNaN(parsed)) return [`${label}请填数字：${raw}`];
  if (allowZero ? parsed < 0 : parsed <= 0)
    return [allowZero ? `${label}不能为负` : `${label}必须大于 0`];
  return [];
}

/** 到货记录的本地校验。日期关系也在这里拦一次（后端同样有）。 */
export function validateDelivery(input: VendorDeliveryInput): string[] {
  const problems: string[] = [];
  if (!input.deliveryRef.trim()) problems.push('到货单号必填：它是回原始 06 采购单的引用');
  problems.push(...decimalProblem('到货数量', input.quantity, false));
  problems.push(...decimalProblem('到货金额', input.amount, true));
  problems.push(...validateCurrency(input.currency));
  if (!input.orderedAt.trim()) problems.push('下单时间必填');
  if (!input.dueDate.trim()) problems.push('承诺到货日必填');
  if (!input.receivedDate.trim()) problems.push('实际到货日必填');
  // 早到不是错误：准时与否由「实际到货日 <= 承诺到货日」推出，表单里不额外问「是否准时」。
  if (input.orderedAt && input.receivedDate && input.receivedDate < input.orderedAt.slice(0, 10)) {
    problems.push('实际到货日不能早于下单日期：收货事实不可能早于下单');
  }
  return problems;
}

/** 质检记录的本地校验：合格数不得超过检验数（后端与 DB CHECK 各拦一道）。 */
export function validateInspection(input: VendorQualityInspectionInput): string[] {
  const problems: string[] = [];
  if (!input.inspectionRef.trim()) problems.push('质检单号必填');
  problems.push(...decimalProblem('检验数量', input.inspectedQuantity, false));
  problems.push(...decimalProblem('合格数量', input.qualifiedQuantity, true));
  if (!input.inspectedAt.trim()) problems.push('检验时间必填');
  const inspected = Number(input.inspectedQuantity.trim());
  const qualified = Number(input.qualifiedQuantity.trim());
  if (
    !Number.isNaN(inspected) &&
    !Number.isNaN(qualified) &&
    qualified > inspected
  ) {
    problems.push(
      `合格数量（${input.qualifiedQuantity}）不能超过检验数量（${input.inspectedQuantity}）：抽检数据录错会让合格率算出 100% 以上`,
    );
  }
  return problems;
}

/** 作废理由必填：没有理由的作废会让「准时率突然变好」无法解释。 */
export function validateVoidReason(reason: string): string[] {
  return reason.trim() ? [] : ['作废必须填理由：没有理由的作废会让「准时率突然变好」无法解释'];
}

/** 从一行铺出运营档案表单初值（编辑时用）。 */
export function toSupplyProfileForm(row: VendorRow): VendorSupplyProfileInput {
  return {
    supplyStatus: row.supplyStatus ?? 'PENDING_REVIEW',
    categoryId: row.categoryId ?? null,
    note: row.supplyNote ?? '',
    version: row.supplyProfileVersion ?? 0,
  };
}

// ================================================================== 端点

export function fetchVendorOptions(): Promise<VendorListOptions> {
  return api<VendorListOptions>('catalog/supply/vendors/options');
}

export function fetchVendorOverview(): Promise<VendorOverview> {
  return api<VendorOverview>('catalog/supply/vendors/overview');
}

export function fetchVendors(filters: VendorFilters): Promise<VendorPage> {
  const query = new URLSearchParams();
  if (filters.keyword) query.set('keyword', filters.keyword);
  if (filters.supplyStatus) query.set('supplyStatus', filters.supplyStatus);
  if (filters.categoryId) query.set('categoryId', filters.categoryId);
  if (filters.cooperationLevel) query.set('cooperationLevel', filters.cooperationLevel);
  if (filters.partyStatus) query.set('partyStatus', filters.partyStatus);
  if (filters.currency) query.set('currency', filters.currency);
  if (filters.missingProfile) query.set('missingProfile', 'true');
  if (filters.metricFrom) query.set('metricFrom', filters.metricFrom);
  if (filters.metricTo) query.set('metricTo', filters.metricTo);
  if (filters.sort) query.set('sort', filters.sort);
  if (filters.desc !== null) query.set('desc', String(filters.desc));
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return api<VendorPage>(`catalog/supply/vendors?${query.toString()}`);
}

export function fetchVendorPurchaseView(roleId: string): Promise<VendorPurchaseView> {
  return api<VendorPurchaseView>(`catalog/supply/vendors/${roleId}`);
}

/** 保存采购侧运营档案。**只写采购侧那三列** —— 主体档案不是本页的字段。 */
export function saveVendorSupplyProfile(
  roleId: string,
  input: VendorSupplyProfileInput,
): Promise<VendorFactSaved> {
  return api<VendorFactSaved>(`catalog/supply/vendors/${roleId}/profile`, 'PUT', input);
}

/** 追加到货记录（只追加，没有编辑端点）。 */
export function recordVendorDelivery(
  roleId: string,
  input: VendorDeliveryInput,
): Promise<VendorFactSaved> {
  return api<VendorFactSaved>(`catalog/supply/vendors/${roleId}/deliveries`, 'POST', input);
}

/** 追加质检记录。 */
export function recordVendorInspection(
  roleId: string,
  input: VendorQualityInspectionInput,
): Promise<VendorFactSaved> {
  return api<VendorFactSaved>(`catalog/supply/vendors/${roleId}/quality-inspections`, 'POST', input);
}

export function voidVendorDelivery(roleId: string, recordId: string, reason: string): Promise<unknown> {
  return api(`catalog/supply/vendors/${roleId}/deliveries/${recordId}/void`, 'POST', { reason });
}

export function voidVendorInspection(
  roleId: string,
  recordId: string,
  reason: string,
): Promise<unknown> {
  return api(`catalog/supply/vendors/${roleId}/quality-inspections/${recordId}/void`, 'POST', {
    reason,
  });
}

/** 导出走 3.8.2.1 的统一通道（不是本页另开一个无审计的导出端点）。 */
export const VENDOR_EXPORT_SOURCE_KEY = 'VENDOR_LIST';
