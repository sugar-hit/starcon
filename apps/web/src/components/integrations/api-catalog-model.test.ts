import { describe, expect, it } from 'vitest';
import type {
  ApiCatalogDomain,
  ApiCatalogInterface,
  ApiCatalogRateLimit,
  ApiCatalogSandboxResult,
  ApiCatalogServiceStatus,
} from '../../../../../packages/api-contracts/src/index';
import {
  buildConcretePath,
  domainLabel,
  exampleSourceLabel,
  exampleSourceTone,
  groupByResourceGroup,
  methodLabel,
  methodTone,
  missingPathParams,
  outcomeLabel,
  outcomeTone,
  pathParamNames,
  rateLimitSummary,
  sandboxConclusion,
  statusTone,
  unavailableHint,
  unavailableServices,
} from './api-catalog-model';

/**
 * 18.1.1.2 接口目录 前端模型的单测。
 *
 * 🔴 这组用例守的是四件最容易做丢的事：
 *
 * 1. **「按 schema 生成的示例」不能显示成官方示例** —— 它不是契约的一部分，
 *    显示成绿色就跟 spec 自带的一样了。
 * 2. **「读不到」不能被混进空态** —— 读不到 ≠ 没有对外接口。
 * 3. **限流是按应用判定的** —— 摘要里必须出现应用与窗口，
 *    不能只写「每分钟 100 次」（那会让人以为限的是这个接口）。
 * 4. **沙箱「被拒发」与「跑挂了」必须分开说** —— 前者是设计使然（写请求不碰生产数据），
 *    后者是故障。
 */

const iface = (over: Partial<ApiCatalogInterface>): ApiCatalogInterface => ({
  key: 'catalog GET /catalog/spus',
  service: 'catalog',
  domain: 'catalog',
  resourceGroup: 'spus',
  method: 'GET',
  path: '/catalog/spus',
  summary: 'SPU 列表',
  description: '',
  deprecated: false,
  tags: [],
  parameterCount: 2,
  responseCount: 2,
  sandboxTestable: true,
  ...over,
});

const service = (over: Partial<ApiCatalogServiceStatus>): ApiCatalogServiceStatus => ({
  service: 'catalog',
  title: 'catalog 服务',
  baseUrl: 'http://127.0.0.1:8082',
  available: true,
  unavailableReason: null,
  interfaceCount: 4,
  ...over,
});

describe('18.1.1.2 接口目录 · HTTP 方法的口径', () => {
  it('读是中性色、写是黄、删是红', () => {
    expect(methodTone('GET')).toBe('muted');
    expect(methodTone('POST')).toBe('warn');
    expect(methodTone('PATCH')).toBe('warn');
    expect(methodTone('DELETE')).toBe('danger');
  });

  it('方法的中文说法分得清读写删', () => {
    expect(methodLabel('GET')).toBe('读');
    expect(methodLabel('POST')).toBe('新建');
    expect(methodLabel('PATCH')).toBe('改');
    expect(methodLabel('DELETE')).toBe('删');
  });
});

describe('18.1.1.2 接口目录 · 状态码的语气', () => {
  it('2xx 是绿、4xx 是黄、5xx 是红', () => {
    expect(statusTone(200)).toBe('ok');
    expect(statusTone(404)).toBe('warn');
    expect(statusTone(500)).toBe('danger');
  });

  it('没有状态码是灰的（那不是「0」）', () => {
    expect(statusTone(null)).toBe('muted');
    expect(statusTone(undefined)).toBe('muted');
  });
});

describe('18.1.1.2 接口目录 · 示例出处必须说清', () => {
  it('spec 自带是绿、按 schema 生成是黄', () => {
    expect(exampleSourceTone('SPEC')).toBe('ok');
    expect(exampleSourceTone('GENERATED_FROM_SCHEMA')).toBe('warn');
  });

  it('按 schema 生成的示例明确标成非官方示例', () => {
    expect(exampleSourceLabel('GENERATED_FROM_SCHEMA')).toContain('非官方');
    expect(exampleSourceLabel('SPEC')).toBe('spec 自带');
    expect(exampleSourceLabel('NONE')).toBe('无示例');
  });
});

describe('18.1.1.2 接口目录 · 读不到的服务不能混进空态', () => {
  it('读不到的服务被单独挑出来', () => {
    const services = [
      service({}),
      service({ service: 'finance', available: false, unavailableReason: '上游不可达' }),
    ];
    const missing = unavailableServices(services);
    expect(missing).toHaveLength(1);
    expect(missing[0]?.service).toBe('finance');
  });

  it('读不到的理由被写进提示里', () => {
    const hint = unavailableHint(
      service({ service: 'finance', available: false, unavailableReason: '上游不可达' }),
    );
    expect(hint).toContain('finance');
    expect(hint).toContain('上游不可达');
  });
});

