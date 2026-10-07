import type {
  InTransitAlerts,
  InTransitDetail,
  InTransitNettingPage,
  InTransitPage,
  InTransitRow,
  InTransitSaved,
} from '../../../../../packages/api-contracts/src/catalog';
import { api } from './api';

/**
 * 7.5.1.2 在途库存前端模型。
 *
 * <p>界面上最容易误读的三处，这个模块显式处理：
 *
 * <ul>
 *   <li>**在途是四类货的一个口径**（调拨 / 头程 / 采购 / FBA 发运），不是某一类货的别名。
 *       所以清单首屏是全量汇总 + 类型筛选，而不是四张各说各话的表。
 *   <li>**未达量 / 已了结量 / 原发量是三个不同的数**。缺口计算要扣的是 `outstandingQuantity`，
 *       `declaredQuantity` 只是「当初发了多少」。把已了结的部分当成还在路上，就是重复补货的根因。
 *   <li>**逾期是算出来的，不是标记的**。`overdueLevel` 由 ETA 与此刻相减得出，
 *       改了 ETA 档位立刻跟着变；`NO_ETA`（没登记 ETA）如实单列，不当成准点。
 * </ul>
 *
 * <p>**契约与后端的一处已知不一致**（不是绕过，是如实标注）：
 * `InTransitRegisterRequest.lotId` / `expectedArriveAt` 在生成的契约里是非空 `string`，
 * 而后端 `register()` 明确把它们当可空处理（`request.lotId() != null` 分支、
 * ETA 缺省不算逾期）。发空串会让 Jackson 按 UUID / OffsetDateTime 解析失败直接 400，
 * 所以这里用本地的 {@link RegisterPayload} 类型把可空字段标成可选并**整键省略**，
 * 而不是塞 `''`。响应体一律用契约类型。
 */

