import { api } from '../../lib/api';

/**
 * 16.4.1.1 侵权库 前端模型。
 *
 * 蓝图：「维护知识产权风险库：品牌商标、专利、版权、肖像权，登记风险等级与适用平台。
 * 输入：侵权条目登记；批量导入。输出：侵权库记录。关系：上游 = 3.3.2 品牌；下游 = 16.4.1.2 检测。
 * **侵权检测是刊登前拦截而非事后申诉**，成本差一个数量级。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **权利类型是受控枚举**（四种）—— 页面上的下拉**只给**后端说的那四种，
 *    不给自由文本框（「商标 / TRADEMARK / 商标类」会被当成三种权利）。
 * 2. **风险等级是受控等级集**（高 / 中 / 低）—— 不是 0..100 的裸数字输入框。
 * 3. **适用平台是多对多** —— 登记表单里是**多选**，不是单选下拉。
 * 4. **匹配特征是多值** —— 品牌名（可空）+ 关键词 / 短语集合 + 可选标识符特征；
 *    页面**只登记**，**不做任何比对**（比对归 16.4.1.2，该模块**未交付**）。
 * 5. **未交付的下游灰掉**：16.4.1.2 检测与预警当前**未交付**，
 *    界面上如实标出，**不编**「立即检测 / 放行」这类入口。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，不是并进既有 channel 分支）。 */
export const BASE = 'channel/ip-risk-library';

/** 🔴 权利类型值域（与后端 CHECK 逐字一致）：四种。 */
export const RIGHT_TYPES = ['TRADEMARK', 'PATENT', 'COPYRIGHT', 'IMAGE_RIGHT'] as const;

/** 🔴 风险等级值域（与后端 CHECK 逐字一致）：高 / 中 / 低。 */
export const RISK_LEVELS = ['HIGH', 'MEDIUM', 'LOW'] as const;

/** 生命周期值域。 */
export const STATUSES = ['ACTIVE', 'DISABLED'] as const;

/** 匹配特征分类值域。 */
export const TERM_KINDS = ['KEYWORD', 'PHRASE'] as const;

/** 来源值域。 */
export const SOURCES = ['MANUAL', 'IMPORT'] as const;

/** 权利类型筛选的可选项。 */
export const RIGHT_TYPE_OPTIONS = [
  { value: '', label: '全部权利类型' },
  { value: 'TRADEMARK', label: '品牌商标' },
  { value: 'PATENT', label: '专利' },
  { value: 'COPYRIGHT', label: '版权' },
  { value: 'IMAGE_RIGHT', label: '肖像权' },
] as const;

/** 风险等级筛选的可选项。 */
export const RISK_LEVEL_OPTIONS = [
  { value: '', label: '全部风险等级' },
  { value: 'HIGH', label: '高风险' },
  { value: 'MEDIUM', label: '中风险' },
  { value: 'LOW', label: '低风险' },
] as const;

/** 生命周期筛选的可选项。 */
export const STATUS_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: 'ACTIVE', label: '启用' },
  { value: 'DISABLED', label: '已停用' },
] as const;

/** 匹配特征分类的可选项。 */
export const TERM_KIND_OPTIONS = [
  { value: 'KEYWORD', label: '关键词' },
  { value: 'PHRASE', label: '短语' },
] as const;

// ==================================================================
// 类型
// ==================================================================

/** 一个下游（或维度）的交付状态。 */
export interface IpRiskLibraryCapabilityGap {
  moduleId: string;
  moduleName: string;
  ownerModule: string;
  available: boolean;
  impact: string;
}

/** 能力声明。 */
export interface IpRiskLibraryCapabilities {
  gaps: IpRiskLibraryCapabilityGap[];
  rightTypes: string[];
  rightTypeLabels: Record<string, string>;
  riskLevels: string[];
  riskLevelLabels: Record<string, string>;
  statuses: string[];
  statusLabels: Record<string, string>;
  termKinds: string[];
  termKindLabels: Record<string, string>;
  sources: string[];
  sourceLabels: Record<string, string>;
  rightTypeNote: string;
  riskLevelNote: string;
  platformNote: string;
  matchFeatureNote: string;
  lifecycleNote: string;
  importNote: string;
  upstreamNote: string;
  downstreamNote: string;
}

