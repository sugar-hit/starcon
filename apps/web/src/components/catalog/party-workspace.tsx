'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard, StatCard } from '@starcon/ui';
import {
  Building2,
  Plus,
  RefreshCw,
  Save,
  ShieldAlert,
  Trash2,
  TriangleAlert,
  UserRound,
} from 'lucide-react';
import type {
  PartyDetail,
  PartyInput,
  PartyOverview,
  PartyRole,
  PartyRoleEditInput,
  PartyRow,
} from '../../../../../packages/api-contracts/src/catalog';
import { Editor, Field, control } from '../common/fields';
import {
  PARTY_ROLES,
  PARTY_STATUSES,
  PARTY_TYPES,
  USABLE_FILTERS,
  allowedTargets,
  assignRoleProblem,
  createParty,
  createRole,
  deleteProblem,
  deleteParty,
  emptyHint,
  emptyPartyInput,
  emptyRoleInput,
  errorText,
  fetchCandidates,
  fetchDetail,
  fetchOverview,
  fetchParties,
  partyInputProblem,
  reasonHint,
  removeRole,
  removedRoles,
  roleLabel,
  saveParty,
  saveRole,
  setPartyStatus,
  statusHint,
  statusLabel,
  toPartyInput,
  toRoleInput,
  transitionProblem,
  usableBlockLabel,
  type UsableFilter,
} from './party-model';
import { useIdentity } from '../iam/identity-shell';

const PAGE_SIZE = 20;

/**
 * 3.6.1.1 往来单位工作区。
 *
 * <p>页面按「维护主体 → 看它扮演哪些身份」的顺序排。每个区块回答一个独立的问题：
 *
 * <ul>
 *   <li><b>概览</b> —— 规模，以及两个治理缺口：「同时是供应商和客户」的主体数
 *       （蓝图要求一份记录就够用，它下降说明有人在建重复主体）与「状态可用却没角色」的条数。
 *   <li><b>往来单位列表</b> —— 分页 + 搜索 + 筛选。<b>每行直接显示角色</b>：
 *       这是本模块最容易出错的地方，如果角色只藏在详情里，运营看到一个既是供应商又是客户
 *       的主体会以为数据错了，于是去建第二条记录 —— 那正是本模块要消灭的形态。
 *   <li><b>主体编辑</b> —— 编码、名称、类型、国家、联系方式、状态与角色（整体替换）。
 *   <li><b>状态流转</b> —— 走独立动作而不是保存表单：进出拉黑必须填理由。
 *   <li><b>角色指派</b> —— 指派 / 启用停用 / 解除，并显式提示「这次保存会解掉哪些身份」。
 *   <li><b>引用候选</b> —— 本模块交给 06 采购 / 11 售后 / 12 财务的那份下拉数据，
 *       直接消费它们会调用的同一个端点，不是另做一套演示。
 * </ul>
 *
 * <p>可用性判定一律来自后端（`PartyRules.usableBlock` 是唯一实现），前端只把原因码
 * 翻成要显示的话。理由见 `party-model.ts` 头部。
 */
