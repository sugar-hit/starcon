import type {
  CrmServiceTicketProcessActionPage,
  CrmServiceTicketProcessCapabilities,
  CrmServiceTicketProcessLinkPage,
  CrmServiceTicketProcessNotePage,
  CrmServiceTicketProcessOutcome,
  CrmServiceTicketProcessQueuePage,
  CrmServiceTicketProcessResolutionPage,
  CrmServiceTicketProcessTicket,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 15.4.1.2 工单处理前端模型。
 *
 * <p>蓝图：「处理工单：回复、备注、状态流转、关联订单与售后单、解决确认、
 * 满意度回收、SLA 暂停/恢复。」「处理动作；关联单据；满意度评分」
 * → 「工单状态 + 处理记录 + 关联单据更新。」
 * <b>工单关闭不等于问题解决</b>，需确认实际结果（退款到账、换货发出）才计为解决。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>「已关闭」与「已确认解决」是两个字段</b>：页面**不自己推断**哪一个成立 ——
 *       关掉了但退款没到，就是「已关闭=是、已确认解决=否」，这正是加粗句要留住的可见性。</li>
 *   <li><b>SLA 剩余显示的是「营业时间剩余」</b>：后端给的是本次读取按营业时间轴算出来的
 *       剩余秒数（<b>已扣除暂停区间</b>），页面**原样显示**，不自己换算成自然时间。</li>
 *   <li><b>「暂停中」是第四种状态</b>：既不是「还在走表」，也不是「已经超期」——
 *       冻结期间 SLA 不消耗，到期时刻要等恢复之后才定得下来。</li>
 *   <li><b>关联单据查不到时显示原因</b>，不显示空白 ——
 *       空白会被客服读成「这一单没有信息」，而不是「我们没查到」。</li>
 *   <li><b>页面不做任何算术</b>：剩余量、汇总、逾期计数全部由服务端算。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有工单」。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code crm/service-ticket-process}，与同 L3 兄弟
 * {@code crm/service-tickets}（15.4.1.1 工单列表，<b>只读</b>）刻意分开 ——
 * 那边回答「有哪些工单」，这边回答「<b>这一张怎么处理</b>」。
 */

const base = 'crm/service-ticket-process';

/**
 * 处理队列的筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface ServiceTicketProcessQueueParams {
  status?: string | undefined;
  ticketType?: string | undefined;
  priority?: string | undefined;
  assignedToRef?: string | undefined;
  slaState?: string | undefined;
  overdueOnly?: boolean | undefined;
  pausedOnly?: boolean | undefined;
  resolutionState?: string | undefined;
  keyword?: string | undefined;
  sort?: string | undefined;
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
export function fetchServiceTicketProcessCapabilities() {
  return api<CrmServiceTicketProcessCapabilities>(`${base}/capabilities`);
}

// ================================================================ 处理队列

/** 处理队列（真分页 + 真筛选；服务端真算 SLA 与汇总）。 */
export function fetchServiceTicketProcessQueue(params: ServiceTicketProcessQueueParams = {}) {
  return api<CrmServiceTicketProcessQueuePage>(`${base}/tickets${query({ ...params })}`);
}

// ================================================================ 单张工单

/** 单张工单的处理视图（SLA + 暂停 + 解决确认 + 当前可执行动作）。 */
export function fetchServiceTicketProcessTicket(ticketId: string) {
  return api<CrmServiceTicketProcessTicket>(`${base}/tickets/${encodeURIComponent(ticketId)}`);
}

/** 备注 / 回复列表（真分页）。 */
export function fetchServiceTicketProcessNotes(ticketId: string, page = 0, size = 20) {
  return api<CrmServiceTicketProcessNotePage>(
    `${base}/tickets/${encodeURIComponent(ticketId)}/notes${query({ page, size })}`,
  );
}

/** 处理记录（动作时间线，真分页）。 */
export function fetchServiceTicketProcessActions(ticketId: string, page = 0, size = 20) {
  return api<CrmServiceTicketProcessActionPage>(
    `${base}/tickets/${encodeURIComponent(ticketId)}/actions${query({ page, size })}`,
  );
}

