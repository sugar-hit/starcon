import type {
  CrmCustomerDetailAmount,
  CrmCustomerDetailIdentity,
  CrmCustomerDetailProfile,
  CrmCustomerDetailSourceState,
  CrmCustomerDetailTimelineEntry,
  CrmCustomerDetailTimelinePage,
  CrmCustomerDetailTimelineRun,
  CrmCustomerDetailView,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 15.3.1.2 客户详情前端模型。
 *
 * <p>蓝图：「客户全档案：基本信息、跨店铺身份关系、订单历史、售后历史、消息历史、评价、
 * 标签、接触记录、风险与价值分层。输入：customer_id；聚合各域数据。输出：详情视图 +
 * 时间线（订单/消息/售后合并时间线）。关系：上游 = 15.3.1.1；下游 = 9 订单、11 售后、
 * 15.1 消息。**统一时间线是客服理解客户的唯一高效方式** —— 跨系统翻找会显著拖慢响应。」
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>「未接入」与「没有数据」是两件事</b>：某个来源读不到时，页面必须显示
 *       「读不到 / 缺什么 / 归谁」，而不是画成「0 条」—— 画成 0 条等于在说
 *       「这个人从来没下过单」，那是<strong>编数据</strong>。</li>
 *   <li><b>未合并就是未合并</b>：跨店合并由 15.3.1.3 拥有，本模块只给
 *       <strong>线索</strong>（同名 / 同联系方式指纹）。页面把线索标成「线索」，
 *       绝不写成「已识别为同一人」。</li>
 *   <li><b>联系方式默认脱敏</b>：明文要<strong>另外</strong>的权限码
 *       （{@code crm.customers.pii.read}）+ 页面上的显式开关；没有权限时开关
 *       <strong>禁用</strong>（而不是点了之后报 403）。</li>
 *   <li><b>金额按币种分开</b>、<b>前端不做任何算术</b>（比率、客单价都由后端算好）。</li>
 *   <li><b>「不可判定」是一等状态</b>：退款率分母为 0 时画成「不可判定」而不是「0%」。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态要区分「还没建立时间线」与「这段来源本来就没有记录」。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code crm/customers/{id}} —— 与 15.3.1.1 客户列表
 * （{@code crm/customers}）是<strong>同一业务对象的两个面</strong>，子路径互不重叠。
 */

const base = 'crm/customers';

// ================================================================ 可读名

/** 时间线来源的可读名。 */
export function customerDetailSourceLabel(kind: string | undefined): string {
  switch (kind) {
    case 'ORDER':
      return '订单';
    case 'AFTERSALES':
      return '售后';
    case 'MESSAGE':
      return '消息';
    case 'REVIEW':
      return '评价';
    case 'CONTACT':
      return '接触记录';
    default:
      return kind ?? '';
  }
}

/** 价值分层的可读名（后端也给了 label，这份是**兜底**）。 */
export function customerDetailValueTierLabel(code: string | undefined): string {
  switch (code) {
    case 'VIP':
      return '高价值';
    case 'HIGH':
      return '较高价值';
    case 'NORMAL':
      return '常规';
    case 'LOW':
      return '无有效消费';
    default:
      return code ?? '';
  }
}

/**
 * 风险标记的可读名。
 *
 * <p>🔴 `INSUFFICIENT_SAMPLE` 与 `NONE` **必须是两句话**：
 * 「样本不足」画成「无风险标记」，会让人以为「这个人没问题」——
 * 而他其实**没被判过**。
 */
export function customerDetailRiskFlagLabel(code: string | undefined): string {
  switch (code) {
    case 'NONE':
      return '无风险标记';
    case 'HIGH_RETURN_RATE':
      return '退款率显著高于租户基线';
    case 'OPT_OUT':
      return '已登记生效中的退订';
    case 'HIGH_RETURN_RATE_AND_OPT_OUT':
      return '退款率偏高且已退订';
    case 'INSUFFICIENT_SAMPLE':
      return '样本不足，不可判定';
    default:
      return code ?? '';
  }
}

/** 退订状态的可读名。 */
export function customerDetailOptOutStateLabel(code: string | undefined): string {
  switch (code) {
    case 'NONE':
      return '无退订记录';
    case 'ACTIVE':
      return '退订生效中';
    case 'REVOKED':
      return '退订已撤销';
    default:
      return code ?? '';
  }
}

/**
 * 退款率的显示文本。
 *
 * <p>🔴 分母为 0（后端给 null）⇒「不可判定」，**不是**「0%」。
 */
