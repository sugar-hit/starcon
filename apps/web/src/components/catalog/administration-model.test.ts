import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Access } from '../../../../../packages/api-contracts/src/catalog';
import {
  assignRole,
  canManage,
  canAccessRecord,
  isOwner,
  removeAdministration,
  saveAdministration,
  switchTenant,
  togglePermission,
  transferOwner,
} from './administration-model';

const access: Access = {
  userId: 'user',
  tenantId: 'tenant',
  tenantName: '租户',
  membershipId: 'member',
  departmentId: null,
  permissions: ['identity.role.read', 'identity.role.update'],
  grants: [
    {
      roleId: 'owner',
      roleCode: 'OWNER',
      scopeMode: 'ALL',
      permissions: ['identity.role.read', 'identity.role.update', 'tenant.child.read'],
    },
  ],
};
afterEach(() => vi.unstubAllGlobals());
function mockFetch(ok = true) {
  const fetcher = vi.fn().mockImplementation(() =>
    Promise.resolve(
      new Response(JSON.stringify(ok ? 'saved' : { message: '拒绝访问' }), {
        status: ok ? 200 : 403,
      }),
    ),
  );
  vi.stubGlobal('fetch', fetcher);
  return fetcher;
}
describe('角色权限与数据范围配置', () => {
  it('操作自动包含同页面查看，取消查看同时移除该页面操作', () => {
    expect(togglePermission([], 'catalog.spu.update', true)).toEqual([
      'catalog.spu.read',
      'catalog.spu.update',
    ]);
    expect(
      togglePermission(
        ['catalog.spu.read', 'catalog.spu.update', 'catalog.sku.read'],
        'catalog.spu.read',
        false,
      ),
    ).toEqual(['catalog.sku.read']);
    expect(
      togglePermission(['catalog.spu.read', 'catalog.spu.update'], 'catalog.spu.update', false),
    ).toEqual(['catalog.spu.read']);
  });
  it('角色范围独立，不把多个角色权限与范围合并', () => {
    let assignments = assignRole([], 'writer', 'SELF');
    assignments = assignRole(assignments, 'reader', 'ALL');
    expect(assignRole(assignments, 'writer', 'DEPARTMENT')).toEqual([
      { roleId: 'reader', scopeMode: 'ALL' },
      { roleId: 'writer', scopeMode: 'DEPARTMENT' },
    ]);
    expect(assignRole(assignments, 'writer', null)).toEqual([
      { roleId: 'reader', scopeMode: 'ALL' },
    ]);
    expect(assignments[0]?.scopeMode).toBe('SELF');
  });
  it('管理读权限可委派，授权写操作只显示给目标租户 Owner', () => {
    expect(isOwner(access)).toBe(true);
    expect(canManage(access, 'roles', 'update')).toBe(true);
    expect(canManage(access, 'children')).toBe(true);
    expect(canManage(access, 'members')).toBe(false);
    const reader = { ...access, grants: [{ ...access.grants[0]!, roleCode: 'READER' }] };
    expect(canManage(reader, 'roles')).toBe(true);
    expect(canManage(reader, 'roles', 'update')).toBe(false);
    expect(
      canManage({ ...access, grants: [{ ...access.grants[0]!, scopeMode: 'SELF' }] }, 'roles'),
    ).toBe(false);
  });
});
describe('管理页真实 API 命令', () => {
  it('角色创建与编辑使用同一契约并区分方法', async () => {
    const fetcher = mockFetch();
    const role = {
      code: 'EDITOR',
      name: '编辑',
      status: 'ACTIVE',
      permissions: ['catalog.spu.read'],
    };
    await saveAdministration('roles', undefined, role);
    expect(fetcher).toHaveBeenLastCalledWith(
      '/api/catalog/identity/roles',
      expect.objectContaining({ method: 'POST', body: JSON.stringify(role) }),
    );
    await saveAdministration('roles', 'role-id', role);
    expect(fetcher).toHaveBeenLastCalledWith(
      '/api/catalog/identity/roles/role-id',
      expect.objectContaining({ method: 'PUT' }),
    );
  });
  it('成员提交部门和每个角色的范围，编辑不包含登录凭据', async () => {
    const fetcher = mockFetch();
    const member = {
      status: 'ACTIVE',
      departmentId: 'department',
      assignments: [{ roleId: 'role', scopeMode: 'DEPARTMENT' }],
    };
    await saveAdministration('members', undefined, {
      ...member,
      username: 'existing-user',
      password: null,
    });
    expect(fetcher).toHaveBeenLastCalledWith(
      '/api/catalog/identity/members',
      expect.objectContaining({
        body: JSON.stringify({ ...member, username: 'existing-user', password: null }),
      }),
    );
    await saveAdministration('members', 'member', member);
    expect(fetcher).toHaveBeenLastCalledWith(
      '/api/catalog/identity/members/member',
      expect.objectContaining({ method: 'PUT', body: JSON.stringify(member) }),
    );
  });
  it('部门增改和角色/成员/部门删除均提交后端', async () => {
    const fetcher = mockFetch();
    await saveAdministration('departments', undefined, { name: '商品部' });
    expect(fetcher).toHaveBeenLastCalledWith(
      '/api/catalog/identity/departments',
      expect.objectContaining({ method: 'POST' }),
    );
    await saveAdministration('departments', 'department', { name: '运营部' });
    expect(fetcher).toHaveBeenLastCalledWith(
      '/api/catalog/identity/departments/department',
      expect.objectContaining({ method: 'PUT' }),
    );
    for (const kind of ['roles', 'members', 'departments'] as const) {
      await removeAdministration(kind, 'id');
      expect(fetcher).toHaveBeenLastCalledWith(
        `/api/catalog/identity/${kind}/id`,
        expect.objectContaining({ method: 'DELETE' }),
      );
    }
  });
  it('子租户创建显式提交首位所有者，暂停与恢复只修改生命周期', async () => {
    const fetcher = mockFetch();
    const child = { code: 'CHILD', name: '子租户', ownerUsername: 'owner', ownerPassword: null };
    await saveAdministration('children', undefined, child);
    expect(fetcher).toHaveBeenLastCalledWith(
      '/api/catalog/identity/children',
      expect.objectContaining({ body: JSON.stringify(child) }),
    );
    for (const status of ['SUSPENDED', 'ACTIVE']) {
      await saveAdministration('children', 'child', { name: '子租户', status });
      expect(fetcher).toHaveBeenLastCalledWith(
        '/api/catalog/identity/children/child',
        expect.objectContaining({
          method: 'PUT',
          body: JSON.stringify({ name: '子租户', status }),
        }),
      );
    }
  });
  it('租户切换提交 membershipId，Owner 转移走专用命令', async () => {
    const fetcher = mockFetch();
    await switchTenant('target-member');
    expect(fetcher).toHaveBeenLastCalledWith(
      '/api/catalog/auth/switch',
      expect.objectContaining({ method: 'POST', body: '{"membershipId":"target-member"}' }),
    );
    await transferOwner('successor');
    expect(fetcher).toHaveBeenLastCalledWith(
      '/api/catalog/identity/owner-transfer',
      expect.objectContaining({ method: 'POST', body: '{"membershipId":"successor"}' }),
    );
  });
  it('保存失败向页面传播错误，不伪造成功', async () => {
    mockFetch(false);
    await expect(saveAdministration('departments', undefined, { name: '部门' })).rejects.toThrow(
      '拒绝访问',
    );
  });
});

