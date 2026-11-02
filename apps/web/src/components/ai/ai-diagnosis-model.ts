import type {
  AiDiagnosisCapabilities,
  AiDiagnosisConclusionDetail,
  AiDiagnosisConclusionRow,
  AiDiagnosisEvidenceRow,
  AiDiagnosisEventDetail,
  AiDiagnosisEventInput,
  AiDiagnosisEventPage,
  AiDiagnosisEventRow,
  AiDiagnosisOverview,
  AiDiagnosisRunDetail,
  AiDiagnosisRunRow,
  AiDiagnosisSyncResult,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 18.4.2.3「异常诊断」前端模型。
 *
 * 蓝图：「**AI 异常诊断**：对库存异常、利润异常、发货延迟、同步失败、广告亏损等给出
 * 可能原因分析（自动关联相关数据）与处置建议。输入：异常事件 + 关联数据（订单、库存、
 * 成本、历史记录）。输出：诊断结论 + 依据 + 建议动作。关系：上游 = 1 工作台待办、各域异常；
 * 下游 = 对应处理页。**诊断要给依据**（"因为 SKU X 的头程成本上涨 18%"），不能只给结论。」
 *
 * 🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * 1. **依据是一等公民**，不是一个「详情」链接。每条结论下面**摊开**它的每一条依据：
 *    事实表 / 字段 / 数值 / 快照时间 / 下钻引用 / 口径说明 —— 依据要能下钻到事实行，
 *    才回答得��「凭什么这么说」。只显示结论不显示依据 = 本模块没做完。
 * 2. **「读不到」与「没有结论」是两件事**：一次运行读不到某一格上游（`sourcesUnavailable`）
 *    与读到了但没满足触发条件（`conclusions` 为空），页面必须分开写，
 *    否则「没结论」会被读成「没问题」。
 * 3. **未接通的上游 ≠ 没有数据**：能力声明里 `available=false` 的项按原样显示并写明归谁，
 *    不显示成「暂无」。
 * 4. **建议不自动执行**：处置建议只指向处理页与归谁执行，本模块**不执行**任何动作
 *    （补货归 6 采购、调价归 5 定价、停广告归 14 广告）。
 *
 * <p><b>类型全部从契约派生</b>（`packages/api-contracts` 的 codegen 产物），
 * 前端**不手抄**字符串联合 —— 枚举值由后端的字典列给出。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，不是并进既有分支）。 */
const base = 'platform/ai/diagnosis';

/**
 * 拼查询串：空值不拼（拼 `undefined=` 上游会当成「筛选了这个值」）。
 */
function query(params: Record<string, string | number | boolean | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, String(value));
  }
  return search.toString();
}

// ================================================================ 读取

/** 能力声明：归因规则、阈值、事实源与**边界**逐条报出。 */
export function fetchCapabilities(): Promise<AiDiagnosisCapabilities> {
  return api<AiDiagnosisCapabilities>(`${base}/capabilities`);
}

/** 概览：几个数。 */
export function fetchOverview(): Promise<AiDiagnosisOverview> {
  return api<AiDiagnosisOverview>(`${base}/overview`);
}

/** 异常事件列表（真分页 + 真筛选 + 真搜索）。 */
export function fetchEvents(params: {
  anomalyType?: string | undefined;
  status?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}): Promise<AiDiagnosisEventPage> {
  return api<AiDiagnosisEventPage>(`${base}/events?${query(params)}`);
}

/** 事件详情：事件 + 它的历史运行。 */
export function fetchEventDetail(id: string): Promise<AiDiagnosisEventDetail> {
  return api<AiDiagnosisEventDetail>(`${base}/events/${id}`);
}

/** 运行详情：这一次运行的结论 + 每条结论的依据。 */
export function fetchRunDetail(id: string): Promise<AiDiagnosisRunDetail> {
  return api<AiDiagnosisRunDetail>(`${base}/runs/${id}`);
}

