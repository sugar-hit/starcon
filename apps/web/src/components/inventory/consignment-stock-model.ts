import type {
  PlatformConsignmentAuditEntry,
  PlatformConsignmentCapabilities,
  PlatformConsignmentForecastPreview,
  PlatformConsignmentLedger,
  PlatformConsignmentOrder,
  PlatformConsignmentOrderDetail,
  PlatformConsignmentOrderPage,
  PlatformConsignmentReceipt,
  PlatformConsignmentReceiptPage,
  PlatformConsignmentReconciliation,
  PlatformConsignmentSettlementBasisView,
  PlatformConsignmentSettlementReceipt,
  PlatformConsignmentSettlementReceiptPage,
  PlatformConsignmentShopMode,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/**
 * 7.7.2.2 平台托管与全托管备货前端模型。
 *
 * <p>本模块界面上最容易出错、也是蓝图加粗的那一条：**全托管下「备货」不等于「自有库存」**。
 * 平台仓在库数是平台回执的观测值，不得并入自有库存余额。这个模型里所有相关函数
 * 都遵守同一条纪律：
 *
 * <ul>
 *   <li>**四个口径分列**：备货量 / 平台在库 / 平台已售 / 结算未结，各是各的字段，
 *       界面上没有把它们相加的「合计库存」—— 加起来就等于把平台在库当成自有库存了。</li>
 *   <li>**自有库存那一侧并排给出**，标注「不含托管量」，不与平台口径相加。</li>
 *   <li>**未结量可能是 null**：平台一件回执都没给的时候，未结量显示「—（平台还没给回执）」，
 *       显示 0 会被读成「没有未结」，那是谎报。</li>
 *   <li>**在途未确认 ≠ 差异损失**：备货量与平台入库之差是还在路上的量，
 *       差异本身另有��符号的量与四种口径之一。</li>
 * </ul>
 */

export interface OrderParams {
  status?: string;
  shopId?: string;
  settlementBasis?: string;
  keyword?: string;
  page?: number;
  size?: number;
}

function query<T extends object>(params: T): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params as Record<string, unknown>)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

// ------------------------------------------------------------ 接口

const base = 'warehouse/inventory/consignment-stock';

export const fetchConsignmentStockCapabilities = () =>
  api<PlatformConsignmentCapabilities>(`${base}/capabilities`);

export const fetchConsignmentShopMode = (shopId: string) =>
  api<PlatformConsignmentShopMode>(`${base}/shops/${shopId}`);

export const fetchConsignmentForecastPreview = (forecastId: string) =>
  api<PlatformConsignmentForecastPreview>(`${base}/forecast-preview${query({ forecastId })}`);

export const fetchConsignmentOrders = (params: OrderParams = {}) =>
  api<PlatformConsignmentOrderPage>(`${base}/orders${query(params)}`);

export const fetchConsignmentOrder = (orderId: string) =>
  api<PlatformConsignmentOrderDetail>(`${base}/orders/${orderId}`);

export const createConsignmentOrder = (body: unknown) =>
  api<PlatformConsignmentOrderDetail>(`${base}/orders`, 'POST', body);

export const addConsignmentOrderLine = (orderId: string, body: unknown) =>
  api<PlatformConsignmentOrderDetail>(`${base}/orders/${orderId}/lines`, 'POST', body);

export const shipConsignmentOrder = (orderId: string, note?: string) =>
  api<PlatformConsignmentOrder>(`${base}/orders/${orderId}/ship`, 'POST', { note: note ?? '' });

export const confirmConsignmentReceipt = (orderId: string, note?: string) =>
  api<PlatformConsignmentOrder>(`${base}/orders/${orderId}/confirm-receipt`, 'POST', {
    note: note ?? '',
  });

export const closeConsignmentOrder = (orderId: string, note?: string) =>
  api<PlatformConsignmentOrder>(`${base}/orders/${orderId}/close`, 'POST', { note: note ?? '' });

