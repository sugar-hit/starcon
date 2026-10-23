'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button } from '@starcon/ui';
import { RefreshCw } from 'lucide-react';
import type {
  ApprovalTodoBucket,
  ApprovalTodoCapabilities,
  ApprovalTodoPage,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '@/lib/api';
import {
  APPROVAL_DETAIL_ROUTE,
  DWELL_PRESETS,
  EMPTY_FILTERS,
  SORT_LABELS,
  actionTarget,
  availableActions,
  blockedActions,
  bucketCount,
  buildQuery,
  formatAmount,
  formatDwell,
  formatThreshold,
  nodeLabel,
  timeoutLabel,
  timeoutState,
  type ApprovalFilters,
} from './approval-todo-model';

/**
 * 1.1.1.3 审批待办（卷 01 · L3 1.1.1 待办与待决策的第四格）。
 *
 * <p>蓝图原文：「待我审批的单据统一入口，支持按单据类型、业务域、金额阈值、超时时长筛选；
 * 展示审批链路当前节点与已耗时长。」
 *
 * <p>🔴 **本页面不执行审批动作**。卷 01 的边界写死：「工作台只读 + 跳转；
 * 一切状态变更回到归属系统完成」，蓝图的 1.1.1.3 也写死「工作台不执行审批动作，
 * 避免两处审批逻辑不一致」。所以「通过 / 驳回 / 转交」三个动作在能力声明里
 * {@code available=false}，页面上它们被渲染成**带缺口的说明**，
 * 而不是第二个能改单子的按钮；清单上的处理入口只有一条 —— 跳到 17 的审批详情页。
 *
 * <p>页面分五块：
 *
 * <ul>
 *   <li><b>能力声明</b>——哪些筛选维度真接通、哪一维**判不了**（法人级金额阈值）、
 *       审批动作归谁。</li>
 *   <li><b>概览</b>——待办总数 / 当前筛选命中数 / 最久停留 / 已超时 / 判不了超时。</li>
 *   <li><b>分档</b>——单据类型 / 业务域 / 币种 / 金额阈值分支，点一下就按这一维筛。</li>
 *   <li><b>筛选</b>——金额区间 / 停留时长 / 只看已超时 / 排序。</li>
 *   <li><b>清单</b>——单据头信息、金额、阈值档、申请人、当前节点、已耗时长。</li>
 * </ul>
 *
 * <p>「读不到」与「没有数据」是三件不同的事：加载中、后端读不到（403/503）、以及
 * 真的没有待审批的单据。混成一句话，审批人就会以为「审批流没配好」而去找错方向。
 */

/** 清单每页行数（后端上限 200）。 */
const PAGE_SIZE = 50;

export function ApprovalTodoWorkspace() {
  const [ready, setReady] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [data, setData] = useState<ApprovalTodoPage | null>(null);

  const [documentTypeCode, setDocumentTypeCode] = useState('');
  const [sourceSystem, setSourceSystem] = useState('');
  const [currencyCode, setCurrencyCode] = useState('');
  const [amountBranchName, setAmountBranchName] = useState('');
  const [minAmount, setMinAmount] = useState('');
  const [maxAmount, setMaxAmount] = useState('');
  const [dwell, setDwell] = useState('ALL');
  const [overdueOnly, setOverdueOnly] = useState(false);
  const [sort, setSort] = useState('WAITING');
  const [page, setPage] = useState(0);
  const [copied, setCopied] = useState('');

  const filters: ApprovalFilters = useMemo(
    () => ({
      ...EMPTY_FILTERS,
      documentTypeCode,
      sourceSystem,
      currencyCode,
      amountBranchName,
      minAmount,
      maxAmount,
      minDwellSeconds: String((DWELL_PRESETS.find((p) => p.code === dwell)?.minHours ?? 0) * 3600),
      overdueOnly,
      sort,
    }),
    [
      amountBranchName,
      currencyCode,
      documentTypeCode,
      dwell,
      maxAmount,
      minAmount,
      overdueOnly,
      sort,
      sourceSystem,
    ],
  );

  const load = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const query = new URLSearchParams(buildQuery(filters));
      query.set('page', String(page));
      query.set('size', String(PAGE_SIZE));
      setData(await api<ApprovalTodoPage>(`identity/approval-todos?${query.toString()}`));
    } catch (e) {
      setData(null);
      setError(e instanceof Error ? e.message : '读取失败，请重试');
    } finally {
      setBusy(false);
      setReady(true);
    }
  }, [filters, page]);

  useEffect(() => {
    void load();
  }, [load]);

  const capabilities: ApprovalTodoCapabilities | null = data?.capabilities ?? null;
  const summary = data?.summary;
  const items = data?.items ?? [];
  const total = data?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const current = Math.min(page, pageCount - 1);

  /**
   * 超时筛选**能不能用**，由后端的能力声明说了算。
   *
   * <p>本租户没配超时升级规则时，「多久算超时」根本判不出来 ——
   * 这时把「只看已超时」渲染成一个能点的开关，用户点了只会得到一个空列表，
   * 于是以为「没有超时的单子」。所以这里直接禁用并写明缺什么。
   */
  const overdueCapability = capabilities?.filters?.find((f) => f.key === 'OVERDUE_DURATION');
  const overdueUsable = overdueCapability?.available ?? false;

  function resetPage() {
    setPage(0);
  }

  async function copyInstanceNo(instanceNo: string) {
    if (typeof navigator === 'undefined' || !navigator.clipboard) return;
    await navigator.clipboard.writeText(instanceNo);
    setCopied(instanceNo);
  }

  if (!ready)
    return (
      <p role="status" className="text-muted-foreground p-6 text-sm">
        正在加载审批待办…
      </p>
    );

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-copy-13 text-muted-foreground">
          审批待办是卷 01 的<b>只读投影</b>：它读的是 17 组织权限的
          <b> approval_instance 投影</b>，不产生新的业务事实。
          <b>审批动作一律回到 17 完成</b>（工作台不写业务数据，避免两处审批逻辑不一致）。
        </p>
        <Button variant="ghost" size="sm" onClick={() => void load()} disabled={busy}>
          <RefreshCw className="h-4 w-4" />
          刷新
        </Button>
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

      {/* ---------------- 能力声明 ---------------- */}
      {capabilities && (
        <section className="space-y-2">
          <h2 className="text-copy-15 font-medium">能力声明</h2>
          <p className="text-copy-12 text-muted-foreground">
            数据来源 {capabilities.dataSource} · 写入归属 {capabilities.ownerModule} ·
            本模块 {capabilities.module}
          </p>
          <ul className="space-y-1">
            {capabilities.filters.map((filter) => (
              <li
                key={filter.key}
                className="text-copy-12 text-muted-foreground rounded-md border p-2"
              >
                <Badge tone={filter.available ? 'success' : 'warning'}>
                  {filter.available ? '已接通' : '判不了'}
                </Badge>
                <span className="ml-2">
                  {filter.label} · 依据 {filter.source}
                </span>
                {filter.reason && <span className="ml-2">（{filter.reason}）</span>}
              </li>
            ))}
          </ul>
          <ul className="space-y-1">
            {availableActions(capabilities).map((action) => {
              const target = actionTarget(action);
              return (
                <li key={action.code} className="text-copy-12 text-muted-foreground">
                  <Badge tone="success">可用</Badge>
                  <span className="ml-2">
                    {action.label} · 归属 {action.ownerModule}
                  </span>
                  {target && (
                    <a href={target} className="text-brand ml-2 underline-offset-4 hover:underline">
                      {target}
                    </a>
                  )}
                </li>
              );
            })}
            {blockedActions(capabilities).map((action) => (
              <li
                key={action.code}
                className="border-warning/40 bg-warning/5 rounded-md border p-2 text-copy-12"
              >
                <Badge tone="warning">不可用</Badge>
                <span className="ml-2">
                  {action.label} · 归属 {action.ownerModule}
                </span>
                {action.reason && <span className="ml-2">（{action.reason}）</span>}
              </li>
            ))}
          </ul>
        </section>
      )}

      {/* ---------------- 概览 ---------------- */}
      {summary && (
        <section className="space-y-2">
          <h2 className="text-copy-15 font-medium">审批概览</h2>
          <div className="flex flex-wrap gap-2">
            <span className="rounded-md border px-3 py-2 text-copy-12">
              待我审批 <b className="ml-1">{summary.pendingTotal}</b> 条
            </span>
            <span className="rounded-md border px-3 py-2 text-copy-12">
              当前筛选命中 <b className="ml-1">{summary.filteredTotal}</b> 条
            </span>
            <span className="rounded-md border px-3 py-2 text-copy-12">
              最久停留 <b className="ml-1">{formatDwell(summary.longestDwellSeconds)}</b>
            </span>
            <span className="rounded-md border px-3 py-2 text-copy-12">
              已超时 <b className="ml-1">{summary.overdueTotal}</b> 条
            </span>
            <span className="border-warning/40 bg-warning/5 rounded-md border px-3 py-2 text-copy-12">
              判不了超时 <b className="ml-1">{summary.unconfiguredTimeoutTotal}</b> 条
              <span className="text-muted-foreground ml-2">
                （这些单据的节点没配时限，既不算超时也不算没超时）
              </span>
            </span>
          </div>
        </section>
      )}

      {/* ---------------- 分档（点一下就按这一维筛） ---------------- */}
      <section className="space-y-2">
        <h2 className="text-copy-15 font-medium">按维度筛</h2>
        <BucketGroup
          title="单据类型"
          buckets={data?.documentTypes ?? []}
          active={documentTypeCode}
          onPick={(code) => {
            setDocumentTypeCode(code);
            resetPage();
          }}
        />
        <BucketGroup
          title="业务域"
          buckets={data?.businessDomains ?? []}
          active={sourceSystem}
          onPick={(code) => {
            setSourceSystem(code);
            resetPage();
          }}
        />
        <BucketGroup
          title="币种"
          buckets={data?.currencies ?? []}
          active={currencyCode}
          onPick={(code) => {
            setCurrencyCode(code);
            resetPage();
          }}
        />
        <BucketGroup
          title="金额阈值分支"
          buckets={data?.amountBranches ?? []}
          active={amountBranchName}
          onPick={(code) => {
            setAmountBranchName(code);
            resetPage();
          }}
          emptyHint="本租户没有配金额阈值分支（流程上未配金额区间），这一维暂时没有可选项。"
        />
      </section>

      {/* ---------------- 筛选 ---------------- */}
      <section className="flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1 text-copy-12">
          <span className="text-muted-foreground">金额下限</span>
          <input
            className="border rounded px-2 py-1 text-copy-12"
            value={minAmount}
            inputMode="decimal"
            onChange={(e) => {
              setMinAmount(e.target.value);
              resetPage();
            }}
            placeholder="不限"
          />
        </label>
        <label className="flex flex-col gap-1 text-copy-12">
          <span className="text-muted-foreground">金额上限</span>
          <input
            className="border rounded px-2 py-1 text-copy-12"
            value={maxAmount}
            inputMode="decimal"
            onChange={(e) => {
              setMaxAmount(e.target.value);
              resetPage();
            }}
            placeholder="不限"
          />
        </label>
        <label className="flex flex-col gap-1 text-copy-12">
          <span className="text-muted-foreground">停留时长</span>
          <select
            className="border rounded px-2 py-1 text-copy-12"
            value={dwell}
            onChange={(e) => {
              setDwell(e.target.value);
              resetPage();
            }}
          >
            {DWELL_PRESETS.map((preset) => (
              <option key={preset.code} value={preset.code}>
                {preset.label}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-copy-12">
          <span className="text-muted-foreground">只看已超时</span>
          <span className="flex items-center gap-1">
            <input
              type="checkbox"
              checked={overdueOnly}
              disabled={!overdueUsable}
              onChange={(e) => {
                setOverdueOnly(e.target.checked);
                resetPage();
              }}
            />
            {!overdueUsable && (
              <span className="text-muted-foreground">
                {overdueCapability?.reason ?? '本租户尚未配置超时升级规则 ⇒ 判不了超时'}
              </span>
            )}
          </span>
        </label>
        <label className="flex flex-col gap-1 text-copy-12">
          <span className="text-muted-foreground">排序</span>
          <select
            className="border rounded px-2 py-1 text-copy-12"
            value={sort}
            onChange={(e) => {
              setSort(e.target.value);
              resetPage();
            }}
          >
            {Object.entries(SORT_LABELS).map(([code, label]) => (
              <option key={code} value={code}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <Button
          variant="outline"
          size="sm"
          onClick={() => {
            setDocumentTypeCode('');
            setSourceSystem('');
            setCurrencyCode('');
            setAmountBranchName('');
            setMinAmount('');
            setMaxAmount('');
            setDwell('ALL');
            setOverdueOnly(false);
            setSort('WAITING');
            resetPage();
          }}
        >
          重置筛选
        </Button>
      </section>

      {/* ---------------- 待审批清单 ---------------- */}
      <section className="space-y-2">
        <h2 className="text-copy-15 font-medium">待审批清单</h2>
        {items.length === 0 ? (
          <div className="bg-card rounded-lg border p-8 text-center">
            <p className="text-copy-13 text-muted-foreground">
              当前筛选下**没有**待审批的单据。
              这既可能说明确实没有轮到你审，也可能说明审批流还没配节点、
              或者审批人规则还没落到你身上 —— 能力声明里写明了每一维的依据。
            </p>
          </div>
        ) : (
          <>
            <div className="overflow-x-auto">
              <table className="w-full text-copy-12">
                <thead className="text-muted-foreground text-left">
                  <tr>
                    <th className="p-2">单据号</th>
                    <th className="p-2">单据类型 / 流程</th>
                    <th className="p-2">标题</th>
                    <th className="p-2">金额 / 阈值档</th>
                    <th className="p-2">申请人</th>
                    <th className="p-2">当前节点</th>
                    <th className="p-2">停留时长</th>
                    <th className="p-2">超时</th>
                    <th className="p-2">处理</th>
                  </tr>
                </thead>
                <tbody>
                  {items.map((item) => {
                    const state = timeoutState(item);
                    return (
                      <tr key={item.id} className="border-t">
                        <td className="p-2">
                          <div className="font-mono">{item.instanceNo}</div>
                          <div className="text-muted-foreground">
                            {item.sourceSystem} · {item.sourceReferenceId}
                          </div>
                        </td>
                        <td className="p-2">
                          <div>{item.documentTypeName}</div>
                          <div className="text-muted-foreground">{item.flowName}</div>
                        </td>
                        <td className="p-2">{item.subjectTitle}</td>
                        <td className="p-2">
                          <div>{formatAmount(item.currencyCode, item.amount)}</div>
                          <div className="text-muted-foreground">
                            {formatThreshold(item) ?? '未落在任何金额阈值分支'}
                          </div>
                        </td>
                        <td className="p-2">
                          {item.applicantName ?? (
                            <span className="text-muted-foreground">
                              {item.applicantId.slice(0, 8)}（读不到姓名）
                            </span>
                          )}
                        </td>
                        <td className="p-2">{nodeLabel(item)}</td>
                        <td className="p-2">
                          <div>当前节点 {formatDwell(item.dwellSeconds)}</div>
                          <div className="text-muted-foreground">
                            提交至今 {formatDwell(item.totalSeconds)}
                          </div>
                        </td>
                        <td className="p-2">
                          <Badge
                            tone={
                              state === 'OVERDUE' ? 'danger' : state === 'WITHIN' ? 'neutral' : 'warning'
                            }
                          >
                            {timeoutLabel(item)}
                          </Badge>
                        </td>
                        <td className="p-2">
                          <div className="flex flex-wrap gap-2">
                            <a
                              href={APPROVAL_DETAIL_ROUTE}
                              className="text-brand underline-offset-4 hover:underline"
                            >
                              去审批
                            </a>
                            <Button
                              variant="link"
                              size="sm"
                              onClick={() => void copyInstanceNo(item.instanceNo)}
                            >
                              复制单号
                            </Button>
                          </div>
                          {copied === item.instanceNo && (
                            <span className="text-muted-foreground">已复制 {item.instanceNo}</span>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            <div className="flex items-center justify-between text-copy-12">
              <span className="text-muted-foreground">
                第 {current + 1} / {pageCount} 页 · 共 {total} 条待审批
              </span>
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={current <= 0}
                  onClick={() => setPage(current - 1)}
                >
                  上一页
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={current >= pageCount - 1}
                  onClick={() => setPage(current + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
          </>
        )}
      </section>
    </div>
  );
}

/**
 * 一个维度的分档按钮组。
 *
 * <p>计数是**整集合**的计数（后端算的），点一下就把这一维筛成这一档；
 * 再点一下取消。分档为空时给出说明，而不是渲染一个空荡荡的按钮组 ——
 * 空按钮组和「这一维没有可选项」是两件事。
 */
function BucketGroup({
  title,
  buckets,
  active,
  onPick,
  emptyHint,
}: {
  title: string;
  buckets: readonly ApprovalTodoBucket[];
  active: string;
  onPick: (code: string) => void;
  emptyHint?: string;
}) {
  return (
    <div className="space-y-1">
      <p className="text-copy-12 text-muted-foreground">{title}</p>
      {buckets.length === 0 ? (
        <p className="text-copy-12 text-muted-foreground">
          {emptyHint ?? '这一维暂时没有可选项。'}
        </p>
      ) : (
        <div className="flex flex-wrap gap-2">
          {buckets.map((bucket) => (
            <button
              key={bucket.code}
              type="button"
              onClick={() => onPick(active === bucket.code ? '' : bucket.code)}
              className={`rounded-md border px-3 py-2 text-left text-copy-12 ${
                active === bucket.code ? 'border-primary bg-primary/5' : ''
              }`}
            >
              <span className="text-muted-foreground">{bucket.name}</span>
              <span className="ml-2 font-medium">{bucketCount(buckets, bucket.code)} 条</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
