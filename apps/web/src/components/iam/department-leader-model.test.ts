import { afterEach, describe, expect, it, vi } from 'vitest';
import type {
  ApprovalChainView,
  DepartmentLeaderView,
  MemberView,
} from '../../../../../packages/api-contracts/src/index';
import {
  addGrant,
  addProxy,
  authoritySummary,
  chainNeedsFallback,
  clearLeader,
  fetchApprovalChain,
  fetchLeaders,
  findLeader,
  formatAmount,
  needsFallback,
  orderedEntries,
  removeGrant,
  removeProxy,
  saveLeader,
  scopeLabel,
  selectableMembers,
  sourceLabel,
} from './department-leader-model';

function leader(overrides: Partial<DepartmentLeaderView> = {}): DepartmentLeaderView {
  return {
    leaderId: 'leader-1',
    departmentId: 'dept-1',
    departmentCode: 'RD',
    departmentName: '研发部',
    membershipId: 'member-1',
    username: 'alice',
    scopeMode: 'SUBTREE',
    approvalLimit: 5000,
    approvalCurrency: 'USD',
    note: '',
    proxyIds: [],
    grantIds: [],
    createdAt: '2026-10-07T09:00:00Z',
    updatedAt: '2026-10-07T09:00:00Z',
    ...overrides,
  };
}

const member = (overrides: Partial<MemberView>): MemberView => ({
  id: 'm',
  userId: 'u',
  username: 'u',
  status: 'ACTIVE',
  departmentId: null,
  assignments: [],
  owner: false,
  ...overrides,
});

afterEach(() => vi.unstubAllGlobals());
function mockFetch(ok = true) {
  const fetcher = vi.fn().mockImplementation(() =>
    Promise.resolve(
      new Response(JSON.stringify(ok ? 'saved' : { detail: '拒绝访问' }), {
        status: ok ? 200 : 403,
      }),
    ),
  );
  vi.stubGlobal('fetch', fetcher);
  return fetcher;
}