/** 关联单据列表（真分页；当前事实由服务端从上游现取）。 */
export function fetchServiceTicketProcessLinks(ticketId: string, page = 0, size = 20) {
  return api<CrmServiceTicketProcessLinkPage>(
    `${base}/tickets/${encodeURIComponent(ticketId)}/links${query({ page, size })}`,
  );
}

/** 解决确认列表（真分页）。 */
export function fetchServiceTicketProcessResolutions(ticketId: string, page = 0, size = 20) {
  return api<CrmServiceTicketProcessResolutionPage>(
    `${base}/tickets/${encodeURIComponent(ticketId)}/resolutions${query({ page, size })}`,
  );
}

// ================================================================ 处理动作

/** 回复（对买家）。首次回复会同时登记到上游（回复时限的完成时刻）。 */
export function sendServiceTicketProcessReply(
  ticketId: string,
  input: { content: string; replyChannel: string; expectedVersion?: number | null },
) {
  return api<CrmServiceTicketProcessOutcome>(
    `${base}/tickets/${encodeURIComponent(ticketId)}/replies`,
    'POST',
    input,
  );
}

/** 内部备注（只追加）。 */
export function addServiceTicketProcessNote(
  ticketId: string,
  input: { content: string; expectedVersion?: number | null },
) {
  return api<CrmServiceTicketProcessOutcome>(
    `${base}/tickets/${encodeURIComponent(ticketId)}/notes`,
    'POST',
    input,
  );
}

/** 状态流转（待处理 → 处理中 ⇄ 待买家）。 */
export function changeServiceTicketProcessStatus(
  ticketId: string,
  input: { targetStatus: string; note?: string | null; expectedVersion?: number | null },
) {
  return api<CrmServiceTicketProcessOutcome>(
    `${base}/tickets/${encodeURIComponent(ticketId)}/status`,
    'POST',
    input,
  );
}

/**
 * 🔴 关闭工单（落终态）。
 *
 * <p>⚠️ **关闭不等于解决**：这里只让工单不再跟了；
 * 「实际结果到了没有」由**解决确认**回答（另一个动作、另一张表）。
 */
export function closeServiceTicketProcessTicket(
  ticketId: string,
  input: { closeReason: string; closeNote?: string | null; expectedVersion?: number | null },
) {
  return api<CrmServiceTicketProcessOutcome>(
    `${base}/tickets/${encodeURIComponent(ticketId)}/close`,
    'POST',
    input,
  );
}

/** 🔴 解决确认（实际结果：退款到账 / 换货发出 / …）。要求工单已关闭。 */
export function confirmServiceTicketProcessResolution(
  ticketId: string,
  input: { resolutionType: string; resultNote?: string | null; expectedVersion?: number | null },
) {
  return api<CrmServiceTicketProcessOutcome>(
    `${base}/tickets/${encodeURIComponent(ticketId)}/resolutions`,
    'POST',
    input,
  );
}

/** 撤销解决确认（写错了要能改 —— 假的「退款已到账」比没有更糟）。 */
export function revokeServiceTicketProcessResolution(ticketId: string, resolutionId: string) {
  return api<CrmServiceTicketProcessOutcome>(
    `${base}/tickets/${encodeURIComponent(ticketId)}/resolutions/${encodeURIComponent(
      resolutionId,
    )}/revoke`,
    'POST',
  );
}

/** 满意度回收（1~5 分）。只转发上游，本服务不另存一份。 */
export function sendServiceTicketProcessSatisfaction(
  ticketId: string,
  input: { score: number; comment?: string | null },
) {
  return api<CrmServiceTicketProcessOutcome>(
    `${base}/tickets/${encodeURIComponent(ticketId)}/satisfaction`,
    'POST',
    input,
  );
}

/** 🔴 SLA 暂停（沿营业时间轴）。 */
export function pauseServiceTicketProcessSla(
  ticketId: string,
  input: { slaTarget: string; pauseReason: string },
) {
  return api<CrmServiceTicketProcessOutcome>(
    `${base}/tickets/${encodeURIComponent(ticketId)}/sla/pause`,
    'POST',
    input,
  );
}

/** 🔴 SLA 恢复（沿营业时间轴）。 */
export function resumeServiceTicketProcessSla(ticketId: string, input: { resumeReason: string }) {
  return api<CrmServiceTicketProcessOutcome>(
    `${base}/tickets/${encodeURIComponent(ticketId)}/sla/resume`,
    'POST',
    input,
  );
}

