import type {
  CrmAutoReplyCapabilities,
  CrmAutoReplyDutyCalendarInput,
  CrmAutoReplyDutyCalendarPage,
  CrmAutoReplyDutyCalendarRow,
  CrmAutoReplyEscalateInput,
  CrmAutoReplyEscalationPage,
  CrmAutoReplyEscalationRow,
  CrmAutoReplyRecordPage,
  CrmAutoReplyRecordRow,
  CrmAutoReplyRuleInput,
  CrmAutoReplyRulePage,
  CrmAutoReplyRuleRow,
  CrmAutoReplySettingRow,
  CrmAutoReplySettingToggleInput,
  CrmAutoReplyStats,
  CrmAutoReplyTakeoverInput,
  CrmAutoReplyTakeoverPage,
  CrmAutoReplyTakeoverRow,
  CrmAutoReplyTriggerInput,
  CrmAutoReplyTriggerResult,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 15.5.1.1 自动回复前端模型。
 *
 * <p>蓝图：「配置消息自动回复：按触发条件（收到消息分类、关键词、无人工介入超时）自动发送回复，
 * 支持节假日无人值守、分级自动回复（先自动引导再转人工）。」
 * 输出「自动回复记录 + 转人工标记」。
 * 关系：上游 = 15.1.1.3、15.4.2；下游 = 15.1.1.2 发送。
 * <b>自动回复必须可一键关闭并留出人工接管</b>，很多平台对纯自动回复有明确限制。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>🔴 一键关闭要真的显眼</b>：开关状态、关闭原因、待发撤销条数都在页面最上面，
 *       关闭动作必须带原因（后端强制，页面也不允许空提交）。</li>
 *   <li><b>🔴 人工接管的出口必须留得住</b>：转人工标记与人工接管是**两件**输出 ——
 *       「该找人了」与「人已经接手了」不是同一件事，页面分开展示。</li>
 *   <li><b>被闸门挡下的自动回复逐条显示后端给的原因原文</b>：只写「被挡下」三个字，
 *       等于把原因藏起来了。</li>
 *   <li><b>统计口径服务端算</b>：总数 / 发出 / 被挡下 / 撤销 / 转人工 / 接管全部来自服务端，
 *       <b>页面不做任何算术</b>。</li>
 *   <li><b>不编造平台限制</b>：平台对纯自动回复的限制本仓拿不到 ⇒ 页面照后端的
 *       {@code available=false} 灰掉，而不是自己维护一份白名单。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有记录」。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code crm/auto-replies}，与同服务的 {@code crm/message-sends}、
 * {@code crm/message-classifications}、{@code crm/care-reminders} <b>刻意不共用</b>。
 */

const base = 'crm/auto-replies';

/**
 * 筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface AutoReplyRuleParams {
  triggerType?: string | undefined;
  platformCode?: string | undefined;
  enabled?: boolean | undefined;
  ladderCode?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface AutoReplyRecordParams {
  outcome?: string | undefined;
  triggerType?: string | undefined;
  ruleId?: string | undefined;
  messageId?: string | undefined;
  ladderCode?: string | undefined;
  escalated?: boolean | undefined;
  keyword?: string | undefined;
  from?: string | undefined;
  to?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface AutoReplyEscalationParams {
  reasonCode?: string | undefined;
  shopId?: string | undefined;
  takenOver?: boolean | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface AutoReplyTakeoverParams {
  shopId?: string | undefined;
  buyerReference?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface AutoReplyCalendarParams {
  scope?: string | undefined;
  platformCode?: string | undefined;
  from?: string | undefined;
  to?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

function query(params: Record<string, string | number | boolean | undefined>): string {
  const parts: string[] = [];
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined) continue;
    if (typeof value === 'string' && value === '') continue;
    parts.push(`${key}=${encodeURIComponent(String(value))}`);
  }
  return parts.length ? `?${parts.join('&')}` : '';
}

// ================================================================ 能力声明

/** 能力声明：每一维说清「有没有、依据是什么、缺了会怎样」。 */
export function fetchAutoReplyCapabilities() {
  return api<CrmAutoReplyCapabilities>(`${base}/capabilities`);
}

// ================================================================ 全局开关

/** 全局开关现状（🔴 一键关闭的入口）。 */
export function fetchAutoReplySetting() {
  return api<CrmAutoReplySettingRow>(`${base}/settings`);
}

/**
 * 🔴 一键开关。
 *
 * <p>关闭时**必须**带原因 —— 后端会拒绝空原因（没有原因的一键关闭，
 * 事后没人知道当时为什么关、该什么时候开回来）。
 */
export function toggleAutoReplySetting(input: CrmAutoReplySettingToggleInput) {
  return api<CrmAutoReplySettingRow>(`${base}/settings/toggle`, 'POST', input);
}

// ================================================================ 规则

