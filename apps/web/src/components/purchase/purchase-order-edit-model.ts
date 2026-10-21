import type {
  PurchaseOrderEditCapabilities,
  PurchaseOrderEditCreateRequest,
  PurchaseOrderEditForm,
  PurchaseOrderEditFromDraftRequest,
  PurchaseOrderEditFromDraftResult,
  PurchaseOrderEditLineInput,
  PurchaseOrderEditOptions,
  PurchaseOrderEditOrderInput,
  PurchaseOrderEditQuoteCandidate,
  PurchaseOrderEditQuotePreview,
  PurchaseOrderEditQuotePreviewRequest,
  PurchaseOrderEditSubmitRequest,
  PurchaseOrderEditUpdateRequest,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

// 能力声明的类型在页面与测试里都要用：从这里再导出一次，避免各处去翻契约包。
export type { PurchaseOrderEditCapabilities, PurchaseOrderEditQuoteCandidate };

/**
 * 6.3.1.2 采购单创建与编辑前端模型。
 *
 * <p>蓝图：「创建/编辑采购单：选择供应商、添加 SKU 行（带出报价与库存快照）、填写数量/采购单位/
 * 单价/币种、约定交期与发货地址、备注与附件。支持草稿与提交。」
 *
 * <p><b>界面纪律</b>（逐条对应后端模型的口径，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>「无数据」不是 0</b>：汇率快照取不到、税额算不出来、库存快照取不到、箱规取不到 ——
 *       一律显示「无数据」并把后端写的口径原样透出。0 会被读成「不用折算 / 不用交税 /
 *       一件都没有」，而真实情况是「**还不知道**」。</li>
 *   <li><b>不重算后端的结论</b>：总额、行金额、状态一律显示后端给的。
 *       编辑过程中那一行「本页试算」必须**标明**它是试算 —— 真正入账的是数据库重算出来的那个数。</li>
 *   <li><b>报价并列时让用户选</b>：歧义时后端不替用户挑，页面把并列的候选逐条列出来。</li>
 *   <li><b>能力边界常驻</b>：未交付的下游（6.3.1.3 审批 / 12 财务应付与税额）原样显示，
 *       采购员要能知道「为什么这单没有税额」。</li>
 *   <li>加载 / 空 / 错误三态齐全，且三态文案各不相同。</li>
 * </ul>
 */

const base = 'trade/purchase-order-editor';

// ================================================================ 调用点

export function fetchPurchaseOrderEditCapabilities() {
  return api<PurchaseOrderEditCapabilities>(`${base}/capabilities`);
}

export function fetchPurchaseOrderEditOptions() {
  return api<PurchaseOrderEditOptions>(`${base}/options`);
}

/** 带出报价与库存快照（只读，无副作用）。 */
export function previewPurchaseOrderQuote(request: PurchaseOrderEditQuotePreviewRequest) {
  return api<PurchaseOrderEditQuotePreview>(`${base}/quote-preview`, 'POST', request);
}

/** 新建采购单（草稿）。 */
export function createPurchaseOrder(order: PurchaseOrderEditOrderInput) {
  const input: PurchaseOrderEditCreateRequest = { order };
  return api<PurchaseOrderEditForm>(`${base}/orders`, 'POST', input);
}

/** 读一张采购单的编辑表单。 */
export function fetchPurchaseOrderForm(id: string) {
  return api<PurchaseOrderEditForm>(`${base}/orders/${id}`);
}

/** 编辑保存（带 expectedVersion 乐观锁）。 */
export function savePurchaseOrder(
  id: string,
  expectedVersion: number,
  order: PurchaseOrderEditOrderInput,
) {
  const input: PurchaseOrderEditUpdateRequest = { expectedVersion, order };
  return api<PurchaseOrderEditForm>(`${base}/orders/${id}`, 'PUT', input);
}

/** 提交（草稿 → 待审批，冻结单价 / 汇率 / 单位换算快照）。 */
export function submitPurchaseOrder(id: string, expectedVersion: number, note: string) {
  const input: PurchaseOrderEditSubmitRequest = { expectedVersion, note };
  return api<PurchaseOrderEditForm>(`${base}/orders/${id}/submit`, 'POST', input);
}

/** 由 6.2.2.2 的采购单草稿建单。 */
export function convertPurchaseOrderDrafts(draftIds: string[]) {
  const input: PurchaseOrderEditFromDraftRequest = { draftIds };
  return api<PurchaseOrderEditFromDraftResult>(`${base}/from-drafts`, 'POST', input);
}

// ================================================================ 表单构造

/**
 * 一条空行（新建时用）。
 *
 * <p>⚠️ 空行的单价是 <b>null</b> 而不是 0：0 会被当成「这个 SKU 免费」，
 * 而 null 的含义是「还没填」。后端也会拒掉空单价。
 */
export function emptyLine(lineNo: number): PurchaseOrderEditLineInput {
  return {
    lineNo,
    skuId: null,
    skuCode: '',
    skuName: '',
    quantity: null as unknown as number,
    unitCode: '',
    unitPrice: null as unknown as number,
    priceSource: 'MANUAL',
    quoteId: null,
    note: '',
  };
}

/** 一张空表单（新建时用）。 */
export function emptyOrder(): PurchaseOrderEditOrderInput {
  return {
    supplierRefId: '',
    supplierCode: '',
    supplierName: '',
    legalEntityId: null,
    legalEntityCode: '',
    legalEntityName: '',
    currency: '',
    targetWarehouseId: '',
    targetWarehouseCode: '',
    targetWarehouseName: '',
    promisedArrivalDate: '',
    note: '',
    lines: [emptyLine(1)],
    shipAddress: null,
    attachments: [],
  };
}

/** 后端的表单 → 前端的表单（编辑页的初值）。 */
export function orderToInput(form: PurchaseOrderEditForm): PurchaseOrderEditOrderInput {
  return {
    supplierRefId: form.supplierRefId,
    supplierCode: form.supplierCode,
    supplierName: form.supplierName,
    legalEntityId: form.legalEntityId,
    legalEntityCode: form.legalEntityCode,
    legalEntityName: form.legalEntityName,
    currency: form.currency,
    targetWarehouseId: form.targetWarehouseId,
    targetWarehouseCode: form.targetWarehouseCode,
    targetWarehouseName: form.targetWarehouseName,
    promisedArrivalDate: form.promisedArrivalDate,
    note: form.note,
    lines: form.lines.map((line) => ({
      lineNo: line.lineNo,
      skuId: line.skuId,
      skuCode: line.skuCode,
      skuName: line.skuName,
      quantity: line.quantity,
      unitCode: line.unitCode,
      unitPrice: line.unitPrice,
      priceSource: line.priceSource,
      quoteId: line.quoteId,
      note: '',
    })),
    shipAddress: form.shipAddress
      ? {
          recipientName: form.shipAddress.recipientName,
          recipientPhone: form.shipAddress.recipientPhone,
          countryCode: form.shipAddress.countryCode,
          province: form.shipAddress.province,
          city: form.shipAddress.city,
          district: form.shipAddress.district,
          detail: form.shipAddress.detail,
          postalCode: form.shipAddress.postalCode,
          note: form.shipAddress.note,
        }
      : null,
    attachments: form.attachments.map((attachment) => ({
      mediaId: attachment.mediaId,
      attachmentType: attachment.attachmentType,
      note: attachment.note,
    })),
  };
}

// ================================================================ 展示口径

/** 「无数据」的统一文案。null 一律用它 —— 0 会被读成「不用备 / 不要钱 / 一件都没到」。 */
export const NO_DATA_TEXT = '无数据';

/** 金额的展示。null = 金额**算不出来**，**不是 0**。 */
export function fmtAmount(value: number | null | undefined): string {
  if (value === null || value === undefined) return NO_DATA_TEXT;
  const rounded = Number(value.toFixed(4));
  return rounded.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 });
}

