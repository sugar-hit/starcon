/**
 * 13.6.1.2 看板搭建 前端模型（卷 13「利润与经营分析」L3 13.6.1 下的第二格）。
 *
 * <p>蓝图原文：「搭建自定义经营看板：拖拽组件、选择指标与筛选、布局保存、按角色共享、
 * 设置默认值。」输出「看板视图 + 共享配置。」关系：上游 = 13.6.1.1；下游 = 1 工作台
 * （可置为个人看板）。<strong>看板需权限过滤</strong>，不同角色看到的组件不同，不能一个看板通吃。
 *
 * <p><b>🔴 这个页面上最容易做丢的五件事，这里都挡住了</b>：
 *
 * <ol>
 *   <li><b>「算不出来」≠「数据是 0」</b>。服务端 {@code availability='NOT_COMPARABLE'} 的取值渲染成
 *       「—」并<strong>带上原因</strong>，<strong>不是</strong> 0%。分母为 0 的比率显示成 0% 会被读成
 *       「效率为零」，而真相是「压根算不出来」。</li>
 *   <li><b>多币种绝不相加</b>。一个维度取值下可能有多个币种，<strong>分行显示</strong>，
 *       绝不合并成一个数。</li>
 *   <li><b>前端不做业务算术</b>。金额、比率、派生值全部来自服务端（{@code BigDecimal}），
 *       这里只做<strong>展示格式化</strong>。</li>
 *   <li><b>权限过滤是后端的事</b>。前端<strong>不</strong>自己藏组件 —— 组件集合是服务端按
 *       当前用户的权限 / 角色给出来的；前端只负责把「你看得见几个、这份看板一共几个」
 *       如实说出来。</li>
 *   <li><b>数据更新时间常驻</b>。卷 13 卷首加粗句「数据延迟要显示」。</li>
 * </ol>
 *
 * <p><b>本文件是纯函数层</b>：不碰网络、不碰 React、不重算业务口径。
 */

import type {
  BoardCapabilities,
  BoardComponentData,
  BoardComponentPoint,
  BoardComponentSaveRequest,
  BoardComponentValue,
  BoardComponentView,
  BoardDefaultsView,
  BoardDimensionView,
  BoardFilterRequest,
  BoardFilterView,
  BoardLayoutItem,
  BoardLayoutSaveRequest,
  BoardLayoutView,
  BoardMetricView,
  BoardPage,
  BoardRuleNote,
  BoardSaveRequest,
  BoardShareRequest,
  BoardShareView,
  BoardSummary,
  BoardTemplateInstantiateRequest,
  BoardTemplateSaveRequest,
  BoardTemplateView,
  BoardView,
  BoardViewResponse,
  BoardWorkbenchItem,
  BoardWorkbenchResponse,
} from '../../../../../packages/api-contracts/src/index';

export type {
  BoardCapabilities,
  BoardComponentData,
  BoardComponentPoint,
  BoardComponentSaveRequest,
  BoardComponentValue,
  BoardComponentView,
  BoardDefaultsView,
  BoardDimensionView,
  BoardFilterRequest,
  BoardFilterView,
  BoardLayoutItem,
  BoardLayoutSaveRequest,
  BoardLayoutView,
  BoardMetricView,
  BoardPage,
  BoardRuleNote,
  BoardSaveRequest,
  BoardShareRequest,
  BoardShareView,
  BoardSummary,
  BoardTemplateInstantiateRequest,
  BoardTemplateSaveRequest,
  BoardTemplateView,
  BoardView,
  BoardViewResponse,
  BoardWorkbenchItem,
  BoardWorkbenchResponse,
};

// ================================================================ 栅格常量

/** 看板宽度（栅格列数）—— 与服务端 `ReportingBoardCatalog.GRID_COLUMNS` 同一条规则。 */
export const GRID_COLUMNS = 12;

/** 金额刻度（与服务端 numeric(19,4) 对齐，展示时保留 2 位即可）。 */
const MONEY_DECIMALS = 2;

/** 数量刻度（服务端 numeric(19,6)，展示时保留 3 位）。 */
const QUANTITY_DECIMALS = 3;

/** 百分比刻度（服务端已经算成百分数，例如 60.0000 = 60%）。 */
const PERCENT_DECIMALS = 2;

/** 计数刻度。 */
const COUNT_DECIMALS = 0;

