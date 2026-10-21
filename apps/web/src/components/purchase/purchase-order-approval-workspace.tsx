'use client';

import { useCallback, useEffect, useState } from 'react';
import { CheckCheck, ClipboardCheck, RefreshCw, Scale, Users } from 'lucide-react';
import {
  Badge,
  Button,
  EmptyState,
  FormField,
  Input,
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
  Notice,
  PageHeader,
  SectionCard,
  Segmented,
  StatCard,
} from '@starcon/ui';
import type {
  PurchaseOrderApprovalBatchResult,
  PurchaseOrderApprovalCapabilities,
  PurchaseOrderApprovalDetail,
  PurchaseOrderApprovalMembers,
  PurchaseOrderApprovalPage,
  PurchaseOrderApprovalRuleList,
} from '../../../../../packages/api-contracts/src/index';
import {
  actionHint,
  approvePurchaseOrder,
  batchPurchaseOrderApprovals,
  approvalCaseStatusLabel,
  approvalCaseStatusTone,
  approvalErrorText,
  approvalProgressText,
  approvalTierLabel,
  approverBandText,
  batchApprovalResultText,
  buyerLevelLabel,
  fetchPurchaseOrderApprovalCapabilities,
  fetchPurchaseOrderApprovalDetail,
  fetchPurchaseOrderApprovalMembers,
  fetchPurchaseOrderApprovalRules,
  fetchPurchaseOrderApprovals,
  fmtAmount,
  rejectPurchaseOrder,
  fmtDateTime,
  payableStatusLabel,
  releaseStatusLabel,
  ruleBandText,
  ruleMatchLabel,
  type PurchaseOrderApprovalParams,
} from './purchase-order-approval-model';

/**
 * 6.3.1.3 采购单审批工作区。
 *
 * <p>蓝图：「按金额阈值与采购员分级审批，支持批量审批、驳回并附意见，
 * 审批通过后锁定单据生成应付。」
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>「不能批」要说清为什么</b>：每一行都带原因（层级不够 / 金额超授权 /
 *       不能自审 / 已经批过）—— 只给一个灰按钮会让人以为系统坏了。</li>
 *   <li><b>「还没登记应付」不是 0</b>：应付登记与收货放行用状态表达，
 *       金额显示「无数据」，不显示 0。</li>
 *   <li><b>待审金额按币种分列</b>：界面不出现一个跨币种的合计数。</li>
 *   <li><b>审批链路逐条回放</b>：谁、什么层级、哪条规则、批当时的金额、意见是什么。</li>
 *   <li><b>批量逐条给结论</b>：失败的那些张要列出失败原因，不静默丢弃。</li>
 *   <li><b>未交付的下游说清</b>：12 财务 / 08 仓储 / 17 组织各自的状态写在能力边界区。</li>
 *   <li>加载 / 空 / 错误三态齐全，且三态文案各不相同。</li>
 * </ul>
 */
