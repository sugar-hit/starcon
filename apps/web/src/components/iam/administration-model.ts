import type {
  Access,
  AssignmentInput,
  RoleInput,
  MemberInput,
  MemberUpdate,
  DepartmentInput,
  DepartmentView,
  ChildInput,
  ChildUpdate,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

export type AdministrationKind = 'roles' | 'members' | 'departments' | 'children';
export const scopeLabels: Record<string, string> = {
  ALL: '租户内全部',
  DEPARTMENT: '本部门',
  SELF: '仅本人',
};
export const actionLabels: Record<string, string> = {
  read: '查看',
  create: '新增',
  update: '修改',
  delete: '删除',
};
export function isOwner(session: Access) {
  return session.grants.some((g) => g.roleCode === 'OWNER' && g.scopeMode === 'ALL');
}
export function canManage(session: Access, kind: AdministrationKind, action = 'read') {
  const page =
    kind === 'children'
      ? 'tenant.child'
      : `identity.${{ roles: 'role', members: 'member', departments: 'department' }[kind]}`;
  return (
    (action === 'read' || isOwner(session)) &&
    session.grants.some((g) => g.scopeMode === 'ALL' && g.permissions.includes(`${page}.${action}`))
  );
}
/** 按权限码判断，用于部门表单要读法人档案这种跨页面资源的场景。 */
export function hasPermission(session: Access, permission: string) {
  return session.grants.some(
    (g) => g.scopeMode === 'ALL' && g.permissions.includes(permission),
  );
}
export function togglePermission(values: string[], code: string, enabled: boolean) {
  const page = code.slice(0, code.lastIndexOf('.'));
  const next = new Set(values);
  if (enabled) {
    next.add(code);
    next.add(`${page}.read`);
  } else if (code.endsWith('.read')) {
    for (const value of next) if (value.startsWith(`${page}.`)) next.delete(value);
  } else next.delete(code);
  return [...next].sort();
}
export function assignRole(
  assignments: AssignmentInput[],
  roleId: string,
  scopeMode: string | null,
) {
  const rest = assignments.filter((a) => a.roleId !== roleId);
  return scopeMode === null ? rest : [...rest, { roleId, scopeMode }];
}
export function saveAdministration(
  kind: 'roles',
  id: string | undefined,
  input: RoleInput,
): Promise<unknown>;
export function saveAdministration(
  kind: 'members',
  id: string | undefined,
  input: MemberInput | MemberUpdate,
): Promise<unknown>;
export function saveAdministration(
  kind: 'departments',
  id: string | undefined,
  input: DepartmentInput,
): Promise<unknown>;
export function saveAdministration(
  kind: 'children',
  id: string | undefined,
  input: ChildInput | ChildUpdate,
): Promise<unknown>;
export function saveAdministration(
  kind: AdministrationKind,
  id: string | undefined,
  input: unknown,
) {
  return api(`identity/${kind}${id ? `/${id}` : ''}`, id ? 'PUT' : 'POST', input);
}
export function removeAdministration(kind: Exclude<AdministrationKind, 'children'>, id: string) {
  return api(`identity/${kind}/${id}`, 'DELETE');
}
/** 拖拽移动只改层级，单独端点——防环校验只存在于后端那一处。 */
export function moveDepartment(id: string, parentId: string | null) {
  return api(`identity/departments/${id}/parent`, 'PUT', { parentId });
}

/** 树拍平成带缩进的行，供上级部门选择器使用。 */
export type DepartmentRow = { node: DepartmentView; depth: number };
export function flattenDepartments(nodes: DepartmentView[], depth = 0): DepartmentRow[] {
  return nodes.flatMap((node) => [
    { node, depth },
    ...flattenDepartments(node.children, depth + 1),
  ]);
}

/**
 * 拖拽目标是否合法：不能落在自己或自己的子孙上。
 * 后端会用递归查询再判一次，这里只是为了在放下瞬间给出反馈而不是等失败。
 */
export function canDropDepartment(nodes: DepartmentView[], draggedId: string, targetId: string) {
  const dragged = flattenDepartments(nodes).find((row) => row.node.id === draggedId)?.node;
  if (!dragged || dragged.id === targetId) return false;
  return !flattenDepartments(dragged.children).some((row) => row.node.id === targetId);
}

export function transferOwner(membershipId: string) {
  return api('identity/owner-transfer', 'POST', { membershipId });
}
export function switchTenant(membershipId: string) {
  return api('auth/switch', 'POST', { membershipId });
}

/** Presentation follows the same assignment predicate; the backend remains authoritative. */
export function canAccessRecord(
  session: Access,
  permission: string,
  record?: { ownerId: string | null; departmentId: string | null } | null,
) {
  const read = `${permission.slice(0, permission.lastIndexOf('.'))}.read`;
  return session.grants.some(
    (g) =>
      g.permissions.includes(permission) &&
      g.permissions.includes(read) &&
      (g.scopeMode === 'ALL' ||
        (g.scopeMode === 'SELF' && (!record || record.ownerId === session.userId)) ||
        (g.scopeMode === 'DEPARTMENT' &&
          session.departmentId !== null &&
          (!record || record.departmentId === session.departmentId))),
  );
}
