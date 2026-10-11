import { describe, expect, it } from 'vitest';
import type {
  BrandAuthorization,
  BrandAuthorizationInput,
  BrandDetail,
  BrandPublishEligibility,
  BrandRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  BLOCK_ACTIONS,
  BLOCK_LABELS,
  blockAction,
  blockLabel,
  brandInputProblem,
  codeProblem,
  credentialProblem,
  credentialState,
  displayName,
  emptyBrandInput,
  emptyCredentialInput,
  emptyHint,
  linkableCategories,
  logoProblem,
  nameProblem,
  namesProblem,
  statusHint,
  statusLabel,
  toBrandInput,
  toCredentialInput,
} from './brand-model';

function row(patch: Partial<BrandRow> = {}): BrandRow {
  return {
    id: 'brand-1',
    code: 'ACME',
    name: 'Acme 官方',
    logoUri: 'logos/acme.png',
    authorizationStatus: 'OWNED',
    enabled: true,
    publishable: true,
    publishBlockCode: null,
    publishBlockLabel: '可刊登',
    authorizationCount: 0,
    validAuthorizationCount: 0,
    categoryCount: 0,
    categoryNames: '',
    names: {},
    version: 3,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...patch,
  };
}

function credential(patch: Partial<BrandAuthorization> = {}): BrandAuthorization {
  return {
    id: 'auth-1',
    brandId: 'brand-1',
    certificateNo: 'CERT-001',
    grantor: '品牌权利人',
    validFrom: '2026-01-01',
    validTo: '2026-12-31',
    evidenceUri: null,
    note: '',
    enabled: true,
    validOn: true,
    daysToExpiry: 200,
    version: 0,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...patch,
  };
}

function detail(patch: Partial<BrandDetail> = {}): BrandDetail {
  return {
    id: 'brand-1',
    code: 'ACME',
    name: 'Acme 官方',
    logoUri: null,
    authorizationStatus: 'LICENSED',
    enabled: true,
    version: 2,
    names: { 'zh-cn': '艾克米官方', 'en-us': 'Acme Official' },
    categories: [],
    authorizations: [],
    publishable: false,
    publishBlockCode: 'LICENSE_MISSING',
    publishBlockMessage: '品牌【Acme 官方】标记为需授权，但没有任何授权凭证，不能刊登',
    validAuthorizationCount: 0,
    asOf: '2026-06-01',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...patch,
  };
}

describe('3.3.2.1 品牌编码与名称校验', () => {
  it('编码规范化到大写并只收 ASCII 机器标识', () => {
    expect(codeProblem('acme')).toBeNull();
    expect(codeProblem('  acme_01 ')).toBeNull();
    expect(codeProblem('')).toContain('不能为空');
    expect(codeProblem('中文编码')).toContain('英文字母');
    expect(codeProblem('-leading')).toContain('英文字母');
    expect(codeProblem('A'.repeat(65))).toContain('最多 64');
  });

  it('名称只管非空与长度（中文品牌名是常态）', () => {
    expect(nameProblem('星尘')).toBeNull();
    expect(nameProblem('   ')).toContain('不能为空');
    expect(nameProblem('品'.repeat(101))).toContain('最多 100');
  });

  it('Logo 引用可空但不能含空格', () => {
    expect(logoProblem(null)).toBeNull();
    expect(logoProblem('')).toBeNull();
    expect(logoProblem('logos/a.png')).toBeNull();
    expect(logoProblem('logos/my brand.png')).toContain('不能包含空格');
    expect(logoProblem('x'.repeat(501))).toContain('最多 500');
  });
});

describe('3.3.2.1 多语言名', () => {
  it('同语言规范化后重复必须被拒', () => {
    expect(
      namesProblem([
        { locale: 'zh-CN', name: '甲' },
        { locale: 'zh-cn', name: '乙' },
      ]),
    ).toContain('同一个语言只能有一个品牌名');
  });

  it('逐条校验 locale 与内容，并限制数量', () => {
    expect(namesProblem([])).toBeNull();
    expect(namesProblem([{ locale: '', name: 'x' }])).toContain('语言标识不能为空');
    expect(namesProblem([{ locale: 'zh_CN', name: 'x' }])).toContain('BCP 47');
    expect(namesProblem([{ locale: 'zh-CN', name: ' ' }])).toContain('内容不能为空');
    expect(namesProblem([{ locale: 'zh-CN', name: '名'.repeat(201) }])).toContain('最多 200');
    const many = Array.from({ length: 21 }, (_, i) => ({ locale: `l${i}`, name: `名${i}` }));
    expect(namesProblem(many)).toContain('最多配置 20 个语言名');
  });
});

