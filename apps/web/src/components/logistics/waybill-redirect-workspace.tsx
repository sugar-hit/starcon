'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { BellRing, MapPin, RefreshCw, Search, XCircle } from 'lucide-react';
import {
  Button,
  EmptyState,
  FormField,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import type {
  BuyerNotificationTaskPage,
  CarrierRedirectRulePage,
  WaybillRedirectCapabilities,
  WaybillRedirectPage,
} from '../../../../../packages/api-contracts/src/index';
import {
  deadlineText,
  dispatchStateText,
  errorText,
  fetchCapabilities,
  fetchNotifications,
  fetchRedirects,
  fetchRules,
  redirectSummaryLine,
  timeText,
  verdictSentence,
  verdictTone,
  type WaybillRedirectTone,
} from './waybill-redirect-model';

const PAGE_SIZE = 20;

const TONE_CLASS: Record<WaybillRedirectTone, string> = {
  ok: 'text-emerald-600 dark:text-emerald-400',
  warn: 'text-amber-600 dark:text-amber-400',
  bad: 'text-red-600 dark:text-red-400',
  muted: 'text-muted-foreground',
  wait: 'text-sky-600 dark:text-sky-400',
};

function SelectField(props: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  options: { value: string; label: string }[];
  hint?: string;
}) {
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={props.id} className="text-sm font-medium">
        {props.label}
      </label>
      <select
        id={props.id}
        className="border-input h-9 rounded-md border bg-transparent px-2 text-sm"
        value={props.value}
        onChange={(e) => props.onChange(e.target.value)}
      >
        {props.options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
      {props.hint ? <p className="text-muted-foreground text-xs">{props.hint}</p> : null}
    </div>
  );
}

const VERDICT_OPTIONS = [
  { value: '', label: '全部判定' },
  { value: 'WITHIN_WINDOW', label: '在时限内' },
  { value: 'DEADLINE_PASSED', label: '已超时限' },
  { value: 'CHANNEL_UNSUPPORTED', label: '渠道不支持' },
  { value: 'RULE_UNREGISTERED', label: '规则未登记' },
];

const OUTCOME_OPTIONS = [
  { value: '', label: '全部结果' },
  { value: 'NOT_ATTEMPTED', label: '未派发' },
  { value: 'ACCEPTED', label: '已受理' },
  { value: 'REJECTED', label: '已拒收' },
];

const SORT_OPTIONS = [
  { value: '', label: '最新发起在前' },
  { value: 'REQUESTED_AT_ASC', label: '最早发起在前' },
  { value: 'DEADLINE_AT_ASC', label: '最快到期在前' },
  { value: 'VERDICT_ASC', label: '按判定排序' },
  { value: 'CARRIER_CODE_ASC', label: '按承运商排序' },
];

/**
 * 10.2.1.3 改址与取消工作区。
 *
 * <p>蓝图：「处理运单层面的改址（部分渠道支持且有时限）与取消，联动订单地址变更与
 * 平台通知。」加粗句要求**超时的改址请求要明确告知能否成功，而不是让客服猜**。
 *
 * <p>区块顺序就是客服的判断顺序：
 * <b>① 能力与缺口 → ② 渠道规则（时限从哪来）→ ③ 改址清单（逐条一句准话）
 * → ④ 买家通知任务</b>。
 *
 * <p>把「渠道规则」放在清单之前是有意的：清单上每一条「已超时限」都源自某承运商
 * 登记的窗口小时数，运营第一反应会是「时限是谁定的」。先让它看见那张表，
 * 再看清单，才不会把「时限太短」当成系统出错。
 *
 * <p>路由取 {@code /logistics/address-change} —— 与 10.2.1.1 的
 * {@code /logistics/shipments}（看有哪些运单）和 10.2.1.2 的
 * {@code /logistics/waybills}（去申请单号）三条互不为前缀。
 */
