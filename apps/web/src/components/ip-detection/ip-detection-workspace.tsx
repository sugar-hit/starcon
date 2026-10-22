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
  CalendarClock,
  CircleAlert,
  ExternalLink,
  FileSearch,
  Image as ImageIcon,
  Loader2,
  RefreshCw,
  Scale,
  ScanSearch,
  ShieldCheck,
  TriangleAlert,
} from 'lucide-react';
import {
  ALERT_STATUS_OPTIONS,
  CONCLUSION_OPTIONS,
  RISK_LEVEL_OPTIONS,
  SUBJECT_TYPE_OPTIONS,
  alertStatusLabel,
  conclusionLabel,
  conclusionTone,
  decideReview,
  detectSubject,
  emptyState,
  emptyStateKind,
  fetchAlerts,
  fetchCapabilities,
  fetchExternalQueries,
  fetchInspectionPlans,
  fetchInspectionRuns,
  fetchReport,
  fetchReports,
  fetchReviews,
  featureKindLabel,
  fieldLabel,
  hitSourceLabel,
  recommendedActionLabel,
  remediationActionLabel,
  remediationStatusLabel,
  reviewEmptyState,
  reviewStatusLabel,
  riskLevelLabel,
  riskLevelTone,
  runStatusLabel,
  setAlertStatus,
  setRemediationStatus,
  type IpDetectionAlert,
  type IpDetectionCapabilities,
  type IpDetectionExternalQuery,
  type IpDetectionInspectionPlan,
  type IpDetectionInspectionRun,
  type IpDetectionReport,
  type IpDetectionReportDetail,
  type IpDetectionReportPage,
  type IpDetectionReviewTask,
} from './ip-detection-model';

/**
 * 16.4.1.2 检测与预警 工作区。
 *
 * 蓝图：「对商品内容做侵权检测：标题/描述/图片/品牌字段比对侵权库，输出风险等级与命中项；
 * 接入外部商标/专利查询；已上架商品的定期巡检。输入：商品内容与图片；侵权库；外部查询结果。
 * 输出：检测报告 + 预警 + 整改建议。关系：上游 = 16.4.1.1、4.2.1.1；下游 = 16.2.1.2 拦截、16.4.2 授权核验。
 * **图片侵权检测难度高于文本**，误判与漏判都要留人工复核环节。」
 *
 * 🔴 页面上的七条纪律：
 *
 * 1. **风险等级沿用上游口径**（高 / 中 / 低 + 无命中）—— 不是 0..100 的裸数字。
 * 2. **命中必须可解释** —— 每条命中点名条目 / 匹配特征 / 字段 / 命中处原文。
 * 3. **图片命中标「待人工复核」**，**不标**「已判定侵权」。
 * 4. **结论三态分开显示** —— 「无命中」/「待人工复核」/「需整改」不是一回事。
 * 5. **没有拦截 / 放行入口** —— 只显示**建议**（拦截归 16.2.1.2）。
 * 6. **没有删除入口** —— 检测结论与命中项是证据，终态不可回退。
 * 7. **未交付的外部边界灰掉** —— 外部商标库自动查询、以图搜图都**没交付**，如实标出。
 */
const PAGE_SIZE = 20;

