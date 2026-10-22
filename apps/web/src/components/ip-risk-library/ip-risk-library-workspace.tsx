'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import {
  CircleAlert,
  FileUp,
  Loader2,
  RefreshCw,
  Scale,
  ScrollText,
  ShieldAlert,
  TriangleAlert,
} from 'lucide-react';
import {
  IMPORT_COLUMNS,
  RIGHT_TYPE_OPTIONS,
  RISK_LEVEL_OPTIONS,
  STATUS_OPTIONS,
  brandText,
  createEntry,
  emptyState,
  emptyStateKind,
  fetchBrands,
  fetchCapabilities,
  fetchEntries,
  fetchEntry,
  fetchImportBatch,
  fetchImports,
  fetchPlatformSummary,
  fetchPlatforms,
  importEntries,
  importOutcomeLabel,
  importOutcomeTone,
  matchTermsText,
  parseImportText,
  platformText,
  riskLevelTone,
  sourceLabel,
  updateEntryStatus,
  type IpRiskLibraryBrandSet,
  type IpRiskLibraryCapabilities,
  type IpRiskLibraryEntry,
  type IpRiskLibraryEntryDetail,
  type IpRiskLibraryEntryPage,
  type IpRiskLibraryImportDetail,
  type IpRiskLibraryImportPage,
  type IpRiskLibraryImportResult,
  type IpRiskLibraryPlatform,
  type IpRiskLibraryPlatformSummaryPage,
} from './ip-risk-library-model';

/**
 * 16.4.1.1 侵权库 工作区。
 *
 * 蓝图：「维护知识产权风险库：品牌商标、专利、版权、肖像权，登记风险等级与适用平台。
 * 输入：侵权条目登记；批量导入。输出：侵权库记录。关系：上游 = 3.3.2 品牌；下游 = 16.4.1.2 检测。
 * **侵权检测是刊登前拦截而非事后申诉**，成本差一个数量级。」
 *
 * 🔴 页面上的六条纪律：
 *
 * 1. **权利类型与风险等级是受控值域** —— 下拉只给后端说的那几个，**不给自由文本框**。
 * 2. **适用平台是多选**（多对多），不是一个单选下拉。
 * 3. **匹配特征是多值** —— 品牌名（可空）+ 关键词 / 短语集合 + 可选标识符特征。
 *    页面**只登记**，**不做任何比对**（比对归 16.4.1.2，那个模块**未交付**）。
 * 4. **停用必须写理由**；停用后条目内容冻结，**重新启用**才能改。
 * 5. **没有删除入口** —— 条目只停用，历史记录保留。
 * 6. **未交付的下游灰掉**：16.4.1.2 检测与预警当前**未交付**，
 *    界面上如实标出，**不编**「立即检测 / 放行」这类入口。
 */
const PAGE_SIZE = 20;

