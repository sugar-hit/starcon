'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard } from '@starcon/ui';
import { Clock, History, Link2, RefreshCw, ShieldAlert, User } from 'lucide-react';
import type {
  CrmCustomerDetailTimelineEntry,
  CrmCustomerDetailView,
} from '../../../../../packages/api-contracts/src/index';
import { Field, control } from '../common/fields';
import {
  customerDetailAmountText,
  customerDetailCandidateText,
  customerDetailContactIsPlain,
  customerDetailContactText,
  customerDetailEntryText,
  customerDetailEntryTimeText,
  customerDetailIdentityText,
  customerDetailOptOutStateLabel,
  customerDetailPurchaseWindowText,
  customerDetailRefundRateText,
  customerDetailRiskFlagLabel,
  customerDetailRunText,
  customerDetailSourceLabel,
  customerDetailSourceText,
  customerDetailTimelineEmptyText,
  customerDetailUnavailableText,
  customerDetailValueTierLabel,
  fetchCustomerDetail,
  fetchCustomerTimeline,
  rebuildCustomerTimeline,
  type CustomerDetailTimelinePage,
} from './customer-detail-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 15.3.1.2 客户详情工作区。
 *
 * <p>页面按「先看清这个人是谁 → 再看各段数据有没有读到 → 最后才是时间线」的顺序排。
 * 蓝图加粗句是「**统一时间线是客服理解客户的唯一高效方式** —— 跨系统翻找会显著拖慢响应」，
 * 所以时间线是页面的主角；但**在它之前**必须先回答两件事：
 *
 * <ul>
 *   <li><b>每一段数据有没有读到</b>：读不到必须写清「缺什么、归谁」，
 *       画成「0 条」等于替上游说「这个人从来没下过单」。</li>
 *   <li><b>这个人是不是跨店合并过的</b>：合并能力归 15.3.1.3，
 *       本模块只给<strong>线索</strong>，页面绝不写成「已识别为同一人」。</li>
 * </ul>
 *
 * <p>其它纪律：
 * <ul>
 *   <li><b>联系方式默认脱敏</b>：明文要另外的权限码（crm.customers.pii.read）
 *       + 页面上的显式开关；没有权限时开关<strong>禁用</strong>（不是点了之后报 403）。</li>
 *   <li><b>金额按币种分开</b>，页面<strong>不做任何算术</strong>。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态区分「还没建立时间线」「这个来源本来就没有记录」
 *       与「建立过但这次没产出」。</li>
 * </ul>
 */
