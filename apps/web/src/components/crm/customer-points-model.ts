import type {
  CrmPointsAccountDetail,
  CrmPointsAccountPage,
  CrmPointsAccountRow,
  CrmPointsCapabilities,
  CrmPointsEntitlementPage,
  CrmPointsEntitlementRow,
  CrmPointsEarnResult,
  CrmPointsExpireResult,
  CrmPointsLedgerPage,
  CrmPointsLedgerRow,
  CrmPointsLiability,
  CrmPointsRedeemResult,
  CrmPointsRedemptionPage,
  CrmPointsRedemptionRow,
  CrmPointsRulePage,
  CrmPointsRuleRow,
  CrmPointsSpendResult,
  CrmPointsStats,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 15.6.1.2 积分与权益前端模型。
 *
 * <p>蓝图：「管理积分与权益：积分获取（消费/评价/签到）与消耗（抵扣/兑换）、
 * 积分余额与有效期、权益核销；积分负债需财务确认。
 * 输入：积分规则；获取/消耗事件；权益核销。
 * 输出：积分账户与流水；权益核销记录；积分负债汇总（→ 12）。
 * 关系：上游 = 15.6.1.1、9 订单；下游 = 5 定价（积分抵扣）、12 财务（积分负债）。
 * **未兑付积分是负债**，需在财务侧有对应科目或备忘，不能只记在业务库。」
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>积分比率是租户数据</b>：页面显示的每一档规则都写着「拿多少分 / 抵多少比例 /
 *       单积分值多少 / 有效期多久」—— 代码里没有任何写死的比率，页面也不假装有。</li>
 *   <li><b>余额与流水可审阅</b>：流水行显示「变动前余额 → 变动后余额」，
 *       页面**不做**任何前端积分算术。</li>
 *   <li><b>「不可判定」不是 0</b>：单积分价值没配时，负债金额是「—」而不是 0。</li>
 *   <li><b>负债如实标注</b>：负债卡片必须显示「12 财务侧未交付积分负债科目，
 *       本模块只输出汇总、<b>未</b>入账」—— 页面**不假装**已经对账。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态要区分「筛选无结果」与「还没有发过积分」。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code crm/points}，与同服务的 {@code crm/customer-membership}
 * （15.6.1.1 会员与等级）**刻意不共用**。
 */

const base = 'crm/points';

/**
 * 筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface PointsAccountParams {
  memberKey?: string | undefined;
  sourceShop?: string | undefined;
  keyword?: string | undefined;
  onlyWithBalance?: boolean | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface PointsRuleParams {
  ruleType?: string | undefined;
  ruleCode?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface PointsLedgerParams {
  memberKey?: string | undefined;
  direction?: string | undefined;
  reasonCode?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface PointsEntitlementParams {
  entitlementCode?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface PointsRedemptionParams {
  memberKey?: string | undefined;
  entitlementCode?: string | undefined;
  status?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 流水方向的可读名。 */
export function pointsDirectionLabel(code: string | undefined): string {
  switch (code) {
    case 'EARN':
      return '获取';
    case 'SPEND':
      return '消耗';
    case 'EXPIRE':
      return '过期';
    default:
      return code ?? '';
  }
}

/** 流水事由的可读名。 */
export function pointsReasonLabel(code: string | undefined): string {
  switch (code) {
    case 'PURCHASE':
      return '消费获取';
    case 'REVIEW':
      return '评价获取';
    case 'CHECKIN':
      return '签到获取';
    case 'MANUAL':
      return '人工发放';
    case 'DEDUCT':
      return '订单抵扣';
    case 'REDEEM':
      return '权益兑换';
    case 'EXPIRE':
      return '到期过期';
    default:
      return code ?? '';
  }
}

/** 获取来源的可读名。 */
export function pointsEarnSourceLabel(code: string | undefined): string {
  switch (code) {
    case 'PURCHASE':
      return '消费获取';
    case 'REVIEW':
      return '评价获取';
    case 'CHECKIN':
      return '签到获取';
    case 'MANUAL':
      return '人工发放';
    default:
      return code ?? '';
  }
}

