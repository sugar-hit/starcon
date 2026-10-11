import { describe, expect, it } from 'vitest';
import type { PartyRole, PartyRow } from '../../../../../packages/api-contracts/src/index';
import {
  PARTY_ROLES,
  PARTY_STATUSES,
  allowedTargets,
  assignRoleProblem,
  codeProblem,
  contactProblem,
  countryProblem,
  deleteProblem,
  emptyPartyInput,
  emptyRoleInput,
  emailProblem,
  emptyHint,
  hasActiveRole,
  nameProblem,
  partyInputProblem,
  partyTypeLabel,
  phoneProblem,
  reasonHint,
  removedRoles,
  requiresReason,
  roleLabel,
  rolesProblem,
  rowRoleSummary,
  statusLabel,
  toPartyInput,
  toRoleInput,
  transitionProblem,
  usableBlockLabel,
  websiteProblem,
} from './party-model';

/**
 * 3.6.1.1 往来单位档案前端模型单测。
 *
 * <p>重点是**前端不能自己重算判定**：可用性、角色集合都来自后端，
 * 这里只断言「翻成话」的那部分，以及提交前的校验与服务层 `PartyRules` 同口径。
 */

function role(
  type: string,
  status = 'ACTIVE',
): { roleType: string; status: string; note: string | null } {
  return { roleType: type, status, note: null };
}

function detail(overrides: Partial<Parameters<typeof toPartyInput>[0]> = {}) {
  return {
    id: 'p1',
    code: 'ACME',
    name: '星尘科技',
    partyType: 'ORGANIZATION',
    partyTypeLabel: '企业',
    countryCode: 'CN',
    email: 'purchasing@acme.example',
    phone: '+86-138-0000-0000',
    website: null,
    status: 'ACTIVE',
    statusLabel: '可用',
    statusReason: '',
    statusChangedAt: null,
    version: 3,
    roles: [],
    roleSummary: '未指派角色',
    activeRoleCount: 0,
    deleted: false,
    usable: false,
    usableBlockCode: 'PARTY_NO_ROLE',
    usableBlockMessage: '还没有角色',
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z',
    ...overrides,
  } as Parameters<typeof toPartyInput>[0];
}

function partyRole(overrides: Partial<PartyRole> = {}): PartyRole {
  const roleType = overrides.roleType ?? 'SUPPLIER';
  return {
    id: 'r1',
    partyId: 'p1',
    roleType,
    // 标签跟着类型走：写死标签的话，改了 roleType 的用例会拿到「供应商、供应商」这种
    // 看起来像 bug 的结果，排查时先怀疑实现而不是夹具。
    roleLabel: roleLabel(roleType),
    status: 'ACTIVE',
    statusLabel: '使用中',
    note: null,
    version: 0,
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z',
    ...overrides,
  };
}

describe('词表与标签', () => {
  it('主体类型 / 状态 / 角色三张词表都非空且值唯一', () => {
    expect(partyTypeLabel('ORGANIZATION')).toBe('企业');
    expect(partyTypeLabel('INDIVIDUAL')).toBe('个人');
    expect(statusLabel('ACTIVE')).toBe('可用');
    expect(statusLabel('BLACKLISTED')).toBe('拉黑');
    expect(roleLabel('SUPPLIER')).toBe('供应商');
    expect(roleLabel('CARRIER')).toBe('承运商');
    // 未知值原样回显，不吞掉 —— 吞掉的话后端加了新枚举值而前端没跟上时，
    // 运营看到的是一片空白而不是「不认识这个值」。
    expect(partyTypeLabel('WHATEVER')).toBe('WHATEVER');
    expect(statusLabel(null)).toBe('未知');
    expect(roleLabel(undefined)).toBe('未知');
    expect(new Set(PARTY_ROLES.map((r) => r.value)).size).toBe(PARTY_ROLES.length);
    expect(new Set(PARTY_STATUSES.map((s) => s.value)).size).toBe(PARTY_STATUSES.length);
  });

  it('可用性原因码的标签唯一，null 表示可用', () => {
    expect(usableBlockLabel(null)).toBe('可用');
    const codes = ['PARTY_INACTIVE', 'PARTY_BLACKLISTED', 'PARTY_NO_ROLE', 'PARTY_DELETED'];
    const labels = codes.map(usableBlockLabel);
    expect(new Set(labels).size).toBe(codes.length);
    expect(usableBlockLabel('SOMETHING_NEW')).toBe('不可用');
  });
});

