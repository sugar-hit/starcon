import { api } from '../../lib/api';
import type { UserPreferenceView } from '../../../../../packages/api-contracts/src/index';
import {
  DEFAULT_WORKBENCH_TAB,
  WORKBENCH_CARDS,
  WORKBENCH_TABS,
  type WorkbenchCard,
  type WorkbenchTabId,
} from './workbench-card-registry';

/**
 * 1.4.1.1 工作台布局 —— 前端模型（纯函数 + 接口调用）。
 *
 * <p>蓝图：「用户拖拽调整指标卡顺序、隐藏不需要的卡片、选择默认落地 Tab。
 * 输出：个性化后的工作台布局；可重置为默认布局。」
 *
 * <p>本文件钉住四件做错会**静默**的事：
 *
 * <ol>
 *   <li><b>缺键套默认值，而不是报错。</b>用户第一次用、或者存的是老版本布局，
 *       载荷里都可能缺 {@code order} / {@code hidden} / {@code defaultTab}。
 *       缺哪个键就套哪个键的默认值，页面照常渲染。</li>
 *   <li><b>存回来的顺序必须落在「现在认识的卡片」上。</b>系统下架一张卡片之后，
 *       老布局里那一张不能继续占位（否则卡片区永远空着一个坑）。</li>
 *   <li><b>系统新加的卡片默认可见。</b>老用户的 {@code hidden} 里没有它 ⇒ 它就是显示的。
 *       只有「用户明确藏过的那几张」才继续藏着。</li>
 *   <li><b>落库的 {@code order} 永远是一份完整排列。</b>这样「谁在第几位」这件事
 *       只有一份真相，读的时候不需要再猜。</li>
 * </ol>
 *
 * <p><b>载荷是纯个人偏好</b>，落在 {@code user_preference} 的 {@code dashboard.layout}
 * 这一行上（1.4.1.2 建的那张表）：租户与成员身份只从已认证会话解析，
 * 本文件只管「存什么、怎么解释」。
 */

/** 工作台布局在个人偏好表里的页面 key。 */
export const WORKBENCH_LAYOUT_PAGE_KEY = 'dashboard.layout';

/**
 * 一份可渲染的工作台布局。
 *
 * @param order 卡片 key 的**完整排列**（含被隐藏的卡片 —— 藏起来不等于丢掉位置，
 *              取消隐藏时它回到原来那一格）
 * @param hidden 被用户藏起来的卡片 key
 * @param defaultTab 默认落地的分区
 */
