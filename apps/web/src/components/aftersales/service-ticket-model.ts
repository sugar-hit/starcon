/**
 * 11.4.2.1 工单管理的前端数据层。
 *
 * 蓝图 11-aftersales-and-returns.md：「管理非售后类的客户服务工单：咨询、投诉、催单、
 * 平台政策咨询；支持分配、SLA 计时、转派、关闭与满意度评价。」
 * 输出：「工单（待处理/处理中/待买家/已关闭）+ SLA 达成统计。」
 *
 * <b>这一层对三条承诺负三件事</b>：
 *
 * <ol>
 *   <li><b>倒计时由绝对时刻派生，不由接口的剩余秒数派生</b>：
 *       {@link remainingSeconds} 只认 {@code replyDueAt} 这一个事实，每次渲染现算。
 *       接口返回的 {@code secondsToReplyDue} 是**请求那一刻**的快照，
 *       页面停在那里不动就会慢慢变成假数据。</li>
 *   <li><b>「关闭」与「办完」在界面上分得开</b>：{@link closeHint} 把
 *       「已解决 / 已升级转单」和「买家未回应 / 重复工单 / 不再跟进」分开说 ——
 *       前者落 RESOLVED，后者落 CANCELLED，混成一句「已关闭」就把口径糊了。</li>
 *   <li><b>「没测过」与「全没达标」分得开</b>：{@link achievementText} 在分母为 0 时
 *       显示「暂无口径」而不是 0% —— 0% 会把「没关过单」说成「一单都没达标」。</li>
 * </ol>
 *
 * <p>⚠️ <b>SLA 判定的真身只有 DB 函数一处</b>（后端
 * {@code aftersales_ticket_sla_state}，IMMUTABLE）。前端**不重算**达标与否，
 * 只做展示与派生（剩余时间、文案、色调）—— 两处实现必然漂移，
 * 而 SLA 达标直接决定客服绩效口径。
 */
import type {
  ServiceTicketAction,
  ServiceTicketAssign,
  ServiceTicketAutoAssign,
  ServiceTicketCapabilities,
  ServiceTicketClaim,
  ServiceTicketClose,
  ServiceTicketCreate,
  ServiceTicketDetail,
  ServiceTicketEscalate,
  ServiceTicketFirstReply,
  ServiceTicketPage,
  ServiceTicketRelease,
  ServiceTicketSatisfactionCreate,
  ServiceTicketSlaStats,
  ServiceTicketStatusChange,
  ServiceTicketTransfer,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError } from '../../lib/api';

/** 与后端 {@code /trade/service-tickets} 同前缀。 */
const base = 'trade/service-tickets';

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  // ⚠️ exactOptionalPropertyTypes 开着（AGENTS.md）：不能把 `headers: undefined`
  //   直接塞进 RequestInit —— 那不是「没传」，而是「传了个 undefined」。
  //   所以这里按有没有 body 分成两种完整对象，而不是合并一个可能半空的。
  const response = init
    ? await fetch(`/api/${path}`, {
        credentials: 'include',
        ...init,
        ...(init.body ? { headers: { 'Content-Type': 'application/json' } } : {}),
      })
    : await fetch(`/api/${path}`, { credentials: 'include' });
  if (!response.ok) {
    // ApiError 的签名是 (status, message, body)。
    throw new ApiError(response.status, await response.text());
  }
  return (await response.json()) as T;
}

/**
 * 把筛选对象编成 query string。
 *
 * ⚠️ 空串与 undefined 都要跳过：传一个空 `status=` 会让后端收到
 * 「按空状态筛选」而不是「不筛状态」，两种读法在列表页上表现完全不同。
 */
function query(params: Record<string, string | number | boolean | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === '' || value === null) continue;
    search.set(key, String(value));
  }
  const suffix = search.toString();
  return suffix ? `?${suffix}` : '';
}

// ==========================================================================
// 能力声明
// ==========================================================================

export const fetchServiceTicketCapabilities = () =>
  call<ServiceTicketCapabilities>(`${base}/capabilities`);

// ==========================================================================
// 工单
// ==========================================================================

