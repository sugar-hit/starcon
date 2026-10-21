import type {
  CrmReviewAssignmentInput,
  CrmReviewAssignmentPage,
  CrmReviewAssignmentRow,
  CrmReviewEffectComparison,
  CrmReviewEffectivePolicy,
  CrmReviewHolidayInput,
  CrmReviewHolidayPage,
  CrmReviewHolidayRow,
  CrmReviewRuleCapabilities,
  CrmReviewRuleCandidate,
  CrmReviewRuleCandidatePage,
  CrmReviewRuleConfigInput,
  CrmReviewRulePage,
  CrmReviewRuleRow,
  CrmReviewRuleShopView,
  CrmReviewTemplateInput,
  CrmReviewTemplatePage,
  CrmReviewTemplateRow,
  CrmReviewTemplateVariantInput,
  CrmReviewVariantRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 15.2.2.2 索评规则前端模型。
 *
 * <p>蓝图：「配置索评策略：按店铺、按物流方式（自发货/平台仓）设置不同延后时间，避开节假日，
 * 支持 A/B 测试不同话术。」输出「生效策略 + 效果对比」。
 * 关系：上游 = 15.2.2.1；下游 = 15.1.1.2 发送。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>规则是「配置」，不是「一次索评的快照」</b>：改了规则之后<b>新</b>的索评请求按新策略走，
 *       已经落库的分配记录仍按当时的快照算。页面在规则卡片上要显示
 *       「已落地 N 次」，让运营知道改这条规则会影响哪些历史结论。</li>
 *   <li><b>生效策略只有一个答案</b>：同一范围同一优先级不允许有两条生效规则（数据库层挡住）。
 *       页面<b>不提供</b>「指定用哪条规则」的入口 —— 指定规则就绕过了「生效策略」这件事本身。</li>
 *   <li><b>A/B 是确定性的</b>：同一个种子永远落到同一个臂。页面在预览时要把
 *       「这次分到哪个臂」显示出来，并且说清「没有种子就说不出分到哪个臂」。</li>
 *   <li><b>节假日按规则时区换算</b>：页面显示顺延后的排期与命中的节假日，
 *       并且说清「顺延几天」是怎么算出来的（先按延后天数，再往后找第一个不是节假日的日子）。</li>
 *   <li><b>效果对比的分母是「已发出的索评」</b>：分母为 0 时报「不计算」而不是 0% ——
 *       0% 会被读成「发出去了但一个评价都没回来」，那是两件完全不同的事。</li>
 *   <li><b>下游 15.1.1.2 消息发送未交付</b>：本模块只<b>产出</b>话术与生效策略，
 *       页面<b>不提供</b>「已发送」按钮 —— 一个点了就返回成功的假按钮会让运营
 *       以为买家已经被索评过，而过度索评的代价正是买家投诉与平台处罚。</li>
 *   <li>加载 / 空 / 错误三态齐全。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code crm/review-rules}，与兄弟模块 {@code crm/review-requests}
 * （15.2.2.1 索评执行）与 {@code crm/knowledge}（15.4.2.1 客服知识库）<b>刻意不共用</b>：
 * 一个管配置、一个管执行、一个管内容。
 */

const base = 'crm/review-rules';

/**
 * 查询串拼装。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface RuleParams {
  shopId?: string | undefined;
  logisticsMode?: string | undefined;
  status?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface TemplateParams {
  keyword?: string | undefined;
  locale?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface HolidayParams {
  year?: string | undefined;
  region?: string | undefined;
  kind?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface AssignmentParams {
  shopId?: string | undefined;
  ruleId?: string | undefined;
  variantId?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface EffectiveParams {
  shopId?: string | undefined;
  logisticsMode?: string | undefined;
  at?: string | undefined;
  seedKey?: string | undefined;
}

export interface EffectParams {
  days?: number | undefined;
  shopId?: string | undefined;
  ruleId?: string | undefined;
}

/** 通用查询串：空值不拼进去。 */
function buildQuery(params: Record<string, string | number | undefined>): string {
  const parts: string[] = [];
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === '') continue;
    parts.push(`${key}=${encodeURIComponent(String(value))}`);
  }
  return parts.length ? `?${parts.join('&')}` : '';
}

