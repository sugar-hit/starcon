import type {
  AiAdoptionInput,
  AiAdoptionPage,
  AiAdoptionReportPage,
  AiAdoptionRow,
  AiBacktestPage,
  AiBacktestRunInput,
  AiBacktestRunResult,
  AiEffectCapabilities,
  AiEffectOverview,
  AiEffectThresholdInput,
  AiEffectThresholdRow,
  AiFeedbackInput,
  AiFeedbackPage,
  AiFeedbackRow,
  AiSuggestionDecisionInput,
  AiSuggestionGenerateInput,
  AiSuggestionGenerateResult,
  AiSuggestionPage,
  AiSuggestionRow,
  AiWithdrawInput,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 18.4.3.2「效果评估」前端模型。
 *
 * 蓝图：「**职责**：评估 AI 功能效果：翻译采纳率、预测准确度回测、助手答案有用性反馈，
 * 识别低价值功能并下线。**输入**：人工采纳/修改记录；预测回测结果；用户反馈。
 * **输出**：效果报表 + 改进建议。**关系**：上游 = 18.4 全部；下游 = 18.4 迭代。
 * **采纳率是 AI 功能唯一诚实的指标**——生成了但没人用的功能应下线。」
 *
 * 🔴 这个页面上最容易做丢的六件事，这里都挡住了：
 *
 * 1. **分母为 0 时比率是 `null`，不是 0**。前端**不能**把 `null` 画成 0% 的柱状图 ——
 *    「没有调用记录」与「调用了但没人用」是两件完全不同的事。前端全程保留
 *    `string | null`，由 {@link formatRate} 区分「没有值」（显示「—」）与「真的是 0」。
 * 2. **比率不做浮点运算**。后端给的是 `NUMERIC(9,6)` 的十进制文本（`"0.250000"`）。
 *    转成 `number` 再乘 100 会掉精度；{@link formatRate} 用**字符串移位**算百分比，
 *    一次都不碰浮点。
 * 3. **三态不是布尔**。采纳 = 直接采用 / 修改后采用 / 拒绝（**前两者都算被采纳**）；
 *    有用性 = 有用 / 部分有用 / 无用。只显示一个「用了 / 没用」会把「改了才用」
 *    误判成「没用」。
 * 4. **主体成员不是前端能填的**。请求体里**没有** tenantId / userId / membershipId ——
 *    归属一律由服务端从已认证会话解析（Jackson fail-on-unknown-properties ⇒ 自称的字段直接 400）。
 * 5. **本模块只出建议，不下线**。本仓没有按能力粒度的 AI 功能开关 ⇒ 没有下线端点，
 *    决策端点只记录 ACCEPTED / REJECTED，页面不写「已下线」。
 * 6. **助手答案这一维如实答不可用**：18.4.1.1 对话助手已熔断未交付 ⇒ 上游不存在助手答案，
 *    本模块只提供反馈登记的**载体**，页面**不伪造**助手的使用 / 反馈数据。
 *
 * <p><b>类型全部从契约派生</b>（`packages/api-contracts` 的 codegen 产物），
 * 前端**不手抄**字符串联合 —— 枚举值由后端的字典列给出。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，第二段与兄弟车道不同）。 */
const base = 'platform/ai/effects';

/**
 * 拼查询串：空值不拼（拼 `undefined=` 上游会当成「筛选了这个值」）。
 *
 * <p>布尔值要显式转成 {@code 'true' / 'false'} —— {@code undefined} 会被拼成字面量
 * {@code "undefined"}，上游就会当成「筛选了这个值」。
 */
function query(params: Record<string, string | number | boolean | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, typeof value === 'boolean' ? String(value) : String(value));
  }
  return search.toString();
}

// ================================================================ 读取

/** 能力声明：哪些真接通、哪些还没交付、归谁。 */
export function fetchCapabilities(): Promise<AiEffectCapabilities> {
  return api<AiEffectCapabilities>(`${base}/capabilities`);
}

