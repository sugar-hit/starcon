'use client';

import { useCallback, useEffect, useState } from 'react';
import { Badge, Button } from '@starcon/ui';
import { Download, RefreshCw, Search } from 'lucide-react';
import type { AuditDetail, AuditView } from '../../../../../packages/api-contracts/src/index';
import { useIdentity } from './identity-shell';
import {
  actionLabel,
  authorizationSummary,
  changedFields,
  emptyFilters,
  fetchAudit,
  fetchAuditDetail,
  formatTime,
  resourceLabel,
  auditExportUrl,
  type AuditFilters,
} from './audit-model';

/**
 * 17.5.3.1 审计日志。
 *
 * <p>审计记录不可篡改：这里只有检索、变更详情与导出，没有任何编辑入口。
 * 变更详情里的 before/after 是**写入时已脱敏**的值，因此这里不会、也无法还原出明文。
 */
export function AuditWorkspace() {
  const { session } = useIdentity();
  const [filters, setFilters] = useState<AuditFilters>(emptyFilters);
  const [applied, setApplied] = useState<AuditFilters>(emptyFilters);
  const [rows, setRows] = useState<AuditView[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [detail, setDetail] = useState<AuditDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const readable = session.permissions.includes('identity.audit.read');
  const size = 50;

  const load = useCallback(async () => {
    if (!readable) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    try {
      const result = await fetchAudit(applied, page, size);
      setRows(result.items);
      setTotal(result.total);
    } catch (e) {
      setError(e instanceof Error ? e.message : '审计记录加载失败');
    } finally {
      setLoading(false);
    }
  }, [applied, page, readable]);

  useEffect(() => {
    void load();
  }, [load]);

  const openDetail = useCallback(async (id: string) => {
    setDetailLoading(true);
    setError('');
    try {
      setDetail(await fetchAuditDetail(id));
    } catch (e) {
      setError(e instanceof Error ? e.message : '变更详情加载失败');
    } finally {
      setDetailLoading(false);
    }
  }, []);

  if (!readable)
    return (
      <p className="rounded-lg border p-6 text-sm">
        当前角色没有审计日志查看权限，请联系租户所有者开通。
      </p>
    );

  const pages = Math.max(1, Math.ceil(total / size));
  const update = (patch: Partial<AuditFilters>) => setFilters((current) => ({ ...current, ...patch }));

  return (
    <section className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-xl font-semibold">审计日志</h2>
        <div className="flex items-center gap-2">
          {/* 导出走普通链接下载，不走 api()：它是 CSV 而非 JSON，走 fetch 只会拿到一串引号转义的文本。 */}
          <a
            href={auditExportUrl(applied)}
            download
            className="border-border hover:bg-accent inline-flex h-8 items-center gap-1.5 rounded-md border px-3 text-sm"
          >
            <Download className="h-4 w-4" />
            导出 CSV
          </a>
          <Button variant="ghost" size="sm" onClick={() => void load()}>
            <RefreshCw className="h-4 w-4" />
            刷新
          </Button>
        </div>
      </div>
      <p className="text-muted-foreground text-sm">
        记录关键操作的不可篡改留痕：操作人、动作、资源、当时的授权上下文与脱敏后的变更详情。
        密码、令牌与完整客户地址不会进入审计。
      </p>

      <form
        className="bg-card space-y-3 rounded-lg border p-4"
        onSubmit={(event) => {
          event.preventDefault();
          setPage(0);
          setApplied(filters);
        }}
      >
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">资源类型</span>
            <select
              value={filters.resourceType}
              onChange={(e) => update({ resourceType: e.target.value })}
              className="border-border bg-card h-9 w-full rounded-md border px-2"
            >
              <option value="">全部类型</option>
              <option value="identity.role">角色</option>
              <option value="identity.member">成员</option>
              <option value="identity.department">部门</option>
              <option value="identity.legal_entity">法人</option>
              <option value="tenant.child">子租户</option>
            </select>
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">动作</span>
            <select
              value={filters.action}
              onChange={(e) => update({ action: e.target.value })}
              className="border-border bg-card h-9 w-full rounded-md border px-2"
            >
              <option value="">全部动作</option>
              <option value="RoleSaved">保存角色</option>
              <option value="RoleDeleted">删除角色</option>
              <option value="MemberCreated">新增成员</option>
              <option value="MemberUpdated">修改成员</option>
              <option value="MemberRemoved">移除成员</option>
              <option value="OwnerTransferred">转移所有者</option>
              <option value="DepartmentCreated">新增部门</option>
              <option value="DepartmentUpdated">修改部门</option>
              <option value="DepartmentMoved">调整部门层级</option>
              <option value="DepartmentDeleted">删除部门</option>
              <option value="ChildCreated">创建子租户</option>
              <option value="ChildUpdated">修改子租户</option>
              <option value="LegalEntityCreated">新增法人</option>
              <option value="LegalEntityUpdated">修改法人</option>
              <option value="LegalEntityDeleted">删除法人</option>
            </select>
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">资源 ID</span>
            <input
              value={filters.resourceId}
              onChange={(e) => update({ resourceId: e.target.value })}
              placeholder="精确匹配"
              className="border-border bg-card h-9 w-full rounded-md border px-2"
            />
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">操作人 ID</span>
            <input
              value={filters.actorId}
              onChange={(e) => update({ actorId: e.target.value })}
              placeholder="精确匹配"
              className="border-border bg-card h-9 w-full rounded-md border px-2"
            />
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">开始时间</span>
            <input
              type="datetime-local"
              value={filters.from}
              onChange={(e) => update({ from: e.target.value })}
              className="border-border bg-card h-9 w-full rounded-md border px-2"
            />
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">结束时间</span>
            <input
              type="datetime-local"
              value={filters.to}
              onChange={(e) => update({ to: e.target.value })}
              className="border-border bg-card h-9 w-full rounded-md border px-2"
            />
          </label>
        </div>
        <div className="flex items-center gap-2">
          <Button type="submit" size="sm">
            <Search className="h-4 w-4" />
            检索
          </Button>
          <Button
            type="button"
            size="sm"
            variant="ghost"
            onClick={() => {
              setFilters(emptyFilters);
              setApplied(emptyFilters);
              setPage(0);
            }}
          >
            重置
          </Button>
        </div>
      </form>

      {error && (
        <div
          role="alert"
          className="border-destructive/30 bg-destructive/5 text-destructive rounded-md border p-3 text-sm"
        >
          {error}
          <Button variant="link" onClick={() => void load()}>
            重试
          </Button>
        </div>
      )}

      {loading ? (
        <p role="status" className="text-muted-foreground text-sm">
          正在加载审计记录…
        </p>
      ) : rows.length === 0 ? (
        <div className="bg-card rounded-lg border p-8 text-center">
          <p className="text-muted-foreground text-sm">
            {applied === emptyFilters ? '还没有审计记录' : '没有符合筛选条件的审计记录'}
          </p>
        </div>
      ) : (
        <>
          <ul className="divide-y rounded-lg border">
            {rows.map((row) => (
              <li key={row.id} className="flex flex-wrap items-center justify-between gap-3 p-4">
                <div className="min-w-0 space-y-1">
                  <p className="flex flex-wrap items-center gap-2 font-medium">
                    {actionLabel(row.action)}
                    <Badge>{resourceLabel(row.resourceType)}</Badge>
                    {row.resourceLabel && (
                      <span className="text-muted-foreground text-xs">{row.resourceLabel}</span>
                    )}
                  </p>
                  <p className="text-muted-foreground text-xs">
                    {row.actorName ?? '未知操作人'} · {formatTime(row.occurredAt)} · ID {row.id}
                  </p>
                </div>
                <Button variant="outline" size="sm" onClick={() => void openDetail(row.id)}>
                  变更详情
                </Button>
              </li>
            ))}
          </ul>
          <div className="flex items-center justify-between gap-3 text-sm">
            <p className="text-muted-foreground">
              共 {total} 条，第 {page + 1} / {pages} 页
            </p>
            <div className="flex items-center gap-2">
              <Button
                size="sm"
                variant="outline"
                disabled={page === 0}
                onClick={() => setPage((current) => Math.max(0, current - 1))}
              >
                上一页
              </Button>
              <Button
                size="sm"
                variant="outline"
                disabled={page + 1 >= pages}
                onClick={() => setPage((current) => current + 1)}
              >
                下一页
              </Button>
            </div>
          </div>
        </>
      )}

      {detailLoading && (
        <p role="status" className="text-muted-foreground text-sm">
          正在加载变更详情…
        </p>
      )}

      {detail && (
        <AuditDetailPanel detail={detail} onClose={() => setDetail(null)} />
      )}
    </section>
  );
}

function AuditDetailPanel({ detail, onClose }: { detail: AuditDetail; onClose: () => void }) {
  const authorization = authorizationSummary(detail);
  const changes = changedFields(detail);
  return (
    <div className="bg-card space-y-4 rounded-lg border p-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h3 className="font-semibold">
          {actionLabel(detail.action)} · {resourceLabel(detail.resourceType)}
        </h3>
        <Button size="sm" variant="ghost" onClick={onClose}>
          关闭
        </Button>
      </div>
      <dl className="grid gap-3 text-sm sm:grid-cols-2">
        <div>
          <dt className="text-muted-foreground">发生时间</dt>
          <dd>{formatTime(detail.occurredAt)}</dd>
        </div>
        <div>
          <dt className="text-muted-foreground">操作人</dt>
          <dd>
            {detail.actorName ?? '未知'}
            {detail.owner ? '（租户所有者）' : ''}
          </dd>
        </div>
        <div>
          <dt className="text-muted-foreground">资源</dt>
          <dd className="break-all">
            {detail.resourceLabel ?? '—'} / {detail.resourceId}
          </dd>
        </div>
        <div>
          <dt className="text-muted-foreground">授权上下文</dt>
          <dd>
            角色 {authorization.roles} · 范围 {authorization.scopes}
            {authorization.departmentId ? ` · 部门 ${authorization.departmentId}` : ''}
          </dd>
        </div>
      </dl>

      <div className="space-y-2">
        <p className="text-muted-foreground text-xs">
          变更详情（写入时已脱敏，密码、令牌与完整地址不会出现在这里）
        </p>
        {changes.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            该记录没有可展示的变更详情（迁移前的历史记录未采集前后值）。
          </p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground border-b text-left">
                <th className="py-1.5">字段</th>
                <th className="py-1.5">变更前</th>
                <th className="py-1.5">变更后</th>
              </tr>
            </thead>
            <tbody>
              {changes.map((row) => (
                <tr key={row.field} className="border-b last:border-0">
                  <td className="py-1.5 pr-3 align-top font-medium">{row.field}</td>
                  <td className="text-muted-foreground py-1.5 pr-3 align-top break-all">{row.from}</td>
                  <td className="py-1.5 align-top break-all">{row.to}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}