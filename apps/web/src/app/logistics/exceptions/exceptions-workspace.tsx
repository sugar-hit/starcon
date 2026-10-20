'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { AlertTriangle, Clock, PackageSearch, RefreshCw, ShieldAlert, Siren } from 'lucide-react';
import {
  Badge,
  Button,
  DetailDrawer,
  DetailDrawerBody,
  DetailDrawerContent,
  DetailDrawerDescription,
  DetailDrawerHeader,
  DetailDrawerTitle,
  EmptyState,
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
  StatCard,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from '@starcon/ui';
import {
  ACTION_TYPES,
  CASE_STATUSES,
  CLAIM_TYPES,
  DOWNSTREAM_MODULES,
  EXCEPTION_TYPES,
  SEVERITIES,
  TRACKING_NODES,
  actionTypeLabel,
  addDisposition,
  buildCasePayload,
  buildClaimPayload,
  buildDispositionPayload,
  buildRulePayload,
  caseStatusLabel,
  claimTotalsText,
  createExceptionCase,
  createExceptionClaim,
  downstreamModuleLabel,
  downstreamStatusLabel,
  durationText,
  emptyCaseForm,
  emptyClaimForm,
  emptyDispositionForm,
  emptyRuleForm,
  errorText,
  exceptionTypeLabel,
  fetchCapabilities,
  fetchCase,
  fetchCases,
  fetchClaims,
  fetchDispositions,
  fetchOverview,
  fetchRules,
  moneyText,
  quantityText,
  saveExceptionRule,
  scanExceptions,
  severityLabel,
  sourceLabel,
  totalPages,
  type CaseForm,
  type ClaimForm,
  type DispositionForm,
  type ExceptionCaseDetail,
  type ExceptionCasePage,
  type ExceptionCaseRow,
  type ExceptionClaimPage,
  type ExceptionDispositionPage,
  type ExceptionRulePage,
  type LogisticsExceptionCapabilities,
  type LogisticsExceptionOverview,
  type RuleForm,
} from './exceptions-model';

/**
 * 10.3.1.2 异常件。
 *
 * <p>蓝图：「识别与处理异常件：轨迹停滞、妥投失败、地址问题、清关滞留、破损丢件、买家投诉；
 * 支持<b>联系承运商、发起理赔、改址、补发</b>。输入：轨迹异常规则；被动异常标记；客服反馈。
 * 输出：异常件清单 + 处置记录 + 理赔/补发单。」并加粗「<b>异常件必须有人负责到底</b>，
 * 否则变成买家差评与平台纠纷。」
 *
 * <p>🔴 界面上唯一不可让步的一条：**异常件永远有责任人、永远有时限**。
 *
 * <ul>
 *   <li>清单里每一行都带责任人与到期时刻；逾期件单独标红并可一键筛出来。</li>
 *   <li>建件表单的异常类型下拉框第一项是「—— 请显式选择异常类型 ——」（空值），
 *       <b>不是</b>某个默认值 —— 承运商的异常码是自由文本，页面不替人猜。</li>
 *   <li>结案 / 作废**只能**经「追加处置」动作达成，且结案必须写理由。</li>
 *   <li>下游任务只显示「待通知」：11 售后 / 15 客服 / 7 库存当前未交付，
 *       页面**不提供**「标记已通知 / 已受理」的入口。</li>
 *   <li>金额**按币种分行**显示，页面不做任何汇率换算。</li>
 * </ul>
 */
export function ExceptionsWorkspace() {
  const [capabilities, setCapabilities] = useState<LogisticsExceptionCapabilities | null>(null);
  const [overview, setOverview] = useState<LogisticsExceptionOverview | null>(null);
  const [cases, setCases] = useState<ExceptionCasePage | null>(null);
  const [dispositions, setDispositions] = useState<ExceptionDispositionPage | null>(null);
  const [claims, setClaims] = useState<ExceptionClaimPage | null>(null);
  const [rules, setRules] = useState<ExceptionRulePage | null>(null);

  // 清单筛选与分页
  const [exceptionType, setExceptionType] = useState('');
  const [severity, setSeverity] = useState('');
  const [status, setStatus] = useState('');
  const [carrierCode, setCarrierCode] = useState('');
  const [keyword, setKeyword] = useState('');
  const [overdueOnly, setOverdueOnly] = useState(false);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  // 详情抽屉
  const [detail, setDetail] = useState<ExceptionCaseDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  // 建件弹窗
  const [caseOpen, setCaseOpen] = useState(false);
  const [caseForm, setCaseForm] = useState<CaseForm>(emptyCaseForm());
  const [caseError, setCaseError] = useState('');

  // 处置弹窗
  const [dispositionTarget, setDispositionTarget] = useState<ExceptionCaseRow | null>(null);
  const [dispositionForm, setDispositionForm] = useState<DispositionForm>(emptyDispositionForm());
  const [dispositionError, setDispositionError] = useState('');

  // 单据弹窗
  const [claimTarget, setClaimTarget] = useState<ExceptionCaseRow | null>(null);
  const [claimForm, setClaimForm] = useState<ClaimForm>(emptyClaimForm());
  const [claimError, setClaimError] = useState('');

  // 规则弹窗
  const [ruleOpen, setRuleOpen] = useState(false);
  const [ruleForm, setRuleForm] = useState<RuleForm>(emptyRuleForm());
  const [ruleError, setRuleError] = useState('');
  const [scanNote, setScanNote] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [caps, ov, cs, ds, cls, rs] = await Promise.all([
        fetchCapabilities(),
        fetchOverview(),
        fetchCases({
          exceptionType: exceptionType || undefined,
          severity: severity || undefined,
          status: status || undefined,
          carrierCode: carrierCode || undefined,
          keyword: keyword || undefined,
          overdueOnly: overdueOnly || undefined,
          page,
          size,
        }),
        fetchDispositions({ page: 0, size: 50 }),
        fetchClaims({ page: 0, size: 50 }),
        fetchRules(undefined, 0, 50),
      ]);
      setCapabilities(caps);
      setOverview(ov);
      setCases(cs);
      setDispositions(ds);
      setClaims(cls);
      setRules(rs);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [exceptionType, severity, status, carrierCode, keyword, overdueOnly, page, size]);

  useEffect(() => {
    void load();
  }, [load]);

  const openDetail = useCallback(async (id: string) => {
    setDetailLoading(true);
    setError('');
    try {
      setDetail(await fetchCase(id));
    } catch (e) {
      setError(errorText(e));
      setDetail(null);
    } finally {
      setDetailLoading(false);
    }
  }, []);

  const pendingCases = useMemo(
    () => (overview?.openCount ?? 0) + (overview?.investigatingCount ?? 0),
    [overview],
  );

  // ---- 登记异常件 ----
  const submitCase = async () => {
    const built = buildCasePayload(caseForm);
    if ('error' in built) {
      setCaseError(built.error);
      return;
    }
    setBusy(true);
    setCaseError('');
    try {
      await createExceptionCase(built.payload);
      setCaseOpen(false);
      setCaseForm(emptyCaseForm());
      await load();
    } catch (e) {
      setCaseError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  // ---- 追加处置 ----
  const submitDisposition = async () => {
    if (!dispositionTarget) return;
    const built = buildDispositionPayload(dispositionForm);
    if ('error' in built) {
      setDispositionError(built.error);
      return;
    }
    setBusy(true);
    setDispositionError('');
    try {
      await addDisposition(dispositionTarget.id, built.payload);
      setDispositionTarget(null);
      setDispositionForm(emptyDispositionForm());
      await load();
      await openDetail(dispositionTarget.id);
    } catch (e) {
      setDispositionError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  // ---- 发起单据 ----
  const submitClaim = async () => {
    if (!claimTarget) return;
    const built = buildClaimPayload(claimForm);
    if ('error' in built) {
      setClaimError(built.error);
      return;
    }
    setBusy(true);
    setClaimError('');
    try {
      await createExceptionClaim(claimTarget.id, built.payload);
      setClaimTarget(null);
      setClaimForm(emptyClaimForm());
      await load();
      await openDetail(claimTarget.id);
    } catch (e) {
      setClaimError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  // ---- 登记规则 ----
  const submitRule = async () => {
    const built = buildRulePayload(ruleForm);
    if ('error' in built) {
      setRuleError(built.error);
      return;
    }
    setBusy(true);
    setRuleError('');
    try {
      await saveExceptionRule(built.payload);
      setRuleOpen(false);
      setRuleForm(emptyRuleForm());
      await load();
    } catch (e) {
      setRuleError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  // ---- 规则扫描 ----
  const runScan = async () => {
    setBusy(true);
    setScanNote('');
    setError('');
    try {
      const result = await scanExceptions();
      setScanNote(
        `扫描 ${result.ruleCount} 条规则：候选 ${result.candidateCount} 票，新建 ${result.createdCount} 件，` +
          `跳过 ${result.skippedCount} 票（已有未结同类型异常件）。`,
      );
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  const pageCount = totalPages(cases?.total ?? 0, size);

  return (
    <>
      <PageHeader
        title="异常件"
        description="轨迹停滞 / 妥投失败 / 地址问题 / 清关滞留 / 破损丢件 / 买家投诉：识别、建件、处置到底。"
      />

      {error ? (
        <Notice tone="danger" title="操作未完成">
          {error}
        </Notice>
      ) : null}

      {capabilities ? (
        <Notice tone="warning" title="异常件必须有人负责到底">
          {capabilities.responsibilityNote}
        </Notice>
      ) : null}

      <div className="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="异常件"
          value={String(overview?.caseCount ?? 0)}
          comparisonLabel="本租户建过的异常件"
        />
        <StatCard
          label="待跟进"
          value={String(pendingCases)}
          comparisonLabel="待处理 + 处置中（每件都有责任人与时限）"
        />
        <StatCard
          label="已逾期"
          value={String(overview?.overdueCount ?? 0)}
          comparisonLabel="超过责任时限且尚未结案"
        />
        <StatCard
          label="待通知下游"
          value={String(overview?.pendingDownstreamCount ?? 0)}
          comparisonLabel="11 售后 / 15 客服 / 7 库存都还没交付，状态恒为「待通知」"
        />
      </div>

      {overview && overview.claimTotals.length > 0 ? (
        <div className="mt-4">
          <Notice tone="info" title="理赔金额（按币种分行）">
            {claimTotalsText(overview.claimTotals)} —— 本模块不做汇率换算，跨币种绝不相加。
          </Notice>
        </div>
      ) : null}

      <Tabs defaultValue="cases" className="mt-6">
        <TabsList>
          <TabsTrigger value="cases">异常件</TabsTrigger>
          <TabsTrigger value="dispositions">处置记录</TabsTrigger>
          <TabsTrigger value="claims">理赔 / 补发单</TabsTrigger>
          <TabsTrigger value="rules">异常规则</TabsTrigger>
        </TabsList>

        {/* ---------------- 异常件 ---------------- */}
        <TabsContent value="cases">
          <SectionCard
            title="异常件清单"
            description="每一件都有责任人、类型、到期时刻；同一票同一类型同时最多一件未结。"
            extra={
              <div className="flex gap-2">
                <Button size="sm" variant="ghost" onClick={() => void load()} disabled={busy}>
                  <RefreshCw className="mr-1.5 h-4 w-4" />
                  刷新
                </Button>
                <Button size="sm" onClick={() => setCaseOpen(true)} disabled={busy}>
                  登记异常件
                </Button>
              </div>
            }
          >
            <div className="mb-3 flex flex-wrap items-center gap-2 text-sm">
              <input
                className="rounded border px-2 py-1"
                placeholder="异常件号 / 运单号 / 订单号 / 反馈"
                value={keyword}
                onChange={(e) => {
                  setKeyword(e.target.value);
                  setPage(0);
                }}
              />
              <select
                className="rounded border px-2 py-1"
                value={exceptionType}
                onChange={(e) => {
                  setExceptionType(e.target.value);
                  setPage(0);
                }}
              >
                <option value="">全部类型</option>
                {EXCEPTION_TYPES.map((t) => (
                  <option key={t} value={t}>
                    {exceptionTypeLabel(t)}
                  </option>
                ))}
              </select>
              <select
                className="rounded border px-2 py-1"
                value={severity}
                onChange={(e) => {
                  setSeverity(e.target.value);
                  setPage(0);
                }}
              >
                <option value="">全部严重度</option>
                {SEVERITIES.map((s) => (
                  <option key={s} value={s}>
                    {severityLabel(s)}
                  </option>
                ))}
              </select>
              <select
                className="rounded border px-2 py-1"
                value={status}
                onChange={(e) => {
                  setStatus(e.target.value);
                  setPage(0);
                }}
              >
                <option value="">全部状态</option>
                {CASE_STATUSES.map((s) => (
                  <option key={s} value={s}>
                    {caseStatusLabel(s)}
                  </option>
                ))}
              </select>
              <input
                className="rounded border px-2 py-1"
                placeholder="承运商"
                value={carrierCode}
                onChange={(e) => {
                  setCarrierCode(e.target.value);
                  setPage(0);
                }}
              />
              <label className="flex items-center gap-1">
                <input
                  type="checkbox"
                  checked={overdueOnly}
                  onChange={(e) => {
                    setOverdueOnly(e.target.checked);
                    setPage(0);
                  }}
                />
                只看逾期
              </label>
              <span className="text-muted-foreground">共 {cases?.total ?? 0} 条</span>
            </div>

            {loading ? (
              <p className="text-muted-foreground text-sm">正在加载异常件…</p>
            ) : !cases || cases.rows.length === 0 ? (
              <EmptyState
                icon={<PackageSearch className="h-6 w-6" />}
                title="没有异常件"
                description="异常件来自三处：轨迹异常规则命中（扫描）、上游 10.3.1.1 的被动异常标记、客服反馈。"
              />
            ) : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground border-b text-left">
                    <th className="py-2">异常件号 / 运单</th>
                    <th className="py-2">类型 / 来源</th>
                    <th className="py-2">严重度</th>
                    <th className="py-2">停滞时长</th>
                    <th className="py-2">状态</th>
                    <th className="py-2">责任时限</th>
                    <th className="py-2">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {cases.rows.map((row) => (
                    <tr key={row.id} className="border-b">
                      <td className="py-2">
                        <div className="font-medium">{row.caseNo}</div>
                        <div className="text-muted-foreground">{row.waybillNo || '（无运单号）'}</div>
                      </td>
                      <td className="py-2">
                        <div>{row.exceptionTypeLabel}</div>
                        <div className="text-muted-foreground">{sourceLabel(row.source)}</div>
                      </td>
                      <td className="py-2">
                        <Badge tone={row.severity === 'CRITICAL' ? 'danger' : 'neutral'}>
                          {row.severityLabel}
                        </Badge>
                      </td>
                      <td className="py-2">{durationText(row.stalledDurationMinutes)}</td>
                      <td className="py-2">{row.statusLabel}</td>
                      <td className="py-2">
                        {row.overdue ? (
                          <span className="text-destructive flex items-center gap-1">
                            <Siren className="h-4 w-4" />已逾期
                          </span>
                        ) : (
                          <span className="text-muted-foreground">
                            <Clock className="mr-1 inline h-4 w-4" />
                            {new Date(row.dueAt).toLocaleString('zh-CN')}
                          </span>
                        )}
                      </td>
                      <td className="py-2">
                        <div className="flex gap-2">
                          <Button size="sm" variant="ghost" onClick={() => void openDetail(row.id)}>
                            详情
                          </Button>
                          <Button
                            size="sm"
                            variant="ghost"
                            disabled={row.status === 'RESOLVED' || row.status === 'VOID'}
                            onClick={() => {
                              setDispositionTarget(row);
                              setDispositionForm(emptyDispositionForm());
                              setDispositionError('');
                            }}
                          >
                            处置
                          </Button>
                          <Button
                            size="sm"
                            variant="ghost"
                            disabled={row.status === 'RESOLVED' || row.status === 'VOID'}
                            onClick={() => {
                              setClaimTarget(row);
                              setClaimForm(emptyClaimForm());
                              setClaimError('');
                            }}
                          >
                            单据
                          </Button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}

            {cases && cases.total > 0 ? (
              <div className="mt-3 flex items-center gap-2 text-sm">
                <Button
                  size="sm"
                  variant="ghost"
                  disabled={page <= 0}
                  onClick={() => setPage(Math.max(page - 1, 0))}
                >
                  上一页
                </Button>
                <span className="text-muted-foreground">
                  第 {page + 1} / {pageCount} 页（每页 {size} 条）
                </span>
                <Button
                  size="sm"
                  variant="ghost"
                  disabled={page + 1 >= pageCount}
                  onClick={() => setPage(page + 1)}
                >
                  下一页
                </Button>
                <select
                  className="rounded border px-2 py-1"
                  value={String(size)}
                  onChange={(e) => {
                    setSize(Number(e.target.value));
                    setPage(0);
                  }}
                >
                  <option value="20">20 条 / 页</option>
                  <option value="50">50 条 / 页</option>
                  <option value="100">100 条 / 页</option>
                </select>
              </div>
            ) : null}
          </SectionCard>
        </TabsContent>

        {/* ---------------- 处置记录 ---------------- */}
        <TabsContent value="dispositions">
          <SectionCard
            title="处置记录"
            description="当时做了什么 —— 处置留痕不可改写，要更正就追加新的一条。"
          >
            {loading ? (
              <p className="text-muted-foreground text-sm">正在加载处置记录…</p>
            ) : !dispositions || dispositions.rows.length === 0 ? (
              <EmptyState
                icon={<ShieldAlert className="h-6 w-6" />}
                title="还没有处置记录"
                description="联系承运商 / 联系买家 / 联系客服 / 发起理赔 / 请求改址 / 请求补发 / 升级 / 转派 / 结案。"
              />
            ) : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground border-b text-left">
                    <th className="py-2">动作</th>
                    <th className="py-2">异常件</th>
                    <th className="py-2">状态</th>
                    <th className="py-2">金额</th>
                    <th className="py-2">下游待通知</th>
                    <th className="py-2">发生时刻</th>
                    <th className="py-2">说明</th>
                  </tr>
                </thead>
                <tbody>
                  {dispositions.rows.map((row) => (
                    <tr key={row.id} className="border-b">
                      <td className="py-2">{row.actionTypeLabel}</td>
                      <td className="py-2">
                        <div>{row.caseNo}</div>
                        <div className="text-muted-foreground">{row.waybillNo || '—'}</div>
                      </td>
                      <td className="py-2">{row.statusLabel}</td>
                      <td className="py-2">{moneyText(row.amount, row.currency)}</td>
                      <td className="py-2">
                        {row.downstreamTaskCount > 0 ? `${row.downstreamTaskCount} 条待通知` : '—'}
                      </td>
                      <td className="py-2">
                        {new Date(row.occurredAt).toLocaleString('zh-CN')}
                      </td>
                      <td className="py-2">{row.detail || '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>
        </TabsContent>

        {/* ---------------- 单据 ---------------- */}
        <TabsContent value="claims">
          <SectionCard
            title="理赔 / 补发单"
            description="处置结果的单据。金额与数量都是 NUMERIC(19,6)，禁浮点；跨币种分行，绝不相加。"
          >
            {loading ? (
              <p className="text-muted-foreground text-sm">正在加载单据…</p>
            ) : !claims || claims.rows.length === 0 ? (
              <EmptyState
                icon={<AlertTriangle className="h-6 w-6" />}
                title="还没有单据"
                description="理赔单必须有金额与币种，补发单必须有数量 —— 缺一个服务端就拒。"
              />
            ) : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground border-b text-left">
                    <th className="py-2">单号</th>
                    <th className="py-2">类型</th>
                    <th className="py-2">异常件</th>
                    <th className="py-2">状态</th>
                    <th className="py-2">金额 / 数量</th>
                    <th className="py-2">创建时刻</th>
                  </tr>
                </thead>
                <tbody>
                  {claims.rows.map((row) => (
                    <tr key={row.id} className="border-b">
                      <td className="py-2">{row.claimNo}</td>
                      <td className="py-2">{row.claimTypeLabel}</td>
                      <td className="py-2">
                        <div>{row.caseNo}</div>
                        <div className="text-muted-foreground">{row.waybillNo || '—'}</div>
                      </td>
                      <td className="py-2">{row.statusLabel}</td>
                      <td className="py-2">
                        {row.claimType === 'RESHIP'
                          ? quantityText(row.qty)
                          : moneyText(row.amount, row.currency)}
                      </td>
                      <td className="py-2">{new Date(row.createdAt).toLocaleString('zh-CN')}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>
        </TabsContent>

        {/* ---------------- 规则 ---------------- */}
        <TabsContent value="rules">
          <SectionCard
            title="轨迹异常规则"
            description="规则是租户可维护的表：各承运商的停滞口径本来就不一样，写死等于宣称「所有渠道一个阈值」。"
            extra={
              <div className="flex gap-2">
                <Button size="sm" variant="ghost" onClick={() => void runScan()} disabled={busy}>
                  <RefreshCw className="mr-1.5 h-4 w-4" />
                  按规则扫描
                </Button>
                <Button size="sm" onClick={() => setRuleOpen(true)} disabled={busy}>
                  登记规则
                </Button>
              </div>
            }
          >
            {scanNote ? (
              <div className="mb-3">
                <Notice tone="info" title="扫描结果">
                  {scanNote}
                </Notice>
              </div>
            ) : null}
            {loading ? (
              <p className="text-muted-foreground text-sm">正在加载规则…</p>
            ) : !rules || rules.rows.length === 0 ? (
              <EmptyState
                icon={<ShieldAlert className="h-6 w-6" />}
                title="还没有异常规则"
                description="没有规则时，异常件只能由被动异常标记或客服反馈建起；规则扫描是「主动发现停滞」的路径。"
              />
            ) : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground border-b text-left">
                    <th className="py-2">规则编码</th>
                    <th className="py-2">命中类型</th>
                    <th className="py-2">命中条件</th>
                    <th className="py-2">严重度</th>
                    <th className="py-2">责任时限</th>
                    <th className="py-2">启用</th>
                  </tr>
                </thead>
                <tbody>
                  {rules.rows.map((row) => (
                    <tr key={row.id} className="border-b">
                      <td className="py-2">
                        <div className="font-medium">{row.ruleCode}</div>
                        {row.note ? (
                          <div className="text-muted-foreground">{row.note}</div>
                        ) : null}
                      </td>
                      <td className="py-2">{row.ruleTypeLabel}</td>
                      <td className="py-2">
                        {row.matchNode ? `节点=${row.matchNode}` : ''}
                        {row.matchNode && row.matchExceptionCode ? ' · ' : ''}
                        {row.matchExceptionCode ? `异常码=${row.matchExceptionCode}` : ''}
                        {row.thresholdMinutes ? ` · 阈值 ${row.thresholdMinutes} 分钟` : ''}
                        {!row.matchNode && !row.matchExceptionCode && !row.thresholdMinutes
                          ? '所有在途票'
                          : ''}
                      </td>
                      <td className="py-2">{row.severityLabel}</td>
                      <td className="py-2">{durationText(row.resolveSlaMinutes)}</td>
                      <td className="py-2">{row.enabled ? '是' : '否'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>
        </TabsContent>
      </Tabs>

      {/* ---------------- 详情抽屉 ---------------- */}
      <DetailDrawer
        open={detail !== null || detailLoading}
        onOpenChange={(open) => !open && setDetail(null)}
      >
        <DetailDrawerContent width="lg">
          <DetailDrawerHeader>
            <DetailDrawerTitle>
              {detail ? `${detail.head.caseNo} · ${detail.head.exceptionTypeLabel}` : '异常件详情'}
            </DetailDrawerTitle>
            <DetailDrawerDescription>
              {detail
                ? `${detail.head.statusLabel} · 责任人 ${detail.head.ownerMembershipId} · ${detail.head.sourceLabel}`
                : '正在加载…'}
            </DetailDrawerDescription>
          </DetailDrawerHeader>
          <DetailDrawerBody>
            {detailLoading ? (
              <p className="text-muted-foreground text-sm">正在加载详情…</p>
            ) : !detail ? null : (
              <div className="space-y-6">
                <div>
                  <h3 className="mb-2 font-medium">基本信息</h3>
                  <dl className="grid grid-cols-2 gap-2 text-sm">
                    <dt className="text-muted-foreground">运单号</dt>
                    <dd>{detail.head.waybillNo || '—'}</dd>
                    <dt className="text-muted-foreground">承运商</dt>
                    <dd>{detail.head.carrierCode || '—'}</dd>
                    <dt className="text-muted-foreground">订单号</dt>
                    <dd>{detail.head.salesOrderNo || '—'}</dd>
                    <dt className="text-muted-foreground">停滞时长</dt>
                    <dd>{durationText(detail.head.stalledDurationMinutes)}</dd>
                    <dt className="text-muted-foreground">发现时刻</dt>
                    <dd>{new Date(detail.head.detectedAt).toLocaleString('zh-CN')}</dd>
                    <dt className="text-muted-foreground">责任时限</dt>
                    <dd>
                      {detail.head.overdue ? '已逾期 · ' : ''}
                      {new Date(detail.head.dueAt).toLocaleString('zh-CN')}
                    </dd>
                    {detail.head.customerFeedback ? (
                      <>
                        <dt className="text-muted-foreground">客服反馈</dt>
                        <dd>{detail.head.customerFeedback}</dd>
                      </>
                    ) : null}
                    {detail.head.closeReason ? (
                      <>
                        <dt className="text-muted-foreground">结案理由</dt>
                        <dd>{detail.head.closeReason}</dd>
                      </>
                    ) : null}
                    {detail.head.note ? (
                      <>
                        <dt className="text-muted-foreground">备注</dt>
                        <dd>{detail.head.note}</dd>
                      </>
                    ) : null}
                  </dl>
                </div>

                {detail.upstream ? (
                  <div>
                    <h3 className="mb-2 font-medium">上游轨迹上下文（10.3.1.1，只读）</h3>
                    <dl className="grid grid-cols-2 gap-2 text-sm">
                      <dt className="text-muted-foreground">当前节点</dt>
                      <dd>{detail.upstream.latestNode}</dd>
                      <dt className="text-muted-foreground">最后轨迹时刻</dt>
                      <dd>
                        {detail.upstream.latestOccurredAt
                          ? new Date(detail.upstream.latestOccurredAt).toLocaleString('zh-CN')
                          : '—'}
                      </dd>
                      <dt className="text-muted-foreground">事件数</dt>
                      <dd>{detail.upstream.eventCount}</dd>
                      <dt className="text-muted-foreground">承运商异常码</dt>
                      <dd>{detail.upstream.lastExceptionCode || '—'}</dd>
                    </dl>
                  </div>
                ) : null}

                <div>
                  <h3 className="mb-2 font-medium">下游待通知</h3>
                  {detail.downstreamTasks.length === 0 ? (
                    <p className="text-muted-foreground text-sm">
                      没有需要下游接手的事。承运商是外部对手方，不产生内部下游任务。
                    </p>
                  ) : (
                    <ul className="space-y-2 text-sm">
                      {detail.downstreamTasks.map((task) => (
                        <li key={task.id} className="rounded border p-2">
                          <div className="flex items-center gap-2">
                            <Badge tone="warning">{downstreamStatusLabel(task.downstreamStatus)}</Badge>
                            <span>{downstreamModuleLabel(task.downstreamModule)}</span>
                          </div>
                          <p className="text-muted-foreground mt-1">{task.note}</p>
                        </li>
                      ))}
                    </ul>
                  )}
                  {capabilities ? (
                    <p className="text-muted-foreground mt-2 text-sm">{capabilities.downstreamNote}</p>
                  ) : null}
                </div>

                <div>
                  <h3 className="mb-2 font-medium">处置记录</h3>
                  {detail.dispositions.length === 0 ? (
                    <p className="text-muted-foreground text-sm">还没有处置动作。</p>
                  ) : (
                    <ul className="space-y-2 text-sm">
                      {detail.dispositions.map((d) => (
                        <li key={d.id} className="rounded border p-2">
                          <div className="flex items-center gap-2">
                            <Badge>{d.actionTypeLabel}</Badge>
                            <span className="text-muted-foreground">{d.statusLabel}</span>
                            <span className="text-muted-foreground ml-auto">
                              {new Date(d.occurredAt).toLocaleString('zh-CN')}
                            </span>
                          </div>
                          {d.detail ? <p className="mt-1">{d.detail}</p> : null}
                          {d.amount > 0 ? (
                            <p className="text-muted-foreground mt-1">
                              涉及金额 {moneyText(d.amount, d.currency)}
                            </p>
                          ) : null}
                        </li>
                      ))}
                    </ul>
                  )}
                </div>

                <div>
                  <h3 className="mb-2 font-medium">理赔 / 补发单</h3>
                  {detail.claims.length === 0 ? (
                    <p className="text-muted-foreground text-sm">还没有单据。</p>
                  ) : (
                    <ul className="space-y-2 text-sm">
                      {detail.claims.map((c) => (
                        <li key={c.id} className="rounded border p-2">
                          <div className="flex items-center gap-2">
                            <span className="font-medium">{c.claimNo}</span>
                            <Badge>{c.claimTypeLabel}</Badge>
                            <span className="text-muted-foreground">{c.statusLabel}</span>
                          </div>
                          <p className="text-muted-foreground mt-1">
                            {c.claimType === 'RESHIP'
                              ? `补发数量 ${quantityText(c.qty)}`
                              : `金额 ${moneyText(c.amount, c.currency)}`}
                          </p>
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              </div>
            )}
          </DetailDrawerBody>
        </DetailDrawerContent>
      </DetailDrawer>

      {/* ---------------- 登记异常件 ---------------- */}
      <Modal open={caseOpen} onOpenChange={setCaseOpen}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>登记异常件</ModalTitle>
            <ModalDescription>
              来源由服务端派生（有异常节点 ⇒ 被动异常标记；有反馈 ⇒ 客服反馈；其余 ⇒ 人工登记），
              页面替不了这个口径。
            </ModalDescription>
          </ModalHeader>
          {caseError ? (
            <Notice tone="danger" title="未提交">
              {caseError}
            </Notice>
          ) : null}
          <div className="space-y-3 text-sm">
            <label className="block">
              <span className="text-muted-foreground">异常类型（必选）</span>
              <select
                className="mt-1 w-full rounded border px-2 py-1"
                value={caseForm.exceptionType}
                onChange={(e) => setCaseForm({ ...caseForm, exceptionType: e.target.value })}
              >
                <option value="">—— 请显式选择异常类型 ——</option>
                {EXCEPTION_TYPES.map((t) => (
                  <option key={t} value={t}>
                    {exceptionTypeLabel(t)}
                  </option>
                ))}
              </select>
            </label>
            <label className="block">
              <span className="text-muted-foreground">严重度</span>
              <select
                className="mt-1 w-full rounded border px-2 py-1"
                value={caseForm.severity}
                onChange={(e) => setCaseForm({ ...caseForm, severity: e.target.value })}
              >
                {SEVERITIES.map((s) => (
                  <option key={s} value={s}>
                    {severityLabel(s)}
                  </option>
                ))}
              </select>
            </label>
            <label className="block">
              <span className="text-muted-foreground">运单号（可选）</span>
              <Input
                className="mt-1"
                value={caseForm.waybillNo}
                onChange={(e) => setCaseForm({ ...caseForm, waybillNo: e.target.value })}
              />
            </label>
            <label className="block">
              <span className="text-muted-foreground">客服反馈原文（可选）</span>
              <textarea
                className="mt-1 w-full rounded border px-2 py-1"
                rows={3}
                value={caseForm.customerFeedback}
                onChange={(e) => setCaseForm({ ...caseForm, customerFeedback: e.target.value })}
              />
            </label>
            <label className="block">
              <span className="text-muted-foreground">备注（可选）</span>
              <textarea
                className="mt-1 w-full rounded border px-2 py-1"
                rows={2}
                value={caseForm.note}
                onChange={(e) => setCaseForm({ ...caseForm, note: e.target.value })}
              />
            </label>
          </div>
          <ModalFooter>
            <Button variant="ghost" onClick={() => setCaseOpen(false)} disabled={busy}>
              取消
            </Button>
            <Button onClick={() => void submitCase()} disabled={busy}>
              登记
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---------------- 追加处置 ---------------- */}
      <Modal open={dispositionTarget !== null} onOpenChange={(o) => !o && setDispositionTarget(null)}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>追加处置记录</ModalTitle>
            <ModalDescription>
              {dispositionTarget
                ? `${dispositionTarget.caseNo} · ${dispositionTarget.exceptionTypeLabel}`
                : ''}
              。处置留痕不可改写；结案 / 作废只能从这里做，且必须写明理由。
            </ModalDescription>
          </ModalHeader>
          {dispositionError ? (
            <Notice tone="danger" title="未提交">
              {dispositionError}
            </Notice>
          ) : null}
          <div className="space-y-3 text-sm">
            <label className="block">
              <span className="text-muted-foreground">处置动作（必选）</span>
              <select
                className="mt-1 w-full rounded border px-2 py-1"
                value={dispositionForm.actionType}
                onChange={(e) =>
                  setDispositionForm({ ...dispositionForm, actionType: e.target.value })
                }
              >
                <option value="">—— 请选择处置动作 ——</option>
                {ACTION_TYPES.map((a) => (
                  <option key={a} value={a}>
                    {actionTypeLabel(a)}
                  </option>
                ))}
              </select>
            </label>
            <label className="block">
              <span className="text-muted-foreground">处置状态</span>
              <select
                className="mt-1 w-full rounded border px-2 py-1"
                value={dispositionForm.status}
                onChange={(e) => setDispositionForm({ ...dispositionForm, status: e.target.value })}
              >
                <option value="PENDING">待办</option>
                <option value="IN_PROGRESS">进行中</option>
                <option value="SUBMITTED">已提交</option>
                <option value="CONFIRMED">已确认</option>
                <option value="FAILED">失败</option>
                <option value="CANCELLED">已取消</option>
              </select>
            </label>
            {dispositionForm.actionType === 'ASSIGN' ? (
              <label className="block">
                <span className="text-muted-foreground">目标责任人成员 ID（必填）</span>
                <Input
                  className="mt-1"
                  value={dispositionForm.targetOwnerMembershipId}
                  onChange={(e) =>
                    setDispositionForm({
                      ...dispositionForm,
                      targetOwnerMembershipId: e.target.value,
                    })
                  }
                />
              </label>
            ) : null}
            <div className="grid grid-cols-2 gap-2">
              <label className="block">
                <span className="text-muted-foreground">涉及金额（可选）</span>
                <Input
                  className="mt-1"
                  value={dispositionForm.amount}
                  onChange={(e) => setDispositionForm({ ...dispositionForm, amount: e.target.value })}
                />
              </label>
              <label className="block">
                <span className="text-muted-foreground">币种（金额大于 0 时必填）</span>
                <Input
                  className="mt-1"
                  value={dispositionForm.currency}
                  onChange={(e) =>
                    setDispositionForm({ ...dispositionForm, currency: e.target.value })
                  }
                />
              </label>
            </div>
            <label className="block">
              <span className="text-muted-foreground">
                说明（结案 / 作废时必填）
              </span>
              <textarea
                className="mt-1 w-full rounded border px-2 py-1"
                rows={3}
                value={dispositionForm.detail}
                onChange={(e) => setDispositionForm({ ...dispositionForm, detail: e.target.value })}
              />
            </label>
            <Notice tone="info" title="下游待通知">
              「联系客服 / 请求改址 / 请求补发」会自动生成一条**待通知**任务
              （15 客服 / 10.2.1.3 改址 / 7 库存）。这些模块当前未交付，
              页面**不会**把它们显示成「已通知」或「已受理」。
            </Notice>
          </div>
          <ModalFooter>
            <Button
              variant="ghost"
              onClick={() => setDispositionTarget(null)}
              disabled={busy}
            >
              取消
            </Button>
            <Button onClick={() => void submitDisposition()} disabled={busy}>
              提交
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---------------- 发起单据 ---------------- */}
      <Modal open={claimTarget !== null} onOpenChange={(o) => !o && setClaimTarget(null)}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>发起理赔 / 补发单</ModalTitle>
            <ModalDescription>
              {claimTarget ? `${claimTarget.caseNo} · ${claimTarget.exceptionTypeLabel}` : ''}。
              理赔单必须有金额与币种，补发单必须有数量。
            </ModalDescription>
          </ModalHeader>
          {claimError ? (
            <Notice tone="danger" title="未提交">
              {claimError}
            </Notice>
          ) : null}
          <div className="space-y-3 text-sm">
            <label className="block">
              <span className="text-muted-foreground">单据类型（必选）</span>
              <select
                className="mt-1 w-full rounded border px-2 py-1"
                value={claimForm.claimType}
                onChange={(e) => setClaimForm({ ...claimForm, claimType: e.target.value })}
              >
                <option value="">—— 请选择单据类型 ——</option>
                {CLAIM_TYPES.map((t) => (
                  <option key={t} value={t}>
                    {t === 'CARRIER_CLAIM' ? '向承运商理赔' : '补发'}
                  </option>
                ))}
              </select>
            </label>
            {claimForm.claimType === 'CARRIER_CLAIM' ? (
              <div className="grid grid-cols-2 gap-2">
                <label className="block">
                  <span className="text-muted-foreground">理赔金额（必填）</span>
                  <Input
                    className="mt-1"
                    value={claimForm.amount}
                    onChange={(e) => setClaimForm({ ...claimForm, amount: e.target.value })}
                  />
                </label>
                <label className="block">
                  <span className="text-muted-foreground">币种（必填）</span>
                  <Input
                    className="mt-1"
                    placeholder="USD"
                    value={claimForm.currency}
                    onChange={(e) => setClaimForm({ ...claimForm, currency: e.target.value })}
                  />
                </label>
              </div>
            ) : null}
            {claimForm.claimType === 'RESHIP' ? (
              <label className="block">
                <span className="text-muted-foreground">补发数量（必填）</span>
                <Input
                  className="mt-1"
                  value={claimForm.qty}
                  onChange={(e) => setClaimForm({ ...claimForm, qty: e.target.value })}
                />
              </label>
            ) : null}
            <label className="block">
              <span className="text-muted-foreground">凭据说明（可选）</span>
              <textarea
                className="mt-1 w-full rounded border px-2 py-1"
                rows={2}
                value={claimForm.evidenceNote}
                onChange={(e) => setClaimForm({ ...claimForm, evidenceNote: e.target.value })}
              />
            </label>
            {claimForm.claimType === 'RESHIP' ? (
              <Notice tone="warning" title="补发单会产出两条待通知任务">
                7 库存（补发出库）与 11 售后（补发 / 退款）当前都未交付，状态恒为「待通知」——
                本页不跨服务调用、也不动对方的库存。
              </Notice>
            ) : null}
          </div>
          <ModalFooter>
            <Button variant="ghost" onClick={() => setClaimTarget(null)} disabled={busy}>
              取消
            </Button>
            <Button onClick={() => void submitClaim()} disabled={busy}>
              发起
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---------------- 登记规则 ---------------- */}
      <Modal open={ruleOpen} onOpenChange={setRuleOpen}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>登记异常识别规则</ModalTitle>
            <ModalDescription>
              规则是租户级的：命中条件可组合（节点 + 承运商异常码 + 时长阈值），
              命中后带出严重度与责任时限。
            </ModalDescription>
          </ModalHeader>
          {ruleError ? (
            <Notice tone="danger" title="未提交">
              {ruleError}
            </Notice>
          ) : null}
          <div className="space-y-3 text-sm">
            <label className="block">
              <span className="text-muted-foreground">规则编码（租户内唯一）</span>
              <Input
                className="mt-1"
                placeholder="STALL-7D"
                value={ruleForm.ruleCode}
                onChange={(e) => setRuleForm({ ...ruleForm, ruleCode: e.target.value })}
              />
            </label>
            <label className="block">
              <span className="text-muted-foreground">命中后产出的异常类型</span>
              <select
                className="mt-1 w-full rounded border px-2 py-1"
                value={ruleForm.ruleType}
                onChange={(e) => setRuleForm({ ...ruleForm, ruleType: e.target.value })}
              >
                <option value="">—— 请选择异常类型 ——</option>
                {EXCEPTION_TYPES.map((t) => (
                  <option key={t} value={t}>
                    {exceptionTypeLabel(t)}
                  </option>
                ))}
              </select>
            </label>
            <label className="block">
              <span className="text-muted-foreground">匹配轨迹节点（可选）</span>
              <select
                className="mt-1 w-full rounded border px-2 py-1"
                value={ruleForm.matchNode}
                onChange={(e) => setRuleForm({ ...ruleForm, matchNode: e.target.value })}
              >
                <option value="">不限节点</option>
                {TRACKING_NODES.map((n) => (
                  <option key={n} value={n}>
                    {n}
                  </option>
                ))}
              </select>
            </label>
            <label className="block">
              <span className="text-muted-foreground">匹配承运商异常码（可选，精确等值）</span>
              <Input
                className="mt-1"
                placeholder="ADDRESS_INVALID"
                value={ruleForm.matchExceptionCode}
                onChange={(e) =>
                  setRuleForm({ ...ruleForm, matchExceptionCode: e.target.value })
                }
              />
            </label>
            <div className="grid grid-cols-2 gap-2">
              <label className="block">
                <span className="text-muted-foreground">时长阈值（分钟，可选）</span>
                <Input
                  className="mt-1"
                  value={ruleForm.thresholdMinutes}
                  onChange={(e) =>
                    setRuleForm({ ...ruleForm, thresholdMinutes: e.target.value })
                  }
                />
              </label>
              <label className="block">
                <span className="text-muted-foreground">责任时限（分钟）</span>
                <Input
                  className="mt-1"
                  value={ruleForm.resolveSlaMinutes}
                  onChange={(e) =>
                    setRuleForm({ ...ruleForm, resolveSlaMinutes: e.target.value })
                  }
                />
              </label>
            </div>
            <label className="block">
              <span className="text-muted-foreground">严重度</span>
              <select
                className="mt-1 w-full rounded border px-2 py-1"
                value={ruleForm.severity}
                onChange={(e) => setRuleForm({ ...ruleForm, severity: e.target.value })}
              >
                {SEVERITIES.map((s) => (
                  <option key={s} value={s}>
                    {severityLabel(s)}
                  </option>
                ))}
              </select>
            </label>
            <label className="flex items-center gap-2">
              <input
                type="checkbox"
                checked={ruleForm.enabled}
                onChange={(e) => setRuleForm({ ...ruleForm, enabled: e.target.checked })}
              />
              启用
            </label>
          </div>
          <ModalFooter>
            <Button variant="ghost" onClick={() => setRuleOpen(false)} disabled={busy}>
              取消
            </Button>
            <Button onClick={() => void submitRule()} disabled={busy}>
              登记
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </>
  );
}