/** 效果总览：采纳率 / 预测回测误差 / 有用性反馈，三块都带**分子分母与口径**。 */
export function fetchOverview(params: {
  from?: string | undefined;
  to?: string | undefined;
}): Promise<AiEffectOverview> {
  return api<AiEffectOverview>(`${base}/overview?${query(params)}`);
}

/** 采纳记录台账（真分页 + 真筛选）。 */
export function fetchAdoptions(
  page: number,
  size: number,
  params: {
    capabilityCode?: string | undefined;
    featureCode?: string | undefined;
    decision?: string | undefined;
    from?: string | undefined;
    to?: string | undefined;
  },
): Promise<AiAdoptionPage> {
  return api<AiAdoptionPage>(`${base}/adoptions?${query({ ...params, page, size })}`);
}

/** 采纳率报表（**分子 / 分母 / 比率 / 口径四件齐全**）。 */
export function fetchAdoptionReport(
  page: number,
  size: number,
  params: {
    capabilityCode?: string | undefined;
    from?: string | undefined;
    to?: string | undefined;
  },
): Promise<AiAdoptionReportPage> {
  return api<AiAdoptionReportPage>(`${base}/adoption-report?${query({ ...params, page, size })}`);
}

/** 有用性反馈台账（真分页 + 真筛选）。 */
export function fetchFeedback(
  page: number,
  size: number,
  params: {
    capabilityCode?: string | undefined;
    featureCode?: string | undefined;
    usefulness?: string | undefined;
    from?: string | undefined;
    to?: string | undefined;
  },
): Promise<AiFeedbackPage> {
  return api<AiFeedbackPage>(`${base}/feedback?${query({ ...params, page, size })}`);
}

/** 预测准确度回测结果台账（真分页 + 真筛选）。 */
export function fetchBacktests(
  page: number,
  size: number,
  params: {
    runId?: string | undefined;
    skuCode?: string | undefined;
    status?: string | undefined;
  },
): Promise<AiBacktestPage> {
  return api<AiBacktestPage>(`${base}/backtests?${query({ ...params, page, size })}`);
}

/** 当前生效的评估阈值（**租户级一行**；没登记时返回**系统默认阈值**并标 `configured=false`）。 */
export function fetchThresholds(): Promise<AiEffectThresholdRow> {
  return api<AiEffectThresholdRow>(`${base}/thresholds`);
}

/** 改进建议清单（真分页 + 真筛选）。 */
export function fetchSuggestions(
  page: number,
  size: number,
  params: {
    status?: string | undefined;
    suggestionType?: string | undefined;
    capabilityCode?: string | undefined;
  },
): Promise<AiSuggestionPage> {
  return api<AiSuggestionPage>(`${base}/suggestions?${query({ ...params, page, size })}`);
}

// ================================================================ 写入

/**
 * 录入一次人工处置（直接采用 / 修改后采用 / 拒绝）。
 *
 * <p>🔴 请求体里**没有** membershipId：主体成员由服务端从已认证会话解析。
 */
export function recordAdoption(input: AiAdoptionInput): Promise<AiAdoptionRow> {
  return api<AiAdoptionRow>(`${base}/adoptions`, 'POST', input);
}

/** 撤回一条采纳记录（受控状态机 ACTIVE → WITHDRAWN）。 */
export function withdrawAdoption(
  id: string,
  input?: AiWithdrawInput | undefined,
): Promise<AiAdoptionRow> {
  return api<AiAdoptionRow>(`${base}/adoptions/${id}/withdraw`, 'POST', input);
}

/**
 * 登记一次有用性反馈。
 *
 * <p>⚠️ 助手答案这一维上游未交付：端点**照样接受**登记（它是反馈的**载体**），
 * 但聚合时助手维度会如实显示「暂无反馈」，前端**不伪造**助手数据。
 */
export function recordFeedback(input: AiFeedbackInput): Promise<AiFeedbackRow> {
  return api<AiFeedbackRow>(`${base}/feedback`, 'POST', input);
}

