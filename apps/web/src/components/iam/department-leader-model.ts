import type {
  ApprovalChainEntry,
  ApprovalChainView,
  DepartmentLeaderInput,
  DepartmentLeaderView,
  LeaderGrantInput,
  LeaderProxyInput,
  MemberView,
} from '../../../../../packages/api-contracts/src/catalog';
// 相对路径而不是 `@/lib/api`：根 vitest 配置没有 `@/*` 别名（那只在 Next 构建里解析），
// 用别名会让这个模型的单测在 vite 下加载失败。与 administration-model 保持同一套约定。
import { api } from '../../lib/api';

/**
 * 17.2.1.2 部门负责人前端模型。
 *
 * <p>负责人是**授权**而不是组织节点：谁负责、管多大、能批到多少、谁能代管与临时借用，
 * 都是一次授权决定，所以这几个字段必须一起呈现，不能让人只改「谁负责」而误以为授权口径没变。
 *
 * <p>蓝图点名「负责人可为空」，所以没有负责人的部门仍然出现在列表里并标出「需要兜底」——
 * 把它过滤掉等于让运营看不见自己漏配了审批人。
 */

/** 管理范围 → 中文。未知值回落到原值，不吞掉信息。 */
export const scopeLabels: Record<string, string> = {
  DEPARTMENT: '本部门',
  SUBTREE: '本部门及下级部门',
  TENANT: '全租户',
};

/** 审批链来源 → 中文。 */
export const sourceLabels: Record<string, string> = {
  LEADER: '正式负责人',
  PROXY: '代管',
  GRANT: '临时授权',
};

export function scopeLabel(value: string | null | undefined) {
  if (!value) return '—';
  return scopeLabels[value] ?? value;
}

export function sourceLabel(value: string) {
  return sourceLabels[value] ?? value;
}

/**
 * 授权口径摘要：范围 + 额度。
 *
 * <p>额度为空是「不设上限，由审批流自己分级兜底」，与「额度是 0」是完全不同的意思，
 * 所以显示成「不设上限」而不是 0 或空白。
 */
export function authoritySummary(
  row: Pick<DepartmentLeaderView, 'scopeMode' | 'approvalLimit' | 'approvalCurrency'>,
) {
  if (row.approvalLimit === null) return `${scopeLabel(row.scopeMode)} · 不设审批额度上限`;
  return `${scopeLabel(row.scopeMode)} · 额度 ${formatAmount(row.approvalLimit, row.approvalCurrency ?? '')}`;
}

/** 金额展示：直接字符串化，不做算术，所以展示路径上没有浮点误差累积。 */
export function formatAmount(value: number | string | null | undefined, currency: string) {
  if (value === null || value === undefined || value === '') return '—';
  return currency ? `${String(value)} ${currency}` : String(value);
}

/** 列表里定位一行（按部门 id），找不到返回 null，方便断言里直接取。 */
export function findLeader(rows: DepartmentLeaderView[], departmentId: string) {
  return rows.find((row) => row.departmentId === departmentId) ?? null;
}

/** 「未指定负责人」= 该行没有负责人记录。部门仍在列表里，只是要提示审批流配兜底人。 */
export function needsFallback(row: DepartmentLeaderView) {
  return row.leaderId === null;
}

export function fetchLeaders() {
  return api<DepartmentLeaderView[]>('identity/department-leaders');
}

/**
 * 审批链引用：下游 17.4 审批流的取数口。
 *
 * <p>asOf 留空表示「此刻」。给了时刻就能查历史归属——临时授权与代管让审批权随时间变化，
 * 历史单据必须能还原当时该谁批。
 */
export function fetchApprovalChain(departmentId: string, asOf?: string) {
  const query = asOf ? `?asOf=${encodeURIComponent(asOf)}` : '';
  return api<ApprovalChainView>(`identity/departments/${departmentId}/approval-chain${query}`);
}

/** 指定 / 更换负责人。范围与额度同一次提交：分三次保存会出现授权开大但还没配人的中间态。 */
export function saveLeader(departmentId: string, input: DepartmentLeaderInput) {
  return api(`identity/departments/${departmentId}/leader`, 'PUT', input);
}

export function clearLeader(departmentId: string) {
  return api(`identity/departments/${departmentId}/leader`, 'DELETE');
}

export function addProxy(departmentId: string, input: LeaderProxyInput) {
  return api(`identity/departments/${departmentId}/leader/proxies`, 'POST', input);
}

export function removeProxy(departmentId: string, proxyId: string) {
  return api(`identity/departments/${departmentId}/leader/proxies/${proxyId}`, 'DELETE');
}

export function addGrant(departmentId: string, input: LeaderGrantInput) {
  return api(`identity/departments/${departmentId}/leader/grants`, 'POST', input);
}

export function removeGrant(departmentId: string, grantId: string) {
  return api(`identity/departments/${departmentId}/leader/grants/${grantId}`, 'DELETE');
}

/**
 * 可选负责人：只列启用中的成员。
 *
 * <p>停用成员不能当负责人（后端也会拒），把他放进下拉等于给一个必然失败的选择。
 */
export function selectableMembers(members: MemberView[]) {
  return members.filter((member) => member.status === 'ACTIVE');
}

/** 审批链是否处于「需要兜底」状态：没有负责人，也没有代管或临时授权顶上。 */
export function chainNeedsFallback(chain: ApprovalChainView | null) {
  return chain === null || chain.entries.length === 0 || chain.requiresFallback;
}

/** 审批链按顺序渲染：负责人 → 代管 → 临时授权，顺序本身是审批优先级。 */
export function orderedEntries(chain: ApprovalChainView | null): ApprovalChainEntry[] {
  if (!chain) return [];
  return [...chain.entries].sort((a, b) => a.seq - b.seq);
}