describe('状态流转（P4）', () => {
  it('流转表与服务层一致，且自环被拒', () => {
    expect(allowedTargets('ACTIVE')).toEqual(['INACTIVE', 'BLACKLISTED']);
    expect(allowedTargets('INACTIVE')).toEqual(['ACTIVE', 'BLACKLISTED']);
    expect(allowedTargets('BLACKLISTED')).toEqual(['INACTIVE', 'ACTIVE']);
    expect(allowedTargets(null)).toEqual(['ACTIVE', 'INACTIVE', 'BLACKLISTED']);
    // 自环：一次「启用」如果本来就已启用，应当报「状态没有变化」。
    expect(transitionProblem('ACTIVE', 'ACTIVE', '')).toContain('状态没有变化');
  });

  it('进出拉黑都要理由，提示文案与判定一致', () => {
    // 规则是「进出」而不是「进入」：解除拉黑通常也要留档。
    expect(requiresReason('ACTIVE', 'BLACKLISTED')).toBe(true);
    expect(requiresReason('BLACKLISTED', 'ACTIVE')).toBe(true);
    expect(requiresReason('ACTIVE', 'INACTIVE')).toBe(false);
    expect(reasonHint('ACTIVE', 'BLACKLISTED')).toContain('理由');
    expect(reasonHint('ACTIVE', 'INACTIVE')).toBeNull();
    expect(transitionProblem('ACTIVE', 'BLACKLISTED', '  ')).toContain('必须填写理由');
    expect(transitionProblem('BLACKLISTED', 'ACTIVE', '')).toContain('必须填写理由');
    expect(transitionProblem('ACTIVE', 'BLACKLISTED', '结算纠纷')).toBeNull();
  });

  it('方向不对时列出可用流转', () => {
    const problem = transitionProblem('ACTIVE', 'NOPE', '');
    expect(problem).toContain('只能是');
    expect(transitionProblem('ACTIVE', 'INACTIVE', '')).toBeNull();
    expect(transitionProblem('INACTIVE', 'ACTIVE', '')).toBeNull();
    expect(transitionProblem('BLACKLISTED', 'INACTIVE', '已解封')).toBeNull();
    expect(transitionProblem('ACTIVE', 'INACTIVE', 'x'.repeat(501))).toContain('最多 500');
  });
});

