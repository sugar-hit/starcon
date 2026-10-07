import { describe, expect, it } from 'vitest';
import type {
  PartyCarrierProfile,
  PartyCustomerProfile,
  PartyRoleProfileRow,
  PartySupplierProfile,
} from '../../../../../packages/api-contracts/src/catalog';
import {
  customerFormProblem,
  carrierFormProblem,
  emptyCarrierForm,
  emptyCustomerForm,
  emptyProfileQuery,
  emptySupplierForm,
  roleStatusLabel,
  rowHeadline,
  slotState,
  supplierFormProblem,
  toCarrierForm,
  toCustomerForm,
  toCustomerInput,
  toSupplierForm,
  toSupplierInput,
  type SupplierProfileForm,
} from './party-role-profile-model';

/**
 * 3.6.1.2 角色档案前端模型的离线单测。
 *
 * <p>刻意钉死四组断言，它们是「前后端各说一套」最容易漂的地方：
 * R1 的双向拒绝、R2 的额度零非法、三态槽位的区分、以及"空值不能变成 0"。
 */

function supplier(over: Partial<PartySupplierProfile> = {}): PartySupplierProfile {
  return {
    partyId: 'p1',
    partyCode: 'SUP-1',
    partyName: '星尘科技',
    roleId: 'r1',
    roleType: 'SUPPLIER',
    roleTypeLabel: '供应商',
    roleStatus: 'ACTIVE',
    profileFound: true,
    paymentTermsDays: 30,
    paymentTermsLabel: '账期 30 天',
    moqQuantity: 500,
    moqUnitCode: 'PCS',
    moqUnitName: '件',
    moqLabel: '500件',
    cooperationLevel: 'STRATEGIC',
    cooperationLevelLabel: '战略供应商',
    settlementMethod: 'CREDIT',
    settlementMethodLabel: '账期结算',
    note: '备注',
    version: 3,
    warnings: [],
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-02T00:00:00Z',
    ...over,
  };
}

function customer(over: Partial<PartyCustomerProfile> = {}): PartyCustomerProfile {
  return {
    partyId: 'p1',
    partyCode: 'CUS-1',
    partyName: '蓝海商贸',
    roleId: 'r2',
    roleType: 'CUSTOMER',
    roleTypeLabel: '客户',
    roleStatus: 'ACTIVE',
    profileFound: true,
    customerLevel: 'STRATEGIC',
    customerLevelLabel: '战略客户',
    defaultCurrency: 'USD',
    defaultCreditLimit: 100000,
    creditLimitLabel: '额度 100000 USD',
    paymentTermsDays: 30,
    paymentTermsLabel: '账期 30 天',
    note: null,
    version: 1,
    warnings: [],
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-02T00:00:00Z',
    ...over,
  };
}

function carrier(over: Partial<PartyCarrierProfile> = {}): PartyCarrierProfile {
  return {
    partyId: 'p1',
    partyCode: 'CAR-1',
    partyName: '顺捷国际',
    roleId: 'r3',
    roleType: 'CARRIER',
    roleTypeLabel: '承运商',
    roleStatus: 'ACTIVE',
    profileFound: true,
    interfaceType: 'API',
    interfaceTypeLabel: '系统对接',
    accountRef: 'UPS-12345',
    serviceCapabilities: ['AIR', 'SEA'],
    serviceCapabilityOptions: [
      { code: 'AIR', label: '空运' },
      { code: 'SEA', label: '海运' },
    ],
    serviceCapabilitiesLabel: '空运、海运',
    serviceScopeNote: '只做东南亚',
    note: null,
    version: 0,
    warnings: [],
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-02T00:00:00Z',
    ...over,
  };
}

