'use client';

import { useCallback, useEffect, useState } from 'react';
import { Badge, Button } from '@starcon/ui';
import { AlertTriangle, Building2, Check, RefreshCw } from 'lucide-react';
import type {
  TenantContextView,
  TenantSummaryView,
} from '../../../../../packages/api-contracts/src/index';
import { api, blockWorkspaceRequests } from '@/lib/api';
import { Field, control } from '../common/fields';
import { announceIdentityChange, useIdentity } from './identity-shell';

/**
 * 17.1.1.1 租户列表。
 *
 * 这是全系统唯一允许的跨租户列表查询路径，但它只列**你自己有成员关系的租户**：
 * 集合由服务端从会话成员关系推导，前端传什么都不影响范围，X-Tenant-Id 头也不参与判定。
 * 所以这里不需要（也没有）任何「按租户 id 查」的控件 —— 加一个只会让人以为那个 id 是可信的。
 *
 * 切换租户不是在前端改个变量：真正决定上下文与权限过滤的是服务端会话，
 * 这里只负责把选中的成员身份交给服务端并重新认身份。
 */
export function TenantWorkspace() {
  const { session, reload } = useIdentity();
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [tenants, setTenants] = useState<TenantSummaryView[]>([]);
  const [context, setContext] = useState<TenantContextView | null>(null);
  const [editing, setEditing] = useState(false);

  const load = useCallback(async () => {
    setError('');
    setNotice('');
    try {
      // 列表与当前上下文来自两个端点，一次取回避免界面出现
      // 「列表已切换、上下文还是上一个租户」的中间态。
      const [list, current] = await Promise.all([
        api<TenantSummaryView[]>('identity/tenants'),
        api<TenantContextView>('identity/tenants/current'),
      ]);
      setTenants(list);
      setContext(current);
    } catch (e) {
      setError(e instanceof Error ? e.message : '请求失败，请重试');
    } finally {
      setReady(true);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const switchTo = async (membershipId: string, name: string) => {
    if (!window.confirm(`切换到企业工作空间「${name}」？未保存的内容会丢失。`)) return;
    // 先关闸再发请求：旧租户的异步回调不该在上下文切换期间提交写入。
    blockWorkspaceRequests(true);
    setBusy(true);
    setError('');
    try {
      await api('auth/switch', 'POST', { membershipId });
      announceIdentityChange();
      window.location.reload();
    } catch (e) {
      blockWorkspaceRequests(false);
      setError(e instanceof Error ? e.message : '切换失败，请重试');
      setBusy(false);
    }
  };

  if (!ready)
    return (
      <p role="status" className="text-muted-foreground p-6 text-sm">
        正在加载租户列表…
      </p>
    );

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-copy-13 text-muted-foreground">
          只会列出你有成员关系的租户。切换后权限按新租户重新判定，前端菜单不构成权限控制。
        </p>
        <Button variant="ghost" size="sm" onClick={() => void load()}>
          <RefreshCw className="h-4 w-4" />
          刷新
        </Button>
      </div>

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
      {notice && (
        <p role="status" className="text-success text-sm">
          {notice}
        </p>
      )}

      {context && <ContextPanel context={context} sessionName={session.tenantName} />}

      {context?.managed && (
        <TenantProfileForm
          key={`${context.tenantId}:${context.name}:${context.status}`}
          context={context}
          busy={busy}
          editing={editing}
          onEdit={() => setEditing(true)}
          onCancel={() => setEditing(false)}
          onSaved={async (input, message) => {
            setBusy(true);
            try {
              await api('identity/tenants/current', 'PUT', input);
              setNotice(message);
              setEditing(false);
              await load();
              // 租户改名会改变会话里的租户名，账户菜单要一起跟上。
              await reload();
            } catch (e) {
              setError(e instanceof Error ? e.message : '保存失败，请重试');
            } finally {
              setBusy(false);
            }
          }}
        />
      )}

      <section className="space-y-3">
        <h2 className="text-copy-15 font-semibold">可切换的企业工作空间</h2>
        {tenants.length === 0 ? (
          <div className="bg-card rounded-lg border p-8 text-center">
            <p className="text-copy-13 text-muted-foreground">
              你目前没有可进入的企业工作空间，请联系企业管理员开通成员身份。
            </p>
          </div>
        ) : (
          <ul className="space-y-2">
            {tenants.map((tenant) => (
              <li key={tenant.membershipId}>
                <TenantRow
                  tenant={tenant}
                  busy={busy}
                  onSwitch={() => void switchTo(tenant.membershipId, tenant.name)}
                />
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

function moment(value: string | null) {
  if (!value) return '—';
  const at = new Date(value);
  return Number.isNaN(at.getTime()) ? '—' : at.toLocaleString('zh-CN', { hour12: false });
}

function ContextPanel({
  context,
  sessionName,
}: {
  context: TenantContextView;
  sessionName: string;
}) {
  return (
    <section className="bg-card space-y-3 rounded-lg border p-6">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="text-copy-15 font-semibold">当前上下文</h2>
        <Badge tone={context.status === 'ACTIVE' ? 'success' : 'warning'}>
          {context.status === 'ACTIVE' ? '启用' : '停用'}
        </Badge>
        {context.managed && <Badge tone="neutral">可维护基本信息</Badge>}
      </div>
      <p className="text-copy-13 text-muted-foreground">
        会话当前位于「{sessionName}」。所有数据访问都以此租户为边界，切换前请先保存正在编辑的内容。
      </p>
      <dl className="grid gap-3 break-all text-sm sm:grid-cols-2 lg:grid-cols-3">
        <div>
          <dt className="text-muted-foreground">租户名称</dt>
          <dd>{context.name}</dd>
        </div>
        <div>
          <dt className="text-muted-foreground">租户标识</dt>
          <dd>{context.code}</dd>
        </div>
        <div>
          <dt className="text-muted-foreground">租户 ID</dt>
          <dd>{context.tenantId}</dd>
        </div>
        <div>
          <dt className="text-muted-foreground">上级租户</dt>
          <dd>{context.parentTenantId ?? '无（顶级租户）'}</dd>
        </div>
        <div>
          <dt className="text-muted-foreground">启用成员</dt>
          <dd>{context.memberCount} 人</dd>
        </div>
        <div>
          <dt className="text-muted-foreground">直属子租户</dt>
          <dd>{context.childCount} 个</dd>
        </div>
        <div>
          <dt className="text-muted-foreground">最后进入</dt>
          <dd>{moment(context.lastLoginAt)}</dd>
        </div>
      </dl>
    </section>
  );
}

function TenantRow({
  tenant,
  busy,
  onSwitch,
}: {
  tenant: TenantSummaryView;
  busy: boolean;
  onSwitch: () => void;
}) {
  return (
    <div
      className={`bg-card rounded-md border p-4 ${tenant.current ? 'border-brand' : ''}`}
      data-current={tenant.current}
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-copy-15 flex flex-wrap items-center gap-2 font-medium">
            <Building2 className="text-muted-foreground h-4 w-4" />
            {tenant.name}
            <Badge tone={tenant.status === 'ACTIVE' ? 'success' : 'warning'}>
              {tenant.status === 'ACTIVE' ? '启用' : '停用'}
            </Badge>
            {tenant.current && (
              <Badge tone="info">
                <Check className="h-3 w-3" />
                当前
              </Badge>
            )}
          </p>
          <dl className="text-copy-12 text-muted-foreground mt-2 grid gap-x-6 gap-y-1 sm:grid-cols-2 lg:grid-cols-4">
            <div>
              <dt className="inline">标识：</dt>
              <dd className="inline">{tenant.code}</dd>
            </div>
            <div>
              <dt className="inline">角色：</dt>
              <dd className="inline">
                {tenant.roles.length ? tenant.roles.join('、') : '未分配角色'}
              </dd>
            </div>
            <div>
              <dt className="inline">最后进入：</dt>
              <dd className="inline">{moment(tenant.lastLoginAt)}</dd>
            </div>
            <div>
              <dt className="inline">租户 ID：</dt>
              <dd className="inline break-all">{tenant.tenantId}</dd>
            </div>
          </dl>
        </div>
        <Button
          size="sm"
          variant={tenant.current ? 'ghost' : 'default'}
          disabled={busy || tenant.current}
          onClick={onSwitch}
        >
          {tenant.current ? '当前工作空间' : '切换到此租户'}
        </Button>
      </div>
    </div>
  );
}

/**
 * 租户基本信息维护。
 *
 * 标识（code）只读：它全局唯一且被外部集成引用，改了会让既有映射指向不存在的租户，
 * 所以这里只开放名称与状态。
 */
function TenantProfileForm({
  context,
  busy,
  editing,
  onEdit,
  onCancel,
  onSaved,
}: {
  context: TenantContextView;
  busy: boolean;
  editing: boolean;
  onEdit: () => void;
  onCancel: () => void;
  onSaved: (input: { name: string; status: string }, message: string) => Promise<void>;
}) {
  const [name, setName] = useState(context.name);
  const [status, setStatus] = useState(context.status);
  const [invalid, setInvalid] = useState('');

  if (!editing)
    return (
      <section className="bg-card flex flex-wrap items-center justify-between gap-3 rounded-lg border p-5">
        <div>
          <h2 className="text-copy-15 font-semibold">租户基本信息</h2>
          <p className="text-copy-12 text-muted-foreground mt-1">
            {context.name}（{context.code}）· 标识由平台分配，不可修改。
          </p>
        </div>
        <Button size="sm" onClick={onEdit}>
          编辑基本信息
        </Button>
      </section>
    );

  const submit = async () => {
    if (!name.trim()) {
      setInvalid('租户名称不能为空');
      return;
    }
    if (
      status === 'SUSPENDED' &&
      !window.confirm('停用本租户会让所有成员的现有会话立即失效，且无法自助恢复。确认停用？')
    )
      return;
    setInvalid('');
    await onSaved(
      { name: name.trim(), status },
      status === 'SUSPENDED' ? '本租户已停用，现有会话已失效' : '租户基本信息已更新',
    );
  };

  return (
    <section className="bg-card space-y-4 rounded-lg border p-6">
      <h2 className="text-copy-15 font-semibold">编辑租户基本信息</h2>
      <Field label="租户名称">
        <input
          className={control}
          value={name}
          maxLength={100}
          onChange={(e) => setName(e.target.value)}
        />
      </Field>
      <Field label="状态">
        <select className={control} value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="ACTIVE">启用</option>
          <option value="SUSPENDED">停用</option>
        </select>
      </Field>
      <p className="text-copy-12 text-muted-foreground flex items-start gap-2">
        <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
        标识 {context.code}{' '}
        不可修改。停用会立即让本租户全部成员的现有会话失效，需要平台或上级租户介入才能恢复。
      </p>
      {invalid && (
        <p role="alert" className="text-destructive text-sm">
          {invalid}
        </p>
      )}
      <div className="flex items-center gap-2">
        <Button size="sm" disabled={busy} onClick={() => void submit()}>
          {busy ? '保存中…' : '保存'}
        </Button>
        <Button size="sm" variant="ghost" disabled={busy} onClick={onCancel}>
          取消
        </Button>
      </div>
    </section>
  );
}