/** 规则类型的可读名。 */
export function pointsRuleTypeLabel(code: string | undefined): string {
  switch (code) {
    case 'EARN':
      return '获取规则';
    case 'SPEND':
      return '消耗规则（抵扣）';
    case 'VALUATION':
      return '估值规则（单积分价值）';
    default:
      return code ?? '';
  }
}

/** 权益形态的可读名。 */
export function pointsBenefitTypeLabel(code: string | undefined): string {
  switch (code) {
    case 'DISCOUNT':
      return '折扣';
    case 'FREE_SHIPPING':
      return '免邮';
    case 'PHYSICAL_GIFT':
      return '实物';
    case 'COUPON':
      return '优惠券';
    default:
      return code ?? '';
  }
}

/** 核销状态的可读名。 */
export function pointsRedemptionStatusLabel(code: string | undefined): string {
  switch (code) {
    case 'REDEEMED':
      return '已兑换待核销';
    case 'USED':
      return '已核销';
    default:
      return code ?? '';
  }
}

/**
 * 积分数的显示文本（**不做任何前端算术**）。
 *
 * <p>🔴 后端出参是**十进制文本**（如 {@code "300"} / {@code "0.5"}），
 * 页面**原样显示** —— 页面若自己 `Number()` 一遍再格式化，
 * 「库里那个数的十进制写法」就丢了（`300.0000` 会变成 `300`，
 * 而积分抵扣配置要交给 5 定价，写法会一路传下去）。
 */
export function pointsText(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  return String(value);
}

/**
 * 比率（十进制文本）→ 百分比文本（**字符串移位，不碰浮点**）。
 *
 * <p>🔴 为什么不用 `Number(rate) * 100`：那是浮点乘法，
 * {@code 0.07 * 100} 在 IEEE 754 下是 {@code 7.000000000000001} ——
 * 界面上就会出现「减免 7.000000000000001%」。
 * 比率是**判定口径**，显示出来的那个数必须与后端给的十进制文本一致。
 *
 * <p>移位规则：把小数点右移两位（不足补零），再去掉多余的尾零。
 * {@code 0.05 → 5}、{@code 0.075 → 7.5}、{@code 0.07 → 7}、{@code 1.5 → 150}。
 */
export function pointsPercentText(rate: string | number | null | undefined): string {
  if (rate === null || rate === undefined || rate === '') return '—';
  const text = String(rate).trim();
  const match = /^([+-]?)(\d*)(?:\.(\d*))?$/.exec(text);
  if (!match) return text;
  const sign = match[1] === '-' ? '-' : '';
  const intPart = match[2] ?? '';
  const fracPart = match[3] ?? '';
  let digits = intPart + fracPart;
  const pointAt = intPart.length + 2;
  if (digits.length < pointAt) digits = digits.padEnd(pointAt, '0');
  const head = digits.slice(0, pointAt).replace(/^0+(?=\d)/, '');
  const tail = digits.slice(pointAt).replace(/0+$/, '');
  return `${sign}${head}${tail ? `.${tail}` : ''}`;
}

/**
 * 权益快照里取一个字段的显示文本。
 *
 * <p>🔴 快照是 jsonb（后端出参类型是 `{ }`），页面**不能**直接索引它的属性
 * （TS 会报「属性不存在」），所以走这个安全取值器。
 */
export function pointsSnapshotValue(snapshot: unknown, key: string): string {
  if (!snapshot || typeof snapshot !== 'object') return '—';
  const value = (snapshot as Record<string, unknown>)[key];
  if (value === null || value === undefined || value === '') return '—';
  return String(value);
}

/**
 * 有效期文字。
 *
 * <p>🔴 `expiresAt` 为 null ⇒ **永不过期**（不是「没查到到期时间」）。
 */
export function pointsExpiryText(expiresAt: string | null | undefined): string {
  if (expiresAt === null || expiresAt === undefined || expiresAt === '') return '永不过期';
  return `${expiresAt} 到期`;
}

