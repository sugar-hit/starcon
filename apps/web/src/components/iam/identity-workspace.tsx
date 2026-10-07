'use client';
import { useCallback, useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import { Avatar, Button } from '@starcon/ui';
import type { Access } from '../../../../../packages/api-contracts/src/catalog';
import { useIdentity } from './identity-shell';
import { AdministrationPanel } from './administration-panel';
import { MemberOnboardingWorkspace } from './member-onboarding-workspace';
import { canManage, hasPermission, isOwner, type AdministrationKind } from './administration-model';

const destinations = [
  ['tenants', '租户列表', '查看你可进入的租户、切换工作空间并维护本租户基本信息'],
  ['onboarding', '成员开通', '为新成员开通账号、接入成员关系并设定初始部门与角色'],
  ['members', '用户与成员', '管理本租户成员、启停状态和角色分配'],
  [
    'offboarding',
    '离职与停用',
    '停用离职成员、转移在办单据与审批责任、回收待办与任务，全程保留历史记录',
  ],
  ['roles', '角色配置', '配置页面操作权限与数据范围'],
  [
    'role-assignments',
    '角色分配',
    '给成员分配角色，设定生效时间与法人、店铺、仓库、部门作用范围',
  ],
  ['departments', '部门管理', '维护成员归属与部门数据范围'],
  ['department-leaders', '部门负责人', '指定各部门负责人、管理范围与审批权限，支持代管与临时授权'],
  ['children', '子租户管理', '创建独立工作空间并指定所有者'],
  ['hierarchy', '层级授权', '显式开通父租户对子租户数据的访问范围与有效期'],
  ['audit', '审计日志', '检索关键操作留痕、变更详情与导出'],
  ['business-parameters', '业务参数', '配置审单、库存、售后、成本与展示默认值，并按全局/法人/店铺分档生效'],
] as const;
/** 17.2.2.2 成员开通有自己的页面权限码，不与成员管理共用一组。 */
// 'hierarchy' 只出现在导航目的地里（点击跳 /org/hierarchy 独立页面），
// 不是 AdministrationPanel 的一种 kind，所以不进这个联合类型；
// 它只需要在 visible() 里能被判出来（visible 的 key 是 string）。
type WorkspaceKind = AdministrationKind | 'onboarding';

function visible(session: Access, key: string) {
  if (key === 'tenants') return true;
  if (key === 'audit') return hasPermission(session, 'identity.audit.read');
  // 17.5.1.1 业务参数是独立页面权限码，不走 canManage 的页面映射。
  if (key === 'business-parameters')
    return hasPermission(session, 'identity.business_parameter.read');
  // 层级授权是独立页面（不在 AdministrationPanel 的四类管理里），走自己的页面权限码。
  // 17.2.2.2 把内联 filter 抽成 visible() 时漏了这条，hierarchy 会落到 canManage 兜底 ——
  // 而 AdministrationKind 里根本没有 'hierarchy'，于是有 tenant.hierarchy.read 的人反而看不到入口。
  if (key === 'hierarchy') return hasPermission(session, 'tenant.hierarchy.read');
  // 部门负责人同样是独立页面权限码，不走 canManage 的页面映射（17.2.1.2）。
  if (key === 'department-leaders') return hasPermission(session, 'identity.department_leader.read');
  // 离职与停用同样是独立页面权限码，不走 AdministrationPanel 的四类管理（17.2.2.3）。
  if (key === 'offboarding') return hasPermission(session, 'identity.offboarding.read');
  // 角色分配同样是独立页面权限码，与「角色配置」「成员管理」不是一回事（17.3.2.1）。
  if (key === 'role-assignments')
    return hasPermission(session, 'identity.assignment.read');
  if (key === 'onboarding')
    return session.grants.some(
      (g) => g.scopeMode === 'ALL' && g.permissions.includes('identity.onboarding.read'),
    );
  return canManage(session, key as AdministrationKind);
}
export function IdentityWorkspace({
  kind,
  account = false,
}: {
  kind?: WorkspaceKind;
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
      {kind === 'onboarding' ? (
        <MemberOnboardingWorkspace session={session} onError={onError} />
      ) : kind ? (
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
              .filter(([key]) => visible(session, key))
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
