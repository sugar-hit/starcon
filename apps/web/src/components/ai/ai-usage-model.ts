import type {
  AiModelInput,
  AiModelRow,
  AiQuotaCheckInput,
  AiQuotaEvaluation,
  AiQuotaInput,
  AiQuotaRow,
  AiQuotaViolation,
  AiSensitivePolicyInput,
  AiSensitivePolicyRow,
  AiSensitivePolicyView,
  AiUsageCapabilities,
  AiUsageRecordInput,
  AiUsageRecordPage,
  AiUsageRecordResult,
  AiUsageSummary,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 18.4.3.1「使用与配额」前端模型。
 *
 * 蓝图：「**职责**：管理 AI 使用：按租户与用户的调用量与成本、配额限制、模型选择、
 * 敏感数据处理策略开关。**输入**：调用记录；配额配置。**输出**：用量报表 + 配额执行。
 * **关系**：上游 = 18.4 全部；下游 = 17.6.1 计费。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **金额与用量全程十进制文本**。后端把它们暴露成 `string`（`NUMERIC(19,6)` 的原样），
 *    前端**不**把它们转成 `number` —— 转一次就掉精度，`0.1 + 0.2 !== 0.3` 那种错
 *    在账单上看起来只是「差一分钱」，实际上没人查得出来。
 *    `trimDecimal()` 只做**字符串**裁剪，不做数值运算。
 * 2. **不同模型 / 不同币种不混算**。Token 数与字符数相加没有业务含义；
 *    人民币与美元相加更没有。页面按「能力 × 模型 × 币种」列，币种合计**分币种**给。
 * 3. **超限要说清五要素**：超了哪一条、当前用量、阈值、周期、什么时候重置。
 *    只显示一句「超配额了」，调用方没法行动。
 * 4. **敏感数据策略的执行点如实显示**：本仓**没有**全局 AI 拦截点，
 *    策略由**各个 AI 能力自己**在调用前查。页面不写「已全局拦截」。
 * 5. **主体成员不是前端能填的**：请求体里**没有** membershipId ——
 *    租户级配额走 `/quotas`，用户级配额走 `/members/{memberId}/quotas`（成员在**路径**里）。
 *
 * <p><b>类型全部从契约派生</b>（`packages/api-contracts` 的 codegen 产物），
 * 前端**不手抄**字符串联合 —— 枚举值由后端的字典列给出。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，不是并进既有分支）。 */
const base = 'platform/ai/usage';

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
export function fetchCapabilities(): Promise<AiUsageCapabilities> {
  return api<AiUsageCapabilities>(`${base}/capabilities`);
}

/** 模型目录列表（真筛选）。 */
export function fetchModels(params: {
  enabled?: boolean | undefined;
  keyword?: string | undefined;
}): Promise<AiModelRow[]> {
  return api<AiModelRow[]>(`${base}/models?${query(params)}`);
}

/** 用量台账（真分页 + 真筛选）。 */
export function fetchRecords(
  page: number,
  size: number,
  params: {
    capabilityCode?: string | undefined;
    membershipId?: string | undefined;
    modelCode?: string | undefined;
    from?: string | undefined;
    to?: string | undefined;
  },
): Promise<AiUsageRecordPage> {
  return api<AiUsageRecordPage>(`${base}/records?${query({ ...params, page, size })}`);
}

/** 用量报表（**可计费口径**）。 */
export function fetchSummary(params: {
  from?: string | undefined;
  to?: string | undefined;
}): Promise<AiUsageSummary> {
  return api<AiUsageSummary>(`${base}/summary?${query(params)}`);
}

/** 配额列表（含当前周期的用量与是否越线）。 */
export function fetchQuotas(): Promise<AiQuotaRow[]> {
  return api<AiQuotaRow[]>(`${base}/quotas`);
}

/** 敏感数据策略列表（真筛选）。 */
export function fetchPolicies(params: {
  capabilityCode?: string | undefined;
  modelCode?: string | undefined;
}): Promise<AiSensitivePolicyRow[]> {
  return api<AiSensitivePolicyRow[]>(`${base}/policies?${query(params)}`);
}

/** 查**生效**的那条敏感策略（各 AI 能力在调用前查的就是它）。 */
export function fetchEffectivePolicy(
  capabilityCode: string,
  modelCode: string | undefined,
): Promise<AiSensitivePolicyView> {
  return api<AiSensitivePolicyView>(
    `${base}/policies/effective?${query({ capabilityCode, modelCode })}`,
  );
}

// ================================================================ 写入

/** 登记 / 修改一个模型。 */
export function saveModel(input: AiModelInput): Promise<AiModelRow> {
  return api<AiModelRow>(`${base}/models`, 'POST', input);
}

/**
 * 记一次 AI 调用用量。
 *
 * <p>🔴 请求体里**没有** membershipId：主体成员由服务端从已认证会话解析。
 */
export function recordUsage(input: AiUsageRecordInput): Promise<AiUsageRecordResult> {
  return api<AiUsageRecordResult>(`${base}/records`, 'POST', input);
}

/** 登记 / 修改一条**租户级**配额。 */
export function saveTenantQuota(input: AiQuotaInput): Promise<AiQuotaRow> {
  return api<AiQuotaRow>(`${base}/quotas`, 'POST', input);
}

/**
 * 登记 / 修改一条**用户级**配额。
 *
 * <p>🔴 主体成员走**路径**，不是请求体 —— 客户端不能自称归属。
 */
export function saveMemberQuota(memberId: string, input: AiQuotaInput): Promise<AiQuotaRow> {
  return api<AiQuotaRow>(`${base}/members/${memberId}/quotas`, 'POST', input);
}

/** 配额预检（**读快照，不占位额度**）。 */
export function checkQuota(input: AiQuotaCheckInput): Promise<AiQuotaEvaluation> {
  return api<AiQuotaEvaluation>(`${base}/quotas/check`, 'POST', input);
}

/** 登记 / 修改一条敏感数据策略。 */
export function savePolicy(input: AiSensitivePolicyInput): Promise<AiSensitivePolicyRow> {
  return api<AiSensitivePolicyRow>(`${base}/policies`, 'POST', input);
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
 * 金额展示：**币种 + 十进制文本**。
 *
 * <p>不同币种**不合并** —— 调用方自己按币种分开看。
 */
export function formatMoney(
  amount: string | null | undefined,
  currency: string | null | undefined,
): string {
  const value = trimDecimal(amount);
  return currency ? `${value} ${currency}` : value;
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

/** 配额是否越线的语气。 */
export function quotaTone(exceeded: boolean, enabled: boolean): Tone {
  if (!enabled) return 'muted';
  return exceeded ? 'danger' : 'ok';
}

/** 越线说明的语气。 */
export function violationTone(violation: AiQuotaViolation): Tone {
  return violation.limitKind === 'COST' ? 'danger' : 'warn';
}

/**
 * 越线说明的**五要素**摘要（后端已经算好，这里只排版）。
 *
 * <p>🔴 五个要素缺一不可：超了哪一条、当前用量、阈值、周期、什么时候重置。
 * 少一个，调用方就有一件事做不了。
 */
export function violationFacts(violation: AiQuotaViolation): { label: string; value: string }[] {
  return [
    { label: '超了哪一条', value: `${violation.scopeLabel} · ${violation.capabilityCode}` },
    {
      label: '阈值',
      value:
        violation.limitKind === 'COST'
          ? `成本上限 ${trimDecimal(violation.limitValue)}`
          : `次数上限 ${violation.limitValue}`,
    },
    { label: '当前用量', value: trimDecimal(violation.currentUsage) },
    {
      label: '周期',
      value: `${violation.periodLabel}（${formatInstant(violation.periodStart)} 起）`,
    },
    { label: '什么时候重置', value: formatInstant(violation.resetAt) },
  ];
}

/** 判定口径的中文说法。 */
export function evaluationModeLabel(mode: string): string {
  if (mode === 'POST_RECORD') return '记用量时的权威判定';
  if (mode === 'PREFLIGHT') return '调用前预检（快照，不占额度）';
  return mode;
}

/** 敏感数据动作的语气：禁传 = 红，脱敏 = 黄，允许 = 绿。 */
export function sensitiveActionTone(action: string): Tone {
  if (action === 'BLOCK') return 'danger';
  if (action === 'MASK') return 'warn';
  if (action === 'ALLOW') return 'ok';
  return 'muted';
}

/** 敏感数据动作的中文说法。 */
export function sensitiveActionLabel(action: string): string {
  if (action === 'BLOCK') return '禁传';
  if (action === 'MASK') return '脱敏';
  if (action === 'ALLOW') return '允许';
  return action;
}

/** 量纲的中文说法。 */
export function usageUnitLabel(unit: string): string {
  if (unit === 'REQUEST') return '次';
  if (unit === 'TOKEN') return 'Token';
  if (unit === 'THOUSAND_TOKEN') return '千 Token';
  if (unit === 'CHARACTER') return '字符';
  return unit;
}

/**
 * 主体成员的展示（用户级配额的主体）。
 *
 * <p>🔴 这里**只显示 ID 的前 8 位** —— 页面不需要、也不该展示完整成员标识。
 */
export function memberLabel(membershipId: string | null | undefined): string {
  if (!membershipId) return '—';
  return `${membershipId.slice(0, 8)}…`;
}

/**
 * 一条配额的「当前用量 / 阈值」摘要。
 *
 * <p>🔴 **两个上限各自成行**：次数与成本不是一回事（一个是次数，一个是钱），
 * 把它们加在一起没有业务含义。成本那一行**带自己的币种** ——
 * 不同币种的钱不可比，页面不替它们求和。
 */
export function quotaUsageFacts(quota: AiQuotaRow): { label: string; value: string }[] {
  const facts: { label: string; value: string }[] = [];
  if (quota.countConfigured) {
    facts.push({
      label: `次数上限（${quota.periodLabel}）`,
      value: `${formatCount(quota.usedCount)} / ${formatCount(quota.limitCount)}`,
    });
  }
  if (quota.costConfigured) {
    facts.push({
      label: `成本上限（${quota.periodLabel}·${quota.currency}）`,
      value: `${trimDecimal(quota.usedCost)} / ${trimDecimal(quota.limitCost)} ${quota.currency}`,
    });
  }
  facts.push({ label: '重置时刻', value: formatInstant(quota.periodEnd) });
  return facts;
}

/**
 * 一条配额的**一句话口径**（页面上解释「这条管谁、管什么」）。
 */
export function quotaSubjectLabel(quota: AiQuotaRow): string {
  const scope = quota.scope === 'USER' ? `用户级 · ${memberLabel(quota.membershipId)}` : '租户级';
  const capability = quota.capabilityCode === '*' ? '全部能力' : quota.capabilityCode;
  return `${scope} · ${capability}`;
}
