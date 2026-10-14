import type {
  PartyDetail,
  PartyInput,
  PartyOverview,
  PartyPage,
  PartyRole,
  PartyRoleEditInput,
  PartyRoleInput,
  PartyRow,
  PartySaved,
  PartyStatusInput,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/**
 * 3.6.1.1 往来单位档案前端模型。
 *
 * <p>这个页面上最容易出错的三处：
 *
 * <ul>
 *   <li><b>「同时是供应商和客户」必须在界面上看得见</b>。蓝图那句「同一主体可同时是供应商和客户，
 *       用角色档案区分，不用多份记录」是本模块存在的理由；如果角色只藏在详情里，
 *       运营在列表上看到一个既是供应商又是客户的主体时会以为系统数据错了，
 *       于是去建第二条记录 —— 那正是本模块要消灭的形态。
 *       所以列表每行都带 `roleSummary`，且它是后端给的，前端不重算。
 *   <li><b>可用性判定不能在前端重算</b>。`usable` / `usableBlockCode` 一律来自后端
 *       （`PartyRules.usableBlock` 是唯一实现）。前端只把 `usableBlockCode` 翻成
 *       要显示的话。复制一份判定到浏览器里的症状是「页面说这个供应商可选、
 *       采购单说不可选」，而这种事只有真下单那天才会暴露。
 *   <li><b>「停用」和「删除」是两件事</b>。停用保留行、历史单据照常显示；
 *       删除打软删除标记、行仍在（采购单 / 应付 / 售后单都要引用这个 id）。
 *       界面上给的是「停用 / 启用」与「删除」两个不同动作，不能都写成「不用了」。
 * </ul>
 *
 * <p>本文件的校验与 `PartyRules` 逐字同口径。抄一份到前端的理由是
 * 「提交前就少一次无谓的往返」，而不是为了绕过服务层 —— 服务层那份才是权威。
 */

/** 主体类型词表：与 V27 的 CHECK 和 `PartyRules.PARTY_TYPES` 同一口径。 */
export const PARTY_TYPES = [
  { value: 'ORGANIZATION', label: '企业', hint: '法人主体：开票、结算、报关都以它为准' },
  { value: 'INDIVIDUAL', label: '个人', hint: '自然人主体：B2C 场景里就是收件人本人' },
] as const;

export type PartyType = (typeof PARTY_TYPES)[number]['value'];

/** 主体状态词表：可用 / 停用 / 拉黑。 */
export const PARTY_STATUSES = [
  { value: 'ACTIVE', label: '可用', hint: '可以用于新单据' },
  { value: 'INACTIVE', label: '停用', hint: '不再用于新单据，历史单据照常显示' },
  { value: 'BLACKLISTED', label: '拉黑', hint: '制裁名单 / 纠纷对手方；进出都要填理由' },
] as const;

export type PartyStatus = (typeof PARTY_STATUSES)[number]['value'];

/** 角色词表：供应商 / 客户 / 承运商。蓝图职责点名的三个身份。 */
export const PARTY_ROLES = [
  { value: 'SUPPLIER', label: '供应商', hint: '06 采购、12 应付会引用这个身份' },
  { value: 'CUSTOMER', label: '客户', hint: '09 订单、11 售后会引用这个身份' },
  { value: 'CARRIER', label: '承运商', hint: '10 物流与头程会引用这个身份' },
] as const;

export type PartyRoleType = (typeof PARTY_ROLES)[number]['value'];

export function partyTypeLabel(value: string | null | undefined): string {
  return PARTY_TYPES.find((item) => item.value === value)?.label ?? value ?? '未知';
}

export function statusLabel(value: string | null | undefined): string {
  return PARTY_STATUSES.find((item) => item.value === value)?.label ?? value ?? '未知';
}

export function statusHint(value: string | null | undefined): string {
  return PARTY_STATUSES.find((item) => item.value === value)?.hint ?? '';
}

export function roleLabel(value: string | null | undefined): string {
  return PARTY_ROLES.find((item) => item.value === value)?.label ?? value ?? '未知';
}

/** 状态流转表：与 `PartyRules.STATUS_TRANSITIONS` 逐字一致。界面用它把不可点的按钮直接禁用。 */
export const STATUS_TRANSITIONS: Record<PartyStatus, PartyStatus[]> = {
  ACTIVE: ['INACTIVE', 'BLACKLISTED'],
  INACTIVE: ['ACTIVE', 'BLACKLISTED'],
  BLACKLISTED: ['INACTIVE', 'ACTIVE'],
};

/** 某状态下可以流转到的目标。空数组意味着没有可用的流转动作。 */
export function allowedTargets(from: string | null | undefined): PartyStatus[] {
  if (from === 'ACTIVE' || from === 'INACTIVE' || from === 'BLACKLISTED') {
    return STATUS_TRANSITIONS[from];
  }
  return ['ACTIVE', 'INACTIVE', 'BLACKLISTED'];
}

/**
 * 这次流转是否必须填理由（进出拉黑都要）。
 *
 * <p>与 `PartyRules.requiresReason` 同一口径：界面提前提示，而不是让用户提交后吃一个 400。
 */
export function requiresReason(from: string, to: string): boolean {
  return from === 'BLACKLISTED' || to === 'BLACKLISTED';
}

/** 流转理由为空时的提示文案；不需要理由时返回 null。 */
export function reasonHint(from: string, to: string): string | null {
  if (!requiresReason(from, to)) return null;
  return '进出拉黑状态必须填写理由：三个月后没人能记得这个对手方为什么被拉黑，也没人敢决定要不要解开它。';
}

/** 不可用原因码 → 中文标签。与 `PartyRules.usableBlockLabel` 逐字同口径。 */
export const USABLE_BLOCK_LABELS: Record<string, string> = {
  PARTY_INACTIVE: '已停用',
  PARTY_BLACKLISTED: '已拉黑',
  PARTY_NO_ROLE: '未指派角色',
  PARTY_DELETED: '已删除',
};

export function usableBlockLabel(code: string | null | undefined): string {
  if (!code) return '可用';
  return USABLE_BLOCK_LABELS[code] ?? '不可用';
}

/** 列表页筛选项：可用性三态。 */
export const USABLE_FILTERS = [
  { value: '', label: '可用性：全部' },
  { value: 'true', label: '只看可用' },
  { value: 'false', label: '只看不可用' },
] as const;

export type UsableFilter = '' | 'true' | 'false';

// ------------------------------------------------------------ 接口

export type PartyListQuery = {
  keyword: string;
  partyType: string;
  status: string;
  roleType: string;
  countryCode: string;
  includeDeleted: boolean;
  usable: boolean | null;
  page: number;
  size: number;
};

export function fetchOverview() {
  return api<PartyOverview>('catalog/parties/overview');
}

export function fetchParties(query: PartyListQuery) {
  const params = new URLSearchParams();
  if (query.keyword.trim()) params.set('keyword', query.keyword.trim());
  if (query.partyType) params.set('partyType', query.partyType);
  if (query.status) params.set('status', query.status);
  if (query.roleType) params.set('roleType', query.roleType);
  if (query.countryCode.trim()) params.set('countryCode', query.countryCode.trim());
  if (query.includeDeleted) params.set('includeDeleted', 'true');
  if (query.usable !== null) params.set('usable', String(query.usable));
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  return api<PartyPage>(`catalog/parties?${params.toString()}`);
}

export function fetchDetail(partyId: string) {
  return api<PartyDetail>(`catalog/parties/${partyId}`);
}

export function createParty(input: PartyInput) {
  return api<PartySaved>('catalog/parties', 'POST', input);
}

export function saveParty(partyId: string, input: PartyInput) {
  return api<PartySaved>(`catalog/parties/${partyId}`, 'PUT', input);
}

/**
 * 状态流转：走独立端点而不是复用保存。
 *
 * <p>理由是流转要带 `reason`（进出拉黑必填），混在保存里的话「改个名字」也会被迫填理由。
 */
export function setPartyStatus(partyId: string, input: PartyStatusInput) {
  return api<PartySaved>(`catalog/parties/${partyId}/status`, 'PUT', input);
}

/** 软删除：行仍在，只是打上删除标记（采购单 / 应付 / 售后单都要引用这个 id）。 */
export function deleteParty(partyId: string, version: number) {
  return api<{ deleted: boolean }>(`catalog/parties/${partyId}?version=${version}`, 'DELETE');
}

export function fetchRoles(partyId: string) {
  return api<PartyRole[]>(`catalog/parties/${partyId}/roles`);
}

export function createRole(partyId: string, input: PartyRoleEditInput) {
  return api<PartySaved>(`catalog/parties/${partyId}/roles`, 'POST', input);
}

export function saveRole(partyId: string, roleId: string, input: PartyRoleEditInput) {
  return api<PartySaved>(`catalog/parties/${partyId}/roles/${roleId}`, 'PUT', input);
}

/** 解除角色指派。角色行可以物理删除：它是「当前事实」，没有回溯需求。 */
export function removeRole(partyId: string, roleId: string, version: number) {
  return api<PartySaved>(`catalog/parties/${partyId}/roles/${roleId}?version=${version}`, 'DELETE');
}

/** 06 采购 / 11 售后 / 12 财务选往来单位时调的那一个端点。 */
export function fetchCandidates(roleType: string, keyword: string, includeBlocked: boolean) {
  const params = new URLSearchParams();
  if (roleType) params.set('roleType', roleType);
  if (keyword.trim()) params.set('keyword', keyword.trim());
  if (includeBlocked) params.set('includeBlocked', 'true');
  const query = params.toString();
  return api<PartyRow[]>(`catalog/parties/candidates${query ? `?${query}` : ''}`);
}

// ------------------------------------------------------------ 校验（与服务层同口径）

/** 编码校验。与 `PartyRules.codeProblem` 同一套规则。 */
export function codeProblem(value: string): string | null {
  const code = value.trim().toUpperCase();
  if (!code) return '往来单位编码不能为空';
  if (code.length > 64) return '往来单位编码最多 64 个字符';
  if (!/^[A-Z0-9][A-Z0-9_-]{0,63}$/.test(code))
    return '往来单位编码请使用英文字母、数字、下划线或短横线，长度 1~64 位';
  return null;
}

export function nameProblem(value: string): string | null {
  const name = value.trim();
  if (!name) return '往来单位名称不能为空';
  if (name.length > 200) return '往来单位名称最多 200 个字符';
  return null;
}

export function partyTypeProblem(value: string): string | null {
  if (!value.trim()) return '主体类型不能为空';
  if (!PARTY_TYPES.some((item) => item.value === value.trim()))
    return '主体类型只能是企业（ORGANIZATION）或个人（INDIVIDUAL）';
  return null;
}

export function countryProblem(value: string | null | undefined): string | null {
  const code = (value ?? '').trim().toUpperCase();
  if (!code) return null;
  if (!/^[A-Z]{2}$/.test(code)) return '国家代码请用 ISO 3166-1 alpha-2 两位代码，如 CN / DE / US';
  return null;
}

export function emailProblem(value: string | null | undefined): string | null {
  const email = (value ?? '').trim();
  if (!email) return null;
  if (email.length > 254) return '邮箱最多 254 个字符';
  if (email.includes(' ')) return '邮箱不能包含空格';
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email))
    return '邮箱格式不正确，请填写类似 purchasing@example.com 的地址';
  return null;
}