export function IpRiskLibraryWorkspace() {
  const [caps, setCaps] = useState<IpRiskLibraryCapabilities | null>(null);
  const [platforms, setPlatforms] = useState<IpRiskLibraryPlatform[]>([]);
  const [entries, setEntries] = useState<IpRiskLibraryEntryPage | null>(null);
  const [summary, setSummary] = useState<IpRiskLibraryPlatformSummaryPage | null>(null);
  const [imports, setImports] = useState<IpRiskLibraryImportPage | null>(null);

  const [rightType, setRightType] = useState('');
  const [riskLevel, setRiskLevel] = useState('');
  const [status, setStatus] = useState('');
  const [platformCode, setPlatformCode] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(0);

  const [detail, setDetail] = useState<IpRiskLibraryEntryDetail | null>(null);
  const [importDetail, setImportDetail] = useState<IpRiskLibraryImportDetail | null>(null);
  const [brands, setBrands] = useState<IpRiskLibraryBrandSet | null>(null);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // ---- 登记条目 ----
  const [createOpen, setCreateOpen] = useState(false);
  const [entryCode, setEntryCode] = useState('');
  const [newRightType, setNewRightType] = useState('TRADEMARK');
  const [newRiskLevel, setNewRiskLevel] = useState('HIGH');
  const [title, setTitle] = useState('');
  const [rightsHolder, setRightsHolder] = useState('');
  const [brandId, setBrandId] = useState('');
  const [brandName, setBrandName] = useState('');
  const [matchPattern, setMatchPattern] = useState('');
  const [selectedPlatforms, setSelectedPlatforms] = useState<string[]>([]);
  const [termsText, setTermsText] = useState('');

  // ---- 停用 ----
  const [disableFor, setDisableFor] = useState<IpRiskLibraryEntry | null>(null);
  const [disableReason, setDisableReason] = useState('');

  // ---- 批量导入 ----
  const [importOpen, setImportOpen] = useState(false);
  const [importSource, setImportSource] = useState('');
  const [importText, setImportText] = useState('');
  const [importResult, setImportResult] = useState<IpRiskLibraryImportResult | null>(null);

  const loadEntries = useCallback(
    async (target: number) => {
      const result = await fetchEntries(target, PAGE_SIZE, {
        rightType,
        riskLevel,
        status,
        platformCode,
        keyword,
      });
      setEntries(result);
      return result;
    },
    [keyword, platformCode, rightType, riskLevel, status],
  );

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capsResult, platformResult, entryResult, summaryResult, importResult] =
        await Promise.all([
          fetchCapabilities(),
          fetchPlatforms(),
          loadEntries(page),
          fetchPlatformSummary(),
          fetchImports(0, 10),
        ]);
      setCaps(capsResult);
      setPlatforms(platformResult);
      setEntries(entryResult);
      setSummary(summaryResult);
      setImports(importResult);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [loadEntries, page]);

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const run = useCallback(async (work: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await work();
    } catch (e) {
      setError(e instanceof Error ? e.message : '操作失败，请重试');
    } finally {
      setBusy(false);
    }
  }, []);

  const openDetail = useCallback(
    (entryId: string) =>
      run(async () => {
        setDetail(await fetchEntry(entryId));
      }),
    [run],
  );

  const openImportBatch = useCallback(
    (batchId: string) =>
      run(async () => {
        setImportDetail(await fetchImportBatch(batchId));
      }),
    [run],
  );

  const loadBrands = useCallback(
    (keyword: string) =>
      run(async () => {
        setBrands(await fetchBrands(keyword));
      }),
    [run],
  );

  const parsedImport = useMemo(() => parseImportText(importText), [importText]);

  const doCreate = useCallback(
    () =>
      run(async () => {
        const created = await createEntry({
          entryCode: entryCode.trim(),
          rightType: newRightType,
          riskLevel: newRiskLevel,
          title: title.trim(),
          rightsHolder: rightsHolder.trim(),
          brandId: brandId || null,
          brandName: brandName.trim() || null,
          matchPattern: matchPattern.trim() || null,
          note: '',
          platformCodes: selectedPlatforms,
          matchTerms: termsText
            .split(/[\n,，]/)
            .map((term) => term.trim())
            .filter((term) => term.length > 0)
            .map((term) => ({ term, termKind: 'KEYWORD' })),
        });
        setCreateOpen(false);
        setEntryCode('');
        setTitle('');
        setRightsHolder('');
        setBrandId('');
        setBrandName('');
        setMatchPattern('');
        setSelectedPlatforms([]);
        setTermsText('');
        setPage(0);
        await loadEntries(0);
        setDetail(await fetchEntry(created.entry.entryId));
      }),
    [
      brandId,
      brandName,
      entryCode,
      loadEntries,
      matchPattern,
      newRightType,
      newRiskLevel,
      rightsHolder,
      run,
      selectedPlatforms,
      termsText,
      title,
    ],
  );

  const doDisable = useCallback(
    () =>
      run(async () => {
        if (!disableFor) return;
        const updated = await updateEntryStatus(
          disableFor.entryId,
          'DISABLED',
          disableReason.trim(),
        );
        setDisableFor(null);
        setDisableReason('');
        await loadEntries(page);
        setDetail(updated);
      }),
    [disableFor, disableReason, loadEntries, page, run],
  );

  const doEnable = useCallback(
    (entry: IpRiskLibraryEntry) =>
      run(async () => {
        const updated = await updateEntryStatus(entry.entryId, 'ACTIVE', '');
        await loadEntries(page);
        setDetail(updated);
      }),
    [loadEntries, page, run],
  );

  const doImport = useCallback(
    () =>
      run(async () => {
        const result = await importEntries({
          sourceName: importSource.trim() || '手工粘贴',
          rows: parsedImport.rows,
        });
        setImportResult(result);
        setImportText('');
        await Promise.all([loadEntries(page), fetchImports(0, 10).then(setImports)]);
      }),
    [importSource, loadEntries, page, parsedImport.rows, run],
  );

  const filtered = !!(rightType || riskLevel || status || platformCode || keyword);
  const empty = emptyState(emptyStateKind(entries?.total ?? 0, filtered));

  if (loading && !caps) {
    return (
      <div className="flex items-center gap-2 py-10">
        <Loader2 className="text-muted-foreground size-4 animate-spin" />
        <span className="text-label-13">正在加载侵权库…</span>
      </div>
    );
  }

  if (error && !caps) {
    return (
      <SectionCard title="侵权库">
        <EmptyState
          icon={<TriangleAlert className="text-destructive size-8" />}
          title="侵权库加载失败"
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
        title="侵权库"
        description="知识产权风险库：品牌商标 / 专利 / 版权 / 肖像权，登记风险等级、适用平台与匹配特征。本页只做登记与查询，不做检测。"
        actions={
          <>
            <Button variant="outline" onClick={() => void refresh()} disabled={busy}>
              <RefreshCw className="mr-2 size-4" />
              刷新
            </Button>
            <Button variant="outline" onClick={() => setImportOpen(true)} disabled={busy}>
              <FileUp className="mr-2 size-4" />
              批量导入
            </Button>
            <Button onClick={() => setCreateOpen(true)} disabled={busy}>
              登记条目
            </Button>
          </>
        }
      />

      {/* ---- 统计卡（服务端算的计数，覆盖整个筛选结果集） ---- */}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="条目总数"
          value={entries?.total ?? 0}
          polarity="neutral"
          icon={<ScrollText className="size-4" />}
        />
        <StatCard
          label="启用中"
          value={entries?.activeCount ?? 0}
          polarity="neutral"
          icon={<ShieldAlert className="size-4" />}
        />
        <StatCard
          label="已停用"
          value={entries?.disabledCount ?? 0}
          polarity="neutral"
          icon={<CircleAlert className="size-4" />}
        />
        <StatCard
          label="高风险"
          value={entries?.riskLevelCounts?.HIGH ?? 0}
          polarity="negative"
          icon={<Scale className="size-4" />}
        />
      </div>

      {error && (
        <div className="border-destructive-soft bg-destructive-soft text-destructive text-copy-13 rounded-md border px-4 py-3">
          {error}
        </div>
      )}

      {/* ---- 能力声明：未交付的下游灰掉 ---- */}
      <SectionCard
        title="能力声明"
        description="哪些下游真的可用、哪些还没交付 —— 逐条可查，不是文档里的一句话。"
      >
        <ul className="space-y-2">
          {(caps?.gaps ?? []).map((gap) => (
            <li
              key={gap.moduleId}
              className={
                gap.available
                  ? 'border-border flex items-start gap-3 rounded-md border px-3 py-2'
                  : 'border-border bg-muted/40 flex items-start gap-3 rounded-md border px-3 py-2'
              }
            >
              <Badge tone={gap.available ? 'success' : 'neutral'} className="mt-0.5 shrink-0">
                {gap.available ? '已交付' : '未交付'}
              </Badge>
              <div className="min-w-0">
                <div className="text-copy-13 text-foreground font-medium">
                  {gap.moduleId} · {gap.moduleName}
                </div>
                <div className="text-copy-13 text-muted-foreground">{gap.impact}</div>
                {!gap.available && (
                  <div className="text-copy-12 text-muted-foreground mt-1">
                    归属模块：{gap.ownerModule}
                  </div>
                )}
              </div>
            </li>
          ))}
        </ul>
        {caps && (
          <ul className="text-copy-12 text-muted-foreground mt-4 space-y-1">
            <li>· {caps.rightTypeNote}</li>
            <li>· {caps.riskLevelNote}</li>
            <li>· {caps.platformNote}</li>
            <li>· {caps.matchFeatureNote}</li>
            <li>· {caps.lifecycleNote}</li>
            <li>· {caps.importNote}</li>
            <li>· {caps.upstreamNote}</li>
            <li>· {caps.downstreamNote}</li>
          </ul>
        )}
      </SectionCard>

      {/* ---- 平台风险汇总（「这个平台上有哪些高风险条目」的反查） ---- */}
      <SectionCard
        title="平台风险汇总"
        description="按适用平台统计（适用平台是多对多 —— 同一条条目会出现在它登记过的每个平台上）。"
      >
        {summary && summary.items.length > 0 ? (
          <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
            {summary.items.map((row) => (
              <button
                key={row.platformCode}
                type="button"
                onClick={() => {
                  setPlatformCode(row.platformCode);
                  setPage(0);
                }}
                className="border-border hover:border-brand rounded-md border px-3 py-2 text-left"
              >
                <div className="text-copy-13 text-foreground font-medium">
                  {row.platformName}（{row.platformCode}）
                </div>
                <div className="text-copy-12 text-muted-foreground mt-1">
                  高风险 {row.high} · 中风险 {row.medium} · 低风险 {row.low} · 已停用 {row.disabled}
                </div>
              </button>
            ))}
          </div>
        ) : (
          <p className="text-copy-13 text-muted-foreground">平台目录里还没有可统计的平台。</p>
        )}
      </SectionCard>

      {/* ---- 筛选 ---- */}
      <SectionCard
        title="侵权库条目"
        description="真分页 + 真筛选；平台维度是多对多，命中该平台的条目不限只登记了它一个平台。"
      >
        <div className="mb-4 flex flex-wrap items-center gap-2">
          <select
            className="border-input bg-background text-copy-13 h-9 rounded-md border px-2"
            value={rightType}
            onChange={(e) => {
              setRightType(e.target.value);
              setPage(0);
            }}
          >
            {RIGHT_TYPE_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
          <select
            className="border-input bg-background text-copy-13 h-9 rounded-md border px-2"
            value={riskLevel}
            onChange={(e) => {
              setRiskLevel(e.target.value);
              setPage(0);
            }}
          >
            {RISK_LEVEL_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
          <select
            className="border-input bg-background text-copy-13 h-9 rounded-md border px-2"
            value={status}
            onChange={(e) => {
              setStatus(e.target.value);
              setPage(0);
            }}
          >
            {STATUS_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
          <select
            className="border-input bg-background text-copy-13 h-9 rounded-md border px-2"
            value={platformCode}
            onChange={(e) => {
              setPlatformCode(e.target.value);
              setPage(0);
            }}
          >
            <option value="">全部平台</option>
            {platforms.map((platform) => (
              <option key={platform.platformCode} value={platform.platformCode}>
                {platform.platformName}（{platform.platformCode}）
              </option>
            ))}
          </select>
          <Input
            className="h-9 w-56"
            placeholder="关键字：编码 / 标题 / 品牌 / 匹配特征"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                setPage(0);
                void loadEntries(0);
              }
            }}
          />
          <Button
            variant="secondary"
            onClick={() => {
              setRightType('');
              setRiskLevel('');
              setStatus('');
              setPlatformCode('');
              setKeyword('');
              setPage(0);
              void loadEntries(0);
            }}
          >
            重置筛选
          </Button>
        </div>

        {entries && entries.items.length === 0 ? (
          <EmptyState
            icon={<ScrollText className="text-muted-foreground size-8" />}
            title={empty.title}
            description={empty.description}
            action={
              filtered ? (
                <Button variant="secondary" onClick={() => void loadEntries(page)}>
                  重新加载
                </Button>
              ) : (
                <Button onClick={() => setCreateOpen(true)}>登记第一条</Button>
              )
            }
          />
        ) : (
          <ul className="space-y-2">
            {(entries?.items ?? []).map((entry) => (
              <li
                key={entry.entryId}
                className="border-border flex flex-wrap items-start gap-3 rounded-md border px-3 py-2"
              >
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={riskLevelTone(entry.riskLevel)}>{entry.riskLevelLabel}</Badge>
                    <span className="text-copy-13 text-foreground font-medium">{entry.title}</span>
                    {entry.status === 'DISABLED' && <Badge tone="neutral">已停用</Badge>}
                  </div>
                  <div className="text-copy-12 text-muted-foreground mt-1">
                    {entry.entryCode} · {entry.rightTypeLabel} · 平台{' '}
                    {platformText(entry.platformCodes)} · 匹配 {matchTermsText(entry.matchTerms)}
                  </div>
                </div>
                <div className="flex shrink-0 items-center gap-2">
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => void openDetail(entry.entryId)}
                  >
                    详情
                  </Button>
                  {entry.status === 'ACTIVE' ? (
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => {
                        setDisableFor(entry);
                        setDisableReason('');
                      }}
                    >
                      停用
                    </Button>
                  ) : (
                    <Button variant="outline" size="sm" onClick={() => void doEnable(entry)}>
                      重新启用
                    </Button>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}

        {entries && entries.total > 0 && (
          <div className="text-copy-12 text-muted-foreground mt-4 flex items-center justify-between">
            <span>
              共 {entries.total} 条（第 {entries.page + 1} 页，每页 {entries.size} 条）
            </span>
            <div className="flex items-center gap-2">
              <Button
                variant="outline"
                size="sm"
                disabled={page === 0}
                onClick={() => {
                  const target = page - 1;
                  setPage(target);
                  void loadEntries(target);
                }}
              >
                上一页
              </Button>
              <Button
                variant="outline"
                size="sm"
                disabled={(page + 1) * entries.size >= entries.total}
                onClick={() => {
                  const target = page + 1;
                  setPage(target);
                  void loadEntries(target);
                }}
              >
                下一页
              </Button>
            </div>
          </div>
        )}
      </SectionCard>

      {/* ---- 导入批次 ---- */}
      <SectionCard
        title="批量导入记录"
        description="逐行结果落库、可随时回查。重复导入同一批不会产生重复条目（跳过的那几行也算结果）。"
      >
        {imports && imports.items.length > 0 ? (
          <ul className="space-y-2">
            {imports.items.map((batch) => (
              <li
                key={batch.batchId}
                className="border-border flex flex-wrap items-center gap-3 rounded-md border px-3 py-2"
              >
                <div className="min-w-0 flex-1">
                  <div className="text-copy-13 text-foreground font-medium">
                    {batch.sourceName || '（未命名来源）'}
                  </div>
                  <div className="text-copy-12 text-muted-foreground mt-1">
                    共 {batch.totalRows} 行 · 写入 {batch.insertedRows} · 跳过 {batch.skippedRows} ·
                    失败 {batch.failedRows}
                  </div>
                </div>
                <Badge tone={batch.status === 'COMPLETED' ? 'success' : 'warning'}>
                  {batch.status === 'COMPLETED' ? '全部成功' : '部分失败'}
                </Badge>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => void openImportBatch(batch.batchId)}
                >
                  逐行结果
                </Button>
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-copy-13 text-muted-foreground">还没有导入记录。</p>
        )}
      </SectionCard>

      {/* ---- 登记条目 ---- */}
      <Modal open={createOpen} onOpenChange={setCreateOpen}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>登记侵权条目</ModalTitle>
            <ModalDescription>
              权利类型与风险等级是**受控值域**（下拉里给的就是全部合法值）；适用平台**可多选**；
              匹配特征**可填多个**。本页**只登记**，不做任何比对 —— 比对归 16.4.1.2（**未交付**）。
            </ModalDescription>
          </ModalHeader>
          <div className="space-y-3">
            <div className="grid gap-3 sm:grid-cols-2">
              <label className="text-copy-13 block">
                <span className="mb-1 block">条目编码（幂等锚点）</span>
                <Input value={entryCode} onChange={(e) => setEntryCode(e.target.value)} />
              </label>
              <label className="text-copy-13 block">
                <span className="mb-1 block">权利类型</span>
                <select
                  className="border-input bg-background text-copy-13 h-9 w-full rounded-md border px-2"
                  value={newRightType}
                  onChange={(e) => setNewRightType(e.target.value)}
                >
                  {RIGHT_TYPE_OPTIONS.filter((o) => o.value !== '').map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
              </label>
            </div>
            <div className="grid gap-3 sm:grid-cols-2">
              <label className="text-copy-13 block">
                <span className="mb-1 block">风险等级</span>
                <select
                  className="border-input bg-background text-copy-13 h-9 w-full rounded-md border px-2"
                  value={newRiskLevel}
                  onChange={(e) => setNewRiskLevel(e.target.value)}
                >
                  {RISK_LEVEL_OPTIONS.filter((o) => o.value !== '').map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="text-copy-13 block">
                <span className="mb-1 block">标题（权利人 / 权利名称）</span>
                <Input value={title} onChange={(e) => setTitle(e.target.value)} />
              </label>
            </div>
            <div className="grid gap-3 sm:grid-cols-2">
              <label className="text-copy-13 block">
                <span className="mb-1 block">权利人（可空）</span>
                <Input value={rightsHolder} onChange={(e) => setRightsHolder(e.target.value)} />
              </label>
              <label className="text-copy-13 block">
                <span className="mb-1 block">品牌名（可空 —— 有些专利 / 版权没有品牌名）</span>
                <Input value={brandName} onChange={(e) => setBrandName(e.target.value)} />
              </label>
            </div>
            <div className="grid gap-3 sm:grid-cols-2">
              <label className="text-copy-13 block">
                <span className="mb-1 block">关联 3.3.2 品牌（上游只读）</span>
                <div className="flex gap-2">
                  <select
                    className="border-input bg-background text-copy-13 h-9 w-full rounded-md border px-2"
                    value={brandId}
                    onChange={(e) => {
                      const next = e.target.value;
                      setBrandId(next);
                      const picked = brands?.items.find((item) => item.brandId === next);
                      if (picked) setBrandName(picked.name);
                    }}
                  >
                    <option value="">不关联品牌</option>
                    {(brands?.items ?? []).map((item) => (
                      <option key={item.brandId} value={item.brandId}>
                        {item.name}
                      </option>
                    ))}
                  </select>
                  <Button variant="outline" onClick={() => void loadBrands(brandName)}>
                    查品牌
                  </Button>
                </div>
                {brands && !brands.available && (
                  <span className="text-copy-12 text-destructive mt-1 block">
                    上游读不到品牌档案：{brands.note}
                  </span>
                )}
              </label>
              <label className="text-copy-13 block">
                <span className="mb-1 block">标识符 / 外观特征（可空）</span>
                <Input
                  value={matchPattern}
                  onChange={(e) => setMatchPattern(e.target.value)}
                  placeholder="如：商标注册号 / 版权作品名"
                />
              </label>
            </div>
            <div>
              <span className="text-copy-13 mb-1 block">
                适用平台（**多选** —— 一条条目常在多个平台上都要拦）
              </span>
              <div className="flex flex-wrap gap-2">
                {platforms.map((platform) => {
                  const checked = selectedPlatforms.includes(platform.platformCode);
                  return (
                    <button
                      key={platform.platformCode}
                      type="button"
                      onClick={() =>
                        setSelectedPlatforms((current) =>
                          checked
                            ? current.filter((code) => code !== platform.platformCode)
                            : [...current, platform.platformCode],
                        )
                      }
                      className={
                        checked
                          ? 'border-brand bg-brand-soft text-brand text-copy-12 rounded-md border px-2 py-1'
                          : 'border-border text-copy-12 rounded-md border px-2 py-1'
                      }
                    >
                      {platform.platformName}
                    </button>
                  );
                })}
              </div>
            </div>
            <label className="text-copy-13 block">
              <span className="mb-1 block">匹配特征（**多值**，逗号或换行分隔）</span>
              <textarea
                className="border-input bg-background text-copy-13 min-h-20 w-full rounded-md border px-3 py-2"
                value={termsText}
                onChange={(e) => setTermsText(e.target.value)}
                placeholder="如：nike, nike sb, 耐克"
              />
            </label>
          </div>
          <ModalFooter>
            <Button variant="secondary" onClick={() => setCreateOpen(false)}>
              取消
            </Button>
            <Button
              disabled={busy || !entryCode.trim() || !title.trim()}
              onClick={() => void doCreate()}
            >
              登记
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---- 条目详情 ---- */}
      <Modal open={!!detail} onOpenChange={(open) => !open && setDetail(null)}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>{detail?.entry.entryCode ?? '条目详情'}</ModalTitle>
            <ModalDescription>
              {detail?.entry.rightTypeLabel} · {detail?.entry.riskLevelLabel} ·{' '}
              {detail?.entry.statusLabel}
            </ModalDescription>
          </ModalHeader>
          {detail && (
            <div className="text-copy-13 space-y-3">
              <div>
                <span className="text-muted-foreground">标题：</span>
                {detail.entry.title}
              </div>
              <div>
                <span className="text-muted-foreground">权利人：</span>
                {detail.entry.rightsHolder || '（未登记）'}
              </div>
              <div>
                <span className="text-muted-foreground">品牌：</span>
                {brandText(detail.entry)}
              </div>
              <div>
                <span className="text-muted-foreground">标识符 / 外观特征：</span>
                {detail.entry.matchPattern || '（未登记）'}
              </div>
              <div>
                <span className="text-muted-foreground">适用平台（多值）：</span>
                {platformText(detail.entry.platformCodes)}
              </div>
              <div>
                <span className="text-muted-foreground">匹配特征（多值）：</span>
                {detail.matchTerms.length === 0
                  ? '未登记匹配特征'
                  : detail.matchTerms
                      .map((term) => `${term.term}（${term.termKindLabel}）`)
                      .join('、')}
              </div>
              <div>
                <span className="text-muted-foreground">来源：</span>
                {sourceLabel(detail.entry.source)}
              </div>
              {detail.entry.status === 'DISABLED' && (
                <div className="border-destructive-soft bg-destructive-soft text-destructive rounded-md border px-3 py-2">
                  已停用：{detail.entry.disableReason || '（未写理由）'}
                  <div className="text-copy-12 mt-1">
                    停用 =
                    不再参与未来的检测，但**历史记录保留**；停用后条目内容冻结，重新启用才能改。
                  </div>
                </div>
              )}
              <div className="text-copy-12 text-muted-foreground">{detail.note}</div>
            </div>
          )}
          <ModalFooter>
            <Button variant="secondary" onClick={() => setDetail(null)}>
              关闭
            </Button>
            {detail?.entry.status === 'ACTIVE' ? (
              <Button
                variant="destructive"
                onClick={() => {
                  setDisableFor(detail.entry);
                  setDisableReason('');
                }}
              >
                停用
              </Button>
            ) : (
              detail && (
                <Button variant="outline" onClick={() => void doEnable(detail.entry)}>
                  重新启用
                </Button>
              )
            )}
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---- 停用（必写理由） ---- */}
      <Modal open={!!disableFor} onOpenChange={(open) => !open && setDisableFor(null)}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>停用侵权条目</ModalTitle>
            <ModalDescription>
              🔴 停用**必须写理由**（没有理由的停用等于「随手关掉」，事后没人说得清为什么）。 停用 =
              不再参与未来的检测，但**历史记录保留**；条目**不可物理删除**。
            </ModalDescription>
          </ModalHeader>
          <label className="text-copy-13 block">
            <span className="mb-1 block">停用理由（必填）</span>
            <textarea
              className="border-input bg-background text-copy-13 min-h-20 w-full rounded-md border px-3 py-2"
              value={disableReason}
              onChange={(e) => setDisableReason(e.target.value)}
              placeholder="如：品牌方已发函，暂缓上架"
            />
          </label>
          <ModalFooter>
            <Button variant="secondary" onClick={() => setDisableFor(null)}>
              取消
            </Button>
            <Button
              variant="destructive"
              disabled={busy || !disableReason.trim()}
              onClick={() => void doDisable()}
            >
              确认停用
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---- 批量导入 ---- */}
      <Modal open={importOpen} onOpenChange={setImportOpen}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>批量导入侵权条目</ModalTitle>
            <ModalDescription>
              每行一条，管道分隔，列序：{IMPORT_COLUMNS.join(' | ')}。空行与 <code>#</code>{' '}
              开头的注释行忽略。 🔴 导入是**幂等**的（条目编码已存在 ⇒
              跳过，不产生重复）；失败行**不回滚**已成功的行。
            </ModalDescription>
          </ModalHeader>
          <div className="space-y-3">
            <label className="text-copy-13 block">
              <span className="mb-1 block">来源名（文件名 / 来源系统）</span>
              <Input
                value={importSource}
                onChange={(e) => setImportSource(e.target.value)}
                placeholder="如：brand-risk-2026.csv"
              />
            </label>
            <label className="text-copy-13 block">
              <span className="mb-1 block">导入内容</span>
              <textarea
                className="border-input bg-background text-copy-12 min-h-40 w-full rounded-md border px-3 py-2 font-mono"
                value={importText}
                onChange={(e) => setImportText(e.target.value)}
                placeholder={
                  'TM-1|TRADEMARK|HIGH|Nike 商标|权利人甲|BrandX|注册号 123|AMAZON,EBAY|nike,耐克'
                }
              />
            </label>
            {parsedImport.problems.length > 0 && (
              <div className="border-destructive-soft bg-destructive-soft text-destructive rounded-md border px-3 py-2">
                <div className="text-copy-13 font-medium">
                  本地解析发现 {parsedImport.problems.length} 行有问题（提交后也会逐行报错）：
                </div>
                <ul className="text-copy-12 mt-1 list-disc pl-4">
                  {parsedImport.problems.slice(0, 10).map((problem) => (
                    <li key={`${problem.lineNo}-${problem.message}`}>{problem.message}</li>
                  ))}
                </ul>
              </div>
            )}
            {parsedImport.rows.length > 0 && (
              <div className="text-copy-12 text-muted-foreground">
                可导入 {parsedImport.rows.length} 行（有问题的那几行不会被提交）。
              </div>
            )}
            {importResult && (
              <div className="space-y-2">
                <div className="text-copy-13">
                  共 {importResult.totalRows} 行 · 写入 {importResult.insertedRows} · 跳过{' '}
                  {importResult.skippedRows} · 失败 {importResult.failedRows}
                </div>
                <ul className="max-h-60 space-y-1 overflow-y-auto">
                  {importResult.rows.map((row) => (
                    <li
                      key={row.rowNo}
                      className="border-border flex flex-wrap items-center gap-2 rounded-md border px-2 py-1"
                    >
                      <Badge tone={importOutcomeTone(row.outcome)}>
                        {importOutcomeLabel(row.outcome)}
                      </Badge>
                      <span className="text-copy-12">第 {row.rowNo} 行</span>
                      <span className="text-copy-12 text-muted-foreground">{row.entryCode}</span>
                      {row.errorMessage && (
                        <span className="text-copy-12 text-destructive">
                          {row.errorField ? `${row.errorField}：` : ''}
                          {row.errorMessage}
                        </span>
                      )}
                    </li>
                  ))}
                </ul>
                <div className="text-copy-12 text-muted-foreground">{importResult.note}</div>
              </div>
            )}
          </div>
          <ModalFooter>
            <Button variant="secondary" onClick={() => setImportOpen(false)}>
              关闭
            </Button>
            <Button
              disabled={busy || parsedImport.rows.length === 0}
              onClick={() => void doImport()}
            >
              导入 {parsedImport.rows.length} 行
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---- 导入批次逐行结果 ---- */}
      <Modal open={!!importDetail} onOpenChange={(open) => !open && setImportDetail(null)}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>{importDetail?.batch.sourceName || '导入批次'}</ModalTitle>
            <ModalDescription>
              共 {importDetail?.batch.totalRows ?? 0} 行 · 写入{' '}
              {importDetail?.batch.insertedRows ?? 0} · 跳过 {importDetail?.batch.skippedRows ?? 0}{' '}
              · 失败 {importDetail?.batch.failedRows ?? 0}
            </ModalDescription>
          </ModalHeader>
          <ul className="max-h-96 space-y-1 overflow-y-auto">
            {(importDetail?.rows ?? []).map((row) => (
              <li
                key={row.rowNo}
                className="border-border flex flex-wrap items-center gap-2 rounded-md border px-2 py-1"
              >
                <Badge tone={importOutcomeTone(row.outcome)}>
                  {importOutcomeLabel(row.outcome)}
                </Badge>
                <span className="text-copy-12">第 {row.rowNo} 行</span>
                <span className="text-copy-12 text-muted-foreground">{row.entryCode}</span>
                {row.errorMessage && (
                  <span className="text-copy-12 text-destructive">
                    {row.errorField ? `${row.errorField}：` : ''}
                    {row.errorMessage}
                  </span>
                )}
              </li>
            ))}
          </ul>
          <ModalFooter>
            <Button variant="secondary" onClick={() => setImportDetail(null)}>
              关闭
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}
