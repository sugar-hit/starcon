import type {
  ChangePage,
  ConfigImpact,
  ConfigOptions,
  ConfigOverview,
  ShiftInput,
  ShiftSaved,
  ShiftUpdateInput,
  WarehouseConfigInput,
  WarehouseConfigSaved,
} from '../../../../../packages/api-contracts/src/index';
import { api } from './api';
import { errorText, statusLabel } from './warehouse-facility-model';

/**
 * 8.1.1.3 仓库配置前端模型。
 *
 * <p>蓝图：「配置仓库行为参数：是否支持批次/序列号、拣货策略（按订单/按波次/按库位）、
 * 默认复核比例、是否允许拆零发货、工作时段与班次。」以及「配置影响作业方式，不是纯展示项，
 * 修改需记录变更。」
 *
 * <p>这个页面上最容易做错的三件事，本模型逐一挡住：
 *
 * <ul>
 *   <li>**「未配置」与「配成默认值」是两件事。**后端给 {@code unconfigured}，
 *       界面上必须显示成「尚未配置，下面是默认值」而不是把默认值当成用户填的值显示 ——
 *       否则用户会以为这个仓的复核比例是有人特意设成 0 的。
 *   <li>**配置落库了 ≠ 下游已经在按它跑。**拣货（8.3.2）、复核（8.3.3）、
 *       批次（8.2.1.1）、序列号（7.1.1.2）、拆零（8.3.4）都还没接上，
 *       所以影响声明逐条按 {@code available} 分组显示，未生效的写明归哪个模块。
 *   <li>**留痕不是附注，是这一页的一半。**蓝图那句「修改需记录变更」是硬要求，
 *       所以历史单独成区，逐字段显示「从什么改成什么」，而不是只写一句「已保存」。
 * </ul>
 *
 * <p>数量、时刻、错误文案都复用 8.1.1.1 的实现而不是各写一份 ——
 * 同一份事实有两套格式化函数，迟早会在某一个模块里漂移。
 */

const base = 'warehouse/facilities';

export { errorText, statusLabel };

export interface ChangeParams {
  scope?: string;
  shiftId?: string;
  page?: number;
  size?: number;
}

export function fetchConfigOverview(id: string, params: ChangeParams): Promise<ConfigOverview> {
  return api<ConfigOverview>(`${base}/${id}/config${query(params)}`);
}

export function fetchConfigOptions(id: string): Promise<ConfigOptions> {
  return api<ConfigOptions>(`${base}/${id}/config/options`);
}

export function fetchConfigChanges(id: string, params: ChangeParams): Promise<ChangePage> {
  return api<ChangePage>(`${base}/${id}/config/changes${query(params)}`);
}

export function fetchConfigImpacts(id: string): Promise<ConfigImpact[]> {
  return api<ConfigImpact[]>(`${base}/${id}/config/impacts`);
}

// ------------------------------------------------------------ 草稿

export interface ConfigDraft {
  lotTrackingEnabled: boolean;
  serialTrackingEnabled: boolean;
  pickingStrategy: string;
  defaultReviewRatio: string;
  allowSplitShipment: boolean;
  note: string;
}

/**
 * 比例用**字符串**而不是 number。
 *
 * <p>0.1 在 JS 里是 0.1000000000000000055511151231257827，往后端发出去再读回来
 * 展示成 0.1 看起来没事，但它与用户输入的「0.10」是不是同一个值这件事已经说不清了。
 * 输入框本来也给字符串，草稿层保持字符串，到提交那一刻才转成 number。
 */
export function draftOf(overview: ConfigOverview): ConfigDraft {
  return {
    lotTrackingEnabled: overview.config.lotTrackingEnabled,
    serialTrackingEnabled: overview.config.serialTrackingEnabled,
    pickingStrategy: overview.config.pickingStrategy,
    defaultReviewRatio: overview.config.defaultReviewRatio.toFixed(6),
    allowSplitShipment: overview.config.allowSplitShipment,
    note: overview.config.note,
  };
}