// ================================================================ 写入

/**
 * 登记一个异常事件（**调用方提交**这一路来源）。
 *
 * 🔴 请求体里**没有** tenantId：租户从已认证成员关系解析，前端也不该有。
 */
export function submitEvent(input: AiDiagnosisEventInput): Promise<AiDiagnosisEventRow> {
  return api<AiDiagnosisEventRow>(`${base}/events`, 'POST', input);
}

/**
 * 对一个异常事件发起一次诊断（**确定性归因**）。
 *
 * 🔴 上游读不到时后端如实报 503 —— 这里**不**做任何本地兜底兜成「诊断成功但没结论」。
 */
export function diagnoseEvent(id: string): Promise<AiDiagnosisRunDetail> {
  return api<AiDiagnosisRunDetail>(`${base}/events/${id}/diagnose`, 'POST');
}

/**
 * 人工标记异常事件已处置（**只记下「人决定了处置」**）。
 *
 * 🔴 处置说明必填：不然「为什么关掉」无从追问。
 */
export function resolveEvent(id: string, note: string): Promise<AiDiagnosisEventRow> {
  return api<AiDiagnosisEventRow>(`${base}/events/${id}/resolve`, 'POST', { note });
}

/**
 * 从**平台内部失败源**同步异常事件（死信 / 永久失败 / 限流 / 鉴权失败 / 规则执行失败）。
 *
 * 🔴 幂等：同一个失败源事实只登记一次，重复同步不会刷屏。
 */
export function syncInternalFailures(): Promise<AiDiagnosisSyncResult> {
  return api<AiDiagnosisSyncResult>(`${base}/events/sync`, 'POST');
}

// ================================================================ 展示口径

/** 语气档位。 */
export type Tone = 'ok' | 'warn' | 'danger' | 'muted';

/** 异常类别的中文说法（后端也给了 anomalyTypeLabel，这里给的是**筛选器**用的固定档位）。 */
export function anomalyLabel(type: string): string {
  if (type === 'INVENTORY') return '库存异常';
  if (type === 'PROFIT') return '利润异常';
  if (type === 'SHIPPING_DELAY') return '发货延迟';
  if (type === 'SYNC_FAILURE') return '同步失败';
  if (type === 'AD_LOSS') return '广告亏损';
  return type;
}

/** 五类异常（筛选器与提交表单共用一份，别处再抄一遍就会漂）。 */
export const ANOMALY_TYPES = [
  { value: 'INVENTORY', label: '库存异常' },
  { value: 'PROFIT', label: '利润异常' },
  { value: 'SHIPPING_DELAY', label: '发货延迟' },
  { value: 'SYNC_FAILURE', label: '同步失败' },
  { value: 'AD_LOSS', label: '广告亏损' },
] as const;

/** 异常对象类型（提交表单用）。 */
export const SUBJECT_TYPES = [
  { value: 'SKU', label: 'SKU' },
  { value: 'ORDER', label: '订单' },
  { value: 'CAMPAIGN', label: '广告活动' },
  { value: 'SUBSCRIPTION', label: '订阅' },
  { value: 'APP', label: '应用' },
  { value: 'ENDPOINT', label: '接口' },
  { value: 'RULE', label: '规则' },
] as const;

/** 严重度的语气。 */
export function severityTone(severity: string): Tone {
  if (severity === 'CRITICAL') return 'danger';
  if (severity === 'WARNING') return 'warn';
  return 'muted';
}

/** 严重度的中文说法。 */
export function severityLabel(severity: string): string {
  if (severity === 'CRITICAL') return '严重';
  if (severity === 'WARNING') return '警告';
  if (severity === 'INFO') return '提示';
  return severity;
}

/** 事件状态的语气。 */
export function statusTone(status: string): Tone {
  if (status === 'OPEN') return 'danger';
  if (status === 'DIAGNOSED') return 'warn';
  if (status === 'RESOLVED') return 'ok';
  return 'muted';
}