/** 适用平台（读本服务自己的 `platform` 目录）。 */
export interface IpRiskLibraryPlatform {
  platformCode: string;
  platformName: string;
  supportStatus: string;
}

/** 3.3.2 品牌候选（catalog 服务，只读）。 */
export interface IpRiskLibraryBrandCandidate {
  brandId: string;
  code: string;
  name: string;
  authorizationStatus: string;
  source: string;
}

/** 品牌候选读出结果（available=false ⇒ **读不到**，不是「没有品牌」）。 */
export interface IpRiskLibraryBrandSet {
  available: boolean;
  items: IpRiskLibraryBrandCandidate[];
  note: string;
}

/** 一条侵权条目。 */
export interface IpRiskLibraryEntry {
  entryId: string;
  entryCode: string;
  rightType: string;
  rightTypeLabel: string;
  riskLevel: string;
  riskLevelLabel: string;
  title: string;
  rightsHolder: string;
  brandId: string | null;
  brandName: string | null;
  matchPattern: string | null;
  note: string;
  status: string;
  statusLabel: string;
  disableReason: string;
  disabledAt: string | null;
  source: string;
  sourceLabel: string;
  /** 🔴 适用平台是**多值**。 */
  platformCodes: string[];
  /** 🔴 匹配特征是**多值**。 */
  matchTerms: string[];
  createdAt: string;
  updatedAt: string;
}

/** 侵权库清单。 */
export interface IpRiskLibraryEntryPage {
  items: IpRiskLibraryEntry[];
  total: number;
  page: number;
  size: number;
  activeCount: number;
  disabledCount: number;
  riskLevelCounts: Record<string, number>;
  rightTypeCounts: Record<string, number>;
  note: string;
}

/** 一条匹配特征。 */
export interface IpRiskLibraryMatchTerm {
  termId: string;
  entryId: string;
  term: string;
  termKind: string;
  termKindLabel: string;
}

/** 一条适用平台。 */
export interface IpRiskLibraryPlatformRef {
  platformCode: string;
  platformName: string;
}

/** 条目详情。 */
export interface IpRiskLibraryEntryDetail {
  entry: IpRiskLibraryEntry;
  matchTerms: IpRiskLibraryMatchTerm[];
  platforms: IpRiskLibraryPlatformRef[];
  note: string;
}

/** 登记 / 修改条目���入参。 */
export interface IpRiskLibraryEntryInput {
  entryCode: string;
  rightType: string;
  riskLevel: string;
  title: string;
  rightsHolder: string;
  brandId: string | null;
  brandName: string | null;
  matchPattern: string | null;
  note: string;
  platformCodes: string[];
  matchTerms: { term: string; termKind: string }[];
}

/** 登记结果（**幂等**）。 */
export interface IpRiskLibraryEntryCreation {
  entry: IpRiskLibraryEntry;
  created: boolean;
  note: string;
}

/** 批量导入的一行。 */
export interface IpRiskLibraryImportRowInput {
  entryCode: string;
  rightType: string;
  riskLevel: string;
  title: string;
  rightsHolder: string;
  brandId: string | null;
  brandName: string | null;
  matchPattern: string | null;
  note: string;
  platformCodes: string[];
  matchTerms: { term: string; termKind: string }[];
}

/** 批量导入的入参。 */
export interface IpRiskLibraryImportInput {
  sourceName: string;
  rows: IpRiskLibraryImportRowInput[];
}

/** 批量导入的**逐行**结果。 */
export interface IpRiskLibraryImportRowResult {
  rowNo: number;
  entryCode: string;
  outcome: string;
  entryId: string | null;
  errorField: string | null;
  errorMessage: string | null;
}

