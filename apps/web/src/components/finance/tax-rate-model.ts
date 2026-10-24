import type {
  FinanceTaxBindingInput,
  FinanceTaxBindingPage,
  FinanceTaxBindingRow,
  FinanceTaxJurisdictionOptions,
  FinanceTaxRateCapabilities,
  FinanceTaxRateComparison,
  FinanceTaxRateInput,
  FinanceTaxRateOptions,
  FinanceTaxRatePage,
  FinanceTaxRateRow,
  FinanceTaxResolution,
  FinanceTaxShopOptions,
  FinanceTaxStateRateRules,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 12.6.1.1 税率配置的前端模型。
 *
 * <p>蓝图 12-finance.md：「维护税率配置：目的国/地区的销售税率、VAT 税率、IOSS 适用税率、
 * 代扣代缴税；支持按店铺站点绑定默认税率与税码。」关系：「上游 = 2 店铺站点、16 合规；
 * 下游 = 9 订单（税额）、12.6.1.2 申报。<b>税率必须带生效时间</b>，历史订单用当时税率，
 * 不能被新政策追溯修改。」
 *
 * <p><b>命名空间</b>：{@code finance/tax-rates}，与 12.4.1.1 的 {@code finance/cost-valuation}、
 * 12.5.2.1 的 {@code finance/vouchers} <b>刻意不共用前缀</b> —— 那是别的模块的命名空间。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>系统提供结构与辖区选项，不替用户决定税率</b>：页面摆出四个税种的口径说明，
 *       但**零预置税率**，也不标任何「推荐税率」。</li>
 *   <li><b>改税率 = 追加版本</b>：页面上永远能看到「版本 N / 生效区间 / 与上一版的差异」，
 *       并明确提示<b>历史订单不会被追溯改写</b>。</li>
 *   <li><b>命中不到就是命中不到</b>：取税率失败时页面显示服务端的原话（"该时点无生效税率"），
 *       <b>绝不显示 0%</b> —— 「没有税率」和「税率是 0」是两件完全不同的事。</li>
 *   <li><b>州销售税归 channel</b>：页面上的州销售税区块**只读引用** channel 16.3.2.3，
 *       并标明来源模块；读不到时显示「读不到」，<b>不是</b>「没有州销售税」。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有录过税率」。</li>
 * </ul>
 */

const base = 'finance/tax-rates';

/**
 * 税率列表的筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface TaxRateParams {
  taxType?: string | undefined;
  jurisdictionCode?: string | undefined;
  taxCode?: string | undefined;
  status?: string | undefined;
  keyword?: string | undefined;
}

/** 店铺绑定列表的筛选条件。 */
export interface TaxBindingParams {
  shopId?: string | undefined;
  taxType?: string | undefined;
  jurisdictionCode?: string | undefined;
  status?: string | undefined;
  keyword?: string | undefined;
}

/** 取税率的入参（订单日期 + 店铺站点 + 辖区）。 */
export interface TaxResolveParams {
  taxType: string;
  asOf?: string | undefined;
  shopId?: string | undefined;
  jurisdictionCode?: string | undefined;
  taxCode?: string | undefined;
}

function query(params: Record<string, string | number | boolean | undefined>): string {
  const parts: string[] = [];
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined) continue;
    if (typeof value === 'string' && value === '') continue;
    parts.push(`${key}=${encodeURIComponent(String(value))}`);
  }
  return parts.length ? `?${parts.join('&')}` : '';
}

// ================================================================ 端点

/** 能力声明：哪些上游真接通、职责怎么切、哪些还没交付、归谁。 */
export function fetchTaxRateCapabilities() {
  return api<FinanceTaxRateCapabilities>(`${base}/capabilities`);
}

/** 筛选项 + 四个税种选项（系统提供选项，不替用户决定税率）。 */
export function fetchTaxRateOptions() {
  return api<FinanceTaxRateOptions>(`${base}/options`);
}

/** 辖区选项：实时读 channel 的受控辖区码表。 */
export function fetchTaxJurisdictions() {
  return api<FinanceTaxJurisdictionOptions>(`${base}/jurisdictions`);
}

/** 店铺站点选项：实时读 channel 的店铺目录。 */
export function fetchTaxShops() {
  return api<FinanceTaxShopOptions>(`${base}/shops`);
}

/** 税率记录列表（真分页 + 真筛选）。 */
export function fetchTaxRates(params: TaxRateParams = {}) {
  return api<FinanceTaxRatePage>(
    `${base}/rates${query({
      taxType: params.taxType,
      jurisdictionCode: params.jurisdictionCode,
      taxCode: params.taxCode,
      status: params.status,
      keyword: params.keyword,
    })}`,
  );
}

/** 登记 / 换税率（同链已有生效版本 ⇒ 追加新版本）。 */
export function upsertTaxRate(input: FinanceTaxRateInput) {
  return api<FinanceTaxRateRow>(`${base}/rates`, 'POST', input);
}

/** 版本历史（新到旧）。 */
export function fetchTaxRateVersions(taxType: string, jurisdictionCode: string, taxCode: string) {
  return api<FinanceTaxRateRow[]>(
    `${base}/rate-versions${query({ taxType, jurisdictionCode, taxCode })}`,
  );
}

/** 版本对比（换税率前 / 后）。 */
export function fetchTaxRateComparison(
  taxType: string,
  jurisdictionCode: string,
  taxCode: string,
  fromVersion: number,
  toVersion: number,
) {
  return api<FinanceTaxRateComparison>(
    `${base}/rate-compare${query({ taxType, jurisdictionCode, taxCode, fromVersion, toVersion })}`,
  );
}