/** 事件状态的中文说法。 */
export function statusLabel(status: string): string {
  if (status === 'OPEN') return '待诊断';
  if (status === 'DIAGNOSED') return '已诊断';
  if (status === 'RESOLVED') return '已处置';
  return status;
}

/**
 * 依据硬度的中文说法。
 *
 * 🔴 这里说的是「**依据有多硬**」（STRONG / WEAK / INSUFFICIENT），
 * **不是**「我有多确信」—— 本仓没有可校准的置信度来源，
 * 写「置信度 87%」是编的，所以前端也不提供这个说法。
 */
export function evidenceLevelLabel(level: string): string {
  if (level === 'STRONG') return '依据充分（多条独立事实指向）';
  if (level === 'WEAK') return '依据较弱（只有一条事实指向）';
  if (level === 'INSUFFICIENT') return '依据不足（读到了事实但不足以归因）';
  return level;
}

/** 依据硬度的语气。 */
export function evidenceLevelTone(level: string): Tone {
  if (level === 'STRONG') return 'ok';
  if (level === 'WEAK') return 'warn';
  return 'muted';
}

/** 事件来源的中文说法（来源必须显示 —— 「这条异常是从哪来的」）。 */
export function sourceLabel(source: string): string {
  if (source === 'SUBMITTED') return '人工登记';
  if (source === 'PLATFORM_INTERNAL') return '平台内部失败源';
  return source;
}

/** 事实服务的��文说法。 */
export function factServiceLabel(service: string): string {
  if (service === 'warehouse') return '7 库存';
  if (service === 'trade') return '9 订单履约 / 6 采购';
  if (service === 'reporting') return '13 分析';
  if (service === 'channel') return '02 渠道';
  if (service === 'platform') return '18 平台';
  return service;
}

/** 数量（件 / 金额 / 秒数）。 */
export function formatValue(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  const numeric = Number(value);
  if (Number.isNaN(numeric)) return String(value);
  return numeric.toLocaleString('zh-CN', { maximumFractionDigits: 6 });
}

/** 时间（ISO 串 → 本地可读）。 */
export function formatTime(value: string | null | undefined): string {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString('zh-CN', { hour12: false });
}

/**
 * 一条依据的展示行。
 *
 * 🔴 **下钻四件套必须都在**：事实表 + 字段 + 数值 + 快照时间，外加下钻引用与口径说明。
 * 少了任何一样，这条依据就答不出「凭什么这么说」。
 */
export function evidenceRows(evidences: AiDiagnosisEvidenceRow[]): {
  service: string;
  table: string;
  field: string;
  value: string;
  unit: string;
  ref: string;
  snapshotAt: string;
  explanation: string;
}[] {
  return (evidences ?? []).map((e) => ({
    service: factServiceLabel(e.factService),
    table: e.factTable,
    field: e.factField,
    value:
      e.factValue === null || e.factValue === undefined
        ? e.factValueText
        : formatValue(e.factValue),
    unit: e.factUnit,
    ref: e.factRef,
    snapshotAt: formatTime(e.snapshotAt),
    explanation: e.explanation,
  }));
}

/**
 * 一条结论的展示行（**依据不在这里摊开**，它由 `evidenceRows` 单独摊）。
 *
 * 🔴 `impactText` 是**带符号的百分比文本**（如 {@code +18.40%} / {@code -40.00%}），
 * `impact` 是**幅度**（库里 CHECK impact >= 0）—— 两个都要显示，
 * 只显示一个都会把「涨了 18%」说成「跌了 18%」。
 */