/** 索评规则的筛选条件。 */
export function ruleQuery(params: RuleParams = {}): string {
  return buildQuery({
    shopId: params.shopId,
    logisticsMode: params.logisticsMode,
    status: params.status,
    keyword: params.keyword,
    page: params.page,
    size: params.size,
  });
}

/** 话术模板的筛选条件。 */
export function templateQuery(params: TemplateParams = {}): string {
  return buildQuery({
    keyword: params.keyword,
    locale: params.locale,
    page: params.page,
    size: params.size,
  });
}

/** 节假日的筛选条件。 */
export function holidayQuery(params: HolidayParams = {}): string {
  return buildQuery({
    year: params.year,
    region: params.region,
    kind: params.kind,
    page: params.page,
    size: params.size,
  });
}

/** 落地记录的筛选条件。 */
export function assignmentQuery(params: AssignmentParams = {}): string {
  return buildQuery({
    shopId: params.shopId,
    ruleId: params.ruleId,
    variantId: params.variantId,
    page: params.page,
    size: params.size,
  });
}

/** 生效策略预览的筛选条件。 */
export function effectiveQuery(params: EffectiveParams = {}): string {
  return buildQuery({
    shopId: params.shopId,
    logisticsMode: params.logisticsMode,
    at: params.at,
    seedKey: params.seedKey,
  });
}

/** 效果对比的筛选条件。 */
export function effectQuery(params: EffectParams = {}): string {
  return buildQuery({
    days: params.days,
    shopId: params.shopId,
    ruleId: params.ruleId,
  });
}

// ================================================================ 能力与目录

/** 能力声明：每一维说清「有没有、依据是什么、缺了会怎样」。 */
export function fetchRuleCapabilities() {
  return api<CrmReviewRuleCapabilities>(`${base}/capabilities`);
}

/** 店铺目录（来自上游 channel 的真实清单，不是前端写死的常量）。 */
export function fetchRuleShops() {
  return api<CrmReviewRuleShopView[]>(`${base}/shops`);
}

// ================================================================ 索评规则

/** 索评规则列表（真分页 + 真筛选）。 */
export function fetchRules(params: RuleParams = {}) {
  return api<CrmReviewRulePage>(`${base}${ruleQuery(params)}`);
}

/** 单条索评规则。 */
export function fetchRule(ruleId: string) {
  return api<CrmReviewRuleRow>(`${base}/${encodeURIComponent(ruleId)}`);
}

/** 新建索评规则。 */
export function createRule(input: CrmReviewRuleConfigInput) {
  return api<CrmReviewRuleRow>(`${base}`, 'POST', input);
}

/** 更新索评规则。 */
export function updateRule(ruleId: string, input: CrmReviewRuleConfigInput) {
  return api<CrmReviewRuleRow>(`${base}/${encodeURIComponent(ruleId)}`, 'PUT', input);
}

/**
 * 删除索评规则。
 *
 * <p>已被生效策略落地引用过的规则**删不掉**（数据库层会拒）——
 * 删掉之后效果对比里那一段历史会凭空消失，结论被改写而不是少了几条。
 */
export function deleteRule(ruleId: string) {
  return api<void>(`${base}/${encodeURIComponent(ruleId)}`, 'DELETE');
}

// ================================================================ 话术模板

/** 话术模板列表（含变体，真分页 + 真筛选）。 */
export function fetchTemplates(params: TemplateParams = {}) {
  return api<CrmReviewTemplatePage>(`${base}/templates${templateQuery(params)}`);
}

/** 单个话术模板。 */
export function fetchTemplate(templateId: string) {
  return api<CrmReviewTemplateRow>(`${base}/templates/${encodeURIComponent(templateId)}`);
}

/** 新建话术模板（含变体）。 */
export function createTemplate(input: CrmReviewTemplateInput) {
  return api<CrmReviewTemplateRow>(`${base}/templates`, 'POST', input);
}