/** 撤回一条有用性反馈（受控状态机 ACTIVE → WITHDRAWN）。 */
export function withdrawFeedback(
  id: string,
  input?: AiWithdrawInput | undefined,
): Promise<AiFeedbackRow> {
  return api<AiFeedbackRow>(`${base}/feedback/${id}/withdraw`, 'POST', input);
}

/**
 * 跑一次预测准确度回测。
 *
 * <p>🔴 `runId` 留空 = 取最近一次已完成的预测运行。回测是**计算快照**（真实销量是后补的）
 * ⇒ 重跑会刷新同一行。
 */
export function runBacktest(input?: AiBacktestRunInput | undefined): Promise<AiBacktestRunResult> {
  return api<AiBacktestRunResult>(`${base}/backtests/run`, 'POST', input);
}

/**
 * 登记 / 修改评估阈值（留空的字段沿用当前值）。
 *
 * <p>🔴 用 **POST** 而不是 PUT：阈值是**租户级一行**的**局部更新**
 * （同一条覆盖，不是整份替换），与 platform 服务既有的 upsert 约定一致。
 * 另一条硬约束：浏览器侧的 `/api/platform/…` 代理**刻意不导出 PUT**
 * ⇒ 挂 PUT 端点等于让浏览器根本调不到（405）。
 */
export function saveThreshold(input: AiEffectThresholdInput): Promise<AiEffectThresholdRow> {
  return api<AiEffectThresholdRow>(`${base}/thresholds`, 'POST', input);
}

/** 生成改进建议（重新生成 = 刷新同一条，不堆重复行）。 */
export function generateSuggestions(
  input?: AiSuggestionGenerateInput | undefined,
): Promise<AiSuggestionGenerateResult> {
  return api<AiSuggestionGenerateResult>(`${base}/suggestions/generate`, 'POST', input);
}

/**
 * 记录对一条建议的人工决策（**只记录决定，不执行下线**）。
 *
 * <p>🔴 `decision` 走**查询参数**（后端是 `@RequestParam`），不是请求体字段。
 */
export function decideSuggestion(
  id: string,
  decision: 'ACCEPTED' | 'REJECTED',
  input?: AiSuggestionDecisionInput | undefined,
): Promise<AiSuggestionRow> {
  return api<AiSuggestionRow>(
    `${base}/suggestions/${id}/decision?${query({ decision })}`,
    'POST',
    input,
  );
}

// ================================================================ 展示口径

/** 语气。 */
export type Tone = 'ok' | 'warn' | 'danger' | 'muted';

/**
 * 十进制文本 → 去掉多余的尾零（**纯字符串裁剪**，不转数值）。
 *
 * <p>🔴 后端给的是 `NUMERIC(19,6)` 的原样（{@code "3.000000"}）。
 * 直接显示很啰嗦，但**转成 number 再显示**会掉精度 —— 所以这里只裁字符串。
 */
export function trimDecimal(text: string | null | undefined): string {
  if (text === null || text === undefined) return '—';
  const raw = text.trim();
  if (raw === '') return '—';
  if (!/^-?\d+(\.\d+)?$/.test(raw)) return raw;
  if (!raw.includes('.')) return raw;
  const trimmed = raw.replace(/0+$/, '').replace(/\.$/, '');
  return trimmed === '' || trimmed === '-' ? '0' : trimmed;
}

/**
 * 比率 → 百分比（**字符串移位，不碰浮点**）。
 *
 * <p>🔴 为什么不用 `Number(x) * 100`：`"0.07"` 这类值转成浮点再乘 100 会得到
 * `7.000000000000001`。而比率是**判定口径**（低于阈值就判低价值）⇒ 显示必须精确。
 *
 * <p>🔴 `null` 与 `"0"` 必须区分开：
 * `null` = **没有分母**（分母为 0）⇒ 显示「—」；`"0"` = 真的是 0% ⇒ 显示「0%」。
 * 把两者混成同一个 0 就是在说谎。
 */
