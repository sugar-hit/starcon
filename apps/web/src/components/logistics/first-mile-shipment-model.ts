import type {
  FirstMileCancelRequest,
  FirstMileCapabilities,
  FirstMileConfirmRequest,
  FirstMileCreateRequest,
  FirstMileDetail,
  FirstMileDispatchRequest,
  FirstMilePage,
  FirstMileSaved,
  FirstMileTransitLegRow,
  FirstMileUpdateRequest,
  SourceCandidatePage,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../inventory/api';

/**
 * 10.4.1.1 头程发运单前端模型。
 *
 * <p>界面上最容易误读的三处，这个模块都显式处理了：
 *
 * <ul>
 *   <li>**发出不扣在手**。头程货在发出前根本没进过我方库存账（它来自供应商或本租户
 *       另一张收货单，不是从在手扣出来的）。所以「发出后在手没少」是对的，
 *       页面必须写清这句话，否则会被当成账丢货。
 *   <li>**发出量 ≠ 计划量**。可以逐行部分发运，余量另开一张单；
 *       但「发 0」被拒（已发出是终态，这一行会永远留在计划里发不出去）。
 *       界面上每行都要同时显示计划量、发出量与余量。
 *   <li>**货权与成本节点必须看得见**：贸易术语（成本转嫁方式）与风险承担方
 *       从「已发出」那一刻起决定利润口径，所以它们是**可查询的列**，不是页脚备注。
 *       两者的不自洽组合（EXW 配 SELLER / DDP 配 BUYER）由后端拒绝，
 *       前端按下拉给的可选集合收窄，不让用户填出一个注定被拒的组合。
 * </ul>
 */

/** 列表查询条件。与后端 `FirstMileQuery` 一一对应。 */
export interface FirstMileParams {
  status?: string[];
  transportMode?: string;
  incoterm?: string;
  originWarehouseId?: string;
  destinationWarehouseId?: string;
  destinationCountryCode?: string;
  inTransitOnly?: boolean;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

export function firstMileQuery(params: FirstMileParams): string {
  const parts: string[] = [];
  for (const s of params.status ?? []) parts.push(`status=${encodeURIComponent(s)}`);
  if (params.transportMode) parts.push(`transportMode=${encodeURIComponent(params.transportMode)}`);
  if (params.incoterm) parts.push(`incoterm=${encodeURIComponent(params.incoterm)}`);
  if (params.originWarehouseId) {
    parts.push(`originWarehouseId=${encodeURIComponent(params.originWarehouseId)}`);
  }
  if (params.destinationWarehouseId) {
    parts.push(`destinationWarehouseId=${encodeURIComponent(params.destinationWarehouseId)}`);
  }
  if (params.destinationCountryCode?.trim()) {
    parts.push(
      `destinationCountryCode=${encodeURIComponent(params.destinationCountryCode.trim())}`,
    );
  }
  if (params.inTransitOnly) parts.push('inTransitOnly=true');
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export function fetchFirstMileShipments(params: FirstMileParams) {
  return api<FirstMilePage>(`warehouse/first-mile-shipments${firstMileQuery(params)}`);
}

export function fetchFirstMileCapabilities() {
  return api<FirstMileCapabilities>('warehouse/first-mile-shipments/capabilities');
}

export function fetchFirstMileDetail(shipmentId: string) {
  return api<FirstMileDetail>(`warehouse/first-mile-shipments/${encodeURIComponent(shipmentId)}`);
}

/** 选货助手：8.2.1.1 逐次实收行 + 还能发走多少。批次精确到实收行，不是精确到 SKU。 */
export function fetchSourceCandidates(params: {
  receiptId?: string;
  skuId?: string;
  warehouseId?: string;
  keyword?: string;
  page?: number;
  size?: number;
}) {
  const parts: string[] = [];
  if (params.receiptId) parts.push(`receiptId=${encodeURIComponent(params.receiptId)}`);
  if (params.skuId) parts.push(`skuId=${encodeURIComponent(params.skuId)}`);
  if (params.warehouseId) parts.push(`warehouseId=${encodeURIComponent(params.warehouseId)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return api<SourceCandidatePage>(
    `warehouse/first-mile-shipments/source-candidates${parts.length ? `?${parts.join('&')}` : ''}`,
  );
}

export function fetchTransitLegs(shipmentId: string) {
  return api<Array<FirstMileTransitLegRow>>(
    `warehouse/first-mile-shipments/${encodeURIComponent(shipmentId)}/transit-legs`,
  );
}

export function createFirstMileShipment(input: FirstMileCreateRequest) {
  return api<FirstMileSaved>('warehouse/first-mile-shipments', 'POST', input);
}

export function updateFirstMileShipment(shipmentId: string, input: FirstMileUpdateRequest) {
  return api<FirstMileSaved>(
    `warehouse/first-mile-shipments/${encodeURIComponent(shipmentId)}`,
    'PUT',
    input,
  );
}

/** 确认：申报信息齐备、已订舱。货还没走，不动任何账。 */
export function confirmFirstMileShipment(shipmentId: string, input: FirstMileConfirmRequest) {
  return api<FirstMileSaved>(
    `warehouse/first-mile-shipments/${encodeURIComponent(shipmentId)}/confirm`,
    'POST',
    input,
  );
}

/** 发出：货权转移 + 在 7.5.1.2 登记在途腿。**不扣在手**。 */
export function dispatchFirstMileShipment(shipmentId: string, input: FirstMileDispatchRequest) {
  return api<FirstMileSaved>(
    `warehouse/first-mile-shipments/${encodeURIComponent(shipmentId)}/dispatch`,
    'POST',
    input,
  );
}

export function cancelFirstMileShipment(shipmentId: string, input: FirstMileCancelRequest) {
  return api<FirstMileSaved>(
    `warehouse/first-mile-shipments/${encodeURIComponent(shipmentId)}/cancel`,
    'POST',
    input,
  );
}

/** 四态标签。未收录的码原样显示，不静默吞掉。 */
export const shipmentStatusLabels: Record<string, string> = {
  DRAFT: '草稿',
  CONFIRMED: '已确认',
  DISPATCHED: '已发出',
  CANCELLED: '已取消',
};

/** 运输方式标签。蓝图 10.4.1.1 原文点名的四类。 */
export const transportModeLabels: Record<string, string> = {
  SEA: '海运',
  AIR: '空运',
  RAIL: '铁运',
  EXPRESS: '快递',
};

/**
 * 贸易术语与风险承担方的展示。
 *
 * <p>风险承担方**不**在前端抄一份枚举 —— 它从 `capabilities.incoterms[].allowedRiskBearers`
 * 里读。抄一份的话，后端加一个风险承担方时前端会静默漂移：用户能选到一个
 * 后端必然拒绝的组合，错误信息还完全指不到「术语与风险不自洽」。
 */
export function riskBearerLabel(code: string): string {
  switch (code) {
    case 'SELLER':
      return '卖方承担';
    case 'BUYER':
      return '买方承担';
    case 'CARRIER':
      return '承运人承担';
    default:
      return code;
  }
}

/** 某个贸易术语下允许的风险承担方；能力数据没到位时返回全部（后端仍是最终裁判）。 */
export function allowedRiskBearers(
  capabilities: FirstMileCapabilities | null,
  incoterm: string,
): string[] {
  const entry = capabilities?.incoterms?.find((i) => i.value === incoterm);
  if (!entry || !entry.allowedRiskBearers?.length) {
    return ['SELLER', 'BUYER', 'CARRIER'];
  }
  return [...entry.allowedRiskBearers];
}

/** 术语与风险承担方是否自洽。与后端 allowedRiskBearer() 和 DB 的两条 CHECK 同一条纪律。 */
export function riskPairingOk(incoterm: string, bearer: string): boolean {
  // 刻意**不**走 allowedRiskBearers：那条在能力数据缺失时会宽放成三类，
  // 用它判断就会把「EXW 配卖方」判成合法。宽放只给**下拉**用（少一个选项比多一个安全），
  // 判定必须始终按规则本身算；数据缺失时后端照样会拒，这里只是提前少给一个错误选项。
  if (incoterm === 'EXW') return bearer === 'BUYER';
  if (incoterm === 'DDP') return bearer === 'SELLER';
  return true;
}

/** 一次可以推进的动作，按状态给出。 */
export function shipmentActions(status: string): string[] {
  switch (status) {
    case 'DRAFT':
      return ['edit', 'confirm', 'cancel'];
    case 'CONFIRMED':
      return ['edit', 'dispatch', 'cancel'];
    default:
      // DISPATCHED 与 CANCELLED 都是终态：
      // 已发出的货权已转、在途腿已在 7.5.1.2 名下，回退会让两者对不上。
      return [];
  }
}

/** 排序白名单。非法值后端会回落到默认键，前端不给非法选项。 */
export const shipmentSortOptions = [
  { value: 'UPDATED_AT', label: '最近更新' },
  { value: 'CREATED_AT', label: '建单时间' },
  { value: 'SHIPMENT_CODE', label: '单号' },
  { value: 'DEPART_AT', label: '计划开船' },
  { value: 'EXPECTED_ARRIVE_AT', label: '预计到港' },
];

/**
 * 某一行的余量 = 计划量 − 发出量。
 *
 * <p>用后端给的 `pendingQuantity` 而不是前端自己减：部分发运时「余量」不是一个
 * 静态值（它会随别的发运单占用而变），前端自减等于在前端复制一遍口径。
 */
export function pending(line: { pendingQuantity: number }): number {
  return Math.max(0, Number(line.pendingQuantity) || 0);
}

/**
 * 在途腿的口径自检：原发 = 已了结 + 未达。
 *
 * <p>这三个数跨两张表（本模块的回执 + 7.5.1.2 的权威值），一旦对不上就说明
 * 货权与在途脱节了，所以详情页把它算出来显示，而不是只显示 7.5.1.2 那一个数。
 */
export function legQuantityText(leg: {
  quantity: number;
  settledQuantity: number;
  outstandingQuantity: number;
}): { ok: boolean; text: string } {
  const qty = Number(leg.quantity) || 0;
  const settled = Number(leg.settledQuantity) || 0;
  const outstanding = Number(leg.outstandingQuantity) || 0;
  const ok = Math.abs(qty - settled - outstanding) < 0.0001;
  return {
    ok,
    text: `原发 ${fmt(qty)} = 已了结 ${fmt(settled)} + 未达 ${fmt(outstanding)}${ok ? '' : '（对不上，请刷新）'}`,
  };
}

/** 数量展示。 */
export function fmt(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return Number(value).toLocaleString('zh-CN', {
    minimumFractionDigits: 0,
    maximumFractionDigits: 4,
  });
}

/** 金额展示，带币种。成本与货值一律 BigDecimal/NUMERIC 过来，这里只做本地化。 */
export function money(value: number | null | undefined, currency?: string | null): string {
  if (value === null || value === undefined) return '—';
  const text = Number(value).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
  return currency ? `${text} ${currency}` : text;
}

/**
 * 时间展示。**带偏移量**显示，避免跨时区看单子时把到港时刻读错。
 *
 * <p>后端给的是 ISO-8601（含偏移），这里只做本地化不改时刻 —— 改时刻就是改事实。
 */
export function moment(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

/** 下拉项的展示文案。未交付的维度带 unavailable 后缀，界面上直接禁用。 */
export function dimensionCaption(dimension: {
  label: string;
  available: boolean;
  ownerModule: string;
}): string {
  return dimension.available
    ? dimension.label
    : `${dimension.label}（${dimension.ownerModule} 未交付）`;
}

/** 明细行摘要，给展开区用。批次来源（哪张收货单 / 哪次到货 / 哪个批次）必须看得见。 */
export function lineCaption(line: {
  lineNo: number;
  skuCode: string;
  skuName: string;
  lotCode: string | null;
  sourceReceiptCode: string | null;
  sourceWaybillNo: string | null;
  plannedQuantity: number;
  shippedQuantity: number;
  pendingQuantity: number;
  declaredItemName: string;
}): string {
  const batch = line.lotCode ? `批次 ${line.lotCode}` : '无批次';
  const from = line.sourceReceiptCode
    ? `来自 ${line.sourceReceiptCode}${line.sourceWaybillNo ? ` / ${line.sourceWaybillNo}` : ''}`
    : '来源未登记';
  return `第 ${line.lineNo} 行 ${line.skuCode} ${line.skuName}（${batch}，${from}）：计划 ${fmt(line.plannedQuantity)}、已发 ${fmt(line.shippedQuantity)}、余量 ${fmt(line.pendingQuantity)}，申报品名「${line.declaredItemName}」`;
}