function formatNumber(value: number, decimals: number): string {
  return value.toLocaleString('en-US', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  });
}

/** 单位 → 小数位。 */
function decimalsOf(unit: string): number {
  if (unit === 'PERCENT') return PERCENT_DECIMALS;
  if (unit === 'QUANTITY') return QUANTITY_DECIMALS;
  if (unit === 'COUNT') return COUNT_DECIMALS;
  return MONEY_DECIMALS;
}

// ================================================================ 布局（拖拽）

/**
 * 布局是否合法（**与服务端 `reporting_board_layout_ok` 同一条规则**）。
 *
 * <p>⚠️ 服务端仍然会再校验一次 —— 前端这道只是为了**当场**告诉用户哪儿不对，
 * 不是为了替服务端做主。
 */
export function layoutError(layout: BoardLayoutView): string {
  if (!Number.isInteger(layout.x) || !Number.isInteger(layout.y)) {
    return '布局的位置必须是整数';
  }
  if (layout.x < 0 || layout.y < 0) return '布局的位置不能是负数';
  if (!Number.isInteger(layout.w) || !Number.isInteger(layout.h)) {
    return '布局的尺寸必须是整数';
  }
  if (layout.w < 1 || layout.h < 1) return '布局的尺寸至少是 1×1';
  if (layout.x + layout.w > GRID_COLUMNS) {
    return `布局超出看板宽度（看板宽 ${GRID_COLUMNS} 栅格）`;
  }
  return '';
}

/** 布局的展示文本（页面上照着显示，别自己编一句）。 */
export function layoutText(layout: BoardLayoutView): string {
  return `第 ${layout.x + 1} 列起 · 宽 ${layout.w} × 高 ${layout.h}`;
}

/** CSS Grid 的列位置（1 起始）。 */
export function gridColumnStart(layout: BoardLayoutView): number {
  return layout.x + 1;
}

/** CSS Grid 的行位置（1 起始）。 */
export function gridRowStart(layout: BoardLayoutView): number {
  return layout.y + 1;
}

/**
 * 把一个组件往右挪一格（**挪出看板就停住**，不静默丢弃）。
 *
 * @returns 新的布局；挪不动时返回原布局
 */
export function moveRight(layout: BoardLayoutView): BoardLayoutView {
  if (layout.x + layout.w >= GRID_COLUMNS) return layout;
  return { ...layout, x: layout.x + 1 };
}

/** 把一个组件往左挪一格。 */
export function moveLeft(layout: BoardLayoutView): BoardLayoutView {
  if (layout.x <= 0) return layout;
  return { ...layout, x: layout.x - 1 };
}

/** 把一个组件往下挪一行。 */
export function moveDown(layout: BoardLayoutView): BoardLayoutView {
  return { ...layout, y: layout.y + 1 };
}

/** 把一个组件往上挪一行。 */
export function moveUp(layout: BoardLayoutView): BoardLayoutView {
  if (layout.y <= 0) return layout;
  return { ...layout, y: layout.y - 1 };
}

/**
 * 拖拽落位：**交换**两个组件的位置（尺寸跟着卡片走）。
 *
 * @returns 新的组件列表（顺序按 y、再按 x 排）
 */
export function swapLayouts(
  components: BoardComponentView[],
  draggedId: string,
  targetId: string,
): BoardComponentView[] {
  const dragged = components.find((component) => component.componentId === draggedId);
  const target = components.find((component) => component.componentId === targetId);
  if (!dragged || !target || draggedId === targetId) return components;
  const next = components.map((component) => {
    if (component.componentId === draggedId) {
      return { ...component, layout: { ...target.layout, w: dragged.layout.w, h: dragged.layout.h } };
    }
    if (component.componentId === targetId) {
      return { ...component, layout: { ...dragged.layout, w: target.layout.w, h: target.layout.h } };
    }
    return component;
  });
  return sortByLayout(next);
}

/** 按布局排序（先 y 再 x）。 */
export function sortByLayout(components: BoardComponentView[]): BoardComponentView[] {
  return [...components].sort((left, right) => {
    if (left.layout.y !== right.layout.y) return left.layout.y - right.layout.y;
    if (left.layout.x !== right.layout.x) return left.layout.x - right.layout.x;
    return left.sortOrder - right.sortOrder;
  });
}