describe('3.3.2.1 凭证校验', () => {
  const base: BrandAuthorizationInput = {
    certificateNo: 'CERT-1',
    grantor: '授权方',
    validFrom: '2026-01-01',
    validTo: '2026-12-31',
    evidenceUri: null,
    note: null,
    enabled: true,
    version: 0,
  };

  it('通过的基本组合', () => {
    expect(credentialProblem(base)).toBeNull();
    // 失效日留空是长期有效，不是漏填。
    expect(credentialProblem({ ...base, validTo: null })).toBeNull();
  });

  it('拒绝空编号空授权方与日期格式问题', () => {
    expect(credentialProblem({ ...base, certificateNo: ' ' })).toContain('凭证编号不能为空');
    expect(credentialProblem({ ...base, grantor: '' })).toContain('授权方不能为空');
    expect(credentialProblem({ ...base, validFrom: '2026/01/01' })).toContain('yyyy-MM-dd');
    expect(credentialProblem({ ...base, validTo: '20261231' })).toContain('yyyy-MM-dd');
    expect(credentialProblem({ ...base, validFrom: '' })).toContain('生效日期不能为空');
    expect(credentialProblem({ ...base, evidenceUri: 'x'.repeat(501) })).toContain('最多 500');
  });

  it('失效早于生效必须被拒', () => {
    expect(
      credentialProblem({ ...base, validFrom: '2026-12-31', validTo: '2026-01-01' }),
    ).toContain('不能早于生效日期');
  });
});

describe('3.3.2.1 刊登判定的显示口径', () => {
  it('四条原因码各有标签与处理动作（不能都写成「不能刊登」）', () => {
    expect(blockLabel(null)).toBe('可刊登');
    expect(blockLabel('BRAND_DISABLED')).toBe('品牌停用');
    expect(blockLabel('LICENSE_MISSING')).toBe('缺少凭证');
    expect(blockLabel('LICENSE_NOT_YET_EFFECTIVE')).toBe('凭证未生效');
    expect(blockLabel('LICENSE_EXPIRED')).toBe('凭证已过期');
    expect(blockLabel('SOMETHING_NEW')).toBe('不可刊登');
    expect(blockAction(null)).toBeNull();
    expect(blockAction('LICENSE_MISSING')).toBe('登记一条授权凭证');
    expect(blockAction('LICENSE_NOT_YET_EFFECTIVE')).toContain('等凭证到生效日');
    expect(blockAction('LICENSE_EXPIRED')).toContain('续约');
    expect(blockAction('SOMETHING_NEW')).toBeTruthy();
    expect(Object.keys(BLOCK_LABELS)).toEqual(Object.keys(BLOCK_ACTIONS));
  });

  it('凭证状态三态：有效 / 临期 / 已撤销 / 不在有效期', () => {
    expect(credentialState(credential()).label).toBe('有效');
    expect(credentialState(credential({ daysToExpiry: 10 })).label).toContain('10 天后到期');
    expect(credentialState(credential({ daysToExpiry: 10 })).tone).toBe('warn');
    expect(credentialState(credential({ enabled: false })).label).toBe('已撤销');
    expect(credentialState(credential({ validOn: false })).label).toBe('不在有效期内');
    expect(credentialState(credential({ validOn: false })).tone).toBe('muted');
  });

  it('授权状态词表与说明', () => {
    expect(statusLabel('OWNED')).toBe('自有');
    expect(statusLabel('AGENCY')).toBe('代理');
    expect(statusLabel('LICENSED')).toBe('需授权');
    expect(statusLabel('UNKNOWN')).toBe('UNKNOWN');
    expect(statusHint('LICENSED')).toContain('凭证');
    expect(statusHint('UNKNOWN')).toBe('');
  });
});

