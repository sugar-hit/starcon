'use client';
import { useCallback, useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import { Avatar, Button } from '@starcon/ui';
import { useIdentity } from './identity-shell';
import { AdministrationPanel } from './administration-panel';
import { canManage, hasPermission, isOwner, type AdministrationKind } from './administration-model';

const destinations = [
  ['tenants', '租户列表', '查看你可进入的租户、切换工作空间并维护本租户基本信息'],
  ['members', '用户与成员', '管理本租户成员、启停状态和角色分配'],
  ['roles', '角色配置', '配置页面操作权限与数据范围'],
  [
    'role-assignments',
    '角色分配',
    '给成员分配角色，设定生效时间与法人、店铺、仓库、部门作用范围',
  ],
  ['departments', '部门管理', '维护成员归属与部门数据范围'],
  ['children', '子租户管理', '创建独立工作空间并指定所有者'],
  ['hierarchy', '层级授权', '显式开通父租户对子租户数据的访问范围与有效期'],
  ['audit', '审计日志', '检索关键操作留痕、变更详情与导出'],
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
  const [avatar, setAvatar] = useState<string | undefined>();
  const avatarInput = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (!account) return;
    try {
      setAvatar(window.localStorage.getItem(`starcon.avatar.${session.userId}`) ?? undefined);
    } catch {
      setAvatar(undefined);
    }
  }, [account, session.userId]);
  const onError = useCallback(
    (e: unknown) => setError(e instanceof Error ? e.message : '请求失败'),
    [],
  );
  return (
    <div className="space-y-5">
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
            {account && (
              <div className="border-border flex flex-wrap items-center gap-4 border-b pb-5">
                <Avatar size="lg" {...(avatar ? { src: avatar } : {})} name={session.tenantName} />
                <div>
                  <p className="font-medium">个人头像</p>
                  <p className="text-muted-foreground mt-1 text-xs">
                    支持 JPG、PNG 或 WebP，最大 2 MB
                  </p>
                </div>
                <input
                  ref={avatarInput}
                  type="file"
                  accept="image/png,image/jpeg,image/webp"
                  className="sr-only"
                  onChange={(event) => {
                    const file = event.target.files?.[0];
                    event.target.value = '';
                    if (!file) return;
                    if (file.size > 2_000_000) {
                      setError('头像文件不能超过 2 MB');
                      return;
                    }
                    const reader = new FileReader();
                    reader.onload = () => {
                      const value = typeof reader.result === 'string' ? reader.result : undefined;
                      if (!value) return;
                      try {
                        window.localStorage.setItem(`starcon.avatar.${session.userId}`, value);
                        setAvatar(value);
                        window.dispatchEvent(new Event('starcon-avatar-changed'));
                        setError('');
                      } catch {
                        setError('头像保存失败，请选择更小的图片');
                      }
                    };
                    reader.readAsDataURL(file);
                  }}
                />
                <Button
                  className="sm:ml-auto"
                  variant="outline"
                  onClick={() => avatarInput.current?.click()}
                >
                  {avatar ? '更换头像' : '上传头像'}
                </Button>
              </div>
            )}
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
              // 租户列表对每个成员都开放：不知道自己属于哪些租户的人没法切换工作空间，
              // 所以它不受页面权限码过滤（服务端仍按成员关系裁剪，见 17.1.1.1）。
              // 层级授权是独立页面（不在 AdministrationPanel 的四类管理里），走自己的页面权限码。
              // 角色分配同理：它有自己的页面权限码，与「角色配置」「成员管理」不是一回事。
              .filter(([key]) => {
                if (key === 'tenants') return true;
                if (key === 'audit') return hasPermission(session, 'identity.audit.read');
                if (key === 'hierarchy') return hasPermission(session, 'tenant.hierarchy.read');
                if (key === 'role-assignments')
                  return hasPermission(session, 'identity.assignment.read');
                return canManage(session, key as AdministrationKind);
              })
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