/** 规则列表（真分页 + 真筛选）。 */
export function fetchAutoReplyRules(params: AutoReplyRuleParams = {}) {
  return api<CrmAutoReplyRulePage>(`${base}/rules${query({ ...params })}`);
}

/** 单条规则。 */
export function fetchAutoReplyRule(ruleId: string) {
  return api<CrmAutoReplyRuleRow>(`${base}/rules/${encodeURIComponent(ruleId)}`);
}

/**
 * 新建一条规则（= 阶梯的一级）。
 *
 * <p>🔴 触发条件三选一，且各自的必填项不能为空 —— 后端会拒绝并说清缺什么，
 * 页面**不**替后端做决定。
 */
export function createAutoReplyRule(input: CrmAutoReplyRuleInput) {
  return api<CrmAutoReplyRuleRow>(`${base}/rules`, 'POST', input);
}

/** 改规则（不回写历史记录 —— 记录只追加，且快照了当时的话术）。 */
export function updateAutoReplyRule(ruleId: string, input: CrmAutoReplyRuleInput) {
  return api<CrmAutoReplyRuleRow>(`${base}/rules/${encodeURIComponent(ruleId)}`, 'PUT', input);
}

// ================================================================ 值班日历

/** 值班日历列表（真分页 + 真筛选）。 */
export function fetchAutoReplyCalendar(params: AutoReplyCalendarParams = {}) {
  return api<CrmAutoReplyDutyCalendarPage>(`${base}/calendar${query({ ...params })}`);
}

/** 新建一条值班日历（节假日 / 非营业时段安排）。 */
export function createAutoReplyCalendar(input: CrmAutoReplyDutyCalendarInput) {
  return api<CrmAutoReplyDutyCalendarRow>(`${base}/calendar`, 'POST', input);
}

/** 删除一条值班日历（它是配置不是事实，可以删）。 */
export function deleteAutoReplyCalendar(entryId: string) {
  return api<void>(`${base}/calendar/${encodeURIComponent(entryId)}`, 'DELETE');
}

// ================================================================ 触发

/**
 * 触发一次自动回复评估。
 *
 * <p>⚠️ 平台站内信同步未接入 ⇒ 本入口由调用方**显式**调用。
 */
export function triggerAutoReply(input: CrmAutoReplyTriggerInput) {
  return api<CrmAutoReplyTriggerResult>(`${base}/trigger`, 'POST', input);
}

// ================================================================ 自动回复记录

/** 自动回复记录列表（真分页 + 真筛选）。 */
export function fetchAutoReplyRecords(params: AutoReplyRecordParams = {}) {
  return api<CrmAutoReplyRecordPage>(`${base}/records${query({ ...params })}`);
}

/** 单条自动回复记录。 */
export function fetchAutoReplyRecord(recordId: string) {
  return api<CrmAutoReplyRecordRow>(`${base}/records/${encodeURIComponent(recordId)}`);
}

// ================================================================ 转人工标记

/** 转人工标记列表（真分页 + 真筛选）。 */
export function fetchAutoReplyEscalations(params: AutoReplyEscalationParams = {}) {
  return api<CrmAutoReplyEscalationPage>(`${base}/escalations${query({ ...params })}`);
}

/**
 * 人工手动标记转人工。
 *
 * <p>🔴 原因**必填**：没有理由的转人工没法复核；一条消息只转人工一次。
 */
export function escalateAutoReplyRecord(recordId: string, input: CrmAutoReplyEscalateInput) {
  return api<CrmAutoReplyEscalationRow>(
    `${base}/records/${encodeURIComponent(recordId)}/escalate`,
    'POST',
    input,
  );
}

// ================================================================ 人工接管

/** 人工接管标记列表（真分页 + 真筛选）。 */
export function fetchAutoReplyTakeovers(params: AutoReplyTakeoverParams = {}) {
  return api<CrmAutoReplyTakeoverPage>(`${base}/takeovers${query({ ...params })}`);
}

/**
 * 🔴 标记人工接管。
 *
 * <p>接管后**同一会话不再自动回复**；原因**必填**；同一个会话重复接管返回既有行。
 */
export function createAutoReplyTakeover(input: CrmAutoReplyTakeoverInput) {
  return api<CrmAutoReplyTakeoverRow>(`${base}/takeovers`, 'POST', input);
}

// ================================================================ 统计

/** 统计汇总（**服务端算**：页面不重算、不做算术）。 */
export function fetchAutoReplyStats(
  params: { from?: string | undefined; to?: string | undefined } = {},
) {
  return api<CrmAutoReplyStats>(`${base}/stats${query({ ...params })}`);
}

// ================================================================ 纯展示规则

/** 触发条件的中文名（照蓝图点名的三种）。 */
export function autoReplyTriggerTypeLabel(code: string): string {
  switch (code) {
    case 'CLASSIFICATION':
      return '收到消息分类命中';
    case 'KEYWORD':
      return '关键词命中';
    case 'HUMAN_TIMEOUT':
      return '无人工介入超时';
    default:
      return code;
  }
}

