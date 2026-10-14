'use client';
import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { Button } from '@starcon/ui';
import type {
  Access,
  RoleView,
  PermissionView,
  MemberView,
  DepartmentView,
  DepartmentInput,
  LegalEntityView,
  ChildView,
  AssignmentInput,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '@/lib/api';
import { Field, Editor, control } from '../common/fields';
import {
  actionLabels,
  assignRole,
  canManage,
  hasPermission,
  scopeLabels,
  togglePermission,
  saveAdministration,
  removeAdministration,
  transferOwner,
  moveDepartment,
  flattenDepartments,
  type DepartmentRow,
  type AdministrationKind,
} from './administration-model';
import { DepartmentTree } from './department-tree';

const titles = {
  roles: '角色配置',
  members: '用户与成员',
  departments: '部门管理',
  children: '子租户管理',
};
export function AdministrationPanel({
  kind,
  session,
  reloadSession,
  onError,
}: {
  kind: AdministrationKind;
  session: Access;
  reloadSession: () => Promise<void>;
  onError: (error: unknown) => void;
}) {
  const [roles, setRoles] = useState<RoleView[]>([]);
  const [permissions, setPermissions] = useState<PermissionView[]>([]);
  const [members, setMembers] = useState<MemberView[]>([]);
  const [departments, setDepartments] = useState<DepartmentView[]>([]);
  const [legalEntities, setLegalEntities] = useState<LegalEntityView[]>([]);
  const [children, setChildren] = useState<ChildView[]>([]);
  const [editing, setEditing] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);
  const [notice, setNotice] = useState('');
  const load = useCallback(async () => {
    setLoaded(false);
    setLoadFailed(false);
    try {
      if (canManage(session, 'roles')) {
        setRoles(await api<RoleView[]>('identity/roles'));
        setPermissions(await api<PermissionView[]>('identity/permissions'));
      }
      if (canManage(session, 'members')) setMembers(await api<MemberView[]>('identity/members'));
      if (canManage(session, 'departments')) {
        setDepartments(await api<DepartmentView[]>('identity/departments'));
        // 部门表单要选所属法人（17.1.2.1）与负责人，读权限就够
        if (hasPermission(session, 'identity.legal_entity.read'))
          setLegalEntities(await api<LegalEntityView[]>('identity/legal-entities'));
      }
      if (canManage(session, 'children')) setChildren(await api<ChildView[]>('identity/children'));
    } catch (e) {
      // 加载失败必须落到「失败 + 重试」，不能停在"正在加载…"：串行 await 里第一个失败
      // 后面的请求根本没发出去，页面会永远转圈，看起来像慢而不是坏。
      onError(e);
      setLoadFailed(true);
    } finally {
      setLoaded(true);
    }
  }, [session, onError]);
  useEffect(() => {
    void load();
  }, [load]);
  async function mutate(work: () => Promise<unknown>) {
    setBusy(true);
    setNotice('');
    try {
      await work();
      setEditing(null);
      await load();
      await reloadSession();
      setNotice('已保存');
    } catch (e) {
      onError(e);
    } finally {
      setBusy(false);
    }
  }
  if (!canManage(session, kind))
    return (
      <p className="rounded-lg border p-6 text-sm">
        当前角色没有此页面查看权限，请联系租户所有者。
      </p>
    );
  const rows =
    kind === 'roles'
      ? roles
      : kind === 'members'
        ? members
        : kind === 'departments'
          ? []
          : children;
  // 成员人数只在节点上展示计数：identity/members 不支持按部门过滤，
  // 展开列具体成员会变成假数据，这里不做。
  const isTree = kind === 'departments';
  // departments 现在是树（根节点 + 嵌套 children），按 id 找部门必须先拍平
  const departmentRows = flattenDepartments(departments);
  return (
    <section className="space-y-4">
      <div className="flex items-center justify-between gap-3">
        <h2 className="text-xl font-semibold">{titles[kind]}</h2>
        {canManage(session, kind, 'create') && (
          <Button onClick={() => setEditing('new')} disabled={busy}>
            新增
          </Button>
        )}
      </div>
      <p className="text-muted-foreground text-sm">
        {kind === 'children'
          ? '子租户独立管理成员与业务数据。创建时指定首位所有者；获得目标租户成员身份后，可切换租户管理成员。'
          : kind === 'roles'
            ? '页面操作和数据范围由同一个角色分配共同决定。所有者负责授权变更。'
            : kind === 'members'
              ? '每个角色可分别选择租户内全部、本部门或仅本人范围。本部门按记录创建时的归属部门匹配。'
              : '部门树属于当前租户，用于成员归属和数据访问范围；所属法人是法律主体，两者互相独立。有子部门或仍有成员挂靠的部门不能删除。'}
      </p>
      {notice && (
        <p role="status" className="text-sm">
          {notice}
        </p>
      )}
      {!loaded ? (
        <p role="status">正在加载…</p>
      ) : loadFailed ? (
        // 失败和"没有数据"必须能分辨，否则管理员会把接口故障当成"租户里一个人都没有"
        <div className="space-y-2 rounded-lg border border-destructive/40 p-6 text-sm">
          <p className="text-destructive">数据加载失败，上方横幅是具体原因。</p>
          <Button size="sm" variant="outline" disabled={busy} onClick={() => void load()}>
            重试
          </Button>
        </div>
      ) : isTree ? (
        <DepartmentTree
          nodes={departments}
          busy={busy}
          canUpdate={canManage(session, 'departments', 'update')}
          canDelete={canManage(session, 'departments', 'delete')}
          onMove={(id, parentId) => void mutate(() => moveDepartment(id, parentId))}
          onEdit={(id) => setEditing(id)}
          onDelete={(id) => void mutate(() => removeAdministration('departments', id))}
        />
      ) : rows.length === 0 ? (
        <p className="rounded-lg border p-6 text-sm">暂无记录</p>
      ) : (
        <ul className="divide-y rounded-lg border">
          {rows.map((row) => {
            const role = kind === 'roles' ? (row as RoleView) : null;
            const member = kind === 'members' ? (row as MemberView) : null;
            const child = kind === 'children' ? (row as ChildView) : null;
            const protectedRole = role?.system;
            return (
              <li key={row.id} className="flex flex-wrap items-center justify-between gap-3 p-4">
                <div className="min-w-0 space-y-1">
                  <p className="break-words font-medium">
                    {member ? member.username : (row as RoleView | DepartmentView | ChildView).name}
                  </p>
                  {role && (
                    <p className="text-muted-foreground text-xs">
                      {role.code} · {role.status === 'ACTIVE' ? '启用' : '停用'} ·{' '}
                      {role.permissions.length} 项权限
                    </p>
                  )}
                  {member && (
                    <p className="text-muted-foreground text-xs">
                      {member.status === 'ACTIVE' ? '启用' : '停用'} ·{' '}
                      {departmentRows.find((d) => d.node.id === member.departmentId)?.node.name ?? '未设置部门'}{' '}
                      ·{' '}
                      {member.assignments
                        .map(
                          (a) =>
                            `${roles.find((r) => r.id === a.roleId)?.name ?? '角色'}（${scopeLabels[a.scopeMode]}）`,
                        )
                        .join('、') || '未分配角色'}
                    </p>
                  )}
                  {child && (
                    <p className="text-muted-foreground text-xs">
                      {child.code} · {child.status === 'ACTIVE' ? '启用' : '暂停'}
                    </p>
                  )}
                </div>
                <div className="flex flex-wrap gap-2">
                  {canManage(session, kind, 'update') && !protectedRole && (
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={busy}
                      onClick={() => setEditing(row.id)}
                    >
                      编辑
                    </Button>
                  )}
                  {kind !== 'children' &&
                    canManage(session, kind, 'delete') &&
                    !protectedRole &&
                    !member?.owner && (
                      <Button
                        size="sm"
                        variant="ghost"
                        disabled={busy}
                        onClick={() => {
                          if (
                            window.confirm(
                              kind === 'members'
                                ? '移除此成员的全部角色并停用其身份？'
                                : '确认删除？角色删除将同时撤销所有成员的该角色分配。',
                            )
                          )
                            void mutate(() => removeAdministration(kind, row.id));
                        }}
                      >
                        {kind === 'members' ? '移除' : '删除'}
                      </Button>
                    )}
                  {member &&
                    !member.owner &&
                    member.status === 'ACTIVE' &&
                    canManage(session, 'members', 'update') && (
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={busy}
                        onClick={() => {
                          if (
                            window.confirm(
                              `将所有者转移给 ${member.username}？你将失去所有者权限。`,
                            )
                          )
                            void mutate(() => transferOwner(member.id));
                        }}
                      >
                        转移所有者
                      </Button>
                    )}
                </div>
              </li>
            );
          })}
        </ul>
      )}
      {editing !== null && (
        <Editor
          title={`${editing === 'new' ? '新增' : '编辑'}${titles[kind]}`}
          description="保存后立即生效。"
          close={() => {
            if (!busy) setEditing(null);
          }}
        >
          {kind === 'roles' ? (
            <RoleForm
              role={roles.find((r) => r.id === editing)}
              permissions={permissions}
              busy={busy}
              save={(input) =>
                mutate(() =>
                  saveAdministration('roles', editing === 'new' ? undefined : editing, input),
                )
              }
            />
          ) : kind === 'members' ? (
            <MemberForm
              member={members.find((m) => m.id === editing)}
              roles={roles}
              canReadRoles={canManage(session, 'roles')}
              departments={departments}
              busy={busy}
              save={(input) =>
                mutate(() =>
                  saveAdministration('members', editing === 'new' ? undefined : editing, input),
                )
              }
            />
          ) : kind === 'children' ? (
            <ChildForm
              child={children.find((c) => c.id === editing)}
              busy={busy}
              save={(input) =>
                mutate(() =>
                  saveAdministration('children', editing === 'new' ? undefined : editing, input),
                )
              }
            />
          ) : (
            <DepartmentForm
              department={departmentRows.find((d) => d.node.id === editing)?.node}
              departmentRows={departmentRows}
              legalEntities={legalEntities}
              members={members}
              busy={busy}
              save={(input) => {
                const editingDepartment = departmentRows.find(
                  (d) => d.node.id === editing,
                )?.node;
                return mutate(async () => {
                  if (editing === 'new') {
                    await saveAdministration('departments', undefined, input);
                    return;
                  }
                  // PUT 只改字段、不动层级（防环校验只在移动端点里），
                  // 所以上级部门变了要额外调一次移动，否则这个选择框等于摆设。
                  await saveAdministration('departments', editing, input);
                  if ((input.parentId ?? null) !== (editingDepartment?.parentId ?? null))
                    await moveDepartment(editing, input.parentId ?? null);
                });
              }}
            />
          )}
        </Editor>
      )}
    </section>
  );
}