/** 把组件列表转成「批量保存布局」的请求体。 */
export function toLayoutRequest(components: BoardComponentView[]): BoardLayoutSaveRequest {
  return {
    components: sortByLayout(components).map((component, index) => ({
      componentId: component.componentId,
      x: component.layout.x,
      y: component.layout.y,
      w: component.layout.w,
      h: component.layout.h,
      sortOrder: index,
    })),
  };
}

/** 布局是否**真的**变了（用来决定要不要提示「未保存」）。 */
export function layoutChanged(
  before: BoardComponentView[],
  after: BoardComponentView[],
): boolean {
  if (before.length !== after.length) return true;
  const byId = new Map(before.map((component) => [component.componentId, component]));
  for (const component of after) {
    const original = byId.get(component.componentId);
    if (!original) return true;
    if (
      original.layout.x !== component.layout.x ||
      original.layout.y !== component.layout.y ||
      original.layout.w !== component.layout.w ||
      original.layout.h !== component.layout.h
    ) {
      return true;
    }
  }
  return false;
}

// ================================================================ 展示格式化

/**
 * 一个币种取值的展示文本。
 *
 * <p>🔴 {@code value === null} ⇒ 「—」并<strong>带上原因</strong>；绝不用 0 顶替。
 */
export function componentValueText(value: BoardComponentValue, unit: string): string {
  if (value.value === null) return '—';
  const text = formatNumber(value.value, decimalsOf(unit));
  // ⚠️ 百分比先判：百分比指标**不带币种**（空串 = 与币种无关），
  //   但「60.00%」里的百分号**不能**因为币种为空就丢掉。
  if (unit === 'PERCENT') return `${text}%`;
  if (value.currency === '') return text;
  return `${text} ${value.currency}`;
}

/** 不可比 / 没有数据的说明。 */
export function componentValueNote(value: BoardComponentValue): string {
  if (value.availability === 'AVAILABLE') return '';
  if (value.availability === 'NOT_COMPARABLE') {
    return value.availabilityNote || '算不出来（不是 0）';
  }
  return value.availabilityNote || '这个口径下没有事实数据（不是 0）';
}

/** 一个组件的一个维度取值上的全部取值文本（**多币种分行，不相加**）。 */
export function pointValueLines(point: BoardComponentPoint, unit: string): string[] {
  return point.values.map((value) => componentValueText(value, unit));
}

/** 组件的取值行（每个维度取值一行）。 */
export function componentRows(component: BoardComponentData): {
  dimensionValue: string;
  lines: string[];
  notes: string[];
}[] {
  return component.points.map((point) => ({
    dimensionValue: point.dimensionValue === '' ? '—（不适用）' : point.dimensionValue,
    lines: pointValueLines(point, component.unit),
    notes: point.values.map((value) => componentValueNote(value)).filter((note) => note !== ''),
  }));
}

/** 组件标题（指标名 + 维度名）。 */
export function componentTitle(component: BoardComponentView): string {
  return `${component.metricDisplayName || component.metricCode} · ${component.dimensionCode}`;
}

/** 组件卡片的副标题（口径说明 / 单位）。 */
export function componentSubtitle(component: BoardComponentView): string {
  if (component.unit === 'PERCENT') return '百分比';
  if (component.unit === 'CURRENCY') return '金额';
  if (component.unit === 'QUANTITY') return '数量';
  if (component.unit === 'COUNT') return '笔数';
  return component.unit;
}

// ================================================================ 权限与共享

/** 「谁才看得懂这个组件」的中文标签。 */
export function requiredPermissionLabel(requiredPermission: string): string {
  if (requiredPermission === '') return '所有人可见';
  return `需要 ${requiredPermission}`;
}

/** 共享的中文标签（角色 / 权限码二选一）。 */
export function shareTargetLabel(share: BoardShareView): string {
  if (share.roleId) return `角色 ${share.roleId}`;
  return `权限码 ${share.permissionCode}`;
}

/** 共享范围的中文标签。 */
export function shareScopeLabel(scope: string): string {
  return scope === 'TENANT' ? '租户内成员' : '仅命中角色的人';
}

/** 看板可见范围的中文标签。 */
export function visibilityLabel(visibility: string): string {
  return visibility === 'TEAM' ? '团队可见' : '仅自己可见';
}

/** 图表类型的中文标签。 */
export function chartTypeLabel(chartType: string): string {
  switch (chartType) {
    case 'BAR':
      return '柱状图';
    case 'LINE':
      return '折线图';
    case 'PIE':
      return '饼图';
    default:
      return '表格';
  }
}

