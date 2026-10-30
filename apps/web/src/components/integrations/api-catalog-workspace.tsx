'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  Segmented,
  StatCard,
} from '@starcon/ui';
import {
  BookOpen,
  CircleAlert,
  FlaskConical,
  Loader2,
  RefreshCw,
  ScrollText,
  TriangleAlert,
} from 'lucide-react';
import {
  buildConcretePath,
  domainLabel,
  exampleSourceLabel,
  exampleSourceTone,
  fetchCapabilities,
  fetchInterfaceDetail,
  fetchInterfaces,
  fetchOverview,
  fetchSandboxRuns,
  fetchSandboxTargets,
  groupByResourceGroup,
  methodLabel,
  methodTone,
  missingPathParams,
  outcomeLabel,
  outcomeTone,
  pathParamNames,
  rateLimitSummary,
  runSandbox,
  sandboxConclusion,
  statusTone,
  unavailableHint,
  unavailableServices,
  type Tone,
} from './api-catalog-model';
import type {
  ApiCatalogCapabilities,
  ApiCatalogDomain,
  ApiCatalogInterface,
  ApiCatalogInterfaceDetail,
  ApiCatalogInterfacePage,
  ApiCatalogOverview,
  ApiCatalogSandboxResult,
  ApiCatalogSandboxRunPage,
  ApiCatalogSandboxTarget,
  ApiCatalogServiceStatus,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 18.1.1.2「接口目录」工作区（卷 18 前端入口 `/integrations/api-catalog`）。
 *
 * 蓝图：「<strong>职责</strong>：对外开放 API 的目录与文档：按业务域列出接口、参数、返回、限流说明、示例；
 * 支持在线测试（沙箱环境）。<strong>输入</strong>：接口定义（OpenAPI 生成物）；测试请求。
 * <strong>输出</strong>：接口文档 + 测试结果。<strong>关系</strong>：上游 = 后端 OpenAPI spec（单一真相源）；
 * 下游 = 外部系统。<strong>接口契约由后端 spec 生成，文档不手写</strong>——手写文档必然与实现漂移。」
 *
 * 🔴 页面上的六条纪律：
 *
 * 1. **目录是真从后端 spec 解析来的**，页面只是把它显示出来 —— 页面**不**缓存接口清单，
 *    也不自己拼一份（拼出来的那份迟早与实现漂移）。
 * 2. **「读不到」显式摆出来**，不混进空态。某些服务读不到时，页面要写清是哪个服务、什么原因。
 * 3. **示例的出处必须显示**：「spec 自带」与「按 schema 生成」不是一回事，
 *    后者**不是**契约的一部分。
 * 4. **限流说明按应用显示**：限流挂在应用上，不是挂在接口上。
 * 5. **沙箱拒发写请求要讲清为什么**：本仓没有独立沙箱部署，写请求会打到真实业务表。
 * 6. **加载 / 空 / 错误三态齐全**：空态要区分「没有匹配的接口」与「筛选条件不对」。
 */

type Tab = 'catalog' | 'sandbox' | 'history' | 'capabilities';

/** 语气 → Badge 的 tone。 */
const BADGE_TONE: Record<Tone, 'neutral' | 'success' | 'warning' | 'danger'> = {
  ok: 'success',
  warn: 'warning',
  danger: 'danger',
  muted: 'neutral',
};

/** 被选中的接口。 */
interface Selection {
  service: string;
  method: string;
  path: string;
}

export function ApiCatalogWorkspace() {
  const [tab, setTab] = useState<Tab>('catalog');

  const [capabilities, setCapabilities] = useState<ApiCatalogCapabilities | null>(null);
  const [overview, setOverview] = useState<ApiCatalogOverview | null>(null);
  const [domains, setDomains] = useState<ApiCatalogDomain[]>([]);
  const [services, setServices] = useState<ApiCatalogServiceStatus[]>([]);
  const [targets, setTargets] = useState<ApiCatalogSandboxTarget[]>([]);
  const [page, setPage] = useState<ApiCatalogInterfacePage | null>(null);

  const [domain, setDomain] = useState('');
  const [method, setMethod] = useState('');
  const [keyword, setKeyword] = useState('');
  const [pageIndex, setPageIndex] = useState(0);

  const [selection, setSelection] = useState<Selection | null>(null);
  const [detail, setDetail] = useState<ApiCatalogInterfaceDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  const [runs, setRuns] = useState<ApiCatalogSandboxRunPage | null>(null);
  const [runResult, setRunResult] = useState<ApiCatalogSandboxResult | null>(null);

  // 沙箱表单
  const [sandboxService, setSandboxService] = useState('');
  const [sandboxInterfaces, setSandboxInterfaces] = useState<ApiCatalogInterface[]>([]);
  const [sandboxPath, setSandboxPath] = useState('');
  const [sandboxMethod, setSandboxMethod] = useState('GET');
  const [pathValues, setPathValues] = useState<Record<string, string>>({});
  const [queryValues, setQueryValues] = useState<Record<string, string>>({});

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const fail = (e: unknown) => {
    const message = e instanceof Error ? e.message : '请求失败，请稍后重试';
    setError(message);
  };

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [caps, ov, list, tgs] = await Promise.all([
        fetchCapabilities(),
        fetchOverview(),
        fetchInterfaces({ domain, method, keyword, page: pageIndex, size: 50 }),
        fetchSandboxTargets(),
      ]);
      setCapabilities(caps);
      setOverview(ov);
      setDomains(list.domains ?? []);
      setServices(list.services ?? []);
      setPage(list);
      setTargets(tgs);
    } catch (e) {
      fail(e);
    } finally {
      setLoading(false);
    }
  }, [domain, method, keyword, pageIndex]);

  useEffect(() => {
    void load();
  }, [load]);

  // 沙箱历史：切到那一页时再取。
  useEffect(() => {
    if (tab !== 'history') return;
    fetchSandboxRuns({ size: 20 })
      .then(setRuns)
      .catch(fail);
  }, [tab]);

  // 沙箱可测接口：选了服务就拉它的接口清单。
  useEffect(() => {
    if (!sandboxService) {
      setSandboxInterfaces([]);
      return;
    }
    fetchInterfaces({ service: sandboxService, size: 100 })
      .then((list) => setSandboxInterfaces(list.items ?? []))
      .catch(fail);
  }, [sandboxService]);

  const unavailable = useMemo(() => unavailableServices(services), [services]);
  const grouped = useMemo(() => groupByResourceGroup(page?.items ?? []), [page]);
  const missingParams = useMemo(
    () => missingPathParams(sandboxPath, pathValues),
    [sandboxPath, pathValues],
  );

  const select = async (item: ApiCatalogInterface) => {
    setSelection({ service: item.service, method: item.method, path: item.path });
    setDetail(null);
    setDetailLoading(true);
    setError('');
    try {
      setDetail(await fetchInterfaceDetail(item.service, item.method, item.path));
    } catch (e) {
      fail(e);
    } finally {
      setDetailLoading(false);
    }
  };

  const pickSandboxInterface = (item: ApiCatalogInterface) => {
    setSandboxPath(item.path);
    setSandboxMethod(item.method);
    setPathValues({});
    setQueryValues({});
  };

  const submitSandbox = async () => {
    setBusy(true);
    setError('');
    setNotice('');
    setRunResult(null);
    try {
      const result = await runSandbox({
        targetService: sandboxService,
        httpMethod: sandboxMethod,
        targetPath: buildConcretePath(sandboxPath, pathValues),
        query: queryValues,
      });
      setRunResult(result);
      setNotice(sandboxConclusion(result));
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const reloadRuns = async () => {
    try {
      setRuns(await fetchSandboxRuns({ size: 20 }));
    } catch (e) {
      fail(e);
    }
  };

  return (
    <div className="space-y-4">
      <PageHeader
        title="接口目录"
        description="对外开放 API 的目录与文档：按业务域列出接口、参数、返回、限流说明与示例，并支持在沙箱里真发一次请求。"
        actions={
          <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
            {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <RefreshCw className="h-4 w-4" />}
            刷新
          </Button>
        }
      />

      {error && (
        <Notice tone="danger" title="读不到">
          {error}
        </Notice>
      )}
      {notice && (
        <Notice tone="info" title="沙箱结果">
          {notice}
        </Notice>
      )}

      {/* 概览 */}
      <div className="grid gap-3 md:grid-cols-4">
        <StatCard label="业务域" value={overview?.domainCount ?? 0} unit="个" icon={<BookOpen className="h-4 w-4" />} />
        <StatCard label="对外接口" value={overview?.interfaceCount ?? 0} unit="个" icon={<ScrollText className="h-4 w-4" />} />
        <StatCard
          label="可沙箱测试"
          value={overview?.sandboxTestableCount ?? 0}
          unit="个"
          icon={<FlaskConical className="h-4 w-4" />}
        />
        <StatCard label="沙箱测试记录" value={overview?.sandboxRunCount ?? 0} unit="次" />
      </div>

      {/* 🔴 读不到的服务必须显式摆出来 —— 混进空态等于告诉接入方「它没有对外接口」。 */}
      {unavailable.length > 0 && (
        <Notice tone="warning" title={`${unavailable.length} 个服务的接口清单读不到`}>
          <ul className="list-disc space-y-1 pl-4">
            {unavailable.map((s) => (
              <li key={s.service}>{unavailableHint(s)}</li>
            ))}
          </ul>
        </Notice>
      )}

      <Segmented<Tab>
        value={tab}
        onChange={setTab}
        options={[
          { value: 'catalog', label: '接口目录' },
          { value: 'sandbox', label: '沙箱测试' },
          { value: 'history', label: '测试历史' },
          { value: 'capabilities', label: '能力声明' },
        ]}
      />

      {/* ------------------------------------------------------ 接口目录 */}
      {tab === 'catalog' && (
        <div className="grid gap-4 lg:grid-cols-2">
          <SectionCard
            title="接口清单"
            description="按业务域（对外路径首段）分组。清单由后端从各服务的 OpenAPI spec 实时解析，页面不缓存、不手写。"
            extra={
              <div className="flex flex-wrap items-center gap-2">
                <select
                  className="border-control-border h-9 rounded-md border bg-card px-2 text-sm"
                  value={domain}
                  onChange={(e) => {
                    setDomain(e.target.value);
                    setPageIndex(0);
                  }}
                >
                  <option value="">全部业务域</option>
                  {domains.map((d) => (
                    <option key={d.code} value={d.code}>
                      {domainLabel(d)}
                    </option>
                  ))}
                </select>
                <select
                  className="border-control-border h-9 rounded-md border bg-card px-2 text-sm"
                  value={method}
                  onChange={(e) => {
                    setMethod(e.target.value);
                    setPageIndex(0);
                  }}
                >
                  <option value="">全部方法</option>
                  {['GET', 'HEAD', 'POST', 'PUT', 'PATCH', 'DELETE'].map((m) => (
                    <option key={m} value={m}>
                      {m}
                    </option>
                  ))}
                </select>
                <Input
                  className="w-44"
                  placeholder="搜索路径 / 摘要…"
                  value={keyword}
                  onChange={(e) => {
                    setKeyword(e.target.value);
                    setPageIndex(0);
                  }}
                />
              </div>
            }
          >
            {loading ? (
              <div className="text-muted-foreground flex items-center gap-2 py-8 text-sm">
                <Loader2 className="h-4 w-4 animate-spin" /> 正在读取各服务的接口契约…
              </div>
            ) : (page?.items ?? []).length === 0 ? (
              <EmptyState
                icon={<BookOpen className="h-6 w-6" />}
                title={domain || method || keyword ? '没有匹配的接口' : '还没有读到任何接口'}
                description={
                  domain || method || keyword
                    ? '换个关键词或清掉筛选条件再试。'
                    : '这通常意味着所有服务的 spec 都读不到 —— 看上面的提示就知道是哪个服务、什么原因。'
                }
              />
            ) : (
              <div className="space-y-4">
                {grouped.map((group) => (
                  <div key={group.group} className="space-y-2">
                    <div className="text-muted-foreground text-xs font-medium">{group.group}</div>
                    {group.items.map((item) => (
                      <button
                        key={item.key}
                        type="button"
                        onClick={() => void select(item)}
                        className="flex w-full items-start gap-3 rounded-md border p-3 text-left text-sm hover:bg-muted"
                      >
                        <Badge tone={BADGE_TONE[methodTone(item.method)]}>{item.method}</Badge>
                        <span className="min-w-0 flex-1">
                          <span className="block font-mono text-[13px] break-all">{item.path}</span>
                          <span className="text-muted-foreground block truncate">
                            {item.summary || '（spec 未写摘要）'}
                          </span>
                        </span>
                        {item.deprecated && <Badge tone="warning">已废弃</Badge>}
                      </button>
                    ))}
                  </div>
                ))}
                <div className="text-muted-foreground text-xs">
                  共 {page?.total ?? 0} 个接口（当前第 {pageIndex + 1} 页）
                </div>
              </div>
            )}
          </SectionCard>

          <SectionCard
            title="接口详情"
            description="参数 / 返回 / 限流说明 / 示例，全部取自该接口的 spec 与本租户的授权与配额数据。"
          >
            {detailLoading ? (
              <div className="text-muted-foreground flex items-center gap-2 py-8 text-sm">
                <Loader2 className="h-4 w-4 animate-spin" /> 正在读取接口详情…
              </div>
            ) : !detail ? (
              <EmptyState
                icon={<BookOpen className="h-6 w-6" />}
                title="还没有选接口"
                description="在左边点一个接口，这里会给出它的参数、返回、限流说明与示例。"
              />
            ) : (
              <div className="space-y-4 text-sm">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone={BADGE_TONE[methodTone(detail.api.method)]}>{detail.api.method}</Badge>
                  <span className="font-mono text-[13px] break-all">{detail.api.path}</span>
                  <Badge tone="neutral">{detail.api.service}</Badge>
                </div>
                {detail.api.summary && <div className="font-medium">{detail.api.summary}</div>}
                {detail.api.description && (
                  <div className="text-muted-foreground">{detail.api.description}</div>
                )}

                <div>
                  <div className="mb-1 font-medium">参数</div>
                  {detail.parameters.length === 0 ? (
                    <div className="text-muted-foreground text-xs">该接口没有声明参数。</div>
                  ) : (
                    <div className="space-y-1">
                      {detail.parameters.map((p) => (
                        <div key={`${p.location}-${p.name}`} className="flex flex-wrap gap-2 text-xs">
                          <Badge tone={p.required ? 'warning' : 'neutral'}>{p.name}</Badge>
                          <span className="text-muted-foreground">{p.location}</span>
                          <span className="text-muted-foreground">{p.type}</span>
                          {p.required && <span className="text-warning">必填</span>}
                          {p.description && <span className="text-muted-foreground">{p.description}</span>}
                        </div>
                      ))}
                    </div>
                  )}
                </div>

                {detail.requestBody.present && (
                  <div>
                    <div className="mb-1 font-medium">请求体（{detail.requestBody.contentType}）</div>
                    <div className="space-y-1">
                      {detail.requestBody.fields.map((f) => (
                        <div key={f.name} className="flex flex-wrap gap-2 text-xs">
                          <Badge tone={f.required ? 'warning' : 'neutral'}>{f.name}</Badge>
                          <span className="text-muted-foreground">{f.type}</span>
                          {f.required && <span className="text-warning">必填</span>}
                          {f.description && <span className="text-muted-foreground">{f.description}</span>}
                        </div>
                      ))}
                    </div>
                  </div>
                )}

                <div>
                  <div className="mb-1 font-medium">返回</div>
                  <div className="space-y-2">
                    {detail.responses.map((r) => (
                      <div key={r.statusCode} className="rounded-md border p-2 text-xs">
                        <div className="flex items-center gap-2">
                          <Badge tone={BADGE_TONE[statusTone(Number(r.statusCode))]}>{r.statusCode}</Badge>
                          <span className="text-muted-foreground">{r.description}</span>
                        </div>
                        {r.fields.length > 0 && (
                          <div className="mt-1 flex flex-wrap gap-2">
                            {r.fields.map((f) => (
                              <span key={f.name} className="text-muted-foreground">
                                {f.name}: {f.type}
                              </span>
                            ))}
                          </div>
                        )}
                        {r.exampleJson && (
                          <pre className="bg-muted mt-2 overflow-x-auto rounded p-2 text-[11px]">
                            {r.exampleJson}
                          </pre>
                        )}
                      </div>
                    ))}
                  </div>
                </div>

                <div>
                  <div className="mb-1 font-medium">示例</div>
                  <div className="space-y-1 text-xs">
                    <div className="flex items-center gap-2">
                      <span>请求示例</span>
                      <Badge tone={BADGE_TONE[exampleSourceTone(detail.requestExample.source)]}>
                        {exampleSourceLabel(detail.requestExample.source)}
                      </Badge>
                    </div>
                    <div className="flex items-center gap-2">
                      <span>返回示例</span>
                      <Badge tone={BADGE_TONE[exampleSourceTone(detail.responseExample.source)]}>
                        {exampleSourceLabel(detail.responseExample.source)}
                      </Badge>
                    </div>
                    {detail.responseExample.json && (
                      <pre className="bg-muted mt-1 overflow-x-auto rounded p-2 text-[11px]">
                        {detail.responseExample.json}
                      </pre>
                    )}
                  </div>
                </div>

                <div>
                  <div className="mb-1 font-medium">限流说明</div>
                  <div className="text-muted-foreground text-xs">{detail.rateLimit.policy}</div>
                  <div className="mt-1 text-xs">{rateLimitSummary(detail.rateLimit)}</div>
                  <div className="text-muted-foreground mt-1 text-xs">{detail.rateLimit.note}</div>
                  {detail.rateLimit.grantedResourceCodes.length > 0 && (
                    <div className="text-muted-foreground mt-1 text-xs">
                      本租户已授权的资源码：
                      {detail.rateLimit.grantedResourceCodes.join('、')}
                    </div>
                  )}
                </div>
              </div>
            )}
          </SectionCard>
        </div>
      )}

      {/* ------------------------------------------------------ 沙箱测试 */}
      {tab === 'sandbox' && (
        <div className="grid gap-4 lg:grid-cols-2">
          <SectionCard
            title="在沙箱里真跑一次"
            description="沙箱会真的发一次 HTTP 请求，带你自己的会话（上游仍按成员关系解析租户并强制隔离），返回真实状态码与响应摘要。"
          >
            <div className="space-y-3">
              <Notice tone="warning" title="沙箱只放行读请求">
                GET / HEAD 之外的方法会被拒（409）且**一次请求都不发出去** —— 沙箱必须与生产数据隔离。
                写操作请在「应用与凭证」里用真实凭证对真实环境调用。
              </Notice>

              <label className="block space-y-1 text-sm">
                <span className="text-muted-foreground">目标服务</span>
                <select
                  className="border-control-border h-9 w-full rounded-md border bg-card px-2 text-sm"
                  value={sandboxService}
                  onChange={(e) => {
                    setSandboxService(e.target.value);
                    setSandboxPath('');
                  }}
                >
                  <option value="">请选择</option>
                  {targets
                    .filter((t) => t.available)
                    .map((t) => (
                      <option key={t.service} value={t.service}>
                        {t.service}（{t.title || '服务'}）
                      </option>
                    ))}
                </select>
              </label>

              <label className="block space-y-1 text-sm">
                <span className="text-muted-foreground">接口</span>
                <select
                  className="border-control-border h-9 w-full rounded-md border bg-card px-2 text-sm"
                  value={sandboxPath}
                  onChange={(e) => {
                    const found = sandboxInterfaces.find((i) => i.path === e.target.value);
                    if (found) pickSandboxInterface(found);
                    else setSandboxPath(e.target.value);
                  }}
                  disabled={!sandboxService}
                >
                  <option value="">请选择</option>
                  {sandboxInterfaces.map((i) => (
                    <option key={i.key} value={i.path}>
                      {i.method} {i.path}
                    </option>
                  ))}
                </select>
              </label>

              {pathParamNames(sandboxPath).length > 0 && (
                <div className="space-y-2">
                  <div className="text-muted-foreground text-xs">路径参数</div>
                  {pathParamNames(sandboxPath).map((name) => (
                    <label key={name} className="block space-y-1 text-sm">
                      <span className="text-muted-foreground">{name}</span>
                      <Input
                        value={pathValues[name] ?? ''}
                        placeholder="填入真实取值"
                        onChange={(e) => setPathValues({ ...pathValues, [name]: e.target.value })}
                      />
                    </label>
                  ))}
                </div>
              )}

              {detail && detail.parameters.filter((p) => p.location === 'query').length > 0 && (
                <div className="space-y-2">
                  <div className="text-muted-foreground text-xs">查询参数（只允许 spec 里声明过的）</div>
                  {detail.parameters
                    .filter((p) => p.location === 'query')
                    .map((p) => (
                      <label key={p.name} className="block space-y-1 text-sm">
                        <span className="text-muted-foreground">
                          {p.name}
                          {p.required ? '（必填）' : ''}
                        </span>
                        <Input
                          value={queryValues[p.name] ?? ''}
                          placeholder={p.example ?? ''}
                          onChange={(e) => setQueryValues({ ...queryValues, [p.name]: e.target.value })}
                        />
                      </label>
                    ))}
                </div>
              )}

              <Button
                disabled={
                  busy || !sandboxService || !sandboxPath || missingParams.length > 0
                }
                onClick={() => void submitSandbox()}
              >
                {busy ? <Loader2 className="h-4 w-4 animate-spin" /> : <FlaskConical className="h-4 w-4" />}
                发送请求
              </Button>
              {missingParams.length > 0 && (
                <div className="text-warning text-xs">
                  还有路径参数没填：{missingParams.join('、')}
                </div>
              )}
            </div>
          </SectionCard>

          <SectionCard title="沙箱结果" description="真实状态码 / 响应摘要 / 耗时 —— 不是写死的成功样例。">
            {!runResult ? (
              <EmptyState
                icon={<FlaskConical className="h-6 w-6" />}
                title="还没有跑过"
                description="选一个接口、填上参数，点「发送请求」。结果会显示真实的上游响应。"
              />
            ) : (
              <div className="space-y-3 text-sm">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone={BADGE_TONE[outcomeTone(runResult.outcome)]}>
                    {outcomeLabel(runResult.outcome)}
                  </Badge>
                  {runResult.statusCode !== null && (
                    <Badge tone={BADGE_TONE[statusTone(runResult.statusCode)]}>
                      {runResult.statusCode}
                    </Badge>
                  )}
                  <span className="font-mono text-[13px] break-all">
                    {runResult.httpMethod} {runResult.targetPath}
                  </span>
                </div>
                <div className="text-muted-foreground text-xs">
                  {runResult.message}
                  {runResult.responseBytes > 0 && ` · ${runResult.responseBytes} 字节 · ${runResult.durationMs} ms`}
                </div>
                {runResult.bodySummary && (
                  <pre className="bg-muted max-h-80 overflow-auto rounded p-2 text-[11px]">
                    {runResult.bodySummary}
                  </pre>
                )}
              </div>
            )}
          </SectionCard>
        </div>
      )}

      {/* ------------------------------------------------------ 测试历史 */}
      {tab === 'history' && (
        <SectionCard
          title="沙箱测试历史"
          description="每一次尝试都留痕（成功、拒发、目标不合法、上游不可达）。"
          extra={
            <Button variant="outline" size="sm" onClick={() => void reloadRuns()}>
              刷新
            </Button>
          }
        >
          {!runs || runs.items.length === 0 ? (
            <EmptyState
              icon={<ScrollText className="h-6 w-6" />}
              title="还没有测试记录"
              description="在「沙箱测试」里跑一次，这里就会出现那一次的留痕。"
            />
          ) : (
            <div className="space-y-2">
              {runs.items.map((r) => (
                <div key={r.runId} className="rounded-md border p-3 text-sm">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={BADGE_TONE[outcomeTone(r.outcome)]}>{outcomeLabel(r.outcome)}</Badge>
                    {r.statusCode !== null && (
                      <Badge tone={BADGE_TONE[statusTone(r.statusCode)]}>{r.statusCode}</Badge>
                    )}
                    <span className="font-mono text-[13px] break-all">
                      {r.httpMethod} {r.targetPath}
                    </span>
                    <span className="text-muted-foreground text-xs">{r.targetService}</span>
                  </div>
                  {r.message && <div className="text-muted-foreground mt-1 text-xs">{r.message}</div>}
                  {r.bodySummary && (
                    <pre className="bg-muted mt-2 max-h-40 overflow-auto rounded p-2 text-[11px]">
                      {r.bodySummary}
                    </pre>
                  )}
                </div>
              ))}
            </div>
          )}
        </SectionCard>
      )}

      {/* ------------------------------------------------------ 能力声明 */}
      {tab === 'capabilities' && (
        <div className="space-y-3">
          <SectionCard title="能力声明" description="每一维说清「有没有、依据是什么、缺了归谁」。">
            {!capabilities ? (
              <div className="text-muted-foreground flex items-center gap-2 py-6 text-sm">
                <Loader2 className="h-4 w-4 animate-spin" /> 正在读取能力声明…
              </div>
            ) : (
              <div className="space-y-2">
                {capabilities.capabilities.map((c) => (
                  <div key={c.name} className="rounded-md border p-3 text-sm">
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={c.available ? 'success' : 'warning'}>
                        {c.available ? '已具备' : '未接入'}
                      </Badge>
                      <span className="font-medium">{c.name}</span>
                      <span className="text-muted-foreground text-xs">{c.module}</span>
                    </div>
                    <div className="text-muted-foreground mt-1 text-xs">依据：{c.basis}</div>
                    {!c.available && c.ownerModule && (
                      <div className="text-warning mt-1 text-xs">缺在 {c.ownerModule}：{c.impact}</div>
                    )}
                    {c.available && c.impact && (
                      <div className="text-muted-foreground mt-1 text-xs">{c.impact}</div>
                    )}
                  </div>
                ))}
              </div>
            )}
          </SectionCard>

          <SectionCard title="下游缺口" description="谁没交付、归谁。">
            <div className="space-y-2">
              {(capabilities?.downstreamGaps ?? []).map((g) => (
                <div key={`${g.module}-${g.name}`} className="rounded-md border p-3 text-sm">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={g.available ? 'success' : 'neutral'}>{g.module}</Badge>
                    <span className="font-medium">{g.name}</span>
                    <span className="text-muted-foreground text-xs">{g.group}</span>
                  </div>
                  <div className="text-muted-foreground mt-1 text-xs">{g.detail}</div>
                </div>
              ))}
            </div>
          </SectionCard>

          <Notice tone="info" title="接口清单从哪来">
            <span className="inline-flex items-center gap-1">
              <CircleAlert className="h-3.5 w-3.5" />
              每个服务的 <code>/v3/api-docs</code> —— 蓝图原话：「接口契约由后端 spec 生成，文档不手写」。
            </span>
          </Notice>

          {unavailable.length > 0 && (
            <Notice tone="warning" title="读不到的服务">
              <span className="inline-flex items-center gap-1">
                <TriangleAlert className="h-3.5 w-3.5" />
                {unavailable.map((s) => unavailableHint(s)).join('；')}
              </span>
            </Notice>
          )}
        </div>
      )}
    </div>
  );
}
