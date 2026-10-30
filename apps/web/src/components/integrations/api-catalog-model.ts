import type {
  ApiCatalogCapabilities,
  ApiCatalogDomain,
  ApiCatalogInterface,
  ApiCatalogInterfaceDetail,
  ApiCatalogInterfacePage,
  ApiCatalogOverview,
  ApiCatalogRateLimit,
  ApiCatalogSandboxResult,
  ApiCatalogSandboxRunPage,
  ApiCatalogSandboxTarget,
  ApiCatalogServiceStatus,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 18.1.1.2「接口目录」前端模型。
 *
 * 蓝图：「<strong>职责</strong>：对外开放 API 的目录与文档：按业务域列出接口、参数、返回、限流说明、示例；
 * 支持在线测试（沙箱环境）。<strong>输入</strong>：接口定义（OpenAPI 生成物）；测试请求。
 * <strong>输出</strong>：接口文档 + 测试结果。<strong>关系</strong>：上游 = 后端 OpenAPI spec（单一真相源）；
 * 下游 = 外部系统。<strong>接口契约由后端 spec 生成，文档不手写</strong>——手写文档必然与实现漂移。」
 *
 * 🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * 1. **「读不到」不能显示成「没有」**。某个服务的 spec 读不出来时，页面要显式摆出
 *    「这个服务的接口读不到（原因）」—— 把它混进空态，等于告诉接入方「它没有对外接口」。
 * 2. **「按 schema 生成的示例」不能冒充官方示例**。示例出处（`exampleSource`）必须显示出来：
 *    spec 自带 ≠ 按 schema 生成。
 * 3. **限流是按应用判定的，不是按接口**。页面要说「哪个应用、哪个窗口、限多少」，
 *    而不是给接口编一个「每分钟 100 次」。
 * 4. **沙箱写请求被拒要说清为什么**。本仓没有独立沙箱部署，写请求会打到真实业务表 ——
 *    页面如实显示 409 与原因，并指路「用真实凭证对真实环境调用」。
 *
 * <p><b>类型全部从契约派生</b>（`packages/api-contracts` 的 codegen 产物），前端不手抄字符串联合。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，不是并进既有分支）。 */
const base = 'platform/api-catalog';

/**
 * 拼查询串：空值不拼（拼 `undefined=` 上游会当成「筛选了这个值」）。
 */
function query(params: Record<string, string | number | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, String(value));
  }
  return search.toString();
}

// ================================================================ 读取

/** 能力声明：哪些真接通、哪些还没交付、归谁。 */
export function fetchCapabilities(): Promise<ApiCatalogCapabilities> {
  return api<ApiCatalogCapabilities>(`${base}/capabilities`);
}

/** 概览：几个数。 */
export function fetchOverview(): Promise<ApiCatalogOverview> {
  return api<ApiCatalogOverview>(`${base}/overview`);
}

/** 业务域清单（按对外路径首段分组）。 */
export function fetchDomains(): Promise<ApiCatalogDomain[]> {
  return api<ApiCatalogDomain[]>(`${base}/domains`);
}

/** 接口清单（真分页 + 真筛选 + 真搜索）。 */
export function fetchInterfaces(params: {
  domain?: string | undefined;
  service?: string | undefined;
  keyword?: string | undefined;
  method?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}): Promise<ApiCatalogInterfacePage> {
  return api<ApiCatalogInterfacePage>(`${base}/interfaces?${query(params)}`);
}

/** 一个接口的详情：参数 / 返回 / 限流说明 / 示例。 */
export function fetchInterfaceDetail(
  service: string,
  method: string,
  path: string,
): Promise<ApiCatalogInterfaceDetail> {
  const search = new URLSearchParams();
  search.set('service', service);
  search.set('method', method);
  search.set('path', path);
  return api<ApiCatalogInterfaceDetail>(`${base}/interfaces/detail?${search.toString()}`);
}

/** 可测试的目标服务清单。 */
export function fetchSandboxTargets(): Promise<ApiCatalogSandboxTarget[]> {
  return api<ApiCatalogSandboxTarget[]>(`${base}/sandbox/targets`);
}