export function formatRate(text: string | null | undefined): string {
  if (text === null || text === undefined) return '—';
  const raw = text.trim();
  if (raw === '') return '—';
  const match = raw.match(/^(\d+)(?:\.(\d+))?$/);
  if (!match) return raw;
  const integer = match[1] ?? '0';
  const fraction = match[2] ?? '';
  // 小数点右移两位 = ×100：新的整数部分吃掉原小数的前两位（不足补 0），
  // 原小数的后两位成为新的小数部分（不足补 0）。全程字符串操作，不碰浮点。
  const shiftedInteger = `${integer}${fraction.slice(0, 2).padEnd(2, '0')}`;
  const shiftedFraction = fraction.slice(2, 4).padEnd(2, '0');
  const head = shiftedInteger.replace(/^0+(?=\d)/, '');
  return `${head}.${shiftedFraction}%`;
}

/** 次数展示（后端给的是整数 `number`，这里只做格式化）。 */
export function formatCount(count: number | null | undefined): string {
  if (count === null || count === undefined || Number.isNaN(count)) return '—';
  return count.toLocaleString('zh-CN');
}

/** 时刻展示（ISO → 本地可读时间；空值显示「—」）。 */
export function formatInstant(iso: string | null | undefined): string {
  if (!iso) return '—';
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return iso;
  return at.toLocaleString('zh-CN', { hour12: false });
}

/**
 * 主体成员的展示。
 *
 * <p>🔴 这里**只显示 ID 的前 8 位** —— 页面不需要、也不该展示完整成员标识。
 */
export function memberLabel(membershipId: string | null | undefined): string {
  if (!membershipId) return '—';
  return `${membershipId.slice(0, 8)}…`;
}

// ---- 枚举的中文说法（与后端字典列一致；后端已经给了 label 的字段优先用后端那份）

/** 人工处置的三态。 */
export function adoptionDecisionLabel(decision: string): string {
  if (decision === 'ACCEPTED') return '直接采用';
  if (decision === 'MODIFIED') return '修改后采用';
  if (decision === 'REJECTED') return '拒绝';
  return decision;
}

/** 人工处置的语气。 */
export function adoptionDecisionTone(decision: string): Tone {
  if (decision === 'ACCEPTED') return 'ok';
  // 🔴 「修改后采用」**也是被采纳**（计入分子）—— 但它标黄，因为人改过：
  //   它说明产物方向对、质量不够，是改进建议的信号。
  if (decision === 'MODIFIED') return 'warn';
  if (decision === 'REJECTED') return 'danger';
  return 'muted';
}

/** 有用性三档。 */
export function usefulnessLabel(usefulness: string): string {
  if (usefulness === 'USEFUL') return '有用';
  if (usefulness === 'PARTIALLY_USEFUL') return '部分有用';
  if (usefulness === 'NOT_USEFUL') return '无用';
  return usefulness;
}

/** 有用性的语气。 */
export function usefulnessTone(usefulness: string): Tone {
  if (usefulness === 'USEFUL') return 'ok';
  if (usefulness === 'PARTIALLY_USEFUL') return 'warn';
  if (usefulness === 'NOT_USEFUL') return 'danger';
  return 'muted';
}

/** 被评估的 AI 产物类型。 */
export function artifactTypeLabel(artifactType: string): string {
  if (artifactType === 'TRANSLATION') return '译文';
  if (artifactType === 'FORECAST') return '预测结果';
  if (artifactType === 'ASSISTANT') return '助手答案';
  if (artifactType === 'OTHER') return '其它 AI 产物';
  return artifactType;
}

/** 反馈理由码的受控词表（后端同口径；键即码，值即中文说法）。 */
export const FEEDBACK_REASON_LABELS: Record<string, string> = {
  INACCURATE: '内容不准',
  TOO_VERBOSE: '太啰嗦',
  MISSING_CONTEXT: '缺少上下文',
  STYLE_MISMATCH: '风格不符',
  WRONG_LANGUAGE: '语言不对',
  TERM_ISSUE: '术语问题',
  DATA_STALE: '数据过时',
  OTHER: '其它',
};