export function phoneProblem(value: string | null | undefined): string | null {
  const phone = (value ?? '').trim().replace(/ /g, '');
  if (!phone) return null;
  if (phone.length > 64) return '电话最多 64 个字符';
  if (!/^[+0-9()\-.]+$/.test(phone))
    return '电话只能包含数字与 + - ( ) . ，请填写如 +86-138-0000-0000（分机请用 - 连接）';
  return null;
}

export function websiteProblem(value: string | null | undefined): string | null {
  const site = (value ?? '').trim();
  if (!site) return null;
  if (site.length > 500) return '官网最多 500 个字符';
  if (site.includes(' ')) return '官网不能包含空格，请填写完整 URL 或不含空格的域名';
  return null;
}

/**
 * 联系方式整体校验：邮箱与电话**至少给一项**。
 *
 * <p>与 `PartyRules.contactProblem` 同一口径。官网不能替代邮箱 / 电话 ——
 * 官网是查规格用的，联系不上人对履约没有帮助。
 */
export function contactProblem(
  email: string | null | undefined,
  phone: string | null | undefined,
  website: string | null | undefined,
): string | null {
  return (
    emailProblem(email) ??
    phoneProblem(phone) ??
    websiteProblem(website) ??
    ((email ?? '').trim() === '' && (phone ?? '').trim() === ''
      ? '邮箱与电话至少填写一项：06 采购问交期、11 售后问退货、12 财务对账都要靠它联系到这个往来单位，' +
        '两项都空的档案在任何一张单据上都无法使用'
      : null)
  );
}

