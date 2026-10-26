'use client';
import { useCallback, useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import { Avatar, Button } from '@starcon/ui';
import type { Access } from '../../../../../packages/api-contracts/src/index';
import { useIdentity } from './identity-shell';
import { AdministrationPanel } from './administration-panel';
import { MemberOnboardingWorkspace } from './member-onboarding-workspace';
import { ApprovalFlowWorkspace } from './approval-flow-workspace';
import { ApprovalRuleWorkspace } from './approval-rule-workspace';
import { MyApprovalWorkspace } from './my-approval-workspace';
import { ApprovalRecordWorkspace } from './approval-record-workspace';
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
  ['role-assignments', '角色分配', '给成员分配角色，设定生效时间与法人、店铺、仓库、部门作用范围'],
  ['departments', '部门管理', '维护成员归属与部门数据范围'],
  ['department-leaders', '部门负责人', '指定各部门负责人、管理范围与审批权限，支持代管与临时授权'],
  ['children', '子租户管理', '创建独立工作空间并指定所有者'],
  ['hierarchy', '层级授权', '显式开通父租户对子租户数据的访问范围与有效期'],
  ['audit', '审计日志', '检索关键操作留痕、变更详情与导出'],
  [
    'business-parameters',
    '业务参数',
    '配置审单、库存、售后、成本与展示默认值，并按全局/法人/店铺分档生效',
  ],
  [
    'dictionary',
    '数据字典',
    '维护状态枚举、原因码、分类标签与单位类型，被引用的字典项只能停用不能删除',
  ],
  [
    'platform-flags',
    '平台参数与开关',
    '平台级功能开关、灰度放量、限流阈值与公告；按租户禁用只能收紧，租户不可覆盖平台开关',
  ],
  [
    'platform-ops',
    '平台运维视图',
    '跨租户技术指标看板：在线状态、错误率、任务积压、队列深度、容量占用与告警；只看技术指标，不含租户业务数据',
  ],
  [
    'numbering-rules',
    '编号规则',
    '配置单据编号规则（前缀 + 日期 + 序号 + 后缀），序号按实体与日期重置，支持多级嵌套与预览',
  ],
  [
    'approval-flows',
    '审批流定义',
    '按单据类型注册审批流程：审批节点（角色/岗位/指定人）、串行/并行/条件分支、金额阈值与超时升级规则',
  ],
  [
    'approval-rules',
    '审批规则',
    '按金额区间、跨法人、跨部门、新供应商、高风险商品配置审批触发条件与小额免审规则，免审必须设上限金额',
  ],
  [
    'my-approvals',
    '待我审批',
    '查看停在你名下节点的待办（单据类型、金额、申请人、停留时长、当前节点），支持批量审批与转交',
  ],
  [
    'approval-records',
    '审批记录',
    '检索全租户审批全链路：发起、流转节点、各节点意见与时间、转交、驳回与最终结果，含统计报表与审计导出',
  ],
  [
    'operation-log',
    '操作日志',
    '按用户、时间与请求 ID 检索登录、API 调用、任务执行与错误堆栈，用于运维排障',
  ],
] as const;
/** 17.2.2.2 成员开通有自己的页面权限码，不与成员管理共用一组。 */
// 'hierarchy' 只出现在导航目的地里（点击跳 /org/hierarchy 独立页面），
// 不是 AdministrationPanel 的一种 kind，所以不进这个联合类型；
// 它只需要在 visible() 里能被判出来（visible 的 key 是 string）。
type WorkspaceKind =
  | AdministrationKind
  | 'onboarding'
  | 'approval-flows'
  | 'approval-rules'
  | 'my-approvals'
  | 'approval-records';

