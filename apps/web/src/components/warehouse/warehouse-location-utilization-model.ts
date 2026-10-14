import type {
  LocationUtilizationCapabilities,
  LocationUtilizationDetail,
  LocationUtilizationHeatCell,
  LocationUtilizationQuery,
  LocationUtilizationReport,
  LocationUtilizationRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from './api';
import { errorText, qty } from './warehouse-facility-model';

/**
 * 8.5.2.1 库位利用率前端模型。
 *
 * <p>蓝图：「分析库位占用：容量利用率、周转率、空置库位、爆满库位、低效库位，给出调整建议。
 * 输入：库位与库存占用；出库频次。输出：利用率报表 + 热力图 + 建议。」以及
 * 「库位热力数据同时优化拣货路径（高频商品放近出口），一举两得。」
 *
 * <p>本模型钉住六件在界面上最容易说错的事：
 *
 * <ul>
 *   <li><b>「未量」与 0.0% 必须分开。</b>后端对三种情况给 {@code capacityUtilization: null}：
 *       容量从没量过、容量量出来是 0、在手为 0 的周转率。渲染成 0 会把
 *       「没人量过」说成「量过了，是空的」，把「除数是 0」说成「一个都没放」。
 *   <li><b>合计利用率只在全部库位量过容量时才有。</b>后端用
 *       {@code utilizationComputable} 表达，界面据此显示「仅覆盖 N/M 个库位」，
 *       而不是显示一个假装完整的百分比。
 *   <li><b>热力色阶由后端的 {@code heat} 驱动</b>，前端只做 0..100 → 颜色的映射。
 *       每个格子都带 {@code metric}，说明强度来自哪个真实指标 ——
 *       「容量利用率」与「在手量相对值」不是一回事，不能画成同一种色还叫同一个名字。
 *   <li><b>空置库位与「有货但压着不动」是两种形态</b>：前者在手为 0，后者出库为 0
 *       而在手不为 0。两种都要出现在建议里，但文案不同。
 *   <li><b>手数与预占不相加</b>（与 8.1.1.1 / 8.1.2.1 同一纪律）。
 *   <li><b>出库频次的归集粒度要能看见</b>，不只写在文档里 —— 所以界面直接把
 *       {@code outboundFrequencyGrain} 显示出来。
 * </ul>
 */

export type UtilizationGrade = LocationUtilizationRow['grade'];
export type UtilizationMetric = LocationUtilizationHeatCell['metric'];

/** 档位中文名兜底（后端已给 gradeLabel，这份是契约外的防御）。 */
export const gradeLabels: Record<UtilizationGrade, string> = {
  OVERFULL: '爆满',
  HIGH: '高位',
  BALANCED: '适中',
  LOW: '低位',
  IDLE: '空置',
  UNMEASURED: '未量容量',
};

export function gradeLabel(grade: UtilizationGrade): string {
  return gradeLabels[grade] ?? grade;
}

/** 强度来源中文名。界面上要能回答「这个格子为什么红」。 */
export const metricLabels: Record<UtilizationMetric, string> = {
  CAPACITY_UTILIZATION: '容量利用率',
  ON_HAND_RELATIVE: '在手量相对值',
  IDLE: '空置',
};

export function metricLabel(metric: UtilizationMetric): string {
  return metricLabels[metric] ?? metric;
}

const base = 'warehouse/location-utilization';

export interface UtilizationFilters {
  windowDays: number;
  grade: string;
  type: string;
  keyword: string;
  minUtilization: string;
  pickableOnly: boolean;
}

export const defaultFilters: UtilizationFilters = {
  windowDays: 30,
  grade: '',
  type: '',
  keyword: '',
  minUtilization: '',
  pickableOnly: false,
};

/** 空串条件一律不传 —— 传空值会让后端拿到「筛一个空字符串」的语义。 */
function query(filters: UtilizationFilters): string {
  const parts = new URLSearchParams();
  parts.set('windowDays', String(filters.windowDays));
  if (filters.grade !== '') parts.set('grade', filters.grade);
  if (filters.type !== '') parts.set('type', filters.type);
  if (filters.keyword.trim() !== '') parts.set('keyword', filters.keyword.trim());
  if (filters.minUtilization.trim() !== '')
    parts.set('minUtilization', filters.minUtilization.trim());
  if (filters.pickableOnly) parts.set('pickableOnly', 'true');
  return parts.toString();
}

export function fetchUtilization(
  warehouseId: string,
  filters: UtilizationFilters,
): Promise<LocationUtilizationReport> {
  return api<LocationUtilizationReport>(`${base}/${warehouseId}?${query(filters)}`);
}

export function fetchUtilizationCapabilities(): Promise<LocationUtilizationCapabilities> {
  return api<LocationUtilizationCapabilities>(`${base}/capabilities`);
}

export function fetchUtilizationDetail(
  warehouseId: string,
  locationId: string,
  filters: UtilizationFilters,
): Promise<LocationUtilizationDetail> {
  return api<LocationUtilizationDetail>(
    `${base}/${warehouseId}/locations/${locationId}?${query(filters)}`,
  );
}

/**
 * 利用率文本。
 *
 * <p>三态分开，不许把 null 渲染成 0：
 *
 * <ul>
 *   <li>`null` 且 capacity 未量 → 「未量」（缺的是知识）
 *   <li>`null` 且容量为 0 → 「容量为 0」（缺的是定义：除数本身是 0）
 *   <li>有值 → 百分比
 * </ul>
 *
 * 把它折成 `0.0%` 会同时说错前两种：运营会拿它去排库位，而那两格数字背后没有分子也没有分母。
 */
export function utilizationText(row: LocationUtilizationRow): string {
  if (row.capacityUtilization !== null && row.capacityUtilization !== undefined) {
    return `${(row.capacityUtilization * 100).toFixed(1)}%`;
  }
  if (!row.capacityMeasured) return '未量';
  if (row.capacityZero) return '容量为 0';
  return '未量';
}

/**
 * 周转率文本。
 *
 * <p>在手为 0 时后端给 {@code turnover: null} —— 出库 0 ÷ 在手 0 不是「0 倍」，
 * 是没有定义。渲染成 `0.0 倍` 会把「压根没货」说成「周转很慢」。
 */
export function turnoverText(row: LocationUtilizationRow): string {
  if (!row.turnoverComputable || row.turnover === null || row.turnover === undefined) {
    return '无在手量';
  }
  return `${row.turnover.toFixed(2)} 倍/窗口`;
}

/** 出库量：空库位是「没有货可出」，与「有货但一件没出」不同，两者都要说清。 */
export function outboundText(row: LocationUtilizationRow): string {
  if (row.onHand === 0) return '无在手量';
  return qty(row.outboundQuantity);
}

/** 在手天数：没有库存行时后端给 null —— 「从没放过货」不是「0 天」。 */
export function dwellText(row: LocationUtilizationRow): string {
  if (row.dwellDays === null || row.dwellDays === undefined) return '无库存记录';
  return `${row.dwellDays.toFixed(1)} 天未变动`;
}

/**
 * 热力色阶：0..100 的强度 → 品牌紫色系的一档颜色。
 *
 * <p>刻意**不**在服务端塞 RGBA：颜色是呈现责任，且同一套色板报表/图表/其它模块要复用。
 * 这里只做「强度 → 色阶」的单调映射，并保证三档之间亮度递增 ——
 * 暗色模式下也要能一眼看出深浅。
 */
export function heatColor(heat: number): string {
  const clamped = Math.max(0, Math.min(100, heat));
  // 紫蓝（品牌色）→ 品红（警示）：与仓库调色板同一族，不引入暖色「太跳」的色相。
  const stops: ReadonlyArray<readonly [number, readonly [number, number, number]]> = [
    [0, [30, 27, 75]],
    [35, [59, 54, 130]],
    [60, [91, 82, 180]],
    [80, [139, 92, 246]],
    [100, [232, 121, 249]],
  ];
  let lo = stops[0]!;
  let hi = stops[stops.length - 1]!;
  for (let i = 0; i < stops.length - 1; i++) {
    const current = stops[i]!;
    const next = stops[i + 1]!;
    if (clamped >= current[0] && clamped <= next[0]) {
      lo = current;
      hi = next;
      break;
    }
  }
  const span = hi[0] - lo[0];
  const ratio = span === 0 ? 0 : (clamped - lo[0]) / span;
  const mix = lo[1].map((c, i) => Math.round(c + (hi[1][i]! - c) * ratio));
  return `rgb(${mix[0]}, ${mix[1]}, ${mix[2]})`;
}

/** 格子上的字色：深底用白字，浅底用深字，保证对比度。 */
export function heatTextColor(heat: number): string {
  return heat >= 70 ? '#ffffff' : '#e2e8f0';
}

/**
 * 热力图按层级缩进分组。
 *
 * <p>后端给的是**扁平**格子 + depth（与 8.1.2.1 库位树同一形状：springdoc 会丢掉自引用的
 * record 属性，嵌套 children 在契约里整个消失）。所以前端按 depth 缩进渲染，
 * 契约、运行时与界面三者说同一件事。
 */
export interface HeatRow {
  cell: LocationUtilizationHeatCell;
  indent: number;
}

export function heatRows(cells: LocationUtilizationHeatCell[]): HeatRow[] {
  return cells.map((cell) => ({ cell, indent: Math.max(0, cell.depth) }));
}

/** 合计利用率文本：只在后端说「可算」时才给百分比。 */
export function totalUtilizationText(totals: LocationUtilizationReport['totals']): string {
  if (!totals.utilizationComputable || totals.capacityUtilization === null) {
    return `仅覆盖 ${totals.capacityMeasuredCount}/${totals.locationCount} 个库位`;
  }
  return `${(totals.capacityUtilization * 100).toFixed(1)}%`;
}

/** 报表是否为空（区分「空仓」与「加载失败」—— 后者走 error，不进这里）。 */
export function isEmptyUtilization(report: LocationUtilizationReport | null): boolean {
  return report === null || report.locations.length === 0;
}

/** 建议的处置优先级排序权重（后端已排好序，这里只给色调）。 */
export function severityTone(severity: string): string {
  switch (severity) {
    case 'HIGH':
      return 'text-rose-300 border-rose-500/40 bg-rose-500/10';
    case 'MEDIUM':
      return 'text-amber-300 border-amber-500/40 bg-amber-500/10';
    default:
      return 'text-slate-300 border-slate-500/40 bg-slate-500/10';
  }
}

export { errorText };
