'use client';

import { useCallback, useEffect, useState } from 'react';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  PlatformAppealCapabilities,
  PlatformAppealDetail,
  PlatformAppealPage,
} from '../../../../../packages/api-contracts/src/index';
import {
  actionBlockedReason,
  APPEAL_RESULT_OPTIONS,
  APPEAL_STATUS_OPTIONS,
  attachPlatformDisputeAppealMaterial,
  coverageText,
  createPlatformDisputeAppeal,
  fetchPlatformDisputeAppeal,
  fetchPlatformDisputeAppealCapabilities,
  fetchPlatformDisputeAppeals,
  formatAmount,
  formatTime,
  isTerminalStatus,
  recordPlatformDisputeAppealReply,
  recordPlatformDisputeAppealSubmissionAttempt,
  removePlatformDisputeAppealMaterial,
  retryPlatformDisputeAppealSubmission,
  SOURCE_MODULE_OPTIONS,
  sourceText,
  submitPlatformDisputeAppeal,
  SUBMISSION_OUTCOME_OPTIONS,
  SUBMISSION_STATUS_OPTIONS,
  updatePlatformDisputeAppealText,
  withdrawPlatformDisputeAppeal,
  windowText,
  windowTone,
} from './platform-dispute-appeal-model';

/**
 * 11.4.1.2 证据与申诉。
 *
 * <p>蓝图：「组织举证材料（物流签收、沟通记录、商品照片、检测报告）打包提交申诉，
 * 记录平台回复与结果。」输入：「举证材料；申诉文本（可用模板）；提交动作。」
 * 输出：「申诉提交记录 + 平台回复 + 结果（胜诉/败诉/部分）。」
 * 加粗句：「<b>举证材料必须来自系统记录</b>（轨迹、沟通日志），临时拼凑的证据在申诉中往往不被采信。」
 *
 * <p><b>页面围绕三条硬规则组织</b>：
 *
 * <ol>
 *   <li><b>材料只能引用系统里已有的一条事实记录</b>：界面上的「挂材料」表单要填的是
 *       <b>来源系统 + 来源记录 id</b>，**没有「上传文件」**。
 *       证据类型也**不让填** —— 它由后端按来源记录推导，界面只显示推导结果。</li>
 *   <li><b>「能不能提交」只认后端判据</b>：{@code gate} 是 DB 判据函数
 *       （{@code platform_dispute_appeal_gate}）的输出，与 DB 触发器同一个判据。
 *       页面**不自己算一遍时限**。</li>
 *   <li><b>「清单齐了」与「包里覆盖了」是两个数字</b>：分开显示，
 *       否则提交被拒时人不知道该补什么。</li>
 * </ol>
 *
 * <p>这是**真实工作区**：数据全部来自 {@code GET /trade/platform-dispute-appeals} 等真实端点，
 * 加载 / 空 / 错误三态齐全，不是只有字段名的占位页。
 */