/** 沙箱测试历史（真分页）。 */
export function fetchSandboxRuns(params: {
  service?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}): Promise<ApiCatalogSandboxRunPage> {
  return api<ApiCatalogSandboxRunPage>(`${base}/sandbox/runs?${query(params)}`);
}

/**
 * 在沙箱里真发一次请求。
 *
 * 🔴 后端对「没发出去」的几种结论会回**真实状态码**（409 / 400 / 503），
 * 页面据此显示，**不**一律当成失败 —— 「被拒」与「跑挂了」是两件事。
 */
export function runSandbox(input: {
  targetService: string;
  httpMethod: string;
  targetPath: string;
  query?: Record<string, string> | undefined;
  body?: string | undefined;
}): Promise<ApiCatalogSandboxResult> {
  return api<ApiCatalogSandboxResult>(`${base}/sandbox/runs`, 'POST', input);
}

// ================================================================ 词表

/** 语气档位。 */
export type Tone = 'ok' | 'warn' | 'danger' | 'muted';

/** HTTP 方法的显示口径。 */
export function methodLabel(method: string): string {
  switch (method) {
    case 'GET':
      return '读';
    case 'POST':
      return '新建';
    case 'PUT':
    case 'PATCH':
      return '改';
    case 'DELETE':
      return '删';
    default:
      return method || '—';
  }
}

/** HTTP 方法的语气：读是中性色，写是黄，删是红。 */
export function methodTone(method: string): Tone {
  switch (method) {
    case 'GET':
    case 'HEAD':
      return 'muted';
    case 'DELETE':
      return 'danger';
    case 'POST':
    case 'PUT':
    case 'PATCH':
      return 'warn';
    default:
      return 'muted';
  }
}

/** 响应状态码的语气。 */
export function statusTone(statusCode: number | null | undefined): Tone {
  if (statusCode === null || statusCode === undefined) return 'muted';
  if (statusCode >= 200 && statusCode < 300) return 'ok';
  if (statusCode >= 400 && statusCode < 500) return 'warn';
  if (statusCode >= 500) return 'danger';
  return 'muted';
}

/** 示例出处的显示口径。 */
export function exampleSourceLabel(source: string): string {
  switch (source) {
    case 'SPEC':
      return 'spec 自带';
    case 'GENERATED_FROM_SCHEMA':
      return '按 schema 生成（非官方示例）';
    case 'NONE':
      return '无示例';
    default:
      return source || '无示例';
  }
}

/**
 * 示例出处的语气。
 *
 * 🔴 「按 schema 生成」是**黄的** —— 它**不是**契约的一部分，
 * 显示成跟「spec 自带」一样绿，接入方就会把它当成官方保证。
 */
export function exampleSourceTone(source: string): Tone {
  switch (source) {
    case 'SPEC':
      return 'ok';
    case 'GENERATED_FROM_SCHEMA':
      return 'warn';
    default:
      return 'muted';
  }
}

/** 沙箱一次尝试的结论。 */
export function outcomeLabel(outcome: string): string {
  switch (outcome) {
    case 'COMPLETED':
      return '已执行';
    case 'REFUSED_WRITE':
      return '已拒发（写请求）';
    case 'INVALID_TARGET':
      return '目标不合法';
    case 'UPSTREAM_UNREACHABLE':
      return '上游不可达';
    default:
      return outcome || '—';
  }
}

/** 结论的语气。 */
export function outcomeTone(outcome: string): Tone {
  switch (outcome) {
    case 'COMPLETED':
      return 'ok';
    case 'REFUSED_WRITE':
      return 'warn';
    case 'INVALID_TARGET':
      return 'danger';
    case 'UPSTREAM_UNREACHABLE':
      return 'danger';
    default:
      return 'muted';
  }
}

// ================================================================ 展示口径

/**
 * 限流说明的一句话摘要。
 *
 * 🔴 限流是**按应用**判定的 —— 摘要里必须出现应用与窗口，
 * 不能只写「每分钟 100 次」（那会让人以为限的是这个接口）。
 */
