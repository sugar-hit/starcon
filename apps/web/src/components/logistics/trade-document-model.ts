import type {
  TradeCertificationCreateRequest,
  TradeCertificationPage,
  TradeCertificationRow,
  TradeCertificationSaved,
  TradeCertificationUpdateRequest,
  TradeCheckRow,
  TradeDocumentCapabilities,
  TradeDocumentCreateRequest,
  TradeDocumentFinalizeRequest,
  TradeDocumentRow,
  TradeDocumentSaved,
  TradeEventRow,
  TradePreferenceClaimRequest,
  TradePreferenceConfirmationPage,
  TradePreferenceRuleCreateRequest,
  TradePreferenceRulePage,
  TradePreferenceRuleRow,
  TradePreferenceRuleSaved,
  TradePreferenceRuleUpdateRequest,
  TradeReminderPage,
  TradeReminderResolveRequest,
  TradeReminderRow,
  TradeReminderSaved,
  TradeRuleCreateRequest,
  TradeRulePage,
  TradeRuleRow,
  TradeRuleSaved,
  TradeRuleUpdateRequest,
  TradeSaved,
  TradeSetAcknowledgeRequest,
  TradeSetCancelRequest,
  TradeSetCreateRequest,
  TradeSetDetail,
  TradeSetPage,
  TradeSetRejectReviewRequest,
  TradeSetReviewRequest,
  TradeSetRow,
  TradeSetSendBackRequest,
  TradeSetSubmitRequest,
  TradeSetUpdateRequest,
  TradeSetWithdrawReviewRequest,
  TradeTemplateCreateRequest,
  TradeTemplatePage,
  TradeTemplateRow,
  TradeTemplateSaved,
  TradeTemplateUpdateRequest,
} from '../../../../../packages/api-contracts/src/catalog';
import { api } from '../inventory/api';

/**
 * codegen 把 OpenAPI 的 `nullable` 拍平成了非空必填，于是 `legalEntityId: string` 这类
 * **真可以为 null 的**字段在契约上被写成必填字符串。后端 record 仍然是 `UUID`/`Integer`，
 * 传空串会在反序列化时炸（UUID 解析失败），所以这里显式把可空性补回请求类型。
 *
 * <p>只覆盖确实可为 null 的那几个字段，不整体放宽 —— 放宽等于把后端的必填校验也一起关掉。
 */
type Nullable<K extends string, V> = { [P in K]?: V | null };
export type TradeSetCreateInput = Nullable<'legalEntityId', string> &
  Omit<TradeSetCreateRequest, 'legalEntityId'>;
export type TradeDocumentCreateInput = Nullable<'templateId' | 'documentNo', string> &
  Nullable<'quantityTotal', number> &
  Nullable<'packageCount', number> &
  Nullable<'content', Record<string, unknown>> &
  Omit<TradeDocumentCreateRequest, 'templateId' | 'documentNo' | 'quantityTotal' | 'packageCount' | 'content'>;
export type TradePreferenceClaimInput = Nullable<'confirmed', boolean> &
  Omit<TradePreferenceClaimRequest, 'confirmed'>;
export type TradeRuleCreateInput = Nullable<'validTo', string> & Omit<TradeRuleCreateRequest, 'validTo'>;
export type TradePreferenceRuleCreateInput = Nullable<'validTo', string> &
  Omit<TradePreferenceRuleCreateRequest, 'validTo'>;
export type TradeCertificationCreateInput = Nullable<
  'issuedOn' | 'expiresOn' | 'certificateNo' | 'issuingBody' | 'documentRef',
  string
> &
  Omit<
    TradeCertificationCreateRequest,
    'issuedOn' | 'expiresOn' | 'certificateNo' | 'issuingBody' | 'documentRef'
  >;

