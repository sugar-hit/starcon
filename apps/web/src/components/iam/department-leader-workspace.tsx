'use client';

import { useCallback, useEffect, useState } from 'react';
import { Badge, Button } from '@starcon/ui';
import { RefreshCw, Search } from 'lucide-react';
import type {
  ApprovalChainView,
  DepartmentLeaderView,
  MemberView,
} from '../../../../../packages/api-contracts/src/catalog';
import { api } from '@/lib/api';
import { useIdentity } from './identity-shell';
import {
  addGrant,
  addProxy,
  authoritySummary,
  chainNeedsFallback,
  clearLeader,
  fetchApprovalChain,
  fetchLeaders,
  findLeader,
  formatAmount,
  needsFallback,
  orderedEntries,
  removeGrant,
  removeProxy,
  saveLeader,
  scopeLabel,
  scopeLabels,
  selectableMembers,
  sourceLabel,
} from './department-leader-model';

/**
 * 17.2.1.2 部门负责人。
 *
 * <p>负责人在这里是**授权**而不是组织结构：管理范围（管多大）、审批额度（能批到多少）、
 * 代管与临时授权（谁能临时代批）必须一起呈现。页面刻意把「未指定负责人」的部门也列出来
 * 并标出需要兜底——蓝图说没有负责人的部门要在审批流里指定兜底人，把它藏起来等于
 * 让运营看不见自己漏配了审批人。
 */