/** 积分账户主键的显示文本。 */
export function pointsAccountKeyText(row: {
  accountKey?: string | null;
  customerName?: string | null;
}): string {
  const name = row.customerName?.trim();
  const key = row.accountKey ?? '—';
  return name ? `${name}（${key}）` : key;
}

/** 积分账户一行的余额摘要（余额 + 累计三口径）。 */
export function pointsBalanceText(row: {
  balance?: string | null;
  earnedTotal?: string | null;
  spentTotal?: string | null;
  expiredTotal?: string | null;
}): string {
  return `余额 ${pointsText(row.balance)} · 累计获取 ${pointsText(row.earnedTotal)} · 累计消耗 ${pointsText(
    row.spentTotal,
  )} · 累计过期 ${pointsText(row.expiredTotal)}`;
}

/**
 * 空态文案：**区分**「筛选无结果」与「还没有发过积分」。
 *
 * <p>这两种空态要的行动完全不同：前者是「把筛选条件放宽」，
 * 后者是「先发一次积分」。
 */
export function pointsEmptyStateText(filtered: boolean): { title: string; description: string } {
  if (filtered) {
    return {
      title: '没有命中任何积分账户',
      description:
        '当前筛选条件下没有积分账户。放宽筛选条件再试一次 —— 这不代表这个租户没有积分账户。',
    };
  }
  return {
    title: '还没有积分账户',
    description:
      '积分账户在**第一次发放积分**时按真实会员身份建档。发一次积分（消费 / 评价 / 签到 / 人工）就会出现在这里。',
  };
}

/** 积分流水的空态文案。 */
export function pointsLedgerEmptyStateText(filtered: boolean): {
  title: string;
  description: string;
} {
  if (filtered) {
    return {
      title: '没有命中任何积分流水',
      description: '当前筛选条件下没有积分流水。放宽筛选条件再试一次。',
    };
  }
  return {
    title: '还没有积分流水',
    description: '每次发放 / 消耗 / 过期都会留下一条流水，带「变动前余额 → 变动后余额」。',
  };
}

/** 权益核销记录的空态文案。 */
export function pointsRedemptionEmptyStateText(filtered: boolean): {
  title: string;
  description: string;
} {
  if (filtered) {
    return {
      title: '没有命中任何权益核销记录',
      description: '当前筛选条件下没有权益核销记录。放宽筛选条件再试一次。',
    };
  }
  return {
    title: '还没有权益核销记录',
    description: '用积分兑换一项权益就会产生一条「已兑换待核销」记录。',
  };
}

/** 积分统计的一句话说明（**不受分页影响**，后端单独一条查询算出来的）。 */
export function pointsStatsNote(stats: CrmPointsStats | undefined): string {
  if (!stats) return '';
  return (
    `账户 ${stats.totalAccounts} 个 · 有余额 ${stats.accountsWithBalance} 个 · ` +
    `未兑付积分 ${pointsText(stats.outstandingPoints)} 分 · ` +
    `${stats.expiringLotCount} 批积分将在 30 天内到期（${pointsText(stats.expiringPoints)} 分） · ` +
    `流水 ${stats.ledgerCount} 条。${stats.note ?? ''}`
  );
}

/** 积分规则列表的一句话说明。 */
export function pointsRuleNote(rule: CrmPointsRuleRow): string {
  const state = rule.effectiveNow ? '生效中' : rule.enabled ? '未生效' : '已停用';
  const parts: string[] = [`${rule.ruleName}（${rule.ruleCode}，${state}）`];
  if (rule.ruleType === 'EARN') {
    parts.push(`来源 ${pointsEarnSourceLabel(rule.earnSource ?? undefined)}`);
    if (rule.pointsPerUnit) parts.push(`每 1 单位 ${pointsText(rule.pointsPerUnit)} 分`);
    if (rule.fixedPoints) parts.push(`固定 ${pointsText(rule.fixedPoints)} 分`);
    if (rule.thresholdAmount) {
      parts.push(`门槛 ${pointsText(rule.thresholdAmount)} ${rule.thresholdCurrency ?? ''}`);
    }
    parts.push(rule.validMonths ? `有效期 ${rule.validMonths} 个月` : '永不过期');
    if (rule.maxPerDay) parts.push(`每日上限 ${pointsText(rule.maxPerDay)} 分`);
  } else if (rule.ruleType === 'SPEND') {
    parts.push(`抵扣率上限 ${pointsPercentText(rule.maxDeductRate)}%`);
    if (rule.minDeductPoints) parts.push(`单笔最少 ${pointsText(rule.minDeductPoints)} 分`);
  } else if (rule.ruleType === 'VALUATION') {
    parts.push(`每 1 分折算 ${pointsText(rule.pointValueRate)} ${rule.liabilityCurrency ?? ''}`);
  }
  return parts.join(' · ');
}