/** 反馈理由码（**受控词表**，可聚合）。 */
export function feedbackReasonLabel(reasonCode: string): string {
  return FEEDBACK_REASON_LABELS[reasonCode] ?? reasonCode;
}

/** 回测判定的语气。 */
export function backtestStatusTone(status: string): Tone {
  if (status === 'COMPARED') return 'ok';
  // 🔴 NO_ACTUAL_SOURCE **不是失败**，是「这个 SKU 一条销量都没有」——
  //   该 SKU 没有实际值来源，如实记「缺实际值来源」而不是记 0。
  if (status === 'NO_ACTUAL_SOURCE') return 'muted';
  if (status === 'NOT_COMPARABLE') return 'warn';
  return 'muted';
}

/** 建议类型。 */
export function suggestionTypeLabel(suggestionType: string): string {
  if (suggestionType === 'OFFLINE_CANDIDATE') return '低价值，建议下线';
  if (suggestionType === 'LOW_USEFULNESS') return '有用性偏低';
  if (suggestionType === 'FORECAST_INACCURACY') return '预测准确度偏低';
  if (suggestionType === 'DATA_GAP') return '数据不足';
  return suggestionType;
}

/** 建议类型的语气。 */
export function suggestionTypeTone(suggestionType: string): Tone {
  if (suggestionType === 'OFFLINE_CANDIDATE') return 'danger';
  if (suggestionType === 'DATA_GAP') {
    // 🔴 数据不足**不是低价值** —— 不拿 3 次调用判死刑。
    return 'muted';
  }
  return 'warn';
}

/** 建议严重度。 */
export function suggestionSeverityTone(severity: string): Tone {
  if (severity === 'HIGH') return 'danger';
  if (severity === 'MEDIUM') return 'warn';
  if (severity === 'LOW') return 'muted';
  return 'muted';
}

/** 建议状态。 */
export function suggestionStatusLabel(status: string): string {
  if (status === 'OPEN') return '待决策';
  if (status === 'ACCEPTED') return '已采纳';
  if (status === 'REJECTED') return '已驳回';
  if (status === 'RESOLVED') return '条件已不成立，已自动关闭';
  return status;
}

/** 建议状态的语气。 */
export function suggestionStatusTone(status: string): Tone {
  if (status === 'OPEN') return 'warn';
  if (status === 'ACCEPTED') return 'ok';
  return 'muted';
}

/** 功能低价值判定的语气。 */
export function verdictTone(verdict: string): Tone {
  if (verdict === 'OK') return 'ok';
  if (verdict === 'LOW_VALUE') return 'danger';
  if (verdict === 'INSUFFICIENT_DATA') return 'warn';
  return 'muted';
}

/** 功能低价值判定的中文说法。 */
export function verdictLabel(verdict: string): string {
  if (verdict === 'OK') return '正常';
  if (verdict === 'LOW_VALUE') return '低价值';
  if (verdict === 'INSUFFICIENT_DATA') return '数据不足';
  return verdict;
}

/**
 * 事实行的状态（采纳 / 反馈台账）。
 *
 * <p>事实**不可改写**，但允许受控的 ACTIVE → WITHDRAWN 撤回（填错了要能纠正，
 * 否则一条错记录会让采纳率永远偏）。
 */
export function factStatusLabel(status: string): string {
  if (status === 'ACTIVE') return '有效';
  if (status === 'WITHDRAWN') return '已撤回';
  return status;
}

/** 事实行的状态语气。 */
export function factStatusTone(status: string): Tone {
  return status === 'ACTIVE' ? 'ok' : 'muted';
}

/**
 * 一条采纳率报表行的**四件套**（分子 / 分母 / 比率 / 口径）。
 *
 * <p>🔴 四件缺一不可 —— 没有口径的数字没法被信任。
 * 分子还要**分开列**「已关联调用记录」与「未关联」两段：两者的单位可能不是一回事
 * （未关联的那段不是「调用次数」口径下的采纳），混算会得出假的比率。
 */
