'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import {
  CircleHelp,
  Clock,
  FileWarning,
  Globe2,
  Loader2,
  RefreshCw,
  Scale,
  ShieldAlert,
  Trash2,
  TriangleAlert,
} from 'lucide-react';
import {
  CLEANUP_RESULTS,
  DATA_CLASSES,
  LEGAL_BASES,
  REQUEST_TYPES,
  RETENTION_CLASSES,
  TRIGGER_KINDS,
  actorLabel,
  approveDeletion,
  canApproveDeletion,
  canExecuteCleanup,
  canExecuteDeletion,
  cleanupStatusLabel,
  cleanupStatusTone,
  conflictSummary,
  createCleanupOrder,
  createDeletionRequest,
  createPolicy,
  createTransfer,
  dateText,
  daysText,
  deletionStatusLabel,
  deletionStatusTone,
  deferralText,
  emptyState,
  endTransfer,
  executeCleanupOrder,
  executeDeletion,
  fetchCapabilities,
  fetchCleanupOrders,
  fetchCleanupRecords,
  fetchConflicts,
  fetchDeletionRequests,
  fetchDomains,
  fetchPolicies,
  fetchTransfers,
  rejectDeletion,
  resolutionTone,
  retentionClassLabel,
  saveDomain,
  suspendTransfer,
  transferStatusLabel,
  transferStatusTone,
  unavailableState,
  unavailableUpstreams,
  type DataGovernanceCapabilities,
  type DataGovernanceDeletionPage,
  type DataGovernanceDomainPage,
  type DataGovernanceTransferPage,
  type RetentionCleanupOrder,
  type RetentionCleanupPage,
  type RetentionCleanupRecordPage,
  type RetentionConflictPage,
  type RetentionPolicyPage,
} from './data-retention-model';

/**
 * 16.6.2.1 数据出境与留存 工作区。
 *
 * 蓝图：「管理数据合规：跨境数据传输登记与合法性基础、数据留存期配置、到期清理与删除请求（被遗忘权）、
 * 保留期与财务留存期的区分。输入：数据流登记；留存策略；删除请求。
 * 输出：合规登记 + 清理/删除执行记录。关系：上游 = 17 权限、全部数据域；下游 = 数据清理任务。
 * <strong>财务数据有法定留存期，PII 有 GDPR 删除权，两者冲突时按财务留存优先</strong>——实体目录 §5 已明确这条边界。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **留存期裁决排最前**，而且**冲突对、双方期限、裁决结果与依据四件齐全** ——
 *    只显示「已裁决」，运营根本不知道依据是什么。
 * 2. **「推迟」与「驳回」是两种状态**：法定留存期没走完 ⇒ 推迟到某一天（写明推到哪天）；
 *    被拒 ⇒ 驳回。折成一个「没批」就没法做合规台账了。
 * 3. **「没登记留存策略」（判不了）与「没冲突」分开显示** —— 判不了不是默认放行。
 * 4. **未交付的上游如实标「读不到」**：12 财务（finance 服务）与下游数据清理任务
 *    都还没落地，页面写明归属模块，**不用空列表冒充**。
 * 5. **加载 / 空 / 错误 / 读不到**四种状态文案各不相同。
 */