/** 关联订单或售后单（服务端会去上游核实，查不到就拒绝关联）。 */
export function linkServiceTicketProcessDocument(
  ticketId: string,
  input: { linkType: string; orderNo?: string | null; caseNo?: string | null; note?: string | null },
) {
  return api<CrmServiceTicketProcessOutcome>(
    `${base}/tickets/${encodeURIComponent(ticketId)}/links`,
    'POST',
    input,
  );
}

/** 升级（转投诉 / 转纠纷 ⇒ 下游 11 售后）。 */
export function escalateServiceTicketProcessTicket(
  ticketId: string,
  input: Record<string, unknown>,
) {
  return api<CrmServiceTicketProcessOutcome>(
    `${base}/tickets/${encodeURIComponent(ticketId)}/escalate`,
    'POST',
    input,
  );
}

// ================================================================ 展示口径

/** SLA 状态标签（营业时间口径，暂停感知）。 */
export function serviceTicketProcessSlaStateLabel(state: string): string {
  switch (state) {
    case 'NO_DEADLINE':
      return '无时限';
    case 'NO_CALENDAR':
      return '未配营业时间';
    case 'PENDING':
      return '计时中';
    case 'PAUSED':
      return '暂停中';
    case 'MET':
      return '已达标';
    case 'BREACHED':
      return '已超期';
    default:
      return state;
  }
}

/** SLA 状态的语义色（页面据此上色，不自己判断含义）。 */
export function serviceTicketProcessSlaTone(
  state: string,
): 'neutral' | 'success' | 'warning' | 'danger' | 'info' {
  switch (state) {
    case 'MET':
      return 'success';
    case 'PENDING':
      return 'warning';
    case 'PAUSED':
      return 'info';
    case 'BREACHED':
      return 'danger';
    default:
      return 'neutral';
  }
}

/**
 * 剩余时间文案（**营业时间**口径）。
 *
 * <p>⚠️ 负数 = 已经超了多少（营业时间）；页面**不做任何换算**，
 * 只把后端给的秒数变成人话 —— 后端给的是「还剩 3 小时 20 分（营业时间）」，
 * 页面再乘一遍自然小时数的话，加粗句要避免的误判就又回来了。
 */