export function adoptionRateFacts(row: AdoptionReportLike): { label: string; value: string }[] {
  return [
    { label: '分母（AI 调用次数）', value: formatCount(row.calls) },
    { label: '分子（被采纳次数）', value: formatCount(row.adoptedCount) },
    {
      label: '其中：直接采用 / 修改后采用 / 拒绝',
      value: `${formatCount(row.acceptedCount)} / ${formatCount(row.modifiedCount)} / ${formatCount(row.rejectedCount)}`,
    },
    {
      label: '分子里已关联 / 未关联调用记录',
      value: `${formatCount(row.linkedAdoptedCount)} / ${formatCount(row.unlinkedAdoptedCount)}`,
    },
    { label: '采纳率', value: formatRate(row.adoptionRate) },
    { label: '分母来源', value: row.denominatorSource },
    { label: '分子来源', value: row.numeratorSource },
  ];
}

/** 采纳率报表行的最小结构（只取四件套用到的字段）。 */
export interface AdoptionReportLike {
  calls: number;
  adoptedCount: number;
  acceptedCount: number;
  modifiedCount: number;
  rejectedCount: number;
  linkedAdoptedCount: number;
  unlinkedAdoptedCount: number;
  adoptionRate: string | null;
  denominatorSource: string;
  numeratorSource: string;
}

/**
 * 阈值行的人类可读说明。
 *
 * <p>🔴 `configured=false` 时要**说清这是系统默认值，不是本租户定的** ——
 * 谎称是租户定的会让人以为「我已经按业务调过了」。
 */
export function thresholdFacts(row: AiEffectThresholdRow): { label: string; value: string }[] {
  return [
    { label: '低价值判定门槛（调用次数）', value: formatCount(row.minCalls) },
    { label: '低价值采纳率阈值', value: formatRate(row.lowAdoptionRate) },
    { label: '有用性判定门槛（反馈数）', value: formatCount(row.minFeedbackCount) },
    { label: '低有用性阈值', value: formatRate(row.lowUsefulRate) },
    { label: '预测误差上限（MAPE）', value: formatRate(row.maxMape) },
  ];
}

/**
 * 建议的**四件事**（针对哪个功能 / 当前指标 / 建议做什么 / 依据是什么）。
 *
 * <p>🔴 缺一不可 —— 只说「建议下线」而不说依据，运营没法判断该不该照做。
 */
export function suggestionFacts(row: SuggestionLike): { label: string; value: string }[] {
  return [
    { label: '针对功能', value: `${row.capabilityCode} · ${row.featureCode}` },
    { label: '当前指标', value: row.currentMetric },
    { label: '建议做什么', value: row.recommendation },
    { label: '依据', value: row.evidence },
  ];
}

/** 建议行的最小结构（只取四件事用到的字段）。 */
export interface SuggestionLike {
  capabilityCode: string;
  featureCode: string;
  currentMetric: string;
  recommendation: string;
  evidence: string;
}

/**
 * 把阈值入参里的**空串**归一成 `null`（= 留空沿用当前值）。
 *
 * <p>后端语义：{@code null} = 沿用当前值。所以表单里清空一格，**不能**发空串 ——
 * 空串会被后端当成「这个值是空的」而报错或写坏数据。
 */
export function normalizeThresholdInput(input: {
  minCalls: string;
  lowAdoptionRate: string;
  minFeedbackCount: string;
  lowUsefulRate: string;
  maxMape: string;
}): AiEffectThresholdInput {
  const text = (value: string): string | null => {
    const trimmed = value.trim();
    return trimmed === '' ? null : trimmed;
  };
  const count = (value: string): number | null => {
    const trimmed = value.trim();
    if (trimmed === '') return null;
    const parsed = Number(trimmed);
    return Number.isInteger(parsed) && parsed >= 0 ? parsed : null;
  };
  return {
    minCalls: count(input.minCalls),
    lowAdoptionRate: text(input.lowAdoptionRate),
    minFeedbackCount: count(input.minFeedbackCount),
    lowUsefulRate: text(input.lowUsefulRate),
    maxMape: text(input.maxMape),
  };
}