export function rateLimitSummary(rateLimit: ApiCatalogRateLimit | null | undefined): string {
  if (!rateLimit) return '没有限流说明';
  const apps = rateLimit.appQuotas ?? [];
  if (apps.length === 0) return '本租户没有应用被授权到这个业务域（不限流 ≠ 限 0 次）';
  const parts = apps.map((app) => {
    const windows = (app.windows ?? [])
      .map((w) => `${w.windowType} ≤ ${w.limitCount}${w.enabled ? '' : '（已停用）'}`)
      .join('、');
    return `${app.appCode}（${app.grantedActions.join('/')}）${windows || '未配配额'}`;
  });
  return parts.join('；');
}

/**
 * 「读不到」的服务清单。
 *
 * 🔴 这些服务**必须**显式摆出来 —— 混进空态等于告诉接入方「它没有对外接口」。
 */
export function unavailableServices(
  services: ApiCatalogServiceStatus[] | null | undefined,
): ApiCatalogServiceStatus[] {
  return (services ?? []).filter((s) => !s.available);
}

/** 读不到的服务的一句话说明。 */
export function unavailableHint(service: ApiCatalogServiceStatus): string {
  return `${service.service} 的接口清单读不到：${service.unavailableReason ?? '原因未知'}`;
}

/**
 * 按资源组（路径第二段）分组 —— 目录里同一个业务域下往往是几十条路径，
 * 按资源组分组才看得下去。
 */
export function groupByResourceGroup(
  items: ApiCatalogInterface[] | null | undefined,
): { group: string; items: ApiCatalogInterface[] }[] {
  const groups = new Map<string, ApiCatalogInterface[]>();
  for (const item of items ?? []) {
    const key = item.resourceGroup || '（顶层）';
    const bucket = groups.get(key);
    if (bucket) bucket.push(item);
    else groups.set(key, [item]);
  }
  return [...groups.entries()].map(([group, groupItems]) => ({ group, items: groupItems }));
}

/** 路径模板里的路径参数名（{@code /catalog/spus/{spuId}} ⇒ {@code ['spuId']}）。 */
export function pathParamNames(path: string): string[] {
  const names: string[] = [];
  const pattern = /\{([^}]+)\}/g;
  let match = pattern.exec(path);
  while (match) {
    // ⚠️ noUncheckedIndexedAccess：捕获组可能不存在，取不到就跳过（不塞 undefined 进名字表）。
    const name = match[1];
    if (name) names.push(name);
    match = pattern.exec(path);
  }
  return names;
}

/**
 * 用填好的值把路径模板变成一个具体路径。
 *
 * <p>🔴 没填的路径参数**原样保留** `{xxx}` —— 页面据此提示「这一段还没填」，
 * 而不是悄悄发一个带花括号的请求（那必然 404，而 404 会被误读成「接口不存在」）。
 */
export function buildConcretePath(template: string, values: Record<string, string>): string {
  return template.replace(/\{([^}]+)\}/g, (whole, name: string) => {
    const value = values[name];
    return value && value.trim() ? encodeURIComponent(value.trim()) : whole;
  });
}

/** 路径模板里还有没填的路径参数。 */
export function missingPathParams(template: string, values: Record<string, string>): string[] {
  return pathParamNames(template).filter((name) => !values[name] || !values[name].trim());
}

/**
 * 沙箱一次尝试的结论文案。
 *
 * 🔴 「被拒发」与「跑挂了」必须分开说 —— 前者是设计使然（写请求不碰生产数据），
 * 后者是故障。
 */
export function sandboxConclusion(result: ApiCatalogSandboxResult | null | undefined): string {
  if (!result) return '';
  if (result.outcome === 'COMPLETED') {
    return `上游真的回了 ${result.statusCode}（${result.responseBytes} 字节，${result.durationMs} ms）`;
  }
  return result.message || outcomeLabel(result.outcome);
}

/** 业务域的显示口径（域码 + 它属于哪些服务）。 */
export function domainLabel(domain: ApiCatalogDomain): string {
  const services = (domain.services ?? []).join(' + ');
  return services ? `${domain.code}（${services}）` : domain.code;
}