export function customerDetailRefundRateText(rate: string | number | null | undefined): string {
  if (rate === null || rate === undefined || rate === '') return '不可判定';
  return String(rate);
}

/**
 * 金额按币种分行的文本（**一行一个币种**，不做任何前端算术）。
 *
 * <p>「累计消费 USD 315.60 / EUR 12.00」比「327.60」诚实 ——
 * USD 与 EUR 加起来不是任何一个金额。
 */
export function customerDetailAmountText(amounts: CrmCustomerDetailAmount[] | undefined): string {
  if (!amounts || amounts.length === 0) return '无消费记录';
  return amounts
    .map((amount) => {
      const total = String(amount.totalAmount);
      const avg = amount.avgOrderAmount === null ? '—' : String(amount.avgOrderAmount);
      return `${amount.currency} ${total}（客单价 ${avg}）`;
    })
    .join(' / ');
}

/** 联系方式的显示文本（**默认脱敏**，页面只原样显示后端给的值）。 */
export function customerDetailContactText(profile: CrmCustomerDetailProfile | undefined): string {
  if (!profile) return '—';
  const email = profile.contactEmail || '—';
  const phone = profile.contactPhone || '—';
  return `${email} / ${phone}`;
}

/** 联系方式是明文还是脱敏（页面据此提示「本次已展示明文」）。 */
export function customerDetailContactIsPlain(
  profile: CrmCustomerDetailProfile | undefined,
): boolean {
  return profile?.contactMasked === false;
}

/** 首末购买时间的文本。 */
export function customerDetailPurchaseWindowText(
  profile: CrmCustomerDetailProfile | undefined,
): string {
  if (!profile) return '—';
  const first = profile.firstOrderAt ? profile.firstOrderAt.slice(0, 10) : '—';
  const last = profile.lastOrderAt ? profile.lastOrderAt.slice(0, 10) : '—';
  return `${first} ~ ${last}`;
}

// ================================================================ 跨店铺身份关系

/**
 * 跨店铺身份关系段的一句话说明。
 *
 * <p>🔴 页面**必须**说清「线索 ≠ 合并」：合并映射由 15.3.1.3 拥有，
 * 本模块不判定、不自动合并。
 */
export function customerDetailIdentityText(
  identity: CrmCustomerDetailIdentity | undefined,
): string {
  if (!identity) return '';
  const candidates = identity.candidates?.length ?? 0;
  return (
    `${identity.mergeStateLabel ?? '未合并'} · 合并能力由 ${identity.ownerModule ?? '15.3.1.3'} 负责` +
    ` · 身份线索 ${candidates} 条（线索不是合并结论）`
  );
}

/** 一条身份线索的显示文本（**永远带「线索」二字**）。 */
export function customerDetailCandidateText(
  candidate: CrmCustomerDetailIdentity['candidates'][number],
): string {
  return `${candidate.sourceShop} / ${candidate.customerRef} · ${candidate.customerName || '（无名）'} · 线索：${candidate.clue}`;
}

// ================================================================ 来源接入状态

/**
 * 逐段接入状态的显示文本。
 *
 * <p>🔴 `available=false` 时**必须**给出原因；没有原因就等于让用户以为
 * 「这个客户就是没有订单」，而真相可能是「上游读不到」。
 */
export function customerDetailSourceText(source: CrmCustomerDetailSourceState): string {
  const count = source.available
    ? `${source.count} 条`
    : `不可用（${source.reason || '原因未说明'}）`;
  return `${source.sourceLabel}：${count} · 归属 ${source.ownerModule || '本服务'}`;
}

/** 读不到的来源的汇总说明（**空数组 ⇒ 一句话说明「全部读到了」**）。 */
export function customerDetailUnavailableText(sources: CrmCustomerDetailSourceState[]): string {
  const missing = (sources ?? []).filter((source) => !source.available);
  if (missing.length === 0) return '五个来源全部可读。';
  return missing
    .map((source) => `${source.sourceLabel}：${source.reason || '原因未说明'}`)
    .join('；');
}

// ================================================================ 时间线

/** 一条时间线的显示文本（标题 + 摘要）。 */
export function customerDetailEntryText(entry: CrmCustomerDetailTimelineEntry): string {
  const title = entry.title || customerDetailSourceLabel(entry.sourceKind);
  return entry.summary ? `${title} —— ${entry.summary}` : title;
}

/**
 * 一条时间线的发生时刻文本。
 *
 * <p>🔴 时刻来自上游 / 事实本身（`occurredAt`），**不是**入库时刻；
 * 页面不拿入库时刻冒充发生时刻。
 */
