'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState } from '@starcon/ui';
import { AlertTriangle, Plus, RefreshCw, Search, Trash2 } from 'lucide-react';
import type {
  ApprovalDocumentTypeView,
  ApprovalFlowDetailView,
  ApprovalFlowView,
  ApprovalPositionView,
  ApprovalRegistrationView,
  ApprovalValidationView,
  MemberView,
  RoleView,
} from '../../../../../packages/api-contracts/src/catalog';
import { ApiError, api } from '@/lib/api';
import { Field, Editor, control } from '../common/fields';
import { useIdentity } from './identity-shell';
import {
  APPROVAL_MODE_HINTS,
  APPROVAL_MODE_LABELS,
  APPROVAL_MODES,
  APPROVER_TYPE_LABELS,
  APPROVER_TYPES,
  FLOW_STATUS_LABELS,
  FLOW_STATUSES,
  FLOW_STATUS_TONES,
  POSITION_STATUS_LABELS,
  SEVERITY_LABELS,
  SEVERITY_TONES,
  TIMEOUT_ACTION_LABELS,
  TIMEOUT_ACTIONS,
  type BranchDraft,
  type EscalationDraft,
  type FlowDraft,
  type NodeDraft,
  approverSummary,
  emptyDraft,
  flowSummary,
  toRequestBody,
  unusableNodes,
  validateDraft,
} from './approval-flow-model';

/**
 * 17.4.1.1 审批流定义。
 *
 * 四个区块对应蓝图点名的四件事：**流程定义**（适用单据类型 + 节点 + 审批方式）、
 * **金额阈值分支**、**超时升级规则**、以及**按单据类型注册**的当前状态清单。
 *
 * <p>页面只做定义与校验：它不会发起审批、不出待办、也不会有任何计时器在超时后真的
 * 转办或放行 —— 那是 17.4.1.2 与 17.4.2.1 的职责。所以「超时视同通过」在这里只是一条
 * 被保存下来的策略定义，界面上也照直这么写。
 *
 * <p>「未注册」那一列是这个页面最重要的一块：蓝图说「未注册的变更类操作默认可直接执行」，
 * 所以没注册的单据类型不是「还没做完」，而是**当前真的没有管控**。页面把这句原文摆出来，
 * 避免管理员误以为配了流程之外的单据也走审批。
 */