export function DataRetentionWorkspace() {
  const [caps, setCaps] = useState<DataGovernanceCapabilities | null>(null);
  const [domains, setDomains] = useState<DataGovernanceDomainPage | null>(null);
  const [policies, setPolicies] = useState<RetentionPolicyPage | null>(null);
  const [conflicts, setConflicts] = useState<RetentionConflictPage | null>(null);
  const [transfers, setTransfers] = useState<DataGovernanceTransferPage | null>(null);
  const [deletions, setDeletions] = useState<DataGovernanceDeletionPage | null>(null);
  const [cleanupOrders, setCleanupOrders] = useState<RetentionCleanupPage | null>(null);
  const [cleanupRecords, setCleanupRecords] = useState<RetentionCleanupRecordPage | null>(null);

  const [domainFilter, setDomainFilter] = useState('');
  const [transferStatus, setTransferStatus] = useState('');
  const [deletionStatus, setDeletionStatus] = useState('');

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  // ---- 表单态 ----
  const [newDomainCode, setNewDomainCode] = useState('');
  const [newDomainName, setNewDomainName] = useState('');
  const [newDomainOwner, setNewDomainOwner] = useState('');
  const [newDomainClass, setNewDomainClass] = useState('PII');
  const [newDomainPii, setNewDomainPii] = useState(true);
  const [newDomainFinancial, setNewDomainFinancial] = useState(false);

  const [policyDomain, setPolicyDomain] = useState('');
  const [policyClass, setPolicyClass] = useState('STANDARD');
  const [policyDays, setPolicyDays] = useState('180');
  const [policyTrigger, setPolicyTrigger] = useState('CREATION');
  const [policyBasis, setPolicyBasis] = useState('');

  const [transferDomain, setTransferDomain] = useState('');
  const [transferRegion, setTransferRegion] = useState('DE');
  const [transferRecipient, setTransferRecipient] = useState('');
  const [transferBasis, setTransferBasis] = useState('CONTRACT');
  const [transferSafeguards, setTransferSafeguards] = useState('');

  const [deletionType, setDeletionType] = useState('FORGETTING_RIGHT');
  const [deletionDomain, setDeletionDomain] = useState('');
  const [deletionSubject, setDeletionSubject] = useState('');
  const [deletionReason, setDeletionReason] = useState('');

  const [cleanupDomain, setCleanupDomain] = useState('');
  const [cleanupSubject, setCleanupSubject] = useState('');
  const [cleanupTrigger, setCleanupTrigger] = useState('MANUAL');

  const PAGE_SIZE = 10;

  const loadDomains = useCallback(async (code: string) => {
    setDomains(await fetchDomains(code, 0, PAGE_SIZE));
  }, []);

  const loadPolicies = useCallback(async (code: string) => {
    setPolicies(await fetchPolicies(code, 0, PAGE_SIZE));
  }, []);

  const loadConflicts = useCallback(async (code: string) => {
    setConflicts(await fetchConflicts(code, 0, PAGE_SIZE));
  }, []);

  const loadTransfers = useCallback(async (status: string, code: string) => {
    setTransfers(await fetchTransfers(status, code, 0, PAGE_SIZE));
  }, []);

  const loadDeletions = useCallback(async (status: string, code: string) => {
    setDeletions(await fetchDeletionRequests(status, code, 0, PAGE_SIZE));
  }, []);

  const loadCleanupOrders = useCallback(async (status: string, code: string) => {
    setCleanupOrders(await fetchCleanupOrders(status, code, 0, PAGE_SIZE));
  }, []);

  const loadCleanupRecords = useCallback(async (code: string) => {
    setCleanupRecords(await fetchCleanupRecords('', code, 0, PAGE_SIZE));
  }, []);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const capsResult = await fetchCapabilities();
      setCaps(capsResult);
      await Promise.all([
        loadDomains(domainFilter),
        loadPolicies(domainFilter),
        loadConflicts(domainFilter),
        loadTransfers(transferStatus, domainFilter),
        loadDeletions(deletionStatus, domainFilter),
        loadCleanupOrders('', domainFilter),
        loadCleanupRecords(domainFilter),
      ]);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [
    domainFilter,
    transferStatus,
    deletionStatus,
    loadDomains,
    loadPolicies,
    loadConflicts,
    loadTransfers,
    loadDeletions,
    loadCleanupOrders,
    loadCleanupRecords,
  ]);

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /** 统一的动作包装：出错就把后端的人话原因显示出来，成功后重载。 */
  const run = useCallback(
    async (work: () => Promise<unknown>, message: string) => {
      setBusy(true);
      setError(null);
      setNotice(null);
      try {
        await work();
        setNotice(message);
        await refresh();
      } catch (e) {
        setError(e instanceof Error ? e.message : '操作失败，请重试');
      } finally {
        setBusy(false);
      }
    },
    [refresh],
  );

  const missingUpstreams = useMemo(() => unavailableUpstreams(caps), [caps]);

  const submitDomain = () =>
    void run(
      () =>
        saveDomain({
          domainCode: newDomainCode.trim(),
          domainName: newDomainName.trim(),
          ownerModule: newDomainOwner.trim(),
          dataClass: newDomainClass,
          containsPii: newDomainPii,
          statutoryFinancial: newDomainFinancial,
          note: '',
        }),
      '数据域已登记。',
    );

  const submitPolicy = () =>
    void run(
      () =>
        createPolicy({
          domainCode: policyDomain.trim(),
          policyName: `${policyClass} 留存策略`,
          retentionClass: policyClass,
          retentionDays: Number(policyDays),
          retentionTrigger: policyTrigger,
          legalBasis: policyBasis.trim(),
          effectiveFrom: new Date().toISOString(),
        }),
      '留存策略已登记（旧版本自动置为「已被新版本取代」）。',
    );

  const submitTransfer = () =>
    void run(
      () =>
        createTransfer({
          sourceDomainCode: transferDomain.trim(),
          targetRegion: transferRegion.trim().toUpperCase(),
          recipient: transferRecipient.trim(),
          legalBasis: transferBasis,
          safeguards: transferSafeguards.trim(),
          tiaRef: '',
          effectiveFrom: new Date().toISOString(),
          note: '',
        }),
      '跨境数据传输登记已提交。',
    );

  const submitDeletion = () =>
    void run(
      () =>
        createDeletionRequest({
          requestType: deletionType,
          subjectKind: 'ORDER',
          subjectRef: deletionSubject.trim(),
          domainCode: deletionDomain.trim(),
          reason: deletionReason.trim(),
        }),
      '删除请求已提交，等待审批。',
    );

  const submitCleanup = () =>
    void run(
      () =>
        createCleanupOrder({
          domainCode: cleanupDomain.trim(),
          subjectRef: cleanupSubject.trim(),
          triggerKind: cleanupTrigger,
          dueAt: null,
          affectedRecordCount: null,
        }),
      '清理工单已登记（最早可清理日由生效留存期算出）。',
    );

  if (loading) {
    return (
      <div className="text-copy-13 text-muted-foreground flex items-center gap-2">
        <Loader2 className="h-4 w-4 animate-spin" />
        正在加载数据出境与留存…
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <PageHeader
        title="数据出境与留存"
        description="跨境数据传输登记与合法性基础、数据留存期配置、到期清理与删除请求（被遗忘权）—— 财务（法定）留存期优先于删除权。"
        actions={
          <Button size="sm" variant="outline" onClick={() => void refresh()} disabled={busy}>
            <RefreshCw className="h-3.5 w-3.5" />
            刷新
          </Button>
        }
      />

      {error && (
        <div className="border-destructive-soft bg-destructive-soft text-destructive flex items-start gap-2 rounded-lg border px-4 py-3">
          <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <span className="text-copy-13">{error}</span>
        </div>
      )}
      {notice && (
        <div className="border-info-soft bg-info-soft text-info flex items-start gap-2 rounded-lg border px-4 py-3">
          <CircleHelp className="mt-0.5 h-4 w-4 shrink-0" />
          <span className="text-copy-13 whitespace-pre-line">{notice}</span>
        </div>
      )}

      {/* ---------------- 概览 ---------------- */}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="已登记数据域" value={domains?.total ?? 0} polarity="neutral" />
        <StatCard
          label="留存期冲突"
          value={conflicts?.counts.conflicting ?? 0}
          polarity={conflicts?.counts.conflicting ? 'negative' : 'neutral'}
        />
        <StatCard
          label="删除被财务留存挡住"
          value={conflicts?.counts.deletionBlocked ?? 0}
          polarity={conflicts?.counts.deletionBlocked ? 'negative' : 'neutral'}
        />
        <StatCard
          label="待审批删除请求"
          value={deletions?.counts.pending ?? 0}
          polarity="neutral"
        />
      </div>

      {/* ---------------- 能力声明 ---------------- */}
      {caps && (
        <SectionCard
          title="能力声明"
          description="登记口径、留存口径、冲突裁决口径、删除与清理口径 —— 界面上显示的每一句都来自这里。"
        >
          <div className="space-y-4">
            {missingUpstreams.length > 0 && (
              <div className="space-y-2">
                {missingUpstreams.map((upstream) => (
                  <div
                    key={upstream.sourceCode}
                    className="border-warning-soft bg-warning-soft rounded-lg border px-3 py-2"
                  >
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone="warning">读不到</Badge>
                      <span className="text-copy-13 text-foreground font-medium">
                        {upstream.sourceLabel}
                      </span>
                      <span className="text-copy-12 text-muted-foreground">
                        归属：{upstream.ownerModule || '未标注归属模块'}
                      </span>
                    </div>
                    <p className="text-copy-12 text-muted-foreground mt-1">
                      {unavailableState(upstream).description}
                    </p>
                  </div>
                ))}
              </div>
            )}
            <div className="flex flex-wrap gap-2">
              {caps.upstreams.map((u) => (
                <Badge key={u.sourceCode} tone={u.available ? 'info' : 'warning'}>
                  {u.sourceLabel}：{u.available ? '可读' : '读不到'}
                </Badge>
              ))}
            </div>
            <details className="text-copy-12 text-muted-foreground">
              <summary className="cursor-pointer">
                口径说明（登记 / 留存 / 冲突 / 删除 / 清理 / 留痕 / 边界）
              </summary>
              <div className="mt-2 space-y-2">
                <p>{caps.transferSemantics}</p>
                <p>{caps.retentionSemantics}</p>
                <p>{caps.conflictSemantics}</p>
                <p>{caps.deletionSemantics}</p>
                <p>{caps.cleanupSemantics}</p>
                <p>{caps.auditSemantics}</p>
                <p>{caps.downstreamNote}</p>
                <p>{caps.boundaryNote}</p>
              </div>
            </details>
          </div>
        </SectionCard>
      )}

      {/* ---------------- 留存期裁决（排最前：这是本模块的核心）---------------- */}
      <SectionCard
        title="留存期裁决"
        description="保留期与财务（法定）留存期的冲突：冲突对、双方期限、裁决结果与依据都摆在这里 —— 不只存不用。"
        extra={
          <Input
            className="h-8 w-48"
            placeholder="按数据域筛选（留空看全部）"
            value={domainFilter}
            onChange={(e) => setDomainFilter(e.target.value)}
          />
        }
      >
        {!conflicts || conflicts.items.length === 0 ? (
          <EmptyState
            icon={<Scale className="h-6 w-6" />}
            title={emptyState(domainFilter ? 'FILTER_EMPTY' : 'NO_CONFLICT').title}
            description={emptyState(domainFilter ? 'FILTER_EMPTY' : 'NO_CONFLICT').description}
          />
        ) : (
          <div className="space-y-3">
            {conflicts.items.map((conflict) => (
              <div key={conflict.domainCode} className="border-border rounded-lg border px-4 py-3">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-copy-13 text-foreground font-medium">
                    {conflict.domainName}
                  </span>
                  <Badge tone="neutral">{conflict.domainCode}</Badge>
                  <Badge tone={resolutionTone(conflict.resolution)}>
                    {conflict.resolutionLabel}
                  </Badge>
                  <Badge tone={conflict.deletionBlocked ? 'warning' : 'neutral'}>
                    {conflict.deletionBlocked ? '删除被挡住' : '可删'}
                  </Badge>
                </div>
                <div className="text-copy-12 text-muted-foreground mt-2 grid gap-1 md:grid-cols-2">
                  <div>
                    普通留存期：{daysText(conflict.standardRetentionDays)} 天
                    {conflict.standardLegalBasis ? `（依据：${conflict.standardLegalBasis}）` : ''}
                  </div>
                  <div>
                    法定（财务）留存期：{daysText(conflict.financialRetentionDays)} 天
                    {conflict.financialLegalBasis
                      ? `（依据：${conflict.financialLegalBasis}）`
                      : ''}
                  </div>
                  <div>
                    冲突形态：{conflict.conflictKindLabel}
                    {conflict.effectiveRetentionDays !== null
                      ? ` · 生效留存期 ${daysText(conflict.effectiveRetentionDays)} 天`
                      : ''}
                  </div>
                  <div>
                    {conflict.deletionBlocked
                      ? `删除最早可执行日：${dateText(conflict.deletionBlockedUntil)}`
                      : '法定留存期已走完 · 不再阻挡删除'}
                  </div>
                </div>
                <p className="text-copy-12 text-muted-foreground mt-2">{conflict.rationale}</p>
                {conflict.lastAdjudicatedAt && (
                  <p className="text-copy-12 text-muted-foreground mt-1">
                    上次裁决：{dateText(conflict.lastAdjudicatedAt)}
                  </p>
                )}
              </div>
            ))}
          </div>
        )}
      </SectionCard>

      {/* ---------------- 数据域登记 ---------------- */}
      <SectionCard
        title="数据域登记"
        description="留存策略、跨境传输、删除请求都挂在数据域上 —— 没有归属方的数据域没人负责清理。"
      >
        <div className="space-y-4">
          <div className="grid gap-3 md:grid-cols-4">
            <label className="text-copy-13 text-foreground space-y-1">
              <span>数据域编码</span>
              <Input
                value={newDomainCode}
                placeholder="ORDER"
                onChange={(e) => setNewDomainCode(e.target.value)}
              />
            </label>
            <label className="text-copy-13 text-foreground space-y-1">
              <span>数据域名称</span>
              <Input
                value={newDomainName}
                placeholder="9 订单"
                onChange={(e) => setNewDomainName(e.target.value)}
              />
            </label>
            <label className="text-copy-13 text-foreground space-y-1">
              <span>归属模块</span>
              <Input
                value={newDomainOwner}
                placeholder="9 订单（trade 服务）"
                onChange={(e) => setNewDomainOwner(e.target.value)}
              />
            </label>
            <label className="text-copy-13 text-foreground space-y-1">
              <span>数据分类</span>
              <select
                className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3"
                value={newDomainClass}
                onChange={(e) => setNewDomainClass(e.target.value)}
              >
                {DATA_CLASSES.map((c) => (
                  <option key={c.value} value={c.value}>
                    {c.label}
                  </option>
                ))}
              </select>
            </label>
          </div>
          <div className="flex flex-wrap items-center gap-4">
            <label className="text-copy-13 text-foreground flex items-center gap-2">
              <input
                type="checkbox"
                checked={newDomainPii}
                onChange={(e) => setNewDomainPii(e.target.checked)}
              />
              含个人信息（GDPR 删除权适用）
            </label>
            <label className="text-copy-13 text-foreground flex items-center gap-2">
              <input
                type="checkbox"
                checked={newDomainFinancial}
                onChange={(e) => setNewDomainFinancial(e.target.checked)}
              />
              适用法定（财务）留存期
            </label>
            <Button size="sm" onClick={submitDomain} disabled={busy || !newDomainCode.trim()}>
              登记数据域
            </Button>
          </div>

          {!domains || domains.items.length === 0 ? (
            <EmptyState
              icon={<ShieldAlert className="h-6 w-6" />}
              title={emptyState(domainFilter ? 'FILTER_EMPTY' : 'NO_DOMAIN').title}
              description={emptyState(domainFilter ? 'FILTER_EMPTY' : 'NO_DOMAIN').description}
            />
          ) : (
            <div className="space-y-2">
              {domains.items.map((d) => (
                <div key={d.domainId} className="border-border rounded-md border px-3 py-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-copy-13 text-foreground font-medium">{d.domainName}</span>
                    <Badge tone="neutral">{d.domainCode}</Badge>
                    <Badge tone="info">{d.dataClassLabel}</Badge>
                    {d.containsPii && <Badge tone="warning">含 PII</Badge>}
                    {d.statutoryFinancial && <Badge tone="danger">适用财务留存期</Badge>}
                  </div>
                  <p className="text-copy-12 text-muted-foreground mt-1">归属：{d.ownerModule}</p>
                </div>
              ))}
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---------------- 留存策略 ---------------- */}
      <SectionCard
        title="留存策略"
        description="同一（数据域 × 类别）同时只能有一条生效策略；改策略 = 登记新版本，旧版本自动作废 —— 历史判定不被追溯改变。"
      >
        <div className="space-y-4">
          <div className="grid gap-3 md:grid-cols-5">
            <label className="text-copy-13 text-foreground space-y-1">
              <span>数据域编码</span>
              <Input value={policyDomain} onChange={(e) => setPolicyDomain(e.target.value)} />
            </label>
            <label className="text-copy-13 text-foreground space-y-1">
              <span>留存期类别</span>
              <select
                className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3"
                value={policyClass}
                onChange={(e) => setPolicyClass(e.target.value)}
              >
                {RETENTION_CLASSES.map((c) => (
                  <option key={c.value} value={c.value}>
                    {c.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="text-copy-13 text-foreground space-y-1">
              <span>留存天数（整天）</span>
              <Input
                type="number"
                min={0}
                value={policyDays}
                onChange={(e) => setPolicyDays(e.target.value)}
              />
            </label>
            <label className="text-copy-13 text-foreground space-y-1">
              <span>起算口径</span>
              <select
                className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3"
                value={policyTrigger}
                onChange={(e) => setPolicyTrigger(e.target.value)}
              >
                <option value="CREATION">按数据创建时间起算</option>
                <option value="CLOSURE">按业务结束时间起算</option>
              </select>
            </label>
            <label className="text-copy-13 text-foreground space-y-1">
              <span>法定依据</span>
              <Input
                value={policyBasis}
                placeholder="如：税法要求凭证留存 10 年"
                onChange={(e) => setPolicyBasis(e.target.value)}
              />
            </label>
          </div>
          <Button size="sm" onClick={submitPolicy} disabled={busy || !policyDomain.trim()}>
            登记留存策略（新增版本）
          </Button>

          {!policies || policies.items.length === 0 ? (
            <EmptyState
              icon={<Clock className="h-6 w-6" />}
              title={emptyState(domainFilter ? 'FILTER_EMPTY' : 'NO_POLICY').title}
              description={emptyState(domainFilter ? 'FILTER_EMPTY' : 'NO_POLICY').description}
            />
          ) : (
            <div className="space-y-2">
              {policies.items.map((p) => (
                <div key={p.policyId} className="border-border rounded-md border px-3 py-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-copy-13 text-foreground font-medium">{p.policyName}</span>
                    <Badge tone="neutral">{p.domainCode}</Badge>
                    <Badge tone={p.retentionClass === 'FINANCIAL' ? 'danger' : 'info'}>
                      {retentionClassLabel(p.retentionClass)}
                    </Badge>
                    <Badge tone="neutral">{daysText(p.retentionDays)} 天</Badge>
                    <Badge tone="neutral">v{p.versionNo}</Badge>
                    <Badge tone={p.status === 'ACTIVE' ? 'success' : 'neutral'}>
                      {p.statusLabel}
                    </Badge>
                  </div>
                  <p className="text-copy-12 text-muted-foreground mt-1">
                    {p.retentionTriggerLabel}
                    {p.legalBasis ? ` · 依据：${p.legalBasis}` : ''} · 生效自{' '}
                    {dateText(p.effectiveFrom)}
                  </p>
                </div>
              ))}
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---------------- 跨境传输登记 ---------------- */}
      <SectionCard
        title="跨境数据传输登记"
        description="合法性基础是受控值域、接收方法域是两位码 —— 自由文本的合法性基础等于没登记。"
        extra={
          <select
            className="border-control-border bg-card text-copy-14 text-foreground h-8 rounded-md border px-2"
            value={transferStatus}
            onChange={(e) => setTransferStatus(e.target.value)}
          >
            <option value="">全部状态</option>
            <option value="ACTIVE">进行中</option>
            <option value="SUSPENDED">已暂停</option>
            <option value="ENDED">已终止</option>
          </select>
        }
      >
        <div className="space-y-4">
          <div className="grid gap-3 md:grid-cols-5">
            <label className="text-copy-13 text-foreground space-y-1">
              <span>源数据域</span>
              <Input value={transferDomain} onChange={(e) => setTransferDomain(e.target.value)} />
            </label>
            <label className="text-copy-13 text-foreground space-y-1">
              <span>接收方法域（两位码）</span>
              <Input
                value={transferRegion}
                maxLength={2}
                onChange={(e) => setTransferRegion(e.target.value.toUpperCase())}
              />
            </label>
            <label className="text-copy-13 text-foreground space-y-1">
              <span>接收方</span>
              <Input
                value={transferRecipient}
                onChange={(e) => setTransferRecipient(e.target.value)}
              />
            </label>
            <label className="text-copy-13 text-foreground space-y-1">
              <span>合法性基础</span>
              <select
                className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3"
                value={transferBasis}
                onChange={(e) => setTransferBasis(e.target.value)}
              >
                {LEGAL_BASES.map((b) => (
                  <option key={b.value} value={b.value}>
                    {b.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="text-copy-13 text-foreground space-y-1">
              <span>保障措施</span>
              <Input
                value={transferSafeguards}
                placeholder="SCC 2021/914"
                onChange={(e) => setTransferSafeguards(e.target.value)}
              />
            </label>
          </div>
          <Button
            size="sm"
            onClick={submitTransfer}
            disabled={busy || !transferDomain.trim() || !transferRecipient.trim()}
          >
            登记跨境传输
          </Button>

          {!transfers || transfers.items.length === 0 ? (
            <EmptyState
              icon={<Globe2 className="h-6 w-6" />}
              title={
                emptyState(domainFilter || transferStatus ? 'FILTER_EMPTY' : 'NO_TRANSFER').title
              }
              description={
                emptyState(domainFilter || transferStatus ? 'FILTER_EMPTY' : 'NO_TRANSFER')
                  .description
              }
            />
          ) : (
            <div className="space-y-2">
              {transfers.items.map((t) => (
                <div key={t.transferId} className="border-border rounded-md border px-3 py-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-copy-13 text-foreground font-medium">{t.recipient}</span>
                    <Badge tone="neutral">{t.transferNo}</Badge>
                    <Badge tone="info">{t.targetRegion}</Badge>
                    <Badge tone="neutral">{t.legalBasisLabel}</Badge>
                    <Badge tone={transferStatusTone(t.status)}>
                      {transferStatusLabel(t.status)}
                    </Badge>
                  </div>
                  <p className="text-copy-12 text-muted-foreground mt-1">
                    源数据域 {t.sourceDomainName}（{t.sourceDomainCode}）
                    {t.safeguards ? ` · 保障措施：${t.safeguards}` : ''}
                    {t.tiaRef ? ` · 影响评估：${t.tiaRef}` : ''}
                  </p>
                  {t.suspendReason && (
                    <p className="text-copy-12 text-muted-foreground mt-1">
                      暂停理由：{t.suspendReason}
                    </p>
                  )}
                  {t.endReason && (
                    <p className="text-copy-12 text-muted-foreground mt-1">
                      终止理由：{t.endReason}
                    </p>
                  )}
                  <div className="mt-2 flex flex-wrap gap-2">
                    {t.status === 'ACTIVE' && (
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={busy}
                        onClick={() =>
                          void run(
                            () => suspendTransfer(t.transferId, '合规复核未通过，暂停传输'),
                            '已暂停该传输。',
                          )
                        }
                      >
                        暂停
                      </Button>
                    )}
                    {t.status !== 'ENDED' && (
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={busy}
                        onClick={() =>
                          void run(
                            () => endTransfer(t.transferId, '业务终止，传输结束'),
                            '已终止该传输（终态，不可复活）。',
                          )
                        }
                      >
                        终止
                      </Button>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---------------- 删除请求（被遗忘权）---------------- */}
      <SectionCard
        title="删除请求（被遗忘权）"
        description="申请 → 审批 → 执行；审批人不能是申请人本人。法定（法定）留存期没走完 ⇒ 推迟，不是驳回。"
        extra={
          <select
            className="border-control-border bg-card text-copy-14 text-foreground h-8 rounded-md border px-2"
            value={deletionStatus}
            onChange={(e) => setDeletionStatus(e.target.value)}
          >
            <option value="">全部状态</option>
            <option value="PENDING">待审批</option>
            <option value="APPROVED">已批准</option>
            <option value="DEFERRED">已推迟</option>
            <option value="REJECTED">已驳回</option>
            <option value="EXECUTED">已执行</option>
          </select>
        }
      >
        <div className="space-y-4">
          <div className="grid gap-3 md:grid-cols-4">
            <label className="text-copy-13 text-foreground space-y-1">
              <span>请求类型</span>
              <select
                className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3"
                value={deletionType}
                onChange={(e) => setDeletionType(e.target.value)}
              >
                {REQUEST_TYPES.map((t) => (
                  <option key={t.value} value={t.value}>
                    {t.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="text-copy-13 text-foreground space-y-1">
              <span>目标数据域</span>
              <Input value={deletionDomain} onChange={(e) => setDeletionDomain(e.target.value)} />
            </label>
            <label className="text-copy-13 text-foreground space-y-1">
              <span>主体标识</span>
              <Input
                value={deletionSubject}
                placeholder="订单号 / 买家标识"
                onChange={(e) => setDeletionSubject(e.target.value)}
              />
            </label>
            <label className="text-copy-13 text-foreground space-y-1">
              <span>理由（必填）</span>
              <Input value={deletionReason} onChange={(e) => setDeletionReason(e.target.value)} />
            </label>
          </div>
          <Button
            size="sm"
            onClick={submitDeletion}
            disabled={
              busy || !deletionDomain.trim() || !deletionSubject.trim() || !deletionReason.trim()
            }
          >
            提交删除请求
          </Button>

          {!deletions || deletions.items.length === 0 ? (
            <EmptyState
              icon={<FileWarning className="h-6 w-6" />}
              title={
                emptyState(domainFilter || deletionStatus ? 'FILTER_EMPTY' : 'NO_DELETION').title
              }
              description={
                emptyState(domainFilter || deletionStatus ? 'FILTER_EMPTY' : 'NO_DELETION')
                  .description
              }
            />
          ) : (
            <div className="space-y-2">
              {deletions.items.map((r) => (
                <div key={r.requestId} className="border-border rounded-md border px-3 py-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-copy-13 text-foreground font-medium">{r.subjectRef}</span>
                    <Badge tone="neutral">{r.requestNo}</Badge>
                    <Badge tone="neutral">{r.domainName}</Badge>
                    <Badge tone="info">{r.requestTypeLabel}</Badge>
                    <Badge tone={deletionStatusTone(r.status)}>
                      {deletionStatusLabel(r.status)}
                    </Badge>
                  </div>
                  <p className="text-copy-12 text-muted-foreground mt-1">
                    理由：{r.reason} · 申请人 {actorLabel(r.requestedBy)} ·{' '}
                    {dateText(r.requestedAt)}
                  </p>
                  {r.deferReason && (
                    <p className="text-copy-12 text-warning mt-1">{deferralText(r)}</p>
                  )}
                  {r.cleanupOrderNo && (
                    <p className="text-copy-12 text-muted-foreground mt-1">
                      已派发清理工单：{r.cleanupOrderNo}
                    </p>
                  )}
                  <div className="mt-2 flex flex-wrap gap-2">
                    {canApproveDeletion(r) && (
                      <>
                        <Button
                          size="sm"
                          variant="outline"
                          disabled={busy}
                          onClick={() =>
                            void run(
                              () => approveDeletion(r.requestId, '已核实主体身份，同意删除'),
                              '已审批（若受财务留存期阻挡，状态会是「已推迟」）。',
                            )
                          }
                        >
                          批准
                        </Button>
                        <Button
                          size="sm"
                          variant="outline"
                          disabled={busy}
                          onClick={() =>
                            void run(
                              () => rejectDeletion(r.requestId, '主体身份无法核实，不予删除'),
                              '已驳回。',
                            )
                          }
                        >
                          驳回
                        </Button>
                      </>
                    )}
                    {canExecuteDeletion(r) && (
                      <Button
                        size="sm"
                        disabled={busy}
                        onClick={() =>
                          void run(
                            () => executeDeletion(r.requestId),
                            '已执行 —— 已派发清理工单（物理删除由下游数据清理任务完成）。',
                          )
                        }
                      >
                        执行（派发清理工单）
                      </Button>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---------------- 清理工单 ---------------- */}
      <SectionCard
        title="清理工单"
        description="本模块不跨服务删业务数据 —— 工单 + 状态是产出，真正的删除由下游数据清理任务执行。"
      >
        <div className="space-y-4">
          <div className="grid gap-3 md:grid-cols-3">
            <label className="text-copy-13 text-foreground space-y-1">
              <span>目标数据域</span>
              <Input value={cleanupDomain} onChange={(e) => setCleanupDomain(e.target.value)} />
            </label>
            <label className="text-copy-13 text-foreground space-y-1">
              <span>目标对象（可选）</span>
              <Input value={cleanupSubject} onChange={(e) => setCleanupSubject(e.target.value)} />
            </label>
            <label className="text-copy-13 text-foreground space-y-1">
              <span>触发来源</span>
              <select
                className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3"
                value={cleanupTrigger}
                onChange={(e) => setCleanupTrigger(e.target.value)}
              >
                {TRIGGER_KINDS.map((t) => (
                  <option key={t.value} value={t.value}>
                    {t.label}
                  </option>
                ))}
              </select>
            </label>
          </div>
          <Button size="sm" onClick={submitCleanup} disabled={busy || !cleanupDomain.trim()}>
            登记清理工单
          </Button>

          {!cleanupOrders || cleanupOrders.items.length === 0 ? (
            <EmptyState
              icon={<Trash2 className="h-6 w-6" />}
              title={emptyState(domainFilter ? 'FILTER_EMPTY' : 'NO_CLEANUP').title}
              description={emptyState(domainFilter ? 'FILTER_EMPTY' : 'NO_CLEANUP').description}
            />
          ) : (
            <div className="space-y-2">
              {cleanupOrders.items.map((o: RetentionCleanupOrder) => (
                <div key={o.orderId} className="border-border rounded-md border px-3 py-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-copy-13 text-foreground font-medium">{o.orderNo}</span>
                    <Badge tone="neutral">{o.domainName}</Badge>
                    <Badge tone="info">{o.triggerKindLabel}</Badge>
                    <Badge tone={cleanupStatusTone(o.status)}>{cleanupStatusLabel(o.status)}</Badge>
                    <Badge tone="neutral">{daysText(o.retentionDays)} 天</Badge>
                  </div>
                  <p className="text-copy-12 text-muted-foreground mt-1">
                    {o.subjectRef ? `目标对象 ${o.subjectRef} · ` : ''}最早可清理日{' '}
                    {dateText(o.eligibleAt)} · 完成截止日 {dateText(o.dueAt)}
                  </p>
                  {canExecuteCleanup(o) && (
                    <div className="mt-2 flex flex-wrap gap-2">
                      {CLEANUP_RESULTS.map((r) => (
                        <Button
                          key={r.value}
                          size="sm"
                          variant="outline"
                          disabled={busy}
                          onClick={() =>
                            void run(
                              () => executeCleanupOrder(o.orderId, r.value, 0, ''),
                              `已回报执行结果：${r.label}。`,
                            )
                          }
                        >
                          回报「{r.label}」
                        </Button>
                      ))}
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---------------- 清理执行记录 ---------------- */}
      <SectionCard
        title="清理执行记录"
        description="append-only 的执行凭据 —— 谁、何时、对哪个数据域、做了什么、结果如何、处理了多少条。"
      >
        {!cleanupRecords || cleanupRecords.items.length === 0 ? (
          <EmptyState
            icon={<Trash2 className="h-6 w-6" />}
            title={emptyState(domainFilter ? 'FILTER_EMPTY' : 'NO_RECORD').title}
            description={emptyState(domainFilter ? 'FILTER_EMPTY' : 'NO_RECORD').description}
          />
        ) : (
          <div className="space-y-2">
            {cleanupRecords.items.map((r) => (
              <div key={r.recordId} className="border-border rounded-md border px-3 py-2">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-copy-13 text-foreground font-medium">{r.orderNo}</span>
                  <Badge tone="neutral">{r.domainName}</Badge>
                  <Badge tone="neutral">{r.actionLabel}</Badge>
                  <Badge
                    tone={
                      r.result === 'SUCCESS'
                        ? 'success'
                        : r.result === 'FAILED'
                          ? 'danger'
                          : 'warning'
                    }
                  >
                    {r.resultLabel}
                  </Badge>
                  <Badge tone="neutral">{daysText(r.recordCount)} 条</Badge>
                </div>
                <p className="text-copy-12 text-muted-foreground mt-1">
                  {r.message || '（无说明）'} · 执行人 {actorLabel(r.executedBy)} ·{' '}
                  {dateText(r.executedAt)}
                </p>
              </div>
            ))}
          </div>
        )}
      </SectionCard>
    </div>
  );
}
