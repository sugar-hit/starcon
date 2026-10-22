import type {
  CrmCareReminderCapabilities,
  CrmCareReminderDispatchInput,
  CrmCareReminderGenerateInput,
  CrmCareReminderGenerateResult,
  CrmCareReminderPlatformReceiptStatus,
  CrmCareReminderRuleInput,
  CrmCareReminderRulePage,
  CrmCareReminderRuleRow,
  CrmCareReminderStatEventInput,
  CrmCareReminderStatEventPage,
  CrmCareReminderStatEventRow,
  CrmCareReminderStats,
  CrmCareReminderTaskPage,
  CrmCareReminderTaskRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 15.5.2.1 关怀与催办前端模型。
 *
 * <p>蓝图：「主动触达买家：发货通知、物流提醒、催付（未付款订单）、催评、售后进度通知、
 * 关怀邮件（节假日/生日），按规则定时发送并遵守频次上限与退订要求。」
 * 输出「触达发送记录 + 打开/点击统计 + 退订记录」。
 * 关系：上游 = 9 订单、10 物流、15.3.2.2 接触记录；下游 = 15.1.1.2 发送、12 财务（催付加速回款）。
 * <b>催付有合规边界</b>：部分平台限制催付话术与频次，规则需按平台配置，过度催付会被投诉。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>统计口径服务端算</b>：触达总数 / 成功数 / 失败数 / 打开数 / 点击数 /
 *       退订拦截数 / 频次拦截数全部由后端聚合，<b>页面不做任何算术</b>
 *       （自己算一遍打开率，两处口径迟早会打架）。</li>
 *   <li><b>拦截原因逐条显示</b>：被退订闸 / 频次闸挡下的触达，页面要把**后端给的原因原文**
 *       显示出来 —— 「已拦截」三个字没法让人知道下一步该干什么。</li>
 *   <li><b>催付平台白名单照后端</b>：白名单来自能力声明，页面据此提示；
 *       页面**不**自己维护一份白名单（两份名单会打架）。</li>
 *   <li><b>不提供「一键群发」这类假入口</b>：平台侧发送接口未接入，
 *       本模块只排期与登记，发送由人工在平台侧完成后回登。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有触达任务」。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code crm/care-reminders}，与同服务的 {@code crm/customer-contacts}、
 * {@code crm/customer-tags}、{@code crm/message-inbox}、{@code crm/buyer-emails} <b>刻意不共用</b>。
 */

const base = 'crm/care-reminders';

/** 目标人群筛选条件（后端 jsonb，结构随来源而异）。 */
export type CareReminderTargetFilter = Record<string, unknown>;

/**
 * 筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface CareReminderRuleParams {
  reminderType?: string | undefined;
  platformCode?: string | undefined;
  status?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface CareReminderTaskParams {
  ruleId?: string | undefined;
  reminderType?: string | undefined;
  status?: string | undefined;
  blockCode?: string | undefined;
  customerRef?: string | undefined;
  platformCode?: string | undefined;
  from?: string | undefined;
  to?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface CareReminderStatsParams {
  ruleId?: string | undefined;
  reminderType?: string | undefined;
  status?: string | undefined;
  from?: string | undefined;
  to?: string | undefined;
}

export interface CareReminderEventParams {
  page?: number | undefined;
  size?: number | undefined;
}

function query(params: Record<string, string | number | undefined>): string {
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
export function fetchCareReminderCapabilities() {
  return api<CrmCareReminderCapabilities>(`${base}/capabilities`);
}

// ================================================================ 触达规则

/** 触达规则列表（真分页 + 真筛选）。 */
export function fetchCareReminderRules(params: CareReminderRuleParams = {}) {
  return api<CrmCareReminderRulePage>(`${base}/rules${query({ ...params })}`);
}

/** 单条触达规则。 */
export function fetchCareReminderRule(ruleId: string) {
  return api<CrmCareReminderRuleRow>(`${base}/rules/${encodeURIComponent(ruleId)}`);
}

/**
 * 新建一条触达规则。
 *
 * <p>🔴 催付规则的平台必须在白名单里 —— 不在白名单后端返回 400「该平台不支持催付」，
 * 且**一行都不写**。页面据此提前提示，但**不**替后端做决定。
 */
