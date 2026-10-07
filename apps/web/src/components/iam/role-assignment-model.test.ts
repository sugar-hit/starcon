import { describe, expect, it } from 'vitest';
import type {
  RoleAssignmentMergedPermission,
  RoleAssignmentScopeView,
} from '../../../../../packages/api-contracts/src/catalog';
import {
  describeScopes,
  describeSources,
  effectiveLabel,
  inactiveReasonLabel,
  mergeRuleLabel,
  scopeModeLabel,
  scopeModesRequiringObjects,
  scopeTypeLabel,
  toInstant,
  toScopeInput,
  validateAssignment,
  type AssignmentForm,
} from './role-assignment-model';

function form(patch: Partial<AssignmentForm> = {}): AssignmentForm {
  return {
    roleId: 'role-1',
    scopeMode: 'DEPARTMENT',
    scopeKeys: ['DEPARTMENT:dept-1'],
    effectiveFrom: '2026-05-01T09:00',
    effectiveUntil: '',
    note: '',
    ...patch,
  };
}

function scope(type: string, id: string, code: string): RoleAssignmentScopeView {
  return { scopeType: type as RoleAssignmentScopeView['scopeType'], scopeId: id, scopeCode: code };
}

function permission(
  patch: Partial<RoleAssignmentMergedPermission> = {},
): RoleAssignmentMergedPermission {
  return {
    permissionCode: 'catalog.sku.read',
    pageCode: 'catalog.sku',
    pageName: 'SKU',
    action: 'read',
    mergedScopeMode: 'DEPARTMENT',
    sourceRoles: ['R1'],
    scopeObjects: [],
    mergeRule: 'SINGLE',
    ...patch,
  };
}

describe('词表翻译', () => {
  it('范围模式给出人话而不是枚举值', () => {
    expect(scopeModeLabel('ALL')).toBe('租户内全部');
    expect(scopeModeLabel('SELF')).toBe('仅本人数据');
    expect(scopeModeLabel('DEPARTMENT')).toBe('指定范围');
  });

  it('认不出的状态原样显示，不猜成生效中', () => {
    // 未知状态宁可显得更保守，也不要把认不出的值当成 ACTIVE。
    expect(effectiveLabel('SOMETHING_NEW')).toBe('未知');
    expect(inactiveReasonLabel('MYSTERY')).toBe('MYSTERY');
  });

  it('合并规则三种取值都能翻译成可核对的说法', () => {
    expect(mergeRuleLabel('SINGLE')).toContain('仅一条');
    expect(mergeRuleLabel('UNION')).toContain('并集');
    expect(mergeRuleLabel('ABSORBED_BY_TENANT_SCOPE')).toContain('全部');
  });

  it('范围类型四类齐全', () => {
    expect(scopeTypeLabel('LEGAL_ENTITY')).toBe('法人');
    expect(scopeTypeLabel('SHOP')).toBe('店铺');
    expect(scopeTypeLabel('WAREHOUSE')).toBe('仓库');
    expect(scopeTypeLabel('DEPARTMENT')).toBe('部门');
  });

  it('租户内全部与仅本人不需要选对象，其余需要', () => {
    expect(scopeModesRequiringObjects).toEqual(['DEPARTMENT']);
    expect(scopeModesRequiringObjects).not.toContain('ALL');
    expect(scopeModesRequiringObjects).not.toContain('SELF');
  });
});

describe('范围与来源的摘要', () => {
  it('空范围显示破折号而不是空字符串', () => {
    expect(describeScopes([])).toBe('—');
  });

  it('多个范围用顿号连接并带上类型', () => {
    expect(
      describeScopes([scope('DEPARTMENT', 'd1', 'DEV'), scope('LEGAL_ENTITY', 'e1', 'USCO')]),
    ).toBe('部门 DEV、法人 USCO');
  });

  it('来源角色按加号连接，用于回答这条权限是谁给的', () => {
    expect(describeSources(permission({ sourceRoles: ['WAREHOUSE_MANAGER', 'OPS'] }))).toBe(
      'WAREHOUSE_MANAGER + OPS',
    );
  });
});

describe('提交前自检', () => {
  it('指定范围必须给出作用对象——这就是蓝图那条加粗的硬约束', () => {
    expect(validateAssignment(form({ scopeKeys: [] }))).toContain('不能只给角色不给范围');
  });

  it('租户内全部与仅本人不接受作用对象', () => {
    expect(validateAssignment(form({ scopeMode: 'ALL' }))).toContain('无需再指定作用对象');
    expect(validateAssignment(form({ scopeMode: 'SELF' }))).toContain('无需再指定作用对象');
  });

  it('这两类模式不选对象时通过', () => {
    expect(validateAssignment(form({ scopeMode: 'ALL', scopeKeys: [] }))).toBeNull();
    expect(validateAssignment(form({ scopeMode: 'SELF', scopeKeys: [] }))).toBeNull();
  });

  it('缺角色、缺生效时间都拦得住', () => {
    expect(validateAssignment(form({ roleId: '' }))).toBe('请选择要分配的角色');
    expect(validateAssignment(form({ effectiveFrom: '' }))).toBe('请填写生效开始时间');
  });

  it('结束时间不晚于开始时间时拒绝', () => {
    expect(
      validateAssignment(
        form({ effectiveFrom: '2026-05-01T09:00', effectiveUntil: '2026-05-01T08:00' }),
      ),
    ).toBe('生效结束时间必须晚于开始时间');
    // 同一时刻也不行：那样的一行永远不会生效。
    expect(
      validateAssignment(
        form({ effectiveFrom: '2026-05-01T09:00', effectiveUntil: '2026-05-01T09:00' }),
      ),
    ).toBe('生效结束时间必须晚于开始时间');
  });

  it('格式不合法的时间给出格式提示而不是时间倒挂提示', () => {
    expect(validateAssignment(form({ effectiveUntil: 'not-a-date' }))).toBe(
      '生效结束时间格式不正确',
    );
  });

  it('重复选中同一个对象时拒绝', () => {
    expect(validateAssignment(form({ scopeKeys: ['DEPARTMENT:d1', 'DEPARTMENT:d1'] }))).toBe(
      '作用对象不能重复',
    );
  });

  it('通过自检的表单返回 null', () => {
    expect(validateAssignment(form())).toBeNull();
  });
});

describe('时间与范围对象的序列化', () => {
  it('墙上时间被补上时区偏移，服务端拿到的是真实瞬间', () => {
    const instant = toInstant('2026-05-01T09:00');
    expect(instant).toBeTruthy();
    expect(new Date(instant as string).getHours()).toBe(9);
  });

  it('空值与非法值返回 null，由调用方提示而不是当成「未填就等于现在」', () => {
    expect(toInstant('')).toBeNull();
    expect(toInstant('bad')).toBeNull();
  });

  it('范围键被拆回类型与对象 id，并带上服务端无法自证的名称', () => {
    expect(toScopeInput('WAREHOUSE:w1', () => 'US-WH-1')).toEqual({
      scopeType: 'WAREHOUSE',
      scopeId: 'w1',
      scopeCode: 'US-WH-1',
    });
  });

  it('取不到名称时传 null，服务端会覆写或按规则拒绝，不在这里编一个名称', () => {
    expect(toScopeInput('DEPARTMENT:d1', () => null)).toEqual({
      scopeType: 'DEPARTMENT',
      scopeId: 'd1',
      scopeCode: null,
    });
  });

  it('残缺的键返回 null，不提交半条范围', () => {
    expect(toScopeInput('DEPARTMENT', () => 'DEV')).toBeNull();
  });
});
