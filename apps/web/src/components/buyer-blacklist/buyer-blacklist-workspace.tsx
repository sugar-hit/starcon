'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import {
  CircleHelp,
  ClipboardCheck,
  Gavel,
  History,
  Loader2,
  RefreshCw,
  ShieldAlert,
  ShieldBan,
  TriangleAlert,
} from 'lucide-react';
import {
  actionLabel,
  actionTone,
  check,
  decideAppeal,
  decisionLabel,
  emptyState,
  fetchCapabilities,
  fetchEntries,
  fetchEntry,
  fetchFlags,
  register,
  registerAuto,
  release,
  riskLevelLabel,
  riskLevelTone,
  scoreText,
  statusLabel,
  statusTone,
  submitAppeal,
  type BuyerBlacklistCapabilities,
  type BuyerBlacklistCheckResult,
  type BuyerBlacklistEntry,
  type BuyerBlacklistEntryDetail,
  type BuyerBlacklistEntryPage,
  type BuyerBlacklistFlagPage,
} from './buyer-blacklist-model';

/**
 * 16.5.1.1 黑名单 工作区。
 *
 * 蓝图：「维护买家黑名单：买家标识、店铺、原因、风险等级、生效范围（仅本店铺/全店铺），
 * 下单时自动标记并可拦截。输入：黑名单录入（手工/自动，来自风控信号）；解除操作。
 * 输出：黑名单记录；订单拦截标记。关系：上游 = 16.5.1.2 评分、9.2 审单；下游 = 9.2.1.3 拦截、11 售后。
 * **黑名单应支持申诉解除**，永久不解除会让误判的正常买家永久损失。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **「解除」不是「删除」**：按钮写「解除（留痕）」，并提示「谁、何时、为什么」都会被记下。
 *    页面上**没有删除按钮** —— 黑名单是判定凭据，只能解除不能删除。
 * 2. **「建议拦截」不是「已拦截」**：拦截标记上的动作一律带「建议」二字，
 *    9.2.1.3 的拦截闭环与 11 售后都**不在本页代劳**（页面上没有指向它们的入口）。
 * 3. **「读不到」不是「没风险」**：9.2 不可达时后端回 503，这里显示错误条，**不显示空名单**。
 * 4. **「查无此人」不是「干净」**：检查结果里显示「9.2 审单里没有这个人的画像」。
 * 5. **历史不可抹掉**：详情里按时间顺序列出**每一次**状态流转（谁 / 何时 / 为什么）。
 */