export function WaybillRedirectWorkspace() {
  const [capabilities, setCapabilities] = useState<WaybillRedirectCapabilities | null>(null);
  const [rules, setRules] = useState<CarrierRedirectRulePage | null>(null);
  const [page, setPage] = useState<WaybillRedirectPage | null>(null);
  const [notifications, setNotifications] = useState<BuyerNotificationTaskPage | null>(null);

  const [carrierCode, setCarrierCode] = useState('');
  const [verdict, setVerdict] = useState('');
  const [outcome, setOutcome] = useState('');
  const [keyword, setKeyword] = useState('');
  const [sort, setSort] = useState('');
  const [pageIndex, setPageIndex] = useState(0);

  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError('');
    try {
      const [caps, rulePage, list, tasks] = await Promise.all([
        fetchCapabilities(),
        fetchRules(carrierCode || undefined),
        fetchRedirects({
          carrierCode,
          verdict,
          outcome,
          keyword,
          sort,
          page: pageIndex,
          size: PAGE_SIZE,
        }),
        fetchNotifications({ salesOrderNo: '', page: 0, size: 10 }),
      ]);
      setCapabilities(caps);
      setRules(rulePage);
      setPage(list);
      setNotifications(tasks);
    } catch (error) {
      setLoadError(errorText(error));
    } finally {
      setLoading(false);
    }
  }, [carrierCode, verdict, outcome, keyword, sort, pageIndex]);

  useEffect(() => {
    void load();
  }, [load]);

  const gaps = useMemo(
    () => (capabilities ? capabilities.dimensions.filter((d) => !d.available) : []),
    [capabilities],
  );

  const totalPages = page ? Math.max(1, Math.ceil(page.total / PAGE_SIZE)) : 1;

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="改址与取消"
        description="对已出号的运单发起改址或取消，并按渠道登记的时限明确告知能否成功。"
      />

      {/* ① 能力与缺口 */}
      <SectionCard title="能力与缺口" description="哪些维度真能用、哪些没有上游">
        {loading && !capabilities ? (
          <p className="text-muted-foreground text-sm">加载中…</p>
        ) : (
          <>
            <div className="flex flex-col gap-2">
              {gaps.map((d) => (
                <Notice key={d.dimension} tone="warning" title={d.description}>
                  {d.source}
                  <br />
                  归 <code>{d.ownerModule}</code>；{d.impact}
                </Notice>
              ))}
              {gaps.length === 0 && <p className="text-sm">当前没有缺口。</p>}
            </div>
            {capabilities ? (
              <p className="text-muted-foreground text-xs">
                判定口径：{capabilities.verdictRule}
                <br />
                时限口径：{capabilities.deadlineRule}
              </p>
            ) : null}
          </>
        )}
      </SectionCard>

      {/* ② 渠道规则 */}
      <SectionCard
        title="渠道改址与取消规则"
        description="清单上的每个「已超时限」都来自这张表 —— 时限不是代码里的常数，是租户可维护的数据"
      >
        {loading && !rules ? (
          <p className="text-muted-foreground text-sm">加载中…</p>
        ) : rules && rules.items.length > 0 ? (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b">
                  <th className="p-2 text-left">承运商</th>
                  <th className="p-2 text-left">渠道</th>
                  <th className="p-2 text-left">改址</th>
                  <th className="p-2 text-left">取消</th>
                  <th className="p-2 text-left">备注</th>
                </tr>
              </thead>
              <tbody>
                {rules.items.map((r) => (
                  <tr key={r.id} className="border-b last:border-0">
                    <td className="p-2 font-medium">{r.carrierCode}</td>
                    <td className="text-muted-foreground p-2">{r.carrierChannel || '—'}</td>
                    <td className="p-2">
                      {r.redirectSupported ? (
                        <span className="text-emerald-600 dark:text-emerald-400">
                          支持，{r.redirectDeadlineHours} 小时
                        </span>
                      ) : (
                        <span className="text-muted-foreground">不支持</span>
                      )}
                    </td>
                    <td className="p-2">
                      {r.cancelSupported ? (
                        <span className="text-emerald-600 dark:text-emerald-400">
                          支持，{r.cancelDeadlineHours} 小时
                        </span>
                      ) : (
                        <span className="text-muted-foreground">不支持</span>
                      )}
                    </td>
                    <td className="text-muted-foreground p-2">{r.unsupportedReason || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <EmptyState
            title="还没有登记任何承运商的改址规则"
            description="未登记规则时本系统**不知道**该渠道支持不支持，因此不会受理改址。请先到「运单申请 · 承运商」登记承运商，再登记规则。"
          />
        )}
      </SectionCard>

      {/* ③ 改址清单 */}
      <SectionCard
        title="改址单"
        description="每条给一句能照着答复买家的准话：能不能成、为什么"
        extra={
          <Button variant="outline" onClick={() => void load()} disabled={loading}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
        }
      >
        <div className="grid gap-3 md:grid-cols-3 lg:grid-cols-5">
          <SelectField
            id="redirect-carrier"
            label="承运商"
            value={carrierCode}
            onChange={(v) => {
              setCarrierCode(v);
              setPageIndex(0);
            }}
            options={[
              { value: '', label: '全部承运商' },
              ...(rules?.items ?? []).map((r) => ({ value: r.carrierCode, label: r.carrierCode })),
            ]}
          />
          <SelectField
            id="redirect-verdict"
            label="时限判定"
            value={verdict}
            onChange={(v) => {
              setVerdict(v);
              setPageIndex(0);
            }}
            options={VERDICT_OPTIONS}
          />
          <SelectField
            id="redirect-outcome"
            label="渠道结果"
            value={outcome}
            onChange={(v) => {
              setOutcome(v);
              setPageIndex(0);
            }}
            options={OUTCOME_OPTIONS}
          />
          <SelectField
            id="redirect-sort"
            label="排序"
            value={sort}
            onChange={(v) => {
              setSort(v);
              setPageIndex(0);
            }}
            options={SORT_OPTIONS}
          />
          <FormField id="redirect-keyword" label="关键词">
            <div className="flex gap-2">
              <Input
                id="redirect-keyword"
                value={keyword}
                placeholder="单号 / 运单号 / 收件人"
                onChange={(e) => {
                  setKeyword(e.target.value);
                  setPageIndex(0);
                }}
              />
              <Button variant="outline" onClick={() => void load()} disabled={loading}>
                <Search className="h-4 w-4" />
              </Button>
            </div>
          </FormField>
        </div>

        {loadError ? (
          <Notice tone="danger" title="加载失败">
            {loadError}
          </Notice>
        ) : null}

        {loading && !page ? (
          <p className="text-muted-foreground text-sm">加载中…</p>
        ) : page && page.items.length > 0 ? (
          <>
            <div className="grid gap-3 md:grid-cols-4">
              <StatCard label="筛选结果" value={page.total} polarity="neutral" />
              <StatCard label="已超时限" value={page.deadlinePassed} polarity="negative" />
              <StatCard label="此刻能改" value={page.canSucceedCount} polarity="positive" />
              <StatCard label="未派发" value={page.notAttemptedCount} polarity="neutral" />
            </div>

            <div className="mt-4 overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b">
                    <th className="p-2 text-left">改址单</th>
                    <th className="p-2 text-left">运单 / 承运商</th>
                    <th className="p-2 text-left">能否成功（照着答复买家）</th>
                    <th className="p-2 text-left">截止</th>
                    <th className="p-2 text-left">渠道结果</th>
                    <th className="p-2 text-left">发起</th>
                  </tr>
                </thead>
                <tbody>
                  {page.items.map((row) => (
                    <tr key={row.id} className="border-b last:border-0 align-top">
                      <td className="p-2 font-medium">{row.redirectNo}</td>
                      <td className="p-2">
                        <div>{row.waybillNo}</div>
                        <div className="text-muted-foreground text-xs">
                          {row.carrierCode} · {row.salesOrderNo || '—'}
                        </div>
                        <div className="text-muted-foreground text-xs">
                          新收件人：{row.recipientName}（{row.recipientCountry}）
                        </div>
                      </td>
                      <td className="p-2">
                        <div
                          className={`font-medium ${TONE_CLASS[verdictTone({ verdict: row.verdict, canSucceed: row.canSucceed, outcome: row.outcome })]}`}
                        >
                          {verdictSentence({
                            verdict: row.verdict,
                            verdictLabel: row.verdictLabel,
                            canSucceed: row.canSucceed,
                            canSucceedReason: row.canSucceedReason,
                          })}
                        </div>
                        <div className="text-muted-foreground mt-1 text-xs">{row.nextAction}</div>
                        <div className="text-muted-foreground text-xs">{row.orderChangeNote}</div>
                      </td>
                      <td className="p-2">
                        {deadlineText(row.minutesToDeadline)}
                        <div className="text-muted-foreground text-xs">
                          {timeText(row.deadlineAt)}
                        </div>
                      </td>
                      <td className="p-2">{dispatchStateText(row)}</td>
                      <td className="text-muted-foreground p-2">{timeText(row.requestedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <div className="text-muted-foreground mt-3 flex items-center justify-between text-xs">
              <span>{redirectSummaryLine(page)}</span>
              <span className="flex items-center gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={pageIndex === 0}
                  onClick={() => setPageIndex((p) => Math.max(0, p - 1))}
                >
                  上一页
                </Button>
                <span>
                  第 {pageIndex + 1} / {totalPages} 页
                </span>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={pageIndex + 1 >= totalPages}
                  onClick={() => setPageIndex((p) => p + 1)}
                >
                  下一页
                </Button>
              </span>
            </div>

            <p className="text-muted-foreground mt-2 text-xs">{page.notes.join(' ')}</p>
          </>
        ) : (
          <EmptyState
            title="没有符合条件的改址单"
            description="改址单在「运单申请」里对已出号的运单发起改址时生成；本清单只读，不会自己造单。"
          />
        )}
      </SectionCard>

      {/* ④ 买家通知任务 */}
      <SectionCard
        title="买家通知任务"
        description="本模块只建任务、**不发送** —— 15 客户与客服未交付，发送与送达状态归 15"
      >
        <Notice tone="info" title="这里没有「发送 / 标记已送达」按钮">
          <BellRing className="mr-1 inline h-4 w-4" />
          买家通知由 15 客户与客服发送与回写状态（跨服务，不直连对方库）。
          在这里给一个假的「已送达」，客服会以为买家已经收到而不再跟进 ——
          那比没有通知任务更糟。
        </Notice>

        {notifications && notifications.items.length > 0 ? (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b">
                  <th className="p-2 text-left">通知单</th>
                  <th className="p-2 text-left">类型</th>
                  <th className="p-2 text-left">运单</th>
                  <th className="p-2 text-left">措辞要点</th>
                  <th className="p-2 text-left">状态 / 归属</th>
                </tr>
              </thead>
              <tbody>
                {notifications.items.map((t) => (
                  <tr key={t.id} className="border-b last:border-0 align-top">
                    <td className="p-2 font-medium">{t.notificationNo}</td>
                    <td className="p-2">{t.notificationKindLabel}</td>
                    <td className="p-2">
                      <div>{t.waybillNo}</div>
                      <div className="text-muted-foreground text-xs">{t.carrierCode}</div>
                    </td>
                    <td className="text-muted-foreground p-2 text-xs">{t.bodySummary}</td>
                    <td className="p-2 text-xs">
                      <span className="text-amber-600 dark:text-amber-400">{t.status}</span>
                      <span className="text-muted-foreground"> · 模块 {t.ownerModule}</span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <EmptyState
            title="还没有待发的买家通知"
            description="改址 / 取消产生「需要买家知道的结论」时才会建任务；还在等承运商回话的不建 —— 那时还没有结论可通知。"
          />
        )}

        {notifications ? (
          <p className="text-muted-foreground text-xs">{notifications.notes.join(' ')}</p>
        ) : null}
      </SectionCard>

      {/* 边界说明 */}
      <SectionCard title="本模块做什么、不做什么" description="避免把没数据源的东西当成正常">
        <ul className="text-muted-foreground flex list-disc flex-col gap-1 pl-5 text-xs">
          <li>
            <MapPin className="mr-1 inline h-3 w-3" />
            只对<b>已出号（ISSUED）</b>的运单生效；还没出号的作废请到 10.2.1.2「运单申请」。
          </li>
          <li>
            <XCircle className="mr-1 inline h-3 w-3" />
            运单号与出号时刻由 10.2.1.2 拥有，本模块只读；改址 / 取消单一旦写下判定即不可改写。
          </li>
          <li>「已受理」只可能来自承运商真实回话；渠道未接通时本模块不受理。</li>
          <li>承运商侧是否已揽收没有数据源（10.3 未交付），时限内判成「可改」仍可能被拒收。</li>
        </ul>
      </SectionCard>
    </div>
  );
}