export function PlatformDisputeAppealWorkspace() {
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState('');
  const [currency, setCurrency] = useState('');
  const [submissionStatus, setSubmissionStatus] = useState('');
  const [submittableOnly, setSubmittableOnly] = useState(false);
  const [keyword, setKeyword] = useState('');

  const [data, setData] = useState<PlatformAppealPage | null>(null);
  const [caps, setCaps] = useState<PlatformAppealCapabilities | null>(null);
  const [detail, setDetail] = useState<PlatformAppealDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  // ---- 建申诉草稿 ----
  const [newDisputeId, setNewDisputeId] = useState('');
  const [newText, setNewText] = useState('');
  const [newTemplate, setNewTemplate] = useState('');
  // 申诉文本的编辑缓冲：草稿态可改，提交后冻结。
  const [editText, setEditText] = useState('');

  // ---- 挂材料 ----
  const [sourceModule, setSourceModule] = useState('SHIPMENT_TRACKING_OBSERVATION');
  const [sourceRefId, setSourceRefId] = useState('');
  const [materialNote, setMaterialNote] = useState('');

  // ---- 提交 / 平台回复 / 撤诉 ----
  const [submitNote, setSubmitNote] = useState('');
  const [replyText, setReplyText] = useState('');
  const [replyReference, setReplyReference] = useState('');
  const [replyResult, setReplyResult] = useState('WON');
  const [awardedAmount, setAwardedAmount] = useState('');
  const [resolutionNote, setResolutionNote] = useState('');
  const [withdrawReason, setWithdrawReason] = useState('');
  const [attemptOutcome, setAttemptOutcome] = useState('SENT');
  const [attemptError, setAttemptError] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      // ⚠️ exactOptionalPropertyTypes：筛选条件只能「要么有要么没有」。
      const filters: Parameters<typeof fetchPlatformDisputeAppeals>[0] = { page, size: 20 };
      if (status) filters.status = status;
      if (currency) filters.currency = currency;
      if (submissionStatus) filters.submissionStatus = submissionStatus;
      if (submittableOnly) filters.submittableOnly = true;
      if (keyword) filters.keyword = keyword;
      setData(await fetchPlatformDisputeAppeals(filters));
      setCaps(await fetchPlatformDisputeAppealCapabilities());
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [page, status, currency, submissionStatus, submittableOnly, keyword]);

  useEffect(() => {
    void load();
  }, [load]);

  const open = useCallback(async (id: string) => {
    setError(null);
    try {
      const next = await fetchPlatformDisputeAppeal(id);
      setDetail(next);
      setEditText(next.appealText);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);

  const run = useCallback(
    async (work: () => Promise<PlatformAppealDetail>) => {
      setBusy(true);
      setError(null);
      try {
        const next = await work();
        setDetail(next);
        setData(await fetchPlatformDisputeAppeals({ page, size: 20 }));
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      } finally {
        setBusy(false);
      }
    },
    [page],
  );

  const head = detail?.head;
  const gate = detail?.gate;
  const terminal = isTerminalStatus(head?.appealStatus);

  return (
    <div className="space-y-4 p-4">
      <PageHeader
        title="证据与申诉"
        description="组织举证材料（物流签收、沟通记录、商品照片、检测报告）打包提交申诉，记录平台回复与结果。"
        actions={
          <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
            <RefreshCw className="mr-1 h-4 w-4" />
            刷新
          </Button>
        }
      />

      {error ? (
        <div className="flex items-start gap-2 rounded border border-red-300 bg-red-50 p-3 text-sm text-red-800">
          <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <span>{error}</span>
        </div>
      ) : null}

      {/* ---- 汇总 ---- */}
      <div className="grid gap-3 md:grid-cols-4">
        <StatCard
          label="申诉总数"
          value={String(data?.summary.totalAppeals ?? 0)}
          comparisonLabel={`草稿 ${data?.summary.draftCount ?? 0} · 已提交 ${data?.summary.submittedCount ?? 0}`}
        />
        <StatCard
          label="结果"
          value={`胜 ${data?.summary.wonCount ?? 0} / 负 ${data?.summary.lostCount ?? 0} / 部分 ${data?.summary.partialCount ?? 0}`}
          comparisonLabel={`撤诉 ${data?.summary.withdrawnCount ?? 0}`}
        />
        <StatCard
          label="当前可提交"
          value={String(data?.summary.submittableCount ?? 0)}
          comparisonLabel="由 DB 判据函数给出，与 DB 触发器同一个判据"
        />
        <StatCard
          label="还欠平台一次提交"
          value={String(data?.summary.pendingSubmissionCount ?? 0)}
          comparisonLabel="channel 侧没有申诉端点 ⇒ 只落待提交平台事实"
        />
      </div>

      {/* ---- 建申诉草稿 ---- */}
      <SectionCard title="建申诉草稿">
        <div className="grid gap-2 md:grid-cols-3">
          <Input
            placeholder="纠纷单 id（来自 11.4.1.1 纠纷列表）"
            value={newDisputeId}
            onChange={(e) => setNewDisputeId(e.target.value)}
          />
          <Input
            placeholder="申诉文本（必填）"
            value={newText}
            onChange={(e) => setNewText(e.target.value)}
          />
          <Input
            placeholder="话术模板码（可选，只记模板码）"
            value={newTemplate}
            onChange={(e) => setNewTemplate(e.target.value)}
          />
        </div>
        <div className="mt-2 flex items-center gap-2">
          <Button
            disabled={busy || !newDisputeId || !newText}
            onClick={() =>
              void run(async () =>
                createPlatformDisputeAppeal({
                  disputeId: newDisputeId,
                  appealText: newText,
                  templateCode: newTemplate || null,
                }),
              )
            }
          >
            建申诉
          </Button>
          <span className="text-muted-foreground text-xs">
            主张金额**不由这里填** —— 服务端从纠纷单上读（快照），不信客户端。
          </span>
        </div>
      </SectionCard>

      {/* ---- 申诉列表 ---- */}
      <SectionCard title="申诉列表">
        <div className="mb-3 grid gap-2 md:grid-cols-4">
          <Input
            placeholder="关键字（申诉号 / 平台纠纷号 / 申诉文本）"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
          />
          <select
            className="rounded border px-2 py-1 text-sm"
            value={status}
            onChange={(e) => setStatus(e.target.value)}
          >
            <option value="">全部状态</option>
            {APPEAL_STATUS_OPTIONS.map((o) => (
              <option key={o.code} value={o.code}>
                {o.label}
              </option>
            ))}
          </select>
          <select
            className="rounded border px-2 py-1 text-sm"
            value={submissionStatus}
            onChange={(e) => setSubmissionStatus(e.target.value)}
          >
            <option value="">全部提交状态</option>
            {SUBMISSION_STATUS_OPTIONS.map((o) => (
              <option key={o.code} value={o.code}>
                {o.label}
              </option>
            ))}
          </select>
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={submittableOnly}
              onChange={(e) => setSubmittableOnly(e.target.checked)}
            />
            只看当前可提交
          </label>
        </div>

        {loading ? (
          <div className="text-muted-foreground py-6 text-center text-sm">加载中…</div>
        ) : data && data.rows.length === 0 ? (
          <EmptyState
            title="没有申诉"
            description="从上面的表单建一份申诉草稿，或者调整筛选条件。"
          />
        ) : data ? (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-left">
                <th className="py-2">申诉</th>
                <th className="py-2">纠纷</th>
                <th className="py-2">金额</th>
                <th className="py-2">提交窗口</th>
                <th className="py-2">状态</th>
              </tr>
            </thead>
            <tbody>
              {data.rows.map((r) => (
                <tr
                  key={r.id}
                  className="hover:bg-muted/50 cursor-pointer border-b"
                  onClick={() => void open(r.id)}
                >
                  <td className="py-2">
                    <div>{r.appealNo}</div>
                    <div className="text-muted-foreground text-xs">
                      {formatTime(r.createdAt)} · 材料 {r.materialCount} 项
                    </div>
                  </td>
                  <td className="py-2">
                    <div>{r.disputeNo}</div>
                    <div className="text-muted-foreground text-xs">
                      {r.platformCode} · 平台纠纷号 {r.platformDisputeNo || '—'}
                    </div>
                  </td>
                  <td className="py-2">
                    {formatAmount(r.claimedAmount, r.claimedCurrency)}
                    {r.awardedAmount !== null && r.awardedAmount !== undefined ? (
                      <div className="text-muted-foreground text-xs">
                        平台裁定 {formatAmount(r.awardedAmount, r.awardedCurrency)}
                      </div>
                    ) : null}
                  </td>
                  <td className="py-2">
                    {/* ⚠️ 窗口状态是 DB 判据函数的输出，不是前端算的。 */}
                    <Badge tone={windowTone(r.windowState)}>
                      {windowText(r.windowState, r.windowStateLabel)}
                    </Badge>
                  </td>
                  <td className="py-2">
                    <div className="flex flex-wrap items-center gap-1">
                      <Badge
                        tone={
                          r.appealStatus === 'RESOLVED_WON'
                            ? 'success'
                            : r.appealStatus === 'RESOLVED_LOST'
                              ? 'danger'
                              : r.appealStatus === 'RESOLVED_PARTIAL'
                                ? 'warning'
                                : 'neutral'
                        }
                      >
                        {r.appealStatusLabel}
                      </Badge>
                      {r.submissionCount > 0 ? (
                        <span className="text-muted-foreground text-xs">
                          提交记录 {r.submissionCount} 条
                        </span>
                      ) : null}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}
      </SectionCard>

      {/* ---- 详情 ---- */}
      {detail && head ? (
        <SectionCard title={`申诉 ${head.appealNo}`}>
          <div className="grid gap-2 md:grid-cols-4">
            <div className="text-sm">
              <div className="text-muted-foreground text-xs">状态</div>
              <div>{head.appealStatusLabel}</div>
            </div>
            <div className="text-sm">
              <div className="text-muted-foreground text-xs">主张金额</div>
              <div>{formatAmount(head.claimedAmount, head.claimedCurrency)}</div>
            </div>
            <div className="text-sm">
              <div className="text-muted-foreground text-xs">提交窗口</div>
              <div>{windowText(head.windowState, head.windowStateLabel)}</div>
            </div>
            <div className="text-sm">
              <div className="text-muted-foreground text-xs">举证覆盖</div>
              <div>{gate ? coverageText(gate) : '—'}</div>
            </div>
          </div>

          <div className="mt-3 rounded border p-3 text-sm">
            <div className="text-muted-foreground text-xs">申诉文本</div>
            {head.appealStatus === 'DRAFT' ? (
              <Input
                className="mt-1"
                value={editText}
                onChange={(e) => setEditText(e.target.value)}
                placeholder="申诉文本"
              />
            ) : (
              <div className="whitespace-pre-wrap">{detail.appealText}</div>
            )}
            {head.templateCode ? (
              <div className="text-muted-foreground mt-1 text-xs">话术模板 {head.templateCode}</div>
            ) : null}
          </div>

          {/* ---- 证据材料 ---- */}
          <div className="mt-4">
            <div className="mb-1 text-sm font-medium">
              证据材料（{detail.materials.length}）
              <span className="text-muted-foreground ml-2 text-xs">
                只引用系统里已有的事实记录 —— 临时拼凑的证据在申诉中往往不被采信
              </span>
            </div>
            {detail.materials.length === 0 ? (
              <div className="text-muted-foreground text-sm">还没有挂材料。</div>
            ) : (
              <div className="space-y-1">
                {detail.materials.map((m) => (
                  <div
                    key={m.id}
                    className="hover:bg-muted/50 flex flex-wrap items-center gap-2 rounded border p-2 text-sm"
                  >
                    <Badge tone="info">{m.evidenceTypeLabel}</Badge>
                    <span className="text-xs">{sourceText(m)}</span>
                    {m.evidenceItemId ? (
                      <span className="text-muted-foreground text-xs">已覆盖一项必举证清单</span>
                    ) : null}
                    <Button
                      className="ml-auto"
                      size="sm"
                      variant="ghost"
                      disabled={
                        busy || actionBlockedReason(detail.actions, 'REMOVE_MATERIAL') !== null
                      }
                      onClick={() =>
                        void run(async () =>
                          removePlatformDisputeAppealMaterial(head.id, m.id),
                        )
                      }
                    >
                      移除
                    </Button>
                  </div>
                ))}
              </div>
            )}

            <div className="mt-2 grid gap-2 md:grid-cols-3">
              <select
                className="rounded border px-2 py-1 text-sm"
                value={sourceModule}
                onChange={(e) => setSourceModule(e.target.value)}
              >
                {SOURCE_MODULE_OPTIONS.map((o) => (
                  <option key={o.code} value={o.code}>
                    {o.label}
                  </option>
                ))}
              </select>
              <Input
                placeholder="来源记录 id（系统里已有的一行）"
                value={sourceRefId}
                onChange={(e) => setSourceRefId(e.target.value)}
              />
              <Input
                placeholder="材料说明（可选）"
                value={materialNote}
                onChange={(e) => setMaterialNote(e.target.value)}
              />
            </div>
            <div className="mt-2 flex items-center gap-2">
              <Button
                disabled={
                  busy || !sourceRefId || actionBlockedReason(detail.actions, 'ATTACH_MATERIAL') !== null
                }
                onClick={() =>
                  void run(async () =>
                    attachPlatformDisputeAppealMaterial(head.id, {
                      sourceModule,
                      sourceRefId,
                      evidenceItemId: null,
                      materialNote: materialNote || null,
                    }),
                  )
                }
              >
                挂材料
              </Button>
              <span className="text-muted-foreground text-xs">
                ⚠️ **没有「上传文件」**：证据类型由来源记录推导，界面不让人自己标类型。
              </span>
            </div>
          </div>

          {/* ---- 待提交平台事实 ---- */}
          <div className="mt-4">
            <div className="mb-1 text-sm font-medium">待提交平台事实</div>
            {detail.submissions.length === 0 ? (
              <div className="text-muted-foreground text-sm">
                还没有提交记录（提交申诉时自动登记一条）。
              </div>
            ) : (
              <div className="space-y-1">
                {detail.submissions.map((s) => (
                  <div
                    key={s.id}
                    className="hover:bg-muted/50 flex flex-wrap items-center gap-2 rounded border p-2 text-sm"
                  >
                    <Badge
                      tone={
                        s.submissionStatus === 'ACKNOWLEDGED'
                          ? 'success'
                          : s.submissionStatus === 'FAILED'
                            ? 'danger'
                            : s.submissionStatus === 'SENT'
                              ? 'info'
                              : 'warning'
                      }
                    >
                      {s.submissionStatusLabel}
                    </Badge>
                    <span className="text-muted-foreground text-xs">
                      目标 {s.targetSystem} / {s.targetKey} · 尝试 {s.attemptCount} 次 ·
                      幂等键 {s.idempotencyKey}
                    </span>
                    {s.lastError ? (
                      <span className="text-xs">上次失败：{s.lastError}</span>
                    ) : null}
                    {s.nextRetryAt ? (
                      <span className="text-muted-foreground text-xs">
                        下次重试 {formatTime(s.nextRetryAt)}
                      </span>
                    ) : null}
                  </div>
                ))}
              </div>
            )}
            <div className="mt-2 grid gap-2 md:grid-cols-3">
              <select
                className="rounded border px-2 py-1 text-sm"
                value={attemptOutcome}
                onChange={(e) => setAttemptOutcome(e.target.value)}
              >
                {SUBMISSION_OUTCOME_OPTIONS.map((o) => (
                  <option key={o.code} value={o.code}>
                    {o.label}
                  </option>
                ))}
              </select>
              <Input
                placeholder="失败原因（标「提交失败」时必填）"
                value={attemptError}
                onChange={(e) => setAttemptError(e.target.value)}
              />
              <div className="flex gap-1">
                <Button
                  size="sm"
                  variant="outline"
                  disabled={
                    busy ||
                    actionBlockedReason(detail.actions, 'RECORD_SUBMISSION_ATTEMPT') !== null
                  }
                  onClick={() =>
                    void run(async () =>
                      recordPlatformDisputeAppealSubmissionAttempt(head.id, {
                        outcome: attemptOutcome,
                        errorMessage: attemptError || null,
                        nextRetryAt: null,
                      }),
                    )
                  }
                >
                  记录尝试结果
                </Button>
                <Button
                  size="sm"
                  variant="ghost"
                  disabled={
                    busy || actionBlockedReason(detail.actions, 'RETRY_SUBMISSION') !== null
                  }
                  onClick={() =>
                    void run(async () =>
                      retryPlatformDisputeAppealSubmission(head.id, {
                        lastError: attemptError || null,
                      }),
                    )
                  }
                >
                  重试提交
                </Button>
              </div>
            </div>
          </div>

          {/* ---- 平台回复与结果 ---- */}
          {detail.platformReplyText ? (
            <div className="mt-4 rounded border p-3 text-sm">
              <div className="text-muted-foreground text-xs">
                平台回复 · {formatTime(head.platformReplyAt)}
                {detail.platformReplyReference ? ` · ${detail.platformReplyReference}` : ''}
              </div>
              <div className="whitespace-pre-wrap">{detail.platformReplyText}</div>
              {head.resolutionCode ? (
                <div className="mt-1 text-xs">
                  结果：{head.resolutionCodeLabel}
                  {head.awardedAmount !== null && head.awardedAmount !== undefined
                    ? ` · 裁定金额 ${formatAmount(head.awardedAmount, head.awardedCurrency)}`
                    : ''}
                </div>
              ) : null}
            </div>
          ) : null}

          {/* ---- 动作区 ---- */}
          <div className="mt-4 space-y-2 border-t pt-3">
            <div className="grid gap-2 md:grid-cols-3">
              <Input
                placeholder="提交说明"
                value={submitNote}
                onChange={(e) => setSubmitNote(e.target.value)}
              />
              <Input
                placeholder="平台回复正文"
                value={replyText}
                onChange={(e) => setReplyText(e.target.value)}
              />
              <Input
                placeholder="平台回复单号"
                value={replyReference}
                onChange={(e) => setReplyReference(e.target.value)}
              />
            </div>
            <div className="grid gap-2 md:grid-cols-4">
              <select
                className="rounded border px-2 py-1 text-sm"
                value={replyResult}
                onChange={(e) => setReplyResult(e.target.value)}
              >
                {APPEAL_RESULT_OPTIONS.map((o) => (
                  <option key={o.code} value={o.code}>
                    {o.label}
                  </option>
                ))}
              </select>
              <Input
                placeholder="平台裁定金额（「部分支持」时必填）"
                value={awardedAmount}
                onChange={(e) => setAwardedAmount(e.target.value)}
              />
              <Input
                placeholder="结果说明"
                value={resolutionNote}
                onChange={(e) => setResolutionNote(e.target.value)}
              />
              <Input
                placeholder="撤诉理由"
                value={withdrawReason}
                onChange={(e) => setWithdrawReason(e.target.value)}
              />
            </div>
            <div className="flex flex-wrap items-end gap-2">
              <Button
                variant="outline"
                disabled={
                  busy || actionBlockedReason(detail.actions, 'UPDATE_TEXT') !== null
                }
                onClick={() =>
                  void run(async () =>
                    updatePlatformDisputeAppealText(head.id, {
                      appealText: editText,
                      templateCode: head.templateCode,
                      expectedVersion: head.version,
                    }),
                  )
                }
              >
                保存申诉文本
              </Button>
              <Button
                disabled={
                  busy || actionBlockedReason(detail.actions, 'SUBMIT') !== null
                }
                onClick={() =>
                  void run(async () =>
                    submitPlatformDisputeAppeal(head.id, {
                      submitNote: submitNote || null,
                      expectedVersion: head.version,
                    }),
                  )
                }
              >
                提交申诉
              </Button>
              <Button
                variant="outline"
                disabled={
                  busy || actionBlockedReason(detail.actions, 'PLATFORM_REPLY') !== null
                }
                onClick={() =>
                  void run(async () =>
                    recordPlatformDisputeAppealReply(head.id, {
                      platformReplyText: replyText,
                      platformReplyReference: replyReference || null,
                      result: replyResult,
                      awardedAmount: awardedAmount ? Number(awardedAmount) : null,
                      awardedCurrency: awardedAmount ? head.claimedCurrency : null,
                      resolutionNote: resolutionNote || null,
                      expectedVersion: head.version,
                    }),
                  )
                }
              >
                收回平台回复
              </Button>
              <Button
                variant="ghost"
                disabled={
                  busy || actionBlockedReason(detail.actions, 'WITHDRAW') !== null
                }
                onClick={() =>
                  void run(async () =>
                    withdrawPlatformDisputeAppeal(head.id, {
                      withdrawReason: withdrawReason || '协商后撤诉',
                      expectedVersion: head.version,
                    }),
                  )
                }
              >
                我方撤诉
              </Button>
            </div>
            <div className="text-muted-foreground text-xs">
              {gate
                ? `提交窗口：${windowText(gate.windowState, gate.windowStateLabel)}；${coverageText(
                    gate,
                  )}`
                : ''}
              {terminal ? '（已是终态：业务要素整行冻结）' : ''}
            </div>
          </div>
        </SectionCard>
      ) : null}

      {/* ---- 能力声明（上下游缺口如实显示）---- */}
      {caps ? (
        <SectionCard title="模块边界与上下游缺口">
          <ul className="text-muted-foreground list-disc space-y-1 pl-5 text-xs">
            {caps.dimensions
              .filter((d) => !d.available)
              .map((d) => (
                <li key={d.section}>
                  <span className="text-foreground font-medium">{d.label}</span>：{d.reason}
                </li>
              ))}
            {caps.boundaries.map((b) => (
              <li key={b.boundary}>
                <span className="text-foreground font-medium">{b.boundary}</span>：{b.reason}
              </li>
            ))}
          </ul>
        </SectionCard>
      ) : null}
    </div>
  );
}
