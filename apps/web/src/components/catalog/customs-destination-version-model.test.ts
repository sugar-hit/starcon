/**
 * 3.7.1.2 目的地报关版本 —— 前端模型测试。
 *
 * <p>覆盖蓝图对本模块点名的两条硬要求在**前端这一侧**的兑现：
 * 目的地不可通配（规则 B5）与「留空 = 一直有效」必须发 null 而不是省略键（规则 R1）。
 * 后端同款判定在 CustomsDestinationVersionRulesTest 里，两边都钉一遍：
 * 只钉后端的话，用户仍然能在页面上填出一个必然被后端拒绝、且看不出原因的值。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

import {
  emptyDraft,
  validateDestination,
  validateDestinationHsCode,
  draftToPayload,
  versionState,
  canPublish,
  writable,
  publishable,
  MAX_REQUIREMENTS,
} from './customs-destination-version-model';

const UUID = '0192f3c4-5d6e-7a8b-9c0d-1e2f3a4b5c6d';

describe('validateDestination —— 规则 B5：目的国不可通配', () => {
  it('接受两位大写字母', () => {
    expect(validateDestination('DE')).toEqual([]);
    expect(validateDestination('US')).toEqual([]);
    expect(validateDestination('GB')).toEqual([]);
  });

  it('拒绝空值', () => {
    expect(validateDestination('')).toHaveLength(1);
    expect(validateDestination('   ')).toHaveLength(1);
  });

  // 通配是本模块存在的理由被绕开的那条路，所以每一种写法都要被挡住：
  // 界面上能填出来的每一种「不限目的地」都必须在保存前就报错。
  it.each(['*', ' * ', '＊', '  *  '])('拒绝通配写法 %j', (raw) => {
    expect(validateDestination(raw)).toHaveLength(1);
  });

  it('通配的错误文案点明后果，而不是只说格式不对', () => {
    const [msg] = validateDestination('*');
    expect(msg).toContain('打通全球');
  });

  it.each(['D', 'DEU', 'd e', 'D1', '12'])('拒绝非法形状 %j', (raw) => {
    expect(validateDestination(raw)).toHaveLength(1);
  });

  it('小写输入按大写判定后接受（归一化在上游做）', () => {
    expect(validateDestination('de')).toEqual([]);
  });
});

describe('validateDestinationHsCode', () => {
  // 空串是**合法值**（沿用基础 HS 编码），与「没填」不同 —— 这条弄错会让运营
  // 把一个合法的留空当成漏填而去编一个税号。
  it('空串合法（沿用基础 HS 编码）', () => {
    expect(validateDestinationHsCode('')).toEqual([]);
    expect(validateDestinationHsCode('   ')).toEqual([]);
  });

  it.each(['851712', '8517123090'])('接受 6–10 位纯数字 %j', (raw) => {
    expect(validateDestinationHsCode(raw)).toEqual([]);
  });

  it.each(['85171', '85171230901', '8517A2', '851712 3090', '+851712'])(
    '拒绝 %j',
    (raw) => {
      expect(validateDestinationHsCode(raw)).toHaveLength(1);
    },
  );
});

describe('draftToPayload —— 规则 R1：留空 = 一直有效，发 null 而不是省略键', () => {
  const draft = (over: Partial<ReturnType<typeof emptyDraft>> = {}) => ({
    ...emptyDraft(),
    destinationCountryCode: 'DE',
    effectiveFrom: '2026-01-01T00:00',
    ...over,
  });

  it('effectiveTo 留空时发 null，而不是省略该键', () => {
    const payload = draftToPayload(draft({ effectiveTo: '' }));
    // 键必须在：契约是 `string | null`（键必在、值可空）。省略键会被读成
    // 「这个字段没传」，而 null 才是 +∞。
    expect('effectiveTo' in payload).toBe(true);
    expect(payload.effectiveTo).toBeNull();
  });

  it('effectiveTo 填了就发带时区偏移的 ISO-8601', () => {
    const payload = draftToPayload(draft({ effectiveTo: '2027-01-01T00:00' }));
    expect(payload.effectiveTo).toMatch(/2027-01-01T00:00:00.*([+-]\d{2}:\d{2}|Z)$/);
  });

  it('effectiveFrom 补本机时区偏移，不当成 UTC', () => {
    // datetime-local 不带时区。当成 UTC 会让整个生效区间平移，
    // 而运营填的是当地时间。
    const payload = draftToPayload(draft());
    expect(payload.effectiveFrom).toMatch(/2026-01-01T00:00:00.*([+-]\d{2}:\d{2}|Z)$/);
  });

  it('目的地归一化为大写并去空白', () => {
    expect(draftToPayload(draft({ destinationCountryCode: ' de ' })).destinationCountryCode).toBe(
      'DE',
    );
  });

  it('versionNo 是数字而不是字符串', () => {
    expect(draftToPayload(draft({ versionNo: '3' })).versionNo).toBe(3);
  });

  it('目的地税号空串原样下发（沿用基础，不编默认值）', () => {
    expect(draftToPayload(draft({ destinationHsCode: '  ' })).destinationHsCode).toBe('');
  });
});

describe('versionState —— 「已发布」与「此刻生效」分两格', () => {
  const row = (over: Record<string, unknown> = {}) =>
    ({
      status: 'PUBLISHED',
      effective: true,
      ...over,
    }) as never;

  it('草稿：明确写出「不可被报关引用」', () => {
    const s = versionState(row({ status: 'DRAFT', effective: false }));
    expect(s.key).toBe('DRAFT');
    expect(s.statusLabel).toBe('草稿');
    expect(s.effectiveLabel).toContain('不可被报关引用');
  });

  it('已发布且此刻生效', () => {
    const s = versionState(row());
    expect(s.key).toBe('PUBLISHED_EFFECTIVE');
    expect(s.statusLabel).toBe('已发布');
    expect(s.effectiveLabel).toBe('此刻生效');
    expect(s.tone).toBe('success');
  });

  // 塌缩成「已发布」会让运营把历史版本当成现在该用的版本 —— 拿旧税号去报关。
  it('已发布但已被接替：与生效中区分开', () => {
    const s = versionState(row({ effective: false }));
    expect(s.key).toBe('PUBLISHED_SUPERSEDED');
    expect(s.statusLabel).toBe('已发布');
    expect(s.effectiveLabel).toContain('已被接替');
    // 历史版本与草稿同色：两者的下一步动作都是「改内容」而不是「等审核」。
    expect(s.tone).toBe('neutral');
  });
});

describe('权限与发布前置', () => {
  it('写与发布是两个权限（能填的人未必有权发布）', () => {
    expect(writable(['catalog.compliance.update'])).toBe(true);
    expect(publishable(['catalog.compliance.update'])).toBe(false);
    expect(publishable(['catalog.compliance.publish'])).toBe(true);
  });

  it('只有草稿可发布', () => {
    expect(canPublish({ status: 'DRAFT' })).toBe(true);
    expect(canPublish({ status: 'PUBLISHED' })).toBe(false);
    expect(canPublish(null)).toBe(false);
  });

  it('认证要求条数上限存在（页面据此禁用增行）', () => {
    expect(MAX_REQUIREMENTS).toBeGreaterThan(0);
  });
});

describe('导出契约面', () => {
  beforeEach(() => vi.restoreAllMocks());
  it('UUID 常量形状合法（供后续路径用例复用）', () => {
    expect(UUID).toMatch(/^[0-9a-f-]{36}$/);
  });
});