/** 批量导入的结果。 */
export interface IpRiskLibraryImportResult {
  batchId: string;
  sourceName: string;
  totalRows: number;
  insertedRows: number;
  skippedRows: number;
  failedRows: number;
  status: string;
  rows: IpRiskLibraryImportRowResult[];
  note: string;
}

/** 一次导入批次。 */
export interface IpRiskLibraryImportBatch {
  batchId: string;
  sourceName: string;
  totalRows: number;
  insertedRows: number;
  skippedRows: number;
  failedRows: number;
  status: string;
  createdAt: string;
}

/** 导入批次清单。 */
export interface IpRiskLibraryImportPage {
  items: IpRiskLibraryImportBatch[];
  total: number;
  page: number;
  size: number;
  note: string;
}

/** 导入批次详情。 */
export interface IpRiskLibraryImportDetail {
  batch: IpRiskLibraryImportBatch;
  rows: IpRiskLibraryImportRowResult[];
  note: string;
}

/** 平台上的侵权风险汇总。 */
export interface IpRiskLibraryPlatformSummary {
  platformCode: string;
  platformName: string;
  total: number;
  high: number;
  medium: number;
  low: number;
  disabled: number;
  note: string;
}

/** 平台汇总清单。 */
export interface IpRiskLibraryPlatformSummaryPage {
  items: IpRiskLibraryPlatformSummary[];
  note: string;
}

// ==================================================================
// 读取
// ==================================================================

export async function fetchCapabilities(): Promise<IpRiskLibraryCapabilities> {
  return api<IpRiskLibraryCapabilities>(`${BASE}/capabilities`);
}

export async function fetchPlatforms(): Promise<IpRiskLibraryPlatform[]> {
  return api<IpRiskLibraryPlatform[]>(`${BASE}/platforms`);
}

export async function fetchBrands(keyword?: string): Promise<IpRiskLibraryBrandSet> {
  const query = keyword && keyword.trim() ? `?keyword=${encodeURIComponent(keyword.trim())}` : '';
  return api<IpRiskLibraryBrandSet>(`${BASE}/brands${query}`);
}

export async function fetchEntries(
  page = 0,
  size = 20,
  filters: {
    rightType?: string;
    riskLevel?: string;
    status?: string;
    platformCode?: string;
    keyword?: string;
  } = {},
): Promise<IpRiskLibraryEntryPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.rightType) query.set('rightType', filters.rightType);
  if (filters.riskLevel) query.set('riskLevel', filters.riskLevel);
  if (filters.status) query.set('status', filters.status);
  if (filters.platformCode) query.set('platformCode', filters.platformCode);
  if (filters.keyword) query.set('keyword', filters.keyword);
  return api<IpRiskLibraryEntryPage>(`${BASE}/entries?${query.toString()}`);
}

export async function createEntry(
  input: IpRiskLibraryEntryInput,
): Promise<IpRiskLibraryEntryCreation> {
  return api<IpRiskLibraryEntryCreation>(`${BASE}/entries`, 'POST', input);
}

export async function fetchEntry(entryId: string): Promise<IpRiskLibraryEntryDetail> {
  return api<IpRiskLibraryEntryDetail>(`${BASE}/entries/${entryId}`);
}

export async function updateEntry(
  entryId: string,
  input: IpRiskLibraryEntryInput,
): Promise<IpRiskLibraryEntryDetail> {
  return api<IpRiskLibraryEntryDetail>(`${BASE}/entries/${entryId}`, 'PUT', input);
}

export async function updateEntryStatus(
  entryId: string,
  status: string,
  reason: string,
): Promise<IpRiskLibraryEntryDetail> {
  return api<IpRiskLibraryEntryDetail>(`${BASE}/entries/${entryId}/status`, 'PUT', {
    status,
    reason,
  });
}

export async function importEntries(
  input: IpRiskLibraryImportInput,
): Promise<IpRiskLibraryImportResult> {
  return api<IpRiskLibraryImportResult>(`${BASE}/imports`, 'POST', input);
}

