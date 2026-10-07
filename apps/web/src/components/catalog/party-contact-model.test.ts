import { describe, expect, it } from 'vitest';
import type { PartyAddress, PartyContact } from '../../../../../packages/api-contracts/src/catalog';
import {
  ADDRESS_USAGES,
  addressEmptyHint,
  addressInputProblem,
  addressUsagesProblem,
  addressVersionEmptyHint,
  cityProblem,
  contactBlockLabel,
  contactDisplayName,
  contactEmptyHint,
  contactInputProblem,
  contactReachProblem,
  contactPhoneProblem,
  countryProblem,
  emptyAddressInput,
  emptyContactInput,
  postalCodeProblem,
  removedUsages,
  selectionBlockLabel,
  streetLine1Problem,
  toAddressInput,
  toContactInput,
  usageHint,
  usageLabel,
} from './party-contact-model';

/**
 * 3.6.3.1 前端模型的校验与展示规则。
 *
 * <p>这里的断言与后端 `PartyAddressContactRulesTest` 一一对应 —— 同一份规则的两份实现。
 * 前端那份存在的理由只是「提交前少一次无谓的往返」，所以两边一旦对不上，
 * 用户就会遇到「前端放过、后端拒掉」的红字出现在提交之后。
 */
describe('3.6.3.1 地址与联系人前端模型', () => {
  // ------------------------------------------------------------ 词表与展示

  it('用途词表只有收货 / 发货 / 提货三个且标签与后端一致', () => {
    expect(ADDRESS_USAGES.map((usage) => usage.value)).toEqual(['SHIP_TO', 'SHIP_FROM', 'PICKUP']);
    expect(usageLabel('SHIP_TO')).toBe('收货地址');
    expect(usageLabel('SHIP_FROM')).toBe('发货地址');
    expect(usageLabel('PICKUP')).toBe('提货地址');
    // 三个用途的说明互不相同（界面上靠它解释「什么时候用哪个」）。
    expect(new Set(ADDRESS_USAGES.map((usage) => usage.hint)).size).toBe(3);
    expect(usageHint('PICKUP')).toContain('10 物流');
  });

  it('阻断原因码的标签两两不同', () => {
    const labels = ['ADDRESS_DELETED', 'PARTY_DELETED', 'PARTY_INACTIVE', 'PARTY_BLACKLISTED'].map(
      (code) => selectionBlockLabel(code),
    );
    expect(new Set(labels).size).toBe(4);
    expect(selectionBlockLabel(null)).toBe('可选');
    // 联系人少了「地址已删除」那一档，但主体三档仍与地址一致。
    expect(contactBlockLabel('PARTY_INACTIVE')).toBe(selectionBlockLabel('PARTY_INACTIVE'));
    expect(contactBlockLabel('ADDRESS_DELETED')).toBe('不可选');
    expect(contactBlockLabel(null)).toBe('可选');
  });

  // ------------------------------------------------------------ A1 列校验

  it('国家必填且是两位大写代码', () => {
    expect(countryProblem('')).toContain('国家不能为空');
    expect(countryProblem('CHN')).toContain('最多 2 个字符');
    expect(countryProblem('中国')).toContain('两位');
    // 英国是 GB 而不是 UK：这一条写进提示里是因为 UK 是最常被填错的那个。
    expect(countryProblem('GB')).toBeNull();
    expect(countryProblem(' de ')).toBeNull();
  });

  it('邮编可空但空白串被拒', () => {
    expect(postalCodeProblem(null)).toBeNull();
    expect(postalCodeProblem('')).toBeNull();
    expect(postalCodeProblem('518000')).toBeNull();
    expect(postalCodeProblem('   ')).toContain('空格');
  });

  it('城市与街道必填', () => {
    expect(cityProblem('')).toContain('城市不能为空');
    expect(streetLine1Problem('  ')).toContain('街道地址不能为空');
    expect(contactPhoneProblem('abc', '电话')).toContain('只能包含数字');
    expect(contactPhoneProblem('+86 138 0000 0000', '手机号')).toBeNull();
  });

  it('地址表单整体校验返回第一条可执行原因', () => {
    expect(addressInputProblem(emptyAddressInput())).toContain('城市不能为空');
    expect(
      addressInputProblem({ ...emptyAddressInput(), city: '深圳市', streetLine1: '科技园 1 号' }),
    ).toBeNull();
    expect(
      addressInputProblem({
        ...emptyAddressInput(),
        city: '深圳市',
        streetLine1: '科技园 1 号',
        countryCode: '',
      }),
    ).toContain('国家不能为空');
  });

  // ------------------------------------------------------------ A2 用途

  it('同一用途不能标两次且三个用途可全用', () => {
    expect(
      addressUsagesProblem([
        { usage: 'SHIP_TO', isDefault: true },
        { usage: 'SHIP_TO', isDefault: false },
      ]),
    ).toContain('只能标一次');
    expect(
      addressUsagesProblem([
        { usage: 'SHIP_TO', isDefault: false },
        { usage: 'SHIP_FROM', isDefault: true },
        { usage: 'PICKUP', isDefault: false },
      ]),
    ).toBeNull();
    expect(addressUsagesProblem([])).toBeNull();
    expect(addressUsagesProblem([{ usage: 'WAREHOUSE', isDefault: false }])).toContain('只能是');
  });

  it('整体替换语义下未被提交的用途会被取消且界面要能算出来', () => {
    // 「我明明在改门牌号，怎么提货地址没了」是最难排查的一类问题，
    // 所以取消哪些用途必须在提交前算出来给用户看。
    expect(
      removedUsages(['SHIP_TO', 'PICKUP'], [{ usage: 'SHIP_TO', isDefault: true }, null]),
    ).toEqual(['PICKUP']);
    expect(
      removedUsages(['SHIP_TO', 'PICKUP'], [
        { usage: 'SHIP_TO', isDefault: true },
        { usage: 'PICKUP', isDefault: false },
      ]),
    ).toEqual([]);
    expect(removedUsages(['PICKUP'], [])).toEqual(['PICKUP']);
  });

  // ------------------------------------------------------------ C1 联系方式

  it('邮箱 / 电话 / 手机至少给一项', () => {
    expect(contactReachProblem(null, null, null)).toContain('至少填写一项');
    expect(contactReachProblem('', '', '')).toContain('至少填写一项');
    // 「填了但只有空格」先报它：否则格式校验会把 "   " 说成「格式不正确」，
    // 让运营以为自己格式写错了。
    expect(contactReachProblem('  ', '', '   ')).toContain('空格');
    expect(contactReachProblem('a@example.com', null, null)).toBeNull();
    expect(contactReachProblem(null, null, '138-0000-0000')).toBeNull();
  });

  it('联系人表单整体校验', () => {
    expect(contactInputProblem(emptyContactInput())).toContain('姓名不能为空');
    expect(
      contactInputProblem({
        ...emptyContactInput(),
        name: '张三',
        email: 'not-an-email',
      }),
    ).toContain('格式不正确');
    expect(
      contactInputProblem({ ...emptyContactInput(), name: '张三', mobile: '138-0000-0000' }),
    ).toBeNull();
  });

  // ------------------------------------------------------------ 表单与展示

  it('详情转表单时带全用途（整体替换语义下少发一条就是静默取消）', () => {
    const address = {
      id: 'a',
      partyId: 'p',
      label: '仓库',
      countryCode: 'CN',
      stateProvince: '广东省',
      city: '深圳市',
      district: '南山区',
      postalCode: '518000',
      streetLine1: '科技园 1 号',
      streetLine2: null,
      oneLine: '科技园 1 号 深圳市 CN',
      usages: ['SHIP_TO', 'PICKUP'],
      defaultUsages: ['PICKUP'],
      usageSummary: '收货地址 · 提货地址（默认）',
      latestVersionNo: 3,
      versionCount: 3,
      version: 5,
      selectionBlockCode: null,
      selectionBlockMessage: null,
      deleted: false,
      createdAt: '2026-10-01T00:00:00Z',
      updatedAt: '2026-10-02T00:00:00Z',
    } satisfies PartyAddress;
    const form = toAddressInput(address);
    expect(form.usages).toEqual([
      { usage: 'SHIP_TO', isDefault: false },
      { usage: 'PICKUP', isDefault: true },
    ]);
    // 乐观锁的版本号带过来了（不是 0），否则每次编辑都会撞冲突。
    expect(form.version).toBe(5);
    expect(form.city).toBe('深圳市');
    // 单行地址由服务端给，前端不重拼。
    expect(address.oneLine).toContain('科技园 1 号');
  });

  it('联系人转表单保留默认标记与版本号', () => {
    const contact = {
      id: 'c',
      partyId: 'p',
      name: '张三',
      position: '采购经理',
      email: 'zhangsan@example.com',
      phone: null,
      mobile: '138-0000-0000',
      isPrimary: true,
      note: null,
      selectionBlockCode: null,
      selectionBlockMessage: null,
      version: 2,
      createdAt: '2026-10-01T00:00:00Z',
      updatedAt: '2026-10-01T00:00:00Z',
    } satisfies PartyContact;
    const form = toContactInput(contact);
    expect(form.isPrimary).toBe(true);
    expect(form.version).toBe(2);
    expect(contactDisplayName(contact)).toBe('张三（采购经理）');
  });

  it('联系人的展示名在没有职务时只显示姓名', () => {
    expect(contactDisplayName({ name: '李四', position: null })).toBe('李四');
    expect(contactDisplayName({ name: '李四', position: '  ' })).toBe('李四');
  });

  // ------------------------------------------------------------ 空态文案

  it('无数据与筛选为空用不同文案', () => {
    expect(addressEmptyHint(false)).toContain('一个供应商通常有不止一处');
    expect(addressEmptyHint(true)).toContain('当前筛选条件');
    expect(contactEmptyHint(false)).toContain('至少建一个');
    expect(contactEmptyHint(true)).toContain('当前筛选条件');
    // 历史版本空态要说清「没有版本」不是故障，而是这条地址还没被改过。
    expect(addressVersionEmptyHint()).toContain('冻结快照');
  });
});