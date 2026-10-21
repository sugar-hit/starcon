import { api } from '../inventory/api';

/**
 * 16.2.2.1 合规预检 前端模型。
 *
 * 蓝图：「发布前的合规体检：内容违规、认证缺失、尺寸规格不符、材质与描述不符、危险品与运输限制、
 * 标签与说明书要求，多维度检查并给出整改建议。输入：listing 内容草稿；商品属性；认证状态；平台要求。
 * 输出：体检报告（分维度得分 + 问题清单 + 整改建议）。关系：上游 = 4.2.1.1 编辑器；下游 = 16.2.1.2 拦截。
 * **预检在保存草稿时就跑一次**（即时反馈），**发布时再跑一次**（强制校验）。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **「不可知」显示成「未知」，不显示 0 分**。分维度得分与整改建议**原样显示后端算出来的值**
 *    （`scoreText` / `suggestion`），前端**不重算**。后端给了 `null` 就是 `null`，
 *    这里渲染成「未知」并把「量不到的原因 + 归属模块」一起显示出来。
 * 2. **总分不被未知维度稀释**，也不被未知维度拉低。后端算好的 `overallScoreText` 原样显示；
 *    分数卡上并列显示「N 个维度未知」，免得运营把「只有一项被体检过」误读成「全绿」。
 * 3. **两次触发语义分得开**。报告头带 `triggerKind`，历史里用徽章区分
 *    「保存草稿时的即时反馈」与「发布前的强制校验」。
 * 4. **页面上没有「拦截 / 豁免 / 审批」按钮**。那归 16.2.1.2 拦截执行（未交付）。
 *    本页只出报告与结论。
 * 5. **问题清单里每一条都带归属模块**。量不到的条目也在清单里（`state='UNKNOWN'`），
 *    不是悄悄消失 —— 消失的话界面就只剩一个看起来很干净的分数。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，不是并进既有 channel 分支）。 */
export const BASE = 'channel/compliance-precheck';

/** 两种触发语义。 */
export const TRIGGER_KINDS = [
  { value: 'DRAFT_SAVE', label: '保存草稿时的即时反馈', hint: '改完内容立刻体检，给即时反馈' },
  { value: 'PUBLISH_GATE', label: '发布前的强制校验', hint: '发布前再体检一次，供 16.2.1.2 拦截执行消费' },
] as const;

export type TriggerKind = (typeof TRIGGER_KINDS)[number]['value'];

/** 逐维度能力声明。 */
export interface CompliancePrecheckCapabilityDimension {
  code: string;
  label: string;
  /** 该维度当前是否**量得到**（false = 量不到）。 */
  available: boolean;
  source: string;
  /** 量不到时归谁。 */
  ownerModule: string;
  /** 量不到的影响说明。 */
  impact: string;
}

/** 能力声明。 */
export interface CompliancePrecheckCapabilities {
  dimensions: CompliancePrecheckCapabilityDimension[];
  dimensionOrder: string[];
  checkStates: string[];
  severities: string[];
  triggerKinds: string[];
  scoreSemantics: string;
  verdictSemantics: string;
  unknownSemantics: string;
  reportNote: string;
  enforcementNote: string;
  boundaryNote: string;
  upstreamNote: string;
}

/** 可供体检的内容草稿（4.2.1.1 的草稿，本模块只读）。 */
export interface CompliancePrecheckDraft {
  draftId: string;
  shopId: string;
  platformCode: string;
  marketplaceCode: string;
  locale: string;
  title: string;
  state: string;
  sourceSkuId: string | null;
  updatedAt: string;
}

/** 可体检草稿列表（真分页）。 */
export interface CompliancePrecheckDraftPage {
  items: CompliancePrecheckDraft[];
  total: number;
  page: number;
  size: number;
}