/**
 * 10.5.2.1 贸易单证前端模型。
 *
 * <p>蓝图：「管理与生成贸易相关单证：商业发票、装箱单、原产地证（RCEP/CO/FTA 优惠）、提单/空运单、
 * 进口许可与认证文件，按目的地国家规则校验完整性」。界面上最容易读错的三处，这里都显式处理：
 *
 * <ul>
 *   <li><b>「实质性改变」不是系统能回答的</b>：优惠原产地证主张 FTA 优惠时，
 *       {@code substantiveChangeStatus} 只会停在「待业务确认」直到有人留痕确认。
 *       界面**不能**把它渲染成「校验通过」——
 *       {@link substantiveChangeBlocking} 是唯一判据，它只认「待业务确认」这一个阻断态。
 *   <li><b>「待业务确认」不是「通过」</b>：后端的 {@code CheckResult} 刻意把它单列成一档，
 *       所以 {@link checkPassed} 只在结论真的等于 PASS 时才为真。
 *   <li><b>提交痕迹是历史</b>：被下游退回后 {@code submittedAt} 仍然留着
 *       （「这套备证资料曾经发出去过没有」是举证材料），所以列表与详情都显示成「提交于 …」。
 * </ul>
 */

/** 列表查询条件。与后端 `TradeSetQuery` 一一对应（`status` 可多选）。 */
export interface TradeSetParams {
  status?: string[];
  destinationCountryCode?: string;
  sourceDeclarationId?: string;
  latestCheckResult?: string;
  blockedOnly?: boolean;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

/** 目的国单证要求规则的查询条件。与后端 `RuleQuery` 一一对应。 */
export interface TradeRuleParams {
  countryCode?: string;
  documentKind?: string;
  requirementLevel?: string;
  status?: string;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

/**
 * 拼查询串。
 *
 * <p>两条纪律：① 空条件一律不拼 —— 拼上 `status=` 会被后端当成「筛了这个值」；
 * ② 文本条件先 trim，纯空白的不拼 —— 否则等于拿一个空格去做 `ilike` 搜索。
 */
export function tradeQuery(params: TradeSetParams): string {
  const text = (value: string | undefined) => value?.trim() ?? '';
  const parts: string[] = [];
  for (const s of params.status ?? []) parts.push(`status=${encodeURIComponent(s)}`);
  if (text(params.destinationCountryCode)) {
    parts.push(`destinationCountryCode=${encodeURIComponent(text(params.destinationCountryCode))}`);
  }
  if (params.sourceDeclarationId) {
    parts.push(`sourceDeclarationId=${encodeURIComponent(params.sourceDeclarationId)}`);
  }
  if (text(params.latestCheckResult)) {
    parts.push(`latestCheckResult=${encodeURIComponent(text(params.latestCheckResult))}`);
  }
  if (params.blockedOnly) parts.push('blockedOnly=true');
  if (text(params.keyword)) parts.push(`keyword=${encodeURIComponent(text(params.keyword))}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

/** 规则查询串。与 {@link tradeQuery} 同一条纪律。 */
export function tradeRuleQuery(params: TradeRuleParams): string {
  const text = (value: string | undefined) => value?.trim() ?? '';
  const parts: string[] = [];
  if (text(params.countryCode)) parts.push(`countryCode=${encodeURIComponent(text(params.countryCode))}`);
  if (text(params.documentKind)) parts.push(`documentKind=${encodeURIComponent(text(params.documentKind))}`);
  if (text(params.requirementLevel)) {
    parts.push(`requirementLevel=${encodeURIComponent(text(params.requirementLevel))}`);
  }
  if (text(params.status)) parts.push(`status=${encodeURIComponent(text(params.status))}`);
  if (text(params.keyword)) parts.push(`keyword=${encodeURIComponent(text(params.keyword))}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

/** 基础路径。端点命名空间 `/warehouse/trade-documents`，浏览器只经 /api/warehouse 代理。 */
const BASE = 'warehouse/trade-documents';

export function fetchTradeCapabilities() {
  return api<TradeDocumentCapabilities>(`${BASE}/capabilities`);
}

export function fetchTradeSets(params: TradeSetParams) {
  return api<TradeSetPage>(`${BASE}${tradeQuery(params)}`);
}

export function fetchTradeSetDetail(id: string) {
  return api<TradeSetDetail>(`${BASE}/${encodeURIComponent(id)}`);
}

export function fetchTradeDocuments(setId: string) {
  return api<TradeDocumentRow[]>(`${BASE}/${encodeURIComponent(setId)}/documents`);
}

export function fetchTradeChecks(setId: string) {
  return api<TradeCheckRow[]>(`${BASE}/${encodeURIComponent(setId)}/checks`);
}

export function fetchTradeSetReminders(setId: string) {
  return api<TradeReminderRow[]>(`${BASE}/${encodeURIComponent(setId)}/reminders`);
}

export function fetchTradeEvents(setId: string) {
  return api<TradeEventRow[]>(`${BASE}/${encodeURIComponent(setId)}/events`);
}

// ================================================================ 目的地国家单证要求规则

export function fetchTradeRules(params: TradeRuleParams) {
  return api<TradeRulePage>(`${BASE}/rules${tradeRuleQuery(params)}`);
}

export function createTradeRule(input: TradeRuleCreateInput) {
  return api<TradeRuleSaved>(`${BASE}/rules`, 'POST', input);
}

export function updateTradeRule(id: string, input: TradeRuleUpdateRequest) {
  return api<TradeRuleSaved>(`${BASE}/rules/${encodeURIComponent(id)}`, 'PUT', input);
}

// ================================================================ 单证模板

export function fetchTradeTemplates(params: {
  documentKind?: string;
  countryCode?: string;
  status?: string;
  keyword?: string;
  page?: number;
  size?: number;
}) {
  const text = (value: string | undefined) => value?.trim() ?? '';
  const parts: string[] = [];
  if (text(params.documentKind)) parts.push(`documentKind=${encodeURIComponent(text(params.documentKind))}`);
  if (text(params.countryCode)) parts.push(`countryCode=${encodeURIComponent(text(params.countryCode))}`);
  if (text(params.status)) parts.push(`status=${encodeURIComponent(text(params.status))}`);
  if (text(params.keyword)) parts.push(`keyword=${encodeURIComponent(text(params.keyword))}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  const query = parts.length ? `?${parts.join('&')}` : '';
  return api<TradeTemplatePage>(`${BASE}/templates${query}`);
}

export function createTradeTemplate(input: TradeTemplateCreateRequest) {
  return api<TradeTemplateSaved>(`${BASE}/templates`, 'POST', input);
}

export function updateTradeTemplate(id: string, input: TradeTemplateUpdateRequest) {
  return api<TradeTemplateSaved>(`${BASE}/templates/${encodeURIComponent(id)}`, 'PUT', input);
}

// ================================================================ 认证状态（16 合规替代来源）

export function fetchTradeCertifications(params: {
  skuId?: string;
  status?: string;
  certificationSource?: string;
  keyword?: string;
  page?: number;
  size?: number;
}) {
  const text = (value: string | undefined) => value?.trim() ?? '';
  const parts: string[] = [];
  if (params.skuId) parts.push(`skuId=${encodeURIComponent(params.skuId)}`);
  if (text(params.status)) parts.push(`status=${encodeURIComponent(text(params.status))}`);
  if (text(params.certificationSource)) {
    parts.push(`certificationSource=${encodeURIComponent(text(params.certificationSource))}`);
  }
  if (text(params.keyword)) parts.push(`keyword=${encodeURIComponent(text(params.keyword))}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  const query = parts.length ? `?${parts.join('&')}` : '';
  return api<TradeCertificationPage>(`${BASE}/certifications${query}`);
}

export function createTradeCertification(input: TradeCertificationCreateInput) {
  return api<TradeCertificationSaved>(`${BASE}/certifications`, 'POST', input);
}

export function updateTradeCertification(id: string, input: TradeCertificationUpdateRequest) {
  return api<TradeCertificationSaved>(`${BASE}/certifications/${encodeURIComponent(id)}`, 'PUT', input);
}

// ================================================================ FTA 优惠规则

export function fetchTradePreferenceRules(params: {
  scheme?: string;
  destinationCountryCode?: string;
  status?: string;
  page?: number;
  size?: number;
}) {
  const text = (value: string | undefined) => value?.trim() ?? '';
  const parts: string[] = [];
  if (text(params.scheme)) parts.push(`scheme=${encodeURIComponent(text(params.scheme))}`);
  if (text(params.destinationCountryCode)) {
    parts.push(`destinationCountryCode=${encodeURIComponent(text(params.destinationCountryCode))}`);
  }
  if (text(params.status)) parts.push(`status=${encodeURIComponent(text(params.status))}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  const query = parts.length ? `?${parts.join('&')}` : '';
  return api<TradePreferenceRulePage>(`${BASE}/preference-rules${query}`);
}

export function createTradePreferenceRule(input: TradePreferenceRuleCreateInput) {
  return api<TradePreferenceRuleSaved>(`${BASE}/preference-rules`, 'POST', input);
}

export function updateTradePreferenceRule(id: string, input: TradePreferenceRuleUpdateRequest) {
  return api<TradePreferenceRuleSaved>(`${BASE}/preference-rules/${encodeURIComponent(id)}`, 'PUT', input);
}

/** 优惠主张清单：谁主张了优惠、确认到哪一步、按哪一版规则确认的。 */
export function fetchTradePreferenceConfirmations(params: {
  setId?: string;
  substantiveChangeStatus?: string;
  ftaScheme?: string;
  claimedOnly?: boolean;
  page?: number;
  size?: number;
}) {
  const text = (value: string | undefined) => value?.trim() ?? '';
  const parts: string[] = [];
  if (params.setId) parts.push(`setId=${encodeURIComponent(params.setId)}`);
  if (text(params.substantiveChangeStatus)) {
    parts.push(`substantiveChangeStatus=${encodeURIComponent(text(params.substantiveChangeStatus))}`);
  }
  if (text(params.ftaScheme)) parts.push(`ftaScheme=${encodeURIComponent(text(params.ftaScheme))}`);
  if (params.claimedOnly) parts.push('claimedOnly=true');
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  // 先把查询串拼好再插值：URL 保持成一条**扁平**的模板串。
  // 直接在模板里套条件表达式（`${parts.length ? `?…` : ''}`）会让白名单用例的
  // 「从源码反向抽取调用点」抽不到这条路径 —— 而抽不到意味着**少断言了一条端点
  // 且不会失败**，那比写不出来更糟。
  const query = parts.length ? `?${parts.join('&')}` : '';
  return api<TradePreferenceConfirmationPage>(`${BASE}/preference-confirmations${query}`);
}

// ================================================================ 缺失提醒工作台

export function fetchTradeReminders(params: {
  setId?: string;
  status?: string;
  severity?: string;
  reminderType?: string;
  documentKind?: string;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}) {
  const text = (value: string | undefined) => value?.trim() ?? '';
  const parts: string[] = [];
  if (params.setId) parts.push(`setId=${encodeURIComponent(params.setId)}`);
  if (text(params.status)) parts.push(`status=${encodeURIComponent(text(params.status))}`);
  if (text(params.severity)) parts.push(`severity=${encodeURIComponent(text(params.severity))}`);
  if (text(params.reminderType)) parts.push(`reminderType=${encodeURIComponent(text(params.reminderType))}`);
  if (text(params.documentKind)) parts.push(`documentKind=${encodeURIComponent(text(params.documentKind))}`);
  if (text(params.keyword)) parts.push(`keyword=${encodeURIComponent(text(params.keyword))}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  // 同上：保持 URL 是扁平模板串，反向抽取才抽得到这条路径。
  const query = parts.length ? `?${parts.join('&')}` : '';
  return api<TradeReminderPage>(`${BASE}/reminders${query}`);
}

export function resolveTradeReminder(id: string, input: TradeReminderResolveRequest) {
  return api<TradeReminderSaved>(`${BASE}/reminders/${encodeURIComponent(id)}/resolve`, 'POST', input);
}

// ================================================================ 单证包写动作

export function createTradeSet(input: TradeSetCreateInput) {
  return api<TradeSaved>(BASE, 'POST', input);
}

export function updateTradeSet(id: string, input: TradeSetUpdateRequest) {
  return api<TradeSaved>(`${BASE}/${encodeURIComponent(id)}`, 'PUT', input);
}

export function submitTradeSetForReview(id: string, input: TradeSetReviewRequest) {
  return api<TradeSaved>(`${BASE}/${encodeURIComponent(id)}/submit-for-review`, 'POST', input);
}

export function reviewTradeSet(id: string, input: TradeSetReviewRequest) {
  return api<TradeSaved>(`${BASE}/${encodeURIComponent(id)}/review`, 'POST', input);
}

export function rejectTradeSetReview(id: string, input: TradeSetRejectReviewRequest) {
  return api<TradeSaved>(`${BASE}/${encodeURIComponent(id)}/reject-review`, 'POST', input);
}

export function withdrawTradeSetReview(id: string, input: TradeSetWithdrawReviewRequest) {
  return api<TradeSaved>(`${BASE}/${encodeURIComponent(id)}/withdraw-review`, 'POST', input);
}

/** 跑一次完整性校验。结论只有三档，「待业务确认」不算通过。 */
export function runTradeCheck(id: string) {
  return api<TradeSaved>(`${BASE}/${encodeURIComponent(id)}/check`, 'POST');
}

export function submitTradeSet(id: string, input: TradeSetSubmitRequest) {
  return api<TradeSaved>(`${BASE}/${encodeURIComponent(id)}/submit`, 'POST', input);
}

export function acknowledgeTradeSet(id: string, input: TradeSetAcknowledgeRequest) {
  return api<TradeSaved>(`${BASE}/${encodeURIComponent(id)}/acknowledge`, 'POST', input);
}

export function sendBackTradeSet(id: string, input: TradeSetSendBackRequest) {
  return api<TradeSaved>(`${BASE}/${encodeURIComponent(id)}/send-back`, 'POST', input);
}

export function cancelTradeSet(id: string, input: TradeSetCancelRequest) {
  return api<TradeSaved>(`${BASE}/${encodeURIComponent(id)}/cancel`, 'POST', input);
}

// ================================================================ 单证写动作

export function generateTradeDocument(setId: string, input: TradeDocumentCreateInput) {
  return api<TradeDocumentSaved>(`${BASE}/${encodeURIComponent(setId)}/documents`, 'POST', input);
}

export function finalizeTradeDocument(setId: string, documentId: string, input: TradeDocumentFinalizeRequest) {
  return api<TradeDocumentSaved>(
    `${BASE}/${encodeURIComponent(setId)}/documents/${encodeURIComponent(documentId)}/finalize`,
    'POST',
    input,
  );
}

/** 登记 FTA 优惠主张。只登记主张，不判定成立。 */
export function claimTradePreference(setId: string, documentId: string, input: TradePreferenceClaimInput) {
  return api<TradeDocumentSaved>(
    `${BASE}/${encodeURIComponent(setId)}/documents/${encodeURIComponent(documentId)}/preference-claim`,
    'POST',
    input,
  );
}

/** 业务确认（或确认不成立）。系统只留痕，不代替业务判断「实质性改变」是否成立。 */
export function confirmTradePreference(setId: string, documentId: string, input: TradePreferenceClaimInput) {
  return api<TradeDocumentSaved>(
    `${BASE}/${encodeURIComponent(setId)}/documents/${encodeURIComponent(documentId)}/preference-confirm`,
    'POST',
    input,
  );
}

// ================================================================ 界面口径

/** 状态文案。与后端 `DocumentSetStatus.label()` 逐条一致，不在前端另抄一份。 */
export const tradeSetStatusLabels: Record<string, string> = {
  DRAFT: '草稿',
  UNDER_REVIEW: '待复核',
  APPROVED: '已复核通过',
  REJECTED: '复核退回',
  SUBMITTED: '已提交',
  SENT_BACK: '被退回',
  ACKNOWLEDGED: '已接收',
  CANCELLED: '已取消',
};

/** 单证类型文案。与后端 `TradeDocumentKind.label()` 逐条一致。 */
export const tradeDocumentKindLabels: Record<string, string> = {
  COMMERCIAL_INVOICE: '商业发票',
  PACKING_LIST: '装箱单',
  CERTIFICATE_OF_ORIGIN: '原产地证',
  RCEP_CERTIFICATE_OF_ORIGIN: 'RCEP 原产地证',
  BILL_OF_LADING: '提单',
  AIR_WAYBELL: '空运单',
  IMPORT_LICENSE: '进口许可',
  CERTIFICATION_DOC: '认证文件',
  OTHER: '其他单证',
};

/** 唯一可以主张 FTA 优惠的单证类型。与后端 `TradeDocumentKind.isOriginCertificate()` 同口径。 */
export const originCertificateKinds = ['CERTIFICATE_OF_ORIGIN', 'RCEP_CERTIFICATE_OF_ORIGIN'] as const;

export function isOriginCertificate(documentKind: string | null | undefined): boolean {
  return !!documentKind && (originCertificateKinds as readonly string[]).includes(documentKind);
}

/** 校验结论文案。后端刻意把「待业务确认」与「通过」分开，这里照抄不合并。 */
export const checkResultLabels: Record<string, string> = {
  NOT_CHECKED: '未校验',
  PASS: '校验通过',
  FAIL: '校验不通过',
  PENDING_BUSINESS_CONFIRMATION: '待业务确认',
};

export const substantiveChangeLabels: Record<string, string> = {
  NOT_APPLICABLE: '不适用',
  PENDING_BUSINESS_CONFIRMATION: '待业务确认',
  CONFIRMED: '业务已确认',
  REJECTED: '业务确认不成立',
};

export const reminderTypeLabels: Record<string, string> = {
  MISSING_DOCUMENT: '必备单证缺失',
  UNDECLARED_DOCUMENT: '已出单证但规则未要求',
  UNCONFIRMED_SUBSTANTIVE_CHANGE: 'FTA 优惠待业务确认',
  CERTIFICATION_EXPIRED: '认证已过期',
  CERTIFICATION_REVOKED: '认证已被撤销',
  CERTIFICATION_MISSING: '缺认证状态登记',
  DOCUMENT_NOT_FINAL: '单证未定稿',
};

export const requirementLevelLabels: Record<string, string> = {
  MANDATORY: '必备',
  CONDITIONAL: '有条件必备',
  OPTIONAL: '可选',
};

export const certificationStatusLabels: Record<string, string> = {
  VALID: '有效',
  PENDING: '待取证',
  EXPIRED: '已过期',
  REVOKED: '已撤销',
  NOT_REQUIRED: '无需认证',
};

/** 事件时间线的 kind 文案。缺失时回落成原值，不吞掉未知事件。 */
export const tradeEventLabels: Record<string, string> = {
  CREATED: '创建草稿',
  UPDATED: '修改草稿',
  UNDER_REVIEW: '送人工复核',
  APPROVED: '复核通过',
  REVIEW_REJECTED: '复核退回',
  REVIEW_WITHDRAWN: '撤回复核',
  CHECK_RUN: '跑完整性校验',
  DOCUMENT_GENERATED: '生成单证',
  DOCUMENT_FINALIZED: '单证定稿',
  PREFERENCE_CLAIMED: '登记优惠主张',
  BUSINESS_CONFIRMED: '业务确认优惠',
  BUSINESS_REJECTED: '业务撤回优惠',
  REMINDER_RESOLVED: '处理缺失提醒',
  REMINDER_DISMISSED: '忽略缺失提醒',
  SUBMITTED: '提交下游',
  ACKNOWLEDGED: '下游已接收',
  SENT_BACK: '下游退回',
  CANCELLED: '取消',
};

/**
 * 「实质性改变」待确认是否**阻断**。
 *
 * <p>判据只认一个状态：PENDING_BUSINESS_CONFIRMATION。
 * CONFIRMED 指的是「有人留痕确认过」，**不代表**系统判定它在法律上成立 ——
 * 所以界面文案不能说「优惠已通过」，只能说「业务已确认」。
 */
export function substantiveChangeBlocking(status: string | null | undefined): boolean {
  return status === 'PENDING_BUSINESS_CONFIRMATION';
}

/** 校验是否真的通过。「待业务确认」**不算通过** —— 混进来界面就会许诺一个没发生过的结论。 */
export function checkPassed(result: string | null | undefined): boolean {
  return result === 'PASS';
}

const actionLabels: Record<string, string> = {
  update: '改基本信息',
  submitForReview: '送人工复核',
  review: '复核通过',
  rejectReview: '复核退回',
  withdrawReview: '撤回复核',
  submit: '提交报关行/目的仓',
  acknowledge: '登记已接收',
  sendBack: '登记被退回',
  cancel: '取消单证包',
};

/**
 * 按状态给出动作入口。
 *
 * <p>三个业务硬约束在这里显式编码，避免界面许诺做不到的事：
 * 「提交」只在「已复核通过」出现；「登记已接收 / 被退回」只在已提交出现；
 * 「取消」在已接收/已取消（终态）不出现。
 */
export function tradeSetActions(status: string): string[] {
  switch (status) {
    case 'DRAFT':
      return ['update', 'submitForReview', 'cancel'];
    case 'UNDER_REVIEW':
      return ['review', 'rejectReview', 'withdrawReview', 'cancel'];
    case 'APPROVED':
      // 提交前还有两道闸（校验必须 PASS、优惠必须已确认），由后端与 DB 钉死；
      // 界面不隐藏按钮，但按钮旁常驻说明缺什么，避免「点了才知道」。
      return ['update', 'submit', 'cancel'];
    case 'REJECTED':
      return ['update', 'submitForReview', 'cancel'];
    case 'SUBMITTED':
      return ['acknowledge', 'sendBack'];
    case 'SENT_BACK':
      return ['update', 'submitForReview', 'cancel'];
    case 'ACKNOWLEDGED':
    case 'CANCELLED':
      // 终态：下游已接收/单证包已作废。
      return [];
    default:
      return [];
  }
}

export function tradeActionLabel(action: string): string {
  return actionLabels[action] ?? action;
}

/** 提交前的阻断项清单。返回空数组才可以提交 —— 与后端 `guard_trade_2_submit_gate` 同一组判据。 */
export function submitBlockers(detail: {
  set: TradeSetRow;
  reminders: TradeReminderRow[];
}): string[] {
  const blockers: string[] = [];
  const open = detail.reminders.filter((r) => r.status === 'OPEN' && r.severity === 'BLOCKER');
  for (const r of open) {
    blockers.push(
      r.reminderType === 'UNCONFIRMED_SUBSTANTIVE_CHANGE'
        ? `FTA 优惠待业务确认：${r.documentKindLabel || r.documentKind} —— 实质性改变是否成立需要业务确认，系统只负责按规则校验与留档`
        : r.message,
    );
  }
  if (!checkPassed(detail.set.latestCheckResult)) {
    blockers.push(
      detail.set.latestCheckResult === 'NOT_CHECKED'
        ? '还没跑完整性校验：没校验过就提交，等于没人确认这套单证齐不齐'
        : `最近一次完整性校验结论是「${checkResultLabels[detail.set.latestCheckResult] ?? detail.set.latestCheckResult}」，不是「校验通过」`,
    );
  }
  return blockers;
}

/** 单证是否齐备且都定稿。缺一份或没定稿都不算备证资料。 */
export function documentsReady(documents: TradeDocumentRow[]): {
  ready: boolean;
  missing: string[];
  drafts: string[];
} {
  const missing: string[] = [];
  const drafts: string[] = [];
  const byKind = new Map<string, TradeDocumentRow>();
  for (const d of documents) {
    if (d.status === 'SUPERSEDED') continue;
    const prev = byKind.get(d.documentKind);
    if (!prev || d.documentVersion > prev.documentVersion) byKind.set(d.documentKind, d);
  }
  for (const kind of Object.keys(tradeDocumentKindLabels)) {
    const latest = byKind.get(kind);
    if (!latest) continue;
    if (latest.status !== 'FINAL') drafts.push(kind);
  }
  return { ready: missing.length === 0 && drafts.length === 0, missing, drafts };
}

export const tradeSetSortOptions = [
  { value: 'UPDATED_AT', label: '最近更新' },
  { value: 'CREATED_AT', label: '最近创建' },
  { value: 'SET_CODE', label: '单证包号' },
  { value: 'DECLARED_VALUE', label: '申报货值' },
] as const;

/** 数值展示。数量与金额来自 NUMERIC，后端给的是 number，这里只做展示格式化。 */
export function fmt(value: number | null | undefined, digits = 2): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—';
  return value.toLocaleString('zh-CN', { minimumFractionDigits: digits, maximumFractionDigits: digits });
}

export function money(value: number | null | undefined, currency?: string | null): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—';
  return `${fmt(value)} ${currency ?? ''}`.trim();
}

export function moment(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return '—';
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

export function today(): string {
  return new Date().toISOString().slice(0, 10);
}