/**
 * 阈值表单的**本地**校验（口径与后端一致，但错误要早说，别让人白提交一次）。
 *
 * @returns 错误文案；合法时返回 {@code null}。
 */
export function validateThresholdInput(input: {
  minCalls: string;
  lowAdoptionRate: string;
  minFeedbackCount: string;
  lowUsefulRate: string;
  maxMape: string;
}): string | null {
  const counts: [string, string][] = [
    [input.minCalls, '调用次数门槛'],
    [input.minFeedbackCount, '反馈数门槛'],
  ];
  for (const [raw, label] of counts) {
    const trimmed = raw.trim();
    if (trimmed === '') continue;
    const parsed = Number(trimmed);
    if (!Number.isInteger(parsed) || parsed < 0) return `${label}必须是不小于 0 的整数`;
  }
  const rates: [string, string][] = [
    [input.lowAdoptionRate, '低价值采纳率阈值'],
    [input.lowUsefulRate, '低有用性阈值'],
    [input.maxMape, 'MAPE 阈值'],
  ];
  for (const [raw, label] of rates) {
    const trimmed = raw.trim();
    if (trimmed === '') continue;
    if (!/^\d+(\.\d+)?$/.test(trimmed)) return `${label}必须是不带符号的十进制数（0 ~ 1，如 0.2）`;
    if (Number(trimmed) > 1) return `${label}必须在 0 ~ 1 之间`;
  }
  return null;
}

// ================================================================ 表单校验

/** 采纳登记表单的字段（全部是**文本**，UUID 与整数在提交时才转）。 */
export interface AdoptionForm {
  capabilityCode: string;
  featureCode: string;
  decision: string;
  artifactType: string;
  artifactId: string;
  artifactVersion: string;
  artifactRef: string;
  usageRecordId: string;
  editNote: string;
  rejectReason: string;
}

/** 有用性反馈表单的字段。 */
export interface FeedbackForm {
  capabilityCode: string;
  featureCode: string;
  usefulness: string;
  reasonCode: string;
  reasonText: string;
  artifactType: string;
  artifactId: string;
  artifactVersion: string;
  artifactRef: string;
}

/** 完整 UUID 形状（与白名单正则里的 id 段一致）。 */
const UUID_SHAPE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** 能力码：大写字母 / 数字 / 下划线（与后端同口径）。 */
const CAPABILITY_SHAPE = /^[A-Z][A-Z0-9_]{0,63}$/;

/**
 * 采纳登记的**本地**校验（口径与后端一致，但错误要早说，别让人白提交一次）。
 *
 * @returns 错误文案；合法时返回 {@code null}。
 */
export function validateAdoptionInput(form: AdoptionForm): string | null {
  const common = validateArtifactFields(
    form.capabilityCode,
    form.artifactType,
    form.artifactId,
    form.artifactVersion,
  );
  if (common) return common;
  if (!['ACCEPTED', 'MODIFIED', 'REJECTED'].includes(form.decision))
    return '处置只能是 ACCEPTED（直接采用）/ MODIFIED（修改后采用）/ REJECTED（拒绝）';
  // 🔴 驳回必须写明原因 —— 「没用」不是一个可复现的理由。
  if (form.decision === 'REJECTED' && form.rejectReason.trim() === '') return '驳回必须写明原因';
  if (form.usageRecordId.trim() !== '' && !UUID_SHAPE.test(form.usageRecordId.trim()))
    return '关联调用记录 id 必须是 UUID';
  if (form.featureCode.length > 64) return '子功能长度不能超过 64 个字符';
  if (form.artifactRef.length > 64) return '产物定位码长度不能超过 64 个字符';
  return null;
}