export function IpDetectionWorkspace() {
  const [caps, setCaps] = useState<IpDetectionCapabilities | null>(null);
  const [reports, setReports] = useState<IpDetectionReportPage | null>(null);
  const [alerts, setAlerts] = useState<IpDetectionAlert[]>([]);
  const [reviews, setReviews] = useState<IpDetectionReviewTask[]>([]);
  const [queries, setQueries] = useState<IpDetectionExternalQuery[]>([]);
  const [plans, setPlans] = useState<IpDetectionInspectionPlan[]>([]);
  const [runs, setRuns] = useState<IpDetectionInspectionRun[]>([]);

  const [riskLevel, setRiskLevel] = useState('');
  const [conclusion, setConclusion] = useState('');
  const [subjectType, setSubjectType] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(0);

  const [detail, setDetail] = useState<IpDetectionReportDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // ---- 发起检测 ----
  const [detectOpen, setDetectOpen] = useState(false);
  const [detectType, setDetectType] = useState('MANUAL');
  const [detectTarget, setDetectTarget] = useState('');
  const [detectTitle, setDetectTitle] = useState('');
  const [detectDescription, setDetectDescription] = useState('');
  const [detectBullets, setDetectBullets] = useState('');
  const [detectImages, setDetectImages] = useState('');
  const [detectBrand, setDetectBrand] = useState('');
  const [detectIncludeImage, setDetectIncludeImage] = useState(true);

  const loadReports = useCallback(
    async (target: number) => {
      const pageData = await fetchReports(target, PAGE_SIZE, {
        riskLevel,
        conclusion,
        subjectType,
        keyword,
      });
      setReports(pageData);
    },
    [riskLevel, conclusion, subjectType, keyword],
  );

  const refresh = useCallback(async () => {
    setBusy(true);
    setError(null);
    try {
      const [capsData, reportData, alertPage, reviewPage, queryPage, planData, runPage] =
        await Promise.all([
          fetchCapabilities(),
          fetchReports(page, PAGE_SIZE, { riskLevel, conclusion, subjectType, keyword }),
          fetchAlerts(0, PAGE_SIZE),
          fetchReviews(0, PAGE_SIZE, 'PENDING'),
          fetchExternalQueries(0, PAGE_SIZE),
          fetchInspectionPlans(),
          fetchInspectionRuns(0, PAGE_SIZE),
        ]);
      setCaps(capsData);
      setReports(reportData);
      setAlerts(alertPage.items);
      setReviews(reviewPage.items);
      setQueries(queryPage.items);
      setPlans(planData);
      setRuns(runPage.items);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setBusy(false);
      setLoading(false);
    }
  }, [page, riskLevel, conclusion, subjectType, keyword]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const filtered = !!(riskLevel || conclusion || subjectType || keyword);
  const empty = emptyState(emptyStateKind(reports?.total ?? 0, filtered));

  const openReport = useCallback(async (reportId: string) => {
    setBusy(true);
    setError(null);
    try {
      setDetail(await fetchReport(reportId));
    } catch (e) {
      setError(e instanceof Error ? e.message : '读取报告失败');
    } finally {
      setBusy(false);
    }
  }, []);

  const runDetect = useCallback(async () => {
    setBusy(true);
    setError(null);
    try {
      const result = await detectSubject({
        subjectType: detectType,
        listingId: detectType === 'LISTING' ? detectTarget : null,
        draftId: detectType === 'DRAFT' ? detectTarget : null,
        title: detectType === 'MANUAL' ? detectTitle : null,
        description: detectType === 'MANUAL' ? detectDescription : null,
        bulletPoints:
          detectType === 'MANUAL' && detectBullets.trim()
            ? detectBullets
                .split(/\r?\n/)
                .map((line) => line.trim())
                .filter((line) => line.length > 0)
            : [],
        imageUrls:
          detectType === 'MANUAL' && detectImages.trim()
            ? detectImages
                .split(/\r?\n/)
                .map((line) => line.trim())
                .filter((line) => line.length > 0)
            : [],
        brandName: detectType === 'MANUAL' ? detectBrand : null,
        includeImage: detectIncludeImage,
      });
      setDetectOpen(false);
      setDetectTarget('');
      setDetectTitle('');
      setDetectDescription('');
      setDetectBullets('');
      setDetectImages('');
      setDetectBrand('');
      await refresh();
      setDetail(result);
    } catch (e) {
      setError(e instanceof Error ? e.message : '发起检测失败');
    } finally {
      setBusy(false);
    }
  }, [
    detectType,
    detectTarget,
    detectTitle,
    detectDescription,
    detectBullets,
    detectImages,
    detectBrand,
    detectIncludeImage,
    refresh,
  ]);

  const resolveReview = useCallback(
    async (task: IpDetectionReviewTask, status: 'CONFIRMED' | 'DISMISSED') => {
      setBusy(true);
      setError(null);
      try {
        const comment =
          status === 'CONFIRMED'
            ? '人工复核确认是侵权内容'
            : '人工复核判定为误报（内容与权利无关）';
        await decideReview(task.reviewTaskId, status, comment);
        await refresh();
        if (detail) await openReport(detail.report.reportId);
      } catch (e) {
        setError(e instanceof Error ? e.message : '提交复核结论失败');
      } finally {
        setBusy(false);
      }
    },
    [refresh, detail, openReport],
  );

  const ackAlert = useCallback(
    async (alert: IpDetectionAlert) => {
      setBusy(true);
      setError(null);
      try {
        await setAlertStatus(alert.alertId, 'ACKNOWLEDGED');
        await refresh();
      } catch (e) {
        setError(e instanceof Error ? e.message : '确认预警失败');
      } finally {
        setBusy(false);
      }
    },
    [refresh],
  );

  const closeRemediation = useCallback(
    async (remediationId: string) => {
      setBusy(true);
      setError(null);
      try {
        await setRemediationStatus(remediationId, 'DONE');
        await refresh();
        if (detail) await openReport(detail.report.reportId);
      } catch (e) {
        setError(e instanceof Error ? e.message : '更新整改建议失败');
      } finally {
        setBusy(false);
      }
    },
    [refresh, detail, openReport],
  );

  const pendingReviews = useMemo(() => reviews.filter((task) => task.status === 'PENDING'), [reviews]);

  if (loading && !caps) {
    return (
      <div className="flex items-center gap-2 py-10">
        <Loader2 className="text-muted-foreground size-4 animate-spin" />
        <span className="text-label-13">正在加载检测与预警…</span>
      </div>
    );
  }

  if (error && !caps) {
    return (
      <SectionCard title="检测与预警">
        <EmptyState
          icon={<TriangleAlert className="text-destructive size-8" />}
          title="检测与预警加载失败"
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
        title="检测与预警"
        description="把商品内容（标题 / 描述 / 卖点 / 图片 / 品牌）比对侵权库，输出风险等级、命中项、预警与整改建议。图片命中一律先过人工复核。"
        actions={
          <>
            <Button variant="outline" onClick={() => void refresh()} disabled={busy}>
              <RefreshCw className="mr-2 size-4" />
              刷新
            </Button>
            <Button onClick={() => setDetectOpen(true)} disabled={busy}>
              <ScanSearch className="mr-2 size-4" />
              发起检测
            </Button>
          </>
        }
      />

      {/* ---- 统计卡（服务端算的计数，覆盖整个筛选结果集） ---- */}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="报告总数"
          value={reports?.total ?? 0}
          polarity="neutral"
          icon={<FileSearch className="size-4" />}
        />
        <StatCard
          label="高风险"
          value={reports?.riskLevelCounts?.HIGH ?? 0}
          polarity="negative"
          icon={<Scale className="size-4" />}
        />
        <StatCard
          label="待人工复核"
          value={reports?.pendingReviewTotal ?? 0}
          polarity="negative"
          icon={<ImageIcon className="size-4" />}
        />
        <StatCard
          label="需整改"
          value={reports?.conclusionCounts?.ACTION_REQUIRED ?? 0}
          polarity="negative"
          icon={<CircleAlert className="size-4" />}
        />
      </div>

      {error && (
        <div className="border-destructive-soft bg-destructive-soft text-destructive text-copy-13 rounded-md border px-4 py-3">
          {error}
        </div>
      )}

      {/* ---- 能力声明：未交付的外部边界灰掉 ---- */}
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
            <li>· {caps.riskLevelNote}</li>
            <li>· {caps.matchNote}</li>
            <li>· {caps.imageNote}</li>
            <li>· {caps.reviewNote}</li>
            <li>· {caps.externalNote}</li>
            <li>· {caps.inspectionNote}</li>
            <li>· {caps.upstreamNote}</li>
            <li>· {caps.downstreamNote}</li>
          </ul>
        )}
      </SectionCard>

      {/* ---- 🔴 人工复核队列（图片命中自动进这里） ---- */}
      <SectionCard
        title="人工复核队列"
        description="🔴 图片命中自动进这里；文本命中若被判误判，也可由运营提复核。复核结论是证据，终态不可回退。"
      >
        {pendingReviews.length === 0 ? (
          <EmptyState
            icon={<ShieldCheck className="text-muted-foreground size-8" />}
            title={reviewEmptyState().title}
            description={reviewEmptyState().description}
          />
        ) : (
          <ul className="space-y-2">
            {pendingReviews.map((task) => (
              <li
                key={task.reviewTaskId}
                className="border-border flex flex-wrap items-center justify-between gap-2 rounded-md border px-3 py-2"
              >
                <div className="min-w-0">
                  <div className="text-copy-13 text-foreground font-medium">
                    {task.reasonLabel} · 报告 {task.reportId.slice(0, 8)}
                  </div>
                  <div className="text-copy-12 text-muted-foreground">
                    命中 {task.hitId ? task.hitId.slice(0, 8) : '—'} ·{' '}
                    {reviewStatusLabel(task.status)}
                  </div>
                </div>
                <div className="flex shrink-0 gap-2">
                  <Button
                    variant="secondary"
                    disabled={busy}
                    onClick={() => void resolveReview(task, 'DISMISSED')}
                  >
                    判定误报
                  </Button>
                  <Button disabled={busy} onClick={() => void resolveReview(task, 'CONFIRMED')}>
                    确认侵权
                  </Button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </SectionCard>

      {/* ---- 筛选 + 报告清单 ---- */}
      <SectionCard
        title="检测报告"
        description="真分页 + 真筛选。点一行看命中项、预警、整改建议与复核任务。"
      >
        <div className="mb-4 flex flex-wrap items-center gap-2">
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
            value={conclusion}
            onChange={(e) => {
              setConclusion(e.target.value);
              setPage(0);
            }}
          >
            {CONCLUSION_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
          <select
            className="border-input bg-background text-copy-13 h-9 rounded-md border px-2"
            value={subjectType}
            onChange={(e) => {
              setSubjectType(e.target.value);
              setPage(0);
            }}
          >
            {SUBJECT_TYPE_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
          <Input
            className="h-9 w-56"
            placeholder="关键字：商品标题"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                setPage(0);
                void loadReports(0);
              }
            }}
          />
          <Button
            variant="secondary"
            onClick={() => {
              setRiskLevel('');
              setConclusion('');
              setSubjectType('');
              setKeyword('');
              setPage(0);
              void loadReports(0);
            }}
          >
            重置筛选
          </Button>
        </div>

        {reports && reports.items.length === 0 ? (
          <EmptyState
            icon={<FileSearch className="text-muted-foreground size-8" />}
            title={empty.title}
            description={empty.description}
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-copy-13">
              <thead className="text-muted-foreground border-border border-b">
                <tr>
                  <th className="py-2 pr-3 font-medium">检测对象</th>
                  <th className="py-2 pr-3 font-medium">风险等级</th>
                  <th className="py-2 pr-3 font-medium">结论</th>
                  <th className="py-2 pr-3 font-medium">命中</th>
                  <th className="py-2 pr-3 font-medium">建议动作</th>
                  <th className="py-2 pr-3 font-medium">检测时刻</th>
                  <th className="py-2 font-medium" />
                </tr>
              </thead>
              <tbody>
                {(reports?.items ?? []).map((report: IpDetectionReport) => (
                  <tr key={report.reportId} className="border-border border-b">
                    <td className="py-2 pr-3">
                      <div className="text-foreground font-medium">{report.subjectLabel || '（未命名）'}</div>
                      <div className="text-copy-12 text-muted-foreground">
                        {report.subjectTypeLabel} · {report.triggerType === 'INSPECTION' ? '巡检产生' : '人工发起'}
                      </div>
                    </td>
                    <td className="py-2 pr-3">
                      <Badge tone={riskLevelTone(report.riskLevel)}>{riskLevelLabel(report.riskLevel)}</Badge>
                    </td>
                    <td className="py-2 pr-3">
                      <Badge tone={conclusionTone(report.conclusion)}>{conclusionLabel(report.conclusion)}</Badge>
                    </td>
                    <td className="py-2 pr-3">
                      <div>文本 {report.textHitCount} · 图片 {report.imageHitCount}</div>
                      {report.pendingReviewCount > 0 && (
                        <div className="text-copy-12 text-muted-foreground">
                          待复核 {report.pendingReviewCount}
                        </div>
                      )}
                    </td>
                    <td className="py-2 pr-3">{recommendedActionLabel(report.recommendedAction)}</td>
                    <td className="text-muted-foreground py-2 pr-3">
                      {report.createdAt ? new Date(report.createdAt).toLocaleString('zh-CN') : '—'}
                    </td>
                    <td className="py-2">
                      <Button variant="secondary" onClick={() => void openReport(report.reportId)}>
                        详情
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {reports && reports.total > reports.size && (
          <div className="mt-4 flex items-center gap-2">
            <Button
              variant="secondary"
              disabled={page === 0}
              onClick={() => {
                const next = Math.max(0, page - 1);
                setPage(next);
                void loadReports(next);
              }}
            >
              上一页
            </Button>
            <span className="text-copy-12 text-muted-foreground">
              第 {page + 1} 页 · 共 {reports.total} 份
            </span>
            <Button
              variant="secondary"
              disabled={(page + 1) * reports.size >= reports.total}
              onClick={() => {
                const next = page + 1;
                setPage(next);
                void loadReports(next);
              }}
            >
              下一页
            </Button>
          </div>
        )}
      </SectionCard>

      {/* ---- 预警 ---- */}
      <SectionCard
        title="预警"
        description="🔴 预警不是拦截：处置动作归 16.2.1.2，本页只负责把「发现了什么、建议怎么办」说清楚。"
      >
        {alerts.length === 0 ? (
          <EmptyState
            icon={<ShieldCheck className="text-muted-foreground size-8" />}
            title="还没有预警"
            description="有命中才会发预警 —— 「没查出错」不是预警。"
          />
        ) : (
          <ul className="space-y-2">
            {alerts.map((alert) => (
              <li
                key={alert.alertId}
                className="border-border flex flex-wrap items-start justify-between gap-2 rounded-md border px-3 py-2"
              >
                <div className="min-w-0">
                  <div className="text-copy-13 text-foreground font-medium">{alert.title}</div>
                  <div className="text-copy-12 text-muted-foreground">{alert.detail}</div>
                  <div className="text-copy-12 text-muted-foreground mt-1">
                    {alert.alertLevelLabel} · {alert.alertTypeLabel} · {alertStatusLabel(alert.status)}
                  </div>
                </div>
                <div className="flex shrink-0 gap-2">
                  <Button variant="secondary" onClick={() => void openReport(alert.reportId)}>
                    看报告
                  </Button>
                  {alert.status === 'OPEN' && (
                    <Button disabled={busy} onClick={() => void ackAlert(alert)}>
                      确认
                    </Button>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </SectionCard>

      {/* ---- 外部商标 / 专利查询（登记承接） ---- */}
      <SectionCard
        title="外部商标 / 专利查询"
        description="🔴 本仓没有外部商标库的凭据与网络出口 ⇒ 这里承接的是「登记进来的查询与结果」，不是自动外呼。"
      >
        {queries.length === 0 ? (
          <EmptyState
            icon={<ExternalLink className="text-muted-foreground size-8" />}
            title="还没有登记外部查询"
            description="在外部商标 / 专利库查到的结果，登记进来之后会与本地侵权库一起参与比对。"
          />
        ) : (
          <ul className="space-y-2">
            {queries.map((query) => (
              <li
                key={query.queryId}
                className="border-border flex flex-wrap items-center justify-between gap-2 rounded-md border px-3 py-2"
              >
                <div className="min-w-0">
                  <div className="text-copy-13 text-foreground font-medium">
                    {query.keyword}（{query.queryCode}）
                  </div>
                  <div className="text-copy-12 text-muted-foreground">
                    {query.rightTypeLabel} · {query.queryStatusLabel} · 结果 {query.resultCount} 条
                    {query.sourceName ? ` · 来源 ${query.sourceName}` : ''}
                  </div>
                </div>
              </li>
            ))}
          </ul>
        )}
      </SectionCard>

      {/* ---- 巡检 ---- */}
      <SectionCard
        title="定期巡检"
        description="🔴 巡检对象是已上架商品的当前线上内容。本服务不装定时器 —— 由外部调度按间隔调一次运行入口。"
      >
        <div className="space-y-3">
          {plans.length === 0 ? (
            <p className="text-copy-13 text-muted-foreground">还没有巡检计划。</p>
          ) : (
            <ul className="space-y-2">
              {plans.map((plan) => (
                <li
                  key={plan.planId}
                  className="border-border flex flex-wrap items-center justify-between gap-2 rounded-md border px-3 py-2"
                >
                  <div className="min-w-0">
                    <div className="text-copy-13 text-foreground font-medium">
                      {plan.planCode} · {plan.scopeLabel}
                    </div>
                    <div className="text-copy-12 text-muted-foreground">
                      每 {plan.intervalDays} 天 ·{' '}
                      {plan.includeImage ? '含图片检测（图片命中一律进人工复核）' : '🔴 不含图片检测'} ·{' '}
                      {plan.enabled ? '已启用' : '已停用'}
                    </div>
                  </div>
                  <Badge tone={plan.enabled ? 'success' : 'neutral'}>
                    {plan.enabled ? '已启用' : '已停用'}
                  </Badge>
                </li>
              ))}
            </ul>
          )}

          {runs.length > 0 && (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-copy-13">
                <thead className="text-muted-foreground border-border border-b">
                  <tr>
                    <th className="py-2 pr-3 font-medium">运行</th>
                    <th className="py-2 pr-3 font-medium">状态</th>
                    <th className="py-2 pr-3 font-medium">扫描商品</th>
                    <th className="py-2 pr-3 font-medium">产出报告</th>
                    <th className="py-2 pr-3 font-medium">查出命中</th>
                    <th className="py-2 font-medium">结束时刻</th>
                  </tr>
                </thead>
                <tbody>
                  {runs.map((run) => (
                    <tr key={run.runId} className="border-border border-b">
                      <td className="py-2 pr-3">
                        <div className="flex items-center gap-1">
                          <CalendarClock className="text-muted-foreground size-4" />
                          {run.planCode || run.runId.slice(0, 8)}
                        </div>
                      </td>
                      <td className="py-2 pr-3">{runStatusLabel(run.status)}</td>
                      <td className="py-2 pr-3">{run.listingScanned}</td>
                      <td className="py-2 pr-3">{run.reportCount}</td>
                      <td className="py-2 pr-3">{run.hitListingCount}</td>
                      <td className="text-muted-foreground py-2">
                        {run.finishedAt ? new Date(run.finishedAt).toLocaleString('zh-CN') : '—'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---- 发起检测 ---- */}
      <Modal open={detectOpen} onOpenChange={setDetectOpen}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>发起检测</ModalTitle>
            <ModalDescription>
              🔴 图片维度的比对是**标识文本比对**（文件名 / 替代文本 / URL），**不是**以图搜图；
              图片命中**一律**先过人工复核。
            </ModalDescription>
          </ModalHeader>
          <div className="space-y-3">
            <div className="flex flex-wrap gap-2">
              {SUBJECT_TYPE_OPTIONS.filter((option) => option.value).map((option) => (
                <Button
                  key={option.value}
                  variant={detectType === option.value ? 'default' : 'outline'}
                  onClick={() => setDetectType(option.value)}
                >
                  {option.label}
                </Button>
              ))}
            </div>

            {detectType === 'LISTING' && (
              <Input
                placeholder="已上架商品 id（listing id）"
                value={detectTarget}
                onChange={(e) => setDetectTarget(e.target.value)}
              />
            )}
            {detectType === 'DRAFT' && (
              <Input
                placeholder="刊登编辑器草稿 id（draft id）"
                value={detectTarget}
                onChange={(e) => setDetectTarget(e.target.value)}
              />
            )}

            {detectType === 'MANUAL' && (
              <>
                <Input
                  placeholder="标题（必填）"
                  value={detectTitle}
                  onChange={(e) => setDetectTitle(e.target.value)}
                />
                <textarea
                  className="border-input bg-background text-copy-13 min-h-20 w-full rounded-md border px-3 py-2"
                  placeholder="描述"
                  value={detectDescription}
                  onChange={(e) => setDetectDescription(e.target.value)}
                />
                <textarea
                  className="border-input bg-background text-copy-13 min-h-20 w-full rounded-md border px-3 py-2"
                  placeholder="卖点（一行一条）"
                  value={detectBullets}
                  onChange={(e) => setDetectBullets(e.target.value)}
                />
                <textarea
                  className="border-input bg-background text-copy-13 min-h-20 w-full rounded-md border px-3 py-2"
                  placeholder="图片（一行一个 URL 或文件名）"
                  value={detectImages}
                  onChange={(e) => setDetectImages(e.target.value)}
                />
                <Input
                  placeholder="品牌名（可空 —— 留空表示品牌维度没查，不表示没有品牌风险）"
                  value={detectBrand}
                  onChange={(e) => setDetectBrand(e.target.value)}
                />
              </>
            )}

            <label className="text-copy-13 flex items-center gap-2">
              <input
                type="checkbox"
                checked={detectIncludeImage}
                onChange={(e) => setDetectIncludeImage(e.target.checked)}
              />
              包含图片检测（默认含 —— 关掉它等于承认「图片不用查」，那是漏判）
            </label>
          </div>
          <ModalFooter>
            <Button variant="secondary" onClick={() => setDetectOpen(false)}>
              取消
            </Button>
            <Button
              disabled={busy || (detectType === 'MANUAL' ? !detectTitle.trim() : !detectTarget.trim())}
              onClick={() => void runDetect()}
            >
              {busy ? <Loader2 className="mr-2 size-4 animate-spin" /> : null}
              开始检测
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---- 报告详情 ---- */}
      <Modal open={!!detail} onOpenChange={(open) => !open && setDetail(null)}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>{detail?.report.subjectLabel || '检测报告'}</ModalTitle>
            <ModalDescription>
              {detail?.report.subjectTypeLabel} · {detail?.report.riskLevelLabel} ·{' '}
              {detail?.report.conclusionLabel} · 建议：{detail?.report.recommendedActionLabel}
            </ModalDescription>
          </ModalHeader>
          <div className="space-y-4">
            {detail?.report.sourceNote && (
              <p className="text-copy-12 text-muted-foreground">{detail.report.sourceNote}</p>
            )}

            <div>
              <div className="text-copy-13 text-foreground mb-2 font-medium">
                命中项（{detail?.hits.length ?? 0}）
              </div>
              {detail && detail.hits.length === 0 ? (
                <p className="text-copy-13 text-muted-foreground">这条报告没有命中任何侵权特征。</p>
              ) : (
                <ul className="space-y-2">
                  {(detail?.hits ?? []).map((hit) => (
                    <li key={hit.hitId} className="border-border rounded-md border px-3 py-2">
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge tone={riskLevelTone(hit.riskLevel)}>{riskLevelLabel(hit.riskLevel)}</Badge>
                        <Badge tone="neutral">{fieldLabel(hit.field)}</Badge>
                        {hit.needsManualReview ? (
                          <Badge tone="warning">待人工复核</Badge>
                        ) : (
                          <Badge tone="success">可判定</Badge>
                        )}
                        {hit.reviewStatus && (
                          <Badge tone="neutral">{reviewStatusLabel(hit.reviewStatus)}</Badge>
                        )}
                      </div>
                      <div className="text-copy-12 text-muted-foreground mt-1">
                        来源：{hitSourceLabel(hit)} · 权利类型 {hit.rightType} · 特征分类{' '}
                        {featureKindLabel(hit.featureKind)}
                      </div>
                      <div className="text-copy-13 text-foreground mt-1">
                        命中特征：{hit.matchedFeature}
                      </div>
                      {hit.matchedText && (
                        <div className="text-copy-12 text-muted-foreground mt-1">
                          命中处原文：{hit.matchedText}
                        </div>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </div>

            {detail && detail.reviewTasks.length > 0 && (
              <div>
                <div className="text-copy-13 text-foreground mb-2 font-medium">复核任务</div>
                <ul className="space-y-1">
                  {detail.reviewTasks.map((task) => (
                    <li key={task.reviewTaskId} className="text-copy-12 text-muted-foreground">
                      {task.reasonLabel} · {reviewStatusLabel(task.status)}
                      {task.reviewComment ? ` · ${task.reviewComment}` : ''}
                    </li>
                  ))}
                </ul>
              </div>
            )}

            {detail && detail.remediations.length > 0 && (
              <div>
                <div className="text-copy-13 text-foreground mb-2 font-medium">整改建议</div>
                <ul className="space-y-2">
                  {detail.remediations.map((item) => (
                    <li key={item.remediationId} className="border-border rounded-md border px-3 py-2">
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <div className="text-copy-13 text-foreground">
                          {remediationActionLabel(item.actionCode)} · {remediationStatusLabel(item.status)}
                        </div>
                        {item.status === 'OPEN' || item.status === 'IN_PROGRESS' ? (
                          <Button
                            variant="secondary"
                            disabled={busy}
                            onClick={() => void closeRemediation(item.remediationId)}
                          >
                            标记办完
                          </Button>
                        ) : null}
                      </div>
                      <div className="text-copy-12 text-muted-foreground mt-1">{item.suggestion}</div>
                    </li>
                  ))}
                </ul>
              </div>
            )}

            {detail && detail.alerts.length > 0 && (
              <div>
                <div className="text-copy-13 text-foreground mb-2 font-medium">预警</div>
                <ul className="space-y-1">
                  {detail.alerts.map((alert) => (
                    <li key={alert.alertId} className="text-copy-12 text-muted-foreground">
                      {alert.alertLevelLabel} · {alert.alertTypeLabel} · {alertStatusLabel(alert.status)}
                    </li>
                  ))}
                </ul>
              </div>
            )}

            {detail && (
              <p className="text-copy-12 text-muted-foreground">{detail.note}</p>
            )}
          </div>
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
