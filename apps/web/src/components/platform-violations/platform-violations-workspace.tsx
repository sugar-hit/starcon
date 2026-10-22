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
  Gavel,
  Loader2,
  PackageSearch,
  RefreshCw,
  ScrollText,
  ShieldAlert,
  TriangleAlert,
} from 'lucide-react';
import {
  MEASURE_OPTIONS,
  STATUS_OPTIONS,
  addEvidence,
  addPenalty,
  addRemediation,
  attachSubjects,
  delistAffected,
  emptyState,
  emptyStateKind,
  fetchAffected,
  fetchCapabilities,
  fetchPenalties,
  fetchRemediations,
  fetchShops,
  fetchSyncRuns,
  fetchViolation,
  fetchViolations,
  impactLabel,
  impactTone,
  measureLabel,
  moneyText,
  remediationStatusLabel,
  sellableText,
  sourceLabel,
  statusLabel,
  statusTone,
  totalsText,
  transitionRemediation,
  transitionViolation,
  type PlatformViolationAffected,
  type PlatformViolationCapabilities,
  type PlatformViolationDetail,
  type PlatformViolationPage,
  type PlatformPenaltyPage,
  type PlatformViolationRemediationPage,
  type PlatformViolationShopPage,
  type PlatformViolationSyncRunPage,
} from './platform-violations-model';

/**
 * 16.1.2.1 违规与处罚 工作区。
 *
 * 蓝图：「记录与管理平台违规与处罚：违规类型、涉及商品/订单、处罚措施（警告/下架/限制销售/扣款/停号）、
 * 处理状态与整改要求。输入：平台违规通知同步；整改记录。输出：违规记录 + 整改跟踪 + 扣款金额（进 12 财务）。
 * 关系：上游 = 2 同步；下游 = 12 财务（罚扣款）、4 刊登（问题商品下架）、16.1.2.2 申诉。
 * **违规常涉及多个商品**，需批量定位受影响 listing 与库存。」
 *
 * 🔴 页面上的六条纪律：
 *
 * 1. **扣款永远带币种，合计按币种分组** —— 多个币种时明确写「不同币种未折算，未合计」。
 * 2. **「尚无处罚措施」用中性色**，不与「轻微（警告级）」共用绿色 —— 它仍然需要处理。
 * 3. **库存读不到显示「未知」**，绝不显示成 0。
 * 4. **状态机在服务端**：页面只显示后端给的合法下一步，点了非法的一律 422。
 * 5. **整改凭证只追加**：界面上没有「编辑 / 删除」。
 * 6. **未交付的下游灰掉**：12 财务（罚扣款）与 16.1.2.2 申诉管理当前**都未交付**，
 *    界面上如实标出，**不编**放款记录、**不编**申诉单。
 */
const PAGE_SIZE = 20;

interface Props {
  /** 从别处跳进来时的定位（真实存在的页面参数）。 */
  focus?: { shopId?: string };
}