export function conclusionRows(conclusions: AiDiagnosisConclusionDetail[]): {
  id: string;
  ruleCode: string;
  ruleLabel: string;
  severity: string;
  severityTone: Tone;
  summary: string;
  detail: string;
  evidenceLevel: string;
  evidenceLevelLabel: string;
  evidenceLevelTone: Tone;
  impact: string;
  impactUnit: string;
  impactText: string;
  recommendation: string;
  actionRef: string;
  actionOwner: string;
  evidenceCount: number;
}[] {
  return (conclusions ?? []).map((c) => {
    const row: AiDiagnosisConclusionRow = c.conclusion;
    return {
      id: row.id,
      ruleCode: row.ruleCode,
      ruleLabel: row.ruleLabel,
      severity: row.severity,
      severityTone: severityTone(row.severity),
      summary: row.summary,
      detail: row.detail,
      evidenceLevel: row.evidenceLevel,
      evidenceLevelLabel: evidenceLevelLabel(row.evidenceLevel),
      evidenceLevelTone: evidenceLevelTone(row.evidenceLevel),
      impact: row.impact === null || row.impact === undefined ? '—' : formatValue(row.impact),
      impactUnit: row.impactUnit,
      impactText: row.impactText,
      recommendation: row.recommendation,
      actionRef: row.actionRef,
      actionOwner: row.actionOwner,
      evidenceCount: (c.evidences ?? []).length,
    };
  });
}

/**
 * 一次运行的**读数状态**提示。
 *
 * 🔴 **「读不到」与「没有结论」必须分开说**：
 *
 * - 读不到某一格上游（`sourcesUnavailable` 非空）⇒ 这次运行的结论**不完整**，
 *   不能被读成「这一格没问题」。
 * - 读到了但没命中规则（`conclusions` 为空）⇒ 是「读到了事实，事实没越线」。
 *
 * 两者混成一句「暂无结论」= 把「没查出来」说成「没问题」。
 */
export function runReadState(
  run: AiDiagnosisRunRow | null,
  conclusionCount: number,
): {
  tone: Tone;
  title: string;
  detail: string;
} {
  if (!run) {
    return {
      tone: 'muted',
      title: '还没有跑过诊断',
      detail: '这一格的上游是否可读，要跑一次才知道 —— 没跑过不等于没问题。',
    };
  }
  const unavailable = (run.sourcesUnavailable ?? '')
    .split(';')
    .map((s) => s.trim())
    .filter(Boolean);
  if (unavailable.length > 0) {
    return {
      tone: 'danger',
      title: '本次诊断有读不到的事实源',
      detail: `读不到：${unavailable.join('、')}。结论只覆盖读到的那几格，读不到的那几格**没有结论**。`,
    };
  }
  if (conclusionCount === 0) {
    return {
      tone: 'warn',
      title: '本次诊断没有命中任何归因规则',
      detail: run.note || '读到的事实都不满足触发条件。',
    };
  }
  return {
    tone: 'ok',
    title: `本次诊断产出 ${conclusionCount} 条结论`,
    detail: run.note || '',
  };
}

/**
 * 本模块的**执行边界**提示（页面上固定显示，不藏在文档里）。
 *
 * 🔴 蓝图关系句写「下游 = 对应处理页」—— 本模块**只给建议并指向处理页**，
 * 不改订单、不停广告、不建补货单（跨服务写别人的库是被禁止的）。
 */
export function executionBoundaryNotice(): string {
  return '仅诊断，不执行：处置建议只指向对应处理页并点名归谁 —— 补货归 6 采购、调价归 5 定价、停广告归 14 广告。本模块不改订单、不停广告、不建补货单。';
}

/**
 * 能力声明里「还没接通」的项的展示口径。
 *
 * 🔴 **未接通 ≠ 没有数据**：显示成「未接通（归 X 模块）」，
 * 绝不显示成「暂无」—— 那会让运营以为系统查过了、结果就是空。
 */
export function unavailableNote(ownerModule: string, note: string): string {
  const owner = ownerModule && ownerModule !== '-' ? `归 ${ownerModule}` : '归因待定';
  return note ? `未接通（${owner}）：${note}` : `未接通（${owner}）`;
}
