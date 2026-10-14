import type {
  ParcelCostAllocation,
  ParcelDetail,
  ParcelDetailCapabilities,
  ParcelItemSalesOrderLine,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 10.2.2.1 包裹详情前端模型。
 *
 * <p>蓝图 10 卷：「<b>单包裹视图：包裹内 SKU 与数量（parcel_item）、订单行对应关系、重量体积、
 * 面单、轨迹、交运信息。</b>输入：parcel_id；关联订单行与轨迹。」
 * 输出：「包裹详情 + 操作入口。」
 * 关系：「上游 = 8.3.3.2；下游 = 10.3.1.1、12 财务（运费成本归集）。
 * <b>parcel_item 与订单行的对应关系是成本归集到行的基础，缺了就只能算到订单级。</b>」
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>没量 ≠ 量出来是 0。</b>未称重/未量尺寸显示「未称重 / 未量尺寸」，
 *       不显示「0 g / 0 cbm」—— 后者会被读成「这个箱子重 0 克」。</li>
 *   <li><b>归集到行还是订单级必须一眼可见。</b>{@code allocatableToLine} 是主结论：
 *       false 时整块标成「只能归到订单级」，并列出没关联上的明细项 ——
 *       不静默按订单摊，也不把空数组画成「已全部关联」。</li>
 *   <li><b>弱关联要显式标注。</b>{@code strongMatch === false} 时打「弱关联」标签，
 *       让财务知道这条关联的依据不如强关联可靠。</li>
 *   <li><b>体积重不显示、也不试算。</b>除数（6000/5000/…）归 10 物流，界面只展示实测三边与
 *       实测体积，并把后端的 {@code volumeDivisorNote} 原样摆在口径行上。</li>
 *   <li><b>不可得分段画成灰块而不是空白。</b>{@code unavailable} 里 available=false 的段落
 *       写明归谁、为什么 —— 空白会被读成「这一票没有轨迹」。</li>
 *   <li>加载 / 空 / 错误三态分开渲染。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code warehouse/parcels}，刻意不挂在 {@code packing-orders} 之下 ——
 * 10 物流查轨迹、12 财务做运费归集时手里拿的是 parcel_id，不是作业单 id。
 */

const base = 'warehouse/parcels';

/** 成本归集的落点：归到行，还是退回订单级。 */
export type AllocationLevel = 'LINE' | 'ORDER';

export function parcelCapabilities() {
  return api<ParcelDetailCapabilities>(`${base}/capabilities`);
}

export function parcelDetail(parcelId: string) {
  return api<ParcelDetail>(`${base}/${parcelId}`);
}

/** 重量展示：未称重返回 null，页面据此显示「未称重」而不是 0 g。 */
export function weightCell(value: number | null, unit: string): string {
  if (value === null || value === undefined) return '未量';
  return `${trimNumber(value)} ${unit}`;
}

/** 体积展示：未量返回 null，页面据此显示「未量」而不是 0 cbm。 */
export function volumeCell(value: number | null): string {
  if (value === null || value === undefined) return '未量';
  return `${trimNumber(value)} cbm`;
}

/** 外形尺寸三件：任一未量就整体显示「未量尺寸」，不拼一个半空的尺寸串。 */
export function dimensionCell(
  length: number | null,
  width: number | null,
  height: number | null,
): string {
  if (length === null || width === null || height === null) return '未量尺寸';
  return `${trimNumber(length)} × ${trimNumber(width)} × ${trimNumber(height)} cm`;
}

/** 数量：NUMERIC(19,6) 落到 JSON 是 number，去掉无意义的尾零（6.000000 → 6）。 */
export function quantityCell(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return trimNumber(value);
}

/**
 * 归集落点的结论文案。
 *
 * <p>这是本模块最要紧的一句界面话：蓝图说「缺了就只能算到订单级」，
 * 所以退回订单级时必须**说清是订单级**并给出未关联的件数，而不是让用户以为已经归到行了。
 */
export function allocationVerdict(allocation: ParcelCostAllocation): {
  level: AllocationLevel;
  label: string;
  tone: 'ok' | 'warn';
} {
  if (allocation.allocatableToLine) {
    return {
      level: 'LINE',
      label: `可归集到销售订单行（${allocation.distinctSalesOrderLineCount} 行 / ${allocation.distinctSalesOrderCount} 单）`,
      tone: 'ok',
    };
  }
  return {
    level: 'ORDER',
    label: `只能归到订单级：${allocation.unlinkedItemCount} 条明细项未关联销售订单行，涉及 ${trimNumber(
      allocation.unmatchedQuantity,
    )} 件`,
    tone: 'warn',
  };
}

/**
 * 未关联明细项的展示行。
 *
 * <p>退回订单级时逐条列出来，是为了让「这一箱为什么摊不到行」可以追到具体是哪几件货，
 * 而不是只给一个总数。
 */
export function unmatchedRows(allocation: ParcelCostAllocation): string[] {
  return allocation.unmatchedItems.map(
    (u) => `#${u.itemNo} ${u.skuCode}（${u.skuName}）· ${quantityCell(u.quantity)} 件`,
  );
}

/**
 * 销售订单行关联的展示标签。
 *
 * <p>强弱要分开标：强关联（两边都按 (order, sku) 唯一键对齐）可以直接拿去归集，
 * 弱关联（编码一致但 uuid 不同）应当提示人工复核 —— 把两者画成同一种绿色是最危险的简化。
 */
export function orderLineLabel(link: ParcelItemSalesOrderLine): string {
  const strength = link.strongMatch ? '强关联' : '弱关联';
  return `${link.salesOrderNo} · 第 ${link.salesOrderLineNo} 行 · ${strength} · 分摊 ${quantityCell(
    link.allocatedQuantity,
  )} 件`;
}

/** 匹配口径的人读标签；未知口径原样返回，不静默吞掉。 */
export function matchBasisLabel(link: ParcelItemSalesOrderLine): string {
  return link.matchBasisLabel && link.matchBasisLabel.length > 0
    ? link.matchBasisLabel
    : link.matchBasis;
}

/** 不可得分段的灰块文案：归谁 + 为什么。 */
export function unavailableText(
  item: { label: string; ownerModule: string | null; reason: string },
): string {
  return item.ownerModule ? `${item.label} · 归 ${item.ownerModule} 未接入` : `${item.label} · 未接入`;
}

/** 操作入口：没有 href 的（运费归集在本服务没有页面）只展示归属与前置条件。 */
export function actionHint(action: {
  href: string | null;
  ownerModule: string;
  precondition: string;
}): string {
  const pre = action.precondition && action.precondition !== '无' ? `前置：${action.precondition}` : '';
  return `${action.ownerModule}${pre ? ` · ${pre}` : ''}`;
}

/** 去掉 number 的无意义尾零，同时保留业务需要的精度（不四舍五入改数）。 */
function trimNumber(value: number): string {
  if (!Number.isFinite(value)) return String(value);
  return String(Number(value.toFixed(6)));
}
