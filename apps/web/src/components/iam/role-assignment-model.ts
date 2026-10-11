import type {
  RoleAssignmentMergedPermission,
  RoleAssignmentScopeView,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 17.3.2.1 角色分配的前端模型。
 *
 * <p>这里只放**纯展示与纯校验**：词表翻译、合并规则的措辞、提交前的自检。
 * 判定永远在服务端（{@code RoleAssignmentController} 与 V47 的库内约束），
 * 这里的校验只是让用户在点保存之前就看到「这条分配缺范围」，而不是等一个 400。
 */

export type ScopeMode = 'ALL' | 'SELF' | 'DEPARTMENT';
export type ScopeType = 'LEGAL_ENTITY' | 'SHOP' | 'WAREHOUSE' | 'DEPARTMENT';

export const scopeModeLabels: Record<ScopeMode, string> = {
  ALL: '租户内全部',
  SELF: '仅本人数据',
  DEPARTMENT: '指定范围',
};

/** 只有「指定范围」需要列出对象；前两种自带完整范围，再挂对象会被服务端拒绝。 */
export const scopeModesRequiringObjects: ScopeMode[] = ['DEPARTMENT'];

export const scopeTypeLabels: Record<ScopeType, string> = {
  LEGAL_ENTITY: '法人',
  SHOP: '店铺',
  WAREHOUSE: '仓库',
  DEPARTMENT: '部门',
};

export const effectiveLabels: Record<string, string> = {
  ACTIVE: '生效中',
  PENDING: '未生效',
  EXPIRED: '已过期',
  REVOKED: '已撤销',
};

/** 未参与合并的原因。缺一个就说明「这个角色为什么没权限」只能靠猜。 */
export const inactiveReasonLabels: Record<string, string> = {
  PENDING: '尚未生效',
  EXPIRED: '已过期',
  REVOKED: '已撤销',
  ROLE_INACTIVE: '角色已停用',
};

/**
 * 合并规则 → 人话。
 *
 * <p>三个值分别对应服务端的三条规则：单来源直接成立；多来源取并集；
 * 其中一条是租户内全部时整体按全部生效。写清楚是为了让管理员能自己核对，
 * 而不是只能相信一个「有效权限」列表。
 */
export const mergeRuleLabels: Record<string, string> = {
  SINGLE: '仅一条分配提供',
  UNION: '多条分配合并（权限与范围取并集）',
  ABSORBED_BY_TENANT_SCOPE: '存在租户内全部范围，整体按全部生效',
};

export function scopeModeLabel(mode: string): string {
  return scopeModeLabels[mode as ScopeMode] ?? mode;
}

export function scopeTypeLabel(type: string): string {
  return scopeTypeLabels[type as ScopeType] ?? type;
}

export function effectiveLabel(status: string): string {
  return effectiveLabels[status] ?? '未知';
}

export function inactiveReasonLabel(reason: string): string {
  return inactiveReasonLabels[reason] ?? reason;
}

export function mergeRuleLabel(rule: string): string {
  return mergeRuleLabels[rule] ?? rule;
}

/** 范围对象在列表里的一句话摘要：类型 + 编码，空列表返回「—」而不是空串。 */
export function describeScopes(scopes: RoleAssignmentScopeView[]): string {
  if (!scopes.length) return '—';
  return scopes.map((s) => `${scopeTypeLabel(s.scopeType)} ${s.scopeCode}`).join('、');
}

/** 一条合并后权限的来源说明：这个权限是哪几个角色合并来的。 */
export function describeSources(permission: RoleAssignmentMergedPermission): string {
  return permission.sourceRoles.join(' + ');
}

/** 本地时间输入（datetime-local 是「墙上时间」）→ 带偏移的 ISO 字符串。 */
export function toInstant(local: string): string | null {
  if (!local) return null;
  const at = new Date(local);
  return Number.isNaN(at.getTime()) ? null : at.toISOString();
}

export type AssignmentForm = {
  roleId: string;
  scopeMode: ScopeMode;
  /** 选中的范围对象，形如 `TYPE:id`。 */
  scopeKeys: string[];
  effectiveFrom: string;
  effectiveUntil: string;
  note: string;
};

/**
 * 提交前自检，与服务端规则一一对应。
 *
 * <p>服务端会再校验一遍，这里只是提前把话说清楚。返回中文原因而不是字段名：
 * 用户要知道自己该改哪一栏。
 */
export function validateAssignment(form: AssignmentForm): string | null {
  if (!form.roleId) return '请选择要分配的角色';
  if (!form.effectiveFrom) return '请填写生效开始时间';
  const from = new Date(form.effectiveFrom);
  const until = form.effectiveUntil ? new Date(form.effectiveUntil) : null;
  if (Number.isNaN(from.getTime())) return '生效开始时间格式不正确';
  if (form.effectiveUntil && Number.isNaN(until!.getTime())) return '生效结束时间格式不正确';
  if (until && until <= from) return '生效结束时间必须晚于开始时间';

  const needsObjects = scopeModesRequiringObjects.includes(form.scopeMode);
  if (needsObjects && form.scopeKeys.length === 0)
    // 蓝图里加粗的那条：没有范围的角色等于全量权限，实践中极易误配。
    return '「指定范围」必须至少选一个作用对象，不能只给角色不给范围';
  if (!needsObjects && form.scopeKeys.length > 0)
    return '租户内全部与仅本人范围自带完整范围，无需再指定作用对象';
  const unique = new Set(form.scopeKeys);
  if (unique.size !== form.scopeKeys.length) return '作用对象不能重复';
  return null;
}

/** 表单 → 请求体。scopeKeys 拆回类型与对象 id，店铺与仓库补上服务端无法自证的名称。 */
export function toScopeInput(
  key: string,
  nameOf: (type: string, id: string) => string | null,
): { scopeType: string; scopeId: string; scopeCode: string | null } | null {
  const [scopeType, scopeId] = key.split(':');
  if (!scopeType || !scopeId) return null;
  return { scopeType, scopeId, scopeCode: nameOf(scopeType, scopeId) };
}