function visible(session: Access, key: string) {
  if (key === 'tenants') return true;
  if (key === 'audit') return hasPermission(session, 'identity.audit.read');
  // 17.5.1.1 业务参数是独立页面权限码，不走 canManage 的页面映射。
  if (key === 'business-parameters')
    return hasPermission(session, 'identity.business_parameter.read');
  // 17.5.2.1 数据字典同样是独立页面权限码，不走 canManage 的页面映射。
  if (key === 'dictionary') return hasPermission(session, 'identity.dictionary.read');
  // 17.7.2.1 平台参数与开关同样是独立页面权限码。
  // 它与租户级的 business-parameters **不是一回事**：平台开关全平台唯一，
  // 租户参数是租户自己的业务参数 —— 两个入口都挂在同一个 /org 导航下，
  // 名字混起来会让人以为「租户参数能盖掉平台开关」。
  if (key === 'platform-flags') return hasPermission(session, 'identity.platform_flag.read');
  // 17.7.2.2 平台运维视图同样是独立页面权限码。
  // 它与租户级的「操作日志」不是一回事：操作日志是**本租户**的技术排障流水，
  // 平台运维视图是**跨租户**的技术指标看板 —— 后者要平台运营角色才看得到。
  if (key === 'platform-ops') return hasPermission(session, 'identity.platform_ops.read');
  // 层级授权是独立页面（不在 AdministrationPanel 的四类管理里），走自己的页面权限码。
  // 17.2.2.2 把内联 filter 抽成 visible() 时漏了这条，hierarchy 会落到 canManage 兜底 ——
  // 而 AdministrationKind 里根本没有 'hierarchy'，于是有 tenant.hierarchy.read 的人反而看不到入口。
  if (key === 'hierarchy') return hasPermission(session, 'tenant.hierarchy.read');
  // 部门负责人同样是独立页面权限码，不走 canManage 的页面映射（17.2.1.2）。
  if (key === 'department-leaders')
    return hasPermission(session, 'identity.department_leader.read');
  // 离职与停用同样是独立页面权限码，不走 AdministrationPanel 的四类管理（17.2.2.3）。
  if (key === 'offboarding') return hasPermission(session, 'identity.offboarding.read');
  // 角色分配同样是独立页面权限码，与「角色配置」「成员管理」不是一回事（17.3.2.1）。
  if (key === 'role-assignments') return hasPermission(session, 'identity.assignment.read');
  if (key === 'onboarding')
    return session.grants.some(
      (g) => g.scopeMode === 'ALL' && g.permissions.includes('identity.onboarding.read'),
    );
  // 编号规则同样是独立页面权限码，不走 canManage 的页面映射（17.5.1.2）。
  if (key === 'numbering-rules') return hasPermission(session, 'identity.numbering_rule.read');
  // 操作日志同样是独立页面权限码，不走 canManage 的页面映射（17.5.3.2）。
  // 少了这条会落到 canManage 兜底，而 AdministrationKind 里没有 'operation-log'，
  // 于是有 identity.operation_log.read 的人反而在导航里看不到入口。
  if (key === 'operation-log') return hasPermission(session, 'identity.operation_log.read');
  // 审批流定义（17.4.1.1）是独立页面权限码，不走 AdministrationPanel 的四类管理，
  // 也不走 canManage 的页面映射 —— AdministrationKind 里没有它，落到 canManage 会把入口藏掉。
  if (key === 'approval-flows') return hasPermission(session, 'identity.approval_flow.read');
  // 审批规则（17.4.1.2）是独立页面权限码：改「这次提交要不要走审批」能绕开 17.4.1.1 配好的流程，
  // 所以不复用 identity.approval_flow.*，与 canManage 的页面映射也无关。
  if (key === 'approval-rules') return hasPermission(session, 'identity.approval_rule.read');
  // 待我审批（17.4.2.1）同样是独立页面权限码，且**不复用** approval_flow.* ——
  // 「拿到待办」与「配流程」是两种风险：能配流程的人不该因此自动拿到审批权。
  // 页面权限只决定能不能用这个页面，具体哪些单子归他由节点审批人判定（服务端按成员关系过滤）。
  if (key === 'my-approvals') return hasPermission(session, 'identity.approval_inbox.read');
  // 审批记录（17.4.2.2）是独立页面权限码，且刻意**不复用** approval_inbox.* ——
  // 「能看全部审批记录」与「能批单」是两种风险：审计岗要看得见所有人批了什么，
  // 但绝不该因此拿到审批权。同理也不复用 approval_flow.*（那是配流程的权）。
  if (key === 'approval-records') return hasPermission(session, 'identity.approval_record.read');
  // 17.6.1.1 账簿与本位币是独立页面权限码（路由在 /settings/books），
  // 不走 canManage 的页面映射 —— AdministrationKind 里没有 'books'，
  // 少了这条会落到 canManage 兜底并恒为 false，于是有权限的人也看不到入口。
  if (key === 'books') return hasPermission(session, 'identity.accounting_book.read');
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
      ) : kind === 'approval-flows' ? (
        <ApprovalFlowWorkspace />
      ) : kind === 'approval-rules' ? (
        <ApprovalRuleWorkspace />
      ) : kind === 'my-approvals' ? (
        <MyApprovalWorkspace />
      ) : kind === 'approval-records' ? (
        <ApprovalRecordWorkspace />
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
            {/* 17.6.1.1 账簿与本位币：蓝图的路由是 `/settings/books` 而不是 `/org/...`，
                所以它不进 destinations（那张表的 href 是写死的 `/org/${key}` 模板），
                单开一张卡挂同一个 visible() 权限码。少这张卡页面就没人能进 ——
                17.1.2.2 的账簿页正是因此长期零入口。 */}
            {visible(session, 'books') && (
              <Link
                href="/settings/books"
                className="bg-card hover:border-brand rounded-lg border p-5"
              >
                <h2 className="font-semibold">账簿与本位币</h2>
                <p className="text-muted-foreground mt-2 text-sm">
                  一个法人可以同时用人民币业务账与美元海外账记账：按业务账 /
                  法定账分组，指定默认账簿，并登记业务事实的归属账簿
                </p>
              </Link>
            )}
            {/* 1.4.1.2 列表与表格偏好（路由 /settings/profile）。
                刻意**不挂页面权限码**：这一页里的每一行都是「我自己的」偏好，
                读不到别人的也改不了别人的 —— 边界是数据归属（tenant_id + membership_id + RLS），
                不是功能开关。挂一个权限码只会让管理员新建角色时忘了勾，
                于是普通成员的列表页偏好静默失效。 */}
            <Link
              href="/settings/profile"
              className="bg-card hover:border-brand rounded-lg border p-5"
            >
              <h2 className="font-semibold">列表与表格偏好</h2>
              <p className="text-muted-foreground mt-2 text-sm">
                记住各列表页的列显示、排序、每页条数与筛选条件，下次进入该页自动恢复
              </p>
            </Link>
          </div>
          <p className="text-muted-foreground text-sm">
            用户账号可加入多个租户。成员停用仅影响该租户；父子租户之间不会自动获得数据访问权。
          </p>
        </>
      )}
    </div>
  );
}