export const cancelConsignmentOrder = (orderId: string, note?: string) =>
  api<PlatformConsignmentOrder>(`${base}/orders/${orderId}/cancel`, 'POST', { note: note ?? '' });

export const recordConsignmentReceipt = (orderId: string, body: unknown) =>
  api<PlatformConsignmentReceipt>(`${base}/orders/${orderId}/receipts`, 'POST', body);

export const fetchConsignmentReceipts = (
  orderId: string,
  receiptType?: string,
  page = 0,
  size = 20,
) =>
  api<PlatformConsignmentReceiptPage>(
    `${base}/orders/${orderId}/receipts${query({ receiptType, page, size })}`,
  );

export const recordConsignmentSettlementReceipt = (orderId: string, body: unknown) =>
  api<PlatformConsignmentSettlementReceipt>(
    `${base}/orders/${orderId}/settlement-receipts`,
    'POST',
    body,
  );

export const fetchConsignmentSettlementReceipts = (orderId: string, page = 0, size = 20) =>
  api<PlatformConsignmentSettlementReceiptPage>(
    `${base}/orders/${orderId}/settlement-receipts${query({ page, size })}`,
  );

export const fetchConsignmentLedger = (orderId: string) =>
  api<PlatformConsignmentLedger>(`${base}/orders/${orderId}/ledger`);

export const fetchConsignmentReconciliation = (orderId: string) =>
  api<PlatformConsignmentReconciliation>(`${base}/orders/${orderId}/reconciliation`);

export const fetchConsignmentSettlementBasis = (orderId: string) =>
  api<PlatformConsignmentSettlementBasisView>(`${base}/orders/${orderId}/settlement-basis`);

export const fetchConsignmentAudit = (orderId: string) =>
  api<PlatformConsignmentAuditEntry[]>(`${base}/orders/${orderId}/audit`);

// ------------------------------------------------------------ 格式化

/** 数量最多 6 位小数（与后端 numeric(19,6) 口径一致）。 */
export function formatQty(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  const n = typeof value === 'string' ? Number(value) : value;
  if (!Number.isFinite(n)) return '—';
  return n.toLocaleString('zh-CN', { maximumFractionDigits: 6 });
}

/** 金额最多 4 位小数（与后端 numeric(19,4) 一致）。 */
export function formatAmount(
  value: number | string | null | undefined,
  currency: string,
  unavailableText = '—',
): string {
  if (value === null || value === undefined || value === '') return unavailableText;
  const n = typeof value === 'string' ? Number(value) : value;
  if (!Number.isFinite(n)) return unavailableText;
  return `${n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 })} ${currency}`;
}

/**
 * 【本模块最关键的一个函数】结算未结量的展示文本。
 *
 * <p>平台一件回执都没给的时候，未结量是 {@code null} —— 那是「还不知道」，不是「没有未结」。
 * 显示 0 会被读成「平台结清了」，那是谎报。所以这里显式返回「—（平台还没给回执）」。
 */
export function formatUnsettled(value: number | string | null | undefined, known: boolean): string {
  if (!known) return '—（平台还没给回执）';
  if (value === null || value === undefined || value === '') return '—';
  const n = typeof value === 'string' ? Number(value) : value;
  if (!Number.isFinite(n)) return '—';
  return n.toLocaleString('zh-CN', { maximumFractionDigits: 6 });
}

/**
 * 自有库存那一侧的展示文本：**永远带「不含托管量」的口径**，不让它被读成合计。
 */
export function formatOwnInventory(value: number | string | null | undefined): string {
  return `${formatQty(value)}（自有库存，不含托管量）`;
}

/**
 * 台账的四张卡。
 *
 * <p>刻意**四张分开的卡**而不是一张「库存总量」：备货量是「我方发出的」，
 * 平台在库是「平台说在库的」，平台已售是「平台卖掉的」，结算未结是「卖了还没结的」。
 * 加起来没有任何业务含义，而且会把平台在库算成我方库存。
 */
export interface MeasureCard {
  key: string;
  label: string;
  value: string;
  unit: string;
  hint: string;
}

