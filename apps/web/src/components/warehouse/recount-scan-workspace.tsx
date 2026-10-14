'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { RefreshCw } from 'lucide-react';
import { Button, EmptyState, Input, Notice, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  RecountCapabilities,
  RecountDetail,
  RecountExceptionRow,
  RecountPage,
} from '../../../../../packages/api-contracts/src/index';
import { useIdentity } from '../iam/identity-shell';
import {
  cancelRecount,
  canComplete,
  canEscalate,
  canOpenBox,
  canScan,
  completeRecount,
  escalateRecount,
  exceptionKindLabel,
  fetchRecount,
  fetchRecountCapabilities,
  fetchRecounts,
  highValueHint,
  lineStatusLabel,
  lineStatusTone,
  lineWarnings,
  openRecountBox,
  qtyText,
  resolveRecountException,
  resolutionLabel,
  scanRecountItem,
  scanTypeLabel,
  settleRecount,
  shortfallText,
  statusLabel,
  statusTone,
  verdictLabel,
} from './recount-scan-model';

const STATUS_OPTIONS = ['OPEN', 'PASSED', 'FAILED', 'CANCELLED'];
const RESOLUTIONS = ['MANUAL', 'CORRECTED', 'ACCEPTED', 'IGNORED'];

const TONE_CLASS: Record<string, string> = {
  success: 'rounded bg-emerald-900/40 px-2 py-0.5 text-xs text-emerald-200',
  danger: 'rounded bg-rose-900/40 px-2 py-0.5 text-xs text-rose-200',
  info: 'rounded bg-indigo-900/40 px-2 py-0.5 text-xs text-indigo-200',
  neutral: 'rounded bg-slate-800 px-2 py-0.5 text-xs text-slate-300',
};

/**
 * 8.3.3.1 复核扫描工作区。
 *
 * <p>蓝图：「<b>打包前复核：逐件扫描商品与订单核对，检出漏件/错件/多件，支持开箱复核（高价值订单）。</b>」
 * 「<b>复核是错发拦截的最后机会</b>，序列号商品必须逐件核对。」
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>基线是这一页的主干。</b>复核单照「应发 / 实拣 / 实扫」三列并排打印，
 *       作业员扫一件就减一件 —— 三个数必须分开，因为「拣漏了」和「复核没扫到」
 *       要退回的是不同的环节。</li>
 *   <li><b>序列号逐行给出应扫号与已扫号。</b>件数相等但集合不等时，那一行必须顶到最前面
 *       并说清缺哪个号、多哪个号 —— 「序列号不对」四个字没法让人去翻三个号。</li>
 *   <li><b>「完成复核」按钮的可点性由后端的 {@code blocking} 决定</b>，
 *       拦路原因逐条显示。页面不自己数行、不自己判异常。</li>
 *   <li><b>完成后的提示逐字写明「货在打包区、预占仍在」</b>：复核是只读核对动作，
 *       作业员最容易误解的就是「复核完了是不是就发出去了」。</li>
 *   <li><b>高价值只说件数</b>，不编金额（出库单上没有金额列）。</li>
 *   <li>加载 / 空 / 错误三态分开渲染。</li>
 * </ul>
 */