export function serviceTicketProcessRemainingText(seconds: number): string {
  if (!Number.isFinite(seconds)) return '—';
  const overdue = seconds < 0;
  const total = Math.abs(Math.floor(seconds));
  const days = Math.floor(total / 86400);
  const hours = Math.floor((total % 86400) / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const parts: string[] = [];
  if (days > 0) parts.push(`${days} 天`);
  if (hours > 0) parts.push(`${hours} 小时`);
  if (minutes > 0 && days === 0) parts.push(`${minutes} 分钟`);
  if (parts.length === 0) parts.push('不足 1 分钟');
  return overdue ? `已超期 ${parts.join(' ')}（营业时间）` : `还剩 ${parts.join(' ')}（营业时间）`;
}

/** 工单类型标签。 */
export function serviceTicketProcessTypeLabel(code: string, label?: string): string {
  if (label) return label;
  switch (code) {
    case 'INQUIRY':
      return '咨询';
    case 'COMPLAINT':
      return '投诉';
    case 'ORDER_CHASE':
      return '催单';
    case 'POLICY_CONSULT':
      return '平台政策咨询';
    default:
      return code;
  }
}

/** 工单状态标签。 */
export function serviceTicketProcessStatusLabel(code: string, label?: string): string {
  if (label) return label;
  switch (code) {
    case 'OPEN':
      return '待处理';
    case 'IN_PROGRESS':
      return '处理中';
    case 'WAITING_BUYER':
      return '待买家';
    case 'RESOLVED':
      return '已关闭';
    case 'CANCELLED':
      return '已取消';
    default:
      return code;
  }
}

/** 优先级标签。 */
export function serviceTicketProcessPriorityLabel(code: string, label?: string): string {
  if (label) return label;
  switch (code) {
    case 'LOW':
      return '低';
    case 'NORMAL':
      return '普通';
    case 'HIGH':
      return '高';
    case 'URGENT':
      return '紧急';
    default:
      return code;
  }
}

/** 优先级语义色。 */
export function serviceTicketProcessPriorityTone(
  code: string,
): 'neutral' | 'info' | 'warning' | 'danger' {
  switch (code) {
    case 'URGENT':
      return 'danger';
    case 'HIGH':
      return 'warning';
    case 'NORMAL':
      return 'info';
    default:
      return 'neutral';
  }
}

/** 解决结果类型标签。 */
export function serviceTicketProcessResolutionLabel(code: string, label?: string): string {
  if (label) return label;
  switch (code) {
    case 'REFUND_RECEIVED':
      return '退款已到账';
    case 'REPLACEMENT_SHIPPED':
      return '换货已发出';
    case 'RETURN_RECEIVED':
      return '退货已签收';
    case 'ORDER_CANCELLED':
      return '订单已取消';
    case 'GUIDANCE_ACCEPTED':
      return '买家接受方案';
    case 'OTHER':
      return '其它结果';
    default:
      return code;
  }
}

/** 回复渠道标签。 */
export function serviceTicketProcessChannelLabel(code: string, label?: string): string {
  if (label) return label;
  switch (code) {
    case 'PLATFORM_MESSAGE':
      return '平台站内信';
    case 'EMAIL':
      return '邮件';
    case 'CHAT':
      return '在线会话';
    case 'PHONE':
      return '电话';
    case 'OTHER':
      return '其它渠道';
    default:
      return code;
  }
}

/** 关闭原因标签。 */
export function serviceTicketProcessCloseReasonLabel(code: string): string {
  switch (code) {
    case 'RESOLVED':
      return '已解决';
    case 'NO_RESPONSE':
      return '买家未回应';
    case 'DUPLICATE':
      return '重复工单';
    case 'CANCELLED':
      return '与本单无关';
    case 'ESCALATED':
      return '已升级转单';
    case 'ABANDONED':
      return '不再跟进';
    default:
      return code;
  }
}

/** SLA 暂停目标标签。 */
export function serviceTicketProcessSlaTargetLabel(code: string): string {
  switch (code) {
    case 'REPLY':
      return '回复时限';
    case 'RESOLVE':
      return '处理时限';
    case 'BOTH':
      return '回复与处理时限';
    default:
      return code;
  }
}

/** 关联类型标签。 */
export function serviceTicketProcessLinkTypeLabel(code: string, label?: string): string {
  if (label) return label;
  return code === 'ORDER' ? '关联订单' : '关联售后单';
}

/** 动作类型标签。 */
export function serviceTicketProcessActionLabel(code: string, label?: string): string {
  if (label) return label;
  switch (code) {
    case 'REPLY':
      return '回复';
    case 'NOTE':
      return '内部备注';
    case 'STATUS_CHANGE':
      return '状态流转';
    case 'CLOSE':
      return '关闭工单';
    case 'RESOLUTION_CONFIRM':
      return '解决确认';
    case 'RESOLUTION_REVOKE':
      return '撤销解决确认';
    case 'SATISFACTION':
      return '满意度回收';
    case 'SLA_PAUSE':
      return 'SLA 暂停';
    case 'SLA_RESUME':
      return 'SLA 恢复';
    case 'LINK_ORDER':
      return '关联订单';
    case 'LINK_CASE':
      return '关联售后单';
    case 'ESCALATE':
      return '升级';
    default:
      return code;
  }
}

/** 处理动作在页面上叫什么（按钮文案）。 */
export function serviceTicketProcessActionTitle(code: string): string {
  switch (code) {
    case 'REPLY':
      return '回复';
    case 'NOTE':
      return '内部备注';
    case 'STATUS_CHANGE':
      return '状态流转';
    case 'CLOSE':
      return '关闭工单';
    case 'RESOLUTION_CONFIRM':
      return '解决确认';
    case 'SATISFACTION':
      return '满意度回收';
    case 'SLA_PAUSE':
      return '暂停 SLA';
    case 'SLA_RESUME':
      return '恢复 SLA';
    case 'LINK_ORDER':
      return '关联订单';
    case 'LINK_CASE':
      return '关联售后单';
    case 'ESCALATE':
      return '升级';
    default:
      return code;
  }
}
