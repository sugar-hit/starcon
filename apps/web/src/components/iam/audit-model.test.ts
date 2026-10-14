import { describe, expect, it } from 'vitest';
import type { AuditDetail } from '../../../../../packages/api-contracts/src/index';
import {
  actionLabel,
  auditExportUrl,
  auditQuery,
  authorizationSummary,
  changedFields,
  emptyFilters,
  fetchAuditDetail,
  resourceLabel,
  type AuditFilters,
} from './audit-model';

const detail = (changes: unknown, authorization: unknown = {}) =>
  ({
    id: 'a1',
    action: 'RoleSaved',
    resourceType: 'identity.role',
    resourceId: 'r1',
    resourceLabel: 'EDITOR',
    actorId: 'u1',
    actorName: 'admin',
    occurredAt: '2026-10-06T09:00:00Z',
    owner: true,
    authorization,
    changes,
  }) as unknown as AuditDetail;

describe('auditQuery', () => {
  it('omits blank filters so an empty param is never sent', () => {
    const query = auditQuery(emptyFilters, 0, 50);
    expect(query).toBe('page=0&size=50');
    expect(query).not.toContain('actorId=');
    expect(query).not.toContain('resourceType=');
  });

  it('carries every provided filter', () => {
    const filters: AuditFilters = {
      ...emptyFilters,
      resourceType: 'identity.role',
      actorId: 'u-1',
      action: 'RoleSaved',
      resourceId: 'r-1',
    };
    const query = auditQuery(filters, 2, 20);
    expect(query).toContain('page=2');
    expect(query).toContain('size=20');
    expect(query).toContain('resourceType=identity.role');
    expect(query).toContain('actorId=u-1');
    expect(query).toContain('action=RoleSaved');
    expect(query).toContain('resourceId=r-1');
  });

  it('converts local datetime input into an absolute instant', () => {
    const query = auditQuery({ ...emptyFilters, from: '2026-10-06T09:00' }, 0, 50);
    // 时间检索必须带时区语义，否则服务端与浏览器会对「9 点」的理解差一个时区偏移。
    expect(query).toContain('from=');
    expect(decodeURIComponent(query)).toMatch(/from=.*(Z|[+-]\d{2}:\d{2})$/);
  });

  it('export reuses the list conditions so both return the same rows', () => {
    const filters: AuditFilters = { ...emptyFilters, resourceType: 'identity.member' };
    const url = auditExportUrl(filters);
    expect(url).toContain('/api/iam/identity/audit/export');
    // 关键：导出的筛选条件与列表一致，所以「导出的 CSV」就是「当前看到的那些行」。
    // page/size 会一并带上，但导出端点忽略它们（导出全量命中结果，不分页）。
    expect(url).toContain('resourceType=identity.member');
  });
});

describe('labels', () => {
  it('translates known actions and falls back for unknown ones', () => {
    expect(actionLabel('MemberUpdated')).toBe('修改成员');
    // 未知动作必须回落成原值：吞掉会让人以为「没记录这个动作」
    expect(actionLabel('SomethingNew')).toBe('SomethingNew');
    expect(resourceLabel('tenant.child')).toBe('子租户');
    expect(resourceLabel('unknown.type')).toBe('unknown.type');
  });
});

describe('changedFields', () => {
  it('lists only the fields that actually changed', () => {
    const rows = changedFields(
      detail({
        before: { code: 'A', name: '旧名', status: 'ACTIVE' },
        after: { code: 'A', name: '新名', status: 'ACTIVE' },
      }),
    );
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ field: 'name', from: '旧名', to: '新名' });
  });

  it('treats an empty permission list as a real value, not missing data', () => {
    const rows = changedFields(
      detail({ before: { permissions: ['a.read'] }, after: { permissions: [] } }),
    );
    expect(rows[0]?.to).toBe('—');
  });

  it('treats creation as all-new and deletion as all-gone', () => {
    const created = changedFields(detail({ before: null, after: { code: 'B' } }));
    expect(created.map((r) => r.field)).toEqual(['code']);

    const deleted = changedFields(detail({ before: { code: 'B' }, after: null }));
    expect(deleted.map((r) => r.field)).toEqual(['code']);
  });

  it('returns nothing when the record has no change detail', () => {
    expect(changedFields(detail({ before: null, after: null, unavailable: true }))).toEqual([]);
    expect(changedFields(detail({}))).toEqual([]);
  });

  it('renders arrays and nulls readably', () => {
    const rows = changedFields(
      detail({ before: { permissions: ['a.read', 'a.update'] }, after: { permissions: [] } }),
    );
    expect(rows[0]?.from).toBe('a.read、a.update');
    expect(rows[0]?.to).toBe('—');
  });
});

describe('authorizationSummary', () => {
  it('summarises roles, scopes and permission count', () => {
    const summary = authorizationSummary(
      detail(
        { before: null, after: null },
        { roles: ['OWNER'], scopeModes: ['ALL'], permissions: ['a.read', 'b.read'] },
      ),
    );
    expect(summary.roles).toBe('OWNER');
    expect(summary.scopes).toBe('ALL');
    expect(summary.permissions).toHaveLength(2);
  });

  it('does not claim a role or scope that the record lacks', () => {
    const summary = authorizationSummary(detail({ before: null, after: null }, {}));
    expect(summary.roles).toBe('无角色');
    expect(summary.scopes).toBe('无范围');
  });
});

describe('fetchAuditDetail', () => {
  it('requests the detail endpoint for a single record', async () => {
    const calls: string[] = [];
    const original = globalThis.fetch;
    globalThis.fetch = (async (input: RequestInfo | URL) => {
      calls.push(String(input));
      return new Response(JSON.stringify(detail({ before: null, after: null })), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    }) as typeof fetch;
    try {
      const result = await fetchAuditDetail('abc');
      expect(calls[0]).toContain('/api/iam/identity/audit/abc');
      expect(result.id).toBe('a1');
    } finally {
      globalThis.fetch = original;
    }
  });
});
