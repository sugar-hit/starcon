import type {
  DiscrepancyDecisionRequest,
  DiscrepancyEvidenceRequest,
  DiscrepancyHandlingCloseRequest,
  DiscrepancyHandlingDetail,
  DiscrepancyHandlingIntakeRequest,
  DiscrepancyHandlingPage,
  DiscrepancyHandlingRow,
  DiscrepancyHandlingSaved,
  DiscrepancyClaimRequest,
  DiscrepancyClaimRow,
  DiscrepancyClaimSubmitRequest,
  DiscrepancyClaimWithdrawRequest,
  DiscrepancyEvidenceRow,
  DiscrepancyStockMoveRow,
  GoodsReceiptDiscrepancyCapabilities,
} from '../../../../../packages/api-contracts/src/index';
import { api } from './api';

/**
 * 8.2.1.2 收货差异前端模型。
 *
 * <p>这个模块的界面最容易做错一件事：**把「破损」当成和「超收」一样可以正常入库**。
 * 蓝图原文是「破损与短少必须拒收或待检入库，直接入可售会把问题货卖出去」。
 *
 * <p>所以处理决定按钮由 {@link allowedDispositions} 按差异类型过滤 ——
 * 「正常入库」对破损 / 短少 / 型号不符 / 过期**根本不渲染**，而不是渲染了让后端报错。
 * 让操作员点一个注定被拒的按钮，等于告诉他「这个功能坏了」，而真相是这条路径本就不该存在。
 */

