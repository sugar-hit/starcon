import { describe, expect, it } from 'vitest';

import {
  EMPTY_CARRIER_FILTERS,
  availabilityMeta,
  businessTypeLabel,
  carrierCategoryLabel,
  interfaceTypeLabel,
  isSelectable,
  listText,
  parseList,
  serviceCapabilityLabel,
  serviceTierLabel,
  toForm,
  validateCarrierForm,
  validateCredential,
} from './carriers-model';
import type { CarrierRow, SaveCarrierInput } from './carriers-model';

function row(overrides: Partial<CarrierRow> = {}): CarrierRow {
  return {
    id: 'c1',
    roleId: 'r1',
    partyId: 'p1',
    partyCode: 'SF',
    partyName: '顺丰速运',
    partyStatus: 'ACTIVE',
    partyUsable: true,
    roleStatus: 'ACTIVE',
    interfaceType: 'API',
    accountRef: 'ACC-SF',
    serviceCapabilities: ['EXPRESS', 'DOOR_TO_DOOR'],
    carrierCategory: 'EXPRESS_COURIER',
    serviceTier: 'EXPRESS',
    secretRef: 'kv://starcon/carriers/sf',
    secretMasked: 'sf_live_****3f2a',
    coverageCountries: ['DE', 'US'],
    coverageCount: 2,
    businessTypes: ['OWN_FULFILLMENT'],
    businessCount: 1,
    readiness: 'READY',
    availability: 'AVAILABLE',
    availabilityMessage: '可用于系统自动对接',
    note: '',
    version: 3,
    syncedAt: '2026-10-15T02:00:00Z',
    updatedAt: '2026-10-15T03:00:00Z',
    ...overrides,
  };
}

function input(overrides: Partial<SaveCarrierInput> = {}): SaveCarrierInput {
  return {
    carrierCategory: 'EXPRESS_COURIER',
    serviceTier: 'EXPRESS',
    secretRef: 'kv://starcon/carriers/sf',
    secretMasked: 'sf_live_****3f2a',
    coverageCountries: ['DE'],
    businessTypes: ['OWN_FULFILLMENT'],
    note: '',
    version: 0,
    ...overrides,
  };
}

describe('10.1.1.1 文案', () => {
  it('承运商类别覆盖蓝图职责句括号里的五种', () => {
    expect(carrierCategoryLabel('EXPRESS_COURIER')).toBe('快递');
    expect(carrierCategoryLabel('LINE_HAUL')).toBe('专线');
    expect(carrierCategoryLabel('POSTAL')).toBe('邮政');
    expect(carrierCategoryLabel('FBA_CARRIER')).toBe('FBA 承运');
    expect(carrierCategoryLabel('LAST_MILE')).toBe('末端派送');
  });

  it('未登记时显示「未登记」而不是空白', () => {
    // 空白在界面上与「这一项没有数据」无法区分，那是把缺口说成了事实
    expect(carrierCategoryLabel(null)).toBe('未登记');
    expect(serviceTierLabel(null)).toBe('未登记');
  });

  it('接口类型与服务能力是上游只读快照，文案照样给全', () => {
    expect(interfaceTypeLabel('API')).toBe('系统对接');
    expect(interfaceTypeLabel('SFTP_FILE')).toBe('文件交换');
    expect(interfaceTypeLabel('WEB_PORTAL')).toBe('平台下单');
    expect(interfaceTypeLabel('EMAIL')).toBe('邮件往来');
    expect(serviceCapabilityLabel('EXPRESS')).toBe('国际快递');
    expect(businessTypeLabel('FBA_INBOUND')).toBe('FBA 入仓');
  });

  it('空多值列表显示「未登记」而不是空串', () => {
    expect(listText([], (c) => c)).toBe('未登记');
    expect(listText(['DE', 'US'], (c) => c)).toBe('DE、US');
  });
});

