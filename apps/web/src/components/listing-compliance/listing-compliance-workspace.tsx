'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import {
  CheckCircle2,
  FileSearch,
  Loader2,
  RefreshCw,
  ScanSearch,
  ShieldAlert,
  ShieldCheck,
  Trash2,
  TriangleAlert,
} from 'lucide-react';
import {
  conclusionLabel,
  conclusionTone,
  createComplianceWord,
  decideComplianceRisks,
  deleteComplianceWord,
  dimensionStateLabel,
  dimensionStateTone,
  emptyWordInput,
  fetchComplianceCapabilities,
  fetchComplianceDrafts,
  fetchComplianceFilterOptions,
  fetchCompliancePublishGate,
  fetchComplianceScan,
  fetchComplianceScans,
  fetchComplianceWords,
  fieldAnchorId,
  gateSummary,
  matchedTextLabel,
  riskLevelLabel,
  riskLevelTone,
  riskLocationHref,
  riskLocationLabel,
  risksByField,
  runComplianceScan,
  scanSummary,
  severityLabel,
  severityTone,
  setComplianceWordEnabled,
  splitRisks,
  unavailableDimensions,
  wordCategoryLabel,
  type ListingComplianceCapabilities,
  type ListingComplianceDraftRow,
  type ListingComplianceFilterOptions,
  type ListingCompliancePublishGate,
  type ListingComplianceRiskRow,
  type ListingComplianceScanResult,
  type ListingComplianceWordInput,
  type ListingComplianceWordRow,
} from './listing-compliance-model';

/**
 * 4.2.3.2 合规预检 工作区。
 *
 * <p>蓝图：「发布前扫描内容的违规风险：违禁词/敏感词命中、极限词、品牌侵权（外观/商标）、平台类目必填缺失、
 * 图片不合规（尺寸/白底/水印）、认证缺失。……输出：风险清单（风险等级、命中位置、规则来源、建议替换）；
 * 阻断项（未修复不允许发布）与提醒项。……<b>阻断项在服务端强制校验</b>，前端提示不能作为唯一防线。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **前端不重算**。风险等级、命中、阻断还是提醒，全部由后端算完下发，页面原样显示。
 * 2. **阻断项与提醒项分区显示 ≠ 拦截**。真正的拦截在服务端（发布闸门 + DB 触发器），
 *    页面把「未修复不允许发布（服务端已拦截）」写清楚，但**不假装自己在拦**。
 * 3. **「不知道」是一个状态**。维度状态 UNKNOWN 单独成态；结论 INCOMPLETE 不写成「通过」。
 * 4. **命中位置能跳到具体字段**。每条风险带字段键 / 字段名 / 第几项，点一下跳到该栏汇总行。
 * 5. **三态分开渲染**：加载中 / 加载失败 / 没有内容（无草稿 · 无风险 · 扫描失败），文案各不相同。
 */