export function createCareReminderRule(input: CrmCareReminderRuleInput) {
  return api<CrmCareReminderRuleRow>(`${base}/rules`, 'POST', input);
}

/** 修改一条触达规则（不回写历史任务）。 */
export function updateCareReminderRule(ruleId: string, input: CrmCareReminderRuleInput) {
  return api<CrmCareReminderRuleRow>(`${base}/rules/${encodeURIComponent(ruleId)}`, 'PUT', input);
}

/** 启用 / 停用一条触达规则。 */
export function setCareReminderRuleStatus(ruleId: string, status: string) {
  return api<CrmCareReminderRuleRow>(
    `${base}/rules/${encodeURIComponent(ruleId)}/status${query({ status })}`,
    'POST',
  );
}

// ================================================================ 触达任务

/** 触达任务列表（真分页 + 真筛选）。 */
export function fetchCareReminderTasks(params: CareReminderTaskParams = {}) {
  return api<CrmCareReminderTaskPage>(`${base}/tasks${query({ ...params })}`);
}

/** 单条触达任务。 */
export function fetchCareReminderTask(taskId: string) {
  return api<CrmCareReminderTaskRow>(`${base}/tasks/${encodeURIComponent(taskId)}`);
}

/**
 * 按规则生成触达任务。
 *
 * <p>🔴 每一行都过后端的两道闸（退订 / 频次）；被挡下的会落成一条带**明确原因**的
 * 拦截记录，页面要把原因显示出来。
 */
export function generateCareReminderTasks(
  ruleId: string,
  input: CrmCareReminderGenerateInput = {
    plannedAt: null,
    lookbackDays: null,
    minAmount: null,
    orderSource: null,
    customerRefs: null,
  },
) {
  return api<CrmCareReminderGenerateResult>(
    `${base}/rules/${encodeURIComponent(ruleId)}/generate`,
    'POST',
    input,
  );
}

/**
 * 人工交接登记发送结果（**当前唯一可用的发送路径**）。
 *
 * <p>⚠️ 平台侧发送接口未接入 ⇒ 本模块**不重造发送**；
 * 运营在平台侧实际发出后回本服务登记「已发出 / 失败原因 / 平台回执」。
 */
export function dispatchCareReminderTask(taskId: string, input: CrmCareReminderDispatchInput) {
  return api<CrmCareReminderTaskRow>(
    `${base}/tasks/${encodeURIComponent(taskId)}/dispatch`,
    'POST',
    input,
  );
}

// ================================================================ 统计

/** 统计汇总（**服务端算**：总数 / 成功 / 失败 / 打开 / 点击 / 两类拦截数）。 */
export function fetchCareReminderStats(params: CareReminderStatsParams = {}) {
  return api<CrmCareReminderStats>(`${base}/stats${query({ ...params })}`);
}

/** 某条触达任务的打开 / 点击事件。 */
export function fetchCareReminderTaskEvents(taskId: string, params: CareReminderEventParams = {}) {
  return api<CrmCareReminderStatEventPage>(
    `${base}/stats/tasks/${encodeURIComponent(taskId)}/events${query({ ...params })}`,
  );
}

/**
 * 平台打开 / 点击回执的**接入状态**。
 *
 * <p>🔴 平台未提供打开/点击回执接口 ⇒ 服务端**如实答「未接入」且不写任何数据**。
 * 这里拿到的是**服务端给的原因原文**（页面据此告诉运营「为什么统计只能人工录入」），
 * 而不是页面自己编一句「未接入」。
 */
export function fetchCareReminderPlatformReceiptStatus() {
  return api<CrmCareReminderPlatformReceiptStatus>(`${base}/stats/platform-events`);
}

/**
 * 录入一条打开 / 点击事件（**平台 webhook 或人工录入**）。
 *
 * <p>⚠️ 平台打开/点击回执**未接入** ⇒ 自动采集端点如实答「未接入」且不写数据；
 * 统计只能靠 webhook 或人工录入。
 */
export function recordCareReminderStatEvent(input: CrmCareReminderStatEventInput) {
  return api<CrmCareReminderStatEventRow>(`${base}/stats/events`, 'POST', input);
}

// ================================================================ 纯展示规则