export function customerDetailEntryTimeText(entry: CrmCustomerDetailTimelineEntry): string {
  return entry.occurredAt ? entry.occurredAt.slice(0, 19).replace('T', ' ') : '—';
}

/**
 * 时间线空态文案：**区分**「还没建立」与「这段来源本来就没有记录」。
 *
 * @param needsBuild 这位客户**还没有**任何一次重建记录
 * @param canRebuild 当前角色能不能建立/重建（需要 crm.customers.update）
 * @param filtered 是否正在按来源筛选
 */
export function customerDetailTimelineEmptyText(
  needsBuild: boolean,
  canRebuild: boolean,
  filtered: boolean,
): { title: string; description: string } {
  if (needsBuild) {
    return {
      title: '时间线还没有建立',
      description: canRebuild
        ? '统一时间线需要从 9 订单 / 11 售后 / 15.1 消息 / 评价 / 接触记录五个来源各拉一次。点「建立 / 重建时间线」。'
        : '统一时间线需要从上游各拉一次。**当前角色没有 crm.customers.update 权限**，建立不了 —— 请联系管理员。',
    };
  }
  if (filtered) {
    return {
      title: '这个来源没有记录',
      description: '当前筛选的来源下没有条目。换个来源看看 —— 这不代表这位客户没有其它记录。',
    };
  }
  return {
    title: '这位客户还没有任何时间线条目',
    description:
      '上一次重建没有产出条目：五个来源里要么本来就没有记录，要么读取失败（见上方「数据接入状态」）。',
  };
}

/**
 * 一次重建的结果说明。
 *
 * <p>🔴 `PARTIAL` 与 `SUCCEEDED` **必须是两句话**：
 * 「部分来源读不到」画成「完成」，等于把「没读到」藏起来。
 */
export function customerDetailRunText(run: CrmCustomerDetailTimelineRun | undefined): string {
  if (!run) return '还没有建立过时间线。';
  const head =
    run.status === 'SUCCEEDED'
      ? '五个来源全部读到了'
      : run.status === 'PARTIAL'
        ? '部分来源没读到'
        : '一条都没落';
  const unavailable = Object.entries(run.unavailableSources ?? {})
    .map(([kind, reason]) => `${customerDetailSourceLabel(kind)}：${reason}`)
    .join('；');
  return (
    `${head} · 共 ${run.entryCount} 条` +
    (run.truncated ? ' · 🔴 本次扫描被截断，时间线不是全量' : '') +
    (unavailable ? ` · ${unavailable}` : '') +
    (run.failureReason ? ` · ${run.failureReason}` : '')
  );
}

// ================================================================ 请求

export interface CustomerDetailParams {
  revealContact?: boolean | undefined;
}

export interface CustomerTimelineParams {
  sourceKind?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/**
 * 时间线页的**前端可空口径**。
 *
 * <p>🔴 后端的 `lastRun` 是**可空**的（这位客户还没建立过时间线时没有运行记录），
 * 但 SpringDoc **不会**给 `$ref` 类型的属性标 `nullable`（注解在 `$ref` 上被忽略），
 * 于是 codegen 产出的类型把 `lastRun` 写成了必填。
 *
 * <p>本模块**不改共享的 codegen**（那会牵动全部九个服务与并行车道），
 * 也不**手改契约**，而是在前端把这一处口径收窄 —— 契约文件仍然是 codegen 产物。
 */
export type CustomerDetailTimelinePage = Omit<CrmCustomerDetailTimelinePage, 'lastRun'> & {
  lastRun?: CrmCustomerDetailTimelineRun | null;
};

/** 客户详情视图。 */
export function fetchCustomerDetail(id: string, params: CustomerDetailParams = {}) {
  return api<CrmCustomerDetailView>(`${base}/${id}${query({ ...params })}`);
}

/** 统一时间线（真分页 + 按来源筛选）。 */
export function fetchCustomerTimeline(id: string, params: CustomerTimelineParams = {}) {
  return api<CustomerDetailTimelinePage>(`${base}/${id}/timeline${query({ ...params })}`);
}

/** 重建统一时间线（幂等）。 */
export function rebuildCustomerTimeline(id: string) {
  return api<CrmCustomerDetailTimelineRun>(`${base}/${id}/timeline/rebuild`, 'POST');
}

/** 拼查询串（**空值一律不拼** —— 拼一个空的 sourceKind= 会把筛选悄悄变成「来源为空」）。 */
function query(params: Record<string, unknown>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}
