'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button } from '@starcon/ui';
import { ChevronDown, ChevronRight, Plus, RefreshCw, Search, Undo2 } from 'lucide-react';
import type {
  MemberView,
  OffboardingCaseView,
  OffboardingDetailView,
  OffboardingInput,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '@/lib/api';
import { useIdentity } from '../iam/identity-shell';
import { Field, control } from '../common/fields';
import {
  CASE_STATUS_LABELS,
  CASE_STATUS_TONES,
  RECLAIM_KIND_LABELS,
  RECLAIM_STATUS_LABELS,
  RESPONSIBILITY_KIND_LABELS,
  RESPONSIBILITY_STATUS_LABELS,
  canRevert,
  caseSummary,
  effectiveDate,
  effectiveMoment,
  isActionable,
  successorLabel,
} from './offboarding-model';

/**
 * 17.2.2.3 离职与停用。
 *
 * 页面围绕**停用而非删除**组织：这里没有任何删除成员的动作，停用之后成员行还在、
 * 历史操作记录还在，唯一能做的是「撤销」（把人恢复启用）。
 *
 * 责任转移与待办回收两段分别列出逐条明细，而不是只给一个数字：「共 8 项已转 5 项」
 * 看不出剩下的是哪三条。条目状态由服务端台账决定 —— 下游服务消费之前它们就停在
 * 「待转移 / 待回收」，本页面不会替下游宣称已经处理完。
 */
export function OffboardingWorkspace() {
  const { session } = useIdentity();
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [search, setSearch] = useState('');
  const [cases, setCases] = useState<OffboardingCaseView[]>([]);
  const [members, setMembers] = useState<MemberView[]>([]);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [details, setDetails] = useState<Record<string, OffboardingDetailView>>({});
  const [creating, setCreating] = useState(false);

  const can = useCallback(
    (permission: string) => session.permissions.includes(permission),
    [session.permissions],
  );
  const readable = can('identity.offboarding.read');
  const mayOffboard = can('identity.offboarding.create');

  const load = useCallback(async () => {
    setError('');
    setNotice('');
    if (!readable) {
      // 没有读权限就不请求：空数组会被渲染成「还没有离职记录」，那是骗人。
      setReady(true);
      return;
    }
    try {
      // 发起离职需要挑一个「别人」，所以成员列表与案卷列表同口径加载：
      // 成员列表失败时不显示空的离职页。
      const [caseList, memberList] = await Promise.all([
        api<OffboardingCaseView[]>('identity/offboarding'),
        api<MemberView[]>('identity/members'),
      ]);
      setCases(caseList);
      setMembers(memberList);
    } catch (e) {
      setError(e instanceof Error ? e.message : '请求失败，请重试');
    } finally {
      setReady(true);
    }
  }, [readable]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 展开某条案卷时按需拉详情，不在列表接口里塞明细。 */
  const toggle = useCallback(
    async (id: string) => {
      if (expanded === id) {
        setExpanded(null);
        return;
      }
      setExpanded(id);
      try {
        const detail = await api<OffboardingDetailView>(`identity/offboarding/${id}`);
        setDetails((prev) => ({ ...prev, [id]: detail }));
      } catch (e) {
        setError(e instanceof Error ? e.message : '加载离职详情失败');
        setExpanded(null);
      }
    },
    [expanded],
  );

  async function act(path: string, body: unknown, success: string) {
    setBusy(true);
    try {
      await api(path, 'POST', body);
      setNotice(success);
      await load();
      if (expanded) {
        const detail = await api<OffboardingDetailView>(`identity/offboarding/${expanded}`);
        setDetails((prev) => ({ ...prev, [expanded]: detail }));
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '操作失败，请重试');
    } finally {
      setBusy(false);
    }
  }

  // Hook 必须排在早退之前：加载中 / 无权限时 return 之后再调 Hook，
  // 同一个组件在两次渲染间 Hook 数量会变，React 会直接抛错。
  const activeMembers = useMemo(
    () => members.filter((m) => m.status === 'ACTIVE' && !m.owner),
    [members],
  );

  if (!ready)
    return (
      <p role="status" className="text-muted-foreground p-6 text-sm">
        正在加载离职与停用记录…
      </p>
    );

  if (!readable)
    return (
      <p className="rounded-lg border p-6 text-sm">
        当前角色没有离职与停用查看权限，请联系租户所有者开通。
      </p>
    );

  const term = search.trim().toLowerCase();
  const filtered = cases.filter((c) =>
    `${c.memberName} ${c.reason} ${c.operatorName}`.toLowerCase().includes(term),
  );

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-copy-13 text-muted-foreground">
          离职走停用而不是删除：成员行与历史操作记录都会保留，随时可以撤销复职。
        </p>
        <div className="flex flex-wrap items-center gap-2">
          <label className="relative flex items-center">
            <Search className="text-muted-foreground pointer-events-none absolute left-2.5 h-4 w-4" />
            <input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="搜索成员或原因"
              aria-label="搜索离职记录"
              className="border-border bg-card text-copy-13 focus-visible:border-ring h-8 w-56 rounded-md border pl-8 pr-2 outline-none"
            />
          </label>
          <Button variant="ghost" size="sm" onClick={() => void load()}>
            <RefreshCw className="h-4 w-4" />
            刷新
          </Button>
          {mayOffboard && (
            <Button
              size="sm"
              disabled={activeMembers.length === 0}
              onClick={() => setCreating(true)}
            >
              <Plus className="h-4 w-4" />
              发起离职
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

      {mayOffboard && activeMembers.length === 0 && (
        <div className="bg-card rounded-lg border p-6 text-sm">
          当前没有可停用的普通成员。租户所有者不能被停用，需要先使用「转移所有者」。
        </div>
      )}

      {creating && (
        <OffboardingForm
          members={activeMembers}
          busy={busy}
          onCancel={() => setCreating(false)}
          onSubmit={async (input) => {
            setBusy(true);
            try {
              await api('identity/offboarding', 'POST', input);
              setCreating(false);
              setNotice('成员已停用，离职记录已建立');
              await load();
            } catch (e) {
              setError(e instanceof Error ? e.message : '发起离职失败，请重试');
            } finally {
              setBusy(false);
            }
          }}
        />
      )}

      {filtered.length === 0 ? (
        <div className="bg-card rounded-lg border p-8 text-center">
          <p className="text-copy-13 text-muted-foreground">
            {cases.length === 0 ? '还没有离职与停用记录' : '没有匹配当前搜索条件的离职记录'}
          </p>
        </div>
      ) : (
        <ul className="space-y-2">
          {filtered.map((c) => {
            const open = expanded === c.id;
            const detail = details[c.id];
            return (
              <li key={c.id} className="bg-card rounded-md border p-4">
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className="text-copy-15 flex flex-wrap items-center gap-2 font-medium">
                      {c.memberName}
                      <Badge tone={CASE_STATUS_TONES[c.status] ?? 'neutral'}>
                        {CASE_STATUS_LABELS[c.status] ?? c.status}
                      </Badge>
                    </p>
                    <p className="text-copy-13 text-muted-foreground mt-1">{c.reason}</p>
                    <dl className="text-copy-12 text-muted-foreground mt-2 grid gap-x-6 gap-y-1 sm:grid-cols-2 lg:grid-cols-4">
                      <div>
                        <dt className="inline">生效日期：</dt>
                        <dd className="inline">{effectiveDate(c.effectiveAt)}</dd>
                      </div>
                      <div>
                        <dt className="inline">操作人：</dt>
                        <dd className="inline">{c.operatorName}</dd>
                      </div>
                      <div>
                        <dt className="inline">责任转移：</dt>
                        <dd className="inline">
                          {c.responsibilityTotal - c.responsibilityPending}/{c.responsibilityTotal}{' '}
                          已转移
                        </dd>
                      </div>
                      <div>
                        <dt className="inline">待办回收：</dt>
                        <dd className="inline">
                          {c.reclaimTotal - c.reclaimPending}/{c.reclaimTotal} 已回收
                        </dd>
                      </div>
                    </dl>
                    <p className="text-copy-12 text-muted-foreground mt-2">
                      {caseSummary(c)}
                      {c.status === 'REVERTED' && c.revertedAt
                        ? `（撤销于 ${effectiveMoment(c.revertedAt)}）`
                        : ''}
                    </p>
                  </div>
                  <div className="flex shrink-0 items-center gap-2">
                    {mayOffboard && canRevert(c.status) && (
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={busy}
                        onClick={() => {
                          if (!window.confirm(`撤销对 ${c.memberName} 的离职？成员会恢复启用。`))
                            return;
                          void act(
                            `identity/offboarding/${c.id}/revert`,
                            {},
                            '离职已撤销，成员已恢复启用',
                          );
                        }}
                      >
                        <Undo2 className="h-4 w-4" />
                        撤销离职
                      </Button>
                    )}
                    <Button variant="ghost" size="sm" onClick={() => void toggle(c.id)}>
                      {open ? (
                        <ChevronDown className="h-4 w-4" />
                      ) : (
                        <ChevronRight className="h-4 w-4" />
                      )}
                      {open ? '收起明细' : '查看明细'}
                    </Button>
                  </div>
                </div>

                {open && (
                  <div className="border-border mt-4 space-y-4 border-t pt-4">
                    {!detail ? (
                      <p role="status" className="text-copy-12 text-muted-foreground">
                        正在加载明细…
                      </p>
                    ) : (
                      <>
                        <section className="space-y-2">
                          <h3 className="text-copy-13 font-medium">
                            在办单据与审批责任（{detail.responsibilityItems.length}）
                          </h3>
                          <p className="text-copy-12 text-muted-foreground">
                            由持有单据的服务登记到这里。identity 只记录谁转给谁，
                            不去对方库里核对单据是否真的存在。
                          </p>
                          {detail.responsibilityItems.length === 0 ? (
                            <p className="text-copy-12 text-muted-foreground">
                              没有登记待转移的单据或审批。
                            </p>
                          ) : (
                            <ul className="space-y-1">
                              {detail.responsibilityItems.map((item) => (
                                <li
                                  key={item.id}
                                  className="border-border flex flex-wrap items-center justify-between gap-2 border-b py-2 last:border-b-0"
                                >
                                  <div className="min-w-0">
                                    <p className="text-copy-13 flex flex-wrap items-center gap-2">
                                      <Badge tone="neutral">
                                        {RESPONSIBILITY_KIND_LABELS[item.itemKind] ?? item.itemKind}
                                      </Badge>
                                      {item.title}
                                      <Badge
                                        tone={isActionable(item.status) ? 'warning' : 'success'}
                                      >
                                        {RESPONSIBILITY_STATUS_LABELS[item.status] ?? item.status}
                                      </Badge>
                                    </p>
                                    <p className="text-copy-12 text-muted-foreground">
                                      来源 {item.sourceSystem}
                                      {item.referenceNo ? ` · ${item.referenceNo}` : ''} · 接手人：
                                      {item.status === 'TRANSFERRED'
                                        ? successorLabel(item.successorName)
                                        : '未指定接手人'}
                                    </p>
                                  </div>
                                  {mayOffboard && isActionable(item.status) && (
                                    <SelectSuccessor
                                      disabled={busy}
                                      members={members}
                                      onPick={(successorId) =>
                                        void act(
                                          `identity/offboarding/${c.id}/responsibility-items/${item.id}/transfer`,
                                          { successorMembershipId: successorId },
                                          '责任已转移',
                                        )
                                      }
                                    />
                                  )}
                                </li>
                              ))}
                            </ul>
                          )}
                        </section>

                        <section className="space-y-2">
                          <h3 className="text-copy-13 font-medium">
                            待办与任务回收（{detail.reclaimItems.length}）
                          </h3>
                          <p className="text-copy-12 text-muted-foreground">
                            待办可以直接关闭，任务建议改派给接手人 —— 两种结论都会记进台账，
                            事后能分清「关掉了」还是「转给别人了」。
                          </p>
                          {detail.reclaimItems.length === 0 ? (
                            <p className="text-copy-12 text-muted-foreground">
                              没有登记待回收的待办或任务。
                            </p>
                          ) : (
                            <ul className="space-y-1">
                              {detail.reclaimItems.map((item) => (
                                <li
                                  key={item.id}
                                  className="border-border flex flex-wrap items-center justify-between gap-2 border-b py-2 last:border-b-0"
                                >
                                  <div className="min-w-0">
                                    <p className="text-copy-13 flex flex-wrap items-center gap-2">
                                      <Badge tone="neutral">
                                        {RECLAIM_KIND_LABELS[item.itemKind] ?? item.itemKind}
                                      </Badge>
                                      {item.title}
                                      <Badge
                                        tone={isActionable(item.status) ? 'warning' : 'success'}
                                      >
                                        {RECLAIM_STATUS_LABELS[item.status] ?? item.status}
                                      </Badge>
                                    </p>
                                    <p className="text-copy-12 text-muted-foreground">
                                      来源 {item.sourceSystem}
                                      {item.status !== 'PENDING'
                                        ? ` · 接手人：${successorLabel(item.successorName)}`
                                        : ''}
                                    </p>
                                  </div>
                                  {mayOffboard && isActionable(item.status) && (
                                    <div className="flex items-center gap-2">
                                      <Button
                                        variant="outline"
                                        size="sm"
                                        disabled={busy}
                                        onClick={() => {
                                          if (!window.confirm(`关闭待办「${item.title}」？`))
                                            return;
                                          void act(
                                            `identity/offboarding/${c.id}/reclaim-items/${item.id}/reclaim`,
                                            {},
                                            '待办已关闭',
                                          );
                                        }}
                                      >
                                        关闭
                                      </Button>
                                      <SelectSuccessor
                                        disabled={busy}
                                        members={members}
                                        onPick={(successorId) =>
                                          void act(
                                            `identity/offboarding/${c.id}/reclaim-items/${item.id}/reclaim`,
                                            { successorMembershipId: successorId },
                                            '已改派给接手人',
                                          )
                                        }
                                      />
                                    </div>
                                  )}
                                </li>
                              ))}
                            </ul>
                          )}
                        </section>
                      </>
                    )}
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

/** 接手人下拉：排除原责任人（转给自己没有意义）与停用成员。 */
function SelectSuccessor({
  members,
  disabled,
  onPick,
  exclude,
}: {
  members: MemberView[];
  disabled: boolean;
  onPick: (membershipId: string) => void;
  exclude?: string;
}) {
  const options = members.filter((m) => m.status === 'ACTIVE' && m.id !== exclude);
  if (options.length === 0)
    return <span className="text-copy-12 text-muted-foreground">没有可选的启用中成员</span>;
  return (
    <select
      disabled={disabled}
      aria-label="选择接手人"
      defaultValue=""
      onChange={(e) => {
        const value = e.target.value;
        e.target.value = '';
        if (value) onPick(value);
      }}
      className="border-border bg-card text-copy-13 focus-visible:border-ring h-8 rounded-md border px-2 outline-none"
    >
      <option value="">转给…</option>
      {options.map((m) => (
        <option key={m.id} value={m.id}>
          {m.username}
        </option>
      ))}
    </select>
  );
}

/**
 * 发起离职。
 *
 * 生效时刻默认填当前时间：绝大多数离职是「现在就停用」，要补过去时间的情况很少，
 * 但留出这个入口是因为补录场景真实存在。
 */
function OffboardingForm({
  members,
  busy,
  onCancel,
  onSubmit,
}: {
  members: MemberView[];
  busy: boolean;
  onCancel: () => void;
  onSubmit: (input: OffboardingInput) => void;
}) {
  const [membershipId, setMembershipId] = useState(members[0]?.id ?? '');
  const [reason, setReason] = useState('');
  const [effectiveAt, setEffectiveAt] = useState(() => new Date().toISOString().slice(0, 16));

  const valid = membershipId !== '' && reason.trim() !== '' && effectiveAt !== '';

  return (
    <section className="bg-card space-y-3 rounded-lg border p-6">
      <div>
        <h2 className="font-semibold">发起离职</h2>
        <p className="text-copy-12 text-muted-foreground mt-1">
          停用成员并建立离职记录。成员行、账号与历史操作记录都会保留，只撤销角色分配与登录资格。
        </p>
      </div>
      <Field label="离职成员">
        <select
          className={control}
          value={membershipId}
          onChange={(e) => setMembershipId(e.target.value)}
        >
          {members.map((m) => (
            <option key={m.id} value={m.id}>
              {m.username}
            </option>
          ))}
        </select>
      </Field>
      <Field label="离职原因" hint="状态流转必须带原因，事后审计要能解释为什么停用。">
        <input
          className={control}
          value={reason}
          maxLength={500}
          onChange={(e) => setReason(e.target.value)}
          placeholder="例如：合同到期离职"
        />
      </Field>
      <Field
        label="停用生效时刻"
        hint="按你的本地时区填写，提交时会带时区偏移提交，服务端按时刻入库。"
      >
        <input
          type="datetime-local"
          className={control}
          value={effectiveAt}
          onChange={(e) => setEffectiveAt(e.target.value)}
        />
      </Field>
      <div className="flex gap-2">
        <Button
          size="sm"
          disabled={!valid || busy}
          onClick={() =>
            onSubmit({
              membershipId,
              reason: reason.trim(),
              // datetime-local 没有时区信息，这里显式按本地时区换算成 UTC 再提交，
              // 否则服务端会把裸字符串当 UTC，差一个时区偏移。
              effectiveAt: new Date(effectiveAt).toISOString(),
              // 契约里这两个字段是「必填但可为 null」（exactOptionalPropertyTypes 下不能省略键），
              // 所以显式传 null，而不是干脆不传。
              departmentLeaderSuccessorMembershipId: null,
              note: null,
              responsibilityItems: [],
              reclaimItems: [],
            })
          }
        >
          确认停用
        </Button>
        <Button variant="ghost" size="sm" onClick={onCancel}>
          取消
        </Button>
      </div>
    </section>
  );
}
