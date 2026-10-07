'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button } from '@starcon/ui';
import { RefreshCw, ShieldCheck, X } from 'lucide-react';
import type {
  MemberView,
  RoleAssignmentInput,
  RoleAssignmentMergeView,
  RoleAssignmentScopeOption,
  RoleAssignmentScopeView,
  RoleAssignmentView,
  RoleView,
  ShopView,
  WarehouseRef,
} from '../../../../../packages/api-contracts/src/catalog';
import { api } from '@/lib/api';
import { Field, control } from '../common/fields';
import { useIdentity } from './identity-shell';
import {
  describeScopes,
  describeSources,
  effectiveLabel,
  inactiveReasonLabel,
  mergeRuleLabel,
  scopeModeLabel,
  scopeModesRequiringObjects,
  scopeTypeLabel,
  toInstant,
  toScopeInput,
  validateAssignment,
  type AssignmentForm,
  type ScopeMode,
} from './role-assignment-model';

/**
 * 17.3.2.1 角色分配。
 *
 * <p>这个页面围绕蓝图那条加粗的硬约束组织：**分配时必须同时设定范围**。
 * 所以表单把「范围」放在和「角色」同等的位置上，选「指定范围」而不选对象时直接不给保存；
 * 未生效 / 已过期 / 已撤销的分配不会从列表里消失，而是带着状态留在原地——
 * 让管理员看得到「我配过这个角色」，而不是以为系统把它弄丢了。
 *
 * <p>下半部分是**合并后的有效权限预览**：同一个成员身上多条分配同时生效时，
 * 权限取并集、范围取并集、其中任一条是租户内全部则整体按全部生效。页面把这条规则写在结果上方，
 * 并逐条标出这条权限由哪几个角色合并而来，所以「他到底有什么权限」是可以核对的，而不是只能相信。
 *
 * <p>前端只做录入与展示。真正的判定永远在服务端（{@code RoleAssignmentController}
 * 与 V47 的库内约束：缺范围的写入根本进不去）。
 */

type Option = { key: string; type: string; id: string; label: string };

const emptyForm: AssignmentForm = {
  roleId: '',
  scopeMode: 'DEPARTMENT',
  scopeKeys: [],
  effectiveFrom: '',
  effectiveUntil: '',
  note: '',
};

/** datetime-local 的值是「本地墙上时间」，回填时也要按同一口径截断。 */
function toLocal(value: string | null): string {
  if (!value) return '';
  const at = new Date(value);
  return Number.isNaN(at.getTime()) ? '' : at.toISOString().slice(0, 16);
}

function moment(value: string | null | undefined): string {
  if (!value) return '—';
  const at = new Date(value);
  return Number.isNaN(at.getTime()) ? '—' : at.toLocaleString('zh-CN', { hour12: false });
}