export async function fetchImports(page = 0, size = 20): Promise<IpRiskLibraryImportPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  return api<IpRiskLibraryImportPage>(`${BASE}/imports?${query.toString()}`);
}

export async function fetchImportBatch(batchId: string): Promise<IpRiskLibraryImportDetail> {
  return api<IpRiskLibraryImportDetail>(`${BASE}/imports/${batchId}`);
}

export async function fetchPlatformSummary(): Promise<IpRiskLibraryPlatformSummaryPage> {
  return api<IpRiskLibraryPlatformSummaryPage>(`${BASE}/platform-summary`);
}

// ==================================================================
// 展示口径
// ==================================================================

export function rightTypeLabel(code: string | null | undefined): string {
  const map: Record<string, string> = {
    TRADEMARK: '品牌商标',
    PATENT: '专利',
    COPYRIGHT: '版权',
    IMAGE_RIGHT: '肖像权',
  };
  if (!code) return '未声明';
  return map[code] ?? code;
}

export function riskLevelLabel(code: string | null | undefined): string {
  const map: Record<string, string> = { HIGH: '高风险', MEDIUM: '中风险', LOW: '低风险' };
  if (!code) return '未声明';
  return map[code] ?? code;
}

/** 🔴 风险等级的色调：高=红、中=黄、低=中性 —— 低风险**不是**绿色（绿色会让人以为可以无视）。 */
export function riskLevelTone(code: string | null | undefined): 'danger' | 'warning' | 'neutral' {
  if (code === 'HIGH') return 'danger';
  if (code === 'MEDIUM') return 'warning';
  return 'neutral';
}

export function statusLabel(code: string | null | undefined): string {
  const map: Record<string, string> = { ACTIVE: '启用', DISABLED: '已停用' };
  if (!code) return '未声明';
  return map[code] ?? code;
}

export function sourceLabel(code: string | null | undefined): string {
  const map: Record<string, string> = { MANUAL: '人工登记', IMPORT: '批量导入' };
  if (!code) return '未声明';
  return map[code] ?? code;
}

export function termKindLabel(code: string | null | undefined): string {
  const map: Record<string, string> = { KEYWORD: '关键词', PHRASE: '短语' };
  if (!code) return '未声明';
  return map[code] ?? code;
}

/** 🔴 适用平台的展示：一个都没有时写「未限定平台」（空白会被读成「没填」）。 */
export function platformText(codes: string[] | null | undefined): string {
  if (!codes || codes.length === 0) return '未限定平台';
  return codes.join('、');
}

/** 🔴 匹配特征的展示：一个都没有时写「未登记匹配特征」。 */
export function matchTermsText(terms: string[] | null | undefined): string {
  if (!terms || terms.length === 0) return '未登记匹配特征';
  return terms.join('、');
}

/** 品牌名（可空 —— 有些专利 / 版权没有品牌名）。 */
export function brandText(entry: IpRiskLibraryEntry): string {
  return entry.brandName?.trim() ? entry.brandName : '未指定品牌';
}

/** 空态：两种（还没有条目 / 筛选无结果）。 */
export type EmptyKind = 'NO_DATA' | 'NO_MATCH';

export function emptyStateKind(total: number, filtered: boolean): EmptyKind {
  if (filtered) return 'NO_MATCH';
  if (total === 0) return 'NO_DATA';
  return 'NO_MATCH';
}

export function emptyState(kind: EmptyKind): { title: string; description: string } {
  if (kind === 'NO_DATA') {
    return {
      title: '还没有任何侵权条目',
      description:
        '侵权库还是空的。登记第一条条目（权利类型 / 风险等级 / 适用平台 / 匹配特征），或用批量导入一次落库。',
    };
  }
  return {
    title: '筛选条件下没有条目',
    description: '当前筛选（权利类型 / 风险等级 / 状态 / 平台 / 关键字）下没有匹配的侵权条目。',
  };
}