describe('3.3.2.1 表单初值与回填', () => {
  it('空表单初值是自有且启用（不会意外制造阻断状态）', () => {
    const input = emptyBrandInput();
    expect(input.authorizationStatus).toBe('OWNED');
    expect(input.enabled).toBe(true);
    expect(input.names).toEqual([]);
    expect(input.version).toBe(0);
    // 编码与名称留空由表单提示，不在初值里预填——预填一个假编码比留空更糟。
    expect(brandInputProblem(input)).toContain('品牌编码不能为空');
    expect(brandInputProblem({ ...input, code: 'ACME', name: 'Acme' })).toBeNull();
  });

  it('空凭证初值从今天起可用', () => {
    const input = emptyCredentialInput();
    expect(input.enabled).toBe(true);
    expect(input.validFrom).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(credentialProblem(input)).toContain('凭证编号不能为空');
  });

  it('整表校验返回第一条可执行原因', () => {
    expect(
      brandInputProblem({
        code: '中文',
        name: '名字',
        logoUri: null,
        authorizationStatus: 'OWNED',
        names: [],
      }),
    ).toContain('英文字母');
    expect(
      brandInputProblem({
        code: 'ACME',
        name: '',
        logoUri: null,
        authorizationStatus: 'OWNED',
        names: [],
      }),
    ).toContain('品牌名称不能为空');
    expect(
      brandInputProblem({
        code: 'ACME',
        name: '名字',
        logoUri: 'a b',
        authorizationStatus: 'OWNED',
        names: [],
      }),
    ).toContain('空格');
    expect(
      brandInputProblem({
        code: 'ACME',
        name: '名字',
        logoUri: null,
        authorizationStatus: '',
        names: [],
      }),
    ).toContain('授权状态不能为空');
  });

  it('详情回填成表单初值（含多语言名与类目）', () => {
    const input = toBrandInput(
      detail({
        categories: [
          {
            categoryId: 'cat-1',
            categoryCode: 'TOP',
            categoryName: '上装',
            categoryPath: '服饰 / 上装',
            position: 3,
          },
        ],
      }),
    );
    expect(input.code).toBe('ACME');
    expect(input.version).toBe(2);
    expect(input.names).toEqual([
      { locale: 'zh-cn', name: '艾克米官方' },
      { locale: 'en-us', name: 'Acme Official' },
    ]);
    expect(input.categories).toEqual([{ categoryId: 'cat-1', position: 3 }]);
  });

  it('凭证回填成写入初值', () => {
    const input = toCredentialInput(credential({ note: '续签的旧凭证', version: 5 }));
    expect(input.certificateNo).toBe('CERT-001');
    expect(input.version).toBe(5);
    expect(input.note).toBe('续签的旧凭证');
  });
});

describe('3.3.2.1 显示名与候选类目', () => {
  it('按语言取多语言名，缺失时逐级回退到基础名', () => {
    const brand = row({ name: 'Acme 官方', names: { 'zh-cn': '艾克米', 'en-us': 'Acme' } });
    expect(displayName(brand, 'zh-cn')).toBe('艾克米');
    expect(displayName(brand, 'zh-CN')).toBe('艾克米');
    // 只有语言码没有地区码时按主语言匹配。
    expect(displayName(brand, 'zh')).toBe('艾克米');
    // 没有该语言时回退到基础名，而不是显示空白。
    expect(displayName(brand, 'ja-jp')).toBe('Acme 官方');
    expect(displayName(row({ names: {} }), 'zh-cn')).toBe('Acme 官方');
  });

  it('类目候选只给启用中的内部类目', () => {
    const nodes = [
      { id: 'a', enabled: true, treeKind: 'INTERNAL' },
      { id: 'b', enabled: false, treeKind: 'INTERNAL' },
      { id: 'c', enabled: true, treeKind: 'PLATFORM' },
    ] as never;
    expect(linkableCategories(nodes).map((node: { id: string }) => node.id)).toEqual(['a']);
  });

  it('两种空态文案不同（筛不出 vs 根本没有）', () => {
    expect(emptyHint(false)).toContain('还没有品牌');
    expect(emptyHint(true)).toContain('当前筛选条件下没有品牌');
  });
});

describe('3.3.2.1 契约形状', () => {
  it('列表行带刊登判定字段（详情页不必二次请求）', () => {
    const value = row({ publishable: false, publishBlockCode: 'LICENSE_EXPIRED' });
    expect(value.publishable).toBe(false);
    expect(value.publishBlockCode).toBe('LICENSE_EXPIRED');
    expect(blockLabel(value.publishBlockCode)).toBe('凭证已过期');
  });

  it('判定结果带机器可读原因码与人话', () => {
    const eligibility: BrandPublishEligibility = {
      brandId: 'brand-1',
      brandName: 'Acme',
      authorizationStatus: 'LICENSED',
      enabled: true,
      publishable: false,
      blockCode: 'LICENSE_MISSING',
      blockLabel: '缺少凭证',
      blockMessage: '品牌【Acme】标记为需授权，但没有任何授权凭证，不能刊登',
      authorizationCount: 0,
      validAuthorizationCount: 0,
      asOf: '2026-06-01',
    };
    expect(eligibility.blockCode).toBeTruthy();
    expect(eligibility.asOf).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });
});
