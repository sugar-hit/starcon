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
  Activity,
  CircleAlert,
  Gauge,
  Loader2,
  RefreshCw,
  ShieldAlert,
  ShieldCheck,
  TriangleAlert,
} from 'lucide-react';
import {
  DATA_STATES,
  RISK_LEVELS,
  assessShop,
  emptyState,
  emptyStateKind,
  fetchAssessments,
  fetchCapabilities,
  fetchPendingItems,
  fetchRanking,
  fetchShop,
  fetchShops,
  fetchThresholds,
  metricValueText,
  pendingKindLabel,
  recordMetric,
  riskLevelLabel,
  riskLevelTone,
  ruleSourceLabel,
  saveThreshold,
  sourceLabel,
  syncShop,
  thresholdKindLabel,
  type AccountHealthAssessmentPage,
  type AccountHealthAssessmentSummary,
  type AccountHealthCapabilities,
  type AccountHealthMetricInput,
  type AccountHealthPendingList,
  type AccountHealthRankingPage,
  type AccountHealthShopCard,
  type AccountHealthShopPage,
  type AccountHealthThresholdList,
  type AccountHealthThresholdView,
} from './account-health-model';

/**
 * 16.1.1.1 账号健康总览 工作区。
 *
 * 蓝图：「按店铺展示账号健康状态：绩效指标（ODR、迟发率、取消率、有效追踪率）、政策违规计数、
 * 申诉状态、风险等级，支持跨店铺对比与风险排序。输入：平台绩效数据同步；风险等级计算规则；
 * 用户数据范围（店铺维度）。输出：店铺健康卡片 + 风险排序 + 需处理项跳转。
 * 关系：上游 = 2 同步；下游 = 16.1.1.2 指标明细、16.1.2.1 违规。
 * **风险排序比明细更重要**：先处理最可能掉号的店铺。」
 *
 * 🔴 页面上的六条纪律：
 *
 * 1. **风险排序排在最上面**：这是蓝图加粗的那一句 —— 先处理最可能掉号的店铺。
 * 2. **「量不到」显示成「未知」，不显示成 0.00%**；等级「判不了」是**第三种状态**，
 *    不排在健康后面。
 * 3. **判定结论原样显示后端算的**（等级 / 依据 / 命中阈值），前端不重算、不编依据。
 * 4. **手工录入的指标一眼看得出**（「人工录入」标签），不冒充平台同步来的。
 * 5. **未交付的下游如实标出**：违规计数与申诉状态当前**没有数据**，界面**不编**，
 *    也不提供指向不存在页面的跳转。
 * 6. **三态空态各不相同**：「没有店铺」「筛选无结果」「还没采集到任何指标」分开说。
 */
const PAGE_SIZE = 10;

interface Props {
  /** 从「需处理项」跳进来时的定位（真实存在的页面参数）。 */
  focus?: { shopId?: string };
}

interface MetricForm {
  metricCode: string;
  metricValue: string;
  periodStart: string;
  periodEnd: string;
  note: string;
}

const EMPTY_FORM: MetricForm = {
  metricCode: 'ODR',
  metricValue: '',
  periodStart: '',
  periodEnd: '',
  note: '',
};

interface ThresholdForm {
  direction: string;
  warnValue: string;
  criticalValue: string;
  note: string;
}