/** 角色指派校验：词表 + 同一角色只能一条。与 `PartyRules.rolesProblem` 同一口径。 */
export function rolesProblem(roles: (PartyRoleInput | null)[]): string | null {
  if (roles.length === 0) return null;
  if (roles.length > 10) return '单个往来单位最多指派 10 个角色';
  const seen = new Set<string>();
  for (const role of roles) {
    // 契约里数组元素带 `| null`（Jackson 允许显式 null），空项直接当「删掉这条」处理。
    if (role === null) continue;
    const type = role.roleType.trim().toUpperCase();
    if (!type) return '角色类型不能为空';
    if (!PARTY_ROLES.some((item) => item.value === type))
      return '角色类型只能是供应商（SUPPLIER）、客户（CUSTOMER）或承运商（CARRIER）';
    if (role.status !== 'ACTIVE' && role.status !== 'INACTIVE')
      return '角色状态只能是 ACTIVE 或 INACTIVE';
    if ((role.note ?? '').length > 2000) return '角色备注最多 2000 个字符';
    if (seen.has(type))
      return (
        `同一个角色只能指派一次：${roleLabel(type)}` +
        '（一个主体可以同时是供应商和客户，但每个身份只有一条档案）'
      );
    seen.add(type);
  }
  return null;
}