function Form({
  children,
  busy,
  submit,
}: {
  children: ReactNode;
  busy: boolean;
  submit: () => Promise<void>;
}) {
  return (
    <form
      className="max-h-[70vh] space-y-4 overflow-y-auto p-1"
      onSubmit={(e: FormEvent) => {
        e.preventDefault();
        if (!busy) void submit();
      }}
    >
      <fieldset disabled={busy} className="space-y-4">
        {children}
        <Button type="submit" disabled={busy}>
          {busy ? '保存中…' : '保存'}
        </Button>
      </fieldset>
    </form>
  );
}
function RoleForm({
  role,
  permissions,
  busy,
  save,
}: {
  role: RoleView | undefined;
  permissions: PermissionView[];
  busy: boolean;
  save: (
    input: import('../../../../../packages/api-contracts/src/index').RoleInput,
  ) => Promise<void>;
}) {
  const [code, setCode] = useState(role?.code ?? '');
  const [name, setName] = useState(role?.name ?? '');
  const [status, setStatus] = useState(role?.status ?? 'ACTIVE');
  const [selected, setSelected] = useState(role?.permissions ?? []);
  const pages = [...new Map(permissions.map((p) => [p.pageCode, p.pageName])).entries()];
  return (
    <Form busy={busy} submit={() => save({ code, name, status, permissions: selected })}>
      <Field label="角色编码">
        <input
          className={control}
          required
          pattern="[A-Z][A-Z0-9_-]{0,63}"
          maxLength={64}
          value={code}
          onChange={(e) => setCode(e.target.value.toUpperCase())}
        />
      </Field>
      <Field label="角色名称">
        <input
          className={control}
          required
          maxLength={100}
          value={name}
          onChange={(e) => setName(e.target.value)}
        />
      </Field>
      <Status value={status} change={setStatus} />
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <caption className="pb-2 text-left font-medium">页面权限</caption>
          <thead>
            <tr>
              <th className="p-2 text-left">页面</th>
              {Object.values(actionLabels).map((label) => (
                <th className="p-2" key={label}>
                  {label}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {pages.map(([page, label]) => (
              <tr key={page}>
                <th className="p-2 text-left font-normal">{label}</th>
                {Object.entries(actionLabels).map(([action, title]) => {
                  const code = `${page}.${action}`;
                  const exists = permissions.some((p) => p.code === code);
                  return (
                    <td key={action} className="p-2 text-center">
                      <input
                        type="checkbox"
                        aria-label={`${label}·${title}`}
                        disabled={!exists}
                        checked={selected.includes(code)}
                        onChange={(e) =>
                          setSelected(togglePermission(selected, code, e.target.checked))
                        }
                      />
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Form>
  );
}
function Status({
  value,
  change,
  suspended = false,
  disabled = false,
}: {
  value: string;
  change: (value: string) => void;
  suspended?: boolean;
  disabled?: boolean;
}) {
  return (
    <Field label="状态">
      <select
        className={control}
        value={value}
        onChange={(e) => change(e.target.value)}
        disabled={disabled}
      >
        <option value="ACTIVE">启用</option>
        <option value={suspended ? 'SUSPENDED' : 'INACTIVE'}>{suspended ? '暂停' : '停用'}</option>
      </select>
    </Field>
  );
}
function MemberForm({
  member,
  roles,
  canReadRoles,
  departments,
  busy,
  save,
}: {
  member: MemberView | undefined;
  roles: RoleView[];
  canReadRoles: boolean;
  departments: DepartmentView[];
  busy: boolean;
  save: (
    input:
      | import('../../../../../packages/api-contracts/src/index').MemberInput
      | import('../../../../../packages/api-contracts/src/index').MemberUpdate,
  ) => Promise<void>;
}) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [status, setStatus] = useState(member?.status ?? 'ACTIVE');
  const [departmentId, setDepartment] = useState(member?.departmentId ?? '');
  const [assignments, setAssignments] = useState<AssignmentInput[]>(member?.assignments ?? []);
  return (
    <Form
      busy={busy}
      submit={() =>
        save({
          ...(member ? {} : { username, password: password || null }),
          status,
          departmentId: departmentId || null,
          assignments,
        })
      }
    >
      {!member && (
        <AccountFields
          username={username}
          setUsername={setUsername}
          password={password}
          setPassword={setPassword}
        />
      )}
      <Status value={status} change={setStatus} disabled={member?.owner ?? false} />
      <Field label="所属部门">
        <select
          className={control}
          value={departmentId}
          onChange={(e) => setDepartment(e.target.value)}
          required={assignments.some((a) => a.scopeMode === 'DEPARTMENT')}
        >
          <option value="">未设置部门</option>
          {flattenDepartments(departments).map((row) => (
            <option key={row.node.id} value={row.node.id}>
              {'　'.repeat(row.depth)}
              {row.node.name}
            </option>
          ))}
        </select>
      </Field>
      <fieldset disabled={member?.owner ?? false} className="space-y-3">
        <legend className="mb-2 text-sm font-medium">角色与数据范围</legend>
        {/* 角色列表读不到时必须说出来：空 fieldset 看起来像"没有可分配的角色"，
            实际是该管理员缺 identity.role.read，成员会被建成没有角色的裸账号。 */}
        {!canReadRoles && (
          <p role="alert" className="text-warning rounded-md border p-3 text-xs">
            当前角色没有「角色配置」查看权限，读不到可分配的角色列表。这位成员将只能保存账号与部门，
            角色需要由租户所有者另行分配。
          </p>
        )}
        {canReadRoles &&
          roles
            .filter(
              (r) =>
                !r.system && (r.status === 'ACTIVE' || assignments.some((a) => a.roleId === r.id)),
            )
            .map((r) => (
              <Field key={r.id} label={r.name}>
                <select
                  className={control}
                  value={assignments.find((a) => a.roleId === r.id)?.scopeMode ?? ''}
                  onChange={(e) =>
                    setAssignments(assignRole(assignments, r.id, e.target.value || null))
                  }
                >
                  <option value="">不分配</option>
                  {Object.entries(scopeLabels)
                    .filter(
                      ([scope]) =>
                        scope === 'ALL' || r.permissions.every((p) => p.startsWith('catalog.')),
                    )
                    .map(([scope, label]) => (
                      <option key={scope} value={scope}>
                        {label}
                      </option>
                    ))}
                </select>
              </Field>
            ))}
        {canReadRoles &&
          roles.filter((r) => !r.system).length === 0 && (
            <p className="text-muted-foreground text-xs">
              租户内还没有可分配的自定义角色，请先到「角色配置」新建。
            </p>
          )}
      </fieldset>
    </Form>
  );
}
function AccountFields({
  username,
  setUsername,
  password,
  setPassword,
}: {
  username: string;
  setUsername: (v: string) => void;
  password: string;
  setPassword: (v: string) => void;
}) {
  return (
    <>
      <Field label="账号">
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
        label="新账号密码"
        hint="添加已有账号时留空。新建账号需设置 12–72 位密码；不会修改已有账号密码。"
      >
        <input
          className={control}
          type="password"
          minLength={12}
          maxLength={72}
          autoComplete="new-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
      </Field>
    </>
  );
}
function ChildForm({
  child,
  busy,
  save,
}: {
  child: ChildView | undefined;
  busy: boolean;
  save: (
    input:
      | import('../../../../../packages/api-contracts/src/index').ChildInput
      | import('../../../../../packages/api-contracts/src/index').ChildUpdate,
  ) => Promise<void>;
}) {
  const [code, setCode] = useState('');
  const [name, setName] = useState(child?.name ?? '');
  const [status, setStatus] = useState(child?.status ?? 'ACTIVE');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  return (
    <Form
      busy={busy}
      submit={() =>
        save(
          child
            ? { name, status }
            : { code, name, ownerUsername: username, ownerPassword: password || null },
        )
      }
    >
      {!child && (
        <Field label="租户编码">
          <input
            className={control}
            required
            pattern="[A-Z][A-Z0-9_-]{0,63}"
            value={code}
            onChange={(e) => setCode(e.target.value.toUpperCase())}
          />
        </Field>
      )}
      <Field label="租户名称">
        <input
          className={control}
          required
          maxLength={100}
          value={name}
          onChange={(e) => setName(e.target.value)}
        />
      </Field>
      {child ? (
        <Status suspended value={status} change={setStatus} />
      ) : (
        <fieldset className="space-y-3">
          <legend className="mb-2 text-sm font-medium">首位所有者</legend>
          <AccountFields
            username={username}
            setUsername={setUsername}
            password={password}
            setPassword={setPassword}
          />
        </fieldset>
      )}
    </Form>
  );
}
function DepartmentForm({
  department,
  departmentRows,
  legalEntities,
  members,
  busy,
  save,
}: {
  department: DepartmentView | undefined;
  departmentRows: DepartmentRow[];
  legalEntities: LegalEntityView[];
  members: MemberView[];
  busy: boolean;
  save: (input: DepartmentInput) => Promise<void>;
}) {
  const [code, setCode] = useState(department?.code ?? '');
  const [name, setName] = useState(department?.name ?? '');
  const [parentId, setParentId] = useState(department?.parentId ?? '');
  const [legalEntityId, setLegalEntityId] = useState(department?.legalEntityId ?? '');
  const [leaderMembershipId, setLeader] = useState(department?.leaderMembershipId ?? '');
  // 编辑时不能把自己或自己的子孙选为上级，否则一次保存就会造成环
  const candidates = departmentRows.filter((row) => row.node.id !== department?.id);
  return (
    <Form
      busy={busy}
      submit={() =>
        save({
          code: code.trim().toUpperCase(),
          name: name.trim(),
          // 层级调整走独立端点 PUT /departments/{id}/parent，这里只提交字段
          parentId: parentId || null,
          legalEntityId: legalEntityId || null,
          leaderMembershipId: leaderMembershipId || null,
        })
      }
    >
      <Field label="部门编码" hint="租户内唯一，只允许大写字母、数字、下划线和中划线。">
        <input
          className={control}
          required
          maxLength={64}
          pattern="[A-Z0-9_\-]{1,64}"
          value={code}
          onChange={(e) => setCode(e.target.value)}
        />
      </Field>
      <Field label="部门名称">
        <input
          className={control}
          required
          maxLength={100}
          value={name}
          onChange={(e) => setName(e.target.value)}
        />
      </Field>
      <Field label="上级部门" hint="留空表示根部门。层级也可以在树上拖拽调整。">
        <select
          className={control}
          value={parentId}
          onChange={(e) => setParentId(e.target.value)}
        >
          <option value="">（根部门）</option>
          {candidates.map((row) => (
            <option key={row.node.id} value={row.node.id}>
              {'　'.repeat(row.depth)}
              {row.node.name}
            </option>
          ))}
        </select>
      </Field>
      <Field
        label="所属法人"
        hint={
          legalEntities.length === 0
            ? '法人档案为空或当前角色没有法人查看权限；部门是组织维度，可以先不设法人。'
            : '法人是法律主体，与部门这个组织维度互相独立。'
        }
      >
        <select
          className={control}
          value={legalEntityId}
          onChange={(e) => setLegalEntityId(e.target.value)}
        >
          <option value="">（不设所属法人）</option>
          {legalEntities.map((entity) => (
            <option key={entity.id} value={entity.id}>
              {entity.legalName}（{entity.code}）
            </option>
          ))}
        </select>
      </Field>
      <Field
        label="部门负责人"
        hint={
          members.length === 0
            ? '负责人需要成员查看权限才能选择；没有负责人的部门要在审批流里指定兜底人。'
            : '负责人只是一个成员引用；代管与临时授权属于「部门负责人」模块，不在这里配置。'
        }
      >
        <select
          className={control}
          value={leaderMembershipId}
          onChange={(e) => setLeader(e.target.value)}
        >
          <option value="">（暂不指定负责人）</option>
          {members.map((member) => (
            <option key={member.id} value={member.id}>
              {member.username}
            </option>
          ))}
        </select>
      </Field>
    </Form>
  );
}