export function RecountScanWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.inventory.read');
  const writable = session.permissions.includes('warehouse.inventory.update');

  const [page, setPage] = useState(0);
  const [status, setStatus] = useState('');
  const [keyword, setKeyword] = useState('');
  const [exceptionsOnly, setExceptionsOnly] = useState(false);
  const [list, setList] = useState<RecountPage | null>(null);
  const [caps, setCaps] = useState<RecountCapabilities | null>(null);
  const [detail, setDetail] = useState<RecountDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [actionNote, setActionNote] = useState('');

  // 扫描表单
  const [scanType, setScanType] = useState<'BARCODE' | 'SERIAL'>('BARCODE');
  const [scanValue, setScanValue] = useState('');

  // 开箱
  const [boxReason, setBoxReason] = useState('');

  // 异常处置
  const [resolvingId, setResolvingId] = useState('');
  const [resolution, setResolution] = useState('CORRECTED');
  const [resolveNote, setResolveNote] = useState('');
  const [escalateNote, setEscalateNote] = useState('');

  const load = useCallback(async () => {
    if (!readable) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    try {
      const [data, capability] = await Promise.all([
        fetchRecounts({ status, keyword, exceptionsOnly, page, size: 50 }),
        fetchRecountCapabilities(),
      ]);
      setList(data);
      setCaps(capability);
    } catch (e) {
      setError(e instanceof Error ? e.message : '复核单读取失败');
    } finally {
      setLoading(false);
    }
  }, [status, keyword, exceptionsOnly, page, readable]);

  useEffect(() => {
    void load();
  }, [load]);

  const openSession = useCallback(
    async (sessionId: string) => {
      setActionError('');
      setActionNote('');
      try {
        setDetail(await fetchRecount(sessionId));
      } catch (e) {
        setActionError(e instanceof Error ? e.message : '复核单详情读取失败');
      }
    },
    [],
  );

  const run = async (work: () => Promise<unknown>, sessionId?: string) => {
    setActionError('');
    setActionNote('');
    try {
      const result = await work();
      if (typeof result === 'string' && result !== '') setActionNote(result);
      await load();
      if (sessionId) await openSession(sessionId);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '操作失败');
    }
  };

  const rows = useMemo(() => list?.items ?? [], [list]);
  const summary = list?.summary;
  // 刻意叫 recount 而不是 session：外层已有 useIdentity() 的 session（身份），
  // 同名遮蔽会让整页的类型退化成 Access，typecheck 会一路报「属性不存在」。
  const recount = detail?.session;
  const verdict = detail?.verdict;

  if (!readable) {
    return (
      <SectionCard>
        <EmptyState
          title="没有查看复核单的权限"
          description="本功能需要出入库作业读取权限（warehouse.inventory.read）。"
        />
      </SectionCard>
    );
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="复核扫描"
        description="打包前逐件扫描商品与订单核对，检出漏件、错件、多件；高价值订单可开箱复核"
        actions={
          <Button onClick={() => void load()} disabled={loading}>
            <RefreshCw className="h-4 w-4" /> 刷新
          </Button>
        }
      />

      {error !== '' && (
        <Notice tone="danger" title="复核单读取失败">
          {error}
        </Notice>
      )}
      {actionError !== '' && (
        <Notice tone="danger" title="操作失败">
          {actionError}
        </Notice>
      )}
      {actionNote !== '' && (
        <Notice tone="success" title="已完成">
          {actionNote}
        </Notice>
      )}

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
        <StatCard label="复核单总数" value={String(summary?.totalSessions ?? 0)} />
        <StatCard label="复核中" value={String(summary?.openSessions ?? 0)} />
        <StatCard label="复核通过" value={String(summary?.passedSessions ?? 0)} />
        <StatCard label="复核异常" value={String(summary?.failedSessions ?? 0)} />
        <StatCard
          label="待处理异常"
          value={String(summary?.pendingExceptions ?? 0)}
        />
      </div>

      {caps && (
        <SectionCard title="这一页的口径" description="复核是错发拦截的最后机会">
          <ul className="space-y-2 text-sm text-slate-300">
            <li>
              漏件 <b>{summary?.missingItems ?? 0}</b> · 错件 <b>{summary?.wrongItems ?? 0}</b> · 多件{' '}
              <b>{summary?.extraItems ?? 0}</b> · 序列号集合不符 <b>{summary?.serialMismatches ?? 0}</b> · 转人工处理{' '}
              <b>{summary?.manualExceptions ?? 0}</b>
            </li>
            <li>
              应发合计 <b>{qtyText(summary?.baselineQuantity)}</b> 件 / 实扫合计{' '}
              <b>{qtyText(summary?.scannedQuantity)}</b> 件
            </li>
            <li className="text-xs text-slate-400">{caps.verifyBasis}</li>
            <li className="text-xs text-slate-400">
              <b>「高价值」在本服务只能取件数</b>：{caps.highValueBasis}
            </li>
            <li className="text-xs text-slate-400">
              <b>复核不碰库存</b>：货在打包区、预占不释放、不写库存分录。
              通过只是把出库单交到「待打包」（{caps.downstreamModule}
              {caps.packingHandoffLive ? '' : '，尚未交付'}），发货由 8.3.4.1 写。
            </li>
          </ul>
        </SectionCard>
      )}

      <SectionCard title="复核单" description="一张出库单同时只能有一张复核中的复核单；返工轮次是新会话">
        <div className="space-y-3">
          <div className="grid gap-3 sm:grid-cols-3">
            <label className="text-sm text-slate-400">
              状态
              <select
                className="mt-1 w-full rounded border border-slate-700 bg-slate-900 px-2 py-1 text-sm"
                value={status}
                onChange={(e) => {
                  setStatus(e.target.value);
                  setPage(0);
                }}
              >
                <option value="">全部</option>
                {STATUS_OPTIONS.map((s) => (
                  <option key={s} value={s}>
                    {statusLabel(s)}
                  </option>
                ))}
              </select>
            </label>
            <label className="text-sm text-slate-400">
              关键字（复核单号 / 出库单号 / 拣货单号）
              <Input
                className="mt-1"
                value={keyword}
                onChange={(e) => {
                  setKeyword(e.target.value);
                  setPage(0);
                }}
              />
            </label>
            <label className="mt-6 flex items-center gap-2 text-sm text-slate-400">
              <input
                type="checkbox"
                checked={exceptionsOnly}
                onChange={(e) => {
                  setExceptionsOnly(e.target.checked);
                  setPage(0);
                }}
              />
              只看有异常的
            </label>
          </div>

          {loading && <p className="text-sm text-slate-400">读取中…</p>}
          {!loading && rows.length === 0 && (
            <EmptyState
              title="没有复核单"
              description="复核单由拣货任务完成、出库单进入「待复核」之后开出来。"
            />
          )}
          {!loading && rows.length > 0 && (
            <div className="space-y-3">
              <table className="w-full text-sm">
                <thead className="text-left text-slate-400">
                  <tr>
                    <th className="py-2">复核单号</th>
                    <th className="py-2">出库单</th>
                    <th className="py-2">状态</th>
                    <th className="py-2">应发 / 实扫</th>
                    <th className="py-2">核过</th>
                    <th className="py-2">异常</th>
                    <th className="py-2">开箱</th>
                    <th className="py-2" />
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.id} className="border-t border-slate-800">
                      <td className="py-2 font-mono text-xs">{row.sessionCode}</td>
                      <td className="py-2">
                        {row.outboundOrderCode}
                        <span className="ml-2 text-xs text-slate-400">
                          {row.outboundOrderStatusLabel ?? row.outboundOrderStatus}
                        </span>
                      </td>
                      <td className="py-2">
                        <span className={TONE_CLASS[statusTone(row.status)]}>
                          {statusLabel(row.status)}
                        </span>
                      </td>
                      <td className="py-2">
                        {qtyText(row.baselineQuantity)} / {qtyText(row.scannedQuantity)}
                      </td>
                      <td className="py-2">
                        {row.matchedLineCount} / {row.baselineLineCount}
                        {row.mismatchLineCount > 0 ? `（${row.mismatchLineCount} 行没核过）` : ''}
                      </td>
                      <td className="py-2">
                        {row.exceptionCount}
                        {row.pendingExceptionCount > 0 ? `（${row.pendingExceptionCount} 条待处理）` : ''}
                      </td>
                      <td className="py-2 text-xs">
                        {row.openBox ? '已开箱' : row.highValue ? '建议开箱' : ''}
                      </td>
                      <td className="py-2 text-right">
                        <Button onClick={() => void openSession(row.id)}>详情</Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <div className="flex items-center justify-between text-sm text-slate-400">
                <span>共 {list?.total ?? 0} 张（汇总不受筛选影响）</span>
                <div className="flex gap-2">
                  <Button disabled={page === 0} onClick={() => setPage(page - 1)}>
                    上一页
                  </Button>
                  <Button disabled={rows.length < 50} onClick={() => setPage(page + 1)}>
                    下一页
                  </Button>
                </div>
              </div>
            </div>
          )}
        </div>
      </SectionCard>

      {detail && recount && (
        <>
          <SectionCard
            title={`复核单 ${recount.sessionCode}`}
            description={`出库单 ${recount.outboundOrderCode} · 拣货任务 ${recount.pickTaskCode}`}
          >
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <StatCard label="状态" value={statusLabel(recount.status)} />
              <StatCard
                label="核过的行"
                value={`${recount.matchedLineCount} / ${recount.baselineLineCount}`}
              />
              <StatCard
                label="实扫 / 应发"
                value={`${qtyText(recount.scannedQuantity)} / ${qtyText(recount.baselineQuantity)}`}
              />
              <StatCard
                label="待处理异常"
                value={String(recount.pendingExceptionCount)}
              />
            </div>

            <p className="mt-3 text-xs text-slate-400">{recount.verifyBasis}</p>
            {highValueHint(verdict) !== '' && (
              <p className="mt-2 text-sm text-amber-200">{highValueHint(verdict)}</p>
            )}

            {verdict && verdict.blocking && (
              <Notice tone="warning" title={`还不能放行：${verdictLabel(verdict.verdict)}`}>
                <ul className="list-disc pl-5">
                  {verdict.blockingReasons.map((r) => (
                    <li key={r}>{r}</li>
                  ))}
                </ul>
              </Notice>
            )}
          </SectionCard>

          {/* ---------------- 基线行：应发 / 实拣 / 实扫 + 逐个序列号 ---------------- */}
          <SectionCard
            title="复核基线"
            description="逐行以订单明细为应发基线；序列号商品必须逐件对上，件数相等不算过"
          >
            {detail.lines.length === 0 ? (
              <EmptyState title="没有基线行" description="这一张没有可核的明细。" />
            ) : (
              <div className="space-y-3">
                {detail.lines
                  .slice()
                  .sort((a, b) => {
                    // 没核过的顶到最前面：作业员只关心还差哪几行
                    const rank = (s: string) => (s === 'MATCHED' ? 1 : 0);
                    return rank(a.lineStatus) - rank(b.lineStatus) || a.lineNo - b.lineNo;
                  })
                  .map((line) => {
                    const warnings = lineWarnings(line);
                    const gap = shortfallText(line.expectedQuantity, line.scannedQuantity);
                    return (
                      <div
                        key={line.id}
                        className="rounded border border-slate-800 p-3 text-sm"
                      >
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="font-mono text-xs text-slate-400">
                            #{line.lineNo}
                          </span>
                          <span className="font-medium">{line.skuCode}</span>
                          <span className="text-xs text-slate-400">{line.skuName}</span>
                          <span className={TONE_CLASS[lineStatusTone(line.lineStatus)]}>
                            {lineStatusLabel(line.lineStatus)}
                          </span>
                          {line.requiresSerial && (
                            <span className="rounded bg-slate-800 px-2 py-0.5 text-xs text-slate-300">
                              逐件核对 {line.expectedSerialCount} 号
                              {line.serialMatch ? ' · 已对上' : ' · 未对上'}
                            </span>
                          )}
                        </div>

                        <div className="mt-2 grid gap-1 text-xs text-slate-300 sm:grid-cols-3">
                          <span>应发（订单明细）：{qtyText(line.expectedQuantity)} 件</span>
                          <span>实拣（8.3.2.1）：{qtyText(line.pickedQuantity)} 件</span>
                          <span>
                            实扫：{qtyText(line.scannedQuantity)} 件
                            {gap !== '' ? `（${gap}）` : ''}
                          </span>
                        </div>

                        {line.requiresSerial && (
                          <div className="mt-2 grid gap-2 text-xs sm:grid-cols-2">
                            <div>
                              <span className="text-slate-400">应扫（实拣登记的号）：</span>
                              <span className="ml-1 font-mono">
                                {line.expectedSerials.length > 0
                                  ? line.expectedSerials.join('、')
                                  : '—'}
                              </span>
                            </div>
                            <div>
                              <span className="text-slate-400">已扫：</span>
                              <span
                                className={
                                  line.serialMatch ? 'ml-1 font-mono' : 'ml-1 font-mono text-rose-200'
                                }
                              >
                                {line.scannedSerials.length > 0
                                  ? line.scannedSerials.join('、')
                                  : '—'}
                              </span>
                            </div>
                          </div>
                        )}

                        {warnings.length > 0 && (
                          <ul className="mt-2 list-disc pl-5 text-xs text-amber-200">
                            {warnings.map((w) => (
                              <li key={w}>{w}</li>
                            ))}
                          </ul>
                        )}
                      </div>
                    );
                  })}
              </div>
            )}
          </SectionCard>

          {/* ---------------- 扫一件 / 开箱 / 结算 / 完成 ---------------- */}
          {canScan(recount.status) && (
            <SectionCard
              title="扫一件"
              description="条码扫一次算一件；序列号一件一号，扫第二遍会被记成重复件"
            >
              <div className="grid gap-3 sm:grid-cols-4">
                <label className="text-sm text-slate-400">
                  扫描方式
                  <select
                    className="mt-1 w-full rounded border border-slate-700 bg-slate-900 px-2 py-1 text-sm"
                    value={scanType}
                    onChange={(e) => setScanType(e.target.value as 'BARCODE' | 'SERIAL')}
                  >
                    <option value="BARCODE">条码</option>
                    <option value="SERIAL">序列号</option>
                  </select>
                </label>
                <label className="text-sm text-slate-400 sm:col-span-3">
                  {scanTypeLabel(scanType)}
                  <Input
                    className="mt-1"
                    value={scanValue}
                    placeholder={
                      scanType === 'SERIAL' ? '单件序列号，如 SN-0001' : '商品条码，如 SKU-1'
                    }
                    onChange={(e) => setScanValue(e.target.value)}
                  />
                </label>
              </div>
              <div className="mt-3 flex gap-2">
                <Button
                  disabled={!writable || scanValue.trim() === ''}
                  onClick={() =>
                    void run(async () => {
                      await scanRecountItem(recount.id, {
                        scanType,
                        scannedValue: scanValue.trim(),
                        scannedAt: null,
                        note: null,
                      });
                      setScanValue('');
                    }, recount.id)
                  }
                >
                  记录这一件
                </Button>
                <Button
                  disabled={!writable}
                  onClick={() => void run(() => settleRecount(recount.id), recount.id)}
                >
                  结算（检出漏件 / 多件）
                </Button>
              </div>
              <p className="mt-2 text-xs text-slate-400">
                漏件与多件<b>只在结算时定论</b>：扫描过程中「还没扫齐」和「扫完发现少了」
                根本区分不出来。
              </p>
            </SectionCard>
          )}

          <SectionCard
            title="开箱复核"
            description={
              recount.openBox
                ? `已开箱：${recount.openBoxReason ?? ''}`
                : '高价值订单可开箱复核；开箱要人做、要写原因，系统只给建议'
            }
          >
            {recount.openBox ? (
              <p className="text-sm text-slate-300">
                这一单已开箱复核。{highValueHint(verdict)}
              </p>
            ) : canOpenBox(recount.status, recount.openBox) ? (
              <div className="space-y-3">
                <Input
                  value={boxReason}
                  placeholder="为什么这一单要开箱（必填）"
                  onChange={(e) => setBoxReason(e.target.value)}
                />
                <Button
                  disabled={!writable || boxReason.trim() === ''}
                  onClick={() =>
                    void run(async () => {
                      await openRecountBox(recount.id, boxReason.trim());
                      setBoxReason('');
                    }, recount.id)
                  }
                >
                  开箱
                </Button>
              </div>
            ) : (
              <EmptyState title="不需要开箱" description="这一单已经结束复核。" />
            )}
          </SectionCard>

          {/* ---------------- 异常 ---------------- */}
          <SectionCard
            title="复核异常"
            description="漏件、错件、多件、重复扫、序列号集合不符、未知码；异常单可转人工处理"
          >
            {detail.exceptions.length === 0 ? (
              <EmptyState title="没有异常" description="扫到目前没有检出任何与订单明细对不上的地方。" />
            ) : (
              <div className="space-y-3">
                {canEscalate(recount.pendingExceptionCount, recount.status) && (
                  <div className="rounded border border-slate-800 p-3">
                    <p className="text-sm text-slate-300">
                      把这一单全部 <b>{recount.pendingExceptionCount}</b> 条待处理异常一次性转人工处理。
                      <b>转人工不等于放行</b> —— 它清的是「谁来管」，不是「这一行核过了」。
                    </p>
                    <div className="mt-2 flex gap-2">
                      <Input
                        value={escalateNote}
                        placeholder="处理意见（必填：转给谁、要怎么处理）"
                        onChange={(e) => setEscalateNote(e.target.value)}
                      />
                      <Button
                        disabled={!writable || escalateNote.trim() === ''}
                        onClick={() =>
                          void run(async () => {
                            await escalateRecount(recount.id, escalateNote.trim());
                            setEscalateNote('');
                          }, recount.id)
                        }
                      >
                        转人工
                      </Button>
                    </div>
                  </div>
                )}

                <table className="w-full text-sm">
                  <thead className="text-left text-slate-400">
                    <tr>
                      <th className="py-2">种类</th>
                      <th className="py-2">SKU / 序列号</th>
                      <th className="py-2">应发 / 实扫</th>
                      <th className="py-2">说明</th>
                      <th className="py-2">处置</th>
                      <th className="py-2" />
                    </tr>
                  </thead>
                  <tbody>
                    {detail.exceptions.map((e: RecountExceptionRow) => (
                      <tr key={e.id} className="border-t border-slate-800 align-top">
                        <td className="py-2">
                          <span
                            className={
                              e.resolution === 'PENDING'
                                ? TONE_CLASS.danger
                                : TONE_CLASS.neutral
                            }
                          >
                            {exceptionKindLabel(e.kind)}
                          </span>
                        </td>
                        <td className="py-2 text-xs">
                          {e.skuCode}
                          {e.serialNumber ? ` · ${e.serialNumber}` : ''}
                        </td>
                        <td className="py-2 text-xs">
                          {e.expectedQuantity ?? '—'} / {e.scannedQuantity ?? '—'}
                        </td>
                        <td className="py-2 text-xs text-slate-300">
                          {e.detail}
                          {e.manualNote ? (
                            <span className="mt-1 block text-slate-400">处置：{e.manualNote}</span>
                          ) : null}
                        </td>
                        <td className="py-2 text-xs">{resolutionLabel(e.resolution)}</td>
                        <td className="py-2 text-right">
                          {e.resolution === 'PENDING' && canScan(recount.status) && (
                            <Button
                              onClick={() => {
                                setResolvingId(e.id);
                                setResolution('CORRECTED');
                                setResolveNote('');
                              }}
                            >
                              处置
                            </Button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>

                {resolvingId !== '' && (
                  <div className="rounded border border-slate-700 p-3">
                    <p className="text-sm text-slate-300">
                      处置意见<b>必填</b>：「已处理」而不写怎么处理，半年后回看等于没处理。
                    </p>
                    <div className="mt-2 grid gap-2 sm:grid-cols-4">
                      <select
                        className="rounded border border-slate-700 bg-slate-900 px-2 py-1 text-sm"
                        value={resolution}
                        onChange={(e) => setResolution(e.target.value)}
                      >
                        {RESOLUTIONS.map((r) => (
                          <option key={r} value={r}>
                            {resolutionLabel(r)}
                          </option>
                        ))}
                      </select>
                      <Input
                        className="sm:col-span-3"
                        value={resolveNote}
                        placeholder="处理意见（必填）"
                        onChange={(e) => setResolveNote(e.target.value)}
                      />
                    </div>
                    <div className="mt-2 flex gap-2">
                      <Button
                        disabled={!writable || resolveNote.trim() === ''}
                        onClick={() =>
                          void run(async () => {
                            await resolveRecountException(
                              recount.id,
                              resolvingId,
                              resolution,
                              resolveNote.trim(),
                            );
                            setResolvingId('');
                          }, recount.id)
                        }
                      >
                        确认处置
                      </Button>
                      <Button onClick={() => setResolvingId('')}>取消</Button>
                    </div>
                  </div>
                )}
              </div>
            )}
          </SectionCard>

          {/* ---------------- 完成 / 取消 ---------------- */}
          {canScan(recount.status) && (
            <SectionCard
              title="完成复核"
              description="通过 = 每一行都核过、序列号逐件对上、没有未处理的异常"
            >
              <div className="flex flex-wrap gap-2">
                <Button
                  disabled={!writable}
                  onClick={() =>
                    void run(async () => {
                      const report = await completeRecount(recount.id);
                      setActionNote(
                        report.verdict === 'PASSED'
                          ? '复核通过：出库单已交到「待打包」。复核不写库存分录、不释放预占 —— '
                            + '货与占用都还在打包区，发货由 8.3.4.1 写。'
                          : `复核没通过：${report.blockingReasons.join('；')}。`
                            + '检出拣错或漏件时的出口是退回拣货（8.3.2.1 重开），本模块不改库存。',
                      );
                    }, recount.id)
                  }
                >
                  完成复核
                </Button>
                <Button
                  disabled={!writable}
                  onClick={() =>
                    void run(async () => {
                      await cancelRecount(
                        recount.id,
                        window.prompt('取消原因（必填）') ?? '',
                      );
                    }, recount.id)
                  }
                >
                  取消复核
                </Button>
              </div>
              <p className="mt-2 text-xs text-slate-400">
                「完成复核」**不是发货、也不是打包**：它只把出库单交到「待打包」。
                检出拣错或漏件时出口是<b>退回拣货</b>（出库单「待复核」→「拣货中」，
                由 8.3.2.1 重开），本模块不改库存、不释放预占。
                {canComplete(recount.status, verdict)
                  ? '当前无拦路项，可以完成。'
                  : verdict && verdict.blocking
                    ? `当前还不能放行：${verdict.blockingReasons.join('；')}`
                    : ''}
              </p>
            </SectionCard>
          )}

          {/* ---------------- 时间线 ---------------- */}
          <SectionCard title="作业留痕" description="复核流水是 append-only 的事实凭据">
            <ul className="space-y-2 text-sm">
              {detail.timeline.map((e) => (
                <li key={e.id} className="border-l-2 border-slate-700 pl-3">
                  <span className="text-xs text-slate-400">
                    {new Date(e.occurredAt).toLocaleString()}
                  </span>
                  <span className="ml-2 font-medium">{e.eventTypeLabel ?? e.eventType}</span>
                  <span className="ml-2 text-slate-300">{e.detail}</span>
                </li>
              ))}
            </ul>
          </SectionCard>
        </>
      )}
    </div>
  );
}