describe('校验（与服务层 PartyRules 同口径）', () => {
  it('编码去空白转大写后校验', () => {
    expect(codeProblem('acme')).toBeNull();
    expect(codeProblem(' ACME_01 ')).toBeNull();
    expect(codeProblem('')).toContain('不能为空');
    expect(codeProblem('中文编码')).toContain('英文字母');
    expect(codeProblem('A'.repeat(65))).toContain('最多 64');
  });

  it('名称与主体类型', () => {
    expect(nameProblem('星尘科技')).toBeNull();
    expect(nameProblem('   ')).toContain('不能为空');
    expect(nameProblem('x'.repeat(201))).toContain('最多 200');
    // 校验返回的是**第一条**可执行的原因，所以要先把前面的字段填对，
    // 否则先报出来的是编码为空，测的就不是主体类型这条规则了。
    expect(
      partyInputProblem({
        ...emptyPartyInput(),
        code: 'ACME',
        name: '星尘',
        email: 'a@acme.com',
        partyType: 'COMPANY',
      }),
    ).toContain('主体类型');
  });

  it('国家可空，填了必须是 ISO alpha-2', () => {
    expect(countryProblem(null)).toBeNull();
    expect(countryProblem('  ')).toBeNull();
    expect(countryProblem('cn')).toBeNull();
    expect(countryProblem('CHN')).toContain('ISO 3166-1');
  });

  it('邮箱挡掉漏打 @ 与粘两个邮箱', () => {
    expect(emailProblem(null)).toBeNull();
    expect(emailProblem('purchasing@acme.example')).toBeNull();
    expect(emailProblem('purchasing.acme.example')).toContain('格式不正确');
    expect(emailProblem('a@acme.com,b@acme.com')).toContain('格式不正确');
    expect(emailProblem('purchasing @acme.com')).toContain('空格');
  });

  it('电话的空格被规范化掉而不是拒绝', () => {
    expect(phoneProblem('+86 (138) 0000-0000')).toBeNull();
    expect(phoneProblem('1380000转123')).toContain('只能包含数字');
    expect(phoneProblem('1'.repeat(65))).toContain('最多 64');
  });

  it('官网不能替代邮箱或电话（P3）', () => {
    expect(contactProblem(null, null, 'https://acme.example')).toContain('至少填写一项');
    expect(contactProblem('a@acme.com', null, null)).toBeNull();
    expect(contactProblem(null, '+86-138-0000-0000', null)).toBeNull();
    expect(websiteProblem('acme example com')).toContain('空格');
  });

  it('角色同类型只能一条，且 null 项被当成删掉这条', () => {
    expect(rolesProblem([role('SUPPLIER'), role('CUSTOMER')])).toBeNull();
    expect(rolesProblem([role('SUPPLIER'), role('supplier')])).toContain('只能指派一次');
    expect(rolesProblem([null, role('CARRIER')])).toBeNull();
    expect(rolesProblem([role('DISTRIBUTOR')])).toContain('只能是供应商（SUPPLIER）');
    expect(rolesProblem([role('SUPPLIER', 'BLACKLISTED')])).toContain('角色状态');
    expect(
      rolesProblem([{ roleType: 'SUPPLIER', status: 'ACTIVE', note: 'x'.repeat(2001) }]),
    ).toContain('最多 2000');
  });
});

describe('表单级校验', () => {
  it('状态为可用但没有角色时被拦下（P5）', () => {
    const input = { ...emptyPartyInput(), code: 'ACME', name: '星尘', email: 'a@acme.com' };
    // emptyPartyInput 默认停用 —— 停用不需要角色。
    expect(input.status).toBe('INACTIVE');
    expect(partyInputProblem(input)).toBeNull();
    expect(partyInputProblem({ ...input, status: 'ACTIVE' })).toContain('至少要指派一个角色');
    expect(partyInputProblem({ ...input, status: 'ACTIVE', roles: [role('SUPPLIER')] })).toBeNull();
    // 只有停用中的角色也不算数：它在下游下拉里不出现。
    expect(
      partyInputProblem({ ...input, status: 'ACTIVE', roles: [role('SUPPLIER', 'INACTIVE')] }),
    ).toContain('至少要指派一个角色');
  });

  it('返回的是第一条可执行的原因', () => {
    const problem = partyInputProblem({ ...emptyPartyInput(), code: '', name: '', partyType: '' });
    expect(problem).toContain('编码');
  });

  it('hasActiveRole 忽略空项与停用项', () => {
    expect(hasActiveRole([])).toBe(false);
    expect(hasActiveRole([null])).toBe(false);
    expect(hasActiveRole([role('SUPPLIER', 'INACTIVE')])).toBe(false);
    expect(hasActiveRole([null, role('CUSTOMER')])).toBe(true);
  });
});