export function PurchaseOrderApprovalWorkspace() {
  const [view, setView] = useState<'desk' | 'rules' | 'members'>('desk');

  // ---- 台账 ----
  const [params, setParams] = useState<PurchaseOrderApprovalParams>({ page: 0, size: 20 });
  const [data, setData] = useState<PurchaseOrderApprovalPage | null>(null);
  const [caps, setCaps] = useState<PurchaseOrderApprovalCapabilities | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // ---- 筛选 ----
  const [caseStatus, setCaseStatus] = useState('PENDING');
  const [tier, setTier] = useState('');
  const [currency, setCurrency] = useState('');
  const [keyword, setKeyword] = useState('');

  // ---- 批量 ----
  const [selected, setSelected] = useState<string[]>([]);
  const [batchOpen, setBatchOpen] = useState(false);
  const [batchAction, setBatchAction] = useState<'APPROVE' | 'REJECT'>('APPROVE');
  const [batchNote, setBatchNote] = useState('');
  const [batchResult, setBatchResult] = useState<PurchaseOrderApprovalBatchResult | null>(null);
  const [busy, setBusy] = useState(false);

  // ---- 详情 / 单张审批 ----
  const [detail, setDetail] = useState<PurchaseOrderApprovalDetail | null>(null);
  const [detailOpen, setDetailOpen] = useState(false);
  const [decideOpen, setDecideOpen] = useState(false);
  const [decideAction, setDecideAction] = useState<'APPROVE' | 'REJECT'>('APPROVE');
  const [decideNote, setDecideNote] = useState('');
  const [actionError, setActionError] = useState<string | null>(null);

  // ---- 规则 / 成员 ----
  const [rules, setRules] = useState<PurchaseOrderApprovalRuleList | null>(null);
  const [members, setMembers] = useState<PurchaseOrderApprovalMembers | null>(null);
  const [configLoading, setConfigLoading] = useState(false);
  const [configError, setConfigError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await fetchPurchaseOrderApprovals(params));
    } catch (e) {
      setError(approvalErrorText(e));
    } finally {
      setLoading(false);
    }
  }, [params]);

  const loadConfig = useCallback(async () => {
    setConfigLoading(true);
    setConfigError(null);
    try {
      setRules(await fetchPurchaseOrderApprovalRules());
      setMembers(await fetchPurchaseOrderApprovalMembers());
    } catch (e) {
      setConfigError(approvalErrorText(e));
    } finally {
      setConfigLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    fetchPurchaseOrderApprovalCapabilities()
      .then(setCaps)
      .catch(() => setCaps(null));
  }, []);

  useEffect(() => {
    if (view === 'rules' || view === 'members') void loadConfig();
  }, [view, loadConfig]);

  const openDetail = useCallback(async (id: string) => {
    setActionError(null);
    try {
      setDetail(await fetchPurchaseOrderApprovalDetail(id));
      setDetailOpen(true);
    } catch (e) {
      setActionError(approvalErrorText(e));
    }
  }, []);

  const openDecide = useCallback(
    (id: string, action: 'APPROVE' | 'REJECT') => {
      setActionError(null);
      setDecideAction(action);
      setDecideNote('');
      setDecideOpen(true);
      void openDetail(id);
    },
    [openDetail],
  );

  const submitDecide = useCallback(async () => {
    if (!detail) return;
    if (decideAction === 'REJECT' && !decideNote.trim()) {
      setActionError('驳回必须附意见（「驳回而不说为什么」等于没驳回）');
      return;
    }
    setBusy(true);
    setActionError(null);
    try {
      const next =
        decideAction === 'APPROVE'
          ? await approvePurchaseOrder(detail.orderId, decideNote)
          : await rejectPurchaseOrder(detail.orderId, decideNote);
      setDetail(next);
      setDecideOpen(false);
      await load();
    } catch (e) {
      setActionError(approvalErrorText(e));
    } finally {
      setBusy(false);
    }
  }, [detail, decideAction, decideNote, load]);

  const submitBatch = useCallback(async () => {
    if (selected.length === 0) return;
    if (batchAction === 'REJECT' && !batchNote.trim()) {
      setActionError('批量驳回必须附意见');
      return;
    }
    setBusy(true);
    setActionError(null);
    try {
      const result = await batchPurchaseOrderApprovals(selected, batchAction, batchNote);
      setBatchResult(result);
      setSelected([]);
      await load();
    } catch (e) {
      setActionError(approvalErrorText(e));
    } finally {
      setBusy(false);
    }
  }, [selected, batchAction, batchNote, load]);

  const summary = data?.summary ?? null;
  const toggle = (orderId: string) => {
    setSelected((prev) =>
      prev.includes(orderId) ? prev.filter((id) => id !== orderId) : [...prev, orderId],
    );
  };

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="采购单审批"
        description="按金额阈值与采购员分级审批，支持批量审批、驳回并附意见；通过后锁定单据并登记应付。"
        actions={
          <div className="flex items-center gap-2">
            <Button variant="outline" onClick={() => void load()} disabled={loading}>
              <RefreshCw className="size-4" /> 刷新
            </Button>
          </div>
        }
      >
        <Segmented
          value={view}
          onChange={(next) => setView(next as 'desk' | 'rules' | 'members')}
          options={[
            { label: '审批台账', value: 'desk', icon: <ClipboardCheck className="size-4" /> },
            { label: '审批规则', value: 'rules', icon: <Scale className="size-4" /> },
            { label: '审批成员', value: 'members', icon: <Users className="size-4" /> },
          ]}
        />
      </PageHeader>

      {view === 'desk' && (
        <>
          {/* 能力边界常驻：审批这一环与下游各自的状态必须一直摆在眼前 */}
          {caps && (
            <Notice tone="info" title="审批口径与下游边界">
              <ul className="list-disc space-y-1 pl-4 text-xs">
                {caps.capabilities
                  .filter((c) => !c.available)
                  .map((c) => (
                    <li key={c.code}>
                      <b>{c.label}</b>：{c.ownerModule} —— {c.reason}
                    </li>
                  ))}
              </ul>
            </Notice>
          )}

          {summary && (
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <StatCard label="待审批" value={summary.pendingCount} polarity="negative" />
              <StatCard label="已通过" value={summary.approvedCount} polarity="positive" />
              <StatCard label="已驳回" value={summary.rejectedCount} />
              {summary.pendingAmounts.length === 0 ? (
                <StatCard label="待审金额" value="无数据" comparisonLabel="当前筛选下没有待审单" />
              ) : (
                summary.pendingAmounts.map((c) => (
                  <StatCard
                    key={c.currency}
                    label={`待审金额（${c.currency}）`}
                    value={fmtAmount(c.amount)}
                    comparisonLabel={`${c.count} 张单`}
                  />
                ))
              )}
            </div>
          )}

          <SectionCard title="筛选">
            <div className="flex flex-wrap items-end gap-3">
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">审批状态</span>
                <select
                  className="h-9 rounded-md border px-2"
                  value={caseStatus}
                  onChange={(e) => {
                    setCaseStatus(e.target.value);
                    setParams((p) => ({ ...p, caseStatus: e.target.value, page: 0 }));
                  }}
                >
                  <option value="">全部</option>
                  <option value="PENDING">待审批</option>
                  <option value="APPROVED">已通过</option>
                  <option value="REJECTED">已驳回</option>
                </select>
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">要求层级</span>
                <select
                  className="h-9 rounded-md border px-2"
                  value={tier}
                  onChange={(e) => {
                    setTier(e.target.value);
                    setParams((p) => ({ ...p, tier: e.target.value, page: 0 }));
                  }}
                >
                  <option value="">全部</option>
                  <option value="L1">L1 采购主管</option>
                  <option value="L2">L2 采购经理</option>
                  <option value="L3">L3 采购总监</option>
                </select>
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">币种</span>
                <Input
                  value={currency}
                  placeholder="USD"
                  onChange={(e) => {
                    setCurrency(e.target.value);
                    setParams((p) => ({ ...p, currency: e.target.value, page: 0 }));
                  }}
                  className="w-28"
                />
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">关键字</span>
                <Input
                  value={keyword}
                  placeholder="单号 / 供应商编码或名称"
                  onChange={(e) => {
                    setKeyword(e.target.value);
                    setParams((p) => ({ ...p, keyword: e.target.value, page: 0 }));
                  }}
                  className="w-56"
                />
              </label>
            </div>
          </SectionCard>

          {selected.length > 0 && (
            <div className="flex flex-wrap items-center gap-2 rounded-md border border-sky-500/40 bg-sky-500/10 p-3 text-sm">
              <CheckCheck className="size-4" />
              <span>已选中 {selected.length} 张</span>
              <Button
                size="sm"
                onClick={() => {
                  setBatchAction('APPROVE');
                  setBatchNote('');
                  setBatchResult(null);
                  setBatchOpen(true);
                }}
              >
                批量审批
              </Button>
              <Button
                size="sm"
                variant="outline"
                onClick={() => {
                  setBatchAction('REJECT');
                  setBatchNote('');
                  setBatchResult(null);
                  setBatchOpen(true);
                }}
              >
                批量驳回
              </Button>
              <Button size="sm" variant="ghost" onClick={() => setSelected([])}>
                清空选择
              </Button>
            </div>
          )}

          {error && (
            <Notice tone="danger" title="加载失败">
              {error}
            </Notice>
          )}

          {loading && !data ? (
            <p className="text-muted-foreground text-sm">正在加载审批台账……</p>
          ) : !data || data.items.length === 0 ? (
            <EmptyState
              icon={<ClipboardCheck className="size-6" />}
              title={caseStatus === 'PENDING' ? '没有待审批的采购单' : '没有符合条件的采购单'}
              description={
                caseStatus === 'PENDING'
                  ? '采购单被 6.3.1.2 提交后会出现在这里；没有待办说明这一档没有积压。'
                  : '换个筛选条件再看看。'
              }
            />
          ) : (
            <SectionCard title={`审批台账（${data.total} 张）`}>
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="text-muted-foreground text-left text-xs">
                    <tr>
                      <th className="p-2">选择</th>
                      <th className="p-2">采购单</th>
                      <th className="p-2">供应商</th>
                      <th className="p-2">金额</th>
                      <th className="p-2">采购员</th>
                      <th className="p-2">要求层级</th>
                      <th className="p-2">进度</th>
                      <th className="p-2">状态</th>
                      <th className="p-2">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.items.map((row) => (
                      <tr key={row.orderId} className="border-t">
                        <td className="p-2">
                          {row.caseStatus === 'PENDING' && (
                            <input
                              type="checkbox"
                              checked={selected.includes(row.orderId)}
                              onChange={() => toggle(row.orderId)}
                              aria-label={`选择 ${row.orderNo}`}
                            />
                          )}
                        </td>
                        <td className="p-2">
                          <button className="underline" onClick={() => void openDetail(row.orderId)}>
                            {row.orderNo}
                          </button>
                        </td>
                        <td className="p-2">
                          {row.supplierCode} · {row.supplierName}
                        </td>
                        <td className="p-2 whitespace-nowrap">
                          {fmtAmount(row.orderAmount)} {row.currency}
                        </td>
                        <td className="p-2">
                          {row.buyerName || '未登记'} · {buyerLevelLabel(row.buyerLevel)}
                        </td>
                        <td className="p-2">{approvalTierLabel(row.requiredTier)}</td>
                        <td className="p-2">
                          {approvalProgressText(row.approvedCount, row.requiredApprovals)}
                        </td>
                        <td className="p-2">
                          <Badge tone={approvalCaseStatusTone(row.caseStatus)}>
                            {approvalCaseStatusLabel(row.caseStatus)}
                          </Badge>
                        </td>
                        <td className="p-2">
                          {row.canApprove ? (
                            <div className="flex gap-2">
                              <Button size="sm" onClick={() => openDecide(row.orderId, 'APPROVE')}>
                                审批
                              </Button>
                              <Button
                                size="sm"
                                variant="outline"
                                onClick={() => openDecide(row.orderId, 'REJECT')}
                              >
                                驳回
                              </Button>
                            </div>
                          ) : (
                            <span className="text-muted-foreground text-xs">
                              {actionHint({
                                caseStatus: row.caseStatus,
                                canApprove: row.canApprove,
                                blockedReason: row.blockedReason,
                              })}
                            </span>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <div className="mt-3 flex items-center gap-2 text-sm">
                <Button
                  size="sm"
                  variant="outline"
                  disabled={params.page === 0}
                  onClick={() => setParams((p) => ({ ...p, page: Math.max((p.page ?? 0) - 1, 0) }))}
                >
                  上一页
                </Button>
                <span className="text-muted-foreground text-xs">
                  第 {(params.page ?? 0) + 1} 页 / 共 {data.total} 张
                </span>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={(params.page ?? 0) + 1 >= Math.ceil(data.total / data.size)}
                  onClick={() => setParams((p) => ({ ...p, page: (p.page ?? 0) + 1 }))}
                >
                  下一页
                </Button>
              </div>
            </SectionCard>
          )}
        </>
      )}

      {view === 'rules' && (
        <SectionCard title="审批规则" description="（采购员分级, 币种, 金额区间）⇒ 要求的审批层级与人数">
          {configError && (
            <Notice tone="danger" title="加载失败">
              {configError}
            </Notice>
          )}
          {configLoading && !rules ? (
            <p className="text-muted-foreground text-sm">正在加载审批规则……</p>
          ) : !rules || rules.items.length === 0 ? (
            <EmptyState
              icon={<Scale className="size-6" />}
              title="还没有审批规则"
              description="一条规则都没配时，采购单会走默认档（最低层级、1 人）—— 审批这一环不会被跳过。"
            />
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="text-muted-foreground text-left text-xs">
                  <tr>
                    <th className="p-2">规则号</th>
                    <th className="p-2">名称</th>
                    <th className="p-2">适用范围</th>
                    <th className="p-2">要求层级</th>
                    <th className="p-2">人数</th>
                    <th className="p-2">状态</th>
                  </tr>
                </thead>
                <tbody>
                  {rules.items.map((rule) => (
                    <tr key={rule.id} className="border-t">
                      <td className="p-2">{rule.ruleNo}</td>
                      <td className="p-2">{rule.ruleName}</td>
                      <td className="p-2 whitespace-nowrap">{ruleBandText(rule)}</td>
                      <td className="p-2">{approvalTierLabel(rule.requiredTier)}</td>
                      <td className="p-2">{rule.requiredApprovals} 人</td>
                      <td className="p-2">
                        <Badge tone={rule.status === 'ACTIVE' ? 'success' : 'neutral'}>
                          {rule.status === 'ACTIVE' ? '启用' : '停用'}
                        </Badge>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {rules && rules.note && (
            <p className="text-muted-foreground mt-2 text-xs">{rules.note}</p>
          )}
        </SectionCard>
      )}

      {view === 'members' && (
        <>
          <SectionCard title="采购员分级" description="决定这张采购单命中哪一档审批规则">
            {configError && (
              <Notice tone="danger" title="加载失败">
                {configError}
              </Notice>
            )}
            {configLoading && !members ? (
              <p className="text-muted-foreground text-sm">正在加载采购员分级……</p>
            ) : !members || members.buyers.length === 0 ? (
              <EmptyState
                icon={<Users className="size-6" />}
                title="还没有登记采购员分级"
                description="没有分级的采购员会按「未登记分级」命中带 ANY 的规则。"
              />
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="text-muted-foreground text-left text-xs">
                    <tr>
                      <th className="p-2">成员</th>
                      <th className="p-2">分级</th>
                      <th className="p-2">状态</th>
                    </tr>
                  </thead>
                  <tbody>
                    {members.buyers.map((buyer) => (
                      <tr key={buyer.id} className="border-t">
                        <td className="p-2">{buyer.userName || buyer.userId}</td>
                        <td className="p-2">{buyerLevelLabel(buyer.buyerLevel)}</td>
                        <td className="p-2">
                          <Badge tone={buyer.status === 'ACTIVE' ? 'success' : 'neutral'}>
                            {buyer.status === 'ACTIVE' ? '启用' : '停用'}
                          </Badge>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </SectionCard>

          <SectionCard title="审批人与层级" description="层级 + 金额授权区间：两者都够才批得了这一笔">
            {!members || members.approvers.length === 0 ? (
              <EmptyState
                icon={<Users className="size-6" />}
                title="还没有在册审批人"
                description="没有在册审批人时，任何人都批不了这些单 —— 采购单会一直停在待审批，不会被当成已批。"
              />
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="text-muted-foreground text-left text-xs">
                    <tr>
                      <th className="p-2">成员</th>
                      <th className="p-2">层级</th>
                      <th className="p-2">金额授权区间</th>
                      <th className="p-2">状态</th>
                    </tr>
                  </thead>
                  <tbody>
                    {members.approvers.map((approver) => (
                      <tr key={approver.id} className="border-t">
                        <td className="p-2">{approver.userName || approver.userId}</td>
                        <td className="p-2">{approvalTierLabel(approver.approvalTier)}</td>
                        <td className="p-2 whitespace-nowrap">{approverBandText(approver)}</td>
                        <td className="p-2">
                          <Badge tone={approver.status === 'ACTIVE' ? 'success' : 'neutral'}>
                            {approver.status === 'ACTIVE' ? '启用' : '停用'}
                          </Badge>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            {members && members.note && (
              <p className="text-muted-foreground mt-2 text-xs">{members.note}</p>
            )}
          </SectionCard>
        </>
      )}

      {/* ---- 详情 ---- */}
      <Modal open={detailOpen} onOpenChange={setDetailOpen}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>审批详情</ModalTitle>
            <ModalDescription>
              {detail ? `${detail.orderNo} · ${detail.supplierName}` : '采购单审批详情'}
            </ModalDescription>
          </ModalHeader>
          {detail && (
            <div className="flex flex-col gap-3 text-sm">
              <div className="grid gap-2 sm:grid-cols-2">
                <p>
                  <span className="text-muted-foreground">金额：</span>
                  {fmtAmount(detail.orderAmount)} {detail.currency}
                </p>
                <p>
                  <span className="text-muted-foreground">采购员：</span>
                  {detail.buyerName || '未登记'} · {buyerLevelLabel(detail.buyerLevel)}
                </p>
                <p>
                  <span className="text-muted-foreground">依据规则：</span>
                  {detail.ruleName || '（无）'} · {ruleMatchLabel(detail.ruleMatch)}
                </p>
                <p>
                  <span className="text-muted-foreground">要求：</span>
                  {approvalTierLabel(detail.requiredTier)} ·{' '}
                  {approvalProgressText(detail.approvedCount, detail.requiredApprovals)}
                </p>
              </div>

              {detail.blockedReason && (
                <Notice tone="warning" title="当前不可操作">
                  {detail.blockedReason}
                </Notice>
              )}

              <SectionCard title="审批链路">
                {detail.chain.length === 0 ? (
                  <p className="text-muted-foreground text-xs">还没有人批过这一单。</p>
                ) : (
                  <ol className="flex flex-col gap-2">
                    {detail.chain.map((step) => (
                      <li key={step.seq} className="border-l-2 pl-3 text-xs">
                        <p className="font-medium">
                          第 {step.seq} 步 · {step.action === 'APPROVED' ? '通过' : '驳回'} ·{' '}
                          {step.approverName || step.approverUserId}
                        </p>
                        <p className="text-muted-foreground">
                          {approvalTierLabel(step.approvalTier)} · 依据「{step.ruleName || '无'}」 · 金额{' '}
                          {fmtAmount(step.orderAmount)} {step.currency} · {fmtDateTime(step.occurredAt)}
                        </p>
                        {step.note && <p>意见：{step.note}</p>}
                      </li>
                    ))}
                  </ol>
                )}
              </SectionCard>

              <SectionCard title="应付登记">
                <p className="text-xs">
                  <span className="text-muted-foreground">状态：</span>
                  {payableStatusLabel(detail.payable.status)}
                </p>
                <p className="text-xs">
                  <span className="text-muted-foreground">登记金额：</span>
                  {fmtAmount(detail.payable.registeredAmount)} {detail.payable.currency}
                </p>
                <p className="text-xs">
                  <span className="text-muted-foreground">生效金额：</span>
                  {fmtAmount(detail.payable.effectiveAmount)}
                  {detail.payable.status === 'PENDING_FINANCE_CONFIRM' && (
                    <span className="text-muted-foreground">
                      （财务未确认前恒为 0 —— 12 财务服务当前未交付）
                    </span>
                  )}
                </p>
              </SectionCard>

              <SectionCard title="收货放行">
                <p className="text-xs">
                  <span className="text-muted-foreground">状态：</span>
                  {releaseStatusLabel(detail.receiptRelease.status)}
                </p>
                {detail.receiptRelease.releasedAt && (
                  <p className="text-xs">
                    <span className="text-muted-foreground">放行时刻：</span>
                    {fmtDateTime(detail.receiptRelease.releasedAt)}
                  </p>
                )}
                {detail.receiptRelease.releaseNote && (
                  <p className="text-muted-foreground text-xs">{detail.receiptRelease.releaseNote}</p>
                )}
              </SectionCard>
            </div>
          )}
          <ModalFooter>
            <Button variant="outline" onClick={() => setDetailOpen(false)}>
              关闭
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---- 单张审批 / 驳回 ---- */}
      <Modal open={decideOpen} onOpenChange={setDecideOpen}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>{decideAction === 'APPROVE' ? '审批通过' : '驳回'}</ModalTitle>
            <ModalDescription>
              {detail
                ? `${detail.orderNo} · ${fmtAmount(detail.orderAmount)} ${detail.currency}`
                : '采购单审批'}
            </ModalDescription>
          </ModalHeader>
          <div className="flex flex-col gap-3">
            {actionError && (
              <Notice tone="danger" title="操作未完成">
                {actionError}
              </Notice>
            )}
            {decideAction === 'REJECT' && (
              <Notice tone="warning" title="驳回必须说明原因">
                「驳回而不说为什么」等于没驳回 —— 意见会进审批链路记录，采购员照着改。
              </Notice>
            )}
            <FormField
              id="approval-note"
              label={decideAction === 'APPROVE' ? '审批意见（可选）' : '驳回意见'}
              required={decideAction === 'REJECT'}
              hint="意见会原样进入审批链路记录"
            >
              <Input
                id="approval-note"
                value={decideNote}
                onChange={(e) => setDecideNote(e.target.value)}
                placeholder={decideAction === 'APPROVE' ? '例如：同意，按合同价下单' : '例如：单价比报价高，退回重报'}
              />
            </FormField>
          </div>
          <ModalFooter>
            <Button variant="outline" onClick={() => setDecideOpen(false)}>
              取消
            </Button>
            <Button
              variant={decideAction === 'REJECT' ? 'outline' : 'default'}
              disabled={busy}
              onClick={() => void submitDecide()}
            >
              {decideAction === 'APPROVE' ? '确认通过' : '确认驳回'}
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---- 批量审批 ---- */}
      <Modal open={batchOpen} onOpenChange={setBatchOpen}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>{batchAction === 'APPROVE' ? '批量审批' : '批量驳回'}</ModalTitle>
            <ModalDescription>已选中 {selected.length} 张采购单 · 逐条给结论</ModalDescription>
          </ModalHeader>
          <div className="flex flex-col gap-3">
            {actionError && (
              <Notice tone="danger" title="操作未完成">
                {actionError}
              </Notice>
            )}
            {batchResult && (
              <Notice tone={batchResult.failedCount === 0 ? 'success' : 'warning'} title="批量结果">
                {batchApprovalResultText(batchResult)}
              </Notice>
            )}
            {batchAction === 'REJECT' && (
              <Notice tone="warning" title="批量驳回必须说明原因">
                意见会逐条进入每张单的审批链路记录。
              </Notice>
            )}
            <FormField
              id="batch-note"
              label={batchAction === 'APPROVE' ? '审批意见（可选）' : '驳回意见'}
              required={batchAction === 'REJECT'}
            >
              <Input
                id="batch-note"
                value={batchNote}
                onChange={(e) => setBatchNote(e.target.value)}
                placeholder={batchAction === 'APPROVE' ? '例如：本月集中采购，同意' : '例如：单价偏高，退回重报'}
              />
            </FormField>
          </div>
          <ModalFooter>
            <Button variant="outline" onClick={() => setBatchOpen(false)}>
              关闭
            </Button>
            <Button disabled={busy || selected.length === 0} onClick={() => void submitBatch()}>
              {batchAction === 'APPROVE' ? '批量通过' : '批量驳回'}
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}