describe('10.1.1.1 可用性展示', () => {
  it('区分「可用 / 仅人工 / 主体不可用 / 待补配置」四种语义', () => {
    expect(availabilityMeta('AVAILABLE')).toEqual({ label: '可用', tone: 'success' });
    expect(availabilityMeta('MANUAL_ONLY')).toEqual({ label: '仅人工', tone: 'info' });
    // 上游已拉黑与本模块还没配必须长得不一样：前者不该再去改物流配置
    expect(availabilityMeta('PARTY_BLOCKED').tone).toBe('danger');
    expect(availabilityMeta('NOT_REGISTERED').tone).toBe('neutral');
    expect(availabilityMeta('NO_CREDENTIAL').tone).toBe('neutral');
  });

  it('只有真正可被下游挑走的才算可选', () => {
    expect(isSelectable(row({ availability: 'AVAILABLE' }))).toBe(true);
    expect(isSelectable(row({ availability: 'MANUAL_ONLY' }))).toBe(true);
    expect(isSelectable(row({ availability: 'NOT_REGISTERED' }))).toBe(false);
    expect(isSelectable(row({ availability: 'PARTY_BLOCKED' }))).toBe(false);
  });
});

describe('10.1.1.1 凭据只存引用', () => {
  it('真实密钥（没有引用前缀）被拒', () => {
    const problems = validateCredential('sk_live_51H8xQ2eZvKYlo2Cabcdef', 'sk_****cdef');
    expect(problems.join('；')).toContain('真实密钥不入库');
  });

  it('填了引用就必须填真掩码', () => {
    expect(validateCredential('kv://starcon/carriers/sf', '').join('；')).toContain(
      '必须同时填掩码',
    );
    expect(validateCredential('kv://starcon/carriers/sf', 'plaintext').join('；')).toContain(
      '必须真的打码',
    );
  });

  it('四种引用前缀都放行', () => {
    for (const ref of [
      'secret://x',
      'kv://x',
      'vault://x',
      'external-secrets://x',
    ]) {
      expect(validateCredential(ref, 'a****b')).toEqual([]);
    }
  });

  it('没填引用时不报错，但只填掩码会被指出来', () => {
    expect(validateCredential('', '')).toEqual([]);
    expect(validateCredential('', 'a****b').join('；')).toContain('只填了掩码但没有凭据引用');
  });
});

describe('10.1.1.1 物流侧档案校验', () => {
  it('齐备的档案没有理由', () => {
    expect(validateCarrierForm(input())).toEqual([]);
  });

  it('逐条给出理由而不是一句笼统的「有误」', () => {
    const problems = validateCarrierForm(
      input({
        carrierCategory: '',
        serviceTier: '',
        coverageCountries: [],
        businessTypes: [],
      }),
    );
    expect(problems).toHaveLength(4);
    expect(problems.join('；')).toContain('承运商类别必填');
    expect(problems.join('；')).toContain('时效等级必填');
    expect(problems.join('；')).toContain('至少登记一个覆盖国家');
    expect(problems.join('；')).toContain('至少声明一种业务类型');
  });

  it('国家代码必须是两位字母', () => {
    expect(
      validateCarrierForm(input({ coverageCountries: ['DEU'] })).join('；'),
    ).toContain('两位字母');
  });
});

describe('10.1.1.1 表单与多值解析', () => {
  it('逗号 / 空格 / 分号都能拆，并统一大写去重', () => {
    expect(parseList('de, us;jp  DE')).toEqual(['DE', 'US', 'JP']);
    expect(parseList('')).toEqual([]);
  });

  it('从一行铺出表单初值，未登记的是空串而不是 undefined', () => {
    const form = toForm(
      row({ carrierCategory: null, serviceTier: null, secretRef: null, secretMasked: null }),
    );
    expect(form.carrierCategory).toBe('');
    expect(form.serviceTier).toBe('');
    expect(form.secretRef).toBe('');
    expect(form.secretMasked).toBe('');
    expect(form.version).toBe(3);
  });

  it('默认筛选条件不带任何筛选值', () => {
    expect(EMPTY_CARRIER_FILTERS).toMatchObject({
      keyword: '',
      carrierCategory: '',
      serviceTier: '',
      availability: '',
      countryCode: '',
      businessType: '',
      page: 0,
    });
  });
});