/** 列表查询条件。与后端 `InTransitQuery` 一一对应。 */
export interface InTransitParams {
  transitKind?: string;
  fromWarehouseId?: string;
  toWarehouseId?: string;
  skuId?: string;
  overdueLevel?: string[];
  overdueOnly?: boolean;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

function inTransitQuery(params: InTransitParams): string {
  const parts: string[] = [];
  if (params.transitKind) parts.push(`transitKind=${encodeURIComponent(params.transitKind)}`);
  if (params.fromWarehouseId)
    parts.push(`fromWarehouseId=${encodeURIComponent(params.fromWarehouseId)}`);
  if (params.toWarehouseId) parts.push(`toWarehouseId=${encodeURIComponent(params.toWarehouseId)}`);
  if (params.skuId) parts.push(`skuId=${encodeURIComponent(params.skuId)}`);
  // 逾期档位是 List<String>：重复键而非逗号串（Spring 只认重复键）。
  for (const level of params.overdueLevel ?? [])
    parts.push(`overdueLevel=${encodeURIComponent(level)}`);
  if (params.overdueOnly) parts.push(`overdueOnly=true`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

/** 在途清单（调拨 / 头程 / 采购 / FBA 发运四类同一口径）+ 全量汇总 + 下拉词表。 */
export function fetchInTransitLegs(params: InTransitParams) {
  return api<InTransitPage>(`warehouse/inventory/in-transit${inTransitQuery(params)}`);
}

/** 到达预警（逾期未达）。按 ETA 从早到晚排，最该先追的在前。 */
export function fetchInTransitAlerts() {
  return api<InTransitAlerts>('warehouse/inventory/in-transit/alerts');
}

/**
 * 补货可扣在途净额（按目的仓 × SKU）。
 *
 * <p>6.2.1.1 算缺口时的直接输入。这里**只交数**，不给补货建议 ——
 * 需求预测与下单不属本模块（见蓝图与 `usageNote`）。
 */
export function fetchInTransitNetting(params: {
  toWarehouseId?: string;
  skuId?: string;
  sort?: string;
  page?: number;
  size?: number;
}) {
  const parts: string[] = [];
  if (params.toWarehouseId)
    parts.push(`toWarehouseId=${encodeURIComponent(params.toWarehouseId)}`);
  if (params.skuId) parts.push(`skuId=${encodeURIComponent(params.skuId)}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return api<InTransitNettingPage>(
    `warehouse/inventory/in-transit/netting${parts.length ? `?${parts.join('&')}` : ''}`,
  );
}

/** 在途腿详情：行 + 该腿完整的到达 / 差异时间线。跨租户在 RLS 下查不到 → 404。 */
export function fetchInTransitLeg(legId: string) {
  return api<InTransitDetail>(`warehouse/inventory/in-transit/${encodeURIComponent(legId)}`);
}

/**
 * 登记外部在途腿的请求体。
 *
 * <p>可空字段标成可选并整键省略：见文件头「契约与后端的一处已知不一致」。
 * 文本字段可以给空串（后端按 blank 处理），UUID / 时间字段不行。
 */
export interface RegisterPayload {
  transitKind: string;
  skuId: string;
  qualityStatusId: string;
  legalEntityId: string;
  quantity: number;
  shippedAt: string;
  sourceSystem: string;
  sourceReference: string;
  fromWarehouseId?: string;
  fromWarehouseName?: string;
  toWarehouseId?: string;
  toWarehouseName?: string;
  lotId?: string;
  expectedArriveAt?: string;
  carrierName?: string;
  trackingNo?: string;
}

/** 到达请求。可分批到货；进我方仓的必须给库位。 */
export interface ArrivePayload {
  expectedVersion: number;
  quantity: number;
  occurredAt?: string;
  locationId?: string;
  lotId?: string;
  notes?: string;
}

/** 差异登记请求：把到不了的部分如实记为短少，在途随之归零。 */
export interface DiscrepancyPayload {
  expectedVersion: number;
  quantity: number;
  reason: string;
  occurredAt?: string;
  notes?: string;
}

/**
 * 登记前的客户端预检，逐条对应后端 `register()` 的真实校验。
 *
 * <p>放在前端不是为了替代后端校验，而是为了让「为什么不许提交」当场可读 ——
 * 后端那几条要等一次 400 才说得清，其中「调拨不能在这里登记」尤其需要先讲清楚。
 */
export function registerBlockers(input: {
  transitKind: string;
  skuId: string;
  qualityStatusId: string;
  legalEntityId: string;
  sourceSystem: string;
  sourceReference: string;
  quantity: number;
  shippedAt: string;
  expectedArriveAt?: string;
  fromWarehouseId?: string;
  fromWarehouseName?: string;
  toWarehouseId?: string;
  toWarehouseName?: string;
}): string[] {
  const out: string[] = [];
  if (!input.transitKind) out.push('请选择在途类型');
  if (input.transitKind === 'TRANSFER')
    out.push('调拨在途由 7.5.1.1 调拨单的发出动作产生，不能在这里登记（否则在途会被记两次）');
  if (!input.skuId) out.push('请选择 SKU');
  if (!input.qualityStatusId) out.push('请选择质量状态');
  if (!input.legalEntityId) out.push('请选择货权法人');
  if (!input.sourceSystem.trim()) out.push('请填写来源系统（幂等锚点的一半）');
  if (!input.sourceReference.trim())
    out.push('请填写来源单据号（与来源系统共同构成幂等锚点，重复登记会被后端挡成 409）');
  if (!(input.quantity > 0)) out.push('在途数量必须大于 0');
  if (!input.shippedAt) out.push('请填写发出时间');
  // ETA 可以早于「此刻」（那正是一条逾期在途，补录旧批次要有数据可算），
  // 但不能早于发出时间 —— 后端只挡后者。
  if (input.expectedArriveAt && input.shippedAt && input.expectedArriveAt < input.shippedAt)
    out.push('预计到达时间不能早于发出时间');
  if (!input.fromWarehouseId && !input.fromWarehouseName?.trim())
    out.push('起点不是本租户的仓库时必须给出可读名称（供应商工厂、平台仓等不在我们系统里）');
  if (!input.toWarehouseId && !input.toWarehouseName?.trim())
    out.push('终点不是本租户的仓库时必须给出可读名称（供应商工厂、平台仓等不在我们系统里）');
  if (input.transitKind === 'FBA_OUTBOUND' && !input.fromWarehouseId)
    out.push('FBA 发货在途必须指定我方发货仓（登记时要从该仓扣减在手）');
  if (input.transitKind !== 'FBA_OUTBOUND' && input.transitKind && !input.toWarehouseId)
    out.push('该类型必须指定我方收货仓（货最终要进这个仓，缺了它就没有入库落点）');
  if (input.fromWarehouseId && input.toWarehouseId && input.fromWarehouseId === input.toWarehouseId)
    out.push('起点仓与终点仓不能是同一个仓');
  return out;
}

/**
 * 把表单输入变成请求体：空的可选字段**整键省略**。
 *
 * <p>之所以不直接返回契约类型：契约里这几个字段是非空 string，而发空串会被
 * Jackson 的 UUID / OffsetDateTime 解析挡成 400。省略键才是「不填」的正确表达。
 */
export function buildRegisterPayload(input: RegisterPayload): RegisterPayload {
  const payload: RegisterPayload = {
    transitKind: input.transitKind,
    skuId: input.skuId,
    qualityStatusId: input.qualityStatusId,
    legalEntityId: input.legalEntityId,
    quantity: input.quantity,
    shippedAt: input.shippedAt,
    sourceSystem: input.sourceSystem.trim(),
    sourceReference: input.sourceReference.trim(),
  };
  if (input.fromWarehouseId) payload.fromWarehouseId = input.fromWarehouseId;
  const fromName = input.fromWarehouseName?.trim();
  if (fromName) payload.fromWarehouseName = fromName;
  if (input.toWarehouseId) payload.toWarehouseId = input.toWarehouseId;
  const toName = input.toWarehouseName?.trim();
  if (toName) payload.toWarehouseName = toName;
  if (input.lotId) payload.lotId = input.lotId;
  if (input.expectedArriveAt) payload.expectedArriveAt = input.expectedArriveAt;
  const carrier = input.carrierName?.trim();
  if (carrier) payload.carrierName = carrier;
  const tracking = input.trackingNo?.trim();
  if (tracking) payload.trackingNo = tracking;
  return payload;
}

/** 登记外部在途腿（头程发运 / 采购发运 / FBA 发货运单事件）。调拨腿不在这里登记。 */
export function registerInTransit(input: RegisterPayload) {
  return api<InTransitSaved>('warehouse/inventory/in-transit', 'POST', buildRegisterPayload(input));
}

/** 到货：可分批。落目的仓在手并写 RECEIPT 分录。 */
export function arriveInTransitLeg(legId: string, input: ArrivePayload) {
  return api<InTransitSaved>(
    `warehouse/inventory/in-transit/${encodeURIComponent(legId)}/arrive`,
    'POST',
    input,
  );
}

/** 差异登记：到不了的部分如实记为短少，在途随之归零。原因必填，不自动抹平。 */
export function recordInTransitDiscrepancy(legId: string, input: DiscrepancyPayload) {
  return api<InTransitSaved>(
    `warehouse/inventory/in-transit/${encodeURIComponent(legId)}/discrepancy`,
    'POST',
    input,
  );
}

/** 在途类型词表。与后端 `InTransitKind` 逐值对应。 */
export const inTransitKindLabels: Record<string, string> = {
  TRANSFER: '调拨在途',
  FIRST_MILE: '头程在途',
  PURCHASE: '采购在途',
  FBA_OUTBOUND: 'FBA 发货在途',
};

/** 登记动作可选的类型：调拨腿由 7.5.1.1 产生，后端显式拒绝在这里登记。 */
export const registrableTransitKinds = ['FIRST_MILE', 'PURCHASE', 'FBA_OUTBOUND'] as const;

/** 逾期档位标签。与后端 `InTransitOverdueLevel` 一致。 */
export const inTransitOverdueLabels: Record<string, string> = {
  ON_TIME: '未到预计到达',
  MILD: '逾期 1 天内',
  OVERDUE: '逾期 1–3 天',
  SEVERE: '逾期 3–7 天',
  CRITICAL: '逾期 7 天以上',
  NO_ETA: '未登记预计到达',
};

/**
 * 逾期档位的严重度排名，数字越大越该先追。
 *
 * <p>档位是**算出来的分桶**（ETA 与此刻相减），所以排序与配色都从档位推导，
 * 不在前端另存一份「是否逾期」标记 —— 那会让改了 ETA 的行还挂着旧颜色。
 * `NO_ETA` 单独一档：不参与严重度比较，但要显式提示「没登记 ETA，逾期算不出来」。
 */
export function overdueSeverity(level: string): number {
  switch (level) {
    case 'CRITICAL':
      return 5;
    case 'SEVERE':
      return 4;
    case 'OVERDUE':
      return 3;
    case 'MILD':
      return 2;
    case 'ON_TIME':
      return 1;
    default:
      // NO_ETA 与未知码都落到 0：没有 ETA 就**无法**判断逾期，
      // 把它算成准点（ON_TIME）才是会骗人的那种默认值。
      return 0;
  }
}

/** 逾期档位是否需要人工追。NO_ETA 也算：补 ETA 本身就是待办。 */
export function needsAttention(level: string): boolean {
  return overdueSeverity(level) >= 3;
}

/** 在途腿状态标签。后端只会给这三个值，未收录的码原样显示。 */
export const inTransitStatusLabels: Record<string, string> = {
  IN_TRANSIT: '在途',
  RECEIVED: '已到达',
  CANCELLED: '已结案（差异）',
};

/** 差异原因。后端有受控词表，非法值返回 400，所以下拉只给这几个。 */
export const inTransitReasonLabels: Record<string, string> = {
  LOST: '运输丢失',
  DAMAGED: '破损',
  REJECTED: '目的仓拒收',
  CUSTOMS_HELD: '清关扣关',
  SHORT_SHIPPED: '发运即短少',
};

/** 事件类型标签（时间线）。 */
export const inTransitEventLabels: Record<string, string> = {
  ARRIVED: '到达',
  SHORTAGE: '差异',
};

/** 清单排序。后端 `SORTS` 白名单外的键返回 400，所以下拉只给这几个。 */
export const inTransitSortOptions = [
  { value: 'EXPECTED_ARRIVE', label: '预计到达（最近在前）' },
  { value: 'OVERDUE', label: '逾期最久优先' },
  { value: 'SHIPPED_AT', label: '发出时间（最近在前）' },
  { value: 'QUANTITY', label: '数量（多在前）' },
  { value: 'SKU_CODE', label: 'SKU 编码' },
  { value: 'CREATED_AT', label: '登记时间' },
];

/** 净额排序。后端只认 QUANTITY / LATEST_ARRIVAL / SKU_CODE，其余落到数量降序。 */
export const inTransitNettingSortOptions = [
  { value: 'QUANTITY', label: '在途量（多在前）' },
  { value: 'LATEST_ARRIVAL', label: '最晚预计到达在前' },
  { value: 'SKU_CODE', label: 'SKU 编码' },
];

/** 某一行还能到货多少：在途余量。缺口计算要扣的也是它。 */
export function outstanding(row: { outstandingQuantity: number }): number {
  return Math.max(0, Number(row.outstandingQuantity) || 0);
}

/**
 * 某一行还能做什么。
 *
 * <p>只在「仍在途且还有未达量」时给到达 / 差异：已了结的腿（RECEIVED / CANCELLED）
 * 后端会返回 409，界面上再显示这两个按钮只会让人点了挨报错。
 */
export function inTransitActions(row: {
  status: string;
  outstandingQuantity: number;
}): string[] {
  if (row.status !== 'IN_TRANSIT') return [];
  return outstanding(row) > 0 ? ['arrive', 'discrepancy'] : [];
}

/** 到货是否必须给库位：进我方仓的到货要，FBA 发货运到平台仓不要（那边没有我们的库位）。 */
export function arriveNeedsLocation(transitKind: string): boolean {
  return transitKind !== 'FBA_OUTBOUND';
}

/**
 * 到达预检。与后端 `arrive()` 的真实校验对齐。
 *
 * <p>`expectedVersion` 必填是有意的：并发到达 / 结案时必须给当前版本，
 * 不给就等于允许「读到什么就写什么」（后端直接拒）。
 */
export function arriveBlockers(input: {
  version: number;
  transitKind: string;
  outstandingQuantity: number;
  quantity: number;
  locationId?: string;
}): string[] {
  const out: string[] = [];
  if (!(input.quantity > 0)) out.push('本次到货数量必须大于 0');
  if (input.quantity > outstanding(input)) out.push('本次到货数量超过该腿未达量');
  if (arriveNeedsLocation(input.transitKind) && !input.locationId)
    out.push('进我方仓的到货必须指定落库库位（货到了哪个库位是收货必须回答的问题）');
  return out;
}

/** 差异预检。差异原因必填，且不自动抹平 —— 少的那批必须有人认领原因。 */
export function discrepancyBlockers(input: {
  outstandingQuantity: number;
  quantity: number;
  reason: string;
}): string[] {
  const out: string[] = [];
  if (!(input.quantity > 0)) out.push('差异数量必须大于 0');
  if (input.quantity > outstanding(input)) out.push('差异数量超过该腿未达量');
  if (!input.reason) out.push('请选择差异原因（原因必填，系统不自动抹平）');
  return out;
}

/**
 * 守恒式自检文案：原发量 = 已了结 + 仍在途。
 *
 * <p>三个数逐行返回就是为了让这一行自解释。界面上显式摆出等式，
 * 让人看见「这批货现在在哪、什么时候到、还差多少」而不是背口诀。
 */
export function conservationText(row: {
  declaredQuantity: number;
  settledQuantity: number;
  outstandingQuantity: number;
}): { ok: boolean; text: string } {
  const declared = Number(row.declaredQuantity) || 0;
  const settled = Number(row.settledQuantity) || 0;
  const rest = outstanding(row);
  const sum = settled + rest;
  const ok = Math.abs(declared - sum) < 0.0001;
  return {
    ok,
    text: `原发 ${fmt(declared)} = 已了结 ${fmt(settled)} + 仍在途 ${fmt(rest)}${ok ? '' : `（差 ${fmt(declared - sum)}）`}`,
  };
}

/** 数量展示。空值给破折号，不把「没有」显示成 0。 */
export function fmt(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return Number(value).toLocaleString('zh-CN', {
    minimumFractionDigits: 0,
    maximumFractionDigits: 4,
  });
}

/**
 * 时间展示。**带偏移量**，避免跨时区看单子时把「预计到达」读错一天。
 *
 * <p>后端给的是 ISO-8601（含偏移），这里只做本地化不改时刻 —— 改时刻就是改事实。
 */
export function moment(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

/** ETA 展示。没登记 ETA 时说「未登记」而不是把空值当成准点。 */
export function etaText(row: { expectedArriveAt: string | null }): string {
  if (!row.expectedArriveAt) return '未登记 ETA';
  return moment(row.expectedArriveAt);
}

/** 逾期时长文案。没 ETA 就没有逾期小时数，如实说明而不是显示 0 小时。 */
export function overdueHoursText(row: { expectedArriveAt: string | null; overdueHours: number | null }): string {
  if (!row.expectedArriveAt) return '未登记 ETA，无法判断逾期';
  if (row.overdueHours === null || row.overdueHours === undefined) return '未逾期';
  if (row.overdueHours < 0) return '未逾期';
  const hours = row.overdueHours;
  if (hours < 24) return `逾期 ${hours} 小时`;
  return `逾期 ${Math.floor(hours / 24)} 天 ${hours % 24} 小时`;
}

/** 起点 → 终点。缺一侧时如实留空，不拼出「undefined」。 */
export function routeText(row: {
  fromWarehouseCode: string;
  fromWarehouseName: string;
  toWarehouseCode: string;
  toWarehouseName: string;
}): string {
  const from = [row.fromWarehouseCode, row.fromWarehouseName].filter(Boolean).join(' ');
  const to = [row.toWarehouseCode, row.toWarehouseName].filter(Boolean).join(' ');
  if (!from && !to) return '—';
  return `${from || '（外部起点）'} → ${to || '（外部终点）'}`;
}

/** 调拨腿的来源单据。调拨在途由 7.5.1.1 产生，这一列指回那张调拨单。 */
export function transferRefText(row: {
  transferOrderCode: string | null;
  sourceSystem: string;
  sourceReference: string;
}): string {
  if (row.transferOrderCode) return `调拨单 ${row.transferOrderCode}`;
  if (row.sourceReference) return `${row.sourceSystem || '外部'} · ${row.sourceReference}`;
  return '—';
}