/** 报告头。 */
export interface CompliancePrecheckReportHeader {
  reportId: string;
  draftId: string;
  triggerKind: string;
  triggerLabel: string;
  state: string;
  /** 总体得分（0..1 比值；**null = 未知**）。 */
  overallScore: number | null;
  overallState: string;
  /** 总分的展示串（后端算好；未知时是「未知」）。 */
  overallScoreText: string;
  verdict: string;
  hasUnknownDimensions: boolean;
  totalDimensionCount: number;
  scoredDimensionCount: number;
  unknownDimensionCount: number;
  checkItemCount: number;
  failedItemCount: number;
  unknownItemCount: number;
  platformCode: string;
  marketplaceCode: string;
  locale: string;
  checkedAt: string;
}

/** 分维度得分。 */
export interface CompliancePrecheckDimensionScore {
  dimensionCode: string;
  dimensionLabel: string;
  weight: number;
  /** 维度得分（0..1 比值；**null = 量不到**）。 */
  score: number | null;
  /** 维度得分的展示串（后端算好；量不到时是「未知」）。 */
  scoreText: string;
  state: string;
  checkItemCount: number;
  passedItemCount: number;
  failedItemCount: number;
  unknownItemCount: number;
  ownerModule: string | null;
  unavailableReason: string;
  note: string;
}

/** 问题清单里的一条。 */
export interface CompliancePrecheckFinding {
  findingId: string;
  dimensionCode: string;
  dimensionLabel: string;
  checkCode: string;
  subjectRef: string;
  location: string;
  severity: string;
  /** VIOLATION（实打实的问题） / UNKNOWN（量不到）。 */
  state: string;
  message: string;
  suggestion: string;
  ownerModule: string;
  evidence: Record<string, unknown>;
}

/** 一条整改建议。 */
export interface CompliancePrecheckRemediation {
  dimensionCode: string;
  dimensionLabel: string;
  ownerModule: string;
  action: string;
  priority: string;
}

/** 一份完整报告。 */
export interface CompliancePrecheckReport {
  header: CompliancePrecheckReportHeader;
  dimensions: CompliancePrecheckDimensionScore[];
  findings: CompliancePrecheckFinding[];
  remediation: CompliancePrecheckRemediation[];
}

/** 报告历史里的一行。 */
export interface CompliancePrecheckReportSummary {
  reportId: string;
  draftId: string;
  draftTitle: string;
  triggerKind: string;
  triggerLabel: string;
  state: string;
  overallScore: number | null;
  overallScoreText: string;
  verdict: string;
  failedItemCount: number;
  unknownItemCount: number;
  platformCode: string;
  marketplaceCode: string;
  checkedAt: string;
}

/** 报告历史（真分页）。 */
export interface CompliancePrecheckReportPage {
  items: CompliancePrecheckReportSummary[];
  total: number;
  page: number;
  size: number;
}

// ==================================================================
// 接口调用
// ==================================================================

export async function fetchCapabilities(): Promise<CompliancePrecheckCapabilities> {
  return api<CompliancePrecheckCapabilities>(`${BASE}/capabilities`);
}

export async function fetchDrafts(page = 0, size = 20): Promise<CompliancePrecheckDraftPage> {
  return api<CompliancePrecheckDraftPage>(`${BASE}/drafts?page=${page}&size=${size}`);
}

/** 跑一次体检。`trigger` 决定这次是「保存时的即时反馈」还是「发布前的强制校验」。 */
export async function runCheck(
  draftId: string,
  trigger: TriggerKind,
): Promise<CompliancePrecheckReport> {
  const path = trigger === 'PUBLISH_GATE' ? 'precheck' : 'check';
  return api<CompliancePrecheckReport>(`${BASE}/drafts/${draftId}/${path}`, 'POST');
}

export async function fetchReports(
  page = 0,
  size = 20,
  draftId?: string,
): Promise<CompliancePrecheckReportPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (draftId) query.set('draftId', draftId);
  return api<CompliancePrecheckReportPage>(`${BASE}/reports?${query.toString()}`);
}