export function ApprovalFlowWorkspace() {
  const { session } = useIdentity();
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [search, setSearch] = useState('');

  const [flows, setFlows] = useState<ApprovalFlowView[]>([]);
  const [registrations, setRegistrations] = useState<ApprovalRegistrationView[]>([]);
  const [documentTypes, setDocumentTypes] = useState<ApprovalDocumentTypeView[]>([]);
  const [positions, setPositions] = useState<ApprovalPositionView[]>([]);
  const [roles, setRoles] = useState<RoleView[]>([]);
  const [members, setMembers] = useState<MemberView[]>([]);
  const [details, setDetails] = useState<Record<string, ApprovalFlowDetailView>>({});
  const [reports, setReports] = useState<Record<string, ApprovalValidationView>>({});

  const [editing, setEditing] = useState<{ id: string | null; draft: FlowDraft } | null>(null);
  const [positionEditor, setPositionEditor] = useState<ApprovalPositionView | 'new' | null>(null);
  const [positionForm, setPositionForm] = useState({ code: '', name: '', description: '', status: 'ACTIVE' });

  const can = useCallback(
    (permission: string) => session.permissions.includes(permission),
    [session.permissions],
  );
  const readable = can('identity.approval_flow.read');
  const writable = can('identity.approval_flow.create');

  const load = useCallback(async () => {
    setError('');
    if (!readable) {
      // 没有读权限就不请求：空数组会被渲染成「还没有任何流程」，那是骗人。
      setReady(true);
      return;
    }
    try {
      const [flowList, registrationList, typeList, positionList] = await Promise.all([
        api<ApprovalFlowView[]>('identity/approval-flows'),
        api<ApprovalRegistrationView[]>('identity/approval-flows/registrations'),
        api<ApprovalDocumentTypeView[]>('identity/approval-flows/document-types'),
        api<ApprovalPositionView[]>('identity/approval-flows/positions'),
      ]);
      setFlows(flowList);
      setRegistrations(registrationList);
      setDocumentTypes(typeList);
      setPositions(positionList);
      // 角色与成员是节点的审批人候选。读不到就让用户手填 id，而不是假装有下拉。
      const [roleList, memberList] = await Promise.all([
        api<RoleView[]>('identity/roles').catch(() => [] as RoleView[]),
        api<MemberView[]>('identity/members').catch(() => [] as MemberView[]),
      ]);
      setRoles(roleList);
      setMembers(memberList);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '审批流加载失败，请稍后重试');
    } finally {
      setReady(true);
    }
  }, [readable]);

  useEffect(() => {
    void load();
  }, [load]);

  const openDetail = useCallback(
    async (id: string) => {
      setError('');
      try {
        const [detail, report] = await Promise.all([
          api<ApprovalFlowDetailView>(`identity/approval-flows/${id}`),
          api<ApprovalValidationView>(`identity/approval-flows/${id}/validation`),
        ]);
        setDetails((prev) => ({ ...prev, [id]: detail }));
        setReports((prev) => ({ ...prev, [id]: report }));
      } catch (e) {
        setError(e instanceof ApiError ? e.message : '流程详情加载失败');
      }
    },
    [],
  );

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return flows;
    return flows.filter((f) =>
      [f.flowName, f.documentTypeCode, f.documentTypeName, f.status].some((v) =>
        (v ?? '').toLowerCase().includes(q),
      ),
    );
  }, [flows, search]);

  const unregistered = useMemo(
    () => registrations.filter((r) => !r.registered),
    [registrations],
  );

  const activeCount = useMemo(
    () => flows.filter((f) => f.status === 'ACTIVE').length,
    [flows],
  );

  const save = useCallback(async () => {
    if (!editing) return;
    const errors = validateDraft(editing.draft, roles.map((r) => r.id));
    if (errors.length > 0) {
      setError(errors.join('；'));
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const body = toRequestBody(editing.draft);
      if (editing.id)
        await api(`identity/approval-flows/${editing.id}`, 'PUT', body);
      else await api('identity/approval-flows', 'POST', body);
      setNotice(editing.id ? '审批流已更新' : '审批流已创建');
      setEditing(null);
      await load();
      if (editing.id) await openDetail(editing.id);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '保存失败，请稍后重试');
    } finally {
      setBusy(false);
    }
  }, [editing, roles, load, openDetail]);

  const remove = useCallback(
    async (id: string) => {
      setBusy(true);
      setError('');
      try {
        await api(`identity/approval-flows/${id}`, 'DELETE');
        setDetails((prev) => {
          const next = { ...prev };
          delete next[id];
          return next;
        });
        setNotice('审批流已删除');
        await load();
      } catch (e) {
        setError(e instanceof ApiError ? e.message : '删除失败');
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const savePosition = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      if (positionEditor === 'new')
        await api('identity/approval-flows/positions', 'POST', positionForm);
      else if (positionEditor)
        await api(`identity/approval-flows/positions/${positionEditor.id}`, 'PUT', positionForm);
      setNotice('岗位已保存');
      setPositionEditor(null);
      await load();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '岗位保存失败');
    } finally {
      setBusy(false);
    }
  }, [positionEditor, positionForm, load]);

  const roleName = useCallback(
    (id: string | null) => (id ? (roles.find((r) => r.id === id)?.code ?? id) : ''),
    [roles],
  );
  const positionName = useCallback(
    (id: string | null) => (id ? (positions.find((p) => p.id === id)?.name ?? id) : ''),
    [positions],
  );
  const memberName = useCallback(
    (id: string | null) => (id ? (members.find((m) => m.id === id)?.username ?? id) : ''),
    [members],
  );

  if (!ready) return <p className="text-muted-foreground text-sm">正在加载审批流定义…</p>;
  if (!readable)
    return <p className="text-muted-foreground text-sm">当前角色没有审批流定义的查看权限。</p>;

  return (
    <div className="space-y-5">
      <section className="bg-card space-y-3 rounded-lg border p-6">
        <div className="flex flex-wrap items-center gap-3">
          <h2 className="text-xl font-semibold">审批流定义</h2>
          <Badge tone="success">{activeCount} 条生效中</Badge>
          <Badge>{flows.length} 条流程</Badge>
        </div>
        <p className="text-muted-foreground text-sm">
          按单据类型注册：同一单据类型同时只能有一条生效流程。
          <b>未注册的变更类操作默认可直接执行</b>
          —— 需要管控就得显式定义一条流程。
        </p>
        {notice && (
          <p role="status" className="text-sm text-primary">
            {notice}
          </p>
        )}
        {error && (
          <p role="alert" className="text-destructive text-sm">
            {error}
          </p>
        )}
        <div className="flex flex-wrap gap-2">
          <Button
            onClick={() => void load()}
            variant="outline"
            size="sm"
            disabled={busy}
            aria-label="刷新"
          >
            <RefreshCw className="h-4 w-4" /> 刷新
          </Button>
          {writable && (
            <Button
              size="sm"
              onClick={() =>
                setEditing({
                  id: null,
                  draft: { ...emptyDraft(roles[0]?.id ?? null), documentTypeCode: '' },
                })
              }
            >
              <Plus className="h-4 w-4" /> 新建审批流
            </Button>
          )}
        </div>
      </section>

      <section className="bg-card space-y-3 rounded-lg border p-6">
        <h3 className="font-semibold">单据类型注册情况</h3>
        {unregistered.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            全部可注册的单据类型都已注册生效审批流。
          </p>
        ) : (
          <>
            <p className="text-muted-foreground text-sm">
              以下 {unregistered.length} 个单据类型<b>尚未注册</b>，它们的变更类操作当前可以直接执行：
            </p>
            <ul className="flex flex-wrap gap-2">
              {unregistered.map((r) => (
                <li key={r.documentTypeCode}>
                  <Badge tone="warning">
                    {r.documentTypeName} · {r.documentTypeCode}
                  </Badge>
                </li>
              ))}
            </ul>
          </>
        )}
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="text-muted-foreground text-left">
              <tr>
                <th className="py-2 pr-3 font-medium">单据类型</th>
                <th className="py-2 pr-3 font-medium">编码</th>
                <th className="py-2 pr-3 font-medium">是否注册</th>
                <th className="py-2 font-medium">生效流程</th>
              </tr>
            </thead>
            <tbody>
              {registrations.map((r) => (
                <tr key={r.documentTypeCode} className="border-t">
                  <td className="py-2 pr-3">{r.documentTypeName}</td>
                  <td className="text-muted-foreground py-2 pr-3 font-mono text-xs">
                    {r.documentTypeCode}
                  </td>
                  <td className="py-2 pr-3">
                    <Badge tone={r.registered ? 'success' : 'warning'}>
                      {r.registered ? '已注册' : '未注册'}
                    </Badge>
                  </td>
                  <td className="py-2">
                    {r.registered ? r.activeFlowName : '—（默认可直接执行）'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="bg-card space-y-3 rounded-lg border p-6">
        <div className="flex flex-wrap items-center gap-2">
          <h3 className="font-semibold">流程列表</h3>
          <label className="sr-only" htmlFor="approval-flow-search">
            搜索审批流
          </label>
          <div className="relative ml-auto">
            <Search className="text-muted-foreground absolute top-2.5 left-2 h-4 w-4" />
            <input
              id="approval-flow-search"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="搜索流程名称或单据类型"
              className={`${control} pl-8`}
            />
          </div>
        </div>

        {filtered.length === 0 ? (
          <EmptyState
            icon={<AlertTriangle className="h-6 w-6" />}
            title={flows.length === 0 ? '还没有定义任何审批流' : '没有匹配的审批流'}
            description={
              flows.length === 0
                ? '审批流按单据类型注册。未注册的变更类操作默认可直接执行，需要管控就要显式定义一条。'
                : '换一个关键词试试。'
            }
          />
        ) : (
          <ul className="space-y-3">
            {filtered.map((f) => {
              const detail = details[f.id];
              const report = reports[f.id];
              const unusable = detail ? unusableNodes(detail.nodes) : [];
              return (
                <li key={f.id} className="space-y-2 rounded-md border p-4">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-medium">{f.flowName}</span>
                    <Badge tone={FLOW_STATUS_TONES[f.status] ?? 'neutral'}>
                      {FLOW_STATUS_LABELS[f.status] ?? f.status}
                    </Badge>
                    <span className="text-muted-foreground text-xs">
                      {flowSummary(f)} · 第 {f.version} 版
                    </span>
                    <div className="ml-auto flex gap-2">
                      <Button size="sm" variant="outline" onClick={() => void openDetail(f.id)}>
                        {detail ? '收起' : '查看配置'}
                      </Button>
                      {writable && (
                        <>
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => setEditing({ id: f.id, draft: draftOf(f, detail) })}
                          >
                            编辑
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            disabled={busy}
                            aria-label={`删除 ${f.flowName}`}
                            onClick={() => void remove(f.id)}
                          >
                            <Trash2 className="h-4 w-4" />
                          </Button>
                        </>
                      )}
                    </div>
                  </div>

                  {unusable.length > 0 && (
                    <ul className="text-warning space-y-0.5 text-xs">
                      {unusable.map((u) => (
                        <li key={u}>{u}</li>
                      ))}
                    </ul>
                  )}
                  {report && report.findings.length > 0 && (
                    <ul className="space-y-0.5 text-xs">
                      {report.findings.map((finding, i) => (
                        <li key={`${finding.code}-${i}`} className="flex items-center gap-2">
                          <Badge tone={SEVERITY_TONES[finding.severity] ?? 'neutral'}>
                            {SEVERITY_LABELS[finding.severity] ?? finding.severity}
                          </Badge>
                          <span>{finding.message}</span>
                        </li>
                      ))}
                    </ul>
                  )}

                  {detail && (
                    <div className="space-y-3 pt-1 text-sm">
                      <div>
                        <h4 className="mb-1 font-medium">审批节点</h4>
                        <p className="text-muted-foreground mb-1 text-xs">
                          阶段之间串行，阶段内可并行；条件分支仅在表达式命中时参与。
                        </p>
                        <ul className="space-y-1">
                          {detail.nodes.map((n) => (
                            <li key={n.id} className="flex flex-wrap items-center gap-2">
                              <span className="text-muted-foreground font-mono text-xs">
                                {n.nodeKey}
                              </span>
                              <span>{n.nodeName}</span>
                              <Badge>{APPROVAL_MODE_LABELS[n.approvalMode] ?? n.approvalMode}</Badge>
                              <span className="text-muted-foreground">
                                阶段 {n.stageIndex} · 顺序 {n.nodeOrder} ·{' '}
                                {approverSummary(n, { roleName, positionName, memberName })}
                              </span>
                              {n.conditionExpression && (
                                <code className="bg-muted rounded px-1.5 py-0.5 text-xs">
                                  {n.conditionExpression}
                                </code>
                              )}
                            </li>
                          ))}
                        </ul>
                      </div>

                      {detail.branches.length > 0 && (
                        <div>
                          <h4 className="mb-1 font-medium">金额阈值分支</h4>
                          <ul className="space-y-1 text-xs">
                            {detail.branches.map((b) => (
                              <li key={b.id}>
                                {b.branchName} · {b.currencyCode} ·{' '}
                                {b.minAmount ?? '不限'} — {b.maxAmount ?? '不限'} → 节点{' '}
                                {b.targetNodeKey}
                              </li>
                            ))}
                          </ul>
                        </div>
                      )}

                      {detail.escalations.length > 0 && (
                        <div>
                          <h4 className="mb-1 font-medium">超时升级规则</h4>
                          <p className="text-muted-foreground mb-1 text-xs">
                            这里只是把规则定义下来；真正到点后的转办与放行由审批运行时执行，本模块不实现。
                          </p>
                          <ul className="space-y-1 text-xs">
                            {detail.escalations.map((e) => (
                              <li key={e.id}>
                                节点 {e.nodeKey} · 超过 {e.timeoutHours} 小时 ·{' '}
                                {TIMEOUT_ACTION_LABELS[e.timeoutAction] ?? e.timeoutAction}
                                {e.escalateLabel ? ` → ${e.escalateLabel}` : ''}
                              </li>
                            ))}
                          </ul>
                        </div>
                      )}
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
        )}
      </section>

      <section className="bg-card space-y-3 rounded-lg border p-6">
        <div className="flex flex-wrap items-center gap-2">
          <h3 className="font-semibold">岗位字典</h3>
          <p className="text-muted-foreground text-sm">
            岗位只回答「这一步由哪个岗位的人审」，不携带任何权限。
          </p>
          {writable && (
            <Button
              size="sm"
              className="ml-auto"
              variant="outline"
              onClick={() => {
                setPositionForm({ code: '', name: '', description: '', status: 'ACTIVE' });
                setPositionEditor('new');
              }}
            >
              <Plus className="h-4 w-4" /> 新建岗位
            </Button>
          )}
        </div>
        {positions.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            还没有任何岗位。只有需要「按岗位审批」的流程才必须先建岗位。
          </p>
        ) : (
          <ul className="space-y-1 text-sm">
            {positions.map((p) => (
              <li key={p.id} className="flex flex-wrap items-center gap-2">
                <span className="font-mono text-xs">{p.code}</span>
                <span>{p.name}</span>
                <Badge tone={p.status === 'ACTIVE' ? 'success' : 'warning'}>
                  {POSITION_STATUS_LABELS[p.status] ?? p.status}
                </Badge>
                {p.description && <span className="text-muted-foreground">{p.description}</span>}
                {writable && (
                  <Button
                    size="sm"
                    variant="outline"
                    className="ml-auto"
                    onClick={() => {
                      setPositionForm({
                        code: p.code,
                        name: p.name,
                        description: p.description,
                        status: p.status,
                      });
                      setPositionEditor(p);
                    }}
                  >
                    编辑
                  </Button>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>

      {positionEditor && (
        <Editor
          title={positionEditor === 'new' ? '新建岗位' : '编辑岗位'}
          description="岗位编码在租户内唯一。审批节点引用中的岗位不能删除，只能停用。"
          close={() => setPositionEditor(null)}
        >
          <div className="space-y-4">
            <Field label="岗位编码">
              <input
                className={control}
                value={positionForm.code}
                onChange={(e) => setPositionForm({ ...positionForm, code: e.target.value })}
                placeholder="FINANCE_MANAGER"
              />
            </Field>
            <Field label="岗位名称">
              <input
                className={control}
                value={positionForm.name}
                onChange={(e) => setPositionForm({ ...positionForm, name: e.target.value })}
                placeholder="财务经理"
              />
            </Field>
            <Field label="说明">
              <input
                className={control}
                value={positionForm.description}
                onChange={(e) => setPositionForm({ ...positionForm, description: e.target.value })}
              />
            </Field>
            <Field label="状态">
              <select
                className={control}
                value={positionForm.status}
                onChange={(e) => setPositionForm({ ...positionForm, status: e.target.value })}
              >
                {Object.entries(POSITION_STATUS_LABELS).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </select>
            </Field>
            <Button disabled={busy} onClick={() => void savePosition()}>
              保存
            </Button>
          </div>
        </Editor>
      )}

      {editing && (
        <FlowEditor
          draft={editing.draft}
          documentTypes={documentTypes}
          roles={roles}
          positions={positions}
          members={members}
          busy={busy}
          onChange={(draft) => setEditing({ ...editing, draft })}
          onClose={() => setEditing(null)}
          onSave={() => void save()}
        />
      )}
    </div>
  );
}

/** 已保存的流程 → 可编辑草稿。 */
function draftOf(flow: ApprovalFlowView, detail?: ApprovalFlowDetailView): FlowDraft {
  return {
    documentTypeCode: flow.documentTypeCode,
    flowName: flow.flowName,
    status: flow.status,
    description: flow.description,
    nodes:
      detail?.nodes.map((n) => ({
        nodeKey: n.nodeKey,
        nodeName: n.nodeName,
        nodeOrder: n.nodeOrder,
        stageIndex: n.stageIndex,
        approverType: n.approverType,
        roleId: n.roleId,
        positionId: n.positionId,
        membershipId: n.membershipId,
        approvalMode: n.approvalMode,
        conditionExpression: n.conditionExpression,
        description: n.description,
      })) ?? [],
    branches:
      detail?.branches.map((b) => ({
        branchName: b.branchName,
        currencyCode: b.currencyCode,
        minAmount: b.minAmount === null ? '' : String(b.minAmount),
        maxAmount: b.maxAmount === null ? '' : String(b.maxAmount),
        targetNodeKey: b.targetNodeKey,
        description: b.description,
      })) ?? [],
    escalations:
      detail?.escalations.map((e) => ({
        nodeKey: e.nodeKey,
        timeoutAction: e.timeoutAction,
        timeoutHours: e.timeoutHours,
        escalateApproverType: e.escalateApproverType,
        escalateRoleId: e.escalateRoleId,
        escalatePositionId: e.escalatePositionId,
        escalateMembershipId: e.escalateMembershipId,
        description: e.description,
      })) ?? [],
  };
}

/** 流程编辑器：节点 + 金额阈值分支 + 超时规则一次配齐（后端按整棵替换提交）。 */
function FlowEditor({
  draft,
  documentTypes,
  roles,
  positions,
  members,
  busy,
  onChange,
  onClose,
  onSave,
}: {
  draft: FlowDraft;
  documentTypes: ApprovalDocumentTypeView[];
  roles: RoleView[];
  positions: ApprovalPositionView[];
  members: MemberView[];
  busy: boolean;
  onChange: (draft: FlowDraft) => void;
  onClose: () => void;
  onSave: () => void;
}) {
  const errors = validateDraft(draft, roles.map((r) => r.id));
  const patchNode = (index: number, next: Partial<NodeDraft>) => {
    const nodes = draft.nodes.map((n, i) => (i === index ? { ...n, ...next } : n));
    onChange({ ...draft, nodes });
  };
  const patchBranch = (index: number, next: Partial<BranchDraft>) => {
    const branches = draft.branches.map((b, i) => (i === index ? { ...b, ...next } : b));
    onChange({ ...draft, branches });
  };
  const patchEscalation = (index: number, next: Partial<EscalationDraft>) => {
    const escalations = draft.escalations.map((e, i) => (i === index ? { ...e, ...next } : e));
    onChange({ ...draft, escalations });
  };

  return (
    <Editor
      title={draft.flowName || '审批流'}
      description="审批流按单据类型注册：同一单据类型同时只能有一条生效流程。阶段之间串行，阶段内可并行。"
      close={onClose}
    >
      <div className="max-h-[70vh] space-y-5 overflow-y-auto pr-1">
        <Field label="适用单据类型">
          <select
            className={control}
            value={draft.documentTypeCode}
            onChange={(e) => onChange({ ...draft, documentTypeCode: e.target.value })}
          >
            <option value="">请选择</option>
            {documentTypes.map((t) => (
              <option key={t.code} value={t.code}>
                {t.name} · {t.code}
              </option>
            ))}
          </select>
        </Field>
        <Field label="流程名称">
          <input
            className={control}
            value={draft.flowName}
            onChange={(e) => onChange({ ...draft, flowName: e.target.value })}
          />
        </Field>
        <Field label="状态" hint="生效中的流程才会真正管控该单据类型，且同一类型只能有一条。">
          <select
            className={control}
            value={draft.status}
            onChange={(e) => onChange({ ...draft, status: e.target.value })}
          >
            {FLOW_STATUSES.map((s) => (
              <option key={s} value={s}>
                {FLOW_STATUS_LABELS[s]}
              </option>
            ))}
          </select>
        </Field>
        <Field label="说明">
          <input
            className={control}
            value={draft.description}
            onChange={(e) => onChange({ ...draft, description: e.target.value })}
          />
        </Field>

        <section className="space-y-2">
          <div className="flex items-center gap-2">
            <h4 className="font-medium">审批节点</h4>
            <Button
              size="sm"
              variant="outline"
              onClick={() =>
                onChange({
                  ...draft,
                  nodes: [
                    ...draft.nodes,
                    {
                      nodeKey: `N${draft.nodes.length + 1}`,
                      nodeName: '审批节点',
                      nodeOrder: draft.nodes.length,
                      stageIndex: draft.nodes.length,
                      approverType: 'ROLE',
                      roleId: roles[0]?.id ?? null,
                      positionId: null,
                      membershipId: null,
                      approvalMode: 'SERIAL',
                      conditionExpression: null,
                      description: '',
                    },
                  ],
                })
              }
            >
              <Plus className="h-4 w-4" /> 加节点
            </Button>
          </div>
          {draft.nodes.map((n, i) => (
            <div key={i} className="space-y-2 rounded-md border p-3">
              <div className="flex flex-wrap gap-2">
                <Field label="节点标识">
                  <input
                    className={control}
                    value={n.nodeKey}
                    onChange={(e) => patchNode(i, { nodeKey: e.target.value })}
                  />
                </Field>
                <Field label="节点名称">
                  <input
                    className={control}
                    value={n.nodeName}
                    onChange={(e) => patchNode(i, { nodeName: e.target.value })}
                  />
                </Field>
                <Field label="阶段序号">
                  <input
                    type="number"
                    className={control}
                    value={n.stageIndex}
                    onChange={(e) => patchNode(i, { stageIndex: Number(e.target.value) })}
                  />
                </Field>
                <Field label="节点顺序">
                  <input
                    type="number"
                    className={control}
                    value={n.nodeOrder}
                    onChange={(e) => patchNode(i, { nodeOrder: Number(e.target.value) })}
                  />
                </Field>
              </div>
              <div className="flex flex-wrap gap-2">
                <Field
                  label="审批方式"
                  {...(APPROVAL_MODE_HINTS[n.approvalMode]
                    ? { hint: APPROVAL_MODE_HINTS[n.approvalMode] }
                    : {})}
                >
                  <select
                    className={control}
                    value={n.approvalMode}
                    onChange={(e) => patchNode(i, { approvalMode: e.target.value })}
                  >
                    {APPROVAL_MODES.map((m) => (
                      <option key={m} value={m}>
                        {APPROVAL_MODE_LABELS[m]}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label="审批人类型">
                  <select
                    className={control}
                    value={n.approverType}
                    onChange={(e) =>
                      patchNode(i, {
                        approverType: e.target.value,
                        roleId: e.target.value === 'ROLE' ? n.roleId : null,
                        positionId: e.target.value === 'POSITION' ? n.positionId : null,
                        membershipId: e.target.value === 'USER' ? n.membershipId : null,
                      })
                    }
                  >
                    {APPROVER_TYPES.map((t) => (
                      <option key={t} value={t}>
                        {APPROVER_TYPE_LABELS[t]}
                      </option>
                    ))}
                  </select>
                </Field>
                {n.approverType === 'ROLE' && (
                  <Field label="角色">
                    <select
                      className={control}
                      value={n.roleId ?? ''}
                      onChange={(e) => patchNode(i, { roleId: e.target.value || null })}
                    >
                      <option value="">请选择</option>
                      {roles.map((r) => (
                        <option key={r.id} value={r.id}>
                          {r.code} · {r.name}
                        </option>
                      ))}
                    </select>
                  </Field>
                )}
                {n.approverType === 'POSITION' && (
                  <Field label="岗位" hint="没有岗位时请先在下方「岗位字典」新建。">
                    <select
                      className={control}
                      value={n.positionId ?? ''}
                      onChange={(e) => patchNode(i, { positionId: e.target.value || null })}
                    >
                      <option value="">请选择</option>
                      {positions.map((p) => (
                        <option key={p.id} value={p.id}>
                          {p.code} · {p.name}
                        </option>
                      ))}
                    </select>
                  </Field>
                )}
                {n.approverType === 'USER' && (
                  <Field label="指定人">
                    <select
                      className={control}
                      value={n.membershipId ?? ''}
                      onChange={(e) => patchNode(i, { membershipId: e.target.value || null })}
                    >
                      <option value="">请选择</option>
                      {members.map((m) => (
                        <option key={m.id} value={m.id}>
                          {m.username}
                        </option>
                      ))}
                    </select>
                  </Field>
                )}
                <Button
                  size="sm"
                  variant="outline"
                  aria-label={`删除节点 ${n.nodeKey}`}
                  onClick={() =>
                    onChange({ ...draft, nodes: draft.nodes.filter((_, j) => j !== i) })
                  }
                >
                  <Trash2 className="h-4 w-4" />
                </Button>
              </div>
              {n.approvalMode === 'CONDITIONAL' && (
                <Field
                  label="条件表达式"
                  hint="本模块只校验它是否存在，不解析也不执行；执行由审批运行时负责。"
                >
                  <input
                    className={control}
                    value={n.conditionExpression ?? ''}
                    onChange={(e) =>
                      patchNode(i, { conditionExpression: e.target.value || null })
                    }
                    placeholder="amount > 1000 && currency = 'USD'"
                  />
                </Field>
              )}
            </div>
          ))}
        </section>

        <section className="space-y-2">
          <div className="flex items-center gap-2">
            <h4 className="font-medium">金额阈值分支</h4>
            <Button
              size="sm"
              variant="outline"
              onClick={() =>
                onChange({
                  ...draft,
                  branches: [
                    ...draft.branches,
                    {
                      branchName: `分支 ${draft.branches.length + 1}`,
                      currencyCode: 'USD',
                      minAmount: '',
                      maxAmount: '',
                      targetNodeKey: draft.nodes[0]?.nodeKey ?? '',
                      description: '',
                    },
                  ],
                })
              }
            >
              <Plus className="h-4 w-4" /> 加分支
            </Button>
          </div>
          <p className="text-muted-foreground text-xs">
            同一币种内区间不允许交叠（交叠时「该走哪个节点」没有唯一答案）；上限等于下限算正常接续。
          </p>
          {draft.branches.map((b, i) => (
            <div key={i} className="flex flex-wrap items-end gap-2 rounded-md border p-3">
              <Field label="分支名称">
                <input
                  className={control}
                  value={b.branchName}
                  onChange={(e) => patchBranch(i, { branchName: e.target.value })}
                />
              </Field>
              <Field label="币种">
                <input
                  className={control}
                  value={b.currencyCode}
                  onChange={(e) => patchBranch(i, { currencyCode: e.target.value.toUpperCase() })}
                  placeholder="USD"
                />
              </Field>
              <Field label="下限（含）">
                <input
                  className={control}
                  value={b.minAmount}
                  onChange={(e) => patchBranch(i, { minAmount: e.target.value })}
                  placeholder="0"
                />
              </Field>
              <Field label="上限（不含）" hint="留空表示覆盖该币种全部金额，此时不能再有其他分支。">
                <input
                  className={control}
                  value={b.maxAmount}
                  onChange={(e) => patchBranch(i, { maxAmount: e.target.value })}
                  placeholder="1000"
                />
              </Field>
              <Field label="目标节点">
                <select
                  className={control}
                  value={b.targetNodeKey}
                  onChange={(e) => patchBranch(i, { targetNodeKey: e.target.value })}
                >
                  <option value="">请选择</option>
                  {draft.nodes.map((n) => (
                    <option key={n.nodeKey} value={n.nodeKey}>
                      {n.nodeKey} · {n.nodeName}
                    </option>
                  ))}
                </select>
              </Field>
              <Button
                size="sm"
                variant="outline"
                aria-label={`删除金额分支 ${b.branchName}`}
                onClick={() => onChange({ ...draft, branches: draft.branches.filter((_, j) => j !== i) })}
              >
                <Trash2 className="h-4 w-4" />
              </Button>
            </div>
          ))}
        </section>

        <section className="space-y-2">
          <div className="flex items-center gap-2">
            <h4 className="font-medium">超时升级规则</h4>
            <Button
              size="sm"
              variant="outline"
              onClick={() =>
                onChange({
                  ...draft,
                  escalations: [
                    ...draft.escalations,
                    {
                      nodeKey: draft.nodes[0]?.nodeKey ?? '',
                      timeoutAction: 'ESCALATE',
                      timeoutHours: 24,
                      escalateApproverType: 'ROLE',
                      escalateRoleId: roles[0]?.id ?? null,
                      escalatePositionId: null,
                      escalateMembershipId: null,
                      description: '',
                    },
                  ],
                })
              }
            >
              <Plus className="h-4 w-4" /> 加超时规则
            </Button>
          </div>
          <p className="text-muted-foreground text-xs">
            这里只定义规则；到点后的转办或放行由审批运行时执行，本模块不实现任何定时器。
          </p>
          {draft.escalations.map((e, i) => (
            <div key={i} className="flex flex-wrap items-end gap-2 rounded-md border p-3">
              <Field label="节点">
                <select
                  className={control}
                  value={e.nodeKey}
                  onChange={(ev) => patchEscalation(i, { nodeKey: ev.target.value })}
                >
                  <option value="">请选择</option>
                  {draft.nodes.map((n) => (
                    <option key={n.nodeKey} value={n.nodeKey}>
                      {n.nodeKey} · {n.nodeName}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="超时动作">
                <select
                  className={control}
                  value={e.timeoutAction}
                  onChange={(ev) =>
                    patchEscalation(i, {
                      timeoutAction: ev.target.value,
                      escalateApproverType: ev.target.value === 'ESCALATE' ? 'ROLE' : null,
                      escalateRoleId: ev.target.value === 'ESCALATE' ? roles[0]?.id ?? null : null,
                      escalatePositionId: null,
                      escalateMembershipId: null,
                    })
                  }
                >
                  {TIMEOUT_ACTIONS.map((a) => (
                    <option key={a} value={a}>
                      {TIMEOUT_ACTION_LABELS[a]}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="超时（小时）">
                <input
                  type="number"
                  className={control}
                  value={e.timeoutHours}
                  onChange={(ev) => patchEscalation(i, { timeoutHours: Number(ev.target.value) })}
                />
              </Field>
              {e.timeoutAction === 'ESCALATE' && (
                <Field label="转办给">
                  <select
                    className={control}
                    value={e.escalateRoleId ?? ''}
                    onChange={(ev) => patchEscalation(i, { escalateRoleId: ev.target.value || null })}
                  >
                    <option value="">请选择角色</option>
                    {roles.map((r) => (
                      <option key={r.id} value={r.id}>
                        {r.code} · {r.name}
                      </option>
                    ))}
                  </select>
                </Field>
              )}
              <Button
                size="sm"
                variant="outline"
                aria-label={`删除超时规则 ${e.nodeKey}`}
                onClick={() =>
                  onChange({ ...draft, escalations: draft.escalations.filter((_, j) => j !== i) })
                }
              >
                <Trash2 className="h-4 w-4" />
              </Button>
            </div>
          ))}
        </section>

        {errors.length > 0 && (
          <div role="alert" className="bg-card text-destructive space-y-1 rounded-md border p-3 text-sm">
            <p className="font-medium">保存前还有 {errors.length} 处需要修正：</p>
            <ul className="list-inside list-disc">
              {errors.map((e, i) => (
                <li key={i}>{e}</li>
              ))}
            </ul>
          </div>
        )}

        <Button disabled={busy || errors.length > 0} onClick={onSave}>
          保存
        </Button>
      </div>
    </Editor>
  );
}