/** 数量的展示。null 一律给「无数据」。 */
export function fmtQty(value: number | null | undefined): string {
  if (value === null || value === undefined) return NO_DATA_TEXT;
  const rounded = Number(value.toFixed(6));
  return rounded.toLocaleString('zh-CN', { maximumFractionDigits: 6 });
}

/** 汇率的展示（null = 没冻结到汇率快照，**不是 1:1**）。 */
export function fmtRate(value: number | null | undefined): string {
  if (value === null || value === undefined) return NO_DATA_TEXT;
  return value.toLocaleString('zh-CN', { minimumFractionDigits: 4, maximumFractionDigits: 8 });
}

/** 交期的展示。 */
export function fmtDate(value: string | null | undefined): string {
  if (!value) return '未定';
  return value;
}

/**
 * 编辑中的「本页试算」总额。
 *
 * <p>⚠️ 它**不是**入账金额：行金额与头金额由数据库触发器算（调用方传不进来）。
 * 页面必须把它标成试算，真正的数字在保存后由后端返回。
 *
 * @returns 试算总额；**任何一行缺数量或单价时返回 null**（不给一个看起来完整的数）
 */
export function estimateTotal(lines: PurchaseOrderEditLineInput[]): number | null {
  let total = 0;
  for (const line of lines) {
    if (
      line.quantity === null ||
      line.quantity === undefined ||
      line.unitPrice === null ||
      line.unitPrice === undefined
    ) {
      return null;
    }
    total += line.quantity * line.unitPrice;
  }
  return Number(total.toFixed(6));
}