export async function fetchReport(reportId: string): Promise<CompliancePrecheckReport> {
  return api<CompliancePrecheckReport>(`${BASE}/reports/${reportId}`);
}

// ==================================================================
// 展示口径（**不重算后端的分数**）
// ==================================================================

/** 结论的三态文案。 */
export function verdictLabel(verdict: string): string {
  switch (verdict) {
    case 'PASS':
      return '无违规';
    case 'ATTENTION':
      return '有违规';
    case 'UNKNOWN':
      return '判不了';
    default:
      return verdict;
  }
}

/** 结论的徽章色调（对齐 @starcon/ui 的 Badge tone 词表）。 */
export function verdictTone(verdict: string): 'success' | 'danger' | 'neutral' {
  switch (verdict) {
    case 'PASS':
      return 'success';
    case 'ATTENTION':
      return 'danger';
    default:
      return 'neutral';
  }
}

/** 严重度文案。 */
export function severityLabel(severity: string): string {
  switch (severity) {
    case 'BLOCKING':
      return '阻断项';
    case 'WARNING':
      return '警告项';
    default:
      return '提示';
  }
}

/** 触发语义文案。 */
export function triggerText(triggerKind: string): string {
  for (const trigger of TRIGGER_KINDS) {
    if (trigger.value === triggerKind) return trigger.label;
  }
  return triggerKind;
}

/**
 * 🔴 维度得分的展示串。
 *
 * <p>**不重算**：后端给了 `scoreText` 就用它；后端给 `null` 就是「未知」。
 * 前端把 `null` 渲染成 0 是在替后端撒谎（0 分 = 一分没得，null = 没量过）。
 */
export function dimensionScoreText(dimension: CompliancePrecheckDimensionScore): string {
  if (dimension.state === 'UNKNOWN' || dimension.score === null) return '未知';
  return dimension.scoreText;
}

/** 量不到的维度清单（前端用来显示「N 个维度未知」与各自的归属模块）。 */
export function unknownDimensions(
  report: CompliancePrecheckReport | null,
): CompliancePrecheckDimensionScore[] {
  if (!report) return [];
  return report.dimensions.filter((dimension) => dimension.state === 'UNKNOWN');
}

/** 违规项清单（问题清单里 state='VIOLATION' 的那些）。 */
export function violations(report: CompliancePrecheckReport | null): CompliancePrecheckFinding[] {
  if (!report) return [];
  return report.findings.filter((finding) => finding.state === 'VIOLATION');
}

/** 量不到的检查项清单。 */
export function unknownFindings(
  report: CompliancePrecheckReport | null,
): CompliancePrecheckFinding[] {
  if (!report) return [];
  return report.findings.filter((finding) => finding.state === 'UNKNOWN');
}

/**
 * 🔴 空态文案：**三态不同**。
 *
 * @param kind 'NO_DRAFT' 没有可体检的草稿 / 'NO_FINDING' 体检了但没有问题 / 'ALL_UNKNOWN' 全部维度量不到
 */
export function emptyState(kind: 'NO_DRAFT' | 'NO_FINDING' | 'ALL_UNKNOWN'): {
  title: string;
  description: string;
} {
  switch (kind) {
    case 'NO_DRAFT':
      return {
        title: '还没有可体检的内容草稿',
        description:
          '合规预检的输入是 4.2.1.1 刊登编辑器的内容草稿。先去刊登编辑器保存一份草稿，再回来体检。',
      };
    case 'NO_FINDING':
      return {
        title: '本次体检没有发现问题',
        description:
          '已登记的平台要求逐条通过。注意：量不到的维度不产生问题项，也不进得分分母 —— 看看下面的「维度未知」清单。',
      };
    case 'ALL_UNKNOWN':
      return {
        title: '所有维度都量不到',
        description:
          '没有任何维度能量出分（得分是「未知」而不是 0）。每个维度的归属模块都写在下面，接入后重跑一次即可。',
      };
    default:
      return { title: '', description: '' };
  }
}