export function measureCards(ledger: PlatformConsignmentLedger | null): MeasureCard[] {
  if (!ledger) {
    return [
      { key: 'stocked', label: '备货量', value: '—', unit: '件', hint: '我方发给平台的量' },
      { key: 'onHand', label: '平台在库', value: '—', unit: '件', hint: '平台回执口径' },
      { key: 'sold', label: '平台已售', value: '—', unit: '件', hint: '平台出库回执累计' },
      {
        key: 'unsettled',
        label: '结算未结',
        value: '—',
        unit: '件',
        hint: '已售但未按平台口径结算',
      },
    ];
  }
  return [
    {
      key: 'stocked',
      label: '备货量',
      value: formatQty(ledger.totalStockedQuantity),
      unit: '件',
      hint: '我方发出的量；与平台在库之差是在途未确认，不是损失',
    },
    {
      key: 'onHand',
      label: '平台在库',
      value: formatQty(ledger.totalPlatformOnHandQuantity),
      unit: '件',
      hint: '平台回执的观测值：入库回执 − 出库回执，不计入自有库存',
    },
    {
      key: 'sold',
      label: '平台已售',
      value: formatQty(ledger.totalPlatformSoldQuantity),
      unit: '件',
      hint: '平台出库回执累计',
    },
    {
      key: 'unsettled',
      label: '结算未结',
      value: formatQty(ledger.totalUnsettledQuantity),
      unit: '件',
      hint: `按「${ledger.settlementBasisLabel}」口径算出来的未结量`,
    },
  ];
}

// ------------------------------------------------------------ 口径与状态

/** 结算口径的说明（供 12 结算看的那句话）。 */
export function settlementBasisHint(basis: string | null | undefined): string {
  if (basis === 'PER_ITEM') return '按件结算：未结 = 平台已售件数 − 已结算件数';
  if (basis === 'PER_DAY') return '按日结算：未结 = 落在尚未结算的日期区间内的售出件数';
  return '结算口径未定';
}

/** 差异口径的色调：只有「对平」与「在途」是中性，其余要显眼。 */
export function differenceTone(
  basis: string | null | undefined,
): 'ok' | 'warn' | 'bad' | 'neutral' {
  switch (basis) {
    case 'MATCHED':
      return 'ok';
    case 'IN_TRANSIT':
      return 'neutral';
    case 'OVER_RECEIVED':
    case 'NEGATIVE_ON_HAND':
      return 'bad';
    default:
      return 'warn';
  }
}

/** 分账校验结论的色调。 */
export function segregationTone(status: string | null | undefined): 'ok' | 'bad' | 'neutral' {
  if (status === 'SEPARATED') return 'ok';
  if (status === 'VIOLATION') return 'bad';
  return 'neutral';
}

/** 备货单状态的色调。 */
export function statusTone(status: string | null | undefined): 'ok' | 'warn' | 'bad' | 'neutral' {
  switch (status) {
    case 'CLOSED':
      return 'ok';
    case 'CANCELLED':
      return 'bad';
    case 'DRAFT':
      return 'warn';
    default:
      return 'neutral';
  }
}

/** 结算状态的色调。 */
export function settlementStateTone(
  state: string | null | undefined,
): 'ok' | 'warn' | 'bad' | 'neutral' {
  switch (state) {
    case 'SETTLED':
      return 'ok';
    case 'PENDING':
      return 'warn';
    case 'NOT_STARTED':
      return 'neutral';
    default:
      return 'neutral';
  }
}

/** 经营模式标签（后端已给中文名，这里兜底）。 */
export function operatingModeLabel(mode: string | null | undefined): string {
  switch (mode) {
    case 'FULL_CONSIGNMENT':
      return '全托管';
    case 'SEMI_CONSIGNMENT':
      return '半托管';
    case 'SELF_FULFILLED':
      return '自发货';
    case 'OVERSEAS_WAREHOUSE':
      return '海外仓';
    case 'LOCAL':
      return '本地仓';
    default:
      return '未知模式';
  }
}