/**
 * 整个表单的校验：返回第一条可执行的原因，没有则 null。
 *
 * <p>与后端的校验<b>同源但不是同一份</b>：后端那份是权威（`PartyRules`），
 * 这里那份只为「提交前少一次无谓的往返」。两处口径必须逐字一致 ——
 * 前端放过、后端拒掉的现象是用户填完点保存才看到一条莫名其妙的红字。
 */
export function partyInputProblem(
  input: Pick<
    PartyInput,
    | 'code'
    | 'name'
    | 'partyType'
    | 'countryCode'
    | 'email'
    | 'phone'
    | 'website'
    | 'status'
    | 'roles'
  >,
): string | null {
  return (
    codeProblem(input.code) ??
    nameProblem(input.name) ??
    partyTypeProblem(input.partyType) ??
    countryProblem(input.countryCode) ??
    contactProblem(input.email, input.phone, input.website) ??
    (input.status ? null : '状态不能为空') ??
    rolesProblem(input.roles ?? []) ??
    // 规则 P5：状态为可用时至少要有一个使用中的角色，否则这条档案在下游任何一处都选不出来。
    (input.status === 'ACTIVE' && !hasActiveRole(input.roles ?? [])
      ? '状态为「可用」时至少要指派一个角色：没有角色的往来单位在 06 采购、11 售后、12 财务的' +
        '下拉里谁都选不出意义。不想当场定角色的，先建成「停用」，挂好角色再启用'
      : null)
  );
}

/** 提交的角色里有没有使用中的（ACTIVE）角色。 */
export function hasActiveRole(roles: (PartyRoleInput | null)[]): boolean {
  return roles.some((role) => role !== null && role.status === 'ACTIVE');
}

/** 状态流转的校验：方向合法 + 进出拉黑必须有理由。与 `PartyRules.transitionProblem` 同一口径。 */
export function transitionProblem(from: string, to: string, reason: string): string | null {
  if (!to) return '状态不能为空';
  if (!PARTY_STATUSES.some((item) => item.value === to))
    return '状态只能是可用（ACTIVE）、停用（INACTIVE）或拉黑（BLACKLISTED）';
  if (from === to) return `状态没有变化（${statusLabel(from)} → ${statusLabel(to)}）`;
  if (!allowedTargets(from).includes(to as PartyStatus))
    return (
      `不允许的状态流转：${statusLabel(from)} → ${statusLabel(to)}。` +
      `可用流转为 ${allowedTargets(from).map(statusLabel).join(' / ')}`
    );
  if (requiresReason(from, to) && !reason.trim())
    return '进出拉黑状态必须填写理由：三个月后没人能记得这个对手方为什么被拉黑，也没人敢决定要不要解开它';
  if (reason.length > 500) return '状态理由最多 500 个字符';
  return null;
}

