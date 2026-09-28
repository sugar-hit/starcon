'use client';
import { useCallback, useState } from 'react';
import Link from 'next/link';
import { useIdentity } from './identity-shell';
import { AdministrationPanel } from './administration-panel';
import { canManage, isOwner, type AdministrationKind } from './administration-model';

const destinations = [
  ['members', '用户与成员', '管理本租户成员、启停状态和角色分配'],
  ['roles', '角色配置', '配置页面操作权限与数据范围'],
  ['departments', '部门管理', '维护成员归属与部门数据范围'],
  ['children', '子租户管理', '创建独立工作空间并指定所有者'],
] as const;
export function IdentityWorkspace({
  kind,
  account = false,
}: {
  kind?: AdministrationKind;
  account?: boolean;
}) {
  const { session, reload } = useIdentity();
  const [error, setError] = useState('');
  const onError = useCallback(
    (e: unknown) => setError(e instanceof Error ? e.message : '请求失败'),
    [],
  );
  return (
    <div className="space-y-5">
      <nav aria-label="组织管理功能" className="flex flex-wrap gap-3 border-b pb-3">
        <Link href="/org">租户概览</Link>
        {destinations
          .filter(([key]) => canManage(session, key))
          .map(([key, label]) => (
            <Link
              key={key}
              href={`/org/${key}`}
              aria-current={kind === key ? 'page' : undefined}
              className={kind === key ? 'text-brand font-semibold' : 'text-muted-foreground'}
            >
              {label}
            </Link>
          ))}
      </nav>
      {error && (
        <p role="alert" className="text-destructive">
          {error}
        </p>
      )}
      {kind ? (
        <AdministrationPanel
          key={`${session.tenantId}:${kind}`}
          kind={kind}
          session={session}
          reloadSession={reload}
          onError={onError}
        />
      ) : (
        <>
          <section className="bg-card space-y-3 rounded-lg border p-6">
            <h2 className="text-xl font-semibold">{account ? '我的账户' : '租户概览'}</h2>
            <p className="text-lg">{session.tenantName}</p>
            <p className="text-muted-foreground text-sm">
              当前身份：{isOwner(session) ? '租户所有者' : '租户成员'}。角色授权仅在当前租户内生效。
            </p>
            <dl className="grid gap-3 break-all text-sm sm:grid-cols-2">
              <div>
                <dt className="text-muted-foreground">用户 ID</dt>
                <dd>{session.userId}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground">租户 ID</dt>
                <dd>{session.tenantId}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground">成员身份 ID</dt>
                <dd>{session.membershipId}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground">已分配角色</dt>
                <dd>{session.grants.map((g) => g.roleCode).join('、') || '尚未分配角色'}</dd>
              </div>
            </dl>
          </section>
          <div className="grid gap-4 sm:grid-cols-2">
            {destinations
              .filter(([key]) => canManage(session, key))
              .map(([key, label, description]) => (
                <Link
                  key={key}
                  href={`/org/${key}`}
                  className="bg-card hover:border-brand rounded-lg border p-5"
                >
                  <h2 className="font-semibold">{label}</h2>
                  <p className="text-muted-foreground mt-2 text-sm">{description}</p>
                </Link>
              ))}
          </div>
          <p className="text-muted-foreground text-sm">
            用户账号可加入多个租户。成员停用仅影响该租户；父子租户之间不会自动获得数据访问权。
          </p>
        </>
      )}
    </div>
  );
}