/** 该经营模式能不能建平台托管备货单。 */
export function supportsConsignmentStock(mode: string | null | undefined): boolean {
  return mode === 'FULL_CONSIGNMENT' || mode === 'SEMI_CONSIGNMENT';
}

// ------------------------------------------------------------ 动作可用性

export function canShip(status: string | null | undefined): boolean {
  return status === 'DRAFT';
}

export function canAddLine(status: string | null | undefined): boolean {
  return status === 'DRAFT';
}

export function canConfirmReceipt(status: string | null | undefined): boolean {
  return status === 'SHIPPED';
}

export function canClose(status: string | null | undefined): boolean {
  return status === 'RECEIVED';
}

export function canCancel(status: string | null | undefined): boolean {
  return status === 'DRAFT' || status === 'SHIPPED' || status === 'RECEIVED';
}

/** 关单前必须结清：未结不为 0 时按钮禁用（后端也会 409 挡一道）。 */
export function canCloseWithSettlement(
  status: string | null | undefined,
  unsettled: number | null | undefined,
  known: boolean,
): boolean {
  if (!canClose(status)) return false;
  if (!known) return false;
  return unsettled === null || unsettled === undefined || unsettled === 0;
}

/** 登记平台回执：货还没发出去时平台仓不可能有收发。 */
export function canRecordReceipt(status: string | null | undefined): boolean {
  return status === 'SHIPPED' || status === 'RECEIVED';
}

/** 登记结算回执同理。 */
export function canRecordSettlement(status: string | null | undefined): boolean {
  return status === 'SHIPPED' || status === 'RECEIVED';
}

// ------------------------------------------------------------ 其它

/** 错误文本：把后端的人话原样带出来，不要压成「操作失败」。 */
export function errorText(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return '操作失败，请稍后重试';
}

/** 审计条目的中文说法。 */
export function auditLabel(eventType: string | null | undefined): string {
  switch (eventType) {
    case 'ORDER_CREATED':
      return '建立备货单';
    case 'LINE_ADDED':
      return '新增备货明细行';
    case 'ORDER_SHIPPED':
      return '发出备货单';
    case 'PLATFORM_RECEIVED':
      return '平台确认收货';
    case 'RECEIPT_RECORDED':
      return '登记平台仓回执';
    case 'SETTLEMENT_RECEIPT_RECORDED':
      return '登记平台结算回执';
    case 'ORDER_CLOSED':
      return '关闭备货单';
    case 'ORDER_CANCELLED':
      return '作废备货单';
    default:
      return eventType ?? '未知动作';
  }
}

/**
 * 分账声明的展示文本。
 *
 * <p>这句话要在页面上**显眼**，否则使用者一定会把「平台在库」当成「我方库存」，
 * 那正是蓝图加粗句禁止的事。
 */
export function segregationStatement(ledger: PlatformConsignmentLedger | null): string {
  if (!ledger) return '';
  return `${ledger.separationNote}`;
}

/** 平台仓回执类型的中文名。 */
export function receiptTypeLabel(type: string | null | undefined): string {
  return type === 'INBOUND' ? '平台入库' : type === 'OUTBOUND' ? '平台出库' : '未知类型';
}

/** 结算口径视图的展示对象。 */
export function settlementBasisView(basis: PlatformConsignmentSettlementBasisView | null) {
  if (!basis) return null;
  return {
    label: basis.basisLabel,
    rule: basis.basisRule,
    sold: formatQty(basis.platformSoldQuantity),
    settled: formatQty(basis.settledQuantity),
    unsettled: formatUnsettled(basis.unsettledQuantity, basis.unsettledKnown),
    unsettledKnown: basis.unsettledKnown,
    state: basis.settlementStateLabel,
    tone: settlementStateTone(basis.settlementState),
    periods: basis.settledPeriods.map(
      (p) =>
        `${p.periodStart} ~ ${p.periodEnd}：${formatQty(p.settledQuantity)} 件（${p.basis === 'PER_DAY' ? '按日' : '按件'}）`,
    ),
  };
}