/** 列表查询条件。与后端 `DiscrepancyHandlingQuery` 一一对应。 */
export interface DiscrepancyHandlingParams {
  receiptId?: string;
  warehouseId?: string;
  status?: string[];
  disposition?: string[];
  discrepancyType?: string[];
  claimStatus?: string[];
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

export function discrepancyHandlingQuery(params: DiscrepancyHandlingParams): string {
  const parts: string[] = [];
  if (params.receiptId) parts.push(`receiptId=${encodeURIComponent(params.receiptId)}`);
  if (params.warehouseId) parts.push(`warehouseId=${encodeURIComponent(params.warehouseId)}`);
  for (const s of params.status ?? []) parts.push(`status=${encodeURIComponent(s)}`);
  for (const d of params.disposition ?? []) parts.push(`disposition=${encodeURIComponent(d)}`);
  for (const t of params.discrepancyType ?? [])
    parts.push(`discrepancyType=${encodeURIComponent(t)}`);
  for (const c of params.claimStatus ?? []) parts.push(`claimStatus=${encodeURIComponent(c)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export function fetchDiscrepancyHandlings(params: DiscrepancyHandlingParams) {
  return api<DiscrepancyHandlingPage>(
    `warehouse/goods-receipt-discrepancies${discrepancyHandlingQuery(params)}`,
  );
}

export function fetchDiscrepancyCapabilities() {
  return api<GoodsReceiptDiscrepancyCapabilities>(
    'warehouse/goods-receipt-discrepancies/capabilities',
  );
}

export function fetchDiscrepancyDetail(handlingId: string) {
  return api<DiscrepancyHandlingDetail>(
    `warehouse/goods-receipt-discrepancies/${encodeURIComponent(handlingId)}`,
  );
}

/** 接手一条 8.2.1.1 记录下来的差异事实。 */
export function intakeDiscrepancy(discrepancyId: string) {
  return api<DiscrepancyHandlingSaved>(
    'warehouse/goods-receipt-discrepancies',
    'POST',
    { discrepancyId } satisfies DiscrepancyHandlingIntakeRequest,
  );
}

/** 拍照留证：凭证本体真落库，前端传 base64 而不是「将来会有的」外链。 */
export function addDiscrepancyEvidence(handlingId: string, input: DiscrepancyEvidenceRequest) {
  return api<DiscrepancyEvidenceRow>(
    `warehouse/goods-receipt-discrepancies/${encodeURIComponent(handlingId)}/evidence`,
    'POST',
    input,
  );
}

/** 提交处理决定：拒收 / 部分接收 / 待检入库 / 正常入库，并写对应的库存流转。 */
export function decideDiscrepancy(handlingId: string, input: DiscrepancyDecisionRequest) {
  return api<DiscrepancyHandlingSaved>(
    `warehouse/goods-receipt-discrepancies/${encodeURIComponent(handlingId)}/decide`,
    'POST',
    input,
  );
}

/** 发起索赔。金额与币种成对；都不填表示「供应商还没报价，先把索赔发起」。 */
export function claimDiscrepancy(handlingId: string, input: DiscrepancyClaimRequest) {
  return api<DiscrepancyHandlingSaved>(
    `warehouse/goods-receipt-discrepancies/${encodeURIComponent(handlingId)}/claim`,
    'POST',
    input,
  );
}

export function submitDiscrepancyClaim(handlingId: string, expectedVersion: number) {
  return api<DiscrepancyClaimRow>(
    `warehouse/goods-receipt-discrepancies/${encodeURIComponent(handlingId)}/claim/submit`,
    'POST',
    { expectedVersion } satisfies DiscrepancyClaimSubmitRequest,
  );
}

export function withdrawDiscrepancyClaim(
  handlingId: string,
  expectedVersion: number,
  reason: string,
) {
  return api<DiscrepancyClaimRow>(
    `warehouse/goods-receipt-discrepancies/${encodeURIComponent(handlingId)}/claim/withdraw`,
    'POST',
    { expectedVersion, reason } satisfies DiscrepancyClaimWithdrawRequest,
  );
}

export function closeDiscrepancyHandling(handlingId: string, input: DiscrepancyHandlingCloseRequest) {
  return api<DiscrepancyHandlingSaved>(
    `warehouse/goods-receipt-discrepancies/${encodeURIComponent(handlingId)}/close`,
    'POST',
    input,
  );
}

/** 处理状态标签。未收录的码原样显示，不静默吞掉。 */
export const handlingStatusLabels: Record<string, string> = {
  PENDING: '待处理',
  DECIDED: '已决定',
  CLAIMED: '已索赔',
  CLOSED: '已关闭',
};

/** 处理决定标签。与后端 HandlingDisposition 逐值对应。 */
export const dispositionLabels: Record<string, string> = {
  REJECT: '拒收',
  PARTIAL_ACCEPT: '部分接收',
  HOLD_FOR_INSPECTION: '待检入库',
  ACCEPT: '正常入库',
};

/** 七类差异标签。与后端 DiscrepancyType 逐值对应。 */
export const discrepancyTypeLabels: Record<string, string> = {
  OVER_RECEIPT: '超收',
  SHORT_RECEIPT: '少收',
  REJECTED: '拒收',
  DAMAGED: '破损',
  WRONG_ITEM: '型号不符',
  EXPIRED: '过期',
  MISDELIVERED: '错发',
};

/**
 * 风险差异类型：**禁止按「正常入库」处理**的四种。
 *
 * <p>蓝图点名了破损与短少；型号不符与过期同样不能直接当可售卖
 * ——「型号不符」意味着这箱货不是买家要的那个型号，「过期」意味着它根本不该再卖。
 * 与后端 `RISK_DISCREPANCY_TYPES` 和迁移 V78 的触发器逐值对应。
 */
export const riskDiscrepancyTypes = ['DAMAGED', 'SHORT_RECEIPT', 'WRONG_ITEM', 'EXPIRED'] as const;

/** 库存流转方向标签。与后端 StockMoveDirection 逐值对应。 */
export const stockMoveDirectionLabels: Record<string, string> = {
  NOT_POSTED: '拒收不入账',
  WITHDRAW: '撤出',
  INBOUND_TO_HOLD: '转入待检',
  INBOUND_TO_SELLABLE: '正常入库',
  NO_MOVE: '无需移动',
};

/** 质量状态 role 的界面文案。 */
export const qualityRoleLabels: Record<string, string> = {
  SELLABLE: '可售',
  PENDING_INSPECTION: '待检',
  DEFECTIVE: '残次',
};

export const handlingSortOptions = [
  { value: 'UPDATED_AT', label: '最近更新' },
  { value: 'CREATED_AT', label: '接手时间' },
  { value: 'DECIDED_AT', label: '决定时间' },
  { value: 'QUANTITY', label: '差异数量' },
];

/** 该差异类型是否禁止「正常入库」。 */
export function isRiskType(discrepancyType: string): boolean {
  return (riskDiscrepancyTypes as readonly string[]).includes(discrepancyType);
}

/**
 * 当前状态可以执行的动作。
 *
 * <p>界面上只显示当前状态**允许**的按钮，而不是显示全部再让后端报错 ——
 * 「已关闭的记录还显示决定按钮」会让运营以为能改判，而已决定的记录本就不允许改判。
 */
export function handlingActions(status: string): string[] {
  switch (status) {
    case 'PENDING':
      return ['evidence', 'decide'];
    case 'DECIDED':
      return ['evidence', 'claim', 'close'];
    case 'CLAIMED':
      return ['evidence', 'close'];
    default:
      // CLOSED 是终态。
      return [];
  }
}

/**
 * 该差异类型**允许**的处理决定。
 *
 * <p>风险类型直接拿掉「正常入库」——不是禁用按钮，是**根本不渲染**：
 * 点一个注定被 422 拒绝的按钮，运营只会以为系统坏了，而真相是这条路径本就不该存在。
 */
export function allowedDispositions(
  discrepancyType: string,
  available: Array<{ value: string; allowedForRiskTypes: boolean }>,
): string[] {
  const risk = isRiskType(discrepancyType);
  return available
    .filter((o) => (risk ? o.allowedForRiskTypes : true))
    .map((o) => o.value);
}

/**
 * 数量口径自检：接受量 + 拒收量必须恰好等于差异数量。
 *
 * <p>页面上把它显式算出来显示，让「这三个数是怎么来的」自解释。
 * 对不上时提示刷新 —— 服务层与数据库层都会拒绝这种决定，界面先说清原因。
 */
export function handlingQuantityText(row: {
  quantity: number;
  acceptedQuantity: number;
  rejectedQuantity: number;
}): { ok: boolean; text: string } {
  const quantity = Number(row.quantity) || 0;
  const accepted = Number(row.acceptedQuantity) || 0;
  const rejected = Number(row.rejectedQuantity) || 0;
  const ok = Math.abs(accepted + rejected - quantity) < 0.0001;
  return {
    ok,
    text: `差异 ${fmt(quantity)} = 接受 ${fmt(accepted)} + 拒收 ${fmt(rejected)}${ok ? '' : '（对不上，请刷新）'}`,
  };
}

/** 某条处理记录下一步能去哪些质量状态。风险类型不给出「可售」选项。 */
export function inboundQualityOptions(
  discrepancyType: string,
  options: Array<{ id: string; code: string; name: string; role: string }>,
): Array<{ id: string; code: string; name: string; role: string }> {
  if (!isRiskType(discrepancyType)) return options;
  return options.filter((o) => o.role !== 'SELLABLE');
}

/** 一句话说明这条处理记录现在的库存后果，详情页常驻显示。 */
export function stockEffectText(moves: DiscrepancyStockMoveRow[]): string {
  if (!moves.length) return '尚未产生库存流转：处理决定还没提交，或这批货从未进入库存。';
  return moves
    .map((m) => `${stockMoveDirectionLabels[m.direction] ?? m.direction} ${fmt(m.quantity)}`)
    .join(' · ');
}

/** 凭证类型标签。 */
export function evidenceTypeLabel(value: string): string {
  const map: Record<string, string> = {
    PHOTO: '照片',
    WEIGHING: '复磅单',
    INSPECTION: '质检报告',
    DOCUMENT: '单据',
  };
  return map[value] ?? value;
}

/**
 * 把浏览器选中的图片压到长边 ≤1280px 的 JPEG 再上传。
 *
 * <p>为什么前端压而不是后端压：后端没有任何图片库可用，而凭证本体要真落 PostgreSQL，
 * 单条上限 1.5 MB（DB CHECK 兜底）。手机随手拍的货损照片原图常有几 MB，
 * 压到长边 1280px 的 JPEG 通常在 100 KB 量级，既够看清箱体破损与条码，
 * 又能落在上传体积限制内。
 *
 * <p>压不动时抛**可读**错误而不是静默失败 —— 现场需要知道该离近一点重拍。
 */
export async function fileToEvidencePayload(
  file: File,
): Promise<{ fileName: string; contentType: string; contentBase64: string }> {
  const bitmap = await createImageBitmap(file);
  const maxEdge = 1280;
  const scale = Math.min(1, maxEdge / Math.max(bitmap.width, bitmap.height));
  const width = Math.max(1, Math.round(bitmap.width * scale));
  const height = Math.max(1, Math.round(bitmap.height * scale));
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d');
  if (!ctx) {
    bitmap.close();
    throw new Error('浏览器不支持图片压缩，请换一个浏览器或直接上传单据类凭证');
  }
  ctx.drawImage(bitmap, 0, 0, width, height);
  bitmap.close();
  const dataUrl = canvas.toDataURL('image/jpeg', 0.75);
  const base64 = dataUrl.slice(dataUrl.indexOf(',') + 1);
  // base64 会把体积放大约 4/3，上传体积上限约 200 KB，这里留一点余量提前拦。
  if (base64.length > 190_000) {
    throw new Error(
      '照片压缩后仍然过大：请离近一点重拍，或改用「单据」类型上传现场已有的复磅单 / 通知单',
    );
  }
  return { fileName: file.name, contentType: 'image/jpeg', contentBase64: base64 };
}

/** 数量展示。 */
export function fmt(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return Number(value).toLocaleString('zh-CN', {
    minimumFractionDigits: 0,
    maximumFractionDigits: 4,
  });
}

/**
 * 时间展示。**带偏移量**显示，避免跨时区看单子时把处理时刻读错。
 *
 * <p>后端给的是 ISO-8601（含偏移），这里只做本地化不改时刻 —— 改时刻就是改事实。
 */
export function moment(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

/** 下游出口的展示文案。未交付的带 unavailable 后缀，界面上直接禁用。 */
export function dependencyCaption(d: {
  label: string;
  available: boolean;
  ownerModule: string;
}): string {
  return d.available ? d.label : `${d.label}（${d.ownerModule} 未交付）`;
}

export type {
  DiscrepancyHandlingRow,
  DiscrepancyHandlingDetail,
  DiscrepancyEvidenceRow,
  DiscrepancyStockMoveRow,
  DiscrepancyClaimRow,
};
