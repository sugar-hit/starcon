import type {
  BomBinding,
  BomFreezeInput,
  BomFreezeResult,
  BomSnapshotLine,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 6.4.1.2 BOM 关联前端模型。
 *
 * <p>蓝图：「加工单引用 BOM 版本并**冻结展开结果**，后续 BOM 修改不影响已下达的加工单。」
 * 输出「**冻结的组件清单快照**」。上游 = 3.5.2.2 展开，下游 = 6.4.1.1 加工单。
 *
 * <p><b>界面纪律</b>：
 *
 * <ul>
 *   <li><b>快照是值，不是引用</b>：页面显示的每一行都是冻结那一刻的数字，
 *       读它不会去 join 现在的 BOM。所以 BOM 改配之后这张单显示的仍是旧数字 ——
 *       这正是蓝图要的行为，页面必须照实显示，而不是「刷新一下就变」。
 *   <li><b>损耗率是百分数</b>（catalog 3.5.2.1 规则 R6：5 = 5%），
 *       页面带 <code>%</code> 显示，避免被读成比率。
 *   <li><b>需求量不取整</b>（规则 R7，6 位小数）：它是「该领多少」的精确数，
 *       取整成采购量是 3.5.2.2 与 06 采购建议的职责，页面不替它们做决定。
 *   <li><b>被深度上限截断的快照必须显红</b>：它意味着清单不完整（规则 E4），
 *       静默显示一份不完整的清单比不显示更危险。
 *   <li><b>漂移要显式告警</b>：BOM 接替新版本时提示「这张单不受影响，新单另开」，
 *       而不是让操作员以为冻结失效了。
 * </ul>
 */

const base = 'trade/processing';

/** 读一张加工单的 BOM 绑定（冻结快照 + 与此刻生效版本的漂移判定）。 */
export function fetchBomBinding(orderId: string) {
  return api<BomBinding>(`${base}/orders/${encodeURIComponent(orderId)}/bom-binding`);
}

/**
 * 冻结动作：BOM 版本选择 + 冻结参数，一次请求两件事（蓝图「输入：BOM 版本选择；冻结动作」）。
 *
 * <p>POST 而不是 PUT：冻结**不可逆**，且一张单只能有一份快照。
 * PUT 的语义是「把这份资源改成这样」，与冻结正好相反。
 */
export function freezeBomBinding(orderId: string, input: BomFreezeInput) {
  return api<BomFreezeResult>(
    `${base}/orders/${encodeURIComponent(orderId)}/bom-binding`,
    'POST',
    input,
  );
}

/** 只读展开树（纯本地读，不发跨服务请求）。 */
export function fetchBomBindingLines(orderId: string) {
  return api<BomSnapshotLine[]>(`${base}/orders/${encodeURIComponent(orderId)}/bom-binding/lines`);
}

// ================================================================ 展示口径

/**
 * 需求量文案：6 位小数不取整，但去掉无意义的尾随零。
 *
 * <p>「不取整」是口径（规则 R7），去掉尾随零只是排版 —— 10.000000 与 10 是同一个数。
 */
export function requiredText(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  const parsed = typeof value === 'number' ? value : Number(value);
  if (Number.isNaN(parsed)) return String(value);
  return String(Number(parsed.toFixed(6)));
}

/** 损耗率文案：**百分数**（规则 R6），带 % 让口径一眼可见。 */
export function scrapText(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '0%';
  const parsed = typeof value === 'number' ? value : Number(value);
  if (Number.isNaN(parsed)) return String(value);
  return `${Number(parsed.toFixed(4))}%`;
}

/** 物化路径缩进（'1.3.2' → 2 级）。展开树的层在数据里，页面不自己猜层级。 */
export function depthIndent(line: BomSnapshotLine): number {
  return Math.max(0, (line.depth ?? 1) - 1);
}

/** 这一行是不是「要采购的叶子件」（规则 E2：缺口 > 0 且没有再往下展开）。 */
export function isPurchaseLine(line: BomSnapshotLine): boolean {
  return line.gap !== undefined && Number(line.gap) > 0 && !line.expanded;
}

/** 冻结状态的展示口径：只有两种，且**截断必须显红**。 */
export function snapshotTone(snapshot: BomBinding['snapshot']): 'neutral' | 'success' | 'danger' {
  if (!snapshot) return 'neutral';
  return snapshot.truncated ? 'danger' : 'success';
}

/**
 * 漂移告警的一行标题。
 *
 * <p>关键在于措辞：漂移**不等于**冻结失效。已下达的加工单照快照领料，
 * 变的是「下一批货该按哪一版做」。
 */
export function driftHeadline(binding: BomBinding): string | null {
  if (binding.drifted) {
    return `BOM 已接替新版本：本单冻结的是 ${binding.snapshot?.bomVersionNo ?? '—'}，此刻生效的是 ${
      binding.effectiveVersionNo ?? '—'
    }`;
  }
  if (binding.effectiveVersionMissing) return '这个 BOM 此刻没有生效版本';
  return null;
}