describe('三态槽位', () => {
  it('区分「没指派身份」「指派了但没建档案」「已建档案」', () => {
    expect(slotState(null, false)).toBe('not-assigned');
    expect(slotState('r1', false)).toBe('no-profile');
    expect(slotState('r1', true)).toBe('saved');
  });

  it('没建档案时行摘要明确说「尚未建档案」而不是空白', () => {
    const row: PartyRoleProfileRow = {
      partyId: 'p1',
      partyCode: 'SUP-1',
      partyName: '星尘科技',
      roleId: 'r1',
      roleType: 'SUPPLIER',
      roleTypeLabel: '供应商',
      roleStatus: 'ACTIVE',
      roleStatusLabel: '使用中',
      profileFound: false,
      level: null,
      levelLabel: null,
      headline: null,
      note: null,
      version: 0,
      updatedAt: null,
    };
    expect(rowHeadline(row)).toBe('尚未建档案');
    expect(rowHeadline({ ...row, profileFound: true, headline: '账期 30 天' })).toBe('账期 30 天');
  });

  it('角色状态标签区分使用中与已停用', () => {
    expect(roleStatusLabel('ACTIVE')).toBe('使用中');
    expect(roleStatusLabel('INACTIVE')).toBe('已停用');
    expect(roleStatusLabel(null)).toBe('未知');
  });
});

describe('R1 结算方式与账期', () => {
  it('账期结算必须大于零天', () => {
    const form: SupplierProfileForm = { ...emptySupplierForm(), settlementMethod: 'CREDIT', paymentTermsDays: '0' };
    expect(supplierFormProblem(form)).toContain('账期必须大于 0');
    expect(supplierFormProblem({ ...form, paymentTermsDays: '30' })).toBeNull();
  });

  it('非账期结算一律零天（双向拒绝）', () => {
    for (const method of ['PREPAID', 'ON_DELIVERY', 'LETTER_OF_CREDIT']) {
      const base: SupplierProfileForm = { ...emptySupplierForm(), settlementMethod: method };
      expect(supplierFormProblem(base), method).toBeNull();
      expect(supplierFormProblem({ ...base, paymentTermsDays: '90' }), method).toContain('不占用账期');
    }
  });

  it('账期留空合法（= 现结），非整数与越界不合法', () => {
    const base: SupplierProfileForm = { ...emptySupplierForm() };
    expect(supplierFormProblem(base)).toBeNull();
    expect(supplierFormProblem({ ...base, paymentTermsDays: '366' })).toContain('0~365');
    expect(supplierFormProblem({ ...base, paymentTermsDays: '30.5' })).toContain('0~365');
    expect(supplierFormProblem({ ...base, paymentTermsDays: '-1' })).toContain('0~365');
  });
});

describe('MOQ 数量与单位成对', () => {
  it('只填数量或只填单位都被拒', () => {
    const base: SupplierProfileForm = { ...emptySupplierForm() };
    expect(supplierFormProblem({ ...base, moqQuantity: '100' })).toContain('就必须填采购单位');
    expect(supplierFormProblem({ ...base, moqUnitCode: 'PCS' })).toContain('就必须填最小起订量');
    expect(supplierFormProblem({ ...base, moqQuantity: '100', moqUnitCode: 'PCS' })).toBeNull();
  });

  it('MOQ 为 0 不合法（不设请留空），超过 6 位小数不合法', () => {
    const base: SupplierProfileForm = { ...emptySupplierForm() };
    expect(supplierFormProblem({ ...base, moqQuantity: '0', moqUnitCode: 'PCS' })).toContain('大于 0');
    expect(
      supplierFormProblem({ ...base, moqQuantity: '1.1234567', moqUnitCode: 'PCS' }),
    ).toContain('6 位小数');
  });

  it('读取时数量与单位保持成对，空值不被补成 0', () => {
    const form = toSupplierForm(supplier());
    expect(form.moqQuantity).toBe('500');
    expect(form.moqUnitCode).toBe('PCS');
    expect(form.version).toBe(3);
    const none = toSupplierForm(supplier({ moqQuantity: null, moqUnitCode: null }));
    expect(none.moqQuantity).toBe('');
    expect(none.moqUnitCode).toBe('');
  });
});