// ================================================================ 权限过滤的如实说明

/**
 * 「你看得见几个组件」的口径说明。
 *
 * <p>🔴 **分母是这份看板上的组件总数**，分子是**当前角色**看得见的个数 ——
 * 两者不等是正常的（这就是「看板需权限过滤」），页面必须把话说清楚，
 * 不能让人以为「看板坏了」。
 */
export function permissionFilterNote(visible: number, total: number): string {
  if (total === 0) return '这份看板上还没有组件。';
  if (visible === total) return `这份看板共 ${total} 个组件，你都能看见。`;
  return `这份看板共 ${total} 个组件，你的角色只看得见 ${visible} 个（其余组件按角色权限过滤，不是坏了）。`;
}

// ================================================================ 页面状态

/** 页面的三种状态分开说。 */
export type PageState = 'loading' | 'error' | 'empty' | 'ready';

/**
 * 页面状态判定。
 *
 * <p>🔴 **「读不到」与「没有数据」是两件事**：读不到（服务不可达 / 无权限）⇒ 错误态；
 * 读到了但一格数据都没有 ⇒ 空态。
 */
export function pageState(response: BoardViewResponse | null, error: string): PageState {
  if (error) return 'error';
  if (!response) return 'loading';
  if (response.status === 'NO_DATA') return 'empty';
  return 'ready';
}

/** 看板视图的空态文案。 */
export function viewEmptyText(response: BoardViewResponse | null): string {
  if (!response) return '';
  if (response.components.length === 0) {
    return '这个视图里一个组件都没有（可能你的角色看不到这些组件）。';
  }
  // 空态原因在**组件**上（每个组件各自知道自己有没有事实数据）。
  const empty = response.components.find((component) => component.emptyReason === 'NO_FACTS');
  if (empty) {
    return empty.emptyNote || '这个时间窗口里没有任何事实数据（不是 0）。';
  }
  return response.statusNote || '这个时间窗口里没有任何组件取到数据（不是 0）。';
}

/** 看板清单的空态文案（区分「筛选无结果」与「还没建过看板」）。 */
export function listEmptyText(page: BoardPage | null): string {
  if (!page) return '';
  if (page.keyword || page.visibilityFilter) {
    return '没有看板匹配当前筛选条件。';
  }
  return '还没有建过看板。先建一张：加组件 → 拖位置 → 设默认值 → 按角色共享。';
}

/** 工作台（我的看板）的空态文案。 */
export function workbenchEmptyText(workbench: BoardWorkbenchResponse | null): string {
  if (!workbench) return '';
  if (workbench.boards.length === 0) {
    return '你还没有能打开的看板。建一张，或者让同事把看板共享给你。';
  }
  return '';
}

/** 看板清单一行的摘要。 */
export function summaryText(summary: BoardSummary): string {
  return `${summary.visibleComponentCount}/${summary.componentCount} 个组件可见 · ${visibilityLabel(summary.visibility)}`;
}

// ================================================================ 数据更新时间

/**
 * 「数据截至 X」文案。
 *
 * <p>🔴 卷 13 卷首加粗句「<strong>数据延迟要显示</strong>」：用户必须一眼看出看到的是 T-1 还是实时。
 */
export function dataUpdatedText(response: BoardViewResponse | null): string {
  if (!response) return '';
  if (!response.dataUpdatedAt) return '数据更新时间：尚未投影任何数据';
  return `数据截至 ${response.dataUpdatedAt}`;
}

/** 「数据截至」这句话的口径说明（这个时刻<strong>具体指什么</strong>）。 */
export function dataUpdatedNote(response: BoardViewResponse | null): string {
  return response?.dataUpdatedNote ?? '';
}

/** 工作台视图的数据更新时间文案。 */
export function workbenchDataUpdatedText(workbench: BoardWorkbenchResponse | null): string {
  if (!workbench) return '';
  if (!workbench.dataUpdatedAt) return '数据更新时间：尚未投影任何数据';
  return `数据截至 ${workbench.dataUpdatedAt}`;
}

// ================================================================ 能力声明

/** 导出 / 定时推送是否可用（**未交付就写未交付**，不提供假按钮）。 */
export function exportAvailable(capabilities: BoardCapabilities | null): boolean {
  if (!capabilities) return false;
  return !capabilities.notDelivered.some((note) => note.includes('13.6.2.1'));
}

