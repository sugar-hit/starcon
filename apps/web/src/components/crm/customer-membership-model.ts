import type {
  CrmMembershipBenefitConfig,
  CrmMembershipBenefitConfigRow,
  CrmMembershipCapabilities,
  CrmMembershipComputeResult,
  CrmMembershipLevelChangePage,
  CrmMembershipLevelChangeRow,
  CrmMembershipLevelRulePage,
  CrmMembershipLevelRuleRow,
  CrmMembershipMemberDetail,
  CrmMembershipMemberPage,
  CrmMembershipMemberRow,
  CrmMembershipRunPage,
  CrmMembershipStats,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 15.6.1.1 会员与等级前端模型。
 *
 * <p>蓝图：「管理会员体系：会员注册与合并、等级规则（按累计消费/订单数/复购率自动升级降级）、
 * 等级权益（折扣、免邮、优先发货），支持与平台会员体系对接或独立建档。
 * 输入：订单与消费数据（9）；等级规则配置；平台会员数据同步。
 * 输出：会员档案 + 等级变更记录；权益生效配置（→ 5 价目 / 14 促销）。
 * 关系：上游 = 9 订单、15.3.1.2 客户档案；下游 = 5 定价、14 促销、15.6.2 复购。
 * **会员等级必须由真实消费数据驱动**，手工挂等级会导致权益成本失控。」
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>等级来源必须摆在脸上</b>：每一行都显示「规则算出来的」，
 *       页面**不提供**任何「手工指定等级」的入口 —— 那是后端刻意没有的写路径。</li>
 *   <li><b>「未达任何等级」是一等状态</b>：不能画成「普通会员」，
 *       它意味着这位客户**没有**达到任何一档门槛。</li>
 *   <li><b>判定理由要能看见</b>：每一行带 {@code levelReason}（凭什么落这一级），
 *       等级变更带 {@code basis}（判定依据快照）。</li>
 *   <li><b>金额不跨币种相加</b>：分币种一行，页面**不做**任何前端金额算术。</li>
 *   <li><b>「不可判定」不是 0</b>：复购率在窗口内没有订单时是 null，
 *       页面显示「不可判定」而不是「0%」。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态要区分「筛选无结果」与「还没有算过」。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code crm/customer-membership}，与同服务的 {@code crm/customers}
 * （15.3.1.1）、{@code crm/customer-lifecycle}（15.6.2.1）、{@code crm/customer-merge}
 * （15.3.1.3）**刻意不共用**。
 */

const base = 'crm/customer-membership';

/**
 * 筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface MembershipMemberParams {
  levelCode?: string | undefined;
  sourceShop?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface MembershipLevelRuleParams {
  levelCode?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface MembershipLevelChangeParams {
  memberKey?: string | undefined;
  changeKind?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 等级代码的可读名。 */
export function membershipLevelLabel(code: string | undefined): string {
  if (code === 'NONE') return '未达任何等级';
  return code ?? '';
}

/**
 * 等级来源的可读名。
 *
 * <p>🔴 `MANUAL_OVERRIDE` **本轮未交付** —— 后端没有写路径，DB 层也拒绝它。
 * 页面**不提供**任何设置等级的入口，所以这个分支实际上永远走不到；
 * 但一旦有人改了后端，这一行会立刻把「人工覆盖」显示出来，而不是混进普通会员。
 */
export function membershipOriginLabel(code: string | undefined): string {
  switch (code) {
    case 'RULE':
      return '规则算出来的（真实消费数据驱动）';
    case 'MANUAL_OVERRIDE':
      return '人工覆盖（本轮未交付）';
    default:
      return code ?? '';
  }
}

/** 等级变更性质的可读名。 */
export function membershipChangeKindLabel(code: string | undefined): string {
  switch (code) {
    case 'REGISTERED':
      return '首次建档定级';
    case 'UPGRADE':
      return '升级';
    case 'DOWNGRADE':
      return '降级';
    default:
      return code ?? '';
  }
}

/**
 * 金额的显示文本（**一行一个币种**，不做任何前端算术）。
 *
 * <p>「USD 1.00」比裸数字更诚实 —— 金额是带币种的，
 * 页面**不**把它换算成别的币种（换算口径不在本模块）。
 */
export function membershipMonetaryText(
  amount: string | number | null | undefined,
  currency: string | null | undefined,
): string {
  if (amount === null || amount === undefined || amount === '') return '—';
  if (!currency) return String(amount);
  return `${currency} ${amount}`;
}

