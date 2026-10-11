import type {
  SalesOrderOverview,
  SalesOrderOverviewCapabilities,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 9.1.1.2 订单详情（控制塔）前端模型。
 *
 * <p>蓝图：「<b>详情页是订单的『控制塔』</b>：客服、采购、财务都从这一个页面理解这笔订单
 * 为什么长这样。」控制塔的第一要求不是「信息全」，而是<b>「每一段要么是真的，要么明说它没有」</b> ——
 * 一个空白的包裹列表会被读成「这单没有包裹」，而不是「这段能力还没接入」。
 *
 * <p><b>界面纪律（与 9.1.1.1 列表页同源，但更严格）</b>：
 *
 * <ul>
 *   <li><b>七段不可得分区显示为「—」并可点开看归属</b>（{@link unavailableCell}），
 *       而不是空数组渲染成「暂无数据」。后者会被读成「查过了，确实没有」。</li>
 *   <li><b>费用构成与费用分摊在界面上是两个区块</b>（{@link allocationBanner}）：
 *       构成有数据、分摊没有。把构成摆在分摊的位置上，
 *       会让「这一行到底买了多少钱」保持沉默的同时看起来像是有答案。</li>
 *   <li><b>「单据状态」不叫「履约状态」</b>：DRAFT / SUBMITTED 说的是「有没有进审单队列」。</li>
 *   <li><b>操作入口一律禁用并说明前置条件</b>（{@link actionDisabled}）：本模块是读投影，
 *       没有写端点，给一个能点的按钮只会让人点了得到 405。</li>
 * </ul>
 *
 * <p>路径前缀是 {@code trade/sales-orders/detail} ——
 * <b>不是</b> {@code trade/sales-orders/{id}}：那个端点已被 9.1.2.2 占用
 * （手工订单详情）。同一个 {@code @RequestMapping} 下再加一个 {@code @GetMapping("/{id}")}
 * 会让 Spring 在<b>启动时</b>抛 ambiguous mapping 把整个 trade 服务弄挂。
 */

const base = 'trade/sales-orders/detail';

export function fetchOrderOverview(id: string) {
  return api<SalesOrderOverview>(`${base}/${encodeURIComponent(id)}`);
}

export function fetchOrderOverviewCapabilities() {
  return api<SalesOrderOverviewCapabilities>(`${base}/capabilities`);
}

// ================================================================ 展示口径

export const documentStatusLabels: Record<string, string> = {
  DRAFT: '草稿',
  SUBMITTED: '已提交待审',
};

export const orderSourceLabels: Record<string, string> = {
  MANUAL: '手工订单',
  PLATFORM: '平台订单',
  AMAZON: 'Amazon',
  SHOPIFY: 'Shopify',
  EBAY: 'eBay',
  TIKTOK_SHOP: 'TikTok Shop',
  ALIBABA_1688: '1688',
};

export const addressRoleLabels: Record<string, string> = {
  SHIPPING: '收货地址',
  BILLING: '账单地址',
};

export const chargeTypeLabels: Record<string, string> = {
  SHIPPING: '运费',
  DISCOUNT: '折扣',
  TAX: '税费',
  PACKAGING: '特殊包装',
  OTHER: '其他费用',
};

export const chargeKindLabels: Record<string, string> = {
  ORDER: '整单费用',
  LINE: '记在订单行名下',
};

export const manualKindLabels: Record<string, string> = {
  OFFLINE_STORE: '独立站线下',
  B2B: 'B2B',
  DROPSHIP: '代发',
  SAMPLE: '样机单',
};

export const paymentMethodLabels: Record<string, string> = {
  PREPAID: '预付',
  COD: '货到付',
  BANK_TRANSFER: '银行转账',
  CREDIT_TERM: '账期',
  FREIGHT_FORWARDER: '货代代收',
  OTHER: '其他',
};

/** 七段不可得分区的固定顺序（与后端 capabilities 的顺序一致，便于对读）。 */
export const UNAVAILABLE_SECTION_ORDER = [
  'chargeAllocations',
  'lineComponents',
  'payments',
  'refunds',
  'fulfillmentBatches',
  'packages',
  'logisticsTrace',
  'aftersalesRefs',
  'platformRawPayload',
] as const;

export const unavailableSectionLabels: Record<string, string> = {
  chargeAllocations: '费用分摊',
  lineComponents: '组件展开',
  payments: '支付记录',
  refunds: '退款记录',
  fulfillmentBatches: '履约批次',
  packages: '包裹',
  logisticsTrace: '物流轨迹',
  aftersalesRefs: '售后关联',
  platformRawPayload: '平台原始报文',
  fulfillmentStatus: '履约状态（时间线）',
};

export function unavailableSectionLabel(key: string): string {
  return unavailableSectionLabels[key] ?? key;
}

/**
 * 金额格式化。null → 「—」。
 *
 * <p><b>null 与 0 必须分开</b>：这一行有 0 笔包裹是真的，包裹能力没接入不是。
 */
export function formatAmount(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  return String(value);
}

/**
 * 不可得分区的展示文案。
 *
 * <p><b>返回 null 而不是「暂无数据」</b> —— 后者会被读成「查过了，确实没有」。
 * 界面上这里渲染成一个可点开的「—」，点开写明归属模块与原因。
 */
export function unavailableCell(
  value: unknown,
  section: { ownerModule?: string | null; reason?: string | null } | undefined,
): { text: string; title: string } | null {
  if (value !== null && value !== undefined) return null;
  const owner = section?.ownerModule ?? '待定';
  const reason = section?.reason ?? '这段能力尚未接入';
  return {
    text: '—',
    title: `尚未接入 · 归属模块 ${owner}｜${reason}`,
  };
}

/**
 * 费用构成区块的固定横幅。
 *
 * <p><b>这是本模型最重要的一行文案。</b>费用构成**有**数据、费用分摊**没有**表，
 * 两者在界面上必须显式分开 —— 否则客服会拿着「运费 8 元」去回答
 * 「那这个运费摊到这件衬衫上是几元」，而系统里没有任何一个数能回答它。
 */
export function allocationBanner(
  allocations: unknown,
  section: { ownerModule?: string | null; reason?: string | null } | undefined,
): { title: string; body: string } | null {
  if (allocations !== null && allocations !== undefined) return null;
  return {
    title: '费用分摊尚未接入',
    body:
      section?.reason ??
      '本服务没有费用分摊表。上面显示的是**费用构成**（这笔单一共收了哪些钱），' +
        '不是分摊（这笔整单费用按什么比例摊到了哪几行）。',
  };
}

/**
 * 操作入口的禁用说明。
 *
 * <p>本模块是 sales_order 簇的读投影，<b>没有任何写端点</b>：给一个能点的按钮
 * 只会让人点了拿到 405，而 405 不解释为什么。
 */
export function actionDisabled(
  code: string,
  action: { ownerModule?: string | null; precondition?: string | null; reason?: string | null } | undefined,
): { disabled: true; title: string } {
  const owner = action?.ownerModule ?? '待定';
  const precondition = action?.precondition ? `前置条件：${action.precondition}` : '';
  const reason = action?.reason ?? '本模块是读投影，不提供写端点';
  return {
    disabled: true,
    title: `暂不可用 · 归属模块 ${owner}｜${reason}${precondition ? `\n${precondition}` : ''}（${code}）`,
  };
}

export function formatTime(value: string | null | undefined): string {
  if (!value) return '—';
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  return d.toISOString().replace('T', ' ').slice(0, 19);
}

export function orderByKey(key: string | null | undefined): string {
  if (!key) return '—';
  return (
    { lineComponents: '组件展开', sales_order_line: '商品行', order_charge: '费用构成' }[key] ?? key
  );
}