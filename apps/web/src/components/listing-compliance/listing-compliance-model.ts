import { api } from '../inventory/api';
import type {
  ListingComplianceCapabilities,
  ListingComplianceDimension,
  ListingComplianceDraftPage,
  ListingComplianceDraftRow,
  ListingComplianceFilterOptions,
  ListingCompliancePublishGate,
  ListingComplianceRiskRow,
  ListingComplianceScanPage,
  ListingComplianceScanResult,
  ListingComplianceWordInput,
  ListingComplianceWordPage,
  ListingComplianceWordRow,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 4.2.3.2 合规预检 前端模型。
 *
 * <p>蓝图：「发布前扫描内容的违规风险：违禁词/敏感词命中、极限词、品牌侵权（外观/商标）、平台类目必填缺失、
 * 图片不合规（尺寸/白底/水印）、认证缺失。输入：内容草稿；租户违禁词库；平台规则库；16 合规的侵权与认证状态；
 * 03 品牌授权状态。输出：风险清单（风险等级、命中位置、规则来源、建议替换）；阻断项（未修复不允许发布）与提醒项。
 * 关系：上游 = 16 合规与风控、03 品牌、02 平台规则；下游 = 4.3.1.1（阻断发布）。
 * <b>阻断项在服务端强制校验</b>，前端提示不能作为唯一防线。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **前端不重算**。风险等级、命中与否、阻断还是提醒，全部由后端算完下发；
 *    前端**原样显示**，不拿词表在浏览器里再扫一遍 —— 两份算子必然漂移，
 *    漂移之后「界面说没事、服务器说有事」，运营就不知道该信谁。
 * 2. **阻断项与提醒项分区显示，但分区 ≠ 拦截**。真正的拦截在服务端
 *    （发布闸门 + DB 触发器）；页面只负责把「未修复不允许发布」说清楚。
 * 3. **「不知道」是一个状态**。维度状态 UNKNOWN 的那几行单独成态并点名归属模块，
 *    **不会被渲染成「通过」**，结论 INCOMPLETE 也不会被写成「合规通过」。
 * 4. **命中位置要能定位到具体字段**。每条风险都带字段键 / 字段名 / 第几项，
 *    点一下能跳到那一栏的汇总行。
 * 5. **三态分开渲染**：加载中 / 加载失败 / 没有内容（无草稿 / 无风险 / 扫描失败），
 *    三种文案各不相同，不共用一个「暂无数据」。
 */

/** 风险等级（后端枚举，前端只做展示映射）。 */
export const RISK_LEVEL_OPTIONS = [
  { value: 'CRITICAL', label: '严重' },
  { value: 'HIGH', label: '高' },
  { value: 'MEDIUM', label: '中' },
  { value: 'LOW', label: '低' },
] as const;

/** 处置：阻断项 / 提醒项。 */
export const SEVERITY_OPTIONS = [
  { value: 'BLOCKING', label: '阻断项' },
  { value: 'WARNING', label: '提醒项' },
] as const;

/** 词的类型。 */
export const WORD_CATEGORY_OPTIONS = [
  { value: 'PROHIBITED', label: '违禁词' },
  { value: 'SENSITIVE', label: '敏感词' },
  { value: 'SUPERLATIVE', label: '极限词' },
] as const;

/** 结论三态。 */
export const CONCLUSION_OPTIONS = [
  { value: 'PASS', label: '通过' },
  { value: 'BLOCKED', label: '有阻断项' },
  { value: 'INCOMPLETE', label: '结论不完整' },
] as const;

/** 维度状态。 */
export const DIMENSION_STATE_OPTIONS = [
  { value: 'CHECKED', label: '已判定' },
  { value: 'UNKNOWN', label: '未能判定' },
] as const;

export type Tone = 'destructive' | 'warning' | 'brand' | 'muted' | 'default';

export type {
  ListingComplianceCapabilities,
  ListingComplianceDimension,
  ListingComplianceDraftPage,
  ListingComplianceDraftRow,
  ListingComplianceFilterOptions,
  ListingCompliancePublishGate,
  ListingComplianceRiskRow,
  ListingComplianceScanPage,
  ListingComplianceScanResult,
  ListingComplianceWordInput,
  ListingComplianceWordPage,
  ListingComplianceWordRow,
};

// ================================================================ 取数

export async function fetchComplianceCapabilities(): Promise<ListingComplianceCapabilities> {
  return api<ListingComplianceCapabilities>('channel/listing-compliance-precheck/capabilities');
}

export async function fetchComplianceFilterOptions(): Promise<ListingComplianceFilterOptions> {
  return api<ListingComplianceFilterOptions>('channel/listing-compliance-precheck/filter-options');
}

/** 可扫描的编辑器草稿（扫描的内容来源）。 */
export async function fetchComplianceDrafts(
  keyword?: string,
  page = 0,
  size = 20,
): Promise<ListingComplianceDraftPage> {
  const query = new URLSearchParams();
  if (keyword && keyword.trim()) query.set('keyword', keyword.trim());
  query.set('page', String(page));
  query.set('size', String(size));
  return api<ListingComplianceDraftPage>(
    `channel/listing-compliance-precheck/drafts?${query.toString()}`,
  );
}

/** 触发一次扫描。 */
export async function runComplianceScan(
  draftId: string,
  triggerSource: 'MANUAL' | 'PRE_PUBLISH' = 'MANUAL',
): Promise<ListingComplianceScanResult> {
  return api<ListingComplianceScanResult>('channel/listing-compliance-precheck/scans', 'POST', {
    draftId,
    triggerSource,
  });
}

/** 扫描历史（真分页）。 */
export async function fetchComplianceScans(
  page = 0,
  size = 20,
  draftId?: string,
): Promise<ListingComplianceScanPage> {
  const query = new URLSearchParams();
  if (draftId) query.set('draftId', draftId);
  query.set('page', String(page));
  query.set('size', String(size));
  return api<ListingComplianceScanPage>(
    `channel/listing-compliance-precheck/scans?${query.toString()}`,
  );
}

/** 单次扫描详情（含风险清单）。 */
export async function fetchComplianceScan(scanId: string): Promise<ListingComplianceScanResult> {
  return api<ListingComplianceScanResult>(`channel/listing-compliance-precheck/scans/${scanId}`);
}

/** 🔴 发布闸门：服务端算出来的「能不能发」。 */
export async function fetchCompliancePublishGate(
  draftId: string,
): Promise<ListingCompliancePublishGate> {
  return api<ListingCompliancePublishGate>(
    `channel/listing-compliance-precheck/drafts/${draftId}/publish-gate`,
  );
}

/** 批量处置风险。 */
export async function decideComplianceRisks(
  riskIds: string[],
  status: 'OPEN' | 'RESOLVED' | 'IGNORED',
  note: string,
): Promise<{ changed: number }> {
  return api<{ changed: number }>('channel/listing-compliance-precheck/risks/decisions', 'POST', {
    riskIds,
    status,
    note,
  });
}

/** 违禁词库（真分页 + 真筛选）。 */
export async function fetchComplianceWords(
  filters: {
    category?: string;
    severity?: string;
    riskLevel?: string;
    platformCode?: string;
    keyword?: string;
    enabled?: boolean;
  } = {},
  page = 0,
  size = 20,
): Promise<ListingComplianceWordPage> {
  const query = new URLSearchParams();
  if (filters.category) query.set('category', filters.category);
  if (filters.severity) query.set('severity', filters.severity);
  if (filters.riskLevel) query.set('riskLevel', filters.riskLevel);
  if (filters.platformCode) query.set('platformCode', filters.platformCode);
  if (filters.keyword && filters.keyword.trim()) query.set('keyword', filters.keyword.trim());
  if (filters.enabled !== undefined) query.set('enabled', String(filters.enabled));
  query.set('page', String(page));
  query.set('size', String(size));
  return api<ListingComplianceWordPage>(
    `channel/listing-compliance-precheck/words?${query.toString()}`,
  );
}

/** 新建一个词。 */
export async function createComplianceWord(
  body: ListingComplianceWordInput,
): Promise<ListingComplianceWordRow> {
  return api<ListingComplianceWordRow>('channel/listing-compliance-precheck/words', 'POST', body);
}

/** 改一个词。 */
export async function updateComplianceWord(
  id: string,
  body: ListingComplianceWordInput,
): Promise<ListingComplianceWordRow> {
  return api<ListingComplianceWordRow>(
    `channel/listing-compliance-precheck/words/${id}`,
    'PUT',
    body,
  );
}

/** 启停一个词。 */
export async function setComplianceWordEnabled(
  id: string,
  enabled: boolean,
): Promise<ListingComplianceWordRow> {
  return api<ListingComplianceWordRow>(
    `channel/listing-compliance-precheck/words/${id}/enabled?enabled=${enabled}`,
    'PUT',
  );
}

/** 删除一个词。 */
export async function deleteComplianceWord(id: string): Promise<void> {
  await api<unknown>(`channel/listing-compliance-precheck/words/${id}`, 'DELETE');
}

// ================================================================ 纯函数（可单测）

/** 风险等级 → 文案。 */
export function riskLevelLabel(level: string): string {
  return RISK_LEVEL_OPTIONS.find((o) => o.value === level)?.label ?? level;
}

/** 风险等级 → 徽章色调。 */
export function riskLevelTone(level: string): Tone {
  switch (level) {
    case 'CRITICAL':
      return 'destructive';
    case 'HIGH':
      return 'warning';
    case 'MEDIUM':
      return 'brand';
    default:
      return 'muted';
  }
}

/** 处置 → 文案。 */
export function severityLabel(severity: string): string {
  return SEVERITY_OPTIONS.find((o) => o.value === severity)?.label ?? severity;
}

/** 处置 → 徽章色调（阻断项与提醒项**必须**视觉上明确区分）。 */
export function severityTone(severity: string): Tone {
  return severity === 'BLOCKING' ? 'destructive' : 'warning';
}

/** 词的类型 → 文案。 */
export function wordCategoryLabel(category: string): string {
  return WORD_CATEGORY_OPTIONS.find((o) => o.value === category)?.label ?? category;
}

/** 结论 → 文案。🔴 INCOMPLETE 绝不能被写成「通过」。 */
export function conclusionLabel(conclusion: string): string {
  if (conclusion === 'INCOMPLETE') return '结论不完整';
  if (conclusion === 'BLOCKED') return '有阻断项';
  if (conclusion === 'PASS') return '通过';
  return conclusion;
}

/** 结论 → 色调。 */
export function conclusionTone(conclusion: string): Tone {
  if (conclusion === 'BLOCKED') return 'destructive';
  if (conclusion === 'INCOMPLETE') return 'warning';
  return 'brand';
}

/**
 * 命中位置 → 人话。
 *
 * <p>🔴 「标题」与「卖点 · 第 2 项」是两个不同的位置，不能都只写「命中了」。
 */
export function riskLocationLabel(risk: ListingComplianceRiskRow): string {
  const base = risk.fieldLabel || risk.fieldKey || '内容';
  if (risk.occurrenceIndex >= 0) {
    return `${base} · 第 ${risk.occurrenceIndex + 1} 项`;
  }
  return base;
}

/** 命中位置 → 锚点 id（点一下能跳到那一栏的汇总行）。 */
export function fieldAnchorId(fieldKey: string): string {
  return `compliance-field-${fieldKey || 'unknown'}`;
}

/** 命中位置 → 跳转 href。 */
export function riskLocationHref(risk: ListingComplianceRiskRow): string {
  return `#${fieldAnchorId(risk.fieldKey)}`;
}

/** 命中位置 → 命中的原文（没有就写「—」，不写空串冒充有值）。 */
export function matchedTextLabel(risk: ListingComplianceRiskRow): string {
  return risk.matchedText && risk.matchedText.trim() ? risk.matchedText : '—';
}

/** 维度状态 → 文案。 */
export function dimensionStateLabel(state: string): string {
  return DIMENSION_STATE_OPTIONS.find((o) => o.value === state)?.label ?? state;
}

/** 维度状态 → 色调。UNKNOWN 是「量不到」，不能渲染成「通过」。 */
export function dimensionStateTone(state: string): Tone {
  return state === 'CHECKED' ? 'brand' : 'warning';
}

/**
 * 把风险清单拆成阻断项与提醒项两段。
 *
 * <p>🔴 这里**只是分区显示**。拦截在服务端（发布闸门 + DB 触发器），
 * 页面把两段分开不等于页面在拦。
 */
export function splitRisks(risks: ListingComplianceRiskRow[]): {
  blockers: ListingComplianceRiskRow[];
  warnings: ListingComplianceRiskRow[];
} {
  const blockers: ListingComplianceRiskRow[] = [];
  const warnings: ListingComplianceRiskRow[] = [];
  for (const risk of risks) {
    if (risk.severity === 'BLOCKING' && risk.status === 'OPEN') blockers.push(risk);
    else if (risk.severity === 'WARNING' && risk.status === 'OPEN') warnings.push(risk);
  }
  return { blockers, warnings };
}

/**
 * 按命中字段把风险清单归拢成「字段 → 该栏有几条风险」。
 *
 * <p>用途：命中位置要能**定位到具体字段**，就得有一份按字段排的汇总供跳转。
 */
export function risksByField(risks: ListingComplianceRiskRow[]): {
  fieldKey: string;
  fieldLabel: string;
  count: number;
  blockingCount: number;
}[] {
  const buckets = new Map<
    string,
    { fieldKey: string; fieldLabel: string; count: number; blockingCount: number }
  >();
  for (const risk of risks) {
    if (risk.status !== 'OPEN') continue;
    const key = risk.fieldKey || 'unknown';
    const bucket = buckets.get(key) ?? {
      fieldKey: key,
      fieldLabel: risk.fieldLabel || key,
      count: 0,
      blockingCount: 0,
    };
    bucket.count += 1;
    if (risk.severity === 'BLOCKING') bucket.blockingCount += 1;
    buckets.set(key, bucket);
  }
  return [...buckets.values()].sort(
    (a, b) => b.blockingCount - a.blockingCount || a.fieldKey.localeCompare(b.fieldKey),
  );
}

/** 一句话结论（给统计卡用）。 */
export function scanSummary(result: ListingComplianceScanResult): string {
  if (result.conclusion === 'BLOCKED') {
    return `${result.blockingCount} 条阻断项未修复，${result.warningCount} 条提醒项`;
  }
  if (result.conclusion === 'INCOMPLETE') {
    return `没有已知阻断项，但有 ${result.totalDimensionCount - result.evaluatedDimensionCount} 个维度未能判定`;
  }
  return `没有阻断项也没有提醒项，${result.evaluatedDimensionCount}/${result.totalDimensionCount} 个维度已判定`;
}

/** 发布闸门 → 一句话。 */
export function gateSummary(gate: ListingCompliancePublishGate): string {
  if (gate.state === 'NOT_SCANNED') {
    return '还没有扫描过：服务端没有任何已知阻断项，但也没有任何预检证据';
  }
  if (!gate.allowed) {
    return `${gate.blockingCount} 条阻断项未修复，**未修复不允许发布**（服务端已拦截）`;
  }
  if (gate.conclusion === 'INCOMPLETE') {
    return '没有未修复的阻断项，但仍有维度未能判定（结论不是「合规通过」）';
  }
  return '服务端已判定：没有未修复的阻断项';
}

/** 空的词（新建词表单用）。 */
export function emptyWordInput(): ListingComplianceWordInput {
  return {
    word: '',
    category: 'PROHIBITED',
    severity: 'BLOCKING',
    riskLevel: 'HIGH',
    ruleSource: '',
    suggestion: '',
    platformCode: '',
    locale: '',
    note: '',
  };
}

/** 未交付维度（available=false）在界面上的措辞。 */
export function unavailableDimensions(caps: ListingComplianceCapabilities | null) {
  if (!caps) return [];
  return caps.dimensions.filter((d) => !d.available);
}