/**
 * 按生效时间取税率（订单日期 + 店铺站点 + 辖区）。
 *
 * <p>🔴 命中不到时后端直接报错，页面**不假装**命中，也不显示 0%。
 */
export function resolveTaxRate(params: TaxResolveParams) {
  return api<FinanceTaxResolution>(
    `${base}/resolve${query({
      taxType: params.taxType,
      asOf: params.asOf,
      shopId: params.shopId,
      jurisdictionCode: params.jurisdictionCode,
      taxCode: params.taxCode,
    })}`,
  );
}

/** 店铺绑定列表（真分页 + 真筛选）。 */
export function fetchTaxBindings(params: TaxBindingParams = {}) {
  return api<FinanceTaxBindingPage>(
    `${base}/bindings${query({
      shopId: params.shopId,
      taxType: params.taxType,
      jurisdictionCode: params.jurisdictionCode,
      status: params.status,
      keyword: params.keyword,
    })}`,
  );
}

/** 绑定 / 换绑（同链已有生效绑定 ⇒ 追加新版本）。 */
export function upsertTaxBinding(input: FinanceTaxBindingInput) {
  return api<FinanceTaxBindingRow>(`${base}/bindings`, 'POST', input);
}

/** 绑定版本历史（新到旧）。 */
export function fetchTaxBindingVersions(shopId: string, taxType: string, jurisdictionCode: string) {
  return api<FinanceTaxBindingRow[]>(
    `${base}/binding-versions${query({ shopId, taxType, jurisdictionCode })}`,
  );
}

/** 州销售税规则（🔴 **归 channel 16.3.2.3**，本模块只读引用）。 */
export function fetchStateSalesTax(stateCode?: string) {
  return api<FinanceTaxStateRateRules>(`${base}/state-sales-tax${query({ stateCode })}`);
}

// ================================================================ 可读名

/** 税种的可读名（不认识的原样返回 —— 新增取值时页面不会显示 undefined）。 */
export function taxTypeLabel(value: string | null | undefined): string {
  switch (value) {
    case 'SALES_TAX':
      return '目的国/地区销售税率';
    case 'VAT':
      return '增值税';
    case 'IOSS':
      return 'IOSS 适用税率';
    case 'WITHHOLDING':
      return '代扣代缴税';
    default:
      return value ?? '';
  }
}

/** 税率版本状态的可读名。 */
export function taxRateStatusLabel(value: string | null | undefined): string {
  switch (value) {
    case 'ACTIVE':
      return '生效中';
    case 'SUPERSEDED':
      return '已被取代';
    case 'ARCHIVED':
      return '已撤下';
    default:
      return value ?? '';
  }
}

/** 取税率命中方式的可读名。 */
export function resolvedByLabel(value: string | null | undefined): string {
  switch (value) {
    case 'SHOP_BINDING':
      return '按店铺站点绑定';
    case 'JURISDICTION':
      return '按辖区取税率';
    default:
      return value ?? '';
  }
}

/** 税制的可读名（辖区码表自带的税制字段）。 */
export function taxRegimeLabel(value: string | null | undefined): string {
  switch (value) {
    case 'VAT':
      return '增值税';
    case 'GST':
      return '商品与服务税';
    case 'SALES_TAX':
      return '销售税';
    case 'CONSUMPTION_TAX':
      return '消费税';
    case 'NONE':
      return '无';
    default:
      return value ?? '';
  }
}

// ================================================================ 展示

/**
 * 税率的展示：百分比 + 原始小数。
 *
 * <p>🔴 **只做单位换算**（小数率 → 百分数），不做任何业务算术 ——
 * 税额由服务端算，页面不参与计算。
 *
 * <p>🔴 命中不到时后端报**错**，不会返回 0；万一拿到 0，也照样显示「0%」而不是破折号 ——
 * 0% 是一个**真实的税率**，不是「没有数据」。
 */
export function formatTaxRate(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return `${(value * 100).toFixed(2)}%`;
}

/**
 * 税率的原始小数（服务端存的那一串）。
 *
 * <p>与 {@link formatTaxRate} 并排显示，让人一眼看到「19% = 0.19」。
 */
export function taxRateDecimal(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return String(value);
}

/**
 * 生效区间的展示。
 *
 * <p>⚠️ 结束时间为空 = **一直生效**，不是「没填」。
 */
export function taxEffectiveWindow(
  from: string | null | undefined,
  to: string | null | undefined,
): string {
  const start = from ? from.slice(0, 10) : '—';
  if (!to) return `${start} 起（一直生效）`;
  return `${start} → ${to.slice(0, 10)}`;
}

/**
 * 生效区间的**时刻**展示（到分钟）。
 *
 * <p>⚠️ 生效边界是**时刻**不是日期 —— 同一分钟内两版税率都可能生效，
 * 只显示日期会把「哪一版生效」这件事说错。
 */
export function taxEffectiveMoment(from: string | null | undefined): string {
  if (!from) return '—';
  return from.slice(0, 16).replace('T', ' ');
}

/** 辖区选项的可读名（含不可用原因）。 */
export function jurisdictionLabel(
  name: string | null | undefined,
  code: string | null | undefined,
): string {
  const label = name && name.trim() ? name : (code ?? '');
  return label;
}
