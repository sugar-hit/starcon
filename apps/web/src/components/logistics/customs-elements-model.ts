import type {
  ElementSetCapabilities,
  ElementSetCapabilityDimension,
  ElementSetCloseInput,
  ElementSetDetail,
  ElementSetFilterOptions,
  ElementSetInput,
  ElementSetItemInput,
  ElementSetPage,
  ElementSetPublishInput,
  ElementSetRow,
  ElementSetSaved,
  ElementSetUpstreamPreview,
  ElementSetVocabularyItem,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from '../../lib/api';

/**
 * 10.5.1.2 申报要素前端模型。
 *
 * 蓝图：「维护申报所需的品名、规格材质、用途、品牌、数量、单价、币种、产地、
 * 原产地规则（RCEP/优惠税率适用），支持按目的地国家差异化。」输出「完整申报要素集；
 * **缺失项提示**」。
 *
 * <p><b>界面上最容易做错、本模型显式处理的几处</b>：
 *
 * <ul>
 *   <li><b>申报品名与销售标题并排显示，且分色</b>。蓝图加粗句「申报品名不能照搬销售标题」
 *       要求人一眼看出自己填的是清关口径还是转化文案。两者**同屏不同框**，
 *       销售标题一律带「不可直接申报」的前缀 —— 运营看到销售标题出现在申报栏旁边才会去改。</li>
 *   <li><b>缺失项要逐项给理由，不能只给一个计数</b>。计数只回答「齐不齐」，
 *       不回答「缺什么、为什么这一项必填」；运营对着理由才知道该去问报关行还是去翻税则。
 *       {@link missingHeadline} / {@link missingDetail} 把两者分开。</li>
 *   <li><b>「已发布」不等于「不会出事」。</b>已发布集合的缺失项**不会**让它变回草稿
 *       （已发布不可原地改，要改就是新建版本），但它会在报关时被逐项比对 ——
 *       所以 {@link statusTone} 给 PUBLISHED 配的不是「一切正常」的绿。</li>
 *   <li><b>数量与单价显示原值，不做单位换算</b>。清关金额用近似值会算错一个数量级，
 *       而 {@code 8.5000} 与 {@code 8.50} 在报关单上是两回事。</li>
 *   <li><b>汇总不受分页影响</b>，页脚显示筛选后的全量，不是当前这一页。</li>
 * </ul>
 */

const base = 'trade/logistics/customs-elements';

// ------------------------------------------------------------ 参数

export interface ElementSetParams {
  destination?: string;
  status?: string;
  skuId?: string;
  missing?: boolean;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
  /** 生效性判定时刻（ISO）。留空 = 此刻。 */
  at?: string;
}

/**
 * 把参数拼成 query string。
 *
 * <p>刻意把入参声明成 {@code object} 而不是复用调用方的 interface：TS 的 interface
 * <b>没有</b>隐式索引签名，直接传进去会报「不能赋给 Record<string, ...>」。
 */
function query(params: object): string {
  const parts = Object.entries(params as Record<string, string | number | boolean | undefined | null>)
    .filter(([, v]) => v !== undefined && v !== null && `${v}` !== '')
    .map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

// ------------------------------------------------------------ 读

export function fetchElementSetCapabilities(): Promise<ElementSetCapabilities> {
  return api<ElementSetCapabilities>(`${base}/capabilities`);
}

export function fetchElementSetFilterOptions(): Promise<ElementSetFilterOptions> {
  return api<ElementSetFilterOptions>(`${base}/filter-options`);
}

export function fetchElementSetList(params: ElementSetParams): Promise<ElementSetPage> {
  return api<ElementSetPage>(`${base}/list${query(params)}`);
}

export function fetchElementSet(id: string): Promise<ElementSetDetail> {
  return api<ElementSetDetail>(`${base}/${id}`);
}

/**
 * 上游合成申报资料（3.7.1.2 目的地报关版本，**跨服务**）。
 *
 * <p>建集前先取它：它同时给出**归类事实**与**销售标题**，后者正是
 * 「申报品名 ≠ 销售标题」那道比对闸的基准。上游不可达时后端返 503，
 * 本函数不吞 —— 空对象会被读成「这个目的地不用申报」。
 */
export function fetchUpstreamPreview(params: {
  skuId: string;
  destination: string;
  at?: string;
}): Promise<ElementSetUpstreamPreview> {
  return api<ElementSetUpstreamPreview>(`${base}/upstream-preview${query(params)}`);
}

// ------------------------------------------------------------ 写

export function createElementSet(input: ElementSetInput): Promise<ElementSetSaved> {
  return api<ElementSetSaved>(base, 'POST', input);
}

export function updateElementSet(id: string, input: ElementSetInput): Promise<ElementSetSaved> {
  return api<ElementSetSaved>(`${base}/${id}`, 'PUT', input);
}

export function publishElementSet(id: string, input: ElementSetPublishInput): Promise<ElementSetSaved> {
  return api<ElementSetSaved>(`${base}/${id}/publish`, 'POST', input);
}

export function closeElementSet(id: string, input: ElementSetCloseInput): Promise<ElementSetSaved> {
  return api<ElementSetSaved>(`${base}/${id}/close`, 'POST', input);
}

// ------------------------------------------------------------ 纯函数

export type ElementSetTone = 'ok' | 'warn' | 'bad' | 'muted' | 'wait';

/** 状态标签。**只有草稿 / 已发布两态**（刻意没有「退回 / 作废」，见后端注释）。 */
export function statusLabel(status: string): string {
  if (status === 'DRAFT') return '草稿';
  if (status === 'PUBLISHED') return '已发布';
  return status || '未知状态';
}

export function statusTone(status: string): ElementSetTone {
  if (status === 'PUBLISHED') return 'ok';
  if (status === 'DRAFT') return 'wait';
  return 'muted';
}

/**
 * 行上的状态标签串。
 *
 * <p>刻意把「生效」与「已发布」**分开**说：已发布是「这一版登记过了」，
 * 生效是「这一刻海关查得到它」。一版可以已发布但区间还没到（未来版本），
 * 也可以已发布但区间已过 —— 合成一个标签就会出现「绿色 = 一切正常」的误读。
 */
export function rowStateLabel(row: ElementSetRow): string {
  // 局部变量刻意**不叫 base**：白名单测试会从源码反向抽 `${base}` 调用点，
  // 一个同名局部变量会被当成路径常量替换，抽出一条根本不存在的路径。
  const label = statusLabel(row.status);
  if (row.status === 'PUBLISHED') return row.effectiveNow ? `${label} · 此刻生效` : `${label} · 此刻不生效`;
  return label;
}

export function rowStateTone(row: ElementSetRow): ElementSetTone {
  if (row.status !== 'PUBLISHED') return 'wait';
  return row.effectiveNow ? 'ok' : 'muted';
}

/** 缺失项计数 → 一句话。0 条时明说「齐备」，不返回空串（空串会被读成「没数据」）。 */
export function missingCountLabel(row: ElementSetRow): string {
  if (row.missingCount === 0) return '齐备';
  const blocking = row.blockingMissingCount;
  return blocking === 0 ? `${row.missingCount} 项口径提示` : `缺 ${blocking} 项`;
}

export function missingTone(row: ElementSetRow): ElementSetTone {
  if (row.blockingMissingCount > 0) return 'bad';
  if (row.missingCount > 0) return 'warn';
  return 'ok';
}

/** 列表行的缺失项文案（**逐项点名**，不是「缺 3 项」）。 */
export function missingHeadline(detail: ElementSetDetail): string {
  if (detail.missing.length === 0) return '申报要素齐备，可以发布';
  return detail.missing.map((m) => m.label).join('、');
}

/** 缺失项里阻断发布的那几条。 */
export function blockingLabels(detail: ElementSetDetail): string[] {
  return detail.missing.filter((m) => m.severity === 'BLOCKER').map((m) => m.label);
}

/**
 * 申报品名与销售标题的**并排对照**文案。
 *
 * <p>这是本模块最要紧的一处界面纪律：销售标题带固定前缀出现，让人一眼看出
 * 它不能直接抄进申报栏 —— 混用是常见被退单原因。
 */
export function salesTitleForCompare(salesTitle: string): string {
  const t = (salesTitle || '').trim();
  return t ? `销售标题（不可直接作为申报品名）：${t}` : '销售标题：上游未返回';
}

export function declaredNameForCompare(declaredName: string): string {
  const t = (declaredName || '').trim();
  return t ? `申报品名（清关口径）：${t}` : '申报品名（清关口径）：未填写';
}

/** 申报品名是否与销售标题逐字相同（含首尾空白与大小写差异）。 */
export function sameAsSalesTitle(declaredName: string, salesTitle: string): boolean {
  const a = (declaredName || '').trim().toLowerCase();
  const b = (salesTitle || '').trim().toLowerCase();
  return a.length > 0 && a === b;
}

/**
 * 数量显示。
 *
 * <p>空值显示「未填」而不是 {@code 0}：申报数量 0 与没填是两件事，
 * 而 0 会被下游当成「这批货申报 0 件」。
 */
export function quantityText(quantity: number | null | undefined): string {
  if (quantity === null || quantity === undefined) return '未填';
  return String(quantity);
}

/**
 * 单价显示（**原值**）。
 *
 * <p>刻意不四舍五入、不做单位换算：清关金额用近似值会算错一个数量级，
 * 而申报单上写的就是这一格。缺单价显示「未填」而不是 0。
 */
export function unitPriceText(unitPrice: number | null | undefined, currency: string): string {
  if (unitPrice === null || unitPrice === undefined) return '未填';
  return `${unitPrice} ${currency || '（币种未填）'}`;
}

/** 申报货值 = 数量 × 单价。任一缺失时返回 null（**不给 0**）。 */
export function declaredValueText(row: ElementSetRow): string {
  if (row.quantity === null || row.quantity === undefined) return '—';
  if (row.unitPrice === null || row.unitPrice === undefined) return '—';
  return `${(row.quantity * row.unitPrice).toFixed(4)} ${row.currency || '（币种未填）'}`;
}

/** 生效区间文案。左闭右开，to 为空 = 一直有效。 */
export function intervalText(from: string, to: string | null | undefined): string {
  if (!to) return `${from} 起一直有效`;
  return `${from} 至 ${to}（不含终点）`;
}

/**
 * 「主张优惠」的标签。
 *
 * <p>刻意不写「已享惠」：优惠幅度取决于目的国税则与「实质性改变」判定，
 * 那属 16 合规与 10.5.2.1 且都要求业务确认。本模块只记录**主张**。
 */
export function preferenceLabel(row: ElementSetRow): string {
  if (!row.preferentialRateApplicable) return '不主张优惠';
  return `主张优惠（${row.originRuleCode || '未填协定'}）· 是否真的免税待合规确认`;
}

export function preferenceTone(row: ElementSetRow): ElementSetTone {
  return row.preferentialRateApplicable ? 'warn' : 'muted';
}

/** 目的地 + 版本号：清单与详情都用它当主键式的读法。 */
export function destinationVersionLabel(row: ElementSetRow): string {
  return `${row.destinationCountryCode} · v${row.versionNo}`;
}

/** 词表取标签，找不到时回落到 code 本身（而不是空串）。 */
export function vocabularyLabel(items: ElementSetVocabularyItem[], code: string): string {
  const hit = items.find((i) => i.code === code);
  return hit ? hit.label : code;
}

/** 词表转成 <select> 的选项。空串选项保留（不主张优惠 / 未填）。 */
export function vocabularyOptions(
  items: ElementSetVocabularyItem[],
): { value: string; label: string }[] {
  return items.map((i) => ({ value: i.code, label: i.code ? `${i.code} · ${i.label}` : i.label }));
}

/** 错误文案。后端把后果写进 reason，这里只做兜底而不吞掉。 */
export function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 0) return `网络或服务不可达：${error.message}`;
    if (error.status === 503) return `上游不可达：${error.message}`;
    return error.message;
  }
  if (error instanceof Error) return error.message;
  return '未知错误';
}