/** 更新话术模板（变体按变体码对齐：多的删、少的增、同的改）。 */
export function updateTemplate(templateId: string, input: CrmReviewTemplateInput) {
  return api<CrmReviewTemplateRow>(
    `${base}/templates/${encodeURIComponent(templateId)}`,
    'PUT',
    input,
  );
}

/** 删除话术模板（连同它下面的变体）。 */
export function deleteTemplate(templateId: string) {
  return api<void>(`${base}/templates/${encodeURIComponent(templateId)}`, 'DELETE');
}

// ================================================================ 节假日

/** 节假日列表（真分页 + 真筛选）。 */
export function fetchHolidays(params: HolidayParams = {}) {
  return api<CrmReviewHolidayPage>(`${base}/holidays${holidayQuery(params)}`);
}

/** 新建节假日。 */
export function createHoliday(input: CrmReviewHolidayInput) {
  return api<CrmReviewHolidayRow>(`${base}/holidays`, 'POST', input);
}

/** 更新节假日。 */
export function updateHoliday(holidayId: string, input: CrmReviewHolidayInput) {
  return api<CrmReviewHolidayRow>(`${base}/holidays/${encodeURIComponent(holidayId)}`, 'PUT', input);
}

/** 删除节假日。 */
export function deleteHoliday(holidayId: string) {
  return api<void>(`${base}/holidays/${encodeURIComponent(holidayId)}`, 'DELETE');
}

// ================================================================ 生效策略与效果对比

/**
 * 生效策略（蓝图输出句的第一项）。
 *
 * <p>⚠️ 没命中规则时后端返回的是一份 `ruleId` 为空的策略（**不是** 404）：
 * 「这个范围还没有生效规则」本身就是一个必须能回答的问题。
 */
export function fetchEffective(params: EffectiveParams = {}) {
  return api<CrmReviewEffectivePolicy>(`${base}/effective${effectiveQuery(params)}`);
}

/** 生效策略落地记录列表（真分页 + 真筛选）。 */
export function fetchAssignments(params: AssignmentParams = {}) {
  return api<CrmReviewAssignmentPage>(`${base}/assignments${assignmentQuery(params)}`);
}

/** 单条落地记录（哪条索评请求用了哪个话术臂、排期到什么时候）。 */
export function fetchAssignment(assignmentId: string) {
  return api<CrmReviewAssignmentRow>(`${base}/assignments/${encodeURIComponent(assignmentId)}`);
}

/**
 * 可被应用策略的索评请求（上游 15.2.2.1 的索评任务）。
 *
 * <p>它们的**状态原样带出来**：被频次规则拒绝的请求也列出来并标明状态 ——
 * 「为什么这条不能分配话术」是运营需要看到的答案，
 * 而不是在列表里悄悄消失一条。
 */
export function fetchCandidates(params: AssignmentParams = {}) {
  return api<CrmReviewRuleCandidatePage>(`${base}/candidates${assignmentQuery(params)}`);
}

/**
 * 把当前生效策略应用到一条索评请求上。
 *
 * <p>只给「索评请求」与「物流方式」：规则由服务端按生效策略解析得出，
 * 运营不指定规则 —— 指定规则就绕过了「生效策略」这件事本身。
 */
export function applyPolicy(input: CrmReviewAssignmentInput) {
  return api<CrmReviewAssignmentRow>(`${base}/assignments`, 'POST', input);
}

/** 效果对比（蓝图输出句的第二项）。 */
export function fetchEffectComparison(params: EffectParams = {}) {
  return api<CrmReviewEffectComparison>(`${base}/effect-comparison${effectQuery(params)}`);
}

// ================================================================ 可读形态

/** 规则状态的可读名（与后端 DB 词表一一对应）。 */
export function ruleStatusLabel(status: string): string {
  switch (status) {
    case 'DRAFT':
      return '草稿';
    case 'ACTIVE':
      return '生效中';
    case 'PAUSED':
      return '已暂停';
    default:
      return status;
  }
}

/** 物流方式的可读名。 */
export function logisticsModeLabel(mode: string): string {
  switch (mode) {
    case 'ANY':
      return '不限';
    case 'SELF_FULFILLED':
      return '自发货';
    case 'PLATFORM_WAREHOUSE':
      return '平台仓';
    default:
      return mode;
  }
}