describe('R2 信用额度与币种', () => {
  it('额度为零非法，「留空 = 不限制赊账」才是不设额度', () => {
    const base = emptyCustomerForm();
    expect(customerFormProblem({ ...base, defaultCreditLimit: '0' })).toContain('不限制赊账');
    expect(customerFormProblem({ ...base, defaultCreditLimit: '-5' })).toContain('大于 0');
    // 没填额度是合法且有意义的「没有」
    expect(customerFormProblem(base)).toBeNull();
    expect(toCustomerInput({ ...base, defaultCreditLimit: '' }).defaultCreditLimit).toBeNull();
  });

  it('有额度必须有币种，反之不成立', () => {
    const base = emptyCustomerForm();
    expect(
      customerFormProblem({ ...base, defaultCreditLimit: '5000', defaultCurrency: '' }),
    ).toContain('就必须填默认币种');
    expect(
      customerFormProblem({ ...base, defaultCreditLimit: '5000', defaultCurrency: 'USD' }),
    ).toBeNull();
    // 有币种没额度：正常（先建档，币种等第一笔交易才知道）
    expect(customerFormProblem({ ...base, defaultCurrency: 'EUR' })).toBeNull();
  });

  it('风险客户不能留额度', () => {
    const base = emptyCustomerForm();
    expect(
      customerFormProblem({
        ...base,
        customerLevel: 'RISK',
        defaultCurrency: 'USD',
        defaultCreditLimit: '1000',
      }),
    ).toContain('风险客户');
  });

  it('币种必须是 ISO 4217 三位代码（大小写会被规范化）', () => {
    const base = emptyCustomerForm();
    expect(customerFormProblem({ ...base, defaultCurrency: 'usd' })).toBeNull();
    expect(customerFormProblem({ ...base, defaultCurrency: 'US' })).toContain('ISO 4217');
    expect(toCustomerInput({ ...base, defaultCurrency: ' usd ' }).defaultCurrency).toBe('USD');
  });

  it('额度精度上限四位小数', () => {
    const base = emptyCustomerForm();
    expect(
      customerFormProblem({ ...base, defaultCurrency: 'USD', defaultCreditLimit: '1.00001' }),
    ).toContain('4 位小数');
    expect(
      customerFormProblem({ ...base, defaultCurrency: 'USD', defaultCreditLimit: '1.0000' }),
    ).toBeNull();
  });

  it('往返后额度保持数字，空值保持空串而不是 0', () => {
    expect(toCustomerForm(customer()).defaultCreditLimit).toBe('100000');
    expect(toCustomerForm(customer({ defaultCreditLimit: null })).defaultCreditLimit).toBe('');
  });
});

describe('承运商档案', () => {
  it('账号必填（没有账号的档案在 10 物流里用不了）', () => {
    expect(carrierFormProblem({ ...emptyCarrierForm(), accountRef: '  ' })).toContain('账号标识不能为空');
    expect(carrierFormProblem({ ...emptyCarrierForm(), accountRef: 'UPS-1' })).toBeNull();
  });

  it('账号超长被拒', () => {
    expect(
      carrierFormProblem({ ...emptyCarrierForm(), accountRef: 'X'.repeat(129) }),
    ).toContain('128');
  });

  it('能力选择可增删，往返保持已选集合', () => {
    const form = toCarrierForm(carrier());
    expect(form.serviceCapabilities).toEqual(['AIR', 'SEA']);
    expect(form.accountRef).toBe('UPS-12345');
    expect(form.serviceScopeNote).toBe('只做东南亚');
    expect(toCarrierForm(carrier({ serviceCapabilities: [] })).serviceCapabilities).toEqual([]);
  });
});

describe('表单与提交的往返', () => {
  it('供应商表单往返后 version 不丢（乐观锁要靠它）', () => {
    const form = toSupplierForm(supplier());
    expect(form.version).toBe(3);
    const input = toSupplierInput(form);
    expect(input.version).toBe(3);
    expect(input.paymentTermsDays).toBe(30);
    expect(input.moqQuantity).toBe(500);
    expect(input.moqUnitCode).toBe('PCS');
  });

  it('账期为 0 提交成 0 而不是 null（0 在业务上等于现结，有确切含义）', () => {
    const input = toSupplierInput({ ...emptySupplierForm(), paymentTermsDays: '' });
    expect(input.paymentTermsDays).toBeNull();
    const explicit = toSupplierInput({ ...emptySupplierForm(), paymentTermsDays: '0' });
    expect(explicit.paymentTermsDays).toBe(0);
  });

  it('空筛选默认值合理', () => {
    const query = emptyProfileQuery();
    expect(query.roleType).toBe('SUPPLIER');
    expect(query.page).toBe(0);
    expect(query.missingOnly).toBe(false);
    expect(query.includeInactive).toBe(false);
    expect(emptyProfileQuery('CARRIER').roleType).toBe('CARRIER');
  });
});