describe('角色指派与删除的校验', () => {
  it('拉黑主体不能新增角色，停用主体可以（P6）', () => {
    expect(assignRoleProblem('BLACKLISTED', 'SUPPLIER', false)).toContain('拉黑名单');
    // 停用**允许** —— 「先建主体、挂好角色、再启用」这条正常流程就依赖它。
    expect(assignRoleProblem('INACTIVE', 'SUPPLIER', false)).toBeNull();
    expect(assignRoleProblem('ACTIVE', 'SUPPLIER', false)).toBeNull();
    expect(assignRoleProblem('ACTIVE', 'SUPPLIER', true)).toContain('已经指派过');
  });

  it('删除前列出还挂着的角色', () => {
    expect(deleteProblem([])).toBeNull();
    expect(
      deleteProblem([
        partyRole({ roleType: 'SUPPLIER' }),
        partyRole({ id: 'r2', roleType: 'CUSTOMER' }),
      ]),
    ).toContain('供应商、客户');
  });
});

describe('详情 → 表单', () => {
  it('角色被完整带进表单（整体替换语义）', () => {
    // 少带一条 = 静默解指派，所以这里断言角色集合原样带过去。
    const input = toPartyInput(
      detail({
        roles: [
          partyRole({ id: 'r1', roleType: 'SUPPLIER' }),
          partyRole({ id: 'r2', roleType: 'CUSTOMER' }),
        ],
      }),
    );
    expect(input.roles).toHaveLength(2);
    expect(input.roles?.map((r) => r?.roleType)).toEqual(['SUPPLIER', 'CUSTOMER']);
    expect(input.version).toBe(3);
    expect(input.code).toBe('ACME');
  });

  it('removedRoles 指出这次保存会解掉哪些身份', () => {
    const current = [
      partyRole({ id: 'r1', roleType: 'SUPPLIER' }),
      partyRole({ id: 'r2', roleType: 'CUSTOMER' }),
      partyRole({ id: 'r3', roleType: 'CARRIER' }),
    ];
    expect(
      removedRoles(current, [role('SUPPLIER'), role('CUSTOMER')]).map((r) => r.roleType),
    ).toEqual(['CARRIER']);
    expect(removedRoles(current, [])).toHaveLength(3);
    expect(removedRoles([], [role('SUPPLIER')])).toEqual([]);
    // null 项不算「保留」—— 它在契约里表示显式清空。
    expect(removedRoles(current, [role('SUPPLIER'), role('CUSTOMER'), null])).toHaveLength(1);
  });

  it('toRoleInput 带回行级乐观锁版本号', () => {
    expect(toRoleInput(partyRole({ version: 7 })).version).toBe(7);
    expect(toRoleInput(partyRole({ roleType: 'CARRIER' })).roleType).toBe('CARRIER');
  });

  it('空初值是「停用」而不是「可用」', () => {
    expect(emptyPartyInput().status).toBe('INACTIVE');
    expect(emptyPartyInput().roles).toEqual([]);
    expect(emptyRoleInput().status).toBe('ACTIVE');
  });
});

describe('展示辅助', () => {
  it('角色摘要直接用后端给的值，前端不重算', () => {
    // 只关心这两个字段，所以用 Pick 而不是造一整行 —— 造整行的话以后契约加字段
    // 就要回来补一遍夹具，而补漏的那次不会有人注意到。
    const row = { roles: ['SUPPLIER', 'CUSTOMER'], roleSummary: '供应商、客户' } as Pick<
      PartyRow,
      'roles' | 'roleSummary'
    >;
    expect(rowRoleSummary(row)).toBe('供应商、客户');
    // 缺省时兜底成「未指派角色」，而不是一个空字符串（空格子看不出是「没有」还是「没加载」）。
    expect(rowRoleSummary({ roles: [], roleSummary: '' })).toBe('未指派角色');
  });

  it('两种空态文案不同', () => {
    expect(emptyHint(false)).toContain('还没有往来单位');
    expect(emptyHint(true)).toContain('当前筛选条件下没有');
    // 空态文案要带「为什么该建一条」的引导，不能只说「暂无数据」。
    expect(emptyHint(false)).toContain('不要为每个身份各建一条');
  });
});