export function BuyerBlacklistWorkspace() {
  const [caps, setCaps] = useState<BuyerBlacklistCapabilities | null>(null);
  const [page, setPage] = useState<BuyerBlacklistEntryPage | null>(null);
  const [flags, setFlags] = useState<BuyerBlacklistFlagPage | null>(null);
  const [detail, setDetail] = useState<BuyerBlacklistEntryDetail | null>(null);

  const [entryPage, setEntryPage] = useState(0);
  const [flagPage, setFlagPage] = useState(0);
  const [keyword, setKeyword] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [scopeFilter, setScopeFilter] = useState('');
  const [levelFilter, setLevelFilter] = useState('');
  const [actionFilter, setActionFilter] = useState('');

  const [manual, setManual] = useState({
    buyerName: '',
    buyerPhone: '',
    buyerEmail: '',
    scope: 'ALL_SHOPS',
    shopCode: '',
    riskLevel: 'HIGH',
    reason: '',
    note: '',
  });
  const [auto, setAuto] = useState({
    buyerKey: '',
    scope: 'ALL_SHOPS',
    shopCode: '',
    riskLevel: 'HIGH',
    reason: '',
    signalRef: '',
    note: '',
  });
  const [releaseReason, setReleaseReason] = useState('');
  const [appealReason, setAppealReason] = useState('');
  const [decisionReason, setDecisionReason] = useState('');
  const [checkForm, setCheckForm] = useState({
    buyerName: '',
    buyerPhone: '',
    buyerEmail: '',
    shopCode: '',
    orderRef: '',
  });
  const [checkResult, setCheckResult] = useState<BuyerBlacklistCheckResult | null>(null);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const ENTRY_PAGE_SIZE = 10;
  const FLAG_PAGE_SIZE = 10;

  const loadEntries = useCallback(
    async (target: number) => {
      const result = await fetchEntries(target, ENTRY_PAGE_SIZE, {
        status: statusFilter,
        scope: scopeFilter,
        riskLevel: levelFilter,
        keyword,
      });
      setPage(result);
      return result;
    },
    [keyword, levelFilter, scopeFilter, statusFilter],
  );

  const loadFlags = useCallback(
    async (target: number) => {
      const result = await fetchFlags(target, FLAG_PAGE_SIZE, actionFilter);
      setFlags(result);
      return result;
    },
    [actionFilter],
  );

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capsResult] = await Promise.all([fetchCapabilities()]);
      setCaps(capsResult);
      await loadEntries(entryPage);
      await loadFlags(flagPage);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [entryPage, flagPage, loadEntries, loadFlags]);

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const openDetail = useCallback(async (entryId: string) => {
    setError(null);
    try {
      setDetail(await fetchEntry(entryId));
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载黑名单详情失败，请重试');
    }
  }, []);

  const doRegister = useCallback(async () => {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const created = await register(manual);
      setNotice(
        `已登记黑名单（${created.buyerName || created.buyerKey}）。🔴 原始登记不可修改 —— ` +
          '之后要放开只能走「解除（留痕）」，每一次解除都会留下一条谁、何时、为什么。',
      );
      await loadEntries(0);
      setEntryPage(0);
      await openDetail(created.entryId);
    } catch (e) {
      setError(e instanceof Error ? e.message : '登记失败，请重试');
    } finally {
      setBusy(false);
    }
  }, [loadEntries, manual, openDetail]);

  const doRegisterAuto = useCallback(async () => {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const created = await registerAuto(auto);
      setNotice(
        `已按风控信号自动登记黑名单（信号引用 ${created.sourceRef}）。` +
          '🔴 自动来源必须可追溯 —— 信号引用会跟着这条记录一直留着。',
      );
      await loadEntries(0);
      setEntryPage(0);
      await openDetail(created.entryId);
    } catch (e) {
      setError(e instanceof Error ? e.message : '自动登记失败，请重试');
    } finally {
      setBusy(false);
    }
  }, [auto, loadEntries, openDetail]);

  const doRelease = useCallback(async () => {
    if (!detail) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const released = await release(detail.entry.entryId, releaseReason);
      setNotice(
        `已解除（${released.statusLabel}）。🔴 解除**不是删除**：` +
          '登记原样留着，解除作为一条状态流转事件追加在后面 —— 历史一条不少。',
      );
      setReleaseReason('');
      await loadEntries(entryPage);
      await openDetail(detail.entry.entryId);
    } catch (e) {
      setError(e instanceof Error ? e.message : '解除失败，请重试');
    } finally {
      setBusy(false);
    }
  }, [detail, entryPage, loadEntries, openDetail, releaseReason]);

  const doSubmitAppeal = useCallback(async () => {
    if (!detail) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await submitAppeal(detail.entry.entryId, appealReason);
      setNotice(
        '申诉已提交，等待裁决。🔴 申诉通过 ⇒ 解除；申诉驳回 ⇒ 维持黑名单 —— 两种都会留痕。',
      );
      setAppealReason('');
      await openDetail(detail.entry.entryId);
    } catch (e) {
      setError(e instanceof Error ? e.message : '提交申诉失败，请重试');
    } finally {
      setBusy(false);
    }
  }, [appealReason, detail, openDetail]);

  const doDecide = useCallback(
    async (appealId: string, decision: string) => {
      setBusy(true);
      setError(null);
      setNotice(null);
      try {
        await decideAppeal(appealId, decision, decisionReason);
        setNotice(
          decision === 'APPROVED'
            ? '申诉已通过，黑名单已解除 —— 解除动作作为一条状态流转事件留痕。'
            : '申诉已驳回，黑名单维持生效 —— 驳回理由也留了痕。',
        );
        setDecisionReason('');
        await loadEntries(entryPage);
        if (detail) await openDetail(detail.entry.entryId);
      } catch (e) {
        setError(e instanceof Error ? e.message : '裁决失败，请重试');
      } finally {
        setBusy(false);
      }
    },
    [decisionReason, detail, entryPage, loadEntries, openDetail],
  );

  const doCheck = useCallback(async () => {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const result = await check(checkForm);
      setCheckResult(result);
      if (result.flagWritten) {
        await loadFlags(0);
        setFlagPage(0);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '检查失败，请重试');
    } finally {
      setBusy(false);
    }
  }, [checkForm, loadFlags]);

  const entryPages = useMemo(
    () =>
      page ? Math.max(1, Math.ceil(page.total / Math.max(1, page.size || ENTRY_PAGE_SIZE))) : 1,
    [page],
  );
  const flagPages = useMemo(
    () =>
      flags ? Math.max(1, Math.ceil(flags.total / Math.max(1, flags.size || FLAG_PAGE_SIZE))) : 1,
    [flags],
  );

  if (loading) {
    return (
      <div className="text-copy-13 text-muted-foreground flex items-center gap-2">
        <Loader2 className="h-4 w-4 animate-spin" />
        正在加载买家黑名单…
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <PageHeader
        title="买家黑名单"
        description="维护买家黑名单：买家标识、店铺、原因、风险等级、生效范围（仅本店铺 / 全店铺），下单时自动标记并可拦截。"
        actions={
          <Button size="sm" variant="outline" onClick={() => void refresh()}>
            <RefreshCw className="h-3.5 w-3.5" />
            刷新
          </Button>
        }
      />

      {error && (
        <div className="border-destructive-soft bg-destructive-soft text-destructive flex items-start gap-2 rounded-lg border px-4 py-3">
          <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <span className="text-copy-13 whitespace-pre-line">{error}</span>
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
        <StatCard label="黑名单登记" value={page?.counts.total ?? 0} polarity="neutral" />
        <StatCard label="生效中" value={page?.counts.active ?? 0} polarity="negative" />
        <StatCard label="已解除" value={page?.counts.released ?? 0} polarity="neutral" />
        <StatCard
          label="极高 / 高风险"
          value={(page?.counts.critical ?? 0) + (page?.counts.high ?? 0)}
          polarity="negative"
        />
      </div>

      {/* ---------------- 能力声明 ---------------- */}
      {caps && (
        <SectionCard
          title="能力声明"
          description="哪些能力本模块自己负责、哪些归别的模块 —— 未交付的下游不编造、不跳转。"
        >
          <div className="space-y-4">
            <div className="grid gap-2 md:grid-cols-2">
              {caps.capabilities.map((c) => (
                <div key={c.capabilityCode} className="border-border rounded-md border px-3 py-2">
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-copy-13 text-foreground font-medium">
                      {c.capabilityLabel}
                    </span>
                    <Badge tone={c.available ? 'success' : 'warning'}>
                      {c.available ? '本模块负责' : '不代劳'}
                    </Badge>
                  </div>
                  {!c.available && c.ownerModule ? (
                    <p className="text-copy-12 text-muted-foreground mt-1">
                      归属模块：{c.ownerModule}
                    </p>
                  ) : null}
                  <p className="text-copy-12 text-muted-foreground mt-1">{c.impact}</p>
                </div>
              ))}
            </div>
            <details className="text-copy-12 text-muted-foreground">
              <summary className="cursor-pointer">
                口径说明（主体键 / 生效范围 / 解除 / 申诉 / 拦截标记 / 下游与边界）
              </summary>
              <div className="mt-2 space-y-2">
                <p>{caps.subjectKeySemantics}</p>
                <p>{caps.scopeSemantics}</p>
                <p>{caps.releaseSemantics}</p>
                <p>{caps.appealSemantics}</p>
                <p>{caps.flagSemantics}</p>
                <p>{caps.downstreamNote}</p>
                <p>{caps.boundaryNote}</p>
              </div>
            </details>
            <div className="flex flex-wrap gap-2">
              {caps.sources.map((s) => (
                <Badge key={s.sourceCode} tone={s.available ? 'info' : 'warning'}>
                  {s.sourceLabel}：{s.available ? '可读' : '读不到'}
                </Badge>
              ))}
            </div>
          </div>
        </SectionCard>
      )}

      {/* ---------------- 录入 ---------------- */}
      <div className="grid gap-4 lg:grid-cols-2">
        <SectionCard
          title="手工录入黑名单"
          description="收件人三件套 → 主体键。🔴 9.2 审单读不到时会直接报错，不会拿本地算出的键硬写一条。"
        >
          <div className="space-y-3">
            <div className="grid gap-3 md:grid-cols-3">
              <label className="text-copy-13 text-foreground space-y-1">
                <span>收件人姓名</span>
                <Input
                  value={manual.buyerName}
                  onChange={(e) => setManual({ ...manual, buyerName: e.target.value })}
                />
              </label>
              <label className="text-copy-13 text-foreground space-y-1">
                <span>电话</span>
                <Input
                  value={manual.buyerPhone}
                  onChange={(e) => setManual({ ...manual, buyerPhone: e.target.value })}
                />
              </label>
              <label className="text-copy-13 text-foreground space-y-1">
                <span>邮箱</span>
                <Input
                  value={manual.buyerEmail}
                  onChange={(e) => setManual({ ...manual, buyerEmail: e.target.value })}
                />
              </label>
            </div>
            <div className="grid gap-3 md:grid-cols-3">
              <label className="text-copy-13 text-foreground space-y-1">
                <span>生效范围</span>
                <select
                  className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3"
                  value={manual.scope}
                  onChange={(e) => setManual({ ...manual, scope: e.target.value })}
                >
                  <option value="ALL_SHOPS">全店铺</option>
                  <option value="SHOP_ONLY">仅本店铺</option>
                </select>
              </label>
              <label className="text-copy-13 text-foreground space-y-1">
                <span>店铺编码（仅本店铺时必填）</span>
                <Input
                  value={manual.shopCode}
                  disabled={manual.scope !== 'SHOP_ONLY'}
                  onChange={(e) => setManual({ ...manual, shopCode: e.target.value })}
                />
              </label>
              <label className="text-copy-13 text-foreground space-y-1">
                <span>风险等级</span>
                <select
                  className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3"
                  value={manual.riskLevel}
                  onChange={(e) => setManual({ ...manual, riskLevel: e.target.value })}
                >
                  <option value="LOW">低</option>
                  <option value="MEDIUM">中</option>
                  <option value="HIGH">高</option>
                  <option value="CRITICAL">极高</option>
                </select>
              </label>
            </div>
            <label className="text-copy-13 text-foreground block space-y-1">
              <span>拉黑原因（必填 —— 没有理由的黑名单客服无法向买家解释）</span>
              <Input
                value={manual.reason}
                onChange={(e) => setManual({ ...manual, reason: e.target.value })}
              />
            </label>
            <Button size="sm" disabled={busy} onClick={() => void doRegister()}>
              <ShieldBan className="h-3.5 w-3.5" />
              登记黑名单
            </Button>
          </div>
        </SectionCard>

        <SectionCard
          title="自动录入（来自风控信号）"
          description="信号自带主体键与信号引用 —— 🔴 信号引用必填，自动来源必须可追溯。"
        >
          <div className="space-y-3">
            <label className="text-copy-13 text-foreground block space-y-1">
              <span>主体键 buyer_key（32 位十六进制）</span>
              <Input
                value={auto.buyerKey}
                onChange={(e) => setAuto({ ...auto, buyerKey: e.target.value })}
              />
            </label>
            <div className="grid gap-3 md:grid-cols-3">
              <label className="text-copy-13 text-foreground space-y-1">
                <span>生效范围</span>
                <select
                  className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3"
                  value={auto.scope}
                  onChange={(e) => setAuto({ ...auto, scope: e.target.value })}
                >
                  <option value="ALL_SHOPS">全店铺</option>
                  <option value="SHOP_ONLY">仅本店铺</option>
                </select>
              </label>
              <label className="text-copy-13 text-foreground space-y-1">
                <span>店铺编码</span>
                <Input
                  value={auto.shopCode}
                  disabled={auto.scope !== 'SHOP_ONLY'}
                  onChange={(e) => setAuto({ ...auto, shopCode: e.target.value })}
                />
              </label>
              <label className="text-copy-13 text-foreground space-y-1">
                <span>风险等级</span>
                <select
                  className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3"
                  value={auto.riskLevel}
                  onChange={(e) => setAuto({ ...auto, riskLevel: e.target.value })}
                >
                  <option value="LOW">低</option>
                  <option value="MEDIUM">中</option>
                  <option value="HIGH">高</option>
                  <option value="CRITICAL">极高</option>
                </select>
              </label>
            </div>
            <label className="text-copy-13 text-foreground block space-y-1">
              <span>信号引用（必填）</span>
              <Input
                value={auto.signalRef}
                onChange={(e) => setAuto({ ...auto, signalRef: e.target.value })}
              />
            </label>
            <label className="text-copy-13 text-foreground block space-y-1">
              <span>拉黑原因</span>
              <Input
                value={auto.reason}
                onChange={(e) => setAuto({ ...auto, reason: e.target.value })}
              />
            </label>
            <Button
              size="sm"
              variant="outline"
              disabled={busy}
              onClick={() => void doRegisterAuto()}
            >
              按信号自动登记
            </Button>
          </div>
        </SectionCard>
      </div>

      {/* ---------------- 黑名单清单 ---------------- */}
      <SectionCard
        title="黑名单清单"
        description="真分页 + 真筛选。生效范围参与判定：仅本店铺的黑名单不会在全店铺口径下命中。"
        extra={
          <div className="flex flex-wrap items-center gap-2">
            <Input
              className="w-44"
              placeholder="姓名 / 邮箱 / 主体键"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
            />
            <select
              className="border-control-border bg-card text-copy-13 text-foreground h-8 rounded-md border px-2"
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
            >
              <option value="">全部状态</option>
              <option value="ACTIVE">生效中</option>
              <option value="RELEASED">已解除</option>
            </select>
            <select
              className="border-control-border bg-card text-copy-13 text-foreground h-8 rounded-md border px-2"
              value={scopeFilter}
              onChange={(e) => setScopeFilter(e.target.value)}
            >
              <option value="">全部范围</option>
              <option value="SHOP_ONLY">仅本店铺</option>
              <option value="ALL_SHOPS">全店铺</option>
            </select>
            <select
              className="border-control-border bg-card text-copy-13 text-foreground h-8 rounded-md border px-2"
              value={levelFilter}
              onChange={(e) => setLevelFilter(e.target.value)}
            >
              <option value="">全部等级</option>
              <option value="LOW">低</option>
              <option value="MEDIUM">中</option>
              <option value="HIGH">高</option>
              <option value="CRITICAL">极高</option>
            </select>
            <Button
              size="sm"
              variant="outline"
              onClick={() => {
                setEntryPage(0);
                void loadEntries(0);
              }}
            >
              筛选
            </Button>
          </div>
        }
      >
        {!page || page.items.length === 0 ? (
          <EmptyState
            icon={<ShieldBan className="h-6 w-6" />}
            title={emptyState('NO_ENTRY').title}
            description={emptyState('NO_ENTRY').description}
          />
        ) : (
          <div className="space-y-3">
            <div className="space-y-2">
              {page.items.map((entry) => (
                <EntryRow
                  key={entry.entryId}
                  entry={entry}
                  onOpen={() => void openDetail(entry.entryId)}
                />
              ))}
            </div>
            <div className="text-copy-12 text-muted-foreground flex items-center justify-between">
              <span>
                第 {page.page + 1} / {entryPages} 页 · 共 {page.total} 条
              </span>
              <div className="flex gap-2">
                <Button
                  size="sm"
                  variant="outline"
                  disabled={page.page <= 0}
                  onClick={() => {
                    const next = page.page - 1;
                    setEntryPage(next);
                    void loadEntries(next);
                  }}
                >
                  上一页
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={page.page + 1 >= entryPages}
                  onClick={() => {
                    const next = page.page + 1;
                    setEntryPage(next);
                    void loadEntries(next);
                  }}
                >
                  下一页
                </Button>
              </div>
            </div>
          </div>
        )}
      </SectionCard>

      {/* ---------------- 登记详情（状态流转 + 申诉） ---------------- */}
      {detail && (
        <SectionCard
          title="登记详情与状态流转"
          description="🔴 登记不可修改：每一次解除 / 升级 / 申诉裁决都追加一条留痕（谁 / 何时 / 为什么），历史一条不少。"
          extra={
            <Button size="sm" variant="ghost" onClick={() => setDetail(null)}>
              收起
            </Button>
          }
        >
          <div className="space-y-4">
            <div className="grid gap-2 md:grid-cols-2">
              <div className="border-border rounded-md border px-3 py-2">
                <div className="text-copy-12 text-muted-foreground">买家</div>
                <div className="text-copy-13 text-foreground">
                  {detail.entry.buyerName || '（未登记姓名）'} ·{' '}
                  {detail.entry.buyerPhone || '无电话'} · {detail.entry.buyerEmail || '无邮箱'}
                </div>
                <div className="text-copy-12 text-muted-foreground mt-1 break-all">
                  主体键：{detail.entry.buyerKey}
                </div>
              </div>
              <div className="border-border rounded-md border px-3 py-2">
                <div className="text-copy-12 text-muted-foreground">登记</div>
                <div className="text-copy-13 text-foreground">
                  {detail.entry.sourceLabel} · {detail.entry.scopeLabel}
                  {detail.entry.shopCode ? ` · ${detail.entry.shopCode}` : ''}
                </div>
                <div className="text-copy-12 text-muted-foreground mt-1">
                  {detail.entry.registeredAt} · {detail.entry.registeredBy}
                </div>
                {detail.entry.sourceRef ? (
                  <div className="text-copy-12 text-muted-foreground mt-1 break-all">
                    信号引用：{detail.entry.sourceRef}
                  </div>
                ) : null}
              </div>
            </div>

            <div>
              <div className="text-copy-13 text-foreground mb-2 flex items-center gap-2 font-medium">
                <History className="h-4 w-4" />
                状态流转（{detail.events.length} 条）
              </div>
              <div className="space-y-2">
                {detail.events.map((event) => (
                  <div key={event.eventId} className="border-border rounded-md border px-3 py-2">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <span className="text-copy-13 text-foreground">
                        #{event.eventSeq} {event.eventLabel}
                      </span>
                      <Badge tone={riskLevelTone(event.riskLevel)}>
                        风险等级 {riskLevelLabel(event.riskLevel)}
                      </Badge>
                    </div>
                    <div className="text-copy-12 text-muted-foreground mt-1">
                      {event.occurredAt} · {event.actor} · {event.fromStatus} → {event.toStatus}
                    </div>
                    <div className="text-copy-12 text-foreground mt-1 whitespace-pre-line">
                      {event.reason}
                    </div>
                  </div>
                ))}
              </div>
            </div>

            <div>
              <div className="text-copy-13 text-foreground mb-2 flex items-center gap-2 font-medium">
                <Gavel className="h-4 w-4" />
                申诉（{detail.appeals.length} 张）
              </div>
              {detail.appeals.length === 0 ? (
                <p className="text-copy-12 text-muted-foreground">
                  还没有申诉。🔴 蓝图要求「黑名单应支持申诉解除」——
                  永久不解除会让误判的正常买家永久损失。
                </p>
              ) : (
                <div className="space-y-2">
                  {detail.appeals.map((appeal) => (
                    <div
                      key={appeal.appealId}
                      className="border-border rounded-md border px-3 py-2"
                    >
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <span className="text-copy-13 text-foreground">
                          {decisionLabel(appeal.decision)}
                        </span>
                        <Badge
                          tone={
                            appeal.decision === 'APPROVED'
                              ? 'success'
                              : appeal.decision === 'REJECTED'
                                ? 'danger'
                                : 'warning'
                          }
                        >
                          {appeal.decision}
                        </Badge>
                      </div>
                      <div className="text-copy-12 text-muted-foreground mt-1">
                        {appeal.submittedAt} · {appeal.submittedBy}
                      </div>
                      <div className="text-copy-12 text-foreground mt-1 whitespace-pre-line">
                        {appeal.appealReason}
                      </div>
                      {appeal.decidedAt ? (
                        <div className="text-copy-12 text-muted-foreground mt-1">
                          裁决：{appeal.decidedAt} · {appeal.decidedBy} · {appeal.decisionReason}
                        </div>
                      ) : null}
                      {appeal.decision === 'PENDING' ? (
                        <div className="mt-2 space-y-2">
                          <Input
                            placeholder="裁决理由（必填）"
                            value={decisionReason}
                            onChange={(e) => setDecisionReason(e.target.value)}
                          />
                          <div className="flex gap-2">
                            <Button
                              size="sm"
                              disabled={busy}
                              onClick={() => void doDecide(appeal.appealId, 'APPROVED')}
                            >
                              通过并解除
                            </Button>
                            <Button
                              size="sm"
                              variant="outline"
                              disabled={busy}
                              onClick={() => void doDecide(appeal.appealId, 'REJECTED')}
                            >
                              驳回（维持）
                            </Button>
                          </div>
                        </div>
                      ) : null}
                    </div>
                  ))}
                </div>
              )}
            </div>

            {detail.entry.status === 'ACTIVE' ? (
              <div className="grid gap-3 md:grid-cols-2">
                <div className="space-y-2">
                  <div className="text-copy-13 text-foreground font-medium">解除（留痕）</div>
                  <Input
                    placeholder="解除理由（必填 —— 没有理由的解除等于悄悄放过）"
                    value={releaseReason}
                    onChange={(e) => setReleaseReason(e.target.value)}
                  />
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={busy}
                    onClick={() => void doRelease()}
                  >
                    解除（留痕）
                  </Button>
                </div>
                <div className="space-y-2">
                  <div className="text-copy-13 text-foreground font-medium">提交申诉</div>
                  <Input
                    placeholder="申诉理由（必填）"
                    value={appealReason}
                    onChange={(e) => setAppealReason(e.target.value)}
                  />
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={busy}
                    onClick={() => void doSubmitAppeal()}
                  >
                    提交申诉
                  </Button>
                </div>
              </div>
            ) : (
              <p className="text-copy-12 text-muted-foreground">
                这条黑名单已解除 —— 🔴 解除不是删除，登记原样留着，历史一条不少。
              </p>
            )}
          </div>
        </SectionCard>
      )}

      {/* ---------------- 下单检查 ---------------- */}
      <SectionCard
        title="下单时检查"
        description="命中生效中的黑名单就落一条订单拦截标记。🔴 这里给的是**建议**动作 —— 真的拦不拦单是 9.2.1.3 的事。"
      >
        <div className="space-y-3">
          <div className="grid gap-3 md:grid-cols-5">
            <label className="text-copy-13 text-foreground space-y-1">
              <span>收件人姓名</span>
              <Input
                value={checkForm.buyerName}
                onChange={(e) => setCheckForm({ ...checkForm, buyerName: e.target.value })}
              />
            </label>
            <label className="text-copy-13 text-foreground space-y-1">
              <span>电话</span>
              <Input
                value={checkForm.buyerPhone}
                onChange={(e) => setCheckForm({ ...checkForm, buyerPhone: e.target.value })}
              />
            </label>
            <label className="text-copy-13 text-foreground space-y-1">
              <span>邮箱</span>
              <Input
                value={checkForm.buyerEmail}
                onChange={(e) => setCheckForm({ ...checkForm, buyerEmail: e.target.value })}
              />
            </label>
            <label className="text-copy-13 text-foreground space-y-1">
              <span>店铺编码</span>
              <Input
                value={checkForm.shopCode}
                onChange={(e) => setCheckForm({ ...checkForm, shopCode: e.target.value })}
              />
            </label>
            <label className="text-copy-13 text-foreground space-y-1">
              <span>订单引用</span>
              <Input
                value={checkForm.orderRef}
                onChange={(e) => setCheckForm({ ...checkForm, orderRef: e.target.value })}
              />
            </label>
          </div>
          <Button size="sm" disabled={busy} onClick={() => void doCheck()}>
            <ClipboardCheck className="h-3.5 w-3.5" />
            检查
          </Button>

          {checkResult && (
            <div className="border-border space-y-2 rounded-md border px-3 py-2">
              {checkResult.blacklisted ? (
                <>
                  <div className="flex flex-wrap items-center gap-2">
                    <ShieldAlert className="text-destructive h-4 w-4" />
                    <span className="text-copy-13 text-foreground font-medium">
                      命中黑名单（{checkResult.matchedScopeLabel ?? checkResult.matchedScope}
                      {checkResult.matchedShopCode ? ` · ${checkResult.matchedShopCode}` : ''}）
                    </span>
                    <Badge tone={riskLevelTone(checkResult.riskLevel ?? '')}>
                      风险等级 {checkResult.riskLevelLabel ?? checkResult.riskLevel}
                    </Badge>
                    <Badge tone={actionTone(checkResult.recommendedAction ?? '')}>
                      {checkResult.recommendedActionLabel ??
                        actionLabel(checkResult.recommendedAction ?? '')}
                    </Badge>
                  </div>
                  <div className="text-copy-12 text-muted-foreground">
                    原因：{checkResult.reason}
                  </div>
                  <div className="text-copy-12 text-muted-foreground">
                    拦截标记已落库：{checkResult.flagId ?? '—'}
                  </div>
                </>
              ) : (
                <>
                  <div className="text-copy-13 text-foreground font-medium">
                    {emptyState('NOT_HIT').title}
                  </div>
                  <div className="text-copy-12 text-muted-foreground whitespace-pre-line">
                    {emptyState('NOT_HIT').description}
                  </div>
                </>
              )}
              <div className="text-copy-12 text-muted-foreground">
                {checkResult.buyerKnown
                  ? '9.2 审单已确认这个人。'
                  : emptyState('UPSTREAM_UNKNOWN').description}
              </div>
              <div className="text-copy-12 text-muted-foreground">
                最近一次风险评分（16.5.1.2）：{scoreText(checkResult.latestRiskScore)}
                {checkResult.latestEvaluationStatus
                  ? ` · ${checkResult.latestEvaluationStatus}`
                  : ''}
                {checkResult.latestRecommendedAction
                  ? ` · 建议 ${actionLabel(checkResult.latestRecommendedAction)}`
                  : ''}
              </div>
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---------------- 订单拦截标记 ---------------- */}
      <SectionCard
        title="订单拦截标记"
        description="🔴 时点事实：下单那一刻命中黑名单就落一行，后来解除黑名单**不改写**这一行。"
        extra={
          <div className="flex items-center gap-2">
            <select
              className="border-control-border bg-card text-copy-13 text-foreground h-8 rounded-md border px-2"
              value={actionFilter}
              onChange={(e) => setActionFilter(e.target.value)}
            >
              <option value="">全部动作</option>
              <option value="BLOCK">建议拦截</option>
              <option value="REVIEW">建议人工复核</option>
              <option value="WATCH">建议观察</option>
            </select>
            <Button
              size="sm"
              variant="outline"
              onClick={() => {
                setFlagPage(0);
                void loadFlags(0);
              }}
            >
              筛选
            </Button>
          </div>
        }
      >
        {!flags || flags.items.length === 0 ? (
          <EmptyState
            icon={<ClipboardCheck className="h-6 w-6" />}
            title={emptyState('NO_FLAG').title}
            description={emptyState('NO_FLAG').description}
          />
        ) : (
          <div className="space-y-3">
            <div className="space-y-2">
              {flags.items.map((flag) => (
                <div key={flag.flagId} className="border-border rounded-md border px-3 py-2">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <span className="text-copy-13 text-foreground">
                      {flag.buyerName || flag.buyerKey}
                      {flag.shopCode ? ` · ${flag.shopCode}` : ''}
                    </span>
                    <Badge tone={actionTone(flag.recommendedAction)}>
                      {flag.recommendedActionLabel}
                    </Badge>
                  </div>
                  <div className="text-copy-12 text-muted-foreground mt-1">
                    {flag.flaggedAt} · 订单 {flag.orderRef || '（未给订单引用）'} · 风险等级{' '}
                    {riskLevelLabel(flag.riskLevel)}
                  </div>
                </div>
              ))}
            </div>
            <div className="text-copy-12 text-muted-foreground flex items-center justify-between">
              <span>
                第 {flags.page + 1} / {flagPages} 页 · 共 {flags.total} 条
              </span>
              <div className="flex gap-2">
                <Button
                  size="sm"
                  variant="outline"
                  disabled={flags.page <= 0}
                  onClick={() => {
                    const next = flags.page - 1;
                    setFlagPage(next);
                    void loadFlags(next);
                  }}
                >
                  上一页
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={flags.page + 1 >= flagPages}
                  onClick={() => {
                    const next = flags.page + 1;
                    setFlagPage(next);
                    void loadFlags(next);
                  }}
                >
                  下一页
                </Button>
              </div>
            </div>
          </div>
        )}
      </SectionCard>
    </div>
  );
}

/** 一行黑名单登记。 */
function EntryRow({ entry, onOpen }: { entry: BuyerBlacklistEntry; onOpen: () => void }) {
  return (
    <div className="border-border rounded-md border px-3 py-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="text-copy-13 text-foreground font-medium">
          {entry.buyerName || '（未登记姓名）'} · {entry.buyerEmail || '无邮箱'}
        </span>
        <div className="flex flex-wrap items-center gap-2">
          <Badge tone={statusTone(entry.status)}>{statusLabel(entry.status)}</Badge>
          <Badge tone={riskLevelTone(entry.riskLevel)}>风险等级 {entry.riskLevelLabel}</Badge>
          <Badge tone="neutral">
            {entry.scopeLabel}
            {entry.shopCode ? ` · ${entry.shopCode}` : ''}
          </Badge>
        </div>
      </div>
      <div className="text-copy-12 text-muted-foreground mt-1 whitespace-pre-line">
        原因：{entry.reason} · 登记于 {entry.registeredAt}（{entry.registeredBy}）· 来源{' '}
        {entry.sourceLabel}
      </div>
      <div className="text-copy-12 text-muted-foreground mt-1 break-all">
        主体键：{entry.buyerKey} · 申诉 {entry.appealCount} 张（待裁决 {entry.openAppealCount}）·
        拦截标记 {entry.flagCount} 条
      </div>
      <div className="mt-2">
        <Button size="sm" variant="outline" onClick={onOpen}>
          查看详情与留痕
        </Button>
      </div>
    </div>
  );
}