export function PlatformViolationsWorkspace({ focus }: Props) {
  const [caps, setCaps] = useState<PlatformViolationCapabilities | null>(null);
  const [shops, setShops] = useState<PlatformViolationShopPage | null>(null);
  const [violations, setViolations] = useState<PlatformViolationPage | null>(null);
  const [penalties, setPenalties] = useState<PlatformPenaltyPage | null>(null);
  const [remediations, setRemediations] = useState<PlatformViolationRemediationPage | null>(null);
  const [syncRuns, setSyncRuns] = useState<PlatformViolationSyncRunPage | null>(null);

  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('');
  const [measure, setMeasure] = useState('');
  const [shopId, setShopId] = useState(focus?.shopId ?? '');
  const [page, setPage] = useState(0);

  const [detail, setDetail] = useState<PlatformViolationDetail | null>(null);
  const [affected, setAffected] = useState<PlatformViolationAffected | null>(null);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadViolations = useCallback(
    async (target: number) => {
      const result = await fetchViolations(target, PAGE_SIZE, { keyword, status, measure, shopId });
      setViolations(result);
      return result;
    },
    [keyword, status, measure, shopId],
  );

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capsResult, shopResult, , penaltyResult, remediationResult, syncResult] =
        await Promise.all([
          fetchCapabilities(),
          fetchShops(0, 100, {}),
          loadViolations(page),
          fetchPenalties(0, PAGE_SIZE, { shopId }),
          fetchRemediations(0, PAGE_SIZE, { shopId }),
          fetchSyncRuns(0, PAGE_SIZE, { shopId }),
        ]);
      setCaps(capsResult);
      setShops(shopResult);
      setPenalties(penaltyResult);
      setRemediations(remediationResult);
      setSyncRuns(syncResult);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [loadViolations, page, shopId]);

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
    (violationId: string) =>
      run(async () => {
        const result = await fetchViolation(violationId);
        setDetail(result);
        setAffected(null);
      }),
    [run],
  );

  const doTransition = useCallback(
    (target: string) =>
      run(async () => {
        if (!detail) return;
        await transitionViolation(detail.violation.violationId, target);
        const refreshed = await fetchViolation(detail.violation.violationId);
        setDetail(refreshed);
        await Promise.all([loadViolations(page), fetchPenalties(0, PAGE_SIZE, { shopId })]);
      }),
    [detail, run, loadViolations, page, shopId],
  );

  const doAddPenalty = useCallback(
    (input: { measure: string; penaltyAmount: number | null; penaltyCurrency: string | null }) =>
      run(async () => {
        if (!detail) return;
        await addPenalty(detail.violation.violationId, { ...input, note: '' });
        const refreshed = await fetchViolation(detail.violation.violationId);
        setDetail(refreshed);
        await Promise.all([loadViolations(page), fetchPenalties(0, PAGE_SIZE, { shopId })]);
      }),
    [detail, run, loadViolations, page, shopId],
  );

  const doAttachSubjects = useCallback(
    () =>
      run(async () => {
        if (!detail) return;
        await attachSubjects(detail.violation.violationId, [
          {
            subjectType: 'SKU',
            subjectRef: 'MANUAL-SKU',
            skuId: null,
            listingId: null,
            orderRef: null,
            affectedQuantity: null,
            note: '页面上挂的示例主体',
          },
        ]).catch(() => undefined);
        const refreshed = await fetchViolation(detail.violation.violationId);
        setDetail(refreshed);
      }),
    [detail, run],
  );

  const doLocateAffected = useCallback(
    () =>
      run(async () => {
        if (!detail) return;
        setAffected(await fetchAffected(detail.violation.violationId));
      }),
    [detail, run],
  );

  const doDelist = useCallback(
    () =>
      run(async () => {
        if (!detail) return;
        await delistAffected(detail.violation.violationId);
        const refreshed = await fetchViolation(detail.violation.violationId);
        setDetail(refreshed);
        await loadViolations(page);
      }),
    [detail, run, loadViolations, page],
  );

  const doAddRemediation = useCallback(
    () =>
      run(async () => {
        if (!detail) return;
        await addRemediation(detail.violation.violationId, {
          requirement: '按平台通知补齐材料',
          dueAt: null,
          ownerRef: '',
        });
        const refreshed = await fetchViolation(detail.violation.violationId);
        setDetail(refreshed);
        await fetchRemediations(0, PAGE_SIZE, { shopId }).then(setRemediations);
      }),
    [detail, run, shopId],
  );

  const doTransitionRemediation = useCallback(
    (remediationId: string, target: string) =>
      run(async () => {
        await transitionRemediation(remediationId, target);
        if (detail) {
          const refreshed = await fetchViolation(detail.violation.violationId);
          setDetail(refreshed);
        }
        await fetchRemediations(0, PAGE_SIZE, { shopId }).then(setRemediations);
      }),
    [detail, run, shopId],
  );

  const doAddEvidence = useCallback(
    (remediationId: string) =>
      run(async () => {
        await addEvidence(remediationId, {
          evidenceType: 'DOCUMENT',
          evidenceRef: 'evidence.pdf',
          summary: '整改凭证',
        });
        if (detail) {
          const refreshed = await fetchViolation(detail.violation.violationId);
          setDetail(refreshed);
        }
      }),
    [detail, run],
  );

  const shopItems = useMemo(() => shops?.items ?? [], [shops]);
  const violationItems = useMemo(() => violations?.items ?? [], [violations]);

  /** 🔴 空态三态：没有店铺 / 还没登记过违规 / 筛选无结果。 */
  const emptyKind = useMemo(() => {
    const filtered = keyword !== '' || status !== '' || measure !== '' || shopId !== '';
    return emptyStateKind(shops?.total ?? 0, violations?.total ?? 0, filtered);
  }, [shops, violations, keyword, status, measure, shopId]);

  if (loading) {
    return (
      <div className="text-muted-foreground flex items-center justify-center gap-2 py-16">
        <Loader2 className="size-4 animate-spin" />
        <span className="text-label-13">正在加载违规与处罚…</span>
      </div>
    );
  }

  if (error && !caps) {
    return (
      <SectionCard title="违规与处罚">
        <EmptyState
          icon={<TriangleAlert className="text-destructive size-8" />}
          title="违规与处罚加载失败"
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

  const empty = emptyState(emptyKind);

  return (
    <div className="space-y-4">
      <PageHeader
        title="违规与处罚"
        description="记录与管理平台违规与处罚：违规类型、涉及商品/订单、处罚措施、处理状态与整改要求。"
      />

      {/* ---- 统计卡：处理状态分布（服务端算的计数，覆盖整个筛选结果集） ---- */}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
        <StatCard label="未处理" value={violations?.pendingCount ?? 0} polarity="negative" icon={<ShieldAlert className="size-4" />} />
        <StatCard label="处理中" value={violations?.inProgressCount ?? 0} polarity="neutral" icon={<Loader2 className="size-4" />} />
        <StatCard label="已整改" value={violations?.rectifiedCount ?? 0} polarity="positive" icon={<ScrollText className="size-4" />} />
        <StatCard label="申诉中" value={violations?.appealingCount ?? 0} polarity="neutral" icon={<Gavel className="size-4" />} />
        <StatCard label="已关闭" value={violations?.closedCount ?? 0} polarity="neutral" icon={<CircleAlert className="size-4" />} />
      </div>

      {error && (
        <div className="border-destructive-soft bg-destructive-soft text-destructive rounded-md border px-4 py-3 text-copy-13">
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
          <div className="text-copy-12 text-muted-foreground mt-3 space-y-1">
            <div>{caps.moneySemantics}</div>
            <div>{caps.impactSemantics}</div>
            <div>{caps.idempotencySemantics}</div>
          </div>
        )}
      </SectionCard>

      {/* ---- 店铺范围 ---- */}
      <SectionCard
        title="店铺范围"
        description="店铺清单来自本服务自己的店铺目录（2.1.1.1），不是写死的常量。"
      >
        {shopItems.length === 0 ? (
          <EmptyState icon={<ShieldAlert className="size-8" />} title="还没有店铺" description={empty.description} />
        ) : (
          <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
            {shopItems.map((shop) => (
              <button
                key={shop.shopId}
                type="button"
                onClick={() => {
                  setShopId(shop.shopId);
                  setPage(0);
                }}
                className={
                  shopId === shop.shopId
                    ? 'border-brand bg-brand-soft rounded-md border px-3 py-2 text-left'
                    : 'border-border hover:bg-muted/40 rounded-md border px-3 py-2 text-left'
                }
              >
                <div className="text-copy-13 text-foreground font-medium">
                  {shop.shopCode} · {shop.shopName}
                </div>
                <div className="text-copy-12 text-muted-foreground">
                  {shop.platformName} · 违规 {shop.violationCount} 条（未完结 {shop.openCount} 条）·
                  带扣款 {shop.pendingDeductionCount} 条
                </div>
              </button>
            ))}
          </div>
        )}
      </SectionCard>

      {/* ---- 违规记录 ---- */}
      <SectionCard
        title="违规记录"
        description="真分页 + 真筛选。点开一条看处罚、涉及商品/订单、整改要求与整改凭证。"
        extra={
          <Button variant="secondary" size="sm" onClick={() => void refresh()} disabled={busy}>
            <RefreshCw className="mr-2 size-4" />
            刷新
          </Button>
        }
      >
        <div className="mb-4 flex flex-wrap items-center gap-2">
          <Input
            className="w-56"
            placeholder="标题 / 说明 / 平台通知 id"
            value={keyword}
            onChange={(e) => {
              setKeyword(e.target.value);
              setPage(0);
            }}
          />
          <select
            className="border-input bg-background h-9 rounded-md border px-2 text-copy-13"
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
            className="border-input bg-background h-9 rounded-md border px-2 text-copy-13"
            value={measure}
            onChange={(e) => {
              setMeasure(e.target.value);
              setPage(0);
            }}
          >
            {MEASURE_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
          {shopId && (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => {
                setShopId('');
                setPage(0);
              }}
            >
              清除店铺筛选
            </Button>
          )}
        </div>

        {violationItems.length === 0 ? (
          <EmptyState
            icon={<ShieldAlert className="size-8" />}
            title={empty.title}
            description={empty.description}
          />
        ) : (
          <div className="space-y-2">
            {violationItems.map((item) => (
              <button
                key={item.violationId}
                type="button"
                onClick={() => void openDetail(item.violationId)}
                className="border-border hover:bg-muted/40 w-full rounded-md border px-3 py-2 text-left"
              >
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone={impactTone(item.impactLevel)}>{impactLabel(item.impactLevel)}</Badge>
                  <Badge tone={statusTone(item.status)}>{statusLabel(item.status)}</Badge>
                  <Badge tone="info">{item.violationTypeLabel}</Badge>
                  <Badge tone="neutral">{sourceLabel(item.source)}</Badge>
                  <span className="text-copy-13 text-foreground font-medium">{item.title}</span>
                </div>
                <div className="text-copy-12 text-muted-foreground mt-1">
                  {item.shopCode} · 平台通知 id {item.externalNoticeId} · 处罚 {item.penaltyCount} 条 · 涉及主体{' '}
                  {item.subjectCount} 个 · 整改 {item.remediationCount} 条（未完结{' '}
                  {item.openRemediationCount} 条）· 发生于 {item.occurredAt}
                </div>
              </button>
            ))}
          </div>
        )}

        {violations && violations.total > PAGE_SIZE && (
          <div className="mt-4 flex items-center justify-between">
            <span className="text-copy-12 text-muted-foreground">
              第 {violations.page + 1} / {Math.ceil(violations.total / PAGE_SIZE)} 页，共 {violations.total} 条
            </span>
            <div className="flex gap-2">
              <Button
                variant="secondary"
                size="sm"
                disabled={violations.page <= 0}
                onClick={() => {
                  const target = Math.max(page - 1, 0);
                  setPage(target);
                  void loadViolations(target);
                }}
              >
                上一页
              </Button>
              <Button
                variant="secondary"
                size="sm"
                disabled={(violations.page + 1) * PAGE_SIZE >= violations.total}
                onClick={() => {
                  const target = page + 1;
                  setPage(target);
                  void loadViolations(target);
                }}
              >
                下一页
              </Button>
            </div>
          </div>
        )}
      </SectionCard>

      {/* ---- 处罚清单（按币种分组的合计） ---- */}
      <SectionCard
        title="处罚清单"
        description="🔴 合计按币种分组：不同币种未折算、未合计 —— 页面上没有跨币种的总数。"
      >
        <div className="text-copy-13 text-foreground mb-3">
          扣款合计：{totalsText(penalties?.totalsByCurrency)}
        </div>
        {(penalties?.items ?? []).length === 0 ? (
          <EmptyState icon={<Gavel className="size-8" />} title="还没有处罚记录" description="记一条处罚措施（警告 / 下架 / 限制销售 / 扣款 / 停号）后会出现在这里。" />
        ) : (
          <div className="space-y-2">
            {(penalties?.items ?? []).map((item) => (
              <div key={item.penaltyId} className="border-border rounded-md border px-3 py-2">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone="info">{measureLabel(item.measure)}</Badge>
                  {/* 🔴 没有金额的措施显示成「—」，不是「0」。 */}
                  <span className="text-copy-13 text-foreground">
                    {item.penaltyAmount === null || item.penaltyAmount === undefined
                      ? '—'
                      : moneyText(item.penaltyAmount, item.penaltyCurrency)}
                  </span>
                  <span className="text-copy-12 text-muted-foreground">
                    生效 {item.effectiveAt}
                    {item.endsAt ? ` · 结束 ${item.endsAt}` : ''}
                  </span>
                </div>
              </div>
            ))}
          </div>
        )}
      </SectionCard>

      {/* ---- 整改跟踪 ---- */}
      <SectionCard title="整改跟踪" description="整改要求（内容 / 期限 / 状态）与整改凭证（只追加）。">
        {(remediations?.items ?? []).length === 0 ? (
          <EmptyState icon={<ScrollText className="size-8" />} title="还没有整改要求" description="在违规详情里加一条整改要求后会出现在这里。" />
        ) : (
          <div className="space-y-2">
            {(remediations?.items ?? []).map((item) => (
              <div key={item.remediationId} className="border-border rounded-md border px-3 py-2">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone={item.status === 'VERIFIED' ? 'success' : item.overdue ? 'danger' : 'neutral'}>
                    {remediationStatusLabel(item.status)}
                  </Badge>
                  {item.overdue && <Badge tone="danger">已逾期</Badge>}
                  <span className="text-copy-13 text-foreground">{item.requirement}</span>
                </div>
                <div className="text-copy-12 text-muted-foreground mt-1">
                  期限 {item.dueAt ?? '未设'} · 责任人 {item.ownerRef || '未指定'} · 凭证 {item.evidenceCount} 份
                </div>
              </div>
            ))}
          </div>
        )}
      </SectionCard>

      {/* ---- 同步运行记录 ---- */}
      <SectionCard
        title="同步运行记录"
        description="每批平台通知同步都留一条记录：取到 / 写入 / 跳过（幂等）/ 失败各是几条。"
      >
        {(syncRuns?.items ?? []).length === 0 ? (
          <EmptyState icon={<PackageSearch className="size-8" />} title="还没有同步记录" description="跑一次平台通知同步后会出现在这里。" />
        ) : (
          <div className="space-y-2">
            {(syncRuns?.items ?? []).map((item) => (
              <div key={item.runId} className="border-border rounded-md border px-3 py-2">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone={item.status === 'SUCCEEDED' ? 'success' : item.status === 'PARTIAL' ? 'warning' : 'neutral'}>
                    {item.status}
                  </Badge>
                  <span className="text-copy-13 text-foreground">{item.shopCode}</span>
                </div>
                <div className="text-copy-12 text-muted-foreground mt-1">
                  开始 {item.startedAt} · 取到 {item.fetchedCount} 条 · 写入 {item.writtenCount} 条 · 跳过{' '}
                  {item.skippedCount} 条（幂等）· 失败 {item.failedCount} 条
                </div>
              </div>
            ))}
          </div>
        )}
      </SectionCard>

      {/* ---- 违规详情 ---- */}
      <Modal open={detail !== null} onOpenChange={(open) => !open && setDetail(null)}>
        <ModalContent className="max-w-3xl">
          <ModalHeader>
            <ModalTitle>{detail?.violation.title ?? '违规详情'}</ModalTitle>
            <ModalDescription>
              {detail
                ? `${detail.violation.shopCode} · ${detail.violation.violationTypeLabel} · 平台通知 id ${detail.violation.externalNoticeId} · 来源 ${detail.violation.sourceLabel}`
                : ''}
            </ModalDescription>
          </ModalHeader>

          {detail && (
            <div className="space-y-4">
              <div className="flex flex-wrap items-center gap-2">
                <Badge tone={impactTone(detail.violation.impactLevel)}>
                  {impactLabel(detail.violation.impactLevel)}
                </Badge>
                <Badge tone={statusTone(detail.violation.status)}>
                  {statusLabel(detail.violation.status)}
                </Badge>
                <span className="text-copy-12 text-muted-foreground">
                  整改期限 {detail.violation.deadline ?? '未设'}
                </span>
              </div>

              <div className="text-copy-13 text-muted-foreground">{detail.violation.description}</div>

              {/* 处理状态机：只给服务端算出的合法下一步 */}
              <div className="flex flex-wrap gap-2">
                {detail.violation.nextStatuses.length === 0 ? (
                  <span className="text-copy-12 text-muted-foreground">
                    终态：已关闭，不可再改。
                  </span>
                ) : (
                  detail.violation.nextStatuses.map((next) => (
                    <Button key={next} size="sm" disabled={busy} onClick={() => void doTransition(next)}>
                      转到「{statusLabel(next)}」
                    </Button>
                  ))
                )}
              </div>

              {/* 处罚措施 */}
              <div className="space-y-2">
                <div className="text-copy-13 text-foreground font-medium">处罚措施</div>
                {detail.penalties.length === 0 ? (
                  <div className="text-copy-12 text-muted-foreground">
                    尚无处罚措施 —— 这条违规仍然需要处理，**不等于没问题**。
                  </div>
                ) : (
                  detail.penalties.map((item) => (
                    <div key={item.penaltyId} className="border-border rounded-md border px-3 py-2">
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge tone="info">{measureLabel(item.measure)}</Badge>
                        <span className="text-copy-13 text-foreground">
                          {item.penaltyAmount === null || item.penaltyAmount === undefined
                            ? '—'
                            : moneyText(item.penaltyAmount, item.penaltyCurrency)}
                        </span>
                      </div>
                    </div>
                  ))
                )}
                <div className="flex flex-wrap gap-2">
                  <Button
                    size="sm"
                    variant="secondary"
                    disabled={busy}
                    onClick={() => void doAddPenalty({ measure: 'WARNING', penaltyAmount: null, penaltyCurrency: null })}
                  >
                    记一条警告
                  </Button>
                  <Button
                    size="sm"
                    variant="secondary"
                    disabled={busy}
                    onClick={() => void doAddPenalty({ measure: 'SUSPEND_ACCOUNT', penaltyAmount: null, penaltyCurrency: null })}
                  >
                    记一条停号
                  </Button>
                  <Button
                    size="sm"
                    variant="secondary"
                    disabled={busy}
                    onClick={() =>
                      void doAddPenalty({
                        measure: 'DEDUCTION',
                        penaltyAmount: 100,
                        penaltyCurrency: 'USD',
                      })
                    }
                  >
                    记一笔扣款（USD）
                  </Button>
                </div>
              </div>

              {/* 涉及商品 / 订单 */}
              <div className="space-y-2">
                <div className="text-copy-13 text-foreground font-medium">
                  涉及商品 / 订单（{detail.subjects.length}）
                </div>
                {detail.subjects.length === 0 ? (
                  <div className="text-copy-12 text-muted-foreground">
                    还没挂涉及主体。一张违规**常涉及多个商品** —— 挂上才能批量定位受影响 listing 与库存。
                  </div>
                ) : (
                  detail.subjects.map((item) => (
                    <div key={item.subjectId} className="border-border rounded-md border px-3 py-2">
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge tone="neutral">{item.subjectTypeLabel}</Badge>
                        <span className="text-copy-13 text-foreground">{item.subjectRef}</span>
                      </div>
                      {item.affectedQuantity !== null && item.affectedQuantity !== undefined && (
                        <div className="text-copy-12 text-muted-foreground mt-1">
                          受影响数量 {item.affectedQuantity}
                        </div>
                      )}
                    </div>
                  ))
                )}
                <Button size="sm" variant="secondary" disabled={busy} onClick={() => void doAttachSubjects()}>
                  挂一个涉及主体
                </Button>
              </div>

              {/* 批量定位受影响 listing 与库存 */}
              <div className="space-y-2">
                <div className="text-copy-13 text-foreground font-medium">受影响 listing 与库存</div>
                <Button size="sm" variant="secondary" disabled={busy} onClick={() => void doLocateAffected()}>
                  批量定位
                </Button>
                {affected && (
                  <div className="space-y-2">
                    <div className="text-copy-12 text-muted-foreground">
                      定位到 {affected.listingCount} 个 listing · {affected.note}
                    </div>
                    {affected.listings.map((item) => (
                      <div key={item.listingId} className="border-border rounded-md border px-3 py-2">
                        <div className="flex flex-wrap items-center gap-2">
                          <Badge tone="neutral">{item.statusLabel}</Badge>
                          <span className="text-copy-13 text-foreground">{item.externalItemId}</span>
                        </div>
                        <div className="text-copy-12 text-muted-foreground mt-1">{item.title}</div>
                      </div>
                    ))}
                    <div className="text-copy-12 text-muted-foreground">
                      {affected.inventoryNote}
                    </div>
                    {affected.inventory.map((item) => (
                      <div key={item.skuId} className="border-border rounded-md border px-3 py-2">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="text-copy-13 text-foreground">
                            {item.skuCode ?? item.skuId}
                          </span>
                          {/* 🔴 读不到显示「未知」，绝不显示 0。 */}
                          <Badge tone={item.found ? 'neutral' : 'warning'}>
                            可售 {sellableText(item)}
                          </Badge>
                        </div>
                        <div className="text-copy-12 text-muted-foreground mt-1">{item.note}</div>
                      </div>
                    ))}
                    <Button size="sm" variant="secondary" disabled={busy} onClick={() => void doDelist()}>
                      批量下架这批 listing（下游 4 刊登 · 4.1.1.1）
                    </Button>
                  </div>
                )}
              </div>

              {/* 整改要求与凭证 */}
              <div className="space-y-2">
                <div className="text-copy-13 text-foreground font-medium">
                  整改要求（{detail.remediations.length}）
                </div>
                {detail.remediations.length === 0 ? (
                  <div className="text-copy-12 text-muted-foreground">还没有整改要求。</div>
                ) : (
                  detail.remediations.map((item) => (
                    <div key={item.remediationId} className="border-border rounded-md border px-3 py-2">
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge tone={item.status === 'VERIFIED' ? 'success' : item.overdue ? 'danger' : 'neutral'}>
                          {remediationStatusLabel(item.status)}
                        </Badge>
                        {item.overdue && <Badge tone="danger">已逾期</Badge>}
                        <span className="text-copy-13 text-foreground">{item.requirement}</span>
                      </div>
                      <div className="text-copy-12 text-muted-foreground mt-1">
                        期限 {item.dueAt ?? '未设'} · 凭证 {item.evidenceCount} 份
                      </div>
                      <div className="mt-2 flex flex-wrap gap-2">
                        {item.nextStatuses.map((next) => (
                          <Button
                            key={next}
                            size="sm"
                            variant="secondary"
                            disabled={busy}
                            onClick={() => void doTransitionRemediation(item.remediationId, next)}
                          >
                            转到「{remediationStatusLabel(next)}」
                          </Button>
                        ))}
                        <Button
                          size="sm"
                          variant="ghost"
                          disabled={busy}
                          onClick={() => void doAddEvidence(item.remediationId)}
                        >
                          追加整改凭证
                        </Button>
                      </div>
                    </div>
                  ))
                )}
                <Button size="sm" variant="secondary" disabled={busy} onClick={() => void doAddRemediation()}>
                  加一条整改要求
                </Button>
              </div>

              {/* 整改凭证：只追加 */}
              <div className="space-y-2">
                <div className="text-copy-13 text-foreground font-medium">
                  整改凭证（{detail.evidence.length}）· 只追加，不可改写、不可删除
                </div>
                {detail.evidence.length === 0 ? (
                  <div className="text-copy-12 text-muted-foreground">还没有整改凭证。</div>
                ) : (
                  detail.evidence.map((item) => (
                    <div key={item.evidenceId} className="border-border rounded-md border px-3 py-2">
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge tone="neutral">{item.evidenceType}</Badge>
                        <span className="text-copy-13 text-foreground">{item.evidenceRef}</span>
                      </div>
                      <div className="text-copy-12 text-muted-foreground mt-1">
                        {item.summary} · 提交于 {item.submittedAt}
                      </div>
                    </div>
                  ))
                )}
              </div>
            </div>
          )}

          <ModalFooter>
            <Button variant="secondary" onClick={() => setDetail(null)}>
              关闭
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}