it('商品行按钮按同一角色的范围判断，不把全部查看拼接成全部修改', () => {
  const session: Access = {
    ...access,
    departmentId: 'dept',
    grants: [
      { roleId: 'reader', roleCode: 'READER', scopeMode: 'ALL', permissions: ['catalog.spu.read'] },
      {
        roleId: 'writer',
        roleCode: 'WRITER',
        scopeMode: 'SELF',
        permissions: ['catalog.spu.read', 'catalog.spu.update'],
      },
    ],
  };
  const own = { ownerId: session.userId, departmentId: 'dept' };
  const peer = { ownerId: 'someone-else', departmentId: 'dept' };
  expect(canAccessRecord(session, 'catalog.spu.read', peer)).toBe(true);
  expect(canAccessRecord(session, 'catalog.spu.update', own)).toBe(true);
  expect(canAccessRecord(session, 'catalog.spu.update', peer)).toBe(false);
  const department = { ...session, grants: [{ ...session.grants[1]!, scopeMode: 'DEPARTMENT' }] };
  expect(canAccessRecord(department, 'catalog.spu.update', peer)).toBe(true);
  expect(
    canAccessRecord(department, 'catalog.spu.update', { ...peer, departmentId: 'other' }),
  ).toBe(false);
  expect(canAccessRecord({ ...department, departmentId: null }, 'catalog.spu.update')).toBe(false);
  expect(
    canAccessRecord(session, 'catalog.spu.update', { ownerId: null, departmentId: null }),
  ).toBe(false);
});