export function updateConfig(id: string, draft: ConfigDraft): Promise<WarehouseConfigSaved> {
  const input: WarehouseConfigInput = {
    lotTrackingEnabled: draft.lotTrackingEnabled,
    serialTrackingEnabled: draft.serialTrackingEnabled,
    pickingStrategy: draft.pickingStrategy,
    // 服务端会再校验一遍区间并归一到 6 位小数；这里只保证发出去的是个数字。
    defaultReviewRatio: Number(draft.defaultReviewRatio),
    allowSplitShipment: draft.allowSplitShipment,
    note: draft.note.trim(),
  };
  return api<WarehouseConfigSaved>(`${base}/${id}/config`, 'PUT', input);
}

export interface ShiftDraft {
  code: string;
  name: string;
  startLocal: string;
  endLocal: string;
  enabled: boolean;
  note: string;
}

export function emptyShiftDraft(): ShiftDraft {
  return { code: '', name: '', startLocal: '09:00', endLocal: '18:00', enabled: true, note: '' };
}

export function draftOfShift(shift: ShiftRowLike): ShiftDraft {
  return {
    code: shift.code,
    name: shift.name,
    startLocal: shift.startLocal,
    endLocal: shift.endLocal,
    enabled: shift.enabled,
    note: shift.note,
  };
}

/** 编辑已有班次时编码不可改：它是「这个班次」在留痕里的引用键，改了历史就对不上。 */
export interface ShiftRowLike {
  id: string;
  code: string;
  name: string;
  startLocal: string;
  endLocal: string;
  enabled: boolean;
  note: string;
}

export function createShift(id: string, draft: ShiftDraft): Promise<ShiftSaved> {
  const input: ShiftInput = {
    code: draft.code.trim().toUpperCase(),
    name: draft.name.trim(),
    startLocal: draft.startLocal,
    endLocal: draft.endLocal,
    enabled: draft.enabled,
    note: draft.note.trim(),
  };
  return api<ShiftSaved>(`${base}/${id}/config/shifts`, 'POST', input);
}