/** 记录结果的中文名。 */
export function autoReplyOutcomeLabel(code: string): string {
  switch (code) {
    case 'SEND_REGISTERED':
      return '已登记发送';
    case 'SUPPRESSED':
      return '被闸门挡下（未发送）';
    case 'CANCELLED':
      return '已撤销（不得再发）';
    default:
      return code;
  }
}

/** 转人工原因的中文名。 */
export function autoReplyEscalationReasonLabel(code: string): string {
  switch (code) {
    case 'LADDER_EXHAUSTED':
      return '阶梯走完';
    case 'DUTY_GATE':
      return '值班闸挡下';
    case 'SEND_FAILED':
      return '发送登记失败';
    case 'MANUAL_ESCALATION':
      return '人工手动标记';
    default:
      return code;
  }
}

/** 值班安排的中文名。 */
export function autoReplyDutyModeLabel(code: string): string {
  switch (code) {
    case 'UNATTENDED':
      return '无人值守';
    case 'ATTENDED':
      return '有人值班';
    case 'CLOSED':
      return '休息';
    default:
      return code;
  }
}

/** 值班日历范围的中文名。 */
export function autoReplyDutyScopeLabel(code: string): string {
  switch (code) {
    case 'GLOBAL':
      return '整租户';
    case 'PLATFORM':
      return '按平台';
    case 'RULE':
      return '按规则';
    default:
      return code;
  }
}

/** 分类标签的中文名（与上游 15.1.1.3 同一份词表）。 */
export function autoReplyClassificationLabel(code: string): string {
  switch (code) {
    case 'PRE_SALE':
      return '售前咨询';
    case 'LOGISTICS':
      return '物流查询';
    case 'RETURN_REFUND':
      return '退货退款';
    case 'COMPLAINT':
      return '投诉';
    case 'PLATFORM_COMPLIANCE':
      return '平台合规';
    case 'SPAM':
      return '垃圾信息';
    case 'UNCLASSIFIED':
      return '未分类（不可自动回复）';
    default:
      return code;
  }
}

/**
 * 被闸门挡下时的展示文本 —— **后端给的原因原文**，页面不改写、不截断成「已挡下」。
 *
 * <p>🔴 加粗句要求的是「拒绝并给出明确原因」：只显示「被挡下」三个字，
 * 等于把原因藏起来了。
 */
export function autoReplySuppressedText(reason: string): string {
  const text = reason.trim();
  if (!text) return '被闸门挡下（后端没给原因 —— 这是异常，请联系管理员）。';
  return text;
}

/**
 * 全局开关的展示文本。
 *
 * <p>🔴 关闭时的提示必须说清「关闭是即时生效的、待发会被撤销」——
 * 运营点完「一键关闭」之后要知道正在排队的那几条也不会再发出去。
 */
export function autoReplyKillSwitchText(setting: {
  autoReplyEnabled: boolean;
  closedReason: string;
  pendingCancelledCount: number;
}): string {
  if (setting.autoReplyEnabled) {
    return '自动回复**开着**。关闭后新触发不再自动发，已排队待发的会逐条撤销（不得再发）。';
  }
  const reason = setting.closedReason.trim();
  return (
    '自动回复**已关闭**' +
    (reason ? `（关闭原因：${reason}）` : '（没有写关闭原因 —— 这不正常）') +
    '。关闭期间不会评估、不会发送任何回复。'
  );
}

/**
 * 阶梯进度的展示文本。
 *
 * <p>⚠️ 页面**不自己算**「还差多久到下一级」—— 后端给的口径才是口径。
 * 这里只把后端给的「第几级 / 是否转人工」原样讲出来。
 */
export function autoReplyLadderText(level: number, escalateToHuman: boolean): string {
  if (level <= 1) {
    return escalateToHuman
      ? '第 1 级（自动引导，发完即转人工）'
      : '第 1 级（自动引导，还会有下一级）';
  }
  return escalateToHuman
    ? `第 ${level} 级（阶梯终点，发完即转人工）`
    : `第 ${level} 级（阶梯中间级）`;
}

/**
 * 时刻**原样**显示。
 *
 * <p>⚠️ 不在前端换算成别的时区 —— 后端给的是什么口径就是什么口径，
 * 页面再转一次，两边会对不上，而且没人知道该信哪个。
 */
export function autoReplyMomentText(value: string | null | undefined): string {
  if (!value) return '—';
  return value
    .replace('T', ' ')
    .replace(/\..*$/, '')
    .replace(/([+-]\d{2}:\d{2}|Z)$/, '');
}

/**
 * 统计摘要 —— **原样**展示后端算好的那串说明。
 *
 * <p>⚠️ 页面**不重算**任何比率（发出率 / 被挡下占比）：
 * 分子分母各取一份、两个页面各算一次，口径迟早会打架。
 */
export function autoReplyStatsText(note: string): string {
  return note.trim();
}