export function PartyWorkspace() {
  const { session } = useIdentity();
  const writable = session.permissions.includes('catalog.spu.update');

  const [overview, setOverview] = useState<PartyOverview | null>(null);
  const [rows, setRows] = useState<PartyRow[]>([]);
  const [total, setTotal] = useState(0);
  const [activeCount, setActiveCount] = useState(0);
  const [blacklistedCount, setBlacklistedCount] = useState(0);
  const [noRoleCount, setNoRoleCount] = useState(0);
  const [note, setNote] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState('');

  const [keyword, setKeyword] = useState('');
  const [partyType, setPartyType] = useState('');
  const [status, setStatus] = useState('');
  const [roleType, setRoleType] = useState('');
  const [countryCode, setCountryCode] = useState('');
  const [usable, setUsable] = useState<UsableFilter>('');
  const [includeDeleted, setIncludeDeleted] = useState(false);
  const [index, setIndex] = useState(0);

  const [editing, setEditing] = useState<PartyInput | 'new' | null>(null);
  const [detail, setDetail] = useState<PartyDetail | null>(null);
  const [transition, setTransition] = useState<{ to: string; reason: string } | null>(null);
  const [roleEditing, setRoleEditing] = useState<PartyRoleEditInput | 'new' | null>(null);
  const [candidates, setCandidates] = useState<
    {
      id: string;
      code: string;
      name: string;
      roleSummary: string;
      usable: boolean;
      usableBlockLabel: string | null;
    }[]
  >([]);

  const filtered =
    Boolean(keyword.trim()) ||
    partyType !== '' ||
    status !== '' ||
    roleType !== '' ||
    countryCode.trim() !== '' ||
    usable !== '' ||
    includeDeleted;

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [overviewResult, pageResult] = await Promise.all([
        fetchOverview(),
        fetchParties({
          keyword,
          partyType,
          status,
          roleType,
          countryCode,
          includeDeleted,
          usable: usable === '' ? null : usable === 'true',
          page: index,
          size: PAGE_SIZE,
        }),
      ]);
      setOverview(overviewResult);
      setRows(pageResult.items ?? []);
      setTotal(pageResult.total);
      setActiveCount(pageResult.activeCount);
      setBlacklistedCount(pageResult.blacklistedCount);
      setNoRoleCount(pageResult.noRoleCount);
      setNote(pageResult.note);
    } catch (e) {
      setError(errorText(e, '往来单位数据加载失败'));
    } finally {
      setLoading(false);
    }
  }, [keyword, partyType, status, roleType, countryCode, includeDeleted, usable, index]);

  useEffect(() => {
    void load();
  }, [load]);

  // 编辑保存时会被整体替换掉、但没有出现在提交集合里的角色。
  // 界面必须显式提示：整体替换语义下少发一条就是静默的身份丢失，
  // 而「我明明在改名字，怎么供应商身份没了」是最难排查的一类问题。
  const willRemove = useMemo(() => {
    if (editing === 'new' || editing === null || !detail) return [];
    return removedRoles(detail.roles ?? [], editing.roles ?? []);
  }, [editing, detail]);

  async function openDetail(partyId: string) {
    setBusy(true);
    setActionError(null);
    try {
      setDetail(await fetchDetail(partyId));
      setEditing(null);
      setTransition(null);
      setRoleEditing(null);
    } catch (e) {
      setActionError(errorText(e, '往来单位详情加载失败'));
    } finally {
      setBusy(false);
    }
  }

  /**
   * 进入编辑。
   *
   * <p>只收 id、不收行对象：详情卡与列表行都要能触发它，而两者手里的对象类型不同
   * （一个是 {@code PartyDetail}、一个是 {@code PartyRow}）。统一按 id 重新取一次详情，
   * 也顺带保证表单里的 {@code version} 是最新的（行级乐观锁靠它）。
   */
  async function startEdit(partyId?: string) {
    setActionError(null);
    setTransition(null);
    setRoleEditing(null);
    if (!partyId) {
      setDetail(null);
      setEditing(emptyPartyInput());
      return;
    }
    setBusy(true);
    try {
      const loaded = await fetchDetail(partyId);
      setDetail(loaded);
      setEditing(toPartyInput(loaded));
    } catch (e) {
      setActionError(errorText(e, '往来单位详情加载失败'));
    } finally {
      setBusy(false);
    }
  }

  async function submitParty() {
    if (editing === 'new' || editing === null) return;
    const problem = partyInputProblem(editing);
    if (problem) {
      setActionError(problem);
      return;
    }
    setBusy(true);
    setActionError(null);
    try {
      // 编辑必须回传 version（行级乐观锁），新建时是 0。
      await (editing.version === 0 ? createParty(editing) : saveParty(detail!.id, editing));
      setNotice(editing.version === 0 ? '往来单位已创建' : '往来单位已保存');
      setEditing(null);
      setDetail(null);
      await load();
    } catch (e) {
      setActionError(errorText(e, '往来单位保存失败'));
    } finally {
      setBusy(false);
    }
  }

  async function submitTransition() {
    if (!transition || !detail) return;
    const problem = transitionProblem(detail.status, transition.to, transition.reason);
    if (problem) {
      setActionError(problem);
      return;
    }
    setBusy(true);
    setActionError(null);
    try {
      await setPartyStatus(detail.id, {
        status: transition.to,
        reason: transition.reason.trim() || null,
        version: detail.version,
      });
      setNotice(`状态已流转为「${statusLabel(transition.to)}」`);
      setTransition(null);
      await openDetail(detail.id);
      await load();
    } catch (e) {
      setActionError(errorText(e, '状态流转失败'));
    } finally {
      setBusy(false);
    }
  }

  async function submitRole() {
    if (roleEditing === 'new' || roleEditing === null || !detail) return;
    const already = (detail.roles ?? []).some((role) => role.roleType === roleEditing.roleType);
    const problem = assignRoleProblem(detail.status, roleEditing.roleType, already);
    if (problem) {
      setActionError(problem);
      return;
    }
    setBusy(true);
    setActionError(null);
    try {
      await createRole(detail.id, roleEditing);
      setNotice(`已指派「${roleLabel(roleEditing.roleType)}」身份`);
      setRoleEditing(null);
      await openDetail(detail.id);
      await load();
    } catch (e) {
      setActionError(errorText(e, '角色指派失败'));
    } finally {
      setBusy(false);
    }
  }

  async function saveExistingRole(next: PartyRole) {
    if (!detail) return;
    setBusy(true);
    setActionError(null);
    try {
      await saveRole(detail.id, next.id, toRoleInput(next));
      setNotice('角色指派已保存');
      await openDetail(detail.id);
      await load();
    } catch (e) {
      setActionError(errorText(e, '角色保存失败'));
    } finally {
      setBusy(false);
    }
  }

  async function dropRole(next: PartyRole) {
    if (!detail) return;
    setBusy(true);
    setActionError(null);
    try {
      await removeRole(detail.id, next.id, next.version);
      setNotice(`已解除「${next.roleLabel}」身份`);
      await openDetail(detail.id);
      await load();
    } catch (e) {
      setActionError(errorText(e, '角色解除失败'));
    } finally {
      setBusy(false);
    }
  }

  async function removeParty() {
    if (!detail) return;
    // P7：先在界面上说清楚为什么要先解角色，而不是让用户提交后吃一个 400。
    const problem = deleteProblem(detail.roles ?? []);
    if (problem) {
      setActionError(problem);
      return;
    }
    setBusy(true);
    setActionError(null);
    try {
      await deleteParty(detail.id, detail.version);
      setNotice('往来单位已删除：行仍保留，历史单据照常显示它的编码与名称');
      setDetail(null);
      setEditing(null);
      await load();
    } catch (e) {
      setActionError(errorText(e, '往来单位删除失败'));
    } finally {
      setBusy(false);
    }
  }

  async function loadCandidates(includeBlocked: boolean) {
    setBusy(true);
    setActionError(null);
    try {
      const result = await fetchCandidates(roleType, keyword, includeBlocked);
      setCandidates(
        result.map((item) => ({
          id: item.id,
          code: item.code,
          name: item.name,
          roleSummary: item.roleSummary,
          usable: item.usable,
          usableBlockLabel: item.usable ? null : usableBlockLabel(item.usableBlockCode),
        })),
      );
    } catch (e) {
      setActionError(errorText(e, '引用候选加载失败'));
    } finally {
      setBusy(false);
    }
  }

  const lastPage = Math.max(0, Math.ceil(total / PAGE_SIZE) - 1);

  return (
    <div className="space-y-6">
      <header className="space-y-2">
        <h1 className="text-h-20 font-semibold tracking-tight">往来单位</h1>
        <p className="text-copy-13 text-muted-foreground">
          业务主体档案：企业或个人，含编码、名称、联系方式与状态。同一主体可以同时是供应商、客户与承运商
          —— <b>一份记录，按角色区分，不要为每个身份各建一条</b>
          。角色特有信息（账期、信用额度、接口账号） 归 3.6.1.2 角色档案。
        </p>
      </header>

      <section className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          label="往来单位总数"
          value={overview?.partyCount ?? 0}
          icon={<Building2 className="h-4 w-4" />}
        />
        <StatCard
          label="可用"
          value={activeCount}
          delta={overview?.usableCount ?? 0}
          comparisonLabel="其中能被下游选中的"
        />
        <StatCard
          label="多身份主体"
          value={overview?.multiRoleCount ?? 0}
          icon={<UserRound className="h-4 w-4" />}
          polarity={(overview?.multiRoleCount ?? 0) > 0 ? 'positive' : 'neutral'}
        />
        <StatCard
          label="无角色"
          value={noRoleCount}
          icon={<TriangleAlert className="h-4 w-4" />}
          polarity={noRoleCount > 0 ? 'negative' : 'positive'}
        />
      </section>

      {error && (
        <SectionCard title="加载失败">
          <div className="text-destructive space-y-2 text-sm">
            <p>{error}</p>
            <Button variant="outline" size="sm" onClick={() => void load()}>
              <RefreshCw className="h-4 w-4" />
              重试
            </Button>
          </div>
        </SectionCard>
      )}

      {notice && (
        <p className="text-muted-foreground text-sm" role="status">
          {notice}
        </p>
      )}

      {actionError && (
        <SectionCard title="操作未完成">
          <p className="text-destructive text-sm">{actionError}</p>
        </SectionCard>
      )}

      <SectionCard
        title="往来单位列表"
        description="每行直接显示它扮演的角色与可用性：不可用的连原因一起显示，不用点开详情才知道。"
        extra={
          writable ? (
            <Button size="sm" onClick={() => void startEdit()}>
              <Plus className="h-4 w-4" />
              新建往来单位
            </Button>
          ) : undefined
        }
      >
        <div className="space-y-3">
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
            <Field label="关键词" hint="编码 / 名称 / 邮箱 / 电话">
              <input
                className={control}
                value={keyword}
                placeholder="搜对方公司或联系人邮箱"
                onChange={(e) => {
                  setKeyword(e.target.value);
                  setIndex(0);
                }}
              />
            </Field>
            <Field label="主体类型">
              <select
                className={control}
                value={partyType}
                onChange={(e) => {
                  setPartyType(e.target.value);
                  setIndex(0);
                }}
              >
                <option value="">全部</option>
                {PARTY_TYPES.map((item) => (
                  <option key={item.value} value={item.value}>
                    {item.label}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="状态">
              <select
                className={control}
                value={status}
                onChange={(e) => {
                  setStatus(e.target.value);
                  setIndex(0);
                }}
              >
                <option value="">全部</option>
                {PARTY_STATUSES.map((item) => (
                  <option key={item.value} value={item.value}>
                    {item.label}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="角色" hint="按「当前正在使用的身份」筛">
              <select
                className={control}
                value={roleType}
                onChange={(e) => {
                  setRoleType(e.target.value);
                  setIndex(0);
                }}
              >
                <option value="">全部</option>
                {PARTY_ROLES.map((item) => (
                  <option key={item.value} value={item.value}>
                    {item.label}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="国家">
              <input
                className={control}
                value={countryCode}
                placeholder="CN / DE / US"
                maxLength={2}
                onChange={(e) => {
                  setCountryCode(e.target.value);
                  setIndex(0);
                }}
              />
            </Field>
            <Field label="可用性">
              <select
                className={control}
                value={usable}
                onChange={(e) => {
                  setUsable(e.target.value as UsableFilter);
                  setIndex(0);
                }}
              >
                {USABLE_FILTERS.map((item) => (
                  <option key={item.value} value={item.value}>
                    {item.label}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="已删除" hint="软删除的主体默认不显示">
              <select
                className={control}
                value={includeDeleted ? 'true' : ''}
                onChange={(e) => {
                  setIncludeDeleted(e.target.value === 'true');
                  setIndex(0);
                }}
              >
                <option value="">隐藏</option>
                <option value="true">显示已删除</option>
              </select>
            </Field>
          </div>

          {loading ? (
            <p className="text-muted-foreground text-sm">正在读取往来单位…</p>
          ) : rows.length === 0 ? (
            <EmptyState title="没有往来单位" description={emptyHint(filtered)} />
          ) : (
            <div className="space-y-2">
              <table className="w-full text-sm">
                <thead className="text-muted-foreground text-left">
                  <tr>
                    <th className="py-2">往来单位</th>
                    <th className="py-2">类型</th>
                    <th className="py-2">角色</th>
                    <th className="py-2">状态</th>
                    <th className="py-2">可用性</th>
                    <th className="py-2" />
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.id} className="border-border border-t align-top">
                      <td className="py-2">
                        <div className="font-medium">{row.name}</div>
                        <div className="text-muted-foreground text-xs">{row.code}</div>
                        <div className="text-muted-foreground text-xs">
                          {[row.email, row.phone].filter(Boolean).join(' / ') || '无联系方式'}
                        </div>
                      </td>
                      <td className="py-2">
                        <Badge tone="neutral">{row.partyTypeLabel}</Badge>
                        {row.countryCode && (
                          <div className="text-muted-foreground text-xs">{row.countryCode}</div>
                        )}
                      </td>
                      <td className="py-2">
                        {/* 角色是本模块的核心信息，必须在列表上直接看见。 */}
                        {row.roles.length === 0 ? (
                          <span className="text-muted-foreground">未指派角色</span>
                        ) : (
                          <div className="flex flex-wrap gap-1">
                            {row.roles.map((type) => (
                              <Badge key={type} tone="info">
                                {roleLabel(type)}
                              </Badge>
                            ))}
                          </div>
                        )}
                        {row.roleCount > row.activeRoleCount && (
                          <div className="text-muted-foreground text-xs">
                            另有 {row.roleCount - row.activeRoleCount} 个已停用的身份
                          </div>
                        )}
                      </td>
                      <td className="py-2">
                        <Badge
                          tone={
                            row.status === 'ACTIVE'
                              ? 'success'
                              : row.status === 'BLACKLISTED'
                                ? 'danger'
                                : 'neutral'
                          }
                        >
                          {row.statusLabel}
                        </Badge>
                      </td>
                      <td className="py-2">
                        {row.usable ? (
                          <Badge tone="success">可用</Badge>
                        ) : (
                          <div className="space-y-1">
                            <Badge tone="warning">{usableBlockLabel(row.usableBlockCode)}</Badge>
                            <div className="text-muted-foreground max-w-64 text-xs">
                              {row.usableBlockMessage}
                            </div>
                          </div>
                        )}
                      </td>
                      <td className="py-2">
                        <div className="flex flex-wrap justify-end gap-2">
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => void openDetail(row.id)}
                          >
                            查看
                          </Button>
                          {writable && (
                            <Button
                              size="sm"
                              variant="outline"
                              disabled={busy}
                              onClick={() => void startEdit(row.id)}
                            >
                              编辑
                            </Button>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <div className="text-muted-foreground flex items-center justify-between text-xs">
                <span>
                  共 {total} 条 · 可用 {activeCount} 条 · 拉黑 {blacklistedCount} 条 · 无角色{' '}
                  {noRoleCount} 条{note ? ` · ${note}` : ''}
                </span>
                <div className="flex items-center gap-2">
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={index <= 0}
                    onClick={() => setIndex(Math.max(0, index - 1))}
                  >
                    上一页
                  </Button>
                  <span>
                    {index + 1} / {lastPage + 1}
                  </span>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={index >= lastPage}
                    onClick={() => setIndex(index + 1)}
                  >
                    下一页
                  </Button>
                </div>
              </div>
            </div>
          )}
        </div>
      </SectionCard>

      {detail && (
        <SectionCard
          title={detail.name}
          description={`${detail.code} · ${detail.partyTypeLabel} · 角色：${detail.roleSummary}`}
          extra={
            <div className="flex flex-wrap gap-2">
              {writable && (
                <Button size="sm" variant="outline" onClick={() => void startEdit(detail.id)}>
                  编辑
                </Button>
              )}
              <Button size="sm" variant="ghost" onClick={() => setDetail(null)}>
                关闭
              </Button>
            </div>
          }
        >
          <div className="space-y-4">
            {detail.deleted && (
              <p className="text-destructive flex items-start gap-2 text-sm">
                <Trash2 className="mt-0.5 h-4 w-4 shrink-0" />
                该往来单位已删除：行仍保留，所以历史单据照常能显示它的编码与名称；它不会再出现在任何列表或下拉里。
              </p>
            )}
            <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
              <div className="space-y-1 text-sm">
                <div className="text-muted-foreground text-xs">状态</div>
                <Badge
                  tone={
                    detail.status === 'ACTIVE'
                      ? 'success'
                      : detail.status === 'BLACKLISTED'
                        ? 'danger'
                        : 'neutral'
                  }
                >
                  {detail.statusLabel}
                </Badge>
                <div className="text-muted-foreground text-xs">{statusHint(detail.status)}</div>
              </div>
              <div className="space-y-1 text-sm">
                <div className="text-muted-foreground text-xs">可用性</div>
                {detail.usable ? (
                  <Badge tone="success">可用</Badge>
                ) : (
                  <>
                    <Badge tone="warning">{usableBlockLabel(detail.usableBlockCode)}</Badge>
                    <div className="text-muted-foreground text-xs">{detail.usableBlockMessage}</div>
                  </>
                )}
              </div>
              <div className="space-y-1 text-sm">
                <div className="text-muted-foreground text-xs">邮箱</div>
                <div>{detail.email ?? '—'}</div>
              </div>
              <div className="space-y-1 text-sm">
                <div className="text-muted-foreground text-xs">电话</div>
                <div>{detail.phone ?? '—'}</div>
              </div>
            </div>
            {detail.statusReason && (
              <p className="text-muted-foreground text-sm">
                状态理由：{detail.statusReason}
                {detail.statusChangedAt ? `（${detail.statusChangedAt}）` : ''}
              </p>
            )}

            {writable && !detail.deleted && (
              <div className="flex flex-wrap items-end gap-2">
                <Field label="状态流转" hint="进出拉黑必须填理由；停用不会删掉历史单据。">
                  <div className="flex flex-wrap gap-2">
                    {allowedTargets(detail.status).map((target) => (
                      <Button
                        key={target}
                        size="sm"
                        variant="outline"
                        disabled={busy}
                        onClick={() => setTransition({ to: target, reason: '' })}
                      >
                        {statusLabel(target)}
                      </Button>
                    ))}
                  </div>
                </Field>
              </div>
            )}

            <div className="space-y-2">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <h4 className="text-h-16 font-medium">角色指派</h4>
                {writable && !detail.deleted && detail.status !== 'BLACKLISTED' && (
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => setRoleEditing(emptyRoleInput())}
                  >
                    <Plus className="h-4 w-4" />
                    指派角色
                  </Button>
                )}
              </div>
              {detail.status === 'BLACKLISTED' && (
                <p className="text-muted-foreground text-xs">
                  该主体在拉黑名单里，不能指派新角色（解指派与改备注仍然可以）。请先解除拉黑。
                </p>
              )}
              {(detail.roles ?? []).length === 0 ? (
                <EmptyState
                  title="还没有角色"
                  description="没有角色的往来单位在采购、售后与财务的下拉里谁都选不出来。指派一个供应商 / 客户 / 承运商身份，它才会变得可用。"
                />
              ) : (
                <table className="w-full text-sm">
                  <thead className="text-muted-foreground text-left">
                    <tr>
                      <th className="py-2">角色</th>
                      <th className="py-2">状态</th>
                      <th className="py-2">备注</th>
                      <th className="py-2" />
                    </tr>
                  </thead>
                  <tbody>
                    {detail.roles!.map((role) => (
                      <tr key={role.id} className="border-border border-t align-top">
                        <td className="py-2">{role.roleLabel}</td>
                        <td className="py-2">
                          <Badge tone={role.status === 'ACTIVE' ? 'success' : 'neutral'}>
                            {role.statusLabel}
                          </Badge>
                        </td>
                        <td className="text-muted-foreground py-2 text-xs">{role.note ?? '—'}</td>
                        <td className="py-2">
                          <div className="flex flex-wrap justify-end gap-2">
                            {writable && !detail.deleted && (
                              <>
                                <Button
                                  size="sm"
                                  variant="outline"
                                  disabled={busy}
                                  onClick={() =>
                                    void saveExistingRole({
                                      ...role,
                                      status: role.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE',
                                    })
                                  }
                                >
                                  {role.status === 'ACTIVE' ? '停用身份' : '启用身份'}
                                </Button>
                                <Button
                                  size="sm"
                                  variant="outline"
                                  disabled={busy}
                                  onClick={() => void dropRole(role)}
                                >
                                  解除
                                </Button>
                              </>
                            )}
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
              <p className="text-muted-foreground text-xs">
                角色特有信息（账期、MOQ、客户分级、信用额度、接口账号）由 3.6.1.2 角色档案维护：
                每个角色一张 1:1 档案表。本表只回答「这个主体扮演哪些身份」。
              </p>
            </div>

            {writable && !detail.deleted && (
              <Button
                size="sm"
                variant="outline"
                disabled={busy || (detail.roles ?? []).length > 0}
                title={
                  (detail.roles ?? []).length > 0
                    ? '删除前必须先解掉全部角色，否则会留下「角色还在、主体不见了」的状态'
                    : undefined
                }
                onClick={() => void removeParty()}
              >
                <Trash2 className="h-4 w-4" />
                删除往来单位
              </Button>
            )}
            {(detail.roles ?? []).length > 0 && (
              <p className="text-muted-foreground text-xs">
                按钮不可点：删除前要先在上面解除这 {(detail.roles ?? []).length} 个角色。
              </p>
            )}
          </div>
        </SectionCard>
      )}

      <SectionCard
        title="引用候选（下游下拉的数据源）"
        description="06 采购、11 售后、12 财务选往来单位时调的就是这一个端点。默认只给可用的主体。"
        extra={
          <div className="flex flex-wrap gap-2">
            <Button
              size="sm"
              variant="outline"
              disabled={busy}
              onClick={() => void loadCandidates(false)}
            >
              只看可用
            </Button>
            <Button
              size="sm"
              variant="outline"
              disabled={busy}
              onClick={() => void loadCandidates(true)}
            >
              含不可用（带原因）
            </Button>
          </div>
        }
      >
        {candidates.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            点上面的按钮读取候选。候选默认只给「可用 + 至少一个角色」的主体
            {roleType ? `（当前按「${roleLabel(roleType)}」筛选）` : ''}。
          </p>
        ) : (
          <table className="w-full text-sm">
            <thead className="text-muted-foreground text-left">
              <tr>
                <th className="py-2">编码</th>
                <th className="py-2">名称</th>
                <th className="py-2">角色</th>
                <th className="py-2">可用性</th>
              </tr>
            </thead>
            <tbody>
              {candidates.map((item) => (
                <tr key={item.id} className="border-border border-t">
                  <td className="py-2">{item.code}</td>
                  <td className="py-2">{item.name}</td>
                  <td className="py-2 text-xs">{item.roleSummary}</td>
                  <td className="py-2">
                    {item.usable ? (
                      <Badge tone="success">可用</Badge>
                    ) : (
                      <div className="space-y-1">
                        <Badge tone="warning">
                          <ShieldAlert className="mr-1 h-3 w-3" />
                          {item.usableBlockLabel}
                        </Badge>
                      </div>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </SectionCard>

      {editing !== null && editing !== 'new' && (
        <Editor
          title={detail ? `编辑：${detail.name}` : '新建往来单位'}
          description={
            detail
              ? '编码建后不可改：采购单、应付账款与售后单都按它对齐这个主体。角色是整体替换语义。'
              : '不想当场定角色的，先建成「停用」，挂好角色再启用 —— 这是正常流程。'
          }
          close={() => {
            setEditing(null);
            setDetail(null);
          }}
        >
          <div className="space-y-4">
            <div className="grid gap-3 sm:grid-cols-2">
              <Field
                label="编码"
                hint={
                  detail
                    ? '建后不可改：要改请新建一条往来单位。'
                    : '字母数字下划线短横线，1~64 位；租户内唯一。'
                }
              >
                <input
                  className={control}
                  value={editing.code}
                  readOnly={Boolean(detail)}
                  placeholder="SUP-ACME"
                  onChange={(e) => setEditing({ ...editing, code: e.target.value })}
                />
              </Field>
              <Field label="名称" hint="企业的法定名称或个人的姓名，最多 200 字。">
                <input
                  className={control}
                  value={editing.name}
                  onChange={(e) => setEditing({ ...editing, name: e.target.value })}
                />
              </Field>
              <Field label="主体类型">
                <select
                  className={control}
                  value={editing.partyType}
                  onChange={(e) => setEditing({ ...editing, partyType: e.target.value })}
                >
                  {PARTY_TYPES.map((item) => (
                    <option key={item.value} value={item.value}>
                      {item.label}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="国家" hint="ISO 3166-1 alpha-2 两位代码，可空。">
                <input
                  className={control}
                  value={editing.countryCode ?? ''}
                  maxLength={2}
                  placeholder="CN"
                  onChange={(e) => setEditing({ ...editing, countryCode: e.target.value || null })}
                />
              </Field>
              <Field label="邮箱" hint="与电话至少填一项，否则这张档案在单据上用不了。">
                <input
                  className={control}
                  value={editing.email ?? ''}
                  onChange={(e) => setEditing({ ...editing, email: e.target.value || null })}
                />
              </Field>
              <Field label="电话" hint="空格会被自动去掉；分机请用 - 连接。">
                <input
                  className={control}
                  value={editing.phone ?? ''}
                  onChange={(e) => setEditing({ ...editing, phone: e.target.value || null })}
                />
              </Field>
              <Field label="官网" hint="可空；不能替代邮箱与电话。">
                <input
                  className={control}
                  value={editing.website ?? ''}
                  onChange={(e) => setEditing({ ...editing, website: e.target.value || null })}
                />
              </Field>
              <Field label="状态" hint={statusHint(editing.status)}>
                <select
                  className={control}
                  value={editing.status}
                  onChange={(e) => setEditing({ ...editing, status: e.target.value })}
                >
                  {PARTY_STATUSES.map((item) => (
                    <option key={item.value} value={item.value}>
                      {item.label}
                    </option>
                  ))}
                </select>
              </Field>
            </div>

            <div className="space-y-2">
              <div className="flex items-center justify-between">
                <h4 className="text-h-16 font-medium">角色指派</h4>
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() =>
                    setEditing({
                      ...editing,
                      roles: [
                        ...(editing.roles ?? []),
                        { roleType: 'SUPPLIER', status: 'ACTIVE', note: null },
                      ] as PartyInput['roles'],
                    })
                  }
                >
                  <Plus className="h-4 w-4" />
                  添加角色
                </Button>
              </div>
              {(editing.roles ?? []).length === 0 ? (
                <p className="text-muted-foreground text-sm">
                  一个角色都没指派。状态为「可用」时必须有至少一个使用中的角色，否则保存会被拒。
                </p>
              ) : (
                <div className="space-y-2">
                  {(editing.roles ?? []).map((entry, position) => (
                    <div key={position} className="grid gap-2 sm:grid-cols-4">
                      <Field label="角色">
                        <select
                          className={control}
                          value={entry?.roleType ?? 'SUPPLIER'}
                          onChange={(e) => {
                            const next = [...(editing.roles ?? [])];
                            next[position] = {
                              ...(entry ?? { status: 'ACTIVE', note: null }),
                              roleType: e.target.value,
                            } as NonNullable<typeof entry>;
                            setEditing({ ...editing, roles: next });
                          }}
                        >
                          {PARTY_ROLES.map((item) => (
                            <option key={item.value} value={item.value}>
                              {item.label}
                            </option>
                          ))}
                        </select>
                      </Field>
                      <Field label="状态">
                        <select
                          className={control}
                          value={entry?.status ?? 'ACTIVE'}
                          onChange={(e) => {
                            const next = [...(editing.roles ?? [])];
                            next[position] = {
                              ...(entry ?? { roleType: 'SUPPLIER', note: null }),
                              status: e.target.value,
                            } as NonNullable<typeof entry>;
                            setEditing({ ...editing, roles: next });
                          }}
                        >
                          <option value="ACTIVE">使用中</option>
                          <option value="INACTIVE">已停用</option>
                        </select>
                      </Field>
                      <Field label="备注">
                        <input
                          className={control}
                          value={entry?.note ?? ''}
                          onChange={(e) => {
                            const next = [...(editing.roles ?? [])];
                            next[position] = {
                              ...(entry ?? { roleType: 'SUPPLIER', status: 'ACTIVE' }),
                              note: e.target.value || null,
                            } as NonNullable<typeof entry>;
                            setEditing({ ...editing, roles: next });
                          }}
                        />
                      </Field>
                      <div className="flex items-end">
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() =>
                            setEditing({
                              ...editing,
                              roles: (editing.roles ?? []).filter((_, i) => i !== position),
                            })
                          }
                        >
                          移除
                        </Button>
                      </div>
                    </div>
                  ))}
                </div>
              )}
              {willRemove.length > 0 && (
                <p className="text-warning flex items-start gap-2 text-xs">
                  <TriangleAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                  保存后这些身份会被解除：{willRemove.map((role) => role.roleLabel).join('、')}
                  。角色是整体替换语义，提交什么就等于什么。
                </p>
              )}
            </div>

            <div className="flex justify-end gap-2">
              <Button variant="outline" onClick={() => setEditing(null)}>
                取消
              </Button>
              <Button disabled={busy} onClick={() => void submitParty()}>
                <Save className="h-4 w-4" />
                保存
              </Button>
            </div>
          </div>
        </Editor>
      )}

      {transition && detail && (
        <Editor
          title={`状态流转：${statusLabel(detail.status)} → ${statusLabel(transition.to)}`}
          description={statusHint(transition.to)}
          close={() => setTransition(null)}
        >
          <div className="space-y-3">
            <Field
              label="变更理由"
              hint={
                reasonHint(detail.status, transition.to) ?? '可选。写清楚会让三个月后的排查省事。'
              }
            >
              <input
                className={control}
                value={transition.reason}
                onChange={(e) => setTransition({ ...transition, reason: e.target.value })}
              />
            </Field>
            <div className="flex justify-end gap-2">
              <Button variant="outline" onClick={() => setTransition(null)}>
                取消
              </Button>
              <Button disabled={busy} onClick={() => void submitTransition()}>
                确认流转
              </Button>
            </div>
          </div>
        </Editor>
      )}

      {roleEditing !== null && roleEditing !== 'new' && detail && (
        <Editor
          title="指派角色"
          description="一个主体可以同时是供应商、客户与承运商，但每个身份只有一条档案。"
          close={() => setRoleEditing(null)}
        >
          <div className="space-y-3">
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="角色">
                <select
                  className={control}
                  value={roleEditing.roleType}
                  onChange={(e) => setRoleEditing({ ...roleEditing, roleType: e.target.value })}
                >
                  {PARTY_ROLES.map((item) => (
                    <option key={item.value} value={item.value}>
                      {item.label}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="状态">
                <select
                  className={control}
                  value={roleEditing.status}
                  onChange={(e) => setRoleEditing({ ...roleEditing, status: e.target.value })}
                >
                  <option value="ACTIVE">使用中</option>
                  <option value="INACTIVE">已停用</option>
                </select>
              </Field>
            </div>
            <Field label="备注">
              <input
                className={control}
                value={roleEditing.note ?? ''}
                onChange={(e) => setRoleEditing({ ...roleEditing, note: e.target.value || null })}
              />
            </Field>
            <div className="flex justify-end gap-2">
              <Button variant="outline" onClick={() => setRoleEditing(null)}>
                取消
              </Button>
              <Button disabled={busy} onClick={() => void submitRole()}>
                确认指派
              </Button>
            </div>
          </div>
        </Editor>
      )}
    </div>
  );
}