export function DepartmentLeaderWorkspace() {
  const { session } = useIdentity();
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [search, setSearch] = useState('');
  const [rows, setRows] = useState<DepartmentLeaderView[]>([]);
  const [members, setMembers] = useState<MemberView[]>([]);
  const [editing, setEditing] = useState<DepartmentLeaderView | null>(null);
  const [delegating, setDelegating] = useState<DepartmentLeaderView | null>(null);
  const [inspecting, setInspecting] = useState<string | null>(null);

  const can = useCallback(
    (permission: string) => session.permissions.includes(permission),
    [session.permissions],
  );
  const readable = can('identity.department_leader.read');

  const load = useCallback(async () => {
    setError('');
    setNotice('');
    if (!readable) {
      // 没有读权限就不请求：拿不到数据却显示「还没有配置负责人」是在骗人，
      // 用户会以为配了就能用（而保存同样会被 403）。
      setReady(true);
      return;
    }
    try {
      setRows(await fetchLeaders());
      // 负责人表单要选人，需要成员可读名；没有成员读权限时列表仍可看，只是不能改。
      if (can('identity.member.read')) setMembers(await api<MemberView[]>('identity/members'));
    } catch (e) {
      setError(e instanceof Error ? e.message : '请求失败，请重试');
    } finally {
      setReady(true);
    }
  }, [readable, can]);

  useEffect(() => {
    void load();
  }, [load]);

  const term = search.trim().toLowerCase();
  const filtered = rows.filter((row) =>
    `${row.departmentCode} ${row.departmentName} ${row.username ?? ''}`
      .toLowerCase()
      .includes(term),
  );
  const unassigned = rows.filter(needsFallback);

  if (!ready)
    return (
      <p role="status" className="text-muted-foreground p-6 text-sm">
        正在加载部门负责人…
      </p>
    );

  if (!readable)
    return (
      <p className="rounded-lg border p-6 text-sm">
        当前角色没有部门负责人查看权限，请联系租户所有者开通。
      </p>
    );

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-muted-foreground text-sm">
          负责人决定谁能批、能批多少；没有负责人的部门需要在审批流中指定兜底人。
        </p>
        <div className="flex items-center gap-2">
          <label className="relative flex items-center">
            <Search className="text-muted-foreground pointer-events-none absolute left-2.5 h-4 w-4" />
            <input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="搜索部门或负责人"
              aria-label="搜索部门或负责人"
              className="border-border bg-card h-8 w-56 rounded-md border pl-8 pr-2 text-sm outline-none"
            />
          </label>
          <Button variant="ghost" size="sm" onClick={() => void load()}>
            <RefreshCw className="h-4 w-4" />
            刷新
          </Button>
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

      {unassigned.length > 0 && (
        <p className="border-warning/40 bg-warning/5 rounded-md border p-3 text-sm">
          有 {unassigned.length} 个部门尚未指定负责人
          {unassigned.length === 1 ? `（${unassigned[0]?.departmentName}）` : ''}
          ，这些部门需要在审批流中指定兜底人。
        </p>
      )}

      {filtered.length === 0 ? (
        <div className="rounded-lg border p-8 text-center">
          <p className="text-muted-foreground text-sm">
            {rows.length === 0
              ? '还没有部门，先到「部门管理」建好部门树再来指定负责人。'
              : '没有匹配的部门'}
          </p>
        </div>
      ) : (
        <ul className="space-y-2">
          {filtered.map((row) => (
            <li key={row.departmentId} className="bg-card rounded-md border p-4">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div className="min-w-0">
                  <p className="flex flex-wrap items-center gap-2 font-medium">
                    {row.departmentName}
                    <span className="text-muted-foreground text-xs">{row.departmentCode}</span>
                    {needsFallback(row) ? (
                      <Badge tone="warning">未指定负责人</Badge>
                    ) : (
                      <Badge tone="success">{row.username}</Badge>
                    )}
                  </p>
                  <p className="text-muted-foreground mt-1 text-xs">
                    {needsFallback(row) ? '需要在审批流中指定兜底人' : authoritySummary(row)}
                  </p>
                  {row.note && (
                    <p className="text-muted-foreground mt-1 text-xs">备注：{row.note}</p>
                  )}
                </div>
                <div className="flex flex-wrap items-center gap-2">
                  <Button size="sm" variant="ghost" onClick={() => setInspecting(row.departmentId)}>
                    审批链
                  </Button>
                  {!needsFallback(row) && can('identity.department_leader.create') && (
                    <Button size="sm" variant="ghost" onClick={() => setDelegating(row)}>
                      代管与授权
                    </Button>
                  )}
                  {can('identity.department_leader.create') && (
                    <Button size="sm" variant="outline" onClick={() => setEditing(row)}>
                      {needsFallback(row) ? '指定负责人' : '调整授权'}
                    </Button>
                  )}
                  {!needsFallback(row) && can('identity.department_leader.delete') && (
                    <Button
                      size="sm"
                      variant="ghost"
                      disabled={busy}
                      onClick={() => {
                        if (
                          !window.confirm(
                            `撤销「${row.departmentName}」的负责人？该部门的 ${row.proxyIds.length} 条代管与 ${row.grantIds.length} 条临时授权会一并作废。`,
                          )
                        )
                          return;
                        setBusy(true);
                        void clearLeader(row.departmentId)
                          .then(() => setNotice('负责人已撤销，该部门需要在审批流中指定兜底人'))
                          .then(() => load())
                          .catch((e: unknown) =>
                            setError(e instanceof Error ? e.message : '撤销失败，请重试'),
                          )
                          .finally(() => setBusy(false));
                      }}
                    >
                      撤销负责人
                    </Button>
                  )}
                </div>
              </div>
            </li>
          ))}
        </ul>
      )}

      {editing && (
        <LeaderForm
          row={editing}
          members={selectableMembers(members)}
          busy={busy}
          onCancel={() => setEditing(null)}
          onSubmit={async (input) => {
            setBusy(true);
            try {
              await saveLeader(editing.departmentId, input);
              setEditing(null);
              setNotice('负责人已保存');
              await load();
            } catch (e) {
              setError(e instanceof Error ? e.message : '保存失败，请重试');
            } finally {
              setBusy(false);
            }
          }}
        />
      )}

      {delegating && (
        <DelegationPanel
          row={delegating}
          members={selectableMembers(members)}
          onClose={() => setDelegating(null)}
          onChanged={async (message) => {
            setNotice(message);
            await load();
          }}
          onError={(e: unknown) => setError(e instanceof Error ? e.message : '操作失败，请重试')}
        />
      )}

      {inspecting && findLeader(rows, inspecting) && (
        <ApprovalChainPanel
          departmentId={inspecting}
          busy={busy}
          onClose={() => setInspecting(null)}
        />
      )}
    </div>
  );
}