/**
 * 有用性反馈的**本地**校验。
 *
 * @returns 错误文案；合法时返回 {@code null}。
 */
export function validateFeedbackInput(form: FeedbackForm): string | null {
  const common = validateArtifactFields(
    form.capabilityCode,
    form.artifactType,
    form.artifactId,
    form.artifactVersion,
  );
  if (common) return common;
  if (!['USEFUL', 'PARTIALLY_USEFUL', 'NOT_USEFUL'].includes(form.usefulness))
    return '有用性只能是 USEFUL（有用）/ PARTIALLY_USEFUL（部分有用）/ NOT_USEFUL（无用）';
  if (form.reasonCode.trim() !== '' && !(form.reasonCode.trim() in FEEDBACK_REASON_LABELS))
    return '理由码只能是受控词表里的码（INACCURATE / TOO_VERBOSE / …）';
  if (form.featureCode.length > 64) return '子功能长度不能超过 64 个字符';
  if (form.artifactRef.length > 64) return '产物定位码长度不能超过 64 个字符';
  return null;
}

/** 采纳与反馈共用的那几项校验（能力码 / 产物类型 / 产物 id / 版本号）。 */
function validateArtifactFields(
  capabilityCode: string,
  artifactType: string,
  artifactId: string,
  artifactVersion: string,
): string | null {
  const capability = capabilityCode.trim().toUpperCase();
  if (capability === '') return '能力码不能为空';
  if (!CAPABILITY_SHAPE.test(capability))
    return '能力码只能是大写字母、数字与下划线（如 TEXT_TRANSLATION）';
  if (!['TRANSLATION', 'FORECAST', 'ASSISTANT', 'OTHER'].includes(artifactType))
    return '产物类型只能是 TRANSLATION / FORECAST / ASSISTANT / OTHER';
  const id = artifactId.trim();
  // 🔴 本仓自己拥有的两类产物**必须**带 id —— 否则「引用」是空话。
  if ((artifactType === 'TRANSLATION' || artifactType === 'FORECAST') && id === '')
    return artifactType === 'TRANSLATION' ? '译文必须给出产物 id' : '预测结果必须给出产物 id';
  if (id !== '' && !UUID_SHAPE.test(id)) return '产物 id 必须是 UUID';
  const version = artifactVersion.trim();
  if (version !== '') {
    const parsed = Number(version);
    if (!Number.isInteger(parsed) || parsed < 1) return '产物版本号必须是 ≥ 1 的整数';
  }
  return null;
}

/** 把表单里的文本转成契约里的入参（UUID / 整数在**提交时**才转，空值转 null）。 */
export function toAdoptionInput(form: AdoptionForm): AiAdoptionInput {
  const version = form.artifactVersion.trim();
  return {
    capabilityCode: form.capabilityCode.trim().toUpperCase(),
    featureCode: form.featureCode.trim(),
    decision: form.decision,
    artifactType: form.artifactType,
    artifactId: form.artifactId.trim() === '' ? null : form.artifactId.trim(),
    artifactVersion: version === '' ? null : Number(version),
    artifactRef: form.artifactRef.trim(),
    usageRecordId: form.usageRecordId.trim() === '' ? null : form.usageRecordId.trim(),
    editNote: form.editNote.trim(),
    rejectReason: form.rejectReason.trim(),
  };
}

/** 把反馈表单里的文本转成契约里的入参。 */
export function toFeedbackInput(form: FeedbackForm): AiFeedbackInput {
  const version = form.artifactVersion.trim();
  return {
    capabilityCode: form.capabilityCode.trim().toUpperCase(),
    featureCode: form.featureCode.trim(),
    usefulness: form.usefulness,
    reasonCode: form.reasonCode.trim(),
    reasonText: form.reasonText.trim(),
    artifactType: form.artifactType,
    artifactId: form.artifactId.trim() === '' ? null : form.artifactId.trim(),
    artifactVersion: version === '' ? null : Number(version),
    artifactRef: form.artifactRef.trim(),
  };
}