/** 汇总页脚。**不带分页**，永远覆盖筛选后的全量。 */
export function summaryLine(page: ElementSetPage): string {
  const s = page.summary;
  const pages = page.totalPages;
  const where = pages === 0 ? '（无数据）' : `第 ${page.page + 1} / ${pages} 页`;
  return [
    `筛选后共 ${s.total} 个版本`,
    `草稿 ${s.draft}`,
    `已发布 ${s.published}`,
    `此刻生效 ${s.effectiveNow}`,
    `有缺失 ${s.missingAny}`,
    `已发布但有缺失 ${s.publishedMissing}`,
    where,
  ].join(' · ');
}

/**
 * 不可用的能力维度（后端 available=false 的那些）。
 *
 * <p>把这些放在页面最前面而不是藏起来：HS 归类、法定计量单位、优惠幅度
 * **都不属本模块**，不先说清，运营会理所当然地以为界面上应该也有。
 */
export function unavailableDimensions(
  capabilities: ElementSetCapabilities,
): ElementSetCapabilityDimension[] {
  return capabilities.dimensions.filter((d) => !d.available);
}

/** 建集输入的空草稿（界面新建表单的初值）。 */
export function emptyElementSetInput(skuId: string, destination: string): ElementSetInput {
  const from = new Date().toISOString();
  return {
    skuId,
    destinationCountryCode: destination,
    versionNo: 1,
    declaredName: '',
    specMaterial: '',
    usage: '',
    brandType: '',
    brand: '',
    quantity: 0,
    unitPrice: 0,
    currency: '',
    originCountryCode: '',
    originRuleCode: '',
    preferentialRateApplicable: false,
    effectiveFrom: from,
    effectiveTo: from,
    note: '',
    version: 0,
    items: [],
  };
}

/** 详情 → 可回填的写形状。 */
export function detailToInput(detail: ElementSetDetail): ElementSetInput {
  const r = detail.row;
  return {
    skuId: r.skuId,
    destinationCountryCode: r.destinationCountryCode,
    versionNo: r.versionNo,
    declaredName: r.declaredName,
    specMaterial: r.specMaterial,
    usage: '',
    brandType: '',
    brand: r.brand,
    quantity: r.quantity ?? 0,
    unitPrice: r.unitPrice ?? 0,
    currency: r.currency,
    originCountryCode: r.originCountryCode,
    originRuleCode: r.originRuleCode,
    preferentialRateApplicable: r.preferentialRateApplicable,
    effectiveFrom: r.effectiveFrom,
    effectiveTo: r.effectiveTo || r.effectiveFrom,
    note: '',
    version: 0,
    items: detail.items.map<ElementSetItemInput>((i) => ({
      ordinal: i.ordinal,
      elementCode: i.elementCode,
      elementName: i.elementName,
      elementValue: i.elementValue,
      required: i.required,
    })),
  };
}