export function ListingComplianceWorkspace() {
  const [caps, setCaps] = useState<ListingComplianceCapabilities | null>(null);
  const [options, setOptions] = useState<ListingComplianceFilterOptions | null>(null);

  const [drafts, setDrafts] = useState<ListingComplianceDraftRow[]>([]);
  const [draftTotal, setDraftTotal] = useState(0);
  const [draftPage, setDraftPage] = useState(0);
  const [draftKeyword, setDraftKeyword] = useState('');
  const [selectedDraftId, setSelectedDraftId] = useState('');

  const [result, setResult] = useState<ListingComplianceScanResult | null>(null);
  const [gate, setGate] = useState<ListingCompliancePublishGate | null>(null);

  const [history, setHistory] = useState<ListingComplianceRiskRow[]>([]);
  const [scanRows, setScanRows] = useState<Awaited<ReturnType<typeof fetchComplianceScans>> | null>(
    null,
  );
  const [scanPage, setScanPage] = useState(0);

  const [words, setWords] = useState<ListingComplianceWordRow[]>([]);
  const [wordTotal, setWordTotal] = useState(0);
  const [wordPage, setWordPage] = useState(0);
  const [wordDraft, setWordDraft] = useState<ListingComplianceWordInput>(emptyWordInput);

  const [loading, setLoading] = useState(true);
  const [scanning, setScanning] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [scanError, setScanError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const PAGE_SIZE = 20;

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capsResult, optionsResult, draftList, scanList, wordList] = await Promise.all([
        fetchComplianceCapabilities(),
        fetchComplianceFilterOptions(),
        fetchComplianceDrafts(draftKeyword, draftPage, PAGE_SIZE),
        fetchComplianceScans(scanPage, PAGE_SIZE),
        fetchComplianceWords({}, wordPage, PAGE_SIZE),
      ]);
      setCaps(capsResult);
      setOptions(optionsResult);
      setDrafts(draftList.items);
      setDraftTotal(draftList.total);
      setScanRows(scanList);
      setWords(wordList.items);
      setWordTotal(wordList.total);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [draftKeyword, draftPage, scanPage, wordPage]);

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const run = useCallback(async (work: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await work();
    } catch (e) {
      setError(e instanceof Error ? e.message : '操作失败，请重试');
    } finally {
      setBusy(false);
    }
  }, []);

  const scan = useCallback(
    async (draftId: string) => {
      setScanning(true);
      setScanError(null);
      setResult(null);
      setGate(null);
      try {
        const scanned = await runComplianceScan(draftId);
        setResult(scanned);
        setSelectedDraftId(draftId);
        const gateResult = await fetchCompliancePublishGate(draftId);
        setGate(gateResult);
        setHistory(scanned.risks);
        setScanPage(0);
        await refresh();
      } catch (e) {
        setScanError(e instanceof Error ? e.message : '扫描失败，请重试');
      } finally {
        setScanning(false);
      }
    },
    [refresh],
  );

  const openScan = useCallback(
    (scanId: string, draftId: string) =>
      run(async () => {
        const loaded = await fetchComplianceScan(scanId);
        setResult(loaded);
        setSelectedDraftId(draftId);
        setHistory(loaded.risks);
        setGate(await fetchCompliancePublishGate(draftId));
      }),
    [run],
  );

  const decide = useCallback(
    (riskIds: string[], status: 'RESOLVED' | 'IGNORED' | 'OPEN') =>
      run(async () => {
        if (!result) return;
        await decideComplianceRisks(riskIds, status, '在工作区处置');
        const reloaded = await fetchComplianceScan(result.scanId);
        setResult(reloaded);
        setHistory(reloaded.risks);
        setGate(await fetchCompliancePublishGate(result.draftId));
        setNotice(`已处置 ${riskIds.length} 条风险`);
      }),
    [result, run],
  );

  const saveWord = useCallback(
    (body: ListingComplianceWordInput) =>
      run(async () => {
        await createComplianceWord(body);
        setWordDraft(emptyWordInput());
        await refresh();
        setNotice('词条已保存');
      }),
    [refresh, run],
  );

  const toggleWord = useCallback(
    (id: string, enabled: boolean) =>
      run(async () => {
        await setComplianceWordEnabled(id, enabled);
        await refresh();
      }),
    [refresh, run],
  );

  const removeWord = useCallback(
    (id: string) =>
      run(async () => {
        await deleteComplianceWord(id);
        await refresh();
        setNotice('词条已删除');
      }),
    [refresh, run],
  );

  const { blockers, warnings } = useMemo(() => splitRisks(result?.risks ?? []), [result]);
  const fieldBuckets = useMemo(() => risksByField(result?.risks ?? []), [result]);
  const missing = useMemo(() => unavailableDimensions(caps), [caps]);

  // ---- 三态之一：加载中 ----
  if (loading) {
    return (
      <div className="text-muted-foreground flex items-center justify-center gap-2 py-16">
        <Loader2 className="size-4 animate-spin" />
        <span className="text-label-13">正在加载合规预检…</span>
      </div>
    );
  }

  // ---- 三态之二：加载失败 ----
  if (error && !caps) {
    return (
      <SectionCard title="合规预检">
        <EmptyState
          icon={<TriangleAlert className="text-destructive size-8" />}
          title="合规预检加载失败"
          description={error}
          action={
            <Button variant="secondary" onClick={() => void refresh()}>
              <RefreshCw className="mr-2 size-4" />
              重试
            </Button>
          }
        />
      </SectionCard>
    );
  }

  return (
    <div className="space-y-4">
      <PageHeader
        title="合规预检"
        description="发布前扫描内容的违规风险：违禁词 / 敏感词 / 极限词、品牌侵权、平台类目必填、图片不合规、认证缺失。产出风险清单（风险等级 · 命中位置 · 规则来源 · 建议替换）与阻断项 / 提醒项。"
      />

      {error && (
        <div className="border-destructive/40 bg-destructive/5 text-destructive flex items-start gap-2 rounded-lg border px-4 py-3">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <p className="text-label-13">{error}</p>
        </div>
      )}
      {notice && (
        <div className="border-brand/40 bg-brand-soft/40 text-brand flex items-start gap-2 rounded-lg border px-4 py-3">
          <CheckCircle2 className="mt-0.5 size-4 shrink-0" />
          <p className="text-label-13">{notice}</p>
        </div>
      )}

      {/* ---- 服务端强制校验的说明（后端给原文，前端原样展示） ---- */}
      {caps && (
        <div className="border-brand/40 bg-brand-soft/40 rounded-lg border px-4 py-3">
          <p className="text-foreground text-label-13 mb-1 font-medium">阻断项在服务端强制校验</p>
          <ul className="text-muted-foreground text-label-12 space-y-1">
            <li className="flex items-start gap-2">
              <ShieldCheck className="text-brand mt-0.5 size-3.5 shrink-0" />
              <span>{caps.serverEnforcementNote}</span>
            </li>
            <li className="flex items-start gap-2">
              <ShieldAlert className="text-brand mt-0.5 size-3.5 shrink-0" />
              <span>{caps.boundaryNote}</span>
            </li>
            {missing.map((dimension) => (
              <li key={dimension.dimension} className="flex items-start gap-2">
                <TriangleAlert className="text-warning mt-0.5 size-3.5 shrink-0" />
                <span>
                  {dimension.description}（归属模块 {dimension.ownerModule}）：{dimension.impact}
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* ---- 统计 ---- */}
      {result && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard
            label="结论"
            value={conclusionLabel(result.conclusion)}
            icon={<ScanSearch className="size-4" />}
          />
          <StatCard
            label="阻断项"
            value={result.blockingCount}
            unit="条"
            polarity={result.blockingCount > 0 ? 'negative' : 'positive'}
            icon={<ShieldAlert className="size-4" />}
          />
          <StatCard
            label="提醒项"
            value={result.warningCount}
            unit="条"
            icon={<TriangleAlert className="size-4" />}
          />
          <StatCard
            label="已判定维度"
            value={`${result.evaluatedDimensionCount}/${result.totalDimensionCount}`}
            icon={<ShieldCheck className="size-4" />}
          />
        </div>
      )}

      {/* ---- 内容来源（编辑器草稿） ---- */}
      <SectionCard
        title="内容来源"
        description="扫描的对象是刊登编辑器里的内容草稿（按平台 + 站点 + 语言分别保存的那一份）。"
        extra={
          <Button
            variant="secondary"
            onClick={() => void refresh()}
            disabled={busy}
            aria-label="刷新"
          >
            <RefreshCw className="mr-2 size-4" />
            刷新
          </Button>
        }
      >
        <div className="flex flex-wrap items-center gap-2 pb-3">
          <Input
            value={draftKeyword}
            onChange={(event) => {
              setDraftKeyword(event.target.value);
              setDraftPage(0);
            }}
            placeholder="按语言 / 类目路径搜索草稿"
            className="max-w-xs"
            aria-label="搜索草稿"
          />
          <Button
            onClick={() => {
              setDraftPage(0);
              void refresh();
            }}
            variant="secondary"
          >
            搜索
          </Button>
        </div>

        {drafts.length === 0 ? (
          <EmptyState
            icon={<FileSearch className="text-muted-foreground size-8" />}
            title="没有可扫描的内容草稿"
            description="这一租户在当前筛选下还没有编辑器草稿。先去刊登编辑器保存一份草稿，再回来做预检。"
          />
        ) : (
          <div className="space-y-2">
            {drafts.map((draft) => (
              <div
                key={draft.draftId}
                className={
                  selectedDraftId === draft.draftId
                    ? 'border-primary/50 bg-primary/5 flex flex-wrap items-center justify-between gap-3 rounded-lg border px-4 py-3'
                    : 'border-border flex flex-wrap items-center justify-between gap-3 rounded-lg border px-4 py-3'
                }
              >
                <div className="min-w-0 space-y-1">
                  <p className="text-foreground text-label-13 font-medium">
                    {draft.platformCode} / {draft.marketplaceCode} / {draft.locale}
                  </p>
                  <p className="text-muted-foreground text-label-12">
                    类目路径：{draft.categoryPath || '（未填）'} · 草稿状态 {draft.state}
                    {draft.lastConclusion
                      ? ` · 上次预检 ${conclusionLabel(draft.lastConclusion)}（${draft.lastBlockingCount} 条阻断项）`
                      : ' · 从未预检'}
                  </p>
                </div>
                <Button
                  onClick={() => void scan(draft.draftId)}
                  disabled={scanning || busy}
                  variant={selectedDraftId === draft.draftId ? 'default' : 'secondary'}
                >
                  {scanning && selectedDraftId === draft.draftId ? (
                    <Loader2 className="mr-2 size-4 animate-spin" />
                  ) : (
                    <ScanSearch className="mr-2 size-4" />
                  )}
                  触发预检
                </Button>
              </div>
            ))}
            <p className="text-muted-foreground text-label-12">
              共 {draftTotal} 份草稿 · 第 {draftPage + 1} 页
              <Button
                variant="ghost"
                size="sm"
                className="ml-2"
                disabled={draftPage === 0}
                onClick={() => setDraftPage((p) => Math.max(0, p - 1))}
              >
                上一页
              </Button>
              <Button
                variant="ghost"
                size="sm"
                disabled={(draftPage + 1) * PAGE_SIZE >= draftTotal}
                onClick={() => setDraftPage((p) => p + 1)}
              >
                下一页
              </Button>
            </p>
          </div>
        )}
      </SectionCard>

      {/* ---- 风险清单 ---- */}
      <SectionCard
        title="风险清单"
        description="每条风险都带风险等级、命中位置、规则来源与建议替换。阻断项未修复不允许发布（服务端已拦截）。"
      >
        {scanning ? (
          <div className="text-muted-foreground flex items-center justify-center gap-2 py-10">
            <Loader2 className="size-4 animate-spin" />
            <span className="text-label-13">正在扫描…</span>
          </div>
        ) : scanError ? (
          <EmptyState
            icon={<TriangleAlert className="text-destructive size-8" />}
            title="扫描失败"
            description={scanError}
            action={
              selectedDraftId ? (
                <Button onClick={() => void scan(selectedDraftId)}>重试</Button>
              ) : undefined
            }
          />
        ) : !result ? (
          <EmptyState
            icon={<ScanSearch className="text-muted-foreground size-8" />}
            title="还没有扫描结果"
            description="先在上面选一份内容草稿，点「触发预检」。服务端会真的去扫一遍违禁词、品牌授权、类目必填与图片规格。"
          />
        ) : result.risks.length === 0 ? (
          <EmptyState
            icon={<ShieldCheck className="text-success size-8" />}
            title="没有命中任何风险"
            description={`这次扫描没有命中违禁词、极限词、品牌侵权、类目必填或图片问题。${
              result.conclusion === 'INCOMPLETE' ? '但仍有维度未能判定，结论不是「合规通过」。' : ''
            }`}
          />
        ) : (
          <div className="space-y-5">
            {/* 结论 */}
            <div className="flex flex-wrap items-center gap-2">
              <Badge
                tone={conclusionTone(result.conclusion) === 'destructive' ? 'danger' : 'warning'}
              >
                {conclusionLabel(result.conclusion)}
              </Badge>
              <span className="text-muted-foreground text-label-12">{scanSummary(result)}</span>
            </div>

            {/* 发布闸门（服务端算出来的） */}
            {gate && (
              <div
                className={
                  gate.allowed
                    ? 'border-brand/40 bg-brand-soft/40 rounded-lg border px-4 py-3'
                    : 'border-destructive/40 bg-destructive/5 rounded-lg border px-4 py-3'
                }
              >
                <p className="text-foreground text-label-13 mb-1 flex items-center gap-2 font-medium">
                  {gate.allowed ? (
                    <ShieldCheck className="text-brand size-4" />
                  ) : (
                    <ShieldAlert className="text-destructive size-4" />
                  )}
                  服务端发布闸门
                </p>
                <p className="text-muted-foreground text-label-12">{gateSummary(gate)}</p>
              </div>
            )}

            {/* 逐字段汇总（命中位置的跳转落点） */}
            <div className="border-border rounded-lg border">
              <p className="text-muted-foreground border-border text-label-12 border-b px-4 py-2">
                命中位置一览（点风险清单里的位置能跳到对应栏）
              </p>
              {fieldBuckets.map((bucket) => (
                <div
                  key={bucket.fieldKey}
                  id={fieldAnchorId(bucket.fieldKey)}
                  className="border-border flex flex-wrap items-center justify-between gap-2 border-b px-4 py-2 last:border-b-0"
                >
                  <span className="text-foreground text-label-13">{bucket.fieldLabel}</span>
                  <span className="text-muted-foreground text-label-12">
                    {bucket.count} 条（{bucket.blockingCount} 条阻断项）
                  </span>
                </div>
              ))}
            </div>

            {/* 阻断项区 */}
            <div className="border-destructive/40 rounded-lg border">
              <div className="border-destructive/40 bg-destructive/5 flex flex-wrap items-center justify-between gap-2 border-b px-4 py-3">
                <p className="text-destructive text-label-13 flex items-center gap-2 font-medium">
                  <ShieldAlert className="size-4" />
                  阻断项（{blockers.length}）—— 未修复不允许发布
                </p>
                <span className="text-muted-foreground text-label-12">
                  拦截在服务端（发布闸门 + 入队时的 DB 触发器），页面分区只是显示
                </span>
              </div>
              {blockers.length === 0 ? (
                <p className="text-muted-foreground text-label-12 px-4 py-3">
                  没有未修复的阻断项。
                </p>
              ) : (
                <ul className="divide-border divide-y">
                  {blockers.map((risk) => (
                    <li key={risk.riskId} className="space-y-2 px-4 py-3">
                      <RiskHeader risk={risk} />
                      <RiskDetails risk={risk} />
                      <div className="flex gap-2">
                        <Button
                          size="sm"
                          variant="secondary"
                          onClick={() => void decide([risk.riskId], 'RESOLVED')}
                        >
                          标记已修复
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => void decide([risk.riskId], 'IGNORED')}
                        >
                          忽略
                        </Button>
                      </div>
                    </li>
                  ))}
                </ul>
              )}
            </div>

            {/* 提醒项区 */}
            <div className="border-warning/40 rounded-lg border">
              <div className="border-warning/40 bg-warning-soft/40 flex flex-wrap items-center justify-between gap-2 border-b px-4 py-3">
                <p className="text-warning text-label-13 flex items-center gap-2 font-medium">
                  <TriangleAlert className="size-4" />
                  提醒项（{warnings.length}）
                </p>
                <span className="text-muted-foreground text-label-12">
                  提醒项不拦截发布，但建议处理
                </span>
              </div>
              {warnings.length === 0 ? (
                <p className="text-muted-foreground text-label-12 px-4 py-3">没有提醒项。</p>
              ) : (
                <ul className="divide-border divide-y">
                  {warnings.map((risk) => (
                    <li key={risk.riskId} className="space-y-2 px-4 py-3">
                      <RiskHeader risk={risk} />
                      <RiskDetails risk={risk} />
                    </li>
                  ))}
                </ul>
              )}
            </div>

            {/* 逐维度结论（UNKNOWN 单独成态） */}
            <div className="border-border rounded-lg border">
              <p className="text-muted-foreground border-border text-label-12 border-b px-4 py-2">
                逐维度结论（未能判定的维度不计入已判定，也不算通过）
              </p>
              {result.dimensions.map((dimension) => (
                <div
                  key={dimension.dimension}
                  className="border-border flex flex-wrap items-center justify-between gap-2 border-b px-4 py-2 last:border-b-0"
                >
                  <div className="min-w-0 space-y-1">
                    <p className="text-foreground text-label-13">
                      {dimension.label}
                      {dimension.ownerModule ? `（归属模块 ${dimension.ownerModule}）` : ''}
                    </p>
                    <p className="text-muted-foreground text-label-12">{dimension.note}</p>
                  </div>
                  <div className="flex items-center gap-2">
                    <Badge
                      tone={dimensionStateTone(dimension.state) === 'brand' ? 'success' : 'warning'}
                    >
                      {dimensionStateLabel(dimension.state)}
                    </Badge>
                    <span className="text-muted-foreground text-label-12">
                      {dimension.riskCount} 条
                    </span>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}
      </SectionCard>

      {/* ---- 扫描历史（真分页） ---- */}
      <SectionCard
        title="扫描历史"
        description="每一次预检都留痕：扫了哪份草稿、结论是什么、阻断项几条。"
      >
        {!scanRows || scanRows.items.length === 0 ? (
          <EmptyState
            icon={<ScanSearch className="text-muted-foreground size-8" />}
            title="还没有扫描记录"
            description="触发一次预检之后，这里会按时间倒序列出全部扫描。"
          />
        ) : (
          <div className="space-y-2">
            {scanRows.items.map((row) => (
              <div
                key={row.scanId}
                className="border-border flex flex-wrap items-center justify-between gap-3 rounded-lg border px-4 py-3"
              >
                <div className="min-w-0 space-y-1">
                  <p className="text-foreground text-label-13">
                    {row.platformCode} / {row.marketplaceCode} / {row.locale} ·{' '}
                    {new Date(row.scannedAt).toLocaleString('zh-CN')}
                  </p>
                  <p className="text-muted-foreground text-label-12">
                    触发来源 {row.triggerSource} · 风险 {row.riskCount} 条（阻断 {row.blockingCount}{' '}
                    ·提醒 {row.warningCount}）· 已判定维度 {row.evaluatedDimensionCount}/
                    {row.totalDimensionCount}
                  </p>
                </div>
                <div className="flex items-center gap-2">
                  <Badge
                    tone={conclusionTone(row.conclusion) === 'destructive' ? 'danger' : 'warning'}
                  >
                    {conclusionLabel(row.conclusion)}
                  </Badge>
                  <Button
                    size="sm"
                    variant="secondary"
                    onClick={() => void openScan(row.scanId, row.draftId)}
                  >
                    查看
                  </Button>
                </div>
              </div>
            ))}
            <p className="text-muted-foreground text-label-12">
              共 {scanRows.total} 条 · 第 {scanRows.page + 1} 页
              <Button
                variant="ghost"
                size="sm"
                className="ml-2"
                disabled={scanRows.page === 0}
                onClick={() => setScanPage((p) => Math.max(0, p - 1))}
              >
                上一页
              </Button>
              <Button
                variant="ghost"
                size="sm"
                disabled={(scanRows.page + 1) * PAGE_SIZE >= scanRows.total}
                onClick={() => setScanPage((p) => p + 1)}
              >
                下一页
              </Button>
            </p>
          </div>
        )}
      </SectionCard>

      {/* ---- 租户违禁词库 ---- */}
      <SectionCard
        title="租户违禁词库"
        description="本模块自己拥有的租户级词表：运营自己维护，带规则来源与建议替换。它不是 16.2.1.1 的受版本治理合规规则库。"
      >
        <div className="border-border mb-4 grid gap-3 rounded-lg border p-4 md:grid-cols-3">
          <label className="text-label-12 space-y-1">
            <span className="text-foreground">词</span>
            <Input
              value={wordDraft.word}
              onChange={(event) => setWordDraft({ ...wordDraft, word: event.target.value })}
              placeholder="如：最好"
              aria-label="词"
            />
          </label>
          <label className="text-label-12 space-y-1">
            <span className="text-foreground">类型</span>
            <select
              className="border-border bg-background text-label-13 h-9 w-full rounded-md border px-2"
              value={wordDraft.category}
              onChange={(event) => setWordDraft({ ...wordDraft, category: event.target.value })}
              aria-label="类型"
            >
              {['PROHIBITED', 'SENSITIVE', 'SUPERLATIVE'].map((value) => (
                <option key={value} value={value}>
                  {wordCategoryLabel(value)}
                </option>
              ))}
            </select>
          </label>
          <label className="text-label-12 space-y-1">
            <span className="text-foreground">处置</span>
            <select
              className="border-border bg-background text-label-13 h-9 w-full rounded-md border px-2"
              value={wordDraft.severity}
              onChange={(event) => setWordDraft({ ...wordDraft, severity: event.target.value })}
              aria-label="处置"
            >
              {['BLOCKING', 'WARNING'].map((value) => (
                <option key={value} value={value}>
                  {severityLabel(value)}
                </option>
              ))}
            </select>
          </label>
          <label className="text-label-12 space-y-1">
            <span className="text-foreground">风险等级</span>
            <select
              className="border-border bg-background text-label-13 h-9 w-full rounded-md border px-2"
              value={wordDraft.riskLevel}
              onChange={(event) => setWordDraft({ ...wordDraft, riskLevel: event.target.value })}
              aria-label="风险等级"
            >
              {['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'].map((value) => (
                <option key={value} value={value}>
                  {riskLevelLabel(value)}
                </option>
              ))}
            </select>
          </label>
          <label className="text-label-12 space-y-1 md:col-span-2">
            <span className="text-foreground">规则来源（必填）</span>
            <Input
              value={wordDraft.ruleSource}
              onChange={(event) => setWordDraft({ ...wordDraft, ruleSource: event.target.value })}
              placeholder="这条词是谁定的、依据什么"
              aria-label="规则来源"
            />
          </label>
          <label className="text-label-12 space-y-1">
            <span className="text-foreground">建议替换（必填）</span>
            <Input
              value={wordDraft.suggestion}
              onChange={(event) => setWordDraft({ ...wordDraft, suggestion: event.target.value })}
              placeholder="命中之后应该改成什么"
              aria-label="建议替换"
            />
          </label>
          <div className="flex items-end md:col-span-3">
            <Button onClick={() => void saveWord(wordDraft)} disabled={busy}>
              新增词条
            </Button>
          </div>
        </div>

        {words.length === 0 ? (
          <EmptyState
            icon={<ShieldAlert className="text-muted-foreground size-8" />}
            title="词库还是空的"
            description="这个租户还没有登记任何违禁词 / 敏感词 / 极限词。在上面加一条，扫描就会按它命中。"
          />
        ) : (
          <div className="space-y-2">
            {words.map((word) => (
              <div
                key={word.wordId}
                className="border-border flex flex-wrap items-center justify-between gap-3 rounded-lg border px-4 py-3"
              >
                <div className="min-w-0 space-y-1">
                  <p className="text-foreground text-label-13">
                    {word.displayWord}
                    <span className="text-muted-foreground text-label-12 ml-2">
                      {wordCategoryLabel(word.category)} · {severityLabel(word.severity)} ·{' '}
                      {riskLevelLabel(word.riskLevel)}
                    </span>
                  </p>
                  <p className="text-muted-foreground text-label-12">
                    规则来源：{word.ruleSource} · 建议替换：{word.suggestion}
                    {word.platformCode ? ` · 平台 ${word.platformCode}` : ' · 全部平台'}
                  </p>
                </div>
                <div className="flex items-center gap-2">
                  <Button
                    size="sm"
                    variant="secondary"
                    onClick={() => void toggleWord(word.wordId, !word.enabled)}
                  >
                    {word.enabled ? '停用' : '启用'}
                  </Button>
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() => void removeWord(word.wordId)}
                    aria-label="删除"
                  >
                    <Trash2 className="size-4" />
                  </Button>
                </div>
              </div>
            ))}
            <p className="text-muted-foreground text-label-12">
              共 {wordTotal} 条 · 第 {wordPage + 1} 页
              <Button
                variant="ghost"
                size="sm"
                className="ml-2"
                disabled={wordPage === 0}
                onClick={() => setWordPage((p) => Math.max(0, p - 1))}
              >
                上一页
              </Button>
              <Button
                variant="ghost"
                size="sm"
                disabled={(wordPage + 1) * PAGE_SIZE >= wordTotal}
                onClick={() => setWordPage((p) => p + 1)}
              >
                下一页
              </Button>
            </p>
          </div>
        )}
      </SectionCard>
    </div>
  );
}

/** 风险标题行：等级徽章 + 处置徽章 + 命中位置（可跳转）+ 维度。 */
function RiskHeader({ risk }: { risk: ListingComplianceRiskRow }) {
  return (
    <div className="flex flex-wrap items-center gap-2">
      <Badge tone={riskLevelTone(risk.riskLevel) === 'destructive' ? 'danger' : 'warning'}>
        风险等级 {riskLevelLabel(risk.riskLevel)}
      </Badge>
      <Badge tone={severityTone(risk.severity) === 'destructive' ? 'danger' : 'warning'}>
        {severityLabel(risk.severity)}
      </Badge>
      <a
        href={riskLocationHref(risk)}
        className="text-brand text-label-12 underline underline-offset-2"
      >
        命中位置：{riskLocationLabel(risk)}
      </a>
      <span className="text-muted-foreground text-label-12">{risk.dimensionLabel}</span>
    </div>
  );
}

/** 风险四要素：说明、命中的原文、规则来源、建议替换。 */
function RiskDetails({ risk }: { risk: ListingComplianceRiskRow }) {
  return (
    <div className="text-muted-foreground text-label-12 space-y-1">
      <p className="text-foreground text-label-13">{risk.message}</p>
      <p>
        命中内容：<span className="text-foreground">{matchedTextLabel(risk)}</span>
      </p>
      <p>
        规则来源：<span className="text-foreground">{risk.ruleSource}</span>
        {risk.ruleOwnerModule ? `（归属模块 ${risk.ruleOwnerModule}）` : ''}
      </p>
      <p>
        建议替换：<span className="text-foreground">{risk.suggestion}</span>
      </p>
    </div>
  );
}