/**
 * 复购率的显示文本。
 *
 * <p>🔴 窗口内没有订单时是 **null（不可判定）**，不是 0 ——
 * 画成「0%」会被读成「这个人一次都没复购」，而真相是「这个窗口里他没下过单」。
 */
export function membershipRepurchaseText(rate: string | number | null | undefined): string {
  if (rate === null || rate === undefined || rate === '') {
    return '不可判定（判定窗口内没有订单）';
  }
  return `${rate}`;
}

/**
 * 等级门槛的显示文本。
 *
 * <p>🔴 三条门槛的**口径**必须一起显示：运营只看「累计消费 ≥ 5000」会以为那是全平台口径，
 * 实际它还带着币种、订单数与复购率。
 */
export function membershipThresholdsText(rule: {
  minTotalSpend?: string | number | null;
  minTotalSpendCurrency?: string | null;
  minOrderCount?: number | null;
  minRepurchaseRate?: string | number | null;
}): string {
  const parts: string[] = [];
  if (rule.minTotalSpend !== null && rule.minTotalSpend !== undefined) {
    parts.push(`累计消费 ≥ ${rule.minTotalSpend} ${rule.minTotalSpendCurrency ?? ''}`.trim());
  }
  if (rule.minOrderCount !== null && rule.minOrderCount !== undefined) {
    parts.push(`订单数 ≥ ${rule.minOrderCount}`);
  }
  if (rule.minRepurchaseRate !== null && rule.minRepurchaseRate !== undefined) {
    parts.push(`复购率 ≥ ${rule.minRepurchaseRate}`);
  }
  return parts.length ? parts.join(' 且 ') : '无门槛（任何有真实消费的会员都命中）';
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
export function membershipPercentText(rate: string | number | null | undefined): string {
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
 * 等级权益的显示文本（折扣 / 免邮 / 优先发货）。
 *
 * <p>🔴 折扣走 {@link membershipPercentText}（字符串移位），**不**做浮点乘法。
 */
export function membershipBenefitText(row: {
  discountRate?: string | number | null;
  freeShipping?: boolean;
  priorityShipping?: boolean;
}): string {
  const parts: string[] = [];
  if (row.discountRate !== null && row.discountRate !== undefined && row.discountRate !== '') {
    parts.push(`减免 ${membershipPercentText(row.discountRate)}%`);
  }
  if (row.freeShipping) parts.push('免邮');
  if (row.priorityShipping) parts.push('优先发货');
  return parts.length ? parts.join(' · ') : '无权益';
}

/** 会员主键的显示文本（已合并是逻辑客户号，未合并是「店铺:客户标识」）。 */
export function membershipMemberKeyText(row: {
  memberKey?: string | null;
  logicalRef?: string | null;
  memberCount?: number | null;
}): string {
  const key = row.memberKey ?? '—';
  if (row.logicalRef) {
    return `${row.logicalRef}（合并了 ${row.memberCount ?? 1} 个来源客户行）`;
  }
  return key;
}

/**
 * 空态文案：**区分**「筛选无结果」与「还没有算过」。
 *
 * <p>这两种空态要的行动完全不同：前者是「把筛选条件放宽」，
 * 后者是「先从上游重算一次」。
 */
export function membershipEmptyStateText(
  filtered: boolean,
  hasRuns: boolean,
): { title: string; description: string } {
  if (filtered) {
    return {
      title: '没有命中任何会员',
      description: '当前筛选条件下没有会员。放宽筛选条件再试一次 —— 这不代表这个租户没有会员。',
    };
  }
  if (!hasRuns) {
    return {
      title: '还没有算过会员等级',
      description:
        '会员档案来自上游 9 订单的真实订单事实（经授权 HTTP API 只读）。点「重算会员等级」从上游拉一次。',
    };
  }
  return {
    title: '还没有算过会员等级',
    description: '上一次重算没有产出会员档案（判定窗口内没有订单，或那次重算失败了）。',
  };
}

/** 等级变更记录的空态文案。 */
export function membershipLevelChangeEmptyStateText(filtered: boolean): {
  title: string;
  description: string;
} {
  if (filtered) {
    return {
      title: '没有命中任何等级变更',
      description: '当前筛选条件下没有等级变更记录。放宽筛选条件再试一次。',
    };
  }
  return {
    title: '还没有等级变更记录',
    description: '等级只在「首次建档定级」「升级」「降级」时留痕。重算一次就会产生记录。',
  };
}

/** 会员统计的一句话说明（**不受分页影响**，后端单独一条查询算出来的）。 */
export function membershipStatsNote(stats: CrmMembershipStats | undefined): string {
  if (!stats) return '';
  return `会员 ${stats.totalMembers} 位 · 未达任何等级 ${stats.unmatchedMembers} 位 · 升级 ${stats.upgradedMembers} 次 · 降级 ${stats.downgradedMembers} 次。${stats.note ?? ''}`;
}

/** 等级规则列表的一句话说明。 */
export function membershipLevelRuleNote(rule: CrmMembershipLevelRuleRow): string {
  const state = rule.effectiveNow ? '生效中' : rule.enabled ? '未生效' : '已停用';
  return `${rule.levelName}（${rule.levelCode}，等级序 ${rule.levelRank}，${state}）：${membershipThresholdsText(rule)}`;
}

/** 权益生效配置的一行说明（**给下游 5 定价 / 14 促销看的**）。 */
export function membershipBenefitConfigNote(row: CrmMembershipBenefitConfigRow): string {
  return `${row.levelName}（${row.levelCode}）：${row.benefitSummary ?? membershipBenefitText(row)}；生效条件 ${row.appliesTo ?? '—'}`;
}

// ================================================================ 请求

export function fetchMembershipCapabilities() {
  return api<CrmMembershipCapabilities>(`${base}/capabilities`);
}

export function fetchMembershipMembers(params: MembershipMemberParams = {}) {
  return api<CrmMembershipMemberPage>(`${base}/members${query({ ...params })}`);
}

export function fetchMembershipMemberDetail(memberId: string) {
  return api<CrmMembershipMemberDetail>(`${base}/members/${memberId}`);
}

export function fetchMembershipLevelRules(params: MembershipLevelRuleParams = {}) {
  return api<CrmMembershipLevelRulePage>(`${base}/levels${query({ ...params })}`);
}

export function createMembershipLevelRule(input: {
  levelCode: string;
  levelName: string;
  levelRank: number;
  minTotalSpend?: number | undefined;
  minTotalSpendCurrency?: string | undefined;
  minOrderCount?: number | undefined;
  minRepurchaseRate?: number | undefined;
  discountRate?: number | undefined;
  freeShipping?: boolean | undefined;
  priorityShipping?: boolean | undefined;
  enabled?: boolean | undefined;
  effectiveFrom: string;
  effectiveTo?: string | undefined;
  note?: string | undefined;
}) {
  return api<CrmMembershipLevelRuleRow>(`${base}/levels`, 'POST', input);
}

export function updateMembershipLevelRule(
  ruleId: string,
  input: {
    levelCode: string;
    levelName: string;
    levelRank: number;
    minTotalSpend?: number | undefined;
    minTotalSpendCurrency?: string | undefined;
    minOrderCount?: number | undefined;
    minRepurchaseRate?: number | undefined;
    discountRate?: number | undefined;
    freeShipping?: boolean | undefined;
    priorityShipping?: boolean | undefined;
    enabled?: boolean | undefined;
    effectiveFrom: string;
    effectiveTo?: string | undefined;
    note?: string | undefined;
  },
) {
  return api<CrmMembershipLevelRuleRow>(`${base}/levels/${ruleId}`, 'PUT', input);
}

export function fetchMembershipBenefits() {
  return api<CrmMembershipBenefitConfig>(`${base}/benefits`);
}

export function fetchMembershipLevelChanges(params: MembershipLevelChangeParams = {}) {
  return api<CrmMembershipLevelChangePage>(`${base}/level-changes${query({ ...params })}`);
}

export function computeMembership(input: { windowFrom?: string; windowTo?: string } = {}) {
  return api<CrmMembershipComputeResult>(`${base}/compute`, 'POST', input);
}

export function fetchMembershipRuns() {
  return api<CrmMembershipRunPage>(`${base}/runs?size=20`);
}

/** 拼查询串（**空值一律不拼** —— 拼一个空的 levelCode= 会把筛选悄悄变成「等级为空」）。 */
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
  CrmMembershipBenefitConfig,
  CrmMembershipBenefitConfigRow,
  CrmMembershipCapabilities,
  CrmMembershipComputeResult,
  CrmMembershipLevelChangePage,
  CrmMembershipLevelChangeRow,
  CrmMembershipLevelRulePage,
  CrmMembershipLevelRuleRow,
  CrmMembershipMemberDetail,
  CrmMembershipMemberPage,
  CrmMembershipMemberRow,
  CrmMembershipRunPage,
  CrmMembershipStats,
};