/** 指定负责人：人、管理范围、审批额度三件事一次提交，避免授权开大但还没配人的中间态。 */
function LeaderForm({
  row,
  members,
  busy,
  onCancel,
  onSubmit,
}: {
  row: DepartmentLeaderView;
  members: MemberView[];
  busy: boolean;
  onCancel: () => void;
  onSubmit: (input: {
    membershipId: string;
    scopeMode: string;
    approvalLimit: number | null;
    approvalCurrency: string | null;
    note: string;
  }) => Promise<void>;
}) {
  const [membershipId, setMembershipId] = useState(row.membershipId ?? '');
  const [scopeMode, setScopeMode] = useState(row.scopeMode ?? 'DEPARTMENT');
  const [limited, setLimited] = useState(row.approvalLimit !== null);
  const [limit, setLimit] = useState(row.approvalLimit ?? '');
  const [currency, setCurrency] = useState(row.approvalCurrency ?? 'USD');
  const [note, setNote] = useState(row.note ?? '');

  return (
    <section className="bg-card space-y-4 rounded-md border p-4">
      <p className="font-medium">
        {needsFallback(row)
          ? `为「${row.departmentName}」指定负责人`
          : `调整「${row.departmentName}」的负责人授权`}
      </p>
      <div className="grid gap-3 md:grid-cols-2">
        <label className="block">
          <span className="mb-1 block text-sm font-medium">负责人</span>
          <select
            value={membershipId}
            onChange={(e) => setMembershipId(e.target.value)}
            className={inputClass}
          >
            <option value="">请选择成员</option>
            {members.map((m) => (
              <option key={m.id} value={m.id}>
                {m.username}
              </option>
            ))}
          </select>
          <span className="text-muted-foreground mt-1 block text-xs">
            停用成员不能作为负责人；下拉里只列启用中的成员。
          </span>
        </label>
        <label className="block">
          <span className="mb-1 block text-sm font-medium">管理范围</span>
          <select
            value={scopeMode}
            onChange={(e) => setScopeMode(e.target.value)}
            className={inputClass}
          >
            {Object.entries(scopeLabels).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
          <span className="text-muted-foreground mt-1 block text-xs">
            默认只管本部门，要管下级必须显式选择。
          </span>
        </label>
      </div>

      <div className="space-y-2">
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={limited} onChange={(e) => setLimited(e.target.checked)} />
          设置审批额度上限
        </label>
        {limited && (
          <div className="grid gap-3 sm:grid-cols-2">
            <label className="block">
              <span className="mb-1 block text-sm font-medium">审批额度</span>
              <input
                value={limit}
                onChange={(e) => setLimit(e.target.value)}
                inputMode="decimal"
                placeholder="例如 5000.00"
                className={inputClass}
              />
            </label>
            <label className="block">
              <span className="mb-1 block text-sm font-medium">额度币种</span>
              <input
                value={currency}
                onChange={(e) => setCurrency(e.target.value.toUpperCase())}
                placeholder="三位大写币种代码"
                className={inputClass}
              />
              <span className="text-muted-foreground mt-1 block text-xs">
                额度与币种必须成对：不填币种，下游审批流无从判断这是多少钱。
              </span>
            </label>
          </div>
        )}
      </div>

      <label className="block">
        <span className="mb-1 block text-sm font-medium">备注</span>
        <input value={note} onChange={(e) => setNote(e.target.value)} className={inputClass} />
      </label>

      <div className="flex gap-2">
        <Button
          size="sm"
          disabled={busy || !membershipId || (limited && (!limit || currency.length !== 3))}
          onClick={() =>
            void onSubmit({
              membershipId,
              scopeMode,
              approvalLimit: limited ? Number(limit) : null,
              approvalCurrency: limited ? currency : null,
              note,
            })
          }
        >
          保存
        </Button>
        <Button size="sm" variant="ghost" onClick={onCancel}>
          取消
        </Button>
      </div>
    </section>
  );
}

/**
 * 代管与临时授权。
 *
 * <p>两者的区别在界面上必须讲清楚：代管接手的是**整个职务**（范围与额度都跟负责人一致），
 * 临时授权只借走一部分审批额度且必须有截止时间。
 */
function DelegationPanel({
  row,
  members,
  onClose,
  onChanged,
  onError,
}: {
  row: DepartmentLeaderView;
  members: MemberView[];
  onClose: () => void;
  onChanged: (message: string) => Promise<void>;
  onError: (error: unknown) => void;
}) {
  const [working, setWorking] = useState(false);
  const [memberId, setMemberId] = useState('');
  const [reason, setReason] = useState('');
  const [limit, setLimit] = useState('');
  const [currency, setCurrency] = useState(row.approvalCurrency ?? 'USD');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');

  const leaderLimit = row.approvalLimit;
  const currencyMismatch = currency !== row.approvalCurrency;

  async function run(work: () => Promise<unknown>, message: string) {
    setWorking(true);
    try {
      await work();
      await onChanged(message);
    } catch (e) {
      onError(e);
    } finally {
      setWorking(false);
    }
  }

  return (
    <section className="bg-card space-y-4 rounded-md border p-4">
      <div className="flex items-center justify-between">
        <p className="font-medium">「{row.departmentName}」的代管与临时授权</p>
        <Button size="sm" variant="ghost" onClick={onClose}>
          关闭
        </Button>
      </div>
      <p className="text-muted-foreground text-xs">
        当前负责人 {row.username ?? '—'} · {authoritySummary(row)}
      </p>

      <div className="space-y-2 rounded-md border p-3">
        <p className="text-sm font-medium">新增代管</p>
        <p className="text-muted-foreground text-xs">
          代管接手的是整个职务：范围与额度都跟随负责人。留空结束时间表示长期代管。
        </p>
        <div className="grid gap-3 sm:grid-cols-2">
          <label className="block">
            <span className="mb-1 block text-xs font-medium">代管人</span>
            <select
              value={memberId}
              onChange={(e) => setMemberId(e.target.value)}
              className={inputClass}
            >
              <option value="">请选择成员</option>
              {members.map((m) => (
                <option key={m.id} value={m.id}>
                  {m.username}
                </option>
              ))}
            </select>
          </label>
          <label className="block">
            <span className="mb-1 block text-xs font-medium">结束时间（可留空）</span>
            <input
              type="datetime-local"
              value={to}
              onChange={(e) => setTo(e.target.value)}
              className={inputClass}
            />
          </label>
        </div>
        <label className="block">
          <span className="mb-1 block text-xs font-medium">原因</span>
          <input
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            className={inputClass}
          />
        </label>
        <Button
          size="sm"
          disabled={working || !memberId}
          onClick={() =>
            void run(
              () =>
                addProxy(row.departmentId, {
                  membershipId: memberId,
                  reason,
                  startsAt: new Date().toISOString(),
                  endsAt: to ? new Date(to).toISOString() : null,
                }),
              '代管已生效',
            )
          }
        >
          新增代管
        </Button>
      </div>

      <div className="space-y-2 rounded-md border p-3">
        <p className="text-sm font-medium">新增临时授权</p>
        <p className="text-muted-foreground text-xs">
          只借走一部分审批额度，必须有截止时间，且不得超过负责人的额度与币种。
          {leaderLimit === null && ' 当前负责人未设置额度，无法派生临时授权额度。'}
        </p>
        <div className="grid gap-3 sm:grid-cols-2">
          <label className="block">
            <span className="mb-1 block text-xs font-medium">被授权人</span>
            <select
              value={memberId}
              onChange={(e) => setMemberId(e.target.value)}
              className={inputClass}
            >
              <option value="">请选择成员</option>
              {members.map((m) => (
                <option key={m.id} value={m.id}>
                  {m.username}
                </option>
              ))}
            </select>
          </label>
          <label className="block">
            <span className="mb-1 block text-xs font-medium">授权额度</span>
            <input
              value={limit}
              onChange={(e) => setLimit(e.target.value)}
              inputMode="decimal"
              className={inputClass}
            />
          </label>
          <label className="block">
            <span className="mb-1 block text-xs font-medium">币种</span>
            <input
              value={currency}
              onChange={(e) => setCurrency(e.target.value.toUpperCase())}
              className={inputClass}
            />
          </label>
          <label className="block">
            <span className="mb-1 block text-xs font-medium">开始时间</span>
            <input
              type="datetime-local"
              value={from}
              onChange={(e) => setFrom(e.target.value)}
              className={inputClass}
            />
          </label>
          <label className="block">
            <span className="mb-1 block text-xs font-medium">结束时间</span>
            <input
              type="datetime-local"
              value={to}
              onChange={(e) => setTo(e.target.value)}
              className={inputClass}
            />
          </label>
        </div>
        {currencyMismatch && (
          <p role="alert" className="text-destructive text-xs">
            币种必须与负责人一致（{row.approvalCurrency ?? '未设置'}），否则审批流无从比较额度。
          </p>
        )}
        <Button
          size="sm"
          disabled={
            working ||
            !memberId ||
            !limit ||
            !from ||
            !to ||
            currencyMismatch ||
            leaderLimit === null
          }
          onClick={() =>
            void run(
              () =>
                addGrant(row.departmentId, {
                  membershipId: memberId,
                  approvalLimit: Number(limit),
                  approvalCurrency: currency,
                  startsAt: new Date(from).toISOString(),
                  endsAt: new Date(to).toISOString(),
                  reason,
                }),
              '临时授权已生效',
            )
          }
        >
          新增临时授权
        </Button>
      </div>

      <div className="space-y-2">
        <p className="text-sm font-medium">
          已配置：{row.proxyIds.length} 条代管、{row.grantIds.length} 条临时授权
        </p>
        {row.proxyIds.length > 0 && (
          <ul className="space-y-1">
            {row.proxyIds.map((id) => (
              <li key={id} className="flex items-center justify-between text-xs">
                <span className="text-muted-foreground">代管 {id.slice(0, 8)}</span>
                <Button
                  size="sm"
                  variant="ghost"
                  disabled={working}
                  onClick={() => void run(() => removeProxy(row.departmentId, id), '代管已撤销')}
                >
                  撤销
                </Button>
              </li>
            ))}
          </ul>
        )}
        {row.grantIds.length > 0 && (
          <ul className="space-y-1">
            {row.grantIds.map((id) => (
              <li key={id} className="flex items-center justify-between text-xs">
                <span className="text-muted-foreground">临时授权 {id.slice(0, 8)}</span>
                <Button
                  size="sm"
                  variant="ghost"
                  disabled={working}
                  onClick={() =>
                    void run(() => removeGrant(row.departmentId, id), '临时授权已撤销')
                  }
                >
                  撤销
                </Button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  );
}

/**
 * 审批链引用。
 *
 * <p>这是下游 17.4 审批流取数的地方，所以页面直接展示「此刻谁有审批权」并允许查历史时刻——
 * 权限随时间变化，历史单据必须能还原当时的归属。
 */
function ApprovalChainPanel({
  departmentId,
  busy,
  onClose,
}: {
  departmentId: string;
  busy: boolean;
  onClose: () => void;
}) {
  const [chain, setChain] = useState<ApprovalChainView | null>(null);
  const [error, setError] = useState('');
  const [ready, setReady] = useState(false);
  const [asOf, setAsOf] = useState('');

  const load = useCallback(async () => {
    setReady(false);
    setError('');
    try {
      setChain(
        await fetchApprovalChain(departmentId, asOf ? new Date(asOf).toISOString() : undefined),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : '审批链加载失败');
    } finally {
      setReady(true);
    }
  }, [departmentId, asOf]);

  useEffect(() => {
    void load();
  }, [load]);

  const entries = orderedEntries(chain);

  return (
    <section className="bg-card space-y-3 rounded-md border p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="font-medium">审批链引用</p>
        <div className="flex items-center gap-2">
          <label className="flex items-center gap-2 text-xs">
            <span>查询时刻</span>
            <input
              type="datetime-local"
              value={asOf}
              onChange={(e) => setAsOf(e.target.value)}
              className={inputClass}
              aria-label="查询审批链的时刻"
            />
          </label>
          <Button size="sm" variant="ghost" onClick={onClose}>
            关闭
          </Button>
        </div>
      </div>
      {error && (
        <p role="alert" className="text-destructive text-sm">
          {error}
        </p>
      )}
      {!ready ? (
        <p role="status" className="text-muted-foreground text-sm">
          正在解析审批链…
        </p>
      ) : chainNeedsFallback(chain) ? (
        <p className="border-warning/40 bg-warning/5 rounded-md border p-3 text-sm">
          该部门此刻没有审批人{chain ? `（${chain.departmentName}）` : ''}
          ，需要在审批流中指定兜底人。
        </p>
      ) : (
        <ol className="space-y-2">
          {entries.map((entry) => (
            <li key={entry.membershipId} className="rounded-md border p-3 text-sm">
              <p className="flex flex-wrap items-center gap-2 font-medium">
                {entry.seq}. {entry.username}
                <Badge tone="neutral">{sourceLabel(entry.source)}</Badge>
              </p>
              <p className="text-muted-foreground mt-1 text-xs">
                管理范围 {scopeLabel(entry.scopeMode)} · 审批额度{' '}
                {entry.approvalLimit === null
                  ? '不设上限'
                  : formatAmount(entry.approvalLimit, entry.approvalCurrency ?? '')}
              </p>
            </li>
          ))}
        </ol>
      )}
      {busy && <p className="text-muted-foreground text-xs">处理中…</p>}
    </section>
  );
}

const inputClass = 'border-border bg-background h-9 rounded-md border px-2 text-sm outline-none';
