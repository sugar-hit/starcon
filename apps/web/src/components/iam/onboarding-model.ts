import type {
  Access,
  DepartmentView,
  OnboardingInput,
  OnboardingResult,
  OnboardingView,
  RoleView,
} from '../../../../../packages/api-contracts/src/catalog';
import { api } from '../../lib/api';

export type OnboardingRoleInput = OnboardingInput['roles'][number];

const accountModeLabels: Record<string, string> = {
  CREATED: '新建账号',
  LINKED: '关联已有账号',
};
const statusLabels: Record<string, string> = {
  PENDING: '待完成',
  COMPLETED: '已入职',
};

/**
 * 账号模式的中文名。
 *
 * <p>这两个值对应管理员接下来的**不同动作**，所以界面必须说出来而不是显示枚举原文：
 * 新建账号意味着有一份初始凭据要当面转交，关联已有账号意味着没有。
 */
export function accountModeLabel(mode: string) {
  return accountModeLabels[mode] ?? mode;
}

export function onboardingStatusLabel(status: string) {
  return statusLabels[status] ?? status;
}

/**
 * 这一行是否还有「初始凭据待转交」这回事。
 *
 * <p>只有在这次真的新建了账号、而且入职还没完成时才成立：完成入职意味着这件事已经办完，
 * 再提示「请转交密码」只会让人以为漏了一步。同理 LINKED 永远不需要转交——
 * 那个人的密码在他自己手上，服务端返回的是 null，界面凭空造一个提示就是在骗人。
 */
export function needsCredentialHandover(row: OnboardingView) {
  return row.accountMode === 'CREATED' && row.status === 'PENDING';
}

/** 登录后的会话能否进入这个页面。与服务端同一套权限码，不另立一套「谁能开通人」的判断。 */
export function canManageOnboarding(session: Access, action = 'read') {
  return (
    (action === 'read' || isOnboardingOwner(session)) &&
    session.grants.some(
      (g) => g.scopeMode === 'ALL' && g.permissions.includes(`identity.onboarding.${action}`),
    )
  );
}

/** 开通与完成入职都是授权与组织变更，服务端要求当前租户所有者。 */
function isOnboardingOwner(session: Access) {
  return session.grants.some((g) => g.roleCode === 'OWNER' && g.scopeMode === 'ALL');
}

/**
 * 可在开通时分配的角色。
 *
 * <p>OWNER 被排除：所有者只能走「转移所有者」，不能靠开通顺带发出去，
 * 否则「谁能成为所有者」就绕开了那条显式流程。停用角色也排除——服务端同样会拒，
 * 这里先过滤是为了不让用户填完一整张表才被驳回。
 */
export function assignableRoles(roles: RoleView[], assigned: OnboardingRoleInput[] = []) {
  return roles.filter(
    (r) => !r.system && (r.status === 'ACTIVE' || assigned.some((a) => a.roleId === r.id)),
  );
}

/**
 * 管理权限（identity.* / tenant.*）只能绑「租户内全部」范围。
 *
 * <p>与服务端 onboard_member 的同名校验一致：把 identity.member.read 这类权限限定在某个部门，
 * 等于造出「在这个部门里能管人、在别的部门管不了」的角色——实践中几乎总是误配。
 * catalog 业务权限才允许本部门 / 仅本人范围。
 */
export function scopesFor(role: RoleView) {
  return role.permissions.every((p) => p.startsWith('catalog.'))
    ? (['ALL', 'DEPARTMENT', 'SELF'] as const)
    : (['ALL'] as const);
}

/** 选中/取消一个角色的范围；scopeMode 为 null 表示取消分配。 */
export function toggleOnboardingRole(
  assigned: OnboardingRoleInput[],
  roleId: string,
  scopeMode: string | null,
) {
  const rest = assigned.filter((a) => a.roleId !== roleId);
  return scopeMode === null ? rest : [...rest, { roleId, scopeMode }];
}

export function onboardingInput(
  username: string,
  departmentId: string,
  roles: OnboardingRoleInput[],
): OnboardingInput {
  return {
    username: username.trim(),
    departmentId: departmentId || null,
    roles,
  };
}

export function listOnboardings() {
  return api<OnboardingView[]>('identity/onboardings');
}

export function createOnboarding(input: OnboardingInput) {
  return api<OnboardingResult>('identity/onboardings', 'POST', input);
}

export function completeOnboarding(id: string) {
  return api(`identity/onboardings/${id}/complete`, 'POST');
}

/** 部门选择器的扁平行，与 administration-model 的 flattenDepartments 同形（树 + 缩进）。 */
export type DepartmentOption = { node: DepartmentView; depth: number };
export function flattenDepartmentOptions(nodes: DepartmentView[], depth = 0): DepartmentOption[] {
  return nodes.flatMap((node) => [
    { node, depth },
    ...flattenDepartmentOptions(node.children, depth + 1),
  ]);
}
