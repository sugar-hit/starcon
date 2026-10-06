import type { AuditDetail, AuditPage, AuditView } from '../../../../../packages/api-contracts/src/catalog';
import { api } from '../../lib/api';

/**
 * 17.5.3.1 审计日志前端模型。
 *
 * <p>与 17.5.3.2 操作日志的分工：这里只有合规所需的「谁、对什么、做了什么、凭什么」，
 * 不展示登录流水、API 调用摘要、错误堆栈或请求 ID 链路。
 */

/** 动作名 → 中文。审计里出现未登记的动作时回落到原值，而不是显示空白。 */
export const actionLabels: Record<string, string> = {
  RoleSaved: '保存角色',
  RoleDeleted: '删除角色',
  MemberCreated: '新增成员',
  MemberUpdated: '修改成员',
  MemberRemoved: '移除成员',
  OwnerTransferred: '转移所有者',
  DepartmentCreated: '新增部门',
  DepartmentUpdated: '修改部门',
  DepartmentMoved: '调整部门层级',
  DepartmentDeleted: '删除部门',
  ChildCreated: '创建子租户',
  ChildUpdated: '修改子租户',
  LegalEntityCreated: '新增法人',
  LegalEntityUpdated: '修改法人',
  LegalEntityDeleted: '删除法人',
};

/** 资源类型 → 中文。同样对未知类型回落，不吞掉信息。 */
export const resourceLabels: Record<string, string> = {
  'identity.role': '角色',
  'identity.member': '成员',
  'identity.department': '部门',
  'identity.legal_entity': '法人',
  'tenant.child': '子租户',
};

export function actionLabel(action: string) {
  return actionLabels[action] ?? action;
}

export function resourceLabel(type: string) {
  return resourceLabels[type] ?? type;
}

export type AuditFilters = {
  resourceType: string;
  resourceId: string;
  actorId: string;
  action: string;
  from: string;
  to: string;
};

export const emptyFilters: AuditFilters = {
  resourceType: '',
  resourceId: '',
  actorId: '',
  action: '',
  from: '',
  to: '',
};

/**
 * 把筛选条件变成 query string。
 *
 * <p>只带非空项，避免 `?actorId=` 这种「看着像筛选、其实被后端当未传」的参数——
 * 那样会让「查不到结果」和「没在筛」看起来一模一样。
 */
export function auditQuery(filters: AuditFilters, page: number, size: number) {
  const parts: string[] = [`page=${page}`, `size=${size}`];
  if (filters.resourceType) parts.push(`resourceType=${encodeURIComponent(filters.resourceType)}`);
  if (filters.resourceId) parts.push(`resourceId=${encodeURIComponent(filters.resourceId)}`);
  if (filters.actorId) parts.push(`actorId=${encodeURIComponent(filters.actorId)}`);
  if (filters.action) parts.push(`action=${encodeURIComponent(filters.action)}`);
  if (filters.from) parts.push(`from=${encodeURIComponent(new Date(filters.from).toISOString())}`);
  if (filters.to) parts.push(`to=${encodeURIComponent(new Date(filters.to).toISOString())}`);
  return parts.join('&');
}

export function fetchAudit(filters: AuditFilters, page: number, size = 50) {
  return api<AuditPage>(`identity/audit?${auditQuery(filters, page, size)}`);
}

export function fetchAuditDetail(id: string) {
  return api<AuditDetail>(`identity/audit/${id}`);
}

/** 导出链接：与列表共用同一套条件，所以导出结果和当前看到的完全一致。 */
export function auditExportUrl(filters: AuditFilters) {
  return `/api/iam/identity/audit/export?${auditQuery(filters, 0, 1)}`;
}

/**
 * 计算变更详情里真正发生变化的字段。
 *
 * <p>审计详情需要回答「改了什么」，而不是把整份对象倒出来——新建时 before 为空、
 * 删除时 after 为空，两侧都要正确处理。没有变化的字段直接不显示，
 * 免得运营在一堆未改动字段里找那一个真正被改的。
 */
export function changedFields(detail: AuditDetail) {
  // 契约里 changes 的类型是开放对象，这里显式收窄成 before/after 两个快照，
  // 否则访问字段会被 TS 以 `{}` 拒绝。
  const changes = (detail.changes ?? {}) as {
    before?: Record<string, unknown> | null;
    after?: Record<string, unknown> | null;
  };
  const before = changes.before ?? null;
  const after = changes.after ?? null;
  if (!before && !after) return [];
  const keys = [...new Set([...Object.keys(before ?? {}), ...Object.keys(after ?? {})])];
  return keys
    .map((field) => {
      const from = before ? before[field] : null;
      const to = after ? after[field] : null;
      const changed = before === null || after === null || JSON.stringify(from) !== JSON.stringify(to);
      return { field, from: render(from), to: render(to), changed };
    })
    .filter((row) => row.changed);
}

function render(value: unknown) {
  if (value === null || value === undefined) return '—';
  if (Array.isArray(value)) return value.length ? value.join('、') : '—';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

/** 授权上下文：权限集可能很长，展示时折叠成角色 + 范围的摘要。 */
export function authorizationSummary(detail: AuditDetail) {
  const context = (detail.authorization ?? {}) as Record<string, unknown>;
  const roles = Array.isArray(context.roles) ? (context.roles as string[]) : [];
  const scopes = Array.isArray(context.scopeModes) ? (context.scopeModes as string[]) : [];
  const permissions = Array.isArray(context.permissions) ? (context.permissions as string[]) : [];
  return {
    roles: roles.length ? roles.join('、') : '无角色',
    scopes: scopes.length ? scopes.join('、') : '无范围',
    permissions,
    departmentId: context.departmentId ? String(context.departmentId) : '',
  };
}

/** 时间格式化：后端给的是 UTC ISO 串，界面按本地时区展示。 */
export function formatTime(value: string) {
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

export function isAuditView(value: AuditView | null): value is AuditView {
  return value !== null;
}