/** 「这一版还不能做什么」的条目（页面上原样显示）。 */
export function notDeliveredNotes(capabilities: BoardCapabilities | null): string[] {
  return capabilities?.notDelivered ?? [];
}

/** 判定规则（页面上照着显示，不在代码注释里）。 */
export function ruleNotes(capabilities: BoardCapabilities | null): BoardRuleNote[] {
  return capabilities?.rules ?? [];
}

// ================================================================ 请求体构造

/** 看板保存请求（**创建者不在入参里** —— 它来自已认证会话）。 */
export function toBoardSaveRequest(draft: {
  boardCode: string;
  displayName: string;
  description: string;
  visibility: string;
  defaultFilters: BoardFilterRequest[];
  defaultWindowFrom: string;
  defaultWindowTo: string;
}): BoardSaveRequest {
  return {
    boardCode: draft.boardCode.trim(),
    displayName: draft.displayName.trim(),
    description: draft.description.trim(),
    visibility: draft.visibility,
    defaultFilters: draft.defaultFilters.map((filter) => ({
      dimensionCode: filter.dimensionCode,
      operator: filter.operator,
      valueText: filter.valueText,
      valueText2: filter.valueText2 || null,
    })),
    defaultWindowFrom: draft.defaultWindowFrom || null,
    defaultWindowTo: draft.defaultWindowTo || null,
  };
}

/** 组件保存请求。 */
export function toComponentSaveRequest(draft: {
  metricCode: string;
  dimensionCode: string;
  chartType: string;
  layout: BoardLayoutView;
  filter: BoardFilterRequest[];
  requiredPermission: string;
}): BoardComponentSaveRequest {
  return {
    metricCode: draft.metricCode,
    dimensionCode: draft.dimensionCode,
    chartType: draft.chartType,
    layout: draft.layout,
    filter: draft.filter.map((filter) => ({
      dimensionCode: filter.dimensionCode,
      operator: filter.operator,
      valueText: filter.valueText,
      valueText2: filter.valueText2 || null,
    })),
    requiredPermission: draft.requiredPermission.trim(),
    sortOrder: null,
  };
}

/** 共享保存请求（角色与权限码**二选一**）。 */
export function toShareSaveRequest(draft: {
  roleId: string;
  permissionCode: string;
  scope: string;
}): BoardShareRequest {
  return {
    roleId: draft.roleId.trim() || null,
    permissionCode: draft.permissionCode.trim() || null,
    scope: draft.scope,
  };
}

/** 模板保存请求。 */
export function toTemplateSaveRequest(draft: {
  templateCode: string;
  displayName: string;
  description: string;
  components: BoardComponentSaveRequest[];
}): BoardTemplateSaveRequest {
  return {
    templateCode: draft.templateCode.trim(),
    displayName: draft.displayName.trim(),
    description: draft.description.trim(),
    components: draft.components,
  };
}

/** 模板实例化请求。 */
export function toInstantiateRequest(draft: {
  boardCode: string;
  displayName: string;
  description: string;
  visibility: string;
}): BoardTemplateInstantiateRequest {
  return {
    boardCode: draft.boardCode.trim(),
    displayName: draft.displayName.trim(),
    description: draft.description.trim(),
    visibility: draft.visibility,
  };
}

/** 筛选条件的回显（模板 / 组件 / 看板默认值都用这一个形状）。 */
export function filterViewToRequest(filter: BoardFilterView): BoardFilterRequest {
  return {
    dimensionCode: filter.dimensionCode,
    operator: filter.operator,
    valueText: filter.valueText,
    valueText2: filter.valueText2 || null,
  };
}

/** 指标的展示名（找不到时退回指标码，**不编一个名字**）。 */
export function metricLabel(metric: BoardMetricView | undefined, metricCode: string): string {
  return metric?.displayName || metricCode;
}

/** 维度的展示名（白名单里的标签，**服务端给的**）。 */
export function dimensionLabel(dimension: BoardDimensionView | undefined, code: string): string {
  return dimension?.label || code;
}

/** 指标口径的唯一来源提示（页面上照着显示）。 */
export function metricRegistryNote(): string {
  return '指标只能从已登记的语义层指标里选（口径唯一）；维度只有白名单里的 5 个。';
}
