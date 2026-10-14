import type {
  CrmCloseVersionInput,
  CrmCreateEntryInput,
  CrmCreateVersionInput,
  CrmKnowledgeCapabilities,
  CrmKnowledgeEntry,
  CrmKnowledgeEntryDetail,
  CrmKnowledgeReference,
  CrmKnowledgeScope,
  CrmKnowledgeVersion,
  CrmPublishVersionInput,
  CrmRecordReferenceInput,
  CrmReviewDueReport,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 15.4.2.1 客服知识库前端模型。
 *
 * <p>蓝图：「维护客服知识库：常见问题标准答法、平台政策说明、产品使用指导、退换货规则，
 * 按店铺/类目/语言组织，支持版本与生效日期。」输出「知识库内容 + 引用记录」。
 * 关系：上游 = 3.4.2.2 文案资产；下游 = 15.1.1.2 发送、15.5.1 自动回复。
 * **知识条目必须标注适用平台与生效时间**，平台政策变更时才能快速定位过期内容。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>三个组织维度是筛选，不是装饰</b>：店铺 / 类目 / 语言各自一个筛选器，
 *       候选值来自 {@link fetchScopes} 的真实查询（页面上库里有哪几个格子），
 *       不是前端写死的清单。选满三个就是「这家店这个类目中文版当前有效的条目有哪些」。</li>
 *   <li><b>生效时间必须显形</b>：每条知识显示当前生效版本的 {@code [from, to)}；
 *       {@code to} 为空显示「一直有效」，<b>不要</b>显示成一个空白或 0。</li>
 *   <li><b>「没有生效版本」是一个要显红的状态</b>，不是「这条知识是空的」——
 *       草稿还没发布、或已发布版本都被关闭了，客服此刻拿它答复不了任何人。</li>
 *   <li><b>待复核是查出来的，不是人工翻出来的</b>：填平台 + 改版时刻，
 *       {@link fetchReviewDue} 一次列出「当前生效版写于改版之前、且至今没有接替版本」的条目。
 *       页面上必须把后端返回的判定口径原文显示出来，否则运营无法判断这个结果可不可信。</li>
 *   <li><b>引用记录只读</b>：本模块**刻意不提供**修改 / 删除引用的入口 ——
 *       删掉一条引用等于把「没人用过」这个答案做出来（后端 V99 第五节）。</li>
 *   <li>加载 / 空 / 错误三态齐全。</li>
 * </ul>
 *
 * <p><b>与 11.4.2.2「客服知识库与话术」不是同一个模块</b>：那边是 catalog 服务的
 * {@code catalog/kb}（按平台规则版本组织，页面 /aftersales/knowledge），
 * 本模块是 crm 服务的 {@code crm/knowledge}（按店铺/类目/语言组织，页面 /crm/knowledge）。
 * 两者路径、命名空间、数据模型全不相同，不要互相调用。
 */

const base = 'crm/knowledge';

/**
 * 条目列表的筛选条件。三个组织维度各自可空，但至少要能筛出「此刻有效的那些」。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事，
 * 而本文件大量用 `params.shopId || undefined` 这种写法。
 */
export interface KnowledgeEntryParams {
  shopId?: string | undefined;
  categoryId?: string | undefined;
  locale?: string | undefined;
  platformCode?: string | undefined;
  topicType?: string | undefined;
  /** 判定时刻（ISO 8601，**必须带时区偏移量**）。省略即此刻。 */
  at?: string | undefined;
  includeArchived?: boolean | undefined;
}

export function knowledgeEntryQuery(params: KnowledgeEntryParams): string {
  const parts: string[] = [];
  if (params.shopId) parts.push(`shopId=${encodeURIComponent(params.shopId)}`);
  if (params.categoryId) parts.push(`categoryId=${encodeURIComponent(params.categoryId)}`);
  if (params.locale) parts.push(`locale=${encodeURIComponent(params.locale)}`);
  if (params.platformCode) parts.push(`platformCode=${encodeURIComponent(params.platformCode)}`);
  if (params.topicType) parts.push(`topicType=${encodeURIComponent(params.topicType)}`);
  if (params.at) parts.push(`at=${encodeURIComponent(params.at)}`);
  if (params.includeArchived) parts.push('includeArchived=true');
  return parts.length ? `?${parts.join('&')}` : '';
}

export function fetchCapabilities() {
  return api<CrmKnowledgeCapabilities>(`${base}/capabilities`);
}

/** 组织范围：页面上三个下拉框的**真实**数据源（本租户库里真实存在过的格子）。 */
export function fetchScopes() {
  return api<CrmKnowledgeScope[]>(`${base}/scopes`);
}

/** 按格子取条目，每条带「此刻生效的那一版」。 */
export function fetchEntries(params: KnowledgeEntryParams = {}) {
  return api<CrmKnowledgeEntry[]>(`${base}/entries${knowledgeEntryQuery(params)}`);
}

/** 「这个格子此刻生效的条目」—— 只含有生效版本的（下游 15.1.1.2 / 15.5.1 走的就是这个）。 */
export function fetchEffectiveEntries(params: KnowledgeEntryParams = {}) {
  return api<CrmKnowledgeEntry[]>(`${base}/entries/effective${knowledgeEntryQuery(params)}`);
}

export function fetchEntry(entryId: string) {
  return api<CrmKnowledgeEntryDetail>(`${base}/entries/${encodeURIComponent(entryId)}`);
}

export function createEntry(input: CrmCreateEntryInput) {
  return api<CrmKnowledgeEntry>(`${base}/entries`, 'POST', input);
}

export function createVersion(entryId: string, input: CrmCreateVersionInput) {
  return api<CrmKnowledgeVersion>(
    `${base}/entries/${encodeURIComponent(entryId)}/versions`,
    'POST',
    input,
  );
}

/** 发布草稿版本（不可逆；区间重叠在发布那一刻被拒）。 */
export function publishVersion(versionId: string, input: CrmPublishVersionInput = { note: '' }) {
  return api<CrmKnowledgeVersion>(
    `${base}/versions/${encodeURIComponent(versionId)}/publish`,
    'POST',
    input,
  );
}

/** 向前关闭生效区间（已发布版本上唯一合法的写操作）。 */
export function closeVersion(versionId: string, input: CrmCloseVersionInput) {
  return api<CrmKnowledgeVersion>(
    `${base}/versions/${encodeURIComponent(versionId)}/close`,
    'POST',
    input,
  );
}

/** 某版本的引用记录（谁在什么时候引用了它）。只读。 */
export function fetchReferences(versionId: string) {
  return api<CrmKnowledgeReference[]>(
    `${base}/versions/${encodeURIComponent(versionId)}/references`,
  );
}

/** 某条知识全部版本的引用记录。只读。 */
export function fetchEntryReferences(entryId: string) {
  return api<CrmKnowledgeReference[]>(`${base}/entries/${encodeURIComponent(entryId)}/references`);
}

/**
 * 记录一次引用（下游 15.1.1.2 发送 / 15.5.1 自动回复调用的就是它）。
 *
 * <p>本模块自身**不引用**知识 —— 它管的是知识本身；引用发生在答复买家的时候。
 * 页面在「引用记录」页签上只提供查看，不提供新增按钮，
 * 免得运营把「谁引用过」这个审计事实当成本地操作随手改掉。
 */
export function recordReference(versionId: string, input: CrmRecordReferenceInput) {
  return api<CrmKnowledgeReference>(
    `${base}/versions/${encodeURIComponent(versionId)}/references`,
    'POST',
    input,
  );
}

export function reviewDueQuery(platformCode: string, policyChangedAt: string): string {
  const parts = [
    `platformCode=${encodeURIComponent(platformCode)}`,
    `policyChangedAt=${encodeURIComponent(policyChangedAt)}`,
  ];
  return `?${parts.join('&')}`;
}

/** 「某平台改版后哪些条目需要复核」—— 蓝图粗体句的正面回答。 */
export function fetchReviewDue(platformCode: string, policyChangedAt: string) {
  return api<CrmReviewDueReport>(
    `${base}/review-due${reviewDueQuery(platformCode, policyChangedAt)}`,
  );
}

/**
 * 生效区间的可读形式：`[2026-01-01T00:00Z, 2026-06-01T00:00Z)`。
 *
 * <p>右端**不含**（半开区间），所以用 `)`；终点为空显示「一直有效」。
 * 页面直接显示这个串，是为了让「换版那一刻从哪一版切到哪一版」看得见 ——
 * 只显示一个日期会让人以为在终点那一刻两版都生效。
 */
export function formatInterval(from: string | null, to: string | null): string {
  if (!from) return '未设置生效起点';
  return to ? `[${from}, ${to})` : `[${from}, +∞)`;
}

/** 四类知识的可读名（与后端 DB 词表一一对应）。 */
export function topicTypeLabel(topicType: string): string {
  switch (topicType) {
    case 'FAQ':
      return '常见问题标准答法';
    case 'PLATFORM_POLICY':
      return '平台政策说明';
    case 'PRODUCT_USAGE':
      return '产品使用指导';
    case 'RETURN_POLICY':
      return '退换货规则';
    default:
      return topicType;
  }
}

/** 两个消费方（下游 15.1.1.2 / 15.5.1；两者当前都未开发）。 */
export function consumerLabel(consumer: string): string {
  switch (consumer) {
    case 'MANUAL_REPLY':
      return '人工发送（15.1.1.2）';
    case 'AUTO_REPLY':
      return '自动回复（15.5.1.1）';
    default:
      return consumer;
  }
}
