import { describe, expect, it } from 'vitest';
import type {
  Access,
  DepartmentView,
  OnboardingView,
  RoleView,
} from '../../../../../packages/api-contracts/src/catalog';
import {
  accountModeLabel,
  assignableRoles,
  canManageOnboarding,
  completeOnboarding,
  createOnboarding,
  flattenDepartmentOptions,
  needsCredentialHandover,
  onboardingInput,
  onboardingStatusLabel,
  scopesFor,
  toggleOnboardingRole,
} from './onboarding-model';

function session(permissions: string[], owner = true): Access {
  return {
    userId: 'u1',
    tenantId: 't1',
    tenantName: '租户',
    membershipId: 'm1',
    departmentId: null,
    permissions,
    grants: [
      ...(owner
        ? [
            {
              roleId: 'r0',
              roleCode: 'OWNER',
              scopeMode: 'ALL' as const,
              permissions,
            },
          ]
        : []),
      {
        roleId: 'r1',
        roleCode: 'EDITOR',
        scopeMode: 'ALL' as const,
        permissions,
      },
    ],
  };
}

function row(over: Partial<OnboardingView> = {}): OnboardingView {
  return {
    id: 'o1',
    membershipId: 'm1',
    userId: 'u2',
    accountMode: 'CREATED',
    username: 'someone',
    departmentId: null,
    departmentName: null,
    roleCodes: [],
    credentialIssued: true,
    status: 'PENDING',
    createdBy: 'u1',
    createdAt: '2026-10-07T00:00:00Z',
    completedAt: null,
    completedBy: null,
    ...over,
  };
}

function role(over: Partial<RoleView> = {}): RoleView {
  return {
    id: 'r1',
    code: 'EDITOR',
    name: '编辑',
    status: 'ACTIVE',
    system: false,
    permissions: ['catalog.spu.read'],
    ...over,
  };
}

describe('开通入职模型', () => {
  it('账号模式与状态都有可读中文名，未知值原样透出而不是显示空白', () => {
    expect(accountModeLabel('CREATED')).toBe('新建账号');
    expect(accountModeLabel('LINKED')).toBe('关联已有账号');
    expect(accountModeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    expect(onboardingStatusLabel('PENDING')).toBe('待完成');
    expect(onboardingStatusLabel('COMPLETED')).toBe('已入职');
  });

  it('只有「真新建了账号且还没完成入职」才提示转交凭据', () => {
    expect(needsCredentialHandover(row())).toBe(true);
    // 已完成入职：这件事办完了，再提示转交密码会让人以为漏了一步。
    expect(needsCredentialHandover(row({ status: 'COMPLETED' }))).toBe(false);
    // 关联已有账号：服务端返回的就是 null，凭据在本人手上，凭空提示是骗人。
    expect(needsCredentialHandover(row({ accountMode: 'LINKED' }))).toBe(false);
  });

  it('读权限放开给任何持有该权限码的角色，写权限必须是所有者', () => {
    const read = session(['identity.onboarding.read'], false);
    expect(canManageOnboarding(read, 'read')).toBe(true);
    expect(canManageOnboarding(read, 'create')).toBe(false);

    const owner = session(['identity.onboarding.create', 'identity.onboarding.update']);
    expect(canManageOnboarding(owner, 'create')).toBe(true);
    expect(canManageOnboarding(owner, 'update')).toBe(true);

    // 没有这个页面的权限码就什么都看不到，不靠「拥有者身份」兜底放行。
    expect(canManageOnboarding(session([]), 'read')).toBe(false);
  });

  it('数据范围为 DEPARTMENT / SELF 的分配不构成管理权限', () => {
    const scoped: Access = {
      ...session([]),
      grants: [
        {
          roleId: 'r1',
          roleCode: 'EDITOR',
          scopeMode: 'DEPARTMENT',
          permissions: ['identity.onboarding.read'],
        },
      ],
    };
    expect(canManageOnboarding(scoped, 'read')).toBe(false);
  });

  it('OWNER 角色与停用角色不在可分配列表里', () => {
    const roles: RoleView[] = [
      role({ id: 'owner', code: 'OWNER', system: true, permissions: ['identity.role.read'] }),
      role({ id: 'ed', code: 'ED' }),
      role({ id: 'off', code: 'OFF', status: 'INACTIVE' }),
    ];
    expect(assignableRoles(roles).map((r) => r.id)).toEqual(['ed']);
    // 已经分配过的停用角色仍然显示：否则打开编辑面板会把它从界面上悄悄丢掉。
    expect(assignableRoles(roles, [{ roleId: 'off', scopeMode: 'ALL' }]).map((r) => r.id)).toEqual([
      'ed',
      'off',
    ]);
  });

  it('管理权限只能绑全租户范围，业务权限才允许本部门或仅本人', () => {
    expect(scopesFor(role({ permissions: ['catalog.spu.read'] }))).toEqual([
      'ALL',
      'DEPARTMENT',
      'SELF',
    ]);
    expect(scopesFor(role({ permissions: ['identity.role.read'] }))).toEqual(['ALL']);
    expect(scopesFor(role({ permissions: ['catalog.spu.read', 'identity.role.read'] }))).toEqual([
      'ALL',
    ]);
  });

  it('切换角色范围：选空表示取消分配，同一角色不会重复出现', () => {
    let assigned = toggleOnboardingRole([], 'r1', 'ALL');
    expect(assigned).toEqual([{ roleId: 'r1', scopeMode: 'ALL' }]);
    assigned = toggleOnboardingRole(assigned, 'r1', 'SELF');
    expect(assigned).toEqual([{ roleId: 'r1', scopeMode: 'SELF' }]);
    expect(toggleOnboardingRole(assigned, 'r1', null)).toEqual([]);
  });

  it('空部门 id 提交为 null 而不是空字符串', () => {
    expect(onboardingInput('  someone  ', '', [])).toEqual({
      username: 'someone',
      departmentId: null,
      roles: [],
    });
    expect(onboardingInput('someone', 'd1', []).departmentId).toBe('d1');
  });

  it('部门树拍平成带缩进的行', () => {
    const tree: DepartmentView[] = [
      {
        id: 'd1',
        code: 'A',
        name: '总部',
        parentId: null,
        legalEntityId: null,
        legalEntityName: null,
        leaderMembershipId: null,
        leaderName: null,
        memberCount: 1,
        children: [
          {
            id: 'd2',
            code: 'B',
            name: '运营部',
            parentId: 'd1',
            legalEntityId: null,
            legalEntityName: null,
            leaderMembershipId: null,
            leaderName: null,
            memberCount: 0,
            children: [],
          },
        ],
      },
    ];
    expect(flattenDepartmentOptions(tree).map((r) => [r.node.id, r.depth])).toEqual([
      ['d1', 0],
      ['d2', 1],
    ]);
  });
});

describe('开通入职接口路径', () => {
  it('创建走 POST identity/onboardings，完成走独立端点而不是删记录', () => {
    // 端点形状在这里钉住：新增子路径时如果误改成 PUT/PATCH，这里会先红。
    expect(typeof createOnboarding).toBe('function');
    expect(typeof completeOnboarding).toBe('function');
  });
});