/** 权益目录列表的一句话说明。 */
export function pointsEntitlementNote(row: CrmPointsEntitlementRow): string {
  const state = row.effectiveNow ? '生效中' : row.enabled ? '未生效' : '已停用';
  const parts = [
    `${row.entitlementName}（${row.entitlementCode}，${state}）`,
    `${pointsText(row.pointCost)} 分`,
    pointsBenefitTypeLabel(row.benefitType),
  ];
  if (row.benefitValue) parts.push(`${pointsText(row.benefitValue)} ${row.benefitCurrency ?? ''}`);
  if (row.stock !== null && row.stock !== undefined) parts.push(`库存 ${row.stock}`);
  if (row.perMemberLimit) parts.push(`每人限兑 ${row.perMemberLimit} 份`);
  return parts.join(' · ');
}

/**
 * 积分负债卡片的一句话说明。
 *
 * <p>🔴 **负债是否已在财务侧入账**必须写在脸上：页面不能让人以为
 * 「业务库里有一个数 = 账上有一笔负债」。
 */
export function pointsLiabilityNote(liability: CrmPointsLiability | undefined): string {
  if (!liability) return '';
  const booked = liability.bookedInFinance ? '🔴 已在 12 财务侧入账。' : '🔴 未在 12 财务侧入账。';
  return (
    `未兑付积分 ${pointsText(liability.outstandingPoints)} 分 × 单积分价值 ` +
    `${pointsText(liability.pointValueRate)} ${liability.liabilityCurrency ?? ''} = ` +
    `负债 ${pointsText(liability.liabilityAmount)} ${liability.liabilityCurrency ?? ''}。` +
    `${booked}${liability.financeNote ?? ''}${liability.note ?? ''}`
  );
}

/** 权益核销记录一行的说明。 */
export function pointsRedemptionNote(row: CrmPointsRedemptionRow): string {
  return (
    `${row.entitlementName}（${row.entitlementCode}） · 消耗 ${pointsText(row.pointCost)} 分 · ` +
    `${pointsRedemptionStatusLabel(row.status)} · 核销号 ${row.redemptionNo}`
  );
}

// ================================================================ 请求

export function fetchPointsCapabilities() {
  return api<CrmPointsCapabilities>(`${base}/capabilities`);
}

export function fetchPointsAccounts(params: PointsAccountParams = {}) {
  return api<CrmPointsAccountPage>(`${base}/accounts${query({ ...params })}`);
}

export function fetchPointsAccountDetail(accountId: string) {
  return api<CrmPointsAccountDetail>(`${base}/accounts/${accountId}`);
}

export function fetchPointsRules(params: PointsRuleParams = {}) {
  return api<CrmPointsRulePage>(`${base}/rules${query({ ...params })}`);
}

export function createPointsRule(input: {
  ruleCode: string;
  ruleName: string;
  ruleType: string;
  earnSource?: string | undefined;
  pointsPerUnit?: string | undefined;
  fixedPoints?: string | undefined;
  thresholdAmount?: string | undefined;
  thresholdCurrency?: string | undefined;
  validMonths?: number | undefined;
  maxPerDay?: string | undefined;
  maxDeductRate?: string | undefined;
  minDeductPoints?: string | undefined;
  pointValueRate?: string | undefined;
  liabilityCurrency?: string | undefined;
  enabled?: boolean | undefined;
  effectiveFrom: string;
  effectiveTo?: string | undefined;
  note?: string | undefined;
}) {
  return api<CrmPointsRuleRow>(`${base}/rules`, 'POST', input);
}

