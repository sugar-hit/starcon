import type {
  CrmBusinessHoliday,
  CrmBusinessHoursConfig,
  CrmBusinessHoursInput,
  CrmBusinessHolidayInput,
  CrmBusinessHourWindow,
  CrmServiceTicketCapabilities,
  CrmServiceTicketPage,
  CrmServiceTicketRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 15.4.1.1 工单列表前端模型。
 *
 * <p>蓝图：「客服工单列表：类型、优先级、状态、SLA 剩余、关联买家与订单、分配客服、
 * 转派与升级。」「工单创建（自动/人工）；筛选条件」→「工单列表 + SLA 预警。」
 * 关系：上游 = 15.1.1.3、11.4.2.1；下游 = 15.4.1.2 处理。
 * <b>SLA 计时需按营业时间而非自然时间</b>，否则夜间积压会造成误判。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>SLA 剩余显示的是「营业时间剩余」</b>：后端给的是本次读取按营业时间轴算出来的
 *       剩余秒数，页面**原样显示**它，<b>不自己换算成自然时间</b>
 *       （页面再乘一次 24 小时，那正是加粗句要避免的误判）。
 *       自然时间口径的剩余量后端也给了（对照用），页面只在需要时说清「这是对照」。</li>
 *   <li><b>「未配营业时间」是一种状态，不是「没有时限」</b>：两种都说「没有时限」，
 *       现场会以为「这单不考核」，而真相是「我们还没拿到这家店的营业时间」。</li>
 *   <li><b>关联订单 / 买家查不到时显示原因</b>，不显示空白 ——
 *       空白会被客服读成「这一单没有信息」，而不是「我们没查到」。</li>
 *   <li><b>转派历史读上游的既有事实</b>：页面显示上游给的转派次数与最近一次转派，
 *       <b>不自己推断</b>（推出来的「转过几次」没人能复核）。</li>
 *   <li><b>下游未交付就写明</b>：15.4.1.2 工单处理未交付，页面**不提供**任何
 *       「回复 / 备注 / 转状态 / 解决」按钮，也不放一个点不动的入口。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有工单」。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code crm/service-tickets}，与同服务的 {@code crm/message-inbox}、
 * {@code crm/knowledge}、{@code crm/care-reminders} <b>刻意不共用</b>。
 */

const base = 'crm/service-tickets';

/**
 * 列表筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface ServiceTicketListParams {
  status?: string | undefined;
  ticketType?: string | undefined;
  priority?: string | undefined;
  sourceChannel?: string | undefined;
  assignedToRef?: string | undefined;
  caseId?: string | undefined;
  slaState?: string | undefined;
  overdueOnly?: boolean | undefined;
  unassignedOnly?: boolean | undefined;
  from?: string | undefined;
  to?: string | undefined;
  keyword?: string | undefined;
  sort?: string | undefined;
  shopId?: string | undefined;
  enrich?: boolean | undefined;
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
export function fetchServiceTicketCapabilities() {
  return api<CrmServiceTicketCapabilities>(`${base}/capabilities`);
}

// ================================================================ 工单列表

/**
 * 工单列表（真分页 + 真筛选）。
 *
 * <p>⚠️ {@code slaState} / {@code overdueOnly} 走的是后端的**营业时间口径**，
 * 与上游的自然时间口径不是同一个判定。
 */
export function fetchServiceTickets(params: ServiceTicketListParams = {}) {
  return api<CrmServiceTicketPage>(`${base}${query({ ...params })}`);
}

/** 单张工单（含上游的转派历史）。 */
export function fetchServiceTicket(ticketId: string) {
  return api<CrmServiceTicketRow>(`${base}/${encodeURIComponent(ticketId)}`);
}

// ================================================================ 营业时间口径

/** 读某个作用域的营业时间口径（时段 + 节假日例外）。 */
export function fetchBusinessHours(shopId?: string) {
  return api<CrmBusinessHoursConfig>(`${base}/business-hours${query({ shopId })}`);
}

/**
 * 改写某个作用域的营业时段（**整段替换**）。
 *
 * <p>⚠️ 后端会拒绝重叠区间（400）与非法时区（400），页面把原因显示出来，
 * **不自己先算一遍** —— 两处口径迟早会打架。
 */
export function updateBusinessHours(input: CrmBusinessHoursInput) {
  return api<CrmBusinessHoursConfig>(`${base}/business-hours`, 'PUT', input);
}

/** 改写某个作用域的节假日例外（**整段替换**）。 */
export function updateBusinessHolidays(input: CrmBusinessHolidayInput) {
  return api<CrmBusinessHoursConfig>(`${base}/business-hours/holidays`, 'PUT', input);
}

// ================================================================ 展示口径

/** SLA 状态标签（营业时间口径）。 */
export function serviceTicketSlaStateLabel(state: string): string {
  switch (state) {
    case 'NO_DEADLINE':
      return '无时限';
    case 'NO_CALENDAR':
      return '未配营业时间';
    case 'PENDING':
      return '计时中';
    case 'MET':
      return '已达标';
    case 'BREACHED':
      return '已超期';
    default:
      return state;
  }
}

/** SLA 状态的语义色（页面据此上色，不自己判断含义）。 */
export function serviceTicketSlaTone(state: string): 'neutral' | 'success' | 'warning' | 'danger' {
  switch (state) {
    case 'MET':
      return 'success';
    case 'PENDING':
      return 'warning';
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
export function serviceTicketRemainingText(seconds: number): string {
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
export function serviceTicketTypeLabel(code: string, label?: string): string {
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
export function serviceTicketStatusLabel(code: string, label?: string): string {
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
export function serviceTicketPriorityLabel(code: string, label?: string): string {
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
export function serviceTicketPriorityTone(code: string): 'neutral' | 'info' | 'warning' | 'danger' {
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

/** 排序键标签（白名单与后端一致）。 */
export function serviceTicketSortLabel(code: string): string {
  switch (code) {
    case 'sla_asc':
      return '按 SLA 剩余（营业时间）升序';
    case 'priority_desc':
      return '按优先级';
    case 'assigned_asc':
      return '按分配客服';
    case 'created_desc':
      return '按创建时间倒序';
    default:
      return code;
  }
}

/** 工作日标签（ISO-8601：1 = 周一 … 7 = 周日）。 */
export function businessHourWeekdayLabel(weekday: number): string {
  const names = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日'];
  return names[weekday] ?? `第 ${weekday} 天`;
}

/** 由「09:00」这类字面量构造营业时段行（编辑用）。 */
export function businessHourWindow(
  weekday: number,
  startTime: string,
  endTime: string,
  enabled = true,
): CrmBusinessHourWindow {
  return { weekday, startTime, endTime, enabled };
}

/** 由「2026-06-08」这类字面量构造节假日行（编辑用）。 */
export function businessHoliday(date: string, label = '', closed = true): CrmBusinessHoliday {
  return { date, label, closed };
}