export function CustomerDetailWorkspace({ id }: { id: string }) {
  const { session } = useIdentity();
  const canUpdate = session.permissions.includes('crm.customers.update');
  const canSeePlainContact = session.permissions.includes('crm.customers.pii.read');

  const [detail, setDetail] = useState<CrmCustomerDetailView | null>(null);
  const [timeline, setTimeline] = useState<CustomerDetailTimelinePage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [building, setBuilding] = useState(false);
  const [revealContact, setRevealContact] = useState(false);
  const [sourceKind, setSourceKind] = useState('');
  const [pageIndex, setPageIndex] = useState(0);
  const pageSize = 20;

  /** 自动建立只做一次（StrictMode 下 effect 会跑两遍，这里用 ref 兜住）。 */
  const autoBuildTried = useRef(false);

  const loadDetail = useCallback(async () => {
    const view = await fetchCustomerDetail(id, {
      revealContact: revealContact || undefined,
    });
    setDetail(view);
    return view;
  }, [id, revealContact]);

  const loadTimeline = useCallback(async () => {
    const page = await fetchCustomerTimeline(id, {
      sourceKind: sourceKind || undefined,
      page: pageIndex,
      size: pageSize,
    });
    setTimeline(page);
    return page;
  }, [id, sourceKind, pageIndex, pageSize]);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      await Promise.all([loadDetail(), loadTimeline()]);
    } catch (e) {
      setError(e instanceof Error ? e.message : '客户详情读取失败，请稍后重试');
    } finally {
      setLoading(false);
    }
  }, [loadDetail, loadTimeline]);

  useEffect(() => {
    void load();
  }, [load]);

  /**
   * 首次打开且这位客户**还没有**任何一次重建记录 ⇒ 自动建立一次时间线。
   *
   * <p>🔴 只有持有 {@code crm.customers.update} 的角色才会触发（建立要落库）；
   * 没有权限时页面显示「需要 crm.customers.update 权限」，**不**偷偷发请求。
   */
  useEffect(() => {
    if (!detail || autoBuildTried.current) return;
    if (!detail.timeline?.needsBuild) return;
    if (!canUpdate) return;
    autoBuildTried.current = true;
    void (async () => {
      setBuilding(true);
      setNotice('正在建立统一时间线（从 9 订单 / 11 售后 / 15.1 消息 / 评价 / 接触记录各拉一次）…');
      try {
        const run = await rebuildCustomerTimeline(id);
        setNotice(customerDetailRunText(run));
        await load();
      } catch (e) {
        setNotice(null);
        setError(e instanceof Error ? e.message : '建立时间线失败，请稍后重试');
      } finally {
        setBuilding(false);
      }
    })();
  }, [detail, canUpdate, id, load]);

  async function build() {
    setBuilding(true);
    setNotice(null);
    setError(null);
    try {
      const run = await rebuildCustomerTimeline(id);
      setNotice(customerDetailRunText(run));
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '建立时间线失败，请稍后重试');
    } finally {
      setBuilding(false);
    }
  }

  const profile = detail?.profile;
  const sources = detail?.sources ?? [];
  const identity = detail?.identity;
  const lastRun = timeline?.lastRun ?? detail?.timeline?.lastRun ?? null;
  const needsBuild = timeline?.needsBuild ?? detail?.timeline?.needsBuild ?? false;
  const canRebuild = timeline?.canRebuild ?? detail?.timeline?.canRebuild ?? canUpdate;
  const entries = timeline?.entries ?? [];
  const empty = customerDetailTimelineEmptyText(needsBuild, canRebuild, sourceKind !== '');

  const missingSources = useMemo(() => sources.filter((source) => !source.available), [sources]);

  return (
    <div className="space-y-4">
      {error && (
        <div className="border-destructive/40 bg-destructive/10 text-destructive text-copy-13 rounded-md px-3 py-2">
          {error}
        </div>
      )}
      {notice && (
        <div className="border-primary/40 bg-primary/10 text-copy-13 rounded-md px-3 py-2">
          {notice}
        </div>
      )}

      {/* ---------------- 基本信息 ---------------- */}
      <SectionCard
        title="客户档案"
        description="基本信息、价值分层与风险标记来自 15.3.1.1 客户主档（只读）。"
      >
        {loading && !profile ? (
          <p className="text-muted-foreground text-copy-13">客户档案读取中…</p>
        ) : profile ? (
          <div className="space-y-3">
            <div className="grid gap-3 md:grid-cols-3">
              <div className="border-control-border rounded-md px-3 py-2">
                <div className="text-copy-12 text-muted-foreground">客户标识（按来源店铺）</div>
                <div className="text-copy-13 font-medium">{profile.customerRef}</div>
                <div className="text-copy-12 text-muted-foreground">
                  来源店铺：{profile.sourceShop}
                </div>
                <div className="text-copy-12 text-muted-foreground">
                  买家名：{profile.customerName || '—'}
                </div>
              </div>
              <div className="border-control-border rounded-md px-3 py-2">
                <div className="text-copy-12 text-muted-foreground">首末购买时间</div>
                <div className="text-copy-13">{customerDetailPurchaseWindowText(profile)}</div>
                <div className="text-copy-12 text-muted-foreground">
                  订单 {profile.orderCount} 单 · 退款单 {profile.refundCaseCount} 张 · 退款率{' '}
                  {customerDetailRefundRateText(profile.refundRate)}
                </div>
              </div>
              <div className="border-control-border rounded-md px-3 py-2">
                <div className="text-copy-12 text-muted-foreground">价值分层 / 风险标记</div>
                <div className="text-copy-13">
                  {customerDetailValueTierLabel(profile.valueTier)} ·{' '}
                  {customerDetailRiskFlagLabel(profile.riskFlag)}
                </div>
                <div className="text-copy-12 text-muted-foreground">
                  退订：{customerDetailOptOutStateLabel(profile.optOutState)}
                </div>
                {profile.riskReason && (
                  <div className="text-copy-12 text-muted-foreground">
                    原因：{profile.riskReason}
                  </div>
                )}
              </div>
            </div>

            <div className="border-control-border rounded-md px-3 py-2">
              <div className="text-copy-12 text-muted-foreground">累计消费（按币种分开）</div>
              <div className="text-copy-13">{customerDetailAmountText(detail?.amounts)}</div>
            </div>

            <div className="border-control-border rounded-md px-3 py-2">
              <div className="text-copy-12 text-muted-foreground">
                标签（15.3.2.1 拥有，本页只读）
              </div>
              {detail?.tags && detail.tags.length > 0 ? (
                <div className="mt-1 flex flex-wrap gap-1">
                  {detail.tags.map((tag) => (
                    <Badge key={tag.tagCode} tone="info">
                      {tag.tagName}（{tag.categoryLabel}）
                    </Badge>
                  ))}
                </div>
              ) : (
                <div className="text-copy-13">没有标签</div>
              )}
            </div>

            <div className="border-control-border rounded-md px-3 py-2">
              <div className="text-copy-13 flex items-center gap-2 font-medium">
                <ShieldAlert className="h-4 w-4" />
                联系方式口径
              </div>
              <div className="text-copy-13 mt-1">{customerDetailContactText(profile)}</div>
              {customerDetailContactIsPlain(profile) && (
                <div className="text-copy-12 text-muted-foreground">
                  🔴 本次展示的是<strong>明文</strong>联系方式。
                </div>
              )}
              <Field
                label="显示明文联系方式"
                hint={
                  canSeePlainContact
                    ? '需要额外的权限码 crm.customers.pii.read；没有它就只给脱敏形态。'
                    : '当前角色没有 crm.customers.pii.read，只能看脱敏形态。'
                }
              >
                <label className="text-copy-13 flex items-center gap-2">
                  <input
                    type="checkbox"
                    disabled={!canSeePlainContact}
                    checked={revealContact}
                    onChange={(e) => setRevealContact(e.target.checked)}
                  />
                  {revealContact ? '正在显示明文' : '默认脱敏'}
                </label>
              </Field>
            </div>
          </div>
        ) : (
          <EmptyState
            icon={<User className="h-6 w-6" />}
            title="没有读到这位客户"
            description="客户不存在，或不属于当前租户。"
          />
        )}
      </SectionCard>

      {/* ---------------- 跨店铺身份关系 ---------------- */}
      <SectionCard
        title="跨店铺身份关系"
        description="合并映射由 15.3.1.3 跨店合并拥有；本页只给线索，线索不是合并结论。"
      >
        {identity ? (
          <div className="space-y-2">
            <div className="text-copy-13 flex items-center gap-2">
              <Badge tone="warning">{identity.mergeStateLabel || '未合并'}</Badge>
              <span>{customerDetailIdentityText(identity)}</span>
            </div>
            <p className="text-muted-foreground text-copy-12">{identity.note}</p>
            {identity.candidates && identity.candidates.length > 0 ? (
              <ul className="space-y-1">
                {identity.candidates.map((candidate) => (
                  <li key={candidate.customerId} className="text-copy-13 flex items-start gap-2">
                    <Link2 className="mt-0.5 h-4 w-4 shrink-0" />
                    <span>{customerDetailCandidateText(candidate)}</span>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="text-muted-foreground text-copy-13">
                没有找到身份线索（同名或同联系方式指纹的其它客户行）。
              </p>
            )}
          </div>
        ) : (
          <p className="text-muted-foreground text-copy-13">身份关系读取中…</p>
        )}
      </SectionCard>

      {/* ---------------- 数据接入状态 ---------------- */}
      <SectionCard
        title="数据接入状态"
        description="🔴 「读不到」与「没有数据」是两件事：读不到必须写清缺什么、归谁。"
      >
        {sources.length > 0 ? (
          <div className="space-y-2">
            <ul className="space-y-1">
              {sources.map((source) => (
                <li key={source.source} className="text-copy-13 flex items-start gap-2">
                  <Badge tone={source.available ? 'success' : 'warning'}>
                    {source.available ? '可读' : '未接入'}
                  </Badge>
                  <span>{customerDetailSourceText(source)}</span>
                </li>
              ))}
            </ul>
            <p className="text-muted-foreground text-copy-12">
              {customerDetailUnavailableText(sources)}
            </p>
            {missingSources.length > 0 && (
              <p className="text-copy-12 text-muted-foreground">
                🔴 未读到的来源在时间线上<strong>不会</strong>出现条目 —— 那是「没读到」，
                不是「没有」。本模块不编数据。
              </p>
            )}
            <p className="text-muted-foreground text-copy-12">
              {lastRun ? customerDetailRunText(lastRun) : ''}
            </p>
          </div>
        ) : (
          <p className="text-muted-foreground text-copy-13">接入状态读取中…</p>
        )}
      </SectionCard>

      {/* ---------------- 统一时间线 ---------------- */}
      <SectionCard
        title="统一时间线"
        description="订单 / 售后 / 消息 / 评价 / 接触记录合并成一条 —— 这是客服理解客户的唯一高效方式。"
      >
        <div className="space-y-3">
          <div className="flex flex-wrap items-end gap-3">
            <Field label="来源">
              <select
                className={control}
                value={sourceKind}
                onChange={(e) => {
                  setSourceKind(e.target.value);
                  setPageIndex(0);
                }}
              >
                <option value="">全部</option>
                <option value="ORDER">订单</option>
                <option value="AFTERSALES">售后</option>
                <option value="MESSAGE">消息</option>
                <option value="REVIEW">评价</option>
                <option value="CONTACT">接触记录</option>
              </select>
            </Field>
            <Button
              type="button"
              variant="secondary"
              disabled={!canRebuild || building}
              onClick={() => void build()}
            >
              <RefreshCw className={building ? 'h-4 w-4 animate-spin' : 'h-4 w-4'} />
              {building ? '建立中…' : '建立 / 重建时间线'}
            </Button>
            {!canRebuild && (
              <p className="text-muted-foreground text-copy-12">
                当前角色没有 crm.customers.update，建立时间线需要这个权限。
              </p>
            )}
          </div>

          {entries.length === 0 ? (
            <EmptyState
              icon={<History className="h-6 w-6" />}
              title={empty.title}
              description={empty.description}
              action={
                canRebuild && needsBuild ? (
                  <Button type="button" disabled={building} onClick={() => void build()}>
                    <RefreshCw className={building ? 'h-4 w-4 animate-spin' : 'h-4 w-4'} />
                    建立时间线
                  </Button>
                ) : undefined
              }
            />
          ) : (
            <ul className="space-y-2">
              {entries.map((entry) => (
                <TimelineRow key={entry.id} entry={entry} />
              ))}
            </ul>
          )}

          {timeline && timeline.total > 0 && (
            <div className="flex items-center justify-between gap-3">
              <div className="text-muted-foreground text-copy-12">
                第 {timeline.page + 1} / {Math.max(timeline.totalPages, 1)} 页 · 共 {timeline.total}{' '}
                条
              </div>
              <div className="flex gap-2">
                <Button
                  type="button"
                  variant="secondary"
                  disabled={timeline.page <= 0}
                  onClick={() => setPageIndex((index) => Math.max(index - 1, 0))}
                >
                  上一页
                </Button>
                <Button
                  type="button"
                  variant="secondary"
                  disabled={timeline.page + 1 >= timeline.totalPages}
                  onClick={() => setPageIndex((index) => index + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
          )}
        </div>
      </SectionCard>
    </div>
  );
}

/** 时间线上的**一条**：来源徽标 + 发生时刻 + 标题摘要 + 金额。 */
function TimelineRow({ entry }: { entry: CrmCustomerDetailTimelineEntry }) {
  return (
    <li className="border-control-border rounded-md px-3 py-2">
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone="primary">
          {entry.sourceKindLabel || customerDetailSourceLabel(entry.sourceKind)}
        </Badge>
        <span className="text-copy-12 text-muted-foreground flex items-center gap-1">
          <Clock className="h-3 w-3" />
          {customerDetailEntryTimeText(entry)}
        </span>
        {entry.status && (
          <span className="text-copy-12 text-muted-foreground">状态：{entry.status}</span>
        )}
        {entry.amount !== null && entry.amount !== undefined && (
          <span className="text-copy-12 text-muted-foreground">
            金额：{String(entry.amount)} {entry.currency ?? ''}
          </span>
        )}
        {entry.quantity !== null && entry.quantity !== undefined && (
          <span className="text-copy-12 text-muted-foreground">数量：{String(entry.quantity)}</span>
        )}
      </div>
      <div className="text-copy-13 mt-1">{customerDetailEntryText(entry)}</div>
      <div className="text-copy-12 text-muted-foreground">
        归属模块：{entry.sourceModule || '本服务'} · 来源标识：{entry.sourceRef}
      </div>
    </li>
  );
}
