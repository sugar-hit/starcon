'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { RefreshCw } from 'lucide-react';
import { Button, EmptyState, Input, Notice, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  PickWaveCapabilities,
  PickWaveCandidatePage,
  PickWaveDetail,
  PickWavePage,
  PickWaveRuleList,
} from '../../../../../packages/api-contracts/src/index';
import { useIdentity } from '../iam/identity-shell';
import {
  canCancel,
  canComplete,
  canDetach,
  canRelease,
  cancelPickWave,
  completePickWave,
  createPickWave,
  createPickWaveRule,
  detachPickWaveTask,
  eventTypeLabel,
  fetchPickWave,
  fetchPickWaveCandidates,
  fetchPickWaveCapabilities,
  fetchPickWaveRules,
  fetchPickWaves,
  groupVerdict,
  mergeKeyText,
  progressText,
  qtyText,
  releasePickWave,
  ruleScopeText,
  slotText,
  statusLabel,
  statusTone,
  waveHint,
} from './pick-wave-model';

/**
 * 8.3.2.2 波次与合并拣货工作区。
 *
 * <p>蓝图：「<b>把多张出库单按规则合并成拣货波次，提高拣货效率：按承运商/库区/订单数合并，
 * 支持波次进度跟踪。</b>」「<b>合并只影响拣货效率，不改变订单粒度</b>：波次完成后仍需按订单拆分复核与打包。」
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>四块按「决策顺序」排</b>：合并规则 → 候选（现在该开哪一波）→ 波次台账 →
 *       波次详情。规则没配好时后三块都答不出「为什么这张单没被合进去」，
 *       所以规则放在最上面而不是折叠起来。</li>
 *   <li><b>候选按合并键分组</b>，每组给出「现在能并 / 得等上一波 / 没有规则认领」三个数。
 *       只显示第一个数时，看板上就只剩一个解释不通的空白。</li>
 *   <li><b>成员列表一行 = 一张出库单</b>，每行显示自己的出库单号、位置序号、
 *       任务状态与进度 —— 不给「本波共 N 件」这种把订单揉成一团的汇总。</li>
 *   <li><b>完成按钮旁边常驻一句「合并只影响拣货效率」</b>：完成之后出库单还要
 *       各自去 8.3.3.1 复核与打包，不写清楚的话「已完成」很容易被读成「货可以发了」。</li>
 *   <li>加载 / 空 / 错误三态分开渲染。</li>
 * </ul>
 */