/** 模板 / 变体状态的可读名。 */
export function templateStatusLabel(status: string): string {
  switch (status) {
    case 'ACTIVE':
      return '启用中';
    case 'PAUSED':
      return '已停用';
    default:
      return status;
  }
}

/** 节假日类型的可读名。 */
export function holidayKindLabel(kind: string): string {
  switch (kind) {
    case 'HOLIDAY':
      return '法定节假日';
    case 'PROMOTION':
      return '大促';
    default:
      return kind;
  }
}

/** 索评请求状态的可读名（读 15.2.2.1 的词表）。 */
export function requestStatusLabel(status: string): string {
  switch (status) {
    case 'SCHEDULED':
      return '已排期';
    case 'SENT':
      return '已发出';
    case 'CANCELLED':
      return '已取消';
    case 'REJECTED':
      return '被频次规则拒绝';
    default:
      return status;
  }
}

/**
 * 回收率的可读形态：分母为 0 时后端报 null，这里显示「不计算」而不是 0%。
 *
 * <p>0% 会被读成「发出去了但一个评价都没回来」，那是两件完全不同的事。
 */
export function recoveryRateText(rate: number | null | undefined): string {
  if (rate === null || rate === undefined) return '不计算（没有已发出的索评）';
  return `${(rate * 100).toFixed(1)}%`;
}

/** 平均星级：没有回收时显示「暂无」而不是 0 星。 */
export function averageRatingText(rating: number | null | undefined): string {
  if (rating === null || rating === undefined) return '暂无';
  return `${rating} 星`;
}

/**
 * 本次分到的话术变体。
 *
 * <p>⚠️ 没有分桶种子时后端报 `pickedVariantId` 为空 —— **不能**随便挑一个显示：
 * 「说不出分到哪个臂」和「分到了 A」是两件完全不同的事。
 */
export function pickedVariant(
  policy: CrmReviewEffectivePolicy | null,
): CrmReviewVariantRow | null {
  if (!policy || !policy.pickedVariantId) return null;
  return policy.variants.find((variant) => variant.id === policy.pickedVariantId) ?? null;
}

/**
 * 生效策略的一行摘要（页面顶部那块「此刻谁说了算」）。
 *
 * <p>没命中规则时如实说「没有命中」，并把该去配什么写出来 ——
 * 返回一份看起来正常的默认值会让页面以为策略已经配好了。
 */
export function effectiveSummary(policy: CrmReviewEffectivePolicy | null): string {
  if (!policy) return '生效策略暂不可用。';
  if (!policy.ruleId) {
    return '没有命中任何生效规则：先配一条规则，否则索评没有时点可依。';
  }
  const variant = pickedVariant(policy);
  return (
    `命中规则 ${policy.ruleNo}（${policy.ruleName}，范围：${policy.matchedScope}）：` +
    `延后 ${policy.delayHours} 小时，话术 ${
      variant ? `变体 ${variant.variantCode}` : policy.templateName || '未绑定模板'
    }` +
    (policy.holidayAvoided
      ? `，已避开节假日「${policy.holidayName}」顺延 ${policy.shiftedDays} 天`
      : '')
  );
}

/**
 * 效果对比里一个变体的一行。
 *
 * <p>⚠️ 没有分配时显示「未分配」而不是 0 ——
 * 「没分配过」与「分配了但一个都没发出去」是两件不同的事。
 */
export function effectVariantText(row: {
  variantCode: string;
  templateName: string;
  assigned: number;
  sent: number;
  recovered: number;
  recoveryRate: number | null;
  averageRating: number | null;
}): string {
  const template = row.templateName ? `${row.templateName} · ` : '';
  if (row.assigned === 0) return `${template}变体 ${row.variantCode}：未分配`;
  return (
    `${template}变体 ${row.variantCode}：分配 ${row.assigned} · 已发出 ${row.sent} · ` +
    `回收 ${row.recovered} · 回收率 ${recoveryRateText(row.recoveryRate)} · ` +
    `平均星级 ${averageRatingText(row.averageRating)}`
  );
}