describe('部门负责人模型', () => {
  it('负责人可为空：没有负责人的部门仍在列表里并被标为需要兜底', () => {
    const blank = leader({
      leaderId: null,
      // 另一个部门：没有负责人的部门仍然有自己的行，靠 departmentId 定位
      departmentId: 'dept-2',
      departmentCode: 'OPS',
      departmentName: '运营部',
      membershipId: null,
      username: null,
      scopeMode: null,
      approvalLimit: null,
      approvalCurrency: null,
    });
    expect(needsFallback(blank)).toBe(true);
    expect(findLeader([leader(), blank], blank.departmentId)).toBe(blank);
    // 找不到返回 null 而不是抛错，页面据此决定是否渲染详情
    expect(findLeader([leader()], 'missing')).toBeNull();
  });

  it('授权口径摘要区分「不设上限」与具体额度', () => {
    expect(authoritySummary(leader())).toBe('本部门及下级部门 · 额度 5000 USD');
    // 空额度是「不设上限，由审批流分级兜底」，与 0 完全不是一回事
    expect(authoritySummary(leader({ approvalLimit: null }))).toBe(
      '本部门及下级部门 · 不设审批额度上限',
    );
    expect(authoritySummary(leader({ scopeMode: null, approvalLimit: null }))).toBe(
      '— · 不设审批额度上限',
    );
  });

  it('金额按十进制展示，不经过浮点重新格式化', () => {
    expect(formatAmount(1200.5, 'USD')).toBe('1200.5 USD');
    expect(formatAmount(9000, 'USD')).toBe('9000 USD');
    expect(formatAmount(0.1, 'JPY')).toBe('0.1 JPY');
    expect(formatAmount(null, 'USD')).toBe('—');
  });

  it('未知枚举值回落原值，不显示成空白', () => {
    expect(scopeLabel('TENANT')).toBe('全租户');
    expect(scopeLabel('FUTURE_SCOPE')).toBe('FUTURE_SCOPE');
    expect(scopeLabel(null)).toBe('—');
    expect(sourceLabel('PROXY')).toBe('代管');
    expect(sourceLabel('FUTURE_SOURCE')).toBe('FUTURE_SOURCE');
  });

  it('只列启用中的成员可选为负责人', () => {
    const members = [
      member({ id: 'a', username: 'alice' }),
      member({ id: 'b', username: 'bob', status: 'INACTIVE' }),
    ];
    expect(selectableMembers(members).map((m) => m.username)).toEqual(['alice']);
  });

  it('审批链按 seq 排序，并在空链时报出需要兜底', () => {
    const chain: ApprovalChainView = {
      departmentId: 'dept-1',
      departmentCode: 'RD',
      departmentName: '研发部',
      asOf: '2026-10-07T09:00:00Z',
      requiresFallback: false,
      entries: [
        {
          seq: 3,
          source: 'GRANT',
          membershipId: 'm3',
          username: 'carol',
          scopeMode: 'DEPARTMENT',
          approvalLimit: 1000,
          approvalCurrency: 'USD',
          startsAt: '2026-03-01T01:00:00Z',
          endsAt: '2026-03-31T10:00:00Z',
        },
        {
          seq: 1,
          source: 'LEADER',
          membershipId: 'm1',
          username: 'alice',
          scopeMode: 'SUBTREE',
          approvalLimit: 5000,
          approvalCurrency: 'USD',
          startsAt: '2026-10-07T09:00:00Z',
          endsAt: null,
        },
        {
          seq: 2,
          source: 'PROXY',
          membershipId: 'm2',
          username: 'bob',
          scopeMode: 'SUBTREE',
          approvalLimit: 5000,
          approvalCurrency: 'USD',
          startsAt: '2026-10-07T09:00:00Z',
          endsAt: null,
        },
      ],
    };
    expect(orderedEntries(chain).map((e) => e.source)).toEqual(['LEADER', 'PROXY', 'GRANT']);
    expect(chainNeedsFallback(chain)).toBe(false);

    const empty = { ...chain, entries: [], requiresFallback: true };
    expect(chainNeedsFallback(empty)).toBe(true);
    expect(orderedEntries(empty)).toEqual([]);
    // 还没加载出来时也算「需要兜底」而不是当作空链正常渲染
    expect(chainNeedsFallback(null)).toBe(true);
  });

  it('端点形状：负责人、审批链、代管与临时授权各自寻址', async () => {
    const fetcher = mockFetch();
    await fetchLeaders();
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining('/identity/department-leaders'),
      expect.objectContaining({ method: 'GET' }),
    );

    await saveLeader('dept-1', {
      membershipId: 'm1',
      scopeMode: 'DEPARTMENT',
      approvalLimit: null,
      approvalCurrency: null,
      note: '',
    });
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining('/identity/departments/dept-1/leader'),
      expect.objectContaining({ method: 'PUT' }),
    );

    await fetchApprovalChain('dept-1');
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining('/identity/departments/dept-1/approval-chain'),
      expect.objectContaining({ method: 'GET' }),
    );

    // 查历史时刻必须带上 asOf，否则问的是「此刻」而不是当时
    await fetchApprovalChain('dept-1', '2026-03-15T00:00:00Z');
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining('/approval-chain?asOf=2026-03-15T00%3A00%3A00Z'),
      expect.objectContaining({ method: 'GET' }),
    );

    await addProxy('dept-1', { membershipId: 'm2', reason: '休假', startsAt: null, endsAt: null });
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining('/identity/departments/dept-1/leader/proxies'),
      expect.objectContaining({ method: 'POST' }),
    );

    await removeProxy('dept-1', 'proxy-1');
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining('/leader/proxies/proxy-1'),
      expect.objectContaining({ method: 'DELETE' }),
    );

    await addGrant('dept-1', {
      membershipId: 'm3',
      approvalLimit: 1000,
      approvalCurrency: 'USD',
      startsAt: '2026-03-01T01:00:00Z',
      endsAt: '2026-03-31T10:00:00Z',
      reason: '出差',
    });
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining('/identity/departments/dept-1/leader/grants'),
      expect.objectContaining({ method: 'POST' }),
    );

    await removeGrant('dept-1', 'grant-1');
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining('/leader/grants/grant-1'),
      expect.objectContaining({ method: 'DELETE' }),
    );

    await clearLeader('dept-1');
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining('/identity/departments/dept-1/leader'),
      expect.objectContaining({ method: 'DELETE' }),
    );
  });
});