export function PickWaveWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.inventory.read');
  const writable = session.permissions.includes('warehouse.inventory.update');

  const [warehouseId, setWarehouseId] = useState('');
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState('');
  const [keyword, setKeyword] = useState('');

  const [list, setList] = useState<PickWavePage | null>(null);
  const [caps, setCaps] = useState<PickWaveCapabilities | null>(null);
  const [rules, setRules] = useState<PickWaveRuleList | null>(null);
  const [candidates, setCandidates] = useState<PickWaveCandidatePage | null>(null);
  const [detail, setDetail] = useState<PickWaveDetail | null>(null);

  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState(false);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');

  // 新建规则表单
  const [ruleCode, setRuleCode] = useState('');
  const [ruleName, setRuleName] = useState('');
  const [ruleCarrier, setRuleCarrier] = useState('');
  const [ruleSlotStart, setRuleSlotStart] = useState('0');
  const [ruleSlotEnd, setRuleSlotEnd] = useState('24');
  const [ruleMaxOrders, setRuleMaxOrders] = useState('10');

  // 放行表单
  const [assignee, setAssignee] = useState('');
  // 取消原因
  const [cancelReason, setCancelReason] = useState('');

  const load = useCallback(async () => {
    if (!readable) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    try {
      const capability = await fetchPickWaveCapabilities();
      setCaps(capability);
      const data = await fetchPickWaves({ status, keyword, page, size: 50 });
      setList(data);
      const ruleList = await fetchPickWaveRules(warehouseId || undefined);
      setRules(ruleList);
      if (warehouseId !== '') {
        setCandidates(await fetchPickWaveCandidates(warehouseId));
      } else {
        setCandidates(null);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '波次读取失败');
    } finally {
      setLoading(false);
    }
  }, [status, keyword, page, warehouseId, readable]);

  useEffect(() => {
    void load();
  }, [load]);

  const rows = useMemo(() => list?.items ?? [], [list]);
  const wave = detail?.wave;
  const members = useMemo(() => detail?.members ?? [], [detail]);

  const run = async (work: () => Promise<PickWaveDetail>, ok: string) => {
    setActionLoading(true);
    setActionError('');
    setNotice('');
    try {
      const next = await work();
      setDetail(next);
      setNotice(ok);
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '操作失败');
    } finally {
      setActionLoading(false);
    }
  };

  const submitRule = async () => {
    setActionLoading(true);
    setActionError('');
    setNotice('');
    try {
      await createPickWaveRule({
        ruleCode,
        ruleName,
        warehouseId,
        carrierMatch: ruleCarrier === '' ? null : ruleCarrier,
        // 库区不限：显式 null（不是 undefined —— exactOptionalPropertyTypes 会拒）。
        zoneId: null,
        maxOrderCount: Number(ruleMaxOrders),
        timeSlotStart: Number(ruleSlotStart),
        timeSlotEnd: Number(ruleSlotEnd),
        priority: null,
      });
      setRuleCode('');
      setRuleName('');
      setNotice(`合并规则已创建：${ruleCode}`);
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '合并规则创建失败');
    } finally {
      setActionLoading(false);
    }
  };

  const buildWave = async (group: {
    carrierCode: string;
    zoneId: string | null;
    ruleId: string | null;
  }) => {
    setActionLoading(true);
    setActionError('');
    setNotice('');
    try {
      const created = await createPickWave({
        warehouseId,
        carrierCode: group.carrierCode === '' ? null : group.carrierCode,
        zoneId: group.zoneId,
        ruleId: group.ruleId,
        pickTaskIds: null,
        plannedPickAt: null,
        note: null,
      });
      setNotice(
        `已排波 ${created.waveCode}：并入 ${created.memberCount} 张出库单` +
          (created.waitingCount > 0 ? `，另有 ${created.waitingCount} 张等下一波。` : '。'),
      );
      await load();
      await openWave(created.id);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '建波次失败');
    } finally {
      setActionLoading(false);
    }
  };

  const openWave = async (waveId: string) => {
    try {
      setDetail(await fetchPickWave(waveId));
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '波次详情读取失败');
    }
  };

  if (!readable) {
    return (
      <SectionCard>
        <EmptyState
          title="没有查看波次的权限"
          description="本功能需要出入库作业读取权限（warehouse.inventory.read）。"
        />
      </SectionCard>
    );
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="拣货波次"
        description="按承运渠道、库区、时段与订单数把多张出库单合并成波次，提高拣货效率；合并只影响拣货效率，不改变订单粒度"
        actions={
          <Button onClick={() => void load()} disabled={loading}>
            <RefreshCw className="h-4 w-4" /> 刷新
          </Button>
        }
      />

      {error !== '' && (
        <Notice tone="danger" title="波次读取失败">
          {error}
        </Notice>
      )}
      {actionError !== '' && (
        <Notice tone="danger" title="操作失败">
          {actionError}
        </Notice>
      )}
      {notice !== '' && (
        <Notice tone="success" title="操作成功">
          {notice}
        </Notice>
      )}

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
        <StatCard label="已排波" value={String(list?.summary.plannedCount ?? 0)} />
        <StatCard label="拣货中" value={String(list?.summary.pickingCount ?? 0)} />
        <StatCard label="已完成" value={String(list?.summary.completedCount ?? 0)} />
        <StatCard label="已取消" value={String(list?.summary.cancelledCount ?? 0)} />
        <StatCard label="未完波次里的出库单" value={String(list?.summary.openOrderCount ?? 0)} />
      </div>

      {caps !== null && (
        <SectionCard title="合并口径（先看这里，否则看不懂下面为什么少了几张单）">
          <div className="space-y-2 text-sm text-slate-300">
            <p>{caps.mergeBasis}</p>
            <p className="text-xs text-slate-400">库区口径：{caps.zoneBasis}</p>
            <p className="text-xs text-slate-400">时段口径：{caps.timeSlotBasis}</p>
            <p className="text-xs text-slate-400">{caps.note}</p>
            <p className="text-xs text-slate-400">{caps.inventoryNote}</p>
          </div>
        </SectionCard>
      )}

      <SectionCard title="仓库与筛选">
        <div className="grid gap-3 sm:grid-cols-4">
          <label className="text-sm text-slate-300">
            仓库 id（填了才显示候选与建波次）
            <Input
              value={warehouseId}
              placeholder="如 6f46e7a4-4c9b-452e-a3f9-938465325455"
              onChange={(e) => {
                setWarehouseId(e.target.value);
                setPage(0);
              }}
            />
          </label>
          <label className="text-sm text-slate-300">
            状态
            <select
              className="mt-1 w-full rounded border border-slate-600 bg-slate-900 px-2 py-1 text-sm"
              value={status}
              onChange={(e) => {
                setStatus(e.target.value);
                setPage(0);
              }}
            >
              <option value="">全部</option>
              {(caps?.statuses ?? []).map((s) => (
                <option key={s} value={s}>
                  {statusLabel(s)}
                </option>
              ))}
            </select>
          </label>
          <label className="text-sm text-slate-300">
            关键字（波次号 / 出库单号）
            <Input
              value={keyword}
              placeholder="如 PW-20261014-1a2b3c4d"
              onChange={(e) => {
                setKeyword(e.target.value);
                setPage(0);
              }}
            />
          </label>
          <div className="flex items-end gap-2">
            <Button
              onClick={() => {
                setStatus('');
                setKeyword('');
                setPage(0);
              }}
            >
              重置筛选
            </Button>
          </div>
        </div>
      </SectionCard>

      <SectionCard title="合并规则（承运渠道 / 库区 / 时段 / 订单数上限）">
        {loading && rules === null ? (
          <p className="text-sm text-slate-400">正在加载合并规则…</p>
        ) : (rules?.items.length ?? 0) === 0 ? (
          <EmptyState
            title="还没有合并规则"
            description="没有生效规则时没有任何出库单会被认领，波次只能手工指定成员。先在下面建一条：承运渠道（留空 = 不限）、时段（仓本地小时）、订单数上限。"
          />
        ) : (
          <table className="w-full text-sm">
            <thead className="text-left text-slate-400">
              <tr>
                <th className="py-2">规则号</th>
                <th className="py-2">名称</th>
                <th className="py-2">覆盖范围</th>
                <th className="py-2">订单数上限</th>
                <th className="py-2">当前认领</th>
                <th className="py-2">在等上一波</th>
                <th className="py-2">状态</th>
              </tr>
            </thead>
            <tbody>
              {rules?.items.map((rule) => (
                <tr key={rule.id} className="border-t border-slate-800">
                  <td className="py-2 font-mono text-xs">{rule.ruleCode}</td>
                  <td className="py-2">{rule.ruleName}</td>
                  <td className="py-2 text-xs">{ruleScopeText(rule)}</td>
                  <td className="py-2">{rule.maxOrderCount}</td>
                  <td className="py-2">{rule.activeTaskCount}</td>
                  <td className="py-2">
                    {rule.waitingOrderCount}
                    {rule.waitingOrderCount > 0 && (
                      <span className="ml-1 text-xs text-amber-200">
                        （同键已有未完波次，得等它收工）
                      </span>
                    )}
                  </td>
                  <td className="py-2">{rule.status === 'ACTIVE' ? '生效中' : '已停用'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {writable && (
          <div className="mt-4 grid gap-3 sm:grid-cols-6">
            <label className="text-sm text-slate-300">
              规则号
              <Input
                value={ruleCode}
                placeholder="R-MERGE-1"
                onChange={(e) => setRuleCode(e.target.value)}
              />
            </label>
            <label className="text-sm text-slate-300">
              名称
              <Input
                value={ruleName}
                placeholder="SF 上午班"
                onChange={(e) => setRuleName(e.target.value)}
              />
            </label>
            <label className="text-sm text-slate-300">
              承运渠道（留空 = 不限）
              <Input
                value={ruleCarrier}
                placeholder="SF"
                onChange={(e) => setRuleCarrier(e.target.value)}
              />
            </label>
            <label className="text-sm text-slate-300">
              时段起（仓本地小时）
              <Input value={ruleSlotStart} onChange={(e) => setRuleSlotStart(e.target.value)} />
            </label>
            <label className="text-sm text-slate-300">
              时段止（左闭右开）
              <Input value={ruleSlotEnd} onChange={(e) => setRuleSlotEnd(e.target.value)} />
            </label>
            <label className="text-sm text-slate-300">
              订单数上限
              <Input value={ruleMaxOrders} onChange={(e) => setRuleMaxOrders(e.target.value)} />
            </label>
            <div className="sm:col-span-6">
              <Button
                disabled={actionLoading || warehouseId === '' || ruleCode === '' || ruleName === ''}
                onClick={() => void submitRule()}
              >
                新建合并规则
              </Button>
              {warehouseId === '' && (
                <span className="ml-2 text-xs text-amber-200">
                  先填仓库 id：规则必须属于一个仓。
                </span>
              )}
            </div>
          </div>
        )}
      </SectionCard>

      <SectionCard title="合并候选（现在该开哪一波）">
        {warehouseId === '' ? (
          <EmptyState
            title="先选仓库"
            description="候选按仓库计算：填上面的仓库 id 之后，这里会按合并键（承运渠道 × 库区）分组列出可合并的出库单。"
          />
        ) : loading && candidates === null ? (
          <p className="text-sm text-slate-400">正在计算候选…</p>
        ) : (candidates?.groups.length ?? 0) === 0 ? (
          <EmptyState
            title="没有可合并的出库单"
            description="需要拣货任务处于「已生成/拣货中」、出库单处于「拣货中」，且已被某条生效的合并规则认领。"
          />
        ) : (
          <div className="space-y-4">
            <p className="text-xs text-slate-400">
              共 {candidates?.totalEligible ?? 0} 张现在能并、{candidates?.totalWaiting ?? 0}{' '}
              张在等。 时段按仓本地 {candidates?.timezone} 判定。
            </p>
            {candidates?.groups.map((group) => (
              <div
                key={`${group.carrierCode}-${group.zoneId ?? 'any'}`}
                className="rounded border border-slate-800 p-3"
              >
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div>
                    <div className="text-sm text-slate-200">
                      合并键：{mergeKeyText(group.carrierCode, group.zoneCode)}
                    </div>
                    <div className="text-xs text-slate-400">
                      命中规则：{group.ruleCode ?? '（无 —— 只能手工指定成员）'} · 订单数上限：
                      {group.ruleId === null ? '—' : group.maxOrderCount}
                    </div>
                    <div className="text-xs text-slate-400">{groupVerdict(group)}</div>
                  </div>
                  {writable && (
                    <Button
                      disabled={
                        actionLoading || group.eligibleCount === 0 || group.openWaveId !== null
                      }
                      onClick={() =>
                        void buildWave({
                          carrierCode: group.carrierCode,
                          zoneId: group.zoneId,
                          ruleId: group.ruleId,
                        })
                      }
                    >
                      按这一组建波次
                    </Button>
                  )}
                </div>
                <table className="mt-2 w-full text-sm">
                  <thead className="text-left text-slate-400">
                    <tr>
                      <th className="py-1">出库单</th>
                      <th className="py-1">拣货单</th>
                      <th className="py-1">库区</th>
                      <th className="py-1">计划件数</th>
                      <th className="py-1">出库单创建时刻（仓本地小时）</th>
                      <th className="py-1">状态</th>
                    </tr>
                  </thead>
                  <tbody>
                    {group.candidates.map((c) => (
                      <tr key={c.pickTaskId} className="border-t border-slate-800">
                        <td className="py-1">{c.outboundOrderCode}</td>
                        <td className="py-1 font-mono text-xs">{c.taskCode}</td>
                        <td className="py-1 text-xs">
                          {c.zoneCode ?? '—'}
                          {c.crossZone && <span className="ml-1 text-amber-200">（跨区）</span>}
                        </td>
                        <td className="py-1">{qtyText(c.plannedQuantity)}</td>
                        <td className="py-1 text-xs">
                          {c.orderCreatedAt ? new Date(c.orderCreatedAt).toLocaleString() : '—'}
                          <span className="ml-1 text-slate-500">（{c.localHour} 时）</span>
                        </td>
                        <td className="py-1 text-xs">
                          {c.blocked ? (
                            <span className="text-amber-200">{c.blockedReason}</span>
                          ) : c.matchedRuleId === null ? (
                            <span className="text-slate-500">没有规则认领</span>
                          ) : (
                            <span className="text-emerald-200">可并入</span>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ))}
          </div>
        )}
      </SectionCard>

      <SectionCard title="波次台账">
        {loading && rows.length === 0 ? (
          <p className="text-sm text-slate-400">正在加载波次…</p>
        ) : rows.length === 0 ? (
          <EmptyState
            title="还没有波次"
            description="波次由「候选」里的某一组建出来，或者由现场手工指定成员。合并只影响拣货效率，不改变订单粒度。"
          />
        ) : (
          <div className="space-y-3">
            <table className="w-full text-sm">
              <thead className="text-left text-slate-400">
                <tr>
                  <th className="py-2">波次号</th>
                  <th className="py-2">合并键</th>
                  <th className="py-2">规则</th>
                  <th className="py-2">状态</th>
                  <th className="py-2">出库单数</th>
                  <th className="py-2">进度</th>
                  <th className="py-2">计划拣货</th>
                  <th className="py-2" />
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.id} className="border-t border-slate-800">
                    <td className="py-2 font-mono text-xs">{row.waveCode}</td>
                    <td className="py-2 text-xs">{mergeKeyText(row.carrierCode, row.zoneCode)}</td>
                    <td className="py-2 text-xs">{row.ruleCode ?? '手工指定'}</td>
                    <td className="py-2">
                      <span
                        className={
                          statusTone(row.status) === 'success'
                            ? 'rounded bg-emerald-900/40 px-2 py-0.5 text-xs text-emerald-200'
                            : statusTone(row.status) === 'danger'
                              ? 'rounded bg-rose-900/40 px-2 py-0.5 text-xs text-rose-200'
                              : statusTone(row.status) === 'info'
                                ? 'rounded bg-indigo-900/40 px-2 py-0.5 text-xs text-indigo-200'
                                : 'rounded bg-slate-800 px-2 py-0.5 text-xs text-slate-300'
                        }
                      >
                        {statusLabel(row.status)}
                      </span>
                    </td>
                    <td className="py-2">
                      {row.progress.orderCount}
                      <span className="text-xs text-slate-500">
                        （{row.progress.terminalTaskCount}/{row.progress.taskCount} 终态）
                      </span>
                    </td>
                    <td className="py-2">
                      {progressText(row.progress.progress)}
                      <span className="ml-1 text-xs text-slate-400">{row.message}</span>
                    </td>
                    <td className="py-2 text-xs">{new Date(row.plannedPickAt).toLocaleString()}</td>
                    <td className="py-2 text-right">
                      <Button onClick={() => void openWave(row.id)}>详情</Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="flex items-center justify-between text-sm text-slate-400">
              <span>共 {list?.total ?? 0} 个波次（汇总不受筛选影响）</span>
              <div className="flex gap-2">
                <Button disabled={page === 0} onClick={() => setPage(page - 1)}>
                  上一页
                </Button>
                <Button disabled={(list?.items.length ?? 0) < 50} onClick={() => setPage(page + 1)}>
                  下一页
                </Button>
              </div>
            </div>
          </div>
        )}
      </SectionCard>

      {wave !== undefined && (
        <SectionCard title={`波次详情 ${wave.waveCode}`}>
          <div className="space-y-4">
            <p className="text-sm text-slate-300">{waveHint(wave.status)}</p>
            <p className="text-xs text-slate-400">合并口径：{wave.mergeBasis}</p>

            <div>
              <div className="text-sm text-slate-200">
                成员工单（{members.length} 个：一行一张出库单的一张拣货任务）
              </div>
              {members.length === 0 ? (
                <p className="mt-1 text-sm text-amber-200">
                  这个波次没有成员：既不能放行（没人可走）也不能完成（后端守卫会拒）。
                </p>
              ) : (
                <table className="mt-2 w-full text-sm">
                  <thead className="text-left text-slate-400">
                    <tr>
                      <th className="py-1">位置</th>
                      <th className="py-1">出库单</th>
                      <th className="py-1">出库单状态</th>
                      <th className="py-1">拣货单</th>
                      <th className="py-1">拣货状态</th>
                      <th className="py-1">进度</th>
                      <th className="py-1" />
                    </tr>
                  </thead>
                  <tbody>
                    {members.map((m) => (
                      <tr key={m.pickTaskId} className="border-t border-slate-800">
                        <td className="py-1">{m.position}</td>
                        <td className="py-1">{m.outboundOrderCode}</td>
                        <td className="py-1 text-xs">{m.outboundOrderStatus}</td>
                        <td className="py-1 font-mono text-xs">{m.taskCode}</td>
                        <td className="py-1 text-xs">
                          {m.taskStatusLabel}
                          {m.taskStatus === 'CANCELLED' && (
                            <span className="ml-1 text-rose-200">（不拣了，但仍按它计成员）</span>
                          )}
                        </td>
                        <td className="py-1">
                          {progressText(m.taskProgress)}
                          <span className="ml-1 text-xs text-slate-400">
                            {m.pickedLineCount}/{m.plannedLineCount} 段
                          </span>
                        </td>
                        <td className="py-1 text-right">
                          {writable && canDetach(wave.status, members.length) && (
                            <Button
                              disabled={actionLoading}
                              onClick={() =>
                                void run(
                                  () => detachPickWaveTask(wave.id, m.pickTaskId),
                                  '已移出该出库单：它被放回候选池，位置已重排为连续序号。',
                                )
                              }
                            >
                              移出
                            </Button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>

            {writable && (
              <div className="flex flex-wrap items-end gap-3">
                {canRelease(wave.status) && (
                  <>
                    <label className="text-sm text-slate-300">
                      放行给（作业人员 / PDA，<b>必填</b>）
                      <Input
                        value={assignee}
                        placeholder="作业人员 user id"
                        onChange={(e) => setAssignee(e.target.value)}
                      />
                    </label>
                    <Button
                      disabled={actionLoading || assignee === ''}
                      onClick={() =>
                        void run(
                          () => releasePickWave(wave.id, { assigneeUserId: assignee, note: null }),
                          '已放行：这一波开始按位置拣货。',
                        )
                      }
                    >
                      放行开波
                    </Button>
                  </>
                )}
                <Button
                  disabled={
                    actionLoading ||
                    !canComplete(
                      wave.status,
                      wave.progress.terminalTaskCount,
                      wave.progress.taskCount,
                    )
                  }
                  onClick={() =>
                    void run(
                      () => completePickWave(wave.id, { note: null }),
                      '波次已完成：出库单各自去复核与打包。',
                    )
                  }
                >
                  完成波次
                </Button>
                {canCancel(wave.status) && (
                  <>
                    <label className="text-sm text-slate-300">
                      取消原因（<b>必填</b>）
                      <Input
                        value={cancelReason}
                        onChange={(e) => setCancelReason(e.target.value)}
                      />
                    </label>
                    <Button
                      disabled={actionLoading || cancelReason === ''}
                      onClick={() =>
                        void run(
                          () => cancelPickWave(wave.id, { reason: cancelReason }),
                          '已取消：成员放回候选池，货仍在原库位、预占不变。',
                        )
                      }
                    >
                      取消波次
                    </Button>
                  </>
                )}
              </div>
            )}
            {!writable && (
              <p className="text-xs text-amber-200">
                当前身份只能查看：放行 / 移出 / 完成 /
                取消需要出入库作业写入权限（warehouse.inventory.update）。
              </p>
            )}

            {detail !== null && detail.report !== undefined && (
              <div className="text-xs text-slate-400">
                <div>{detail.report.dimensionSummary}</div>
                <div className="mt-1">{detail.report.orderGranularityNote}</div>
              </div>
            )}

            <div>
              <div className="text-sm text-slate-200">波次流水（append-only）</div>
              <ul className="mt-1 space-y-1 text-xs text-slate-400">
                {(detail?.events ?? []).map((e) => (
                  <li key={e.id}>
                    <span className="text-slate-300">{eventTypeLabel(e.eventType)}</span> ·{' '}
                    {new Date(e.occurredAt).toLocaleString()} · {e.detail}
                  </li>
                ))}
              </ul>
            </div>
          </div>
        </SectionCard>
      )}
    </div>
  );
}