export interface WorkbenchLayoutState {
  order: string[];
  hidden: string[];
  defaultTab: WorkbenchTabId;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isString(value: unknown): value is string {
  return typeof value === 'string';
}

/** 卡片 key 是不是注册表里认识的。 */
function isKnownCard(key: string): boolean {
  return WORKBENCH_CARDS.some((card) => card.key === key);
}

/** 分区 id 是不是注册表里认识的。 */
function isKnownTab(id: string): id is WorkbenchTabId {
  return WORKBENCH_TABS.some((tab) => tab.id === id);
}

/**
 * 把一份（可能不完整的）卡片顺序补成**完整排列**。
 *
 * <p>三步：
 *
 * <ol>
 *   <li>只保留注册表认识的 key（第一个出现的位置算数，后面的重复丢掉）；</li>
 *   <li>注册表里**没被提到**的卡片（系统新加的、或这份载荷本来就没写全的）
 *       按注册表顺序**接在后面**；</li>
 *   <li>结果永远与注册表等长。</li>
 * </ol>
 *
 * <p><b>为什么新卡片接在后面，而不是插到「它的同类旁边」</b>：用户可能把某张卡
 * （比如「库存风险」）拖到了第一位。若新卡片按「注册表里排在它前面、且已就位的那张」插入，
 * 它就会紧跟在那张卡后面落进**第一位** —— 一个刚上线的卡片突然出现在用户精心排好的最前面，
 * 比排在最后更让人困惑。接在后面这条规则**可预测**：存过的顺序原样保留，新卡片排在末尾。
 * （从没调过布局的人根本没有存过的顺序 ⇒ 全部按注册表顺序铺开，新卡片落在它该在的位置。）
 */
export function normalizeWorkbenchOrder(candidate: readonly string[]): string[] {
  const seen = new Set<string>();
  const known: string[] = [];
  for (const key of candidate) {
    if (!isString(key) || !isKnownCard(key) || seen.has(key)) continue;
    seen.add(key);
    known.push(key);
  }
  const result = [...known];
  for (const card of WORKBENCH_CARDS) {
    if (result.includes(card.key)) continue;
    result.push(card.key);
  }
  return result;
}

/** 系统默认布局：全部可见、按注册表顺序、落在默认分区。 */
export function defaultWorkbenchLayout(): WorkbenchLayoutState {
  return {
    order: WORKBENCH_CARDS.map((card) => card.key),
    hidden: WORKBENCH_CARDS.filter((card) => !card.defaultVisible).map((card) => card.key),
    defaultTab: DEFAULT_WORKBENCH_TAB,
  };
}

/**
 * 把存下来的载荷解释成一份可渲染的布局。
 *
 * <p><b>缺键套默认值</b>；<b>不认识的卡片 key 直接丢掉</b>；
 * <b>不认识的分区回落到默认分区</b>。
 */
export function resolveWorkbenchLayout(stored: unknown): WorkbenchLayoutState {
  const payload = isPlainObject(stored) ? stored : {};
  const storedOrder = Array.isArray(payload.order) ? payload.order.filter(isString) : [];
  const order = normalizeWorkbenchOrder(storedOrder);

  const storedHidden = Array.isArray(payload.hidden) ? payload.hidden.filter(isString) : [];
  const hidden = storedHidden.filter((key) => isKnownCard(key) && order.includes(key));

  const storedTab = isString(payload.defaultTab) ? payload.defaultTab : '';
  const defaultTab = isKnownTab(storedTab) ? storedTab : DEFAULT_WORKBENCH_TAB;

  return { order, hidden, defaultTab };
}

/**
 * 生成要落库的载荷。
 *
 * <p>落库的 {@code order} 永远是一份完整排列，{@code hidden} 只留注册表认识的卡片。
 */
export function buildWorkbenchLayoutPayload(state: WorkbenchLayoutState): Record<string, unknown> {
  const order = normalizeWorkbenchOrder(state.order);
  return {
    order,
    hidden: state.hidden.filter((key) => isKnownCard(key) && order.includes(key)),
    defaultTab: isKnownTab(state.defaultTab) ? state.defaultTab : DEFAULT_WORKBENCH_TAB,
  };
}

/**
 * 顺序里把某张卡往上/往下挪一格。
 *
 * <p>键盘与按钮走的是这条路：拖拽对键盘用户和读屏用户不友好，
 * 所以每一张卡都同时提供「上移 / 下移」两个动作。
 */
export function moveWorkbenchCard(order: readonly string[], key: string, offset: number): string[] {
  const result = [...order];
  const from = result.indexOf(key);
  if (from < 0) return result;
  const to = Math.min(Math.max(from + offset, 0), result.length - 1);
  if (to === from) return result;
  result.splice(from, 1);
  result.splice(to, 0, key);
  return result;
}

/**
 * 拖拽排序：把 {@code from} 挪到 {@code to} 所在的位置。
 *
 * <p>语义与「把 from 拖到 to 身上」一致 —— from 落到 to 的位置，to 及其后面的卡依次后移。
 */
export function reorderWorkbenchCard(
  order: readonly string[],
  from: string,
  to: string,
): string[] {
  const result = [...order];
  const fromIndex = result.indexOf(from);
  const toIndex = result.indexOf(to);
  if (fromIndex < 0 || toIndex < 0 || fromIndex === toIndex) return result;
  result.splice(fromIndex, 1);
  result.splice(toIndex, 0, from);
  return result;
}

/** 显隐：显示 / 藏起某一张卡。 */
export function setWorkbenchCardHidden(
  hidden: readonly string[],
  key: string,
  visible: boolean,
): string[] {
  const current = hidden.filter((item) => item !== key);
  return visible ? current : [...current, key];
}

/** 布局里**显示**出来的卡片（按布局顺序）。 */
export function visibleWorkbenchCards(state: WorkbenchLayoutState): WorkbenchCard[] {
  return state.order
    .filter((key) => !state.hidden.includes(key))
    .map((key) => WORKBENCH_CARDS.find((card) => card.key === key))
    .filter((card): card is WorkbenchCard => card !== undefined);
}

/** 某个分区里显示出来的卡片（按布局顺序）。 */
export function visibleWorkbenchCardsInTab(
  state: WorkbenchLayoutState,
  tab: WorkbenchTabId,
): WorkbenchCard[] {
  return visibleWorkbenchCards(state).filter((card) => card.tab === tab);
}

/** 把载荷渲染成人能读的摘要（设置页用）。 */
export function describeWorkbenchLayout(state: WorkbenchLayoutState): string[] {
  const visible = visibleWorkbenchCards(state);
  const lines: string[] = [];
  lines.push(`默认落地分区：${WORKBENCH_TABS.find((t) => t.id === state.defaultTab)?.label ?? '概览'}`);
  lines.push(
    visible.length === 0
      ? '显示的卡片：（全部隐藏）'
      : `显示的卡片（${visible.length} 张）：${visible.map((card) => card.label).join('、')}`,
  );
  const hiddenCards = state.hidden
    .map((key) => WORKBENCH_CARDS.find((card) => card.key === key)?.label)
    .filter((label): label is string => label !== undefined);
  if (hiddenCards.length > 0) lines.push(`已隐藏：${hiddenCards.join('、')}`);
  return lines;
}

// ==========================================================================
// 接口调用（复用 1.4.1.2 的 user-preferences 端点，本模块不开新端点）
// ==========================================================================

/** 偏好读取的返回形状（与后端 `UserPreferenceView` 一致）。 */
export type { UserPreferenceView };

function path(): string {
  return `identity/user-preferences/${encodeURIComponent(WORKBENCH_LAYOUT_PAGE_KEY)}`;
}

/**
 * 读我的工作台布局。
 *
 * <p>后端在**没存过**时返回 200 + {@code stored=false}（不是 404）——
 * 「还没设过布局」是常态，不该被当成异常。
 */
export function fetchWorkbenchLayout(): Promise<UserPreferenceView> {
  return api<UserPreferenceView>(path());
}

/** 保存（upsert）我的工作台布局。 */
export function saveWorkbenchLayout(
  payload: Record<string, unknown>,
): Promise<UserPreferenceView> {
  return api<UserPreferenceView>(path(), 'PUT', { preference: payload });
}

/**
 * 重置为默认布局。
 *
 * <p><b>真的重置</b>：删掉服务端那一行 = 回到系统默认布局（不是「提示用户刷新」）。
 */
export function resetWorkbenchLayout(): Promise<unknown> {
  return api<unknown>(path(), 'DELETE');
}