describe('18.1.1.2 接口目录 · 限流说明按应用判定', () => {
  const rateLimit = (over: Partial<ApiCatalogRateLimit>): ApiCatalogRateLimit => ({
    policy: '限流按**应用**判定',
    appQuotas: [],
    grantedResourceCodes: [],
    note: '',
    ...over,
  });

  it('没有授权到这个域的应用时如实说没有', () => {
    expect(rateLimitSummary(rateLimit({}))).toContain('没有应用被授权');
  });

  it('摘要里出现应用与窗口，而不是只写一个次数', () => {
    const summary = rateLimitSummary(
      rateLimit({
        appQuotas: [
          {
            appId: 'a',
            appCode: 'erp-gateway',
            appName: 'ERP 网关',
            grantedActions: ['READ'],
            windows: [{ windowType: 'MINUTE', limitCount: 120, enabled: true }],
          },
        ],
      }),
    );
    expect(summary).toContain('erp-gateway');
    expect(summary).toContain('MINUTE');
    expect(summary).toContain('120');
  });
});

describe('18.1.1.2 接口目录 · 沙箱结论', () => {
  const result = (over: Partial<ApiCatalogSandboxResult>): ApiCatalogSandboxResult => ({
    runId: 'r1',
    targetService: 'catalog',
    httpMethod: 'GET',
    targetPath: '/catalog/spus',
    queryString: '',
    statusCode: 200,
    contentType: 'application/json',
    bodySummary: '{"total":0}',
    truncated: false,
    responseBytes: 11,
    durationMs: 7,
    outcome: 'COMPLETED',
    message: '',
    createdAt: '2026-10-30T00:00:00Z',
    ...over,
  });

  it('已执行时说出真实的���态码与耗时', () => {
    const text = sandboxConclusion(result({}));
    expect(text).toContain('200');
    expect(text).toContain('7 ms');
  });

  it('被拒发与跑挂了分开说', () => {
    expect(outcomeLabel('REFUSED_WRITE')).toContain('已拒发');
    expect(outcomeLabel('UPSTREAM_UNREACHABLE')).toContain('不可达');
    expect(outcomeTone('REFUSED_WRITE')).toBe('warn');
    expect(outcomeTone('UPSTREAM_UNREACHABLE')).toBe('danger');
  });

  it('没发出去时用后端给的原因，不编一个成功样例', () => {
    const text = sandboxConclusion(
      result({ outcome: 'REFUSED_WRITE', statusCode: null, message: '沙箱只放行读请求' }),
    );
    expect(text).toContain('沙箱只放行读请求');
    expect(text).not.toContain('上游真的回了');
  });
});

describe('18.1.1.2 接口目录 · 路径模板', () => {
  it('路径参数名被读出来', () => {
    expect(pathParamNames('/catalog/spus/{spuId}')).toEqual(['spuId']);
    expect(pathParamNames('/catalog/spus')).toEqual([]);
  });

  it('填了值就替换、没填就原样保留', () => {
    expect(buildConcretePath('/catalog/spus/{spuId}', { spuId: 'abc' })).toBe('/catalog/spus/abc');
    expect(buildConcretePath('/catalog/spus/{spuId}', {})).toBe('/catalog/spus/{spuId}');
  });

  it('没填的路径参数被列出来（页面据此提示，而不是悄悄发一个必 404 的请求）', () => {
    expect(missingPathParams('/catalog/spus/{spuId}', {})).toEqual(['spuId']);
    expect(missingPathParams('/catalog/spus/{spuId}', { spuId: 'abc' })).toEqual([]);
  });
});

describe('18.1.1.2 接口目录 · 分组与显示', () => {
  it('按资源组分组，目录才看得下去', () => {
    const groups = groupByResourceGroup([
      iface({}),
      iface({ path: '/catalog/spus/{spuId}', resourceGroup: 'spus' }),
      iface({ path: '/catalog/orders', resourceGroup: 'orders' }),
    ]);
    expect(groups.map((g) => g.group)).toEqual(['spus', 'orders']);
    expect(groups[0]?.items).toHaveLength(2);
  });

  it('业务域标签带上它属于哪些服务', () => {
    const domain: ApiCatalogDomain = {
      code: 'catalog',
      services: ['catalog'],
      pathCount: 3,
      interfaceCount: 4,
    };
    expect(domainLabel(domain)).toContain('catalog');
  });
});