export function AccountHealthWorkspace({ focus }: Props) {
  const [caps, setCaps] = useState<AccountHealthCapabilities | null>(null);
  const [shops, setShops] = useState<AccountHealthShopPage | null>(null);
  const [ranking, setRanking] = useState<AccountHealthRankingPage | null>(null);
  const [pending, setPending] = useState<AccountHealthPendingList | null>(null);
  const [thresholds, setThresholds] = useState<AccountHealthThresholdList | null>(null);
  const [assessments, setAssessments] = useState<AccountHealthAssessmentPage | null>(null);

  const [keyword, setKeyword] = useState('');
  const [platformCode, setPlatformCode] = useState('');
  const [riskLevel, setRiskLevel] = useState('');
  const [dataState, setDataState] = useState('');
  const [shopId, setShopId] = useState(focus?.shopId ?? '');
  const [page, setPage] = useState(0);
  const [rankingPage, setRankingPage] = useState(0);

  const [detail, setDetail] = useState<AccountHealthShopCard | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [form, setForm] = useState<MetricForm>(EMPTY_FORM);
  const [thresholdForm, setThresholdForm] = useState<ThresholdForm | null>(null);
  const [editingThreshold, setEditingThreshold] = useState<AccountHealthThresholdView | null>(null);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadShops = useCallback(
    async (target: number) => {
      const result = await fetchShops(target, PAGE_SIZE, {
        keyword,
        platformCode,
        riskLevel,
        dataState,
        shopId,
      });
      setShops(result);
      return result;
    },
    [keyword, platformCode, riskLevel, dataState, shopId],
  );

  const loadRanking = useCallback(
    async (target: number) => {
      const result = await fetchRanking(target, PAGE_SIZE, { keyword, platformCode, riskLevel, shopId });
      setRanking(result);
      return result;
    },
    [keyword, platformCode, riskLevel, shopId],
  );

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capsResult, , , pendingResult, thresholdResult] = await Promise.all([
        fetchCapabilities(),
        loadShops(page),
        loadRanking(rankingPage),
        fetchPendingItems(platformCode, keyword),
        fetchThresholds(),
      ]);
      setCaps(capsResult);
      setPending(pendingResult);
      setThresholds(thresholdResult);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [loadShops, loadRanking, page, rankingPage, platformCode, keyword]);

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

  const applyFilters = useCallback(
    (next: Partial<{ keyword: string; platformCode: string; riskLevel: string; dataState: string }>) => {
      setKeyword(next.keyword ?? keyword);
      setPlatformCode(next.platformCode ?? platformCode);
      setRiskLevel(next.riskLevel ?? riskLevel);
      setDataState(next.dataState ?? dataState);
      setPage(0);
      setRankingPage(0);
    },
    [keyword, platformCode, riskLevel, dataState],
  );

  const openDetail = useCallback(
    (id: string) =>
      run(async () => {
        setDetail(await fetchShop(id));
      }),
    [run],
  );

  const doAssess = useCallback(
    (id: string) =>
      run(async () => {
        await assessShop(id);
        const [nextShops, nextRanking, nextAssessments] = await Promise.all([
          loadShops(page),
          loadRanking(rankingPage),
          fetchAssessments(id, 0, 20),
        ]);
        setAssessments(nextAssessments);
        setDetail(nextShops.items.find((item) => item.shopId === id) ?? detail);
      }),
    [run, loadShops, loadRanking, page, rankingPage, detail],
  );

  const doSync = useCallback(
    (id: string) =>
      run(async () => {
        await syncShop(id);
        await Promise.all([loadShops(page), loadRanking(rankingPage)]);
      }),
    [run, loadShops, loadRanking, page, rankingPage],
  );

  const submitMetric = useCallback(
    () =>
      run(async () => {
        if (!detail) return;
        const raw = form.metricValue.trim();
        const input: AccountHealthMetricInput = {
          metricCode: form.metricCode,
          metricValue: raw === '' ? null : Number(raw),
          periodStart: form.periodStart,
          periodEnd: form.periodEnd,
          note: form.note,
        };
        const updated = await recordMetric(detail.shopId, input);
        setDetail(updated);
        setFormOpen(false);
        setForm(EMPTY_FORM);
        await Promise.all([loadShops(page), loadRanking(rankingPage)]);
      }),
    [run, detail, form, loadShops, loadRanking, page, rankingPage],
  );

  const submitThreshold = useCallback(
    () =>
      run(async () => {
        if (!editingThreshold || !thresholdForm) return;
        await saveThreshold(editingThreshold.metricCode, {
          direction: thresholdForm.direction,
          warnValue: Number(thresholdForm.warnValue),
          criticalValue: Number(thresholdForm.criticalValue),
          note: thresholdForm.note,
        });
        setThresholds(await fetchThresholds());
        setThresholdForm(null);
        setEditingThreshold(null);
        await Promise.all([loadShops(page), loadRanking(rankingPage)]);
      }),
    [run, editingThreshold, thresholdForm, loadShops, loadRanking, page, rankingPage],
  );

  const shopItems = useMemo(() => shops?.items ?? [], [shops]);
  const pendingItems = useMemo(() => pending?.items ?? [], [pending]);
  const assessmentItems = useMemo(() => assessments?.items ?? [], [assessments]);

  /** 🔴 空态三态：有店铺但一个指标都没量到 ≠ 筛选无结果 ≠ 没有店铺。 */
  const emptyKind = useMemo(() => {
    const totalShops = (ranking?.criticalCount ?? 0) + (ranking?.warningCount ?? 0) +
      (ranking?.unknownCount ?? 0) + (ranking?.healthyCount ?? 0);
    const anyMetricKnown = shopItems.some((item) => item.knownMetricCount > 0);
    return emptyStateKind(totalShops, shops?.total ?? 0, anyMetricKnown);
  }, [ranking, shops, shopItems]);

  if (loading) {
    return (
      <div className="flex items-center justify-center gap-2 py-16 text-muted-foreground">
        <Loader2 className="size-4 animate-spin" />
        <span className="text-label-13">正在加载账号健康…</span>
      </div>
    );
  }

  if (error && !caps) {
    return (
      <SectionCard title="账号健康总览">
        <EmptyState
          icon={<TriangleAlert className="size-8 text-destructive" />}
          title="账号健康总览加载失败"
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
        title="账号健康总览"
        description="按店铺展示账号健康状态：绩效指标、风险等级与跨店铺风险排序。风险排序比明细更重要 —— 先处理最可能掉号的店铺。"
        actions={
          <Button variant="outline" onClick={() => void refresh()}>
            <RefreshCw className="mr-2 size-4" />
            刷新
          </Button>
        }
      />

      {error && (
        <SectionCard>
          <div className="text-destructive flex items-center gap-2 text-copy-13">
            <TriangleAlert className="size-4" />
            {error}
          </div>
        </SectionCard>
      )}

      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          label="严重（先处理）"
          value={ranking?.criticalCount ?? 0}
          polarity="negative"
          icon={<ShieldAlert className="size-4" />}
        />
        <StatCard
          label="预警"
          value={ranking?.warningCount ?? 0}
          polarity="negative"
          icon={<CircleAlert className="size-4" />}
        />
        <StatCard
          label="判不了（指标不全）"
          value={ranking?.unknownCount ?? 0}
          polarity="neutral"
          icon={<TriangleAlert className="size-4" />}
        />
        <StatCard
          label="健康"
          value={ranking?.healthyCount ?? 0}
          polarity="positive"
          icon={<ShieldCheck className="size-4" />}
        />
      </div>

      {caps && caps.gaps.length > 0 && (
        <SectionCard
          title="能力声明"
          description="下面这些是**本模块之外**、当前尚未交付的能力。缺了它们，本模块仍然能算风险等级与排序 —— 但界面上不会拿编造的数据去填那些维度。"
        >
          <ul className="space-y-2">
            {caps.gaps.map((gap) => (
              <li key={gap.moduleId} className="text-copy-13 flex items-start gap-2">
                <Badge tone="warning">{gap.moduleId}</Badge>
                <span className="text-foreground">{gap.moduleName}</span>
                <span className="text-muted-foreground">{gap.impact}</span>
              </li>
            ))}
          </ul>
          <p className="text-muted-foreground mt-3 text-copy-12">{caps.platformSyncNote}</p>
        </SectionCard>
      )}

      {/* ---------- 需处理项 ---------- */}
      <SectionCard
        title="需处理项"
        description="只列**本模块自己真实存在**的待处理项；跳转目标是本模块自己的页面（未交付的违规 / 申诉不编造、不跳转到不存在的页面）。"
      >
        {pendingItems.length === 0 ? (
          <EmptyState
            icon={<ShieldCheck className="size-8" />}
            title="没有需要处理的项目"
            description="当前没有命中阈值的店铺，也没有指标不全的店铺。"
          />
        ) : (
          <div className="space-y-2">
            {pendingItems.map((item, index) => (
              <div
                key={`${item.kind}-${item.shopId ?? 'global'}-${index}`}
                className="border-border flex flex-wrap items-center justify-between gap-2 rounded-md border px-3 py-2"
              >
                <div className="min-w-0">
                  <div className="text-copy-13 text-foreground flex items-center gap-2">
                    <Badge tone={item.kind === 'RISK_BREACH' ? 'danger' : 'warning'}>
                      {pendingKindLabel(item.kind)}
                    </Badge>
                    {item.label}
                  </div>
                  <div className="text-copy-12 text-muted-foreground">{item.description}</div>
                </div>
                <a
                  className="text-copy-13 text-brand underline-offset-2 hover:underline"
                  href={item.targetPath}
                >
                  {item.targetLabel}
                </a>
              </div>
            ))}
          </div>
        )}
      </SectionCard>

      {/* ---------- 风险排序 ---------- */}
      <SectionCard
        title="风险排序"
        description={ranking?.note ?? '先处理最可能掉号的店铺。'}
        extra={
          <Input
            className="h-9 w-40"
            placeholder="搜索店铺编码 / 名称"
            value={keyword}
            onChange={(e) => applyFilters({ keyword: e.target.value })}
          />
        }
      >
        {(ranking?.items.length ?? 0) === 0 ? (
          <EmptyState
            icon={<Activity className="size-8" />}
            title={emptyState(emptyKind).title}
            description={emptyState(emptyKind).description}
          />
        ) : (
          <div className="space-y-2">
            {ranking?.items.map((row) => (
              <div
                key={row.shopId}
                className="border-border flex flex-wrap items-center justify-between gap-2 rounded-md border px-3 py-2"
              >
                <div className="min-w-0">
                  <div className="text-copy-13 text-foreground flex items-center gap-2">
                    <span className="text-muted-foreground">#{row.rank}</span>
                    <span>{row.shopName}</span>
                    <span className="text-muted-foreground">{row.shopCode}</span>
                    <span className="text-muted-foreground">{row.platformCode}</span>
                  </div>
                  <div className="text-copy-12 text-muted-foreground">{row.rankReason}</div>
                </div>
                <div className="flex items-center gap-2">
                  <Badge tone={riskLevelTone(row.riskLevel)}>{row.riskLevelLabel}</Badge>
                  <Button variant="ghost" size="sm" onClick={() => void openDetail(row.shopId)}>
                    查看卡片
                  </Button>
                </div>
              </div>
            ))}
            <Pager
              page={ranking?.page ?? 0}
              size={PAGE_SIZE}
              total={ranking?.total ?? 0}
              onChange={(target) => {
                setRankingPage(target);
                void loadRanking(target);
              }}
            />
          </div>
        )}
      </SectionCard>

      {/* ---------- 店铺健康卡片 ---------- */}
      <SectionCard
        title="店铺健康卡片"
        description="四项绩效指标（ODR / 迟发率 / 取消率 / 有效追踪率）+ 风险等级 + 逐条依据。排序恒为风险优先。"
        extra={
          <div className="flex flex-wrap items-center gap-2">
            <select
              className="border-control-border bg-card text-copy-13 h-9 rounded-md border px-2"
              value={riskLevel}
              onChange={(e) => applyFilters({ riskLevel: e.target.value })}
            >
              {RISK_LEVELS.map((level) => (
                <option key={level.value} value={level.value}>
                  {level.label}
                </option>
              ))}
            </select>
            <select
              className="border-control-border bg-card text-copy-13 h-9 rounded-md border px-2"
              value={dataState}
              onChange={(e) => applyFilters({ dataState: e.target.value })}
            >
              {DATA_STATES.map((state) => (
                <option key={state.value} value={state.value}>
                  {state.label}
                </option>
              ))}
            </select>
            <Input
              className="h-9 w-40"
              placeholder="平台编码（如 AMAZON）"
              value={platformCode}
              onChange={(e) => applyFilters({ platformCode: e.target.value })}
            />
          </div>
        }
      >
        {shopItems.length === 0 ? (
          <EmptyState
            icon={<Gauge className="size-8" />}
            title={emptyState(emptyKind).title}
            description={emptyState(emptyKind).description}
          />
        ) : (
          <div className="space-y-3">
            {shopItems.map((card) => (
              <ShopCard key={card.shopId} card={card} onOpen={() => void openDetail(card.shopId)} />
            ))}
            <Pager
              page={shops?.page ?? 0}
              size={PAGE_SIZE}
              total={shops?.total ?? 0}
              onChange={(target) => {
                setPage(target);
                void loadShops(target);
              }}
            />
          </div>
        )}
      </SectionCard>

      {/* ---------- 阈值 ---------- */}
      <SectionCard
        title="风险等级阈值"
        description={thresholds?.note ?? '阈值在服务端算，可按租户调整。'}
      >
        <div className="space-y-2">
          {(thresholds?.items ?? []).map((view) => (
            <div
              key={view.metricCode}
              className="border-border flex flex-wrap items-center justify-between gap-2 rounded-md border px-3 py-2"
            >
              <div className="min-w-0">
                <div className="text-copy-13 text-foreground">{view.metricLabel}</div>
                <div className="text-copy-12 text-muted-foreground">
                  警告 {metricValueText(view.warnValue)} · 严重 {metricValueText(view.criticalValue)} ·{' '}
                  {view.direction === 'BELOW_IS_BAD' ? '低于阈值即危险' : '高于阈值即危险'} ·{' '}
                  {ruleSourceLabel(view.ruleSource)} · 第 {view.ruleVersion} 版
                </div>
              </div>
              <Button
                variant="secondary"
                size="sm"
                onClick={() => {
                  setEditingThreshold(view);
                  setThresholdForm({
                    direction: view.direction,
                    warnValue: String(view.warnValue),
                    criticalValue: String(view.criticalValue),
                    note: '',
                  });
                }}
              >
                调整
              </Button>
            </div>
          ))}
        </div>
      </SectionCard>

      {/* ---------- 详情弹窗 ---------- */}
      <Modal open={detail !== null} onOpenChange={(open) => !open && setDetail(null)}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>{detail ? `${detail.shopName} · 账号健康` : ''}</ModalTitle>
            <ModalDescription>
              {detail ? `${detail.shopCode} · ${detail.platformName} · 风险排序第 ${detail.rank} 位` : ''}
            </ModalDescription>
          </ModalHeader>
          {detail && (
            <div className="space-y-4">
              <div className="flex flex-wrap items-center gap-2">
                <Badge tone={riskLevelTone(detail.riskLevel)}>{detail.riskLevelLabel}</Badge>
                <span className="text-copy-13 text-muted-foreground">{detail.rankReason}</span>
              </div>

              <div className="space-y-2">
                <div className="text-copy-13 text-foreground">绩效指标</div>
                {detail.metrics.map((metric) => (
                  <div
                    key={metric.metricCode}
                    className="border-border flex flex-wrap items-center justify-between gap-2 rounded-md border px-3 py-2"
                  >
                    <div className="min-w-0">
                      <div className="text-copy-13 text-foreground">{metric.metricLabel}</div>
                      <div className="text-copy-12 text-muted-foreground">
                        {metric.periodStart ?? '—'} ~ {metric.periodEnd ?? '—'} ·{' '}
                        {sourceLabel(metric.source)}
                        {metric.capturedAt ? ` · ${metric.capturedAt.slice(0, 10)}` : ''}
                        {metric.note ? ` · ${metric.note}` : ''}
                      </div>
                    </div>
                    <div className="flex items-center gap-2">
                      {metric.source === 'MANUAL' && <Badge tone="info">人工录入</Badge>}
                      <span className="text-copy-13 text-foreground">
                        {metricValueText(metric.metricValue)}
                      </span>
                    </div>
                  </div>
                ))}
              </div>

              <div className="space-y-2">
                <div className="text-copy-13 text-foreground">判定依据</div>
                {detail.reasons.map((reason) => (
                  <div key={reason.metricCode} className="text-copy-13 flex items-start gap-2">
                    <Badge
                      tone={
                        reason.level === 'CRITICAL'
                          ? 'danger'
                          : reason.level === 'WARNING'
                            ? 'warning'
                            : reason.level === 'UNKNOWN'
                              ? 'neutral'
                              : 'success'
                      }
                    >
                      {thresholdKindLabel(reason.thresholdKind)}
                    </Badge>
                    <span className="text-muted-foreground">{reason.message}</span>
                  </div>
                ))}
              </div>

              <div className="flex flex-wrap gap-2">
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={() => {
                    setForm({
                      ...EMPTY_FORM,
                      periodStart: new Date().toISOString().slice(0, 8) + '01',
                      periodEnd: new Date().toISOString().slice(0, 10),
                    });
                    setFormOpen(true);
                  }}
                >
                  手工录入指标
                </Button>
                <Button variant="secondary" size="sm" onClick={() => void doAssess(detail.shopId)}>
                  跑一次判定
                </Button>
                <Button variant="outline" size="sm" onClick={() => void doSync(detail.shopId)}>
                  尝试平台同步
                </Button>
              </div>

              {assessmentItems.length > 0 && (
                <div className="space-y-2">
                  <div className="text-copy-13 text-foreground">判定历史</div>
                  {assessmentItems.map((row: AccountHealthAssessmentSummary) => (
                    <div key={row.assessmentId} className="text-copy-12 text-muted-foreground">
                      {row.assessedAt.slice(0, 10)} · {row.riskLevelLabel} · 第 {row.ruleVersion} 版 ·{' '}
                      {row.summary}
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
          <ModalFooter>
            <Button variant="secondary" onClick={() => setDetail(null)}>
              关闭
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---------- 手工录入弹窗 ---------- */}
      <Modal open={formOpen} onOpenChange={(open) => setFormOpen(open)}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>手工录入绩效指标</ModalTitle>
            <ModalDescription>
              平台账号绩效同步**未接入**（归卷 02 同步运行），所以这里只能录入你从平台后台看到的数字。
              录入的条目一律标「人工录入」，**不会冒充平台同步**。
            </ModalDescription>
          </ModalHeader>
          <div className="space-y-3">
            <label className="block space-y-1">
              <span className="text-copy-13 text-muted-foreground">指标</span>
              <select
                className="border-control-border bg-card text-copy-13 h-9 w-full rounded-md border px-2"
                value={form.metricCode}
                onChange={(e) => setForm({ ...form, metricCode: e.target.value })}
              >
                <option value="ODR">订单缺陷率（ODR）</option>
                <option value="LATE_SHIPMENT_RATE">迟发率</option>
                <option value="CANCELLATION_RATE">取消率</option>
                <option value="VALID_TRACKING_RATE">有效追踪率</option>
              </select>
            </label>
            <label className="block space-y-1">
              <span className="text-copy-13 text-muted-foreground">
                数值（0..1 比值，例如 2.8% 写 0.0280；留空 = 显式登记「量不到」）
              </span>
              <Input
                value={form.metricValue}
                onChange={(e) => setForm({ ...form, metricValue: e.target.value })}
                placeholder="0.0280"
              />
            </label>
            <div className="grid gap-3 sm:grid-cols-2">
              <label className="block space-y-1">
                <span className="text-copy-13 text-muted-foreground">周期起点</span>
                <Input
                  type="date"
                  value={form.periodStart}
                  onChange={(e) => setForm({ ...form, periodStart: e.target.value })}
                />
              </label>
              <label className="block space-y-1">
                <span className="text-copy-13 text-muted-foreground">周期终点</span>
                <Input
                  type="date"
                  value={form.periodEnd}
                  onChange={(e) => setForm({ ...form, periodEnd: e.target.value })}
                />
              </label>
            </div>
            <label className="block space-y-1">
              <span className="text-copy-13 text-muted-foreground">备注</span>
              <Input
                value={form.note}
                onChange={(e) => setForm({ ...form, note: e.target.value })}
                placeholder="例如：从平台后台导出"
              />
            </label>
          </div>
          <ModalFooter>
            <Button variant="secondary" onClick={() => setFormOpen(false)}>
              取消
            </Button>
            <Button onClick={() => void submitMetric()}>录入</Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---------- 阈值调整弹窗 ---------- */}
      <Modal open={thresholdForm !== null} onOpenChange={(open) => !open && setThresholdForm(null)}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>
              调整阈值{editingThreshold ? ` · ${editingThreshold.metricLabel}` : ''}
            </ModalTitle>
            <ModalDescription>
              阈值是**服务端**口径：改完推进规则版本，**历史判定不会被追溯改变**（判定凭据里带着当时用到的阈值快照）。
            </ModalDescription>
          </ModalHeader>
          {thresholdForm && (
            <div className="space-y-3">
              <label className="block space-y-1">
                <span className="text-copy-13 text-muted-foreground">方向</span>
                <select
                  className="border-control-border bg-card text-copy-13 h-9 w-full rounded-md border px-2"
                  value={thresholdForm.direction}
                  onChange={(e) => setThresholdForm({ ...thresholdForm, direction: e.target.value })}
                >
                  <option value="ABOVE_IS_BAD">高于阈值即危险</option>
                  <option value="BELOW_IS_BAD">低于阈值即危险</option>
                </select>
              </label>
              <div className="grid gap-3 sm:grid-cols-2">
                <label className="block space-y-1">
                  <span className="text-copy-13 text-muted-foreground">警告阈值（0..1）</span>
                  <Input
                    value={thresholdForm.warnValue}
                    onChange={(e) => setThresholdForm({ ...thresholdForm, warnValue: e.target.value })}
                  />
                </label>
                <label className="block space-y-1">
                  <span className="text-copy-13 text-muted-foreground">严重阈值（0..1）</span>
                  <Input
                    value={thresholdForm.criticalValue}
                    onChange={(e) =>
                      setThresholdForm({ ...thresholdForm, criticalValue: e.target.value })
                    }
                  />
                </label>
              </div>
              <label className="block space-y-1">
                <span className="text-copy-13 text-muted-foreground">备注</span>
                <Input
                  value={thresholdForm.note}
                  onChange={(e) => setThresholdForm({ ...thresholdForm, note: e.target.value })}
                  placeholder="为什么改这个阈值"
                />
              </label>
            </div>
          )}
          <ModalFooter>
            <Button variant="secondary" onClick={() => setThresholdForm(null)}>
              取消
            </Button>
            <Button onClick={() => void submitThreshold()}>保存</Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {busy && (
        <div className="text-copy-12 text-muted-foreground flex items-center gap-2">
          <Loader2 className="size-3 animate-spin" />
          处理中…
        </div>
      )}
    </div>
  );
}

function ShopCard({
  card,
  onOpen,
}: {
  card: AccountHealthShopCard;
  onOpen: () => void;
}) {
  return (
    <div className="border-border rounded-md border px-3 py-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="min-w-0">
          <div className="text-copy-13 text-foreground flex flex-wrap items-center gap-2">
            <span className="text-muted-foreground">#{card.rank}</span>
            <span>{card.shopName}</span>
            <span className="text-muted-foreground">{card.shopCode}</span>
            <span className="text-muted-foreground">{card.platformCode}</span>
          </div>
          <div className="text-copy-12 text-muted-foreground">{card.rankReason}</div>
        </div>
        <div className="flex items-center gap-2">
          <Badge tone={riskLevelTone(card.riskLevel)}>{card.riskLevelLabel}</Badge>
          <Button variant="ghost" size="sm" onClick={onOpen}>
            详情
          </Button>
        </div>
      </div>
      <div className="mt-2 grid gap-2 sm:grid-cols-2 xl:grid-cols-4">
        {card.metrics.map((metric) => (
          <div key={metric.metricCode} className="bg-muted/40 rounded-md px-2 py-1.5">
            <div className="text-copy-12 text-muted-foreground">{metric.metricLabel}</div>
            <div className="flex items-center gap-2">
              <span className="text-copy-14 text-foreground">{metricValueText(metric.metricValue)}</span>
              {metric.source === 'MANUAL' && <Badge tone="info">人工录入</Badge>}
              {metric.source === 'PLATFORM_SYNC' && <Badge tone="success">平台同步</Badge>}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

function Pager({
  page,
  size,
  total,
  onChange,
}: {
  page: number;
  size: number;
  total: number;
  onChange: (page: number) => void;
}) {
  const pages = Math.max(1, Math.ceil(total / size));
  if (total === 0) return null;
  return (
    <div className="text-copy-12 text-muted-foreground flex items-center justify-end gap-2">
      <span>
        第 {page + 1} / {pages} 页 · 共 {total} 条
      </span>
      <Button variant="secondary" size="sm" disabled={page <= 0} onClick={() => onChange(page - 1)}>
        上一页
      </Button>
      <Button
        variant="secondary"
        size="sm"
        disabled={page + 1 >= pages}
        onClick={() => onChange(page + 1)}
      >
        下一页
      </Button>
    </div>
  );
}