export function RoleAssignmentWorkspace() {
  const { session } = useIdentity();
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  const [members, setMembers] = useState<MemberView[]>([]);
  const [roles, setRoles] = useState<RoleView[]>([]);
  const [options, setOptions] = useState<RoleAssignmentScopeOption[]>([]);
  // 店铺与仓库归 channel / warehouse 服务，本库没有这两张表（跨服务 FK 被禁止）。
  // 取不到就少给这两类候选，而不是拿一份假数据把四类凑齐。
  const [remoteOptions, setRemoteOptions] = useState<{
    shops: ShopView[];
    warehouses: WarehouseRef[];
  }>({
    shops: [],
    warehouses: [],
  });
  const [remoteNote, setRemoteNote] = useState('');

  const [memberId, setMemberId] = useState('');
  const [assignments, setAssignments] = useState<RoleAssignmentView[]>([]);
  const [preview, setPreview] = useState<RoleAssignmentMergeView | null>(null);
  const [listError, setListError] = useState('');

  const [form, setForm] = useState<AssignmentForm | null>(null);
  const [editing, setEditing] = useState<string | null>(null);
  const [formError, setFormError] = useState('');

  const managed = session.grants.some((g) => g.roleCode === 'OWNER' && g.scopeMode === 'ALL');

  const load = useCallback(async () => {
    setError('');
    try {
      const [memberList, roleList, scopeOptions] = await Promise.all([
        api<MemberView[]>('identity/members'),
        api<RoleView[]>('identity/roles'),
        api<RoleAssignmentScopeOption[]>('identity/role-assignment-scopes'),
      ]);
      setMembers(memberList);
      setRoles(roleList);
      setOptions(scopeOptions);

      // 店铺与仓库要问它们各自的服务。缺权限或服务不可用都不该让整页失败——
      // 法人与部门这两类候选照样能用，页面上说清楚少的是哪两类即可。
      const notes: string[] = [];
      const [shops, warehouses] = await Promise.all([
        api<ShopView[]>('channel/shops').catch(() => {
          notes.push('店铺');
          return [] as ShopView[];
        }),
        api<WarehouseRef[]>('warehouse/warehouses').catch(() => {
          notes.push('仓库');
          return [] as WarehouseRef[];
        }),
      ]);
      setRemoteOptions({ shops, warehouses });
      setRemoteNote(notes.length ? `当前角色无权读取${notes.join('与')}，这两类范围未加载` : '');
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setReady(true);
    }
  }, []);

  const loadMember = useCallback(async (id: string) => {
    if (!id) {
      setAssignments([]);
      setPreview(null);
      setListError('');
      return;
    }
    setListError('');
    try {
      const [list, merged] = await Promise.all([
        api<RoleAssignmentView[]>(`identity/role-assignments?membershipId=${id}`),
        api<RoleAssignmentMergeView>(`identity/members/${id}/effective-permissions`),
      ]);
      setAssignments(list);
      setPreview(merged);
    } catch (e) {
      setAssignments([]);
      setPreview(null);
      setListError(e instanceof Error ? e.message : '读取分配失败，请重试');
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void loadMember(memberId);
  }, [memberId, loadMember]);

  /** 四类范围对象的统一候选池。key 用 `类型:id`，与服务端主键一致。 */
  const scopeOptions: Option[] = useMemo(
    () => [
      ...options.map((o) => ({
        key: `${o.scopeType}:${o.scopeId}`,
        type: o.scopeType,
        id: o.scopeId,
        label: `${scopeTypeLabel(o.scopeType)} ${o.code} ${o.name}`,
      })),
      ...remoteOptions.shops.map((s) => ({
        key: `SHOP:${s.id}`,
        type: 'SHOP',
        id: s.id,
        label: `店铺 ${s.code} ${s.name}`,
      })),
      ...remoteOptions.warehouses.map((w) => ({
        key: `WAREHOUSE:${w.id}`,
        type: 'WAREHOUSE',
        id: w.id,
        label: `仓库 ${w.code} ${w.name}`,
      })),
    ],
    [options, remoteOptions],
  );

  // 店铺与仓库的名称服务端无从校验（表不在本库），所以这里补上；法人与部门由服务端覆写。
  const nameOf = useCallback(
    (type: string, id: string) => {
      if (type === 'SHOP') {
        const shop = remoteOptions.shops.find((s) => s.id === id);
        return shop ? `${shop.code} ${shop.name}` : null;
      }
      if (type === 'WAREHOUSE') {
        const warehouse = remoteOptions.warehouses.find((w) => w.id === id);
        return warehouse ? `${warehouse.code} ${warehouse.name}` : null;
      }
      return null;
    },
    [remoteOptions],
  );

  const startCreate = () => {
    setEditing(null);
    setForm({ ...emptyForm, effectiveFrom: toLocal(new Date().toISOString()) });
    setFormError('');
  };

  const startEdit = (assignment: RoleAssignmentView) => {
    setEditing(assignment.roleId);
    setForm({
      roleId: assignment.roleId,
      scopeMode: assignment.scopeMode,
      scopeKeys: assignment.scopes.map(
        (s: RoleAssignmentScopeView) => `${s.scopeType}:${s.scopeId}`,
      ),
      effectiveFrom: toLocal(assignment.effectiveFrom),
      effectiveUntil: toLocal(assignment.effectiveUntil),
      note: assignment.note ?? '',
    });
    setFormError('');
  };

  const save = async () => {
    if (!form || !memberId) return;
    const problem = validateAssignment(form);
    if (problem) {
      setFormError(problem);
      return;
    }
    const body: RoleAssignmentInput = {
      roleId: form.roleId,
      scopeMode: form.scopeMode,
      scopes: form.scopeKeys
        .map((key) => toScopeInput(key, nameOf))
        .filter((s): s is NonNullable<typeof s> => s !== null),
      effectiveFrom: toInstant(form.effectiveFrom) as string,
      effectiveUntil: form.effectiveUntil ? (toInstant(form.effectiveUntil) as string) : null,
      note: form.note.trim() || null,
    };
    setBusy(true);
    setFormError('');
    setError('');
    try {
      if (editing) {
        await api(`identity/members/${memberId}/role-assignments/${editing}`, 'PUT', body);
        setNotice('分配已更新');
      } else {
        await api(`identity/members/${memberId}/role-assignments`, 'POST', body);
        setNotice('分配已创建');
      }
      setForm(null);
      setEditing(null);
      await loadMember(memberId);
    } catch (e) {
      setFormError(e instanceof Error ? e.message : '保存失败，请重试');
    } finally {
      setBusy(false);
    }
  };

  const revoke = async (assignment: RoleAssignmentView) => {
    // 撤销是终态，界面必须先说清楚，否则用户以为只是暂时收起。
    if (
      !window.confirm(
        `撤销「${assignment.roleName || assignment.roleCode}」的分配？\n撤销后立即失效且不可恢复，需要重新配置。`,
      )
    )
      return;
    setBusy(true);
    setError('');
    try {
      await api(
        `identity/members/${memberId}/role-assignments/${assignment.roleId}/revoke`,
        'POST',
      );
      setNotice('分配已撤销');
      if (editing === assignment.roleId) {
        setForm(null);
        setEditing(null);
      }
      await loadMember(memberId);
    } catch (e) {
      setError(e instanceof Error ? e.message : '撤销失败，请重试');
    } finally {
      setBusy(false);
    }
  };

  if (!ready)
    return (
      <p role="status" className="text-muted-foreground p-6 text-sm">
        正在加载角色分配…
      </p>
    );

  const needsObjects = form ? scopeModesRequiringObjects.includes(form.scopeMode) : false;

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-copy-13 text-muted-foreground">
          分配角色时必须同时设定作用范围。没有范围的角色等于租户内全部权限，实践中极易误配——
          这条由服务端与数据库约束保证，界面上只是提前把话说清楚。
        </p>
        <div className="flex gap-2">
          <Button variant="ghost" size="sm" onClick={() => void load()}>
            <RefreshCw className="h-4 w-4" />
            刷新
          </Button>
          {managed && (
            <Button size="sm" disabled={!memberId} onClick={startCreate}>
              新增分配
            </Button>
          )}
        </div>
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

      <section className="bg-card space-y-3 rounded-lg border p-6">
        <h2 className="text-copy-15 font-semibold">选择成员</h2>
        <Field label="成员" hint="先选成员，再配置它的角色分配与生效时间">
          <select
            className={control}
            value={memberId}
            onChange={(e) => setMemberId(e.target.value)}
          >
            <option value="">请选择成员</option>
            {members.map((m) => (
              <option key={m.id} value={m.id}>
                {m.username}（{m.status === 'ACTIVE' ? '启用' : '停用'}）
              </option>
            ))}
          </select>
        </Field>
        {members.length === 0 && ready && (
          <p className="text-copy-13 text-muted-foreground">
            本租户还没有成员，请先在「用户与成员」里创建。
          </p>
        )}
      </section>

      {form && (
        <section className="bg-card space-y-4 rounded-lg border p-6">
          <h2 className="text-copy-15 font-semibold">{editing ? '修改分配' : '新增分配'}</h2>
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="角色" hint="只能选择本租户内已启用的角色">
              <select
                className={control}
                value={form.roleId}
                disabled={editing !== null}
                onChange={(e) => setForm({ ...form, roleId: e.target.value })}
              >
                <option value="">请选择角色</option>
                {roles
                  .filter((r) => r.status === 'ACTIVE')
                  .map((r) => (
                    <option key={r.id} value={r.id}>
                      {r.name}（{r.code}）
                    </option>
                  ))}
              </select>
            </Field>
            <Field label="范围模式" hint="租户内全部与仅本人自带完整范围；指定范围必须列出作用对象">
              <select
                className={control}
                value={form.scopeMode}
                onChange={(e) =>
                  setForm({
                    ...form,
                    scopeMode: e.target.value as ScopeMode,
                    // 切换到自带范围的模式时清掉已选对象：留着会被服务端判为自相矛盾。
                    scopeKeys: scopeModesRequiringObjects.includes(e.target.value as ScopeMode)
                      ? form.scopeKeys
                      : [],
                  })
                }
              >
                <option value="DEPARTMENT">指定范围（法人 / 店铺 / 仓库 / 部门）</option>
                <option value="ALL">租户内全部</option>
                <option value="SELF">仅本人数据</option>
              </select>
            </Field>
          </div>

          {needsObjects && (
            <Field
              label="作用对象"
              hint="可多选。法人与部门的编码由服务端校验并覆写；店铺与仓库来自各自服务，需当前角色有读取权限"
            >
              <select
                multiple
                className={`${control} h-40`}
                value={form.scopeKeys}
                onChange={(e) =>
                  setForm({
                    ...form,
                    scopeKeys: Array.from(e.target.selectedOptions, (o) => o.value),
                  })
                }
              >
                {scopeOptions.length === 0 && <option value="">暂无可选范围对象</option>}
                {scopeOptions.map((o) => (
                  <option key={o.key} value={o.key}>
                    {o.label}
                  </option>
                ))}
              </select>
            </Field>
          )}
          {needsObjects && remoteNote && (
            <p className="text-copy-13 text-muted-foreground">{remoteNote}</p>
          )}

          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="生效开始" hint="未到时间的分配不产生任何权限">
              <input
                type="datetime-local"
                className={control}
                value={form.effectiveFrom}
                onChange={(e) => setForm({ ...form, effectiveFrom: e.target.value })}
              />
            </Field>
            <Field label="生效结束" hint="留空表示长期有效；到期后自动失效但记录仍在">
              <input
                type="datetime-local"
                className={control}
                value={form.effectiveUntil}
                onChange={(e) => setForm({ ...form, effectiveUntil: e.target.value })}
              />
            </Field>
            <Field label="备注" hint="记下授权原因，审计里会一并留存">
              <input
                className={control}
                value={form.note}
                maxLength={500}
                onChange={(e) => setForm({ ...form, note: e.target.value })}
              />
            </Field>
          </div>

          {formError && (
            <p role="alert" className="text-destructive text-sm">
              {formError}
            </p>
          )}
          <div className="flex gap-2">
            <Button disabled={busy} onClick={() => void save()}>
              {busy ? '保存中…' : '保存'}
            </Button>
            <Button
              variant="outline"
              disabled={busy}
              onClick={() => {
                setForm(null);
                setEditing(null);
                setFormError('');
              }}
            >
              <X className="h-4 w-4" />
              取消
            </Button>
          </div>
        </section>
      )}

      {memberId && (
        <>
          <section className="space-y-3">
            <h2 className="text-copy-15 font-semibold">角色分配</h2>
            {listError && (
              <div
                role="alert"
                className="border-destructive/30 bg-destructive/5 text-destructive rounded-md border p-3 text-sm"
              >
                {listError}
              </div>
            )}
            {assignments.length === 0 ? (
              <div className="bg-card rounded-lg border p-8 text-center">
                <p className="text-copy-13 text-muted-foreground">
                  该成员还没有任何角色分配。没有分配就是没有任何权限——这是安全默认， 不是数据缺失。
                </p>
              </div>
            ) : (
              <ul className="space-y-2">
                {assignments.map((assignment) => (
                  <li
                    key={assignment.roleId}
                    className={`bg-card rounded-md border p-4 ${
                      assignment.effectiveStatus === 'ACTIVE' ? '' : 'opacity-70'
                    }`}
                    data-effective={assignment.effectiveStatus}
                  >
                    <div className="flex flex-wrap items-start justify-between gap-3">
                      <div className="min-w-0 space-y-1">
                        <p className="text-copy-15 flex flex-wrap items-center gap-2 font-medium">
                          {assignment.roleName || assignment.roleCode}
                          <span className="text-copy-13 text-muted-foreground">
                            {assignment.roleCode}
                          </span>
                          <Badge
                            tone={assignment.effectiveStatus === 'ACTIVE' ? 'success' : 'neutral'}
                          >
                            {effectiveLabel(assignment.effectiveStatus)}
                          </Badge>
                        </p>
                        <p className="text-copy-13 text-muted-foreground">
                          范围：{scopeModeLabel(assignment.scopeMode)} ·{' '}
                          {describeScopes(assignment.scopes)}
                        </p>
                        <p className="text-copy-13 text-muted-foreground">
                          生效：{moment(assignment.effectiveFrom)} 至{' '}
                          {assignment.effectiveUntil
                            ? moment(assignment.effectiveUntil)
                            : '长期有效'}
                        </p>
                        {assignment.note && (
                          <p className="text-copy-13 text-muted-foreground">
                            备注：{assignment.note}
                          </p>
                        )}
                        {assignment.revokedAt && (
                          <p className="text-copy-13 text-muted-foreground">
                            撤销于 {moment(assignment.revokedAt)}
                          </p>
                        )}
                      </div>
                      {managed && (
                        <div className="flex shrink-0 gap-2">
                          <Button
                            size="sm"
                            variant="outline"
                            disabled={busy}
                            onClick={() => startEdit(assignment)}
                          >
                            修改
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            disabled={busy || assignment.status !== 'ACTIVE'}
                            onClick={() => void revoke(assignment)}
                          >
                            撤销
                          </Button>
                        </div>
                      )}
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section className="space-y-3">
            <h2 className="text-copy-15 flex items-center gap-2 font-semibold">
              <ShieldCheck className="text-brand h-4 w-4" />
              有效权限预览（合并后）
            </h2>
            {preview && <p className="text-copy-13 text-muted-foreground">{preview.mergeRule}</p>}
            {!preview ? (
              <div className="bg-card rounded-lg border p-8 text-center">
                <p className="text-copy-13 text-muted-foreground">正在读取合并后的权限…</p>
              </div>
            ) : preview.permissions.length === 0 ? (
              <div className="bg-card rounded-lg border p-8 text-center">
                <p className="text-copy-13 text-muted-foreground">
                  该成员当前没有生效中的权限。{preview.assignmentCount}{' '}
                  条分配里没有一条同时满足「已生效」 与「角色启用」。
                </p>
              </div>
            ) : (
              <ul className="space-y-2">
                {preview.permissions.map((permission) => (
                  <li key={permission.permissionCode} className="bg-card rounded-md border p-4">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-copy-13 font-medium">
                        {permission.pageName} · {permission.permissionCode}
                      </span>
                      <Badge tone={permission.mergedScopeMode === 'ALL' ? 'success' : 'neutral'}>
                        {scopeModeLabel(permission.mergedScopeMode)}
                      </Badge>
                    </div>
                    <p className="text-copy-13 text-muted-foreground mt-1">
                      合并方式：{mergeRuleLabel(permission.mergeRule)}（来源{' '}
                      {describeSources(permission)}）
                    </p>
                    {permission.scopeObjects.length > 0 && (
                      <p className="text-copy-13 text-muted-foreground">
                        范围对象：{describeScopes(permission.scopeObjects)}
                      </p>
                    )}
                  </li>
                ))}
              </ul>
            )}
            {preview && preview.inactive.length > 0 && (
              <div className="bg-card space-y-2 rounded-lg border p-4">
                <p className="text-copy-13 font-medium">未参与合并的分配</p>
                <ul className="text-copy-13 text-muted-foreground space-y-1">
                  {preview.inactive.map((i) => (
                    <li key={i.roleId}>
                      {i.roleCode}：{inactiveReasonLabel(i.reason)}
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </section>
        </>
      )}
    </div>
  );
}