export function updateShift(
  id: string,
  shiftId: string,
  draft: ShiftDraft,
): Promise<ShiftSaved> {
  const input: ShiftUpdateInput = {
    name: draft.name.trim(),
    startLocal: draft.startLocal,
    endLocal: draft.endLocal,
    enabled: draft.enabled,
    note: draft.note.trim(),
  };
  return api<ShiftSaved>(`${base}/${id}/config/shifts/${shiftId}`, 'PUT', input);
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

// ------------------------------------------------------------ 展示

/** 比例的展示。统一 2 位百分比，不给 6 位小数让人以为自己看到了精度。 */
export function ratioText(ratio: number): string {
  return `${(ratio * 100).toFixed(2)}%`;
}

/** 留痕里比例值（"0.050000"）的展示。与 ratioText 同一口径，别让人换算。 */
export function ratioValueText(value: string | null): string {
  if (value === null || value === '') return '（无）';
  const parsed = Number(value);
  if (Number.isFinite(parsed)) return ratioText(parsed);
  return value;
}

/** 布尔留痕值的展示。 */
export function boolText(value: string | null): string {
  if (value === null) return '（无）';
  return value === 'true' ? '是' : '否';
}

/** 未配置时的那句话。让「没配过」与「配成了默认值」在界面上分开。 */
export function unconfiguredText(config: { unconfigured: boolean }): string {
  return config.unconfigured
    ? '尚未配置：下面显示的是默认值，作业侧读到的是这一组默认值而不是人工确认过的参数。'
    : '';
}

export function strategyLabel(code: string, options: ConfigOptions | null): string {
  return options?.strategies.find((s) => s.code === code)?.label ?? code;
}

export function strategyNote(code: string, options: ConfigOptions | null): string {
  return options?.strategies.find((s) => s.code === code)?.note ?? '';
}

/** 留痕范围的中文名。与后端 warehouse_config_change.scope 的三个取值对应。 */
export function scopeLabel(scope: string): string {
  if (scope === 'CONFIG') return '作业参数';
  if (scope === 'SHIFT') return '班次字段';
  if (scope === 'SHIFT_SCHEDULE') return '班次启用状态';
  return scope;
}

/** 一条留痕的展示行：「字段名 从什么 → 什么」。 */
export function changeText(
  change: ConfigChangeLike,
  boolField: (field: string) => boolean,
  ratioField: (field: string) => boolean,
): { field: string; from: string; to: string } {
  const render = (value: string | null): string => {
    if (ratioField(change.fieldName)) return ratioValueText(value);
    if (boolField(change.fieldName)) return boolText(value);
    if (value === null) return '（无）';
    if (value === '') return '（空）';
    return value;
  };
  return { field: change.fieldName, from: render(change.oldValue), to: render(change.newValue) };
}

export interface ConfigChangeLike {
  fieldName: string;
  oldValue: string | null;
  newValue: string | null;
}

// 哪些字段是布尔、哪些是比例 —— 留痕里所有值都是规范化文本，
// 展示时要还原成人话，否则「0.050000」和「WAVE」会读得莫名其妙。
const BOOL_FIELDS = new Set([
  'lotTrackingEnabled',
  'serialTrackingEnabled',
  'allowSplitShipment',
  'enabled',
]);
const RATIO_FIELDS = new Set(['defaultReviewRatio']);

export function isBoolField(field: string): boolean {
  return BOOL_FIELDS.has(field);
}

export function isRatioField(field: string): boolean {
  return RATIO_FIELDS.has(field);
}

/** 班次时长的展示：跨夜班会大于 24 小时，说清楚它跨到了次日。 */
export function durationText(minutes: number, crossesMidnight: boolean): string {
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  const text = rest === 0 ? `${hours} 小时` : `${hours} 小时 ${rest} 分`;
  return crossesMidnight ? `${text}（跨夜，至次日）` : text;
}

/** 时段的展示。带上「当地」两个字，避免有人把它当成 UTC。 */
export function windowText(startLocal: string, endLocal: string, crossesMidnight: boolean): string {
  const head = `${startLocal} → ${endLocal}`;
  return crossesMidnight ? `${head}（跨夜）` : head;
}

// ------------------------------------------------------------ 校验

/**
 * 表单的前端校验。界面上先拦住，用户就不用等一个 400。
 *
 * <p>后端仍然独立校验一遍 —— 前端校验是体验，不是安全边界。
 */
export function configProblem(draft: ConfigDraft): string {
  const ratio = draft.defaultReviewRatio.trim();
  if (ratio === '') return '请填写默认复核比例';
  const parsed = Number(ratio);
  if (!Number.isFinite(parsed)) return '默认复核比例必须是数字（如 0.05）';
  if (parsed < 0 || parsed > 1) return '默认复核比例必须在 0 到 1 之间（0 = 不复核，1 = 全量复核）';
  if (!['ORDER', 'WAVE', 'LOCATION'].includes(draft.pickingStrategy))
    return '请选择拣货策略（按订单 / 按波次 / 按库位）';
  return '';
}

const CLOCK = /^([01]\d|2[0-3]):[0-5]\d$/;

export function shiftProblem(draft: ShiftDraft, editing: boolean): string {
  if (!editing && !draft.code.trim()) return '请填写班次编码';
  if (editing && !draft.code.trim()) return '班次编码不能为空（编辑时编码不可改）';
  if (!draft.name.trim()) return '请填写班次名称';
  if (!CLOCK.test(draft.startLocal.trim())) return '上班时刻格式应为 HH:mm';
  if (!CLOCK.test(draft.endLocal.trim())) return '下班时刻格式应为 HH:mm';
  if (draft.startLocal.trim() === draft.endLocal.trim())
    return '上班与下班时刻不能相同（相同会被当成跨夜 24 小时的班）';
  return '';
}

/** 保存后的提示。带上留痕条数 —— 蓝图的留痕要求要让用户看得见。 */
export function savedText(changeCount: number): string {
  if (changeCount === 0) return '已保存。本次没有字段发生变化，因此没有新增变更留痕。';
  return `已保存，本次变更已写入 ${changeCount} 条留痕（逐字段记录改前与改后的值）。`;
}

export const ALL = '';