/** 单价来源的标签。 */
export function priceSourceLabel(source: string | null | undefined): string {
  if (source === 'QUOTE') return '报价';
  if (source === 'REQUEST_ESTIMATE') return '申请预估';
  if (source === 'MANUAL') return '手工填写';
  return '未知来源';
}

/** 汇率快照状态的标签。 */
export function fxStatusLabel(status: string | null | undefined): string {
  if (status === 'CAPTURED') return '已冻结';
  if (status === 'UNAVAILABLE') return '取不到汇率快照';
  if (status === 'NOT_APPLICABLE') return '还没提交 / 不需要折算';
  return '未知';
}

/** 单位换算（箱规）状态的标签。 */
export function conversionStatusLabel(status: string | null | undefined): string {
  if (status === 'CATALOG_UNIT_CONVERSION') return '已取到箱规';
  if (status === 'MANUAL') return '手工填的';
  if (status === 'UNKNOWN') return '未取到箱规';
  return '未知';
}

/** 库存快照状态的标签。 */
export function stockStatusLabel(status: string | null | undefined): string {
  if (status === 'CAPTURED') return '已冻结';
  if (status === 'UNAVAILABLE') return '取不到库存快照';
  return '未知';
}

/** 税额状态的标签。 */
export function taxStatusLabel(status: string | null | undefined): string {
  if (status === 'NOT_AVAILABLE') return '归 12 财务算';
  if (status === 'NOT_APPLICABLE') return '本单不计税';
  return '未知';
}

/** 附件类型的标签。 */
export function attachmentTypeLabel(type: string | null | undefined): string {
  if (type === 'CONTRACT') return '合同';
  if (type === 'QUOTE') return '报价单';
  if (type === 'ORDER') return '订单';
  if (type === 'OTHER') return '其它';
  return '附件';
}

/**
 * 报价候选的标签。
 *
 * <p>SELECTED = 唯一可自动采用；AMBIGUOUS = 与另一条并列最高优先级（**要人选**）；
 * LOWER_PRIORITY = 有效备选。
 */
export function quoteSelectionLabel(state: string | null | undefined): string {
  if (state === 'SELECTED') return '默认采用';
  if (state === 'AMBIGUOUS') return '优先级并列（要你选）';
  if (state === 'LOWER_PRIORITY') return '备选';
  return '未参与选价';
}

/**
 * 能力声明里「没交付」的那些：页面底部要如实列出来。
 *
 * <p>只列 {@code available=false} 的 —— 页面要让采购员知道「为什么这单没有税额、没有审批结论」。
 */
export function unavailableCapabilities(
  caps: PurchaseOrderEditCapabilities | null | undefined,
): PurchaseOrderEditCapabilities['capabilities'] {
  if (!caps) return [];
  return caps.capabilities.filter((capability) => !capability.available);
}