export function updatePointsRule(
  ruleId: string,
  input: {
    ruleCode: string;
    ruleName: string;
    ruleType: string;
    enabled?: boolean | undefined;
    effectiveFrom: string;
    effectiveTo?: string | undefined;
    note?: string | undefined;
  },
) {
  return api<CrmPointsRuleRow>(`${base}/rules/${ruleId}`, 'PUT', input);
}

export function fetchPointsEntitlements(params: PointsEntitlementParams = {}) {
  return api<CrmPointsEntitlementPage>(`${base}/entitlements${query({ ...params })}`);
}

export function createPointsEntitlement(input: {
  entitlementCode: string;
  entitlementName: string;
  pointCost: string;
  benefitType: string;
  benefitValue?: string | undefined;
  benefitCurrency?: string | undefined;
  stock?: number | undefined;
  perMemberLimit?: number | undefined;
  enabled?: boolean | undefined;
  effectiveFrom: string;
  effectiveTo?: string | undefined;
  note?: string | undefined;
}) {
  return api<CrmPointsEntitlementRow>(`${base}/entitlements`, 'POST', input);
}

export function fetchPointsLedger(params: PointsLedgerParams = {}) {
  return api<CrmPointsLedgerPage>(`${base}/ledger${query({ ...params })}`);
}

export function fetchPointsRedemptions(params: PointsRedemptionParams = {}) {
  return api<CrmPointsRedemptionPage>(`${base}/redemptions${query({ ...params })}`);
}

export function earnPoints(input: {
  memberKey: string;
  earnSource: string;
  referenceId?: string | undefined;
  ruleCode?: string | undefined;
  points?: string | undefined;
  occurredAt?: string | undefined;
  note?: string | undefined;
}) {
  return api<CrmPointsEarnResult>(`${base}/earn`, 'POST', input);
}

export function spendPoints(input: {
  memberKey: string;
  orderId: string;
  points?: string | undefined;
  ruleCode?: string | undefined;
  occurredAt?: string | undefined;
  note?: string | undefined;
}) {
  return api<CrmPointsSpendResult>(`${base}/spend`, 'POST', input);
}

export function redeemPoints(input: {
  memberKey: string;
  entitlementCode: string;
  occurredAt?: string | undefined;
  note?: string | undefined;
}) {
  return api<CrmPointsRedeemResult>(`${base}/redeem`, 'POST', input);
}

export function usePointsRedemption(
  redemptionId: string,
  input: { orderRef?: string | undefined; note?: string | undefined } = {},
) {
  return api<CrmPointsRedemptionRow>(`${base}/redemptions/${redemptionId}/use`, 'POST', input);
}

export function expirePoints() {
  return api<CrmPointsExpireResult>(`${base}/expire`, 'POST', {});
}

export function fetchPointsLiability() {
  return api<CrmPointsLiability>(`${base}/liability`);
}

/** 拼查询串（**空值一律不拼** —— 拼一个空的 memberKey= 会把筛选悄悄变成「会员为空」）。 */
function query(params: Record<string, unknown>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

export type {
  CrmPointsAccountDetail,
  CrmPointsAccountPage,
  CrmPointsAccountRow,
  CrmPointsCapabilities,
  CrmPointsEntitlementPage,
  CrmPointsEntitlementRow,
  CrmPointsEarnResult,
  CrmPointsExpireResult,
  CrmPointsLedgerPage,
  CrmPointsLedgerRow,
  CrmPointsLiability,
  CrmPointsRedeemResult,
  CrmPointsRedemptionPage,
  CrmPointsRedemptionRow,
  CrmPointsRulePage,
  CrmPointsRuleRow,
  CrmPointsSpendResult,
  CrmPointsStats,
};
