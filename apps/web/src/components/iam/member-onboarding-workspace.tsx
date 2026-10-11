'use client';
import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Button } from '@starcon/ui';
import type {
  Access,
  DepartmentView,
  OnboardingResult,
  OnboardingView,
  RoleView,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '@/lib/api';
import { Field, Editor, control } from '../common/fields';
import {
  accountModeLabel,
  assignableRoles,
  canManageOnboarding,
  completeOnboarding,
  createOnboarding,
  flattenDepartmentOptions,
  needsCredentialHandover,
  onboardingInput,
  onboardingStatusLabel,
  scopesFor,
  toggleOnboardingRole,
  type OnboardingRoleInput,
} from './onboarding-model';

/**
 * 17.2.2.2 成员开通与入职工作区。
 *
 * <p>这个页面唯一需要特别小心的东西是**初始凭据**：它只在开通成功的那一次响应里出现，
 * 之后服务端再也没有第二个地方能取到它。所以凭据面板是组件级 state、只留到用户关掉它为止，
 * 不写 localStorage、不进 URL、不随列表刷新重新拉——刷新页面就等于放弃这次转交机会，
 * 这与「凭据只返回一次」是同一件事的两面。
 */
export function MemberOnboardingWorkspace({
  session,
  onError,
}: {
  session: Access;
  onError: (error: unknown) => void;
}) {
  const [rows, setRows] = useState<OnboardingView[]>([]);
  const [roles, setRoles] = useState<RoleView[]>([]);
  const [departments, setDepartments] = useState<DepartmentView[]>([]);
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);
  const [issued, setIssued] = useState<OnboardingResult | null>(null);

  const load = useCallback(async () => {
    setLoaded(false);
    setLoadFailed(false);
    try {
      setRows(await api<OnboardingView[]>('identity/onboardings'));
      // 角色与部门只决定「开通表单里能选什么」。缺权限时表单会明说读不到，
      // 而不是静默给一个空的选择框——后者看起来像「这个租户没有角色」。
      if (canReadRoles(session)) setRoles(await api<RoleView[]>('identity/roles'));
      if (canReadDepartments(session))
        setDepartments(await api<DepartmentView[]>('identity/departments'));
    } catch (e) {
      onError(e);
      setLoadFailed(true);
    } finally {
      setLoaded(true);
    }
  }, [session, onError]);

  useEffect(() => {
    void load();
  }, [load]);

  async function mutate(work: () => Promise<void>) {
    setBusy(true);
    try {
      await work();
      await load();
    } catch (e) {
      onError(e);
    } finally {
      setBusy(false);
    }
  }

  if (!canManageOnboarding(session, 'read'))
    return (
      <p className="rounded-lg border p-6 text-sm">
        当前角色没有「成员开通」查看权限，请联系租户所有者。
      </p>
    );

  return (
    <section className="space-y-4">
      <div className="flex items-center justify-between gap-3">
        <h2 className="text-xl font-semibold">成员开通与入职</h2>
        {canManageOnboarding(session, 'create') && (
          <Button onClick={() => setEditing(true)} disabled={busy}>
            开通新成员
          </Button>
        )}
      </div>
      <p className="text-muted-foreground text-sm">
        为新成员开通账号并建立本租户成员关系，同时设定初始部门与角色。账号已存在时只接入成员关系，
        不改原密码。初始凭据由系统生成，只在开通成功时显示一次。
      </p>

      {issued && <CredentialPanel issued={issued} onDismiss={() => setIssued(null)} />}

      {!loaded ? (
        <p role="status">正在加载…</p>
      ) : loadFailed ? (
        // 「加载失败」和「一条记录都没有」必须能分辨，否则管理员会把接口故障当成租户里没人入职。
        <div className="border-destructive/40 space-y-2 rounded-lg border p-6 text-sm">
          <p className="text-destructive">入职记录加载失败，上方横幅是具体原因。</p>
          <Button size="sm" variant="outline" disabled={busy} onClick={() => void load()}>
            重试
          </Button>
        </div>
      ) : rows.length === 0 ? (
        <p className="rounded-lg border p-6 text-sm">
          还没有入职记录。开通第一位成员后，这里会记录账号是新建的还是关联的、初始部门与角色。
        </p>
      ) : (
        <ul className="divide-y rounded-lg border">
          {rows.map((row) => (
            <li key={row.id} className="flex flex-wrap items-start justify-between gap-3 p-4">
              <div className="min-w-0 space-y-1">
                <p className="break-words font-medium">{row.username}</p>
                <p className="text-muted-foreground text-xs">
                  {accountModeLabel(row.accountMode)} · {onboardingStatusLabel(row.status)}
                  {row.departmentName ? ` · ${row.departmentName}` : ' · 未设部门'}
                </p>
                <p className="text-muted-foreground text-xs">
                  初始角色：{row.roleCodes.join('、') || '未分配'}
                </p>
                {needsCredentialHandover(row) && (
                  <p className="text-warning text-xs">
                    初始凭据需当面转交给本人，转交后系统不再保存，也无法再次查看。
                  </p>
                )}
                {row.completedAt && (
                  <p className="text-muted-foreground text-xs">完成于 {row.completedAt}</p>
                )}
              </div>
              {row.status === 'PENDING' && canManageOnboarding(session, 'update') && (
                <Button
                  size="sm"
                  variant="outline"
                  disabled={busy}
                  onClick={() =>
                    void mutate(async () => {
                      await completeOnboarding(row.id);
                      setIssued(null);
                    })
                  }
                >
                  标记已入职
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}

      {editing && (
        <Editor
          title="开通新成员"
          description="账号已存在时只接入本租户成员关系；系统生成初始凭据，仅在开通成功时显示一次。"
          close={() => {
            if (!busy) setEditing(false);
          }}
        >
          <OnboardingForm
            roles={roles}
            canReadRoles={canReadRoles(session)}
            departments={departments}
            busy={busy}
            save={(input) =>
              mutate(async () => {
                const result = await createOnboarding(input);
                setIssued(result);
                setEditing(false);
              })
            }
          />
        </Editor>
      )}
    </section>
  );
}

/**
 * 初始凭据一次性面板。
 *
 * <p>刻意**不提供**「重新生成凭据」或「再次查看」：蓝图写明初始凭据只在开通响应里返回，
 * 想再加一条查看通道，等于把这个模块最核心的约束悄悄废掉。
 */
function CredentialPanel({
  issued,
  onDismiss,
}: {
  issued: OnboardingResult;
  onDismiss: () => void;
}) {
  return (
    <div
      role="status"
      className="border-warning/50 bg-warning/5 space-y-2 rounded-lg border p-4 text-sm"
    >
      {issued.initialCredential ? (
        <>
          <p className="font-medium">请立即把初始凭据转交给 {issued.username}</p>
          <p className="bg-card break-all rounded border px-3 py-2 font-mono text-base">
            {issued.initialCredential}
          </p>
          <p className="text-muted-foreground text-xs">
            这是唯一一次显示。关闭本面板或刷新页面后都无法再次查看——请当面转交或走公司既有的
            凭据发放流程，不要记在工单或聊天记录里。
          </p>
        </>
      ) : (
        <>
          <p className="font-medium">{issued.username} 已接入本租户</p>
          <p className="text-muted-foreground text-xs">
            该账号此前已存在，本次只建立了成员关系，没有也不需要初始凭据——对方的密码在本人手上。
          </p>
        </>
      )}
      <Button size="sm" variant="outline" onClick={onDismiss}>
        我已记录，关闭
      </Button>
    </div>
  );
}

function OnboardingForm({
  roles,
  canReadRoles,
  departments,
  busy,
  save,
}: {
  roles: RoleView[];
  canReadRoles: boolean;
  departments: DepartmentView[];
  busy: boolean;
  save: (
    input: import('../../../../../packages/api-contracts/src/index').OnboardingInput,
  ) => Promise<void>;
}) {
  const [username, setUsername] = useState('');
  const [departmentId, setDepartment] = useState('');
  const [assigned, setAssigned] = useState<OnboardingRoleInput[]>([]);
  return (
    <form
      className="max-h-[70vh] space-y-4 overflow-y-auto p-1"
      onSubmit={(e: FormEvent) => {
        e.preventDefault();
        if (!busy) void save(onboardingInput(username, departmentId, assigned));
      }}
    >
      <fieldset disabled={busy} className="space-y-4">
        <Field
          label="账号"
          hint="填已存在的账号即表示只接入成员关系；填新账号表示由系统新建并生成初始凭据。"
        >
          <input
            className={control}
            required
            maxLength={100}
            autoComplete="off"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
          />
        </Field>
        <Field
          label="初始部门"
          hint="部门是组织维度，可先留空；选了「本部门」数据范围时必须先设置部门。"
        >
          <select
            className={control}
            value={departmentId}
            onChange={(e) => setDepartment(e.target.value)}
            required={assigned.some((a) => a.scopeMode === 'DEPARTMENT')}
          >
            <option value="">未设置部门</option>
            {flattenDepartmentOptions(departments).map((row) => (
              <option key={row.node.id} value={row.node.id}>
                {'　'.repeat(row.depth)}
                {row.node.name}
              </option>
            ))}
          </select>
        </Field>
        <fieldset className="space-y-3">
          <legend className="mb-2 text-sm font-medium">初始角色与数据范围</legend>
          {!canReadRoles && (
            // 空 fieldset 看起来像「没有可分配的角色」，实际是该管理员缺 identity.role.read，
            // 成员会被建成没有角色的裸账号——必须说出来。
            <p role="alert" className="text-warning rounded-md border p-3 text-xs">
              当前角色没有「角色配置」查看权限，读不到可分配的角色列表。这位成员将只能保存账号与部门，
              角色需要由租户所有者另行分配。
            </p>
          )}
          {canReadRoles &&
            assignableRoles(roles, assigned).map((role) => (
              <Field key={role.id} label={role.name}>
                <select
                  className={control}
                  value={assigned.find((a) => a.roleId === role.id)?.scopeMode ?? ''}
                  onChange={(e) =>
                    setAssigned(toggleOnboardingRole(assigned, role.id, e.target.value || null))
                  }
                >
                  <option value="">不分配</option>
                  {scopesFor(role).map((scope) => (
                    <option key={scope} value={scope}>
                      {scopeLabel(scope)}
                    </option>
                  ))}
                </select>
              </Field>
            ))}
          {canReadRoles && assignableRoles(roles, assigned).length === 0 && (
            <p className="text-muted-foreground text-xs">
              租户内还没有可分配的自定义角色，请先到「角色配置」新建。
            </p>
          )}
        </fieldset>
        <Button type="submit" disabled={busy}>
          {busy ? '开通中…' : '开通'}
        </Button>
      </fieldset>
    </form>
  );
}

function scopeLabel(scope: string) {
  return { ALL: '租户内全部', DEPARTMENT: '本部门', SELF: '仅本人' }[scope] ?? scope;
}

/** 开通表单要读角色与部门两个既有资源；缺哪个权限就明说，而不是给一个空选择框。 */
function canReadRoles(session: Access) {
  return session.grants.some(
    (g) => g.scopeMode === 'ALL' && g.permissions.includes('identity.role.read'),
  );
}

function canReadDepartments(session: Access) {
  return session.grants.some(
    (g) => g.scopeMode === 'ALL' && g.permissions.includes('identity.department.read'),
  );
}