/** 角色指派的校验（单条端点用）。与 `PartyRules.assignRoleProblem` 同一口径。 */
export function assignRoleProblem(
  partyStatus: string,
  roleType: string,
  already: boolean,
): string | null {
  if (partyStatus === 'BLACKLISTED')
    return (
      '该往来单位在拉黑名单里，不能再指派新角色：请先解除拉黑' +
      '（停用状态可以正常指派角色 —— 那是「先建主体、挂好角色、再启用」的正常流程）'
    );
  if (already) return `该主体已经指派过${roleLabel(roleType)}角色，请直接编辑那一条而不是重复指派`;
  return null;
}

/** 删除前的校验：必须先解掉全部角色。与 `PartyRules.deleteProblem` 同一口径。 */
export function deleteProblem(roles: PartyRole[]): string | null {
  if (roles.length === 0) return null;
  return (
    `该往来单位还挂着角色（${roles
      .map((role) => role.roleLabel)
      .join('、')}），请先解除这些角色指派再删除：删除后主体对列表不可见，` +
    '而角色行还在，那种状态没人能解释'
  );
}

// ------------------------------------------------------------ 表单

/** 空表单初值。状态默认停用：先建主体、挂好角色、再启用是正常流程。 */
export function emptyPartyInput(): PartyInput {
  return {
    code: '',
    name: '',
    partyType: 'ORGANIZATION',
    countryCode: null,
    email: null,
    phone: null,
    website: null,
    status: 'INACTIVE',
    roles: [],
    version: 0,
  };
}

/** 空角色初值。 */
export function emptyRoleInput(): PartyRoleEditInput {
  return { roleType: 'SUPPLIER', status: 'ACTIVE', note: null, version: 0 };
}

/** 详情 → 表单初值。 */
export function toPartyInput(detail: PartyDetail): PartyInput {
  return {
    code: detail.code,
    name: detail.name,
    partyType: detail.partyType,
    countryCode: detail.countryCode,
    email: detail.email,
    phone: detail.phone,
    website: detail.website,
    status: detail.status,
    // 整体替换语义：表单必须带上当前全集，否则「只改个名字」会把别人的角色改动抹掉。
    roles: (detail.roles ?? []).map((role) => ({
      roleType: role.roleType,
      status: role.status,
      note: role.note,
    })),
    version: detail.version,
  };
}

export function toRoleInput(role: PartyRole): PartyRoleEditInput {
  return {
    roleType: role.roleType,
    status: role.status,
    note: role.note,
    version: role.version,
  };
}

/**
 * 已指派但提交集合里没有的角色 = 会被解指派。
 *
 * <p>界面上必须显式提示：角色是**整体替换**语义，提交什么就等于什么，
 * 少发一条就是静默的身份丢失 —— 而「我明明在编辑名称，怎么供应商身份没了」
 * 是最难排查的一类问题。
 */
export function removedRoles(
  current: PartyRole[],
  submitted: (PartyRoleInput | null)[],
): PartyRole[] {
  const kept = new Set(
    submitted
      .filter((role): role is NonNullable<typeof role> => role !== null)
      .map((role) => role.roleType),
  );
  return current.filter((role) => !kept.has(role.roleType));
}

/** 列表里一行角色的展示：后端已给出 roleSummary，前端不重算，只在缺省时兜底。 */
export function rowRoleSummary(row: Pick<PartyRow, 'roles' | 'roleSummary'>): string {
  return row.roleSummary || '未指派角色';
}

/** 空态与筛选态的提示文案：两种空态文案不同，别混。 */
export function emptyHint(filtered: boolean) {
  return filtered
    ? '当前筛选条件下没有往来单位。往来单位是租户级主数据，先建一条才能在采购、售后与财务里引用。'
    : '这个租户还没有往来单位。一个对手方只要建一条记录，再按需指派供应商 / 客户 / 承运商身份，' +
        '不要为每个身份各建一条。';
}

/** 错误文案：后端给的是可执行的中文原因，这里只补上上下文。 */
export function errorText(error: unknown, fallback = '往来单位数据加载失败') {
  if (error instanceof ApiError && error.message) return error.message;
  return fallback;
}

export type { PartyDetail, PartyRow };