/** 导入结果里每一行的展示口径（成功 / 跳过 / 失败三态分开说）。 */
export function importOutcomeLabel(outcome: string | null | undefined): string {
  if (outcome === 'INSERTED') return '已写入';
  if (outcome === 'SKIPPED') return '已存在，跳过';
  if (outcome === 'FAILED') return '失败';
  return outcome ?? '未知';
}

export function importOutcomeTone(
  outcome: string | null | undefined,
): 'success' | 'neutral' | 'danger' {
  if (outcome === 'INSERTED') return 'success';
  if (outcome === 'FAILED') return 'danger';
  return 'neutral';
}

// ==================================================================
// 批量导入文本解析
// ==================================================================

/** 导入文本的一行解析结果。 */
export interface ParsedImport {
  rows: IpRiskLibraryImportRowInput[];
  problems: { lineNo: number; message: string }[];
}

/** 导入文本的列序（管道分隔）。 */
export const IMPORT_COLUMNS = [
  '条目编码',
  '权利类型',
  '风险等级',
  '标题',
  '权利人',
  '品牌名',
  '标识符特征',
  '适用平台(逗号分隔)',
  '匹配特征(逗号分隔)',
] as const;

/**
 * 🔴 解析粘贴的导入文本（**每行一条，管道分隔**）。
 *
 * <p>列序：{@link IMPORT_COLUMNS}。
 *
 * <p>🔴 **本地先解析一遍**：提交前就把「哪一行、哪个字段」指出来，
 * 比提交后拿到 500 行行级错误再回头改要快得多。
 * 空行与 `#` 开头的注释行忽略。
 */
export function parseImportText(text: string): ParsedImport {
  const rows: IpRiskLibraryImportRowInput[] = [];
  const problems: { lineNo: number; message: string }[] = [];
  const lines = (text ?? '').split(/\r?\n/);

  lines.forEach((raw, index) => {
    const lineNo = index + 1;
    const line = raw.trim();
    if (!line) return;
    if (line.startsWith('#')) return;
    const cells = line.split('|').map((cell) => cell.trim());
    if (cells.length < 4) {
      problems.push({
        lineNo,
        message: `第 ${lineNo} 行只有 ${cells.length} 列，至少要有 4 列（条目编码 / 权利类型 / 风险等级 / 标题）。`,
      });
      return;
    }
    const [entryCode = '', rightType = '', riskLevel = '', title = ''] = cells;
    const rightsHolder = cells[4] ?? '';
    const brandName = cells[5] ?? '';
    const matchPattern = cells[6] ?? '';
    const platformCodes = splitList(cells[7] ?? '');
    const matchTerms = splitList(cells[8] ?? '').map((term) => ({ term, termKind: 'KEYWORD' }));

    if (!entryCode) {
      problems.push({
        lineNo,
        message: `第 ${lineNo} 行：条目编码不能为空（它是批量导入的幂等锚点）。`,
      });
      return;
    }
    if (!RIGHT_TYPES.includes(rightType as (typeof RIGHT_TYPES)[number])) {
      problems.push({
        lineNo,
        message: `第 ${lineNo} 行：权利类型「${rightType}」不在受控值域内（只能是 TRADEMARK / PATENT / COPYRIGHT / IMAGE_RIGHT）。`,
      });
      return;
    }
    if (!RISK_LEVELS.includes(riskLevel as (typeof RISK_LEVELS)[number])) {
      problems.push({
        lineNo,
        message: `第 ${lineNo} 行：风险等级「${riskLevel}」不在受控值域内（只能是 HIGH / MEDIUM / LOW）。`,
      });
      return;
    }
    if (!title) {
      problems.push({ lineNo, message: `第 ${lineNo} 行：标题不能为空。` });
      return;
    }
    rows.push({
      entryCode,
      rightType,
      riskLevel,
      title,
      rightsHolder,
      brandId: null,
      brandName: brandName || null,
      matchPattern: matchPattern || null,
      note: '',
      platformCodes,
      matchTerms,
    });
  });

  return { rows, problems };
}

/** 逗号分隔的列表 → 去空白、去空值。 */
function splitList(value: string): string[] {
  return value
    .split(/[,，]/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0);
}