export const fetchServiceTickets = (params: {
  page?: number;
  size?: number;
  status?: string;
  ticketType?: string;
  priority?: string;
  sourceChannel?: string;
  assignedToRef?: string;
  caseId?: string;
  slaState?: string;
  overdueOnly?: boolean;
  unassignedOnly?: boolean;
  from?: string;
  to?: string;
  keyword?: string;
  sort?: string;
} = {}) => call<ServiceTicketPage>(`${base}${query(params)}`);

export const fetchServiceTicket = (id: string) => call<ServiceTicketDetail>(`${base}/${id}`);

/**
 * 创建工单。
 *
 * ⚠️ 必须给 `caseId`：工单永远挂在一张售后单上（预留表 `case_id NOT NULL`）。
 */
export const createServiceTicket = (body: ServiceTicketCreate) =>
  call<ServiceTicketDetail>(`${base}`, { method: 'POST', body: JSON.stringify(body) });

// ==========================================================================
// 分配 / 转派
// ==========================================================================

/** 人工派单：指定一个责任人（必须在名册里）。 */
export const assignServiceTicket = (id: string, body: ServiceTicketAssign) =>
  call<ServiceTicketDetail>(`${base}/${id}/assign`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

/** 按名册**加权最少在办**自动派单。 */
export const autoAssignServiceTicket = (id: string, body: ServiceTicketAutoAssign) =>
  call<ServiceTicketDetail>(`${base}/${id}/auto-assign`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

/** 转派。必须写理由。 */
export const transferServiceTicket = (id: string, body: ServiceTicketTransfer) =>
  call<ServiceTicketDetail>(`${base}/${id}/transfer`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

/** 认领：只能认领**当前无责任人**的工单。 */
export const claimServiceTicket = (id: string, body: ServiceTicketClaim) =>
  call<ServiceTicketDetail>(`${base}/${id}/claim`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

/** 释放责任人：把单放回待处理池。必须写理由。 */
export const releaseServiceTicket = (id: string, body: ServiceTicketRelease) =>
  call<ServiceTicketDetail>(`${base}/${id}/release`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

// ==========================================================================
// 处理 / 关闭 / 评价 / 升级
// ==========================================================================

/** 记录首次回复（回复时限的完成时刻，只能记一次）。 */
export const recordServiceTicketFirstReply = (id: string, body: ServiceTicketFirstReply) =>
  call<ServiceTicketDetail>(`${base}/${id}/first-reply`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

/** 状态流转（待处理 ⇄ 处理中 ⇄ 待买家）。 */
export const changeServiceTicketStatus = (id: string, body: ServiceTicketStatusChange) =>
  call<ServiceTicketDetail>(`${base}/${id}/status`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

/** 关闭工单。必须写关闭原因。 */
export const closeServiceTicket = (id: string, body: ServiceTicketClose) =>
  call<ServiceTicketDetail>(`${base}/${id}/close`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

/** 满意度评价（1~5 分）。 */
export const recordServiceTicketSatisfaction = (
  id: string,
  body: ServiceTicketSatisfactionCreate,
) =>
  call<ServiceTicketDetail>(`${base}/${id}/satisfaction`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

/** 升级：咨询 → 投诉 / 投诉 → 纠纷。 */
export const escalateServiceTicket = (id: string, body: ServiceTicketEscalate) =>
  call<ServiceTicketDetail>(`${base}/${id}/escalate`, {
    method: 'POST',
    body: JSON.stringify(body),
  });

// ==========================================================================
// SLA 统计
// ==========================================================================

/**
 * SLA 达成统计（独立报表口径，按关闭时间窗）。
 *
 * ⚠️ query 段**拼在模板串外面**：写成 `` `${base}/sla-stats${query(params)}` `` 时，
 *   模板里就带了一个 `${query(...)}` 占位 —— 白名单的「反向抽取真实调用点」
 *   会把整条路径当成「还有未解析的占位」跳过，于是这个端点**没人断言**。
 */
export const fetchServiceTicketSlaStats = (params: { from?: string; to?: string } = {}) =>
  call<ServiceTicketSlaStats>(`${base}/sla-stats` + query(params));

// ==========================================================================
// 纯函数：倒计时 / 色调 / 文案
// ==========================================================================

/**
 * 剩余秒数（由绝对截止时刻减去「现在」算出）。
 *
 * ⚠️ 已过截止时刻返回 **0 而不是负数** —— 负数在列表里会被当成怪数字划过去。
 */
export function remainingSeconds(dueAt: string | null | undefined, now = Date.now()): number {
  if (!dueAt) return 0;
  const due = new Date(dueAt).getTime();
  if (Number.isNaN(due)) return 0;
  return Math.max(0, Math.round((due - now) / 1000));
}

/**
 * 倒计时文案：按天 / 小时 / 分钟 / 秒分级。
 *
 * ⚠️ 已超期显示「已超期」而不是一个负数。
 */
export function countdownText(dueAt: string | null | undefined, now = Date.now()): string {
  if (!dueAt) return '未设时限';
  const due = new Date(dueAt).getTime();
  if (Number.isNaN(due)) return '未设时限';
  const diff = due - now;
  if (diff <= 0) return '已超期';
  const seconds = Math.floor(diff / 1000);
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const rest = seconds % 60;
  if (days > 0) return `${days} 天 ${hours} 小时`;
  if (hours > 0) return `${hours} 小时 ${minutes} 分`;
  if (minutes > 0) return `${minutes} 分 ${rest} 秒`;
  return `${rest} 秒`;
}

/** SLA 判定的色调（语义挂钩，不跟颜色挂钩）。 */
export function slaTone(state: string | null | undefined): 'danger' | 'warning' | 'success' | 'neutral' {
  switch (state) {
    case 'BREACHED':
      return 'danger';
    case 'PENDING':
      return 'warning';
    case 'MET':
      return 'success';
    default:
      return 'neutral';
  }
}

/** 优先级色调。 */
export function priorityTone(priority: string | null | undefined): 'danger' | 'warning' | 'info' | 'neutral' {
  switch (priority) {
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

/** 工单状态色调。 */
export function statusTone(status: string | null | undefined): 'success' | 'info' | 'warning' | 'neutral' {
  switch (status) {
    case 'RESOLVED':
      return 'success';
    case 'IN_PROGRESS':
      return 'info';
    case 'WAITING_BUYER':
      return 'warning';
    default:
      return 'neutral';
  }
}

/**
 * 关闭口径的提示。
 *
 * ⚠️ 「关闭」与「办完」是两件事：已解决 / 已升级转单算办完；
 * 买家未回应 / 重复工单 / 与本单无关 / 不再跟进**不算**办完（落 CANCELLED）。
 */
export function closeHint(closeReason: string | null | undefined): string {
  switch (closeReason) {
    case 'RESOLVED':
      return '已解决（算办完）';
    case 'ESCALATED':
      return '已升级转单（算办完）';
    case 'NO_RESPONSE':
      return '买家未回应（已关闭，但**不算**办完）';
    case 'DUPLICATE':
      return '重复工单（已关闭，但**不算**办完）';
    case 'CANCELLED':
      return '与本单无关（已关闭，但**不算**办完）';
    case 'ABANDONED':
      return '不再跟进（已关闭，但**不算**办完）';
    default:
      return '';
  }
}

/**
 * 达成率的文案。
 *
 * ⚠️ 分母为 0 ⇒ 「暂无口径」，**不是** 0% ——
 * 「没关过单所以没口径」与「关了单一单都没达标」是两件事。
 */
export function achievementText(rate: number | null | undefined): string {
  if (rate === null || rate === undefined) return '暂无口径';
  return `${rate.toFixed(2)}%`;
}

/** 满意度评分的展示（1~5 分 ⇒ 五颗星）。 */
export function satisfactionStars(score: number | null | undefined): string {
  if (score === null || score === undefined) return '未评价';
  const clamped = Math.max(1, Math.min(5, Math.round(score)));
  return `${'★'.repeat(clamped)}${'☆'.repeat(5 - clamped)}（${clamped} 分）`;
}

/**
 * 升级去向的提示：按当前工单类型说清楚**能升成什么**。
 *
 * ⚠️ 类型推进是**带方向**的：咨询只能升为投诉，投诉才能升为纠纷。
 */
export function escalateHint(ticketType: string | null | undefined): string {
  switch (ticketType) {
    case 'INQUIRY':
      return '咨询可升级为投诉（会在原售后单之外新建一张售后单，并把新单号记回工单）';
    case 'COMPLAINT':
      return '投诉可升级为纠纷（会新建一条平台纠纷，并把纠纷号记回工单）';
    default:
      return '催单 / 平台政策咨询不参与「升级」通道（升级是**带方向**的类型推进）';
  }
}

/**
 * 动作禁用理由由后端给，前端只转述。
 *
 * ⚠️ 动作不存在时也要有话说，而不是静默放行。
 *
 * <p>入参只要求这三个字段（结构化子类型）—— 调用方不必为了拿一条理由
 * 而伪造一整条动作记录。
 */
export function actionBlockedReason(
  actions:
    | ReadonlyArray<Pick<ServiceTicketAction, 'code' | 'allowed' | 'blockedReason'>>
    | undefined,
  code: string,
): string | null {
  if (!actions) return '动作清单不可用（未加载到工单详情）';
  const found = actions.find((a) => a.code === code);
  if (!found) return '该动作在当前工单上不可用';
  if (found.allowed) return null;
  return found.blockedReason || '当前状态不允许这个动作';
}

/** 格式化时刻（本地时区，非法值原样返回）。 */
export function formatTime(value: string | null | undefined): string {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString('zh-CN', { hour12: false });
}

// ==========================================================================
// 选项集合
// ==========================================================================

export interface TicketOption {
  code: string;
  label: string;
}

/** 工单状态（顺序 = 状态机推进顺序）。 */
export const TICKET_STATUS_OPTIONS: TicketOption[] = [
  { code: 'OPEN', label: '待处理' },
  { code: 'IN_PROGRESS', label: '处理中' },
  { code: 'WAITING_BUYER', label: '待买家' },
  { code: 'RESOLVED', label: '已关闭' },
  { code: 'CANCELLED', label: '已取消' },
];

/** 工单类型：蓝图点名的四类。 */
export const TICKET_TYPE_OPTIONS: TicketOption[] = [
  { code: 'INQUIRY', label: '咨询' },
  { code: 'COMPLAINT', label: '投诉' },
  { code: 'ORDER_CHASE', label: '催单' },
  { code: 'POLICY_CONSULT', label: '平台政策咨询' },
];

/** 优先级四档。 */
export const TICKET_PRIORITY_OPTIONS: TicketOption[] = [
  { code: 'LOW', label: '低' },
  { code: 'NORMAL', label: '普通' },
  { code: 'HIGH', label: '高' },
  { code: 'URGENT', label: '紧急' },
];

/** 创建来源（输入句点名的三种）。 */
export const TICKET_SOURCE_OPTIONS: TicketOption[] = [
  { code: 'MANUAL', label: '人工创建' },
  { code: 'MESSAGE', label: '来自消息' },
  { code: 'AUTOMATIC', label: '自动触发' },
];

/** 关闭原因。 */
export const CLOSE_REASON_OPTIONS: TicketOption[] = [
  { code: 'RESOLVED', label: '已解决' },
  { code: 'NO_RESPONSE', label: '买家未回应' },
  { code: 'DUPLICATE', label: '重复工单' },
  { code: 'CANCELLED', label: '与本单无关' },
  { code: 'ESCALATED', label: '已升级转单' },
  { code: 'ABANDONED', label: '不再跟进' },
];

/** SLA 判定状态（由 DB 函数现算）。 */
export const SLA_STATE_OPTIONS: TicketOption[] = [
  { code: 'PENDING', label: '计时中' },
  { code: 'MET', label: '已达标' },
  { code: 'BREACHED', label: '已超期' },
  { code: 'NO_DEADLINE', label: '无时限' },
];

/** 排序白名单四个键。 */
export const TICKET_SORT_OPTIONS: TicketOption[] = [
  { code: 'sla_asc', label: 'SLA 最紧急在前' },
  { code: 'created_desc', label: '最新创建在前' },
  { code: 'priority_desc', label: '优先级高的在前' },
  { code: 'assigned_asc', label: '责任人字典序' },
];

/**
 * 升级为投诉时目标售后单的**类型**（照抄 11.1.1.1 售后单列表的五种类型词表，
 * 不另立一套 —— 另立一套会让「这是哪种售后单」在两个模块里给出两个答案）。
 */
export const ESCALATE_CASE_TYPE_OPTIONS: TicketOption[] = [
  { code: 'RETURN_REFUND', label: '退货退款' },
  { code: 'REFUND_ONLY', label: '仅退款' },
  { code: 'EXCHANGE', label: '换货' },
  { code: 'REISSUE', label: '补发' },
  { code: 'REPAIR', label: '维修' },
];

/** 升级为投诉时目标售后单的**原因分类**（照抄 11.1.1.1 的七类词表）。 */
export const ESCALATE_REASON_CODE_OPTIONS: TicketOption[] = [
  { code: 'QUALITY_DEFECT', label: '质量问题' },
  { code: 'DESCRIPTION_MISMATCH', label: '描述不符' },
  { code: 'SIZE_MISMATCH', label: '尺寸不符' },
  { code: 'LOGISTICS_DAMAGE', label: '物流破损' },
  { code: 'BUYER_REASON', label: '买家原因' },
  { code: 'MISTAKE_BUY', label: '买家误购' },
  { code: 'FRAUD_RETURN', label: '欺诈退货' },
];

/** 升级为纠纷时的纠纷类型（11.4.1.1 的四种；前三种各自绑定一个平台）。 */
export const ESCALATE_DISPUTE_TYPE_OPTIONS: TicketOption[] = [
  { code: 'A_TO_Z', label: 'Amazon A-to-Z 索赔（限 AMAZON）' },
  { code: 'EBAY_DISPUTE', label: 'eBay 买家争议（限 EBAY）' },
  { code: 'ALIEXPRESS_DISPUTE', label: '速卖通纠纷（限 ALIEXPRESS）' },
  { code: 'OTHER', label: '其它平台纠纷' },
];

/**
 * 升级表单**还差什么**（缺什么就说什么，别让点击后端才报）。
 *
 * @param targetType 升级去向：COMPLAINT（转投诉）或 DISPUTE（转纠纷）
 * @param fields 两条通道各自要的字段
 * @returns 空串 = 齐了；否则是人话说明缺哪几项
 */
export function escalateMissingFields(
  targetType: string,
  fields: {
    reasonNote?: string;
    caseType?: string;
    reasonCode?: string;
    amount?: string;
    currency?: string;
    quantity?: string;
    platformCode?: string;
    platformName?: string;
    disputeNo?: string;
    disputeType?: string;
    disputeAmount?: string;
    disputeCurrency?: string;
    evidenceDeadline?: string;
  },
): string {
  const missing: string[] = [];
  if (!fields.reasonNote || !fields.reasonNote.trim()) missing.push('升级理由');
  if (targetType === 'COMPLAINT') {
    if (!fields.caseType) missing.push('目标售后类型');
    if (!fields.reasonCode) missing.push('目标原因分类');
    if (!fields.amount || !fields.amount.trim()) missing.push('申请金额');
    if (!fields.currency || fields.currency.trim().length !== 3) missing.push('币种（三位大写字母）');
    if (!fields.quantity || !fields.quantity.trim()) missing.push('申请数量');
  } else if (targetType === 'DISPUTE') {
    if (!fields.platformCode || !fields.platformCode.trim()) missing.push('平台码');
    if (!fields.platformName || !fields.platformName.trim()) missing.push('平台名称');
    if (!fields.disputeNo || !fields.disputeNo.trim()) missing.push('平台纠纷单号');
    if (!fields.disputeType) missing.push('纠纷类型');
    if (!fields.disputeAmount || !fields.disputeAmount.trim()) missing.push('争议金额');
    if (!fields.disputeCurrency || fields.disputeCurrency.trim().length !== 3) {
      missing.push('争议币种（三位大写字母）');
    }
    if (!fields.evidenceDeadline || !fields.evidenceDeadline.trim()) {
      missing.push('举证截止时刻（平台的时限是真实时刻，不是「7 天」这种相对描述）');
    }
  }
  return missing.join('、');
}