/** 触达类型的中文名（照蓝图点名的六类）。 */
export function careReminderTypeLabel(code: string): string {
  switch (code) {
    case 'SHIPMENT_NOTICE':
      return '发货通知';
    case 'LOGISTICS_REMINDER':
      return '物流提醒';
    case 'PAYMENT_DEMAND':
      return '催付（未付款订单）';
    case 'REVIEW_REQUEST':
      return '催评';
    case 'AFTERSALES_PROGRESS':
      return '售后进度通知';
    case 'CARE_MAIL':
      return '关怀邮件（节假日 / 生日）';
    default:
      return code;
  }
}

/** 触达任务状态的中文名。 */
export function careReminderStatusLabel(code: string): string {
  switch (code) {
    case 'QUEUED':
      return '已排期待发';
    case 'SENT':
      return '已发出';
    case 'FAILED':
      return '发送失败';
    case 'BLOCKED':
      return '被拦截';
    case 'CANCELLED':
      return '已作废';
    default:
      return code;
  }
}

/**
 * 拦截码的中文名。
 *
 * <p>⚠️ 词表只有两档 —— 退订闸与频次闸。
 * 「该平台不支持催付」**不在**这里：它是**规则级**的合规判定，
 * 平台不在白名单里时那条催付规则根本建不出来。
 */
export function careReminderBlockLabel(code: string): string {
  switch (code) {
    case 'OPT_OUT':
      return '退订拦截';
    case 'FREQUENCY_LIMIT':
      return '频次超限';
    default:
      return code;
  }
}

/** 触达通道的中文名。 */
export function careReminderTransportLabel(code: string): string {
  switch (code) {
    case 'MANUAL_HANDOFF':
      return '人工交接';
    case 'PLATFORM_API':
      return '平台侧发送接口（未接入）';
    default:
      return code;
  }
}

/** 统计事件类型的中文名。 */
export function careReminderEventLabel(code: string): string {
  switch (code) {
    case 'OPEN':
      return '打开';
    case 'CLICK':
      return '点击';
    default:
      return code;
  }
}

/**
 * 拦截原因的展示文本 —— **后端给的原因原文**，页面不改写、不截断成「已拦截」。
 *
 * <p>🔴 加粗句「遵守频次上限与退订要求」要求的是「拒绝并给出明确原因」：
 * 只显示「被拦截」三个字，等于把原因藏起来了。
 */
export function careReminderBlockText(blockCode: string, blockReason: string): string {
  const reason = blockReason.trim();
  if (!reason) return careReminderBlockLabel(blockCode);
  return `${careReminderBlockLabel(blockCode)}：${reason}`;
}

/**
 * 这个平台能不能配催付规则。
 *
 * <p>🔴 白名单来自**后端的能力声明**（{@code paymentDemandAllowedPlatforms}），
 * 页面**不**自己维护一份 —— 两份名单迟早会打架，而打架的方向往往是
 * 「页面说能配，后端说不能」。
 */
export function careReminderPaymentDemandAllowed(
  platformCode: string,
  allowedPlatforms: readonly string[],
): boolean {
  return allowedPlatforms.includes(platformCode.trim().toUpperCase());
}

/** 催付平台白名单的人话提示（页面据此提示运营为什么这个平台配不了催付）。 */
export function careReminderPaymentDemandHint(allowedPlatforms: readonly string[]): string {
  return `只有 ${allowedPlatforms.join(' / ')} 允许主动催付：部分平台限制催付话术与频次，`
    + `催付规则必须按平台配置。其余平台请用平台自己的催款工具。`;
}

/**
 * 时刻**原样**显示。
 *
 * <p>⚠️ 不在前端换算成别的时区 —— 后端给的是什么口径就是什么口径，
 * 页面再转一次，两边会对不上，而且没人知道该信哪个。
 */
export function careReminderMomentText(value: string | null | undefined): string {
  if (!value) return '—';
  return value.replace('T', ' ').replace(/\..*$/, '').replace(/([+-]\d{2}:\d{2}|Z)$/, '');
}

/**
 * 统计摘要 —— **原样**展示后端算好的那串说明。
 *
 * <p>⚠️ 页面**不重算**任何比率（打开率 / 点击率 / 拦截占比）：
 * 分子分母各取一份、两个页面各算一次，口径迟早会打架。
 */
export function careReminderStatsText(note: string): string {
  return note.trim();
}
