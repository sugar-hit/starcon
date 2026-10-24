import { describe, expect, it } from 'vitest';
import type {
  NotificationPreferenceCapabilities,
  NotificationPreferenceResolution,
  NotificationPreferenceView,
} from '../../../../../packages/api-contracts/src/index';
import {
  CHANNEL_LABELS,
  MAX_QUIET_WINDOWS,
  PREFERENCE_CHANNELS,
  buildPreferenceInput,
  describeTimezoneSource,
  describeWindow,
  groupTimezones,
  isEmptySelection,
  isValidWindowText,
  listTimezones,
  mandatorySummary,
  normalizeWindowText,
  quietStateOf,
  selectionOf,
  settingOf,
  summarizePreference,
  toggleChannel,
  type ChannelSelection,
} from './notification-preference-model';

/**
 * 1.3.1.2 通知偏好 —— 前端模型的单元测试。
 *
 * <p>🔴 **重点盯住三件事**：
 *
 * <ol>
 *   <li><b>底线渠道不可取消</b>（关键业务通知关不掉）—— 客户端先挡一道，
 *       服务端再挡一道（400）；只在前端置灰是不够的。</li>
 *   <li><b>渠道集合不允许为空</b> —— 取消最后一个渠道时保持原样。</li>
 *   <li><b>免打扰时段是「当地时刻」语义</b> —— 形状是 {@code HH:mm-HH:mm}，
 *       跨午夜要说清楚是「次日」。</li>
 * </ol>
 */

const view: NotificationPreferenceView = {
  stored: true,
  types: [
    { type: 'STOCKOUT', label: '库存缺货', channels: ['IN_APP', 'EMAIL'], mandatory: false, baselineChannel: null },
    {
      type: 'TIMELINESS_OVERDUE',
      label: '履约超时',
      channels: ['IN_APP'],
      mandatory: true,
      baselineChannel: 'IN_APP',
    },
    { type: 'SYNC_FAILURE', label: '同步失败', channels: ['IN_APP', 'EMAIL', 'IM'], mandatory: false, baselineChannel: null },
    {
      type: 'RECONCILIATION_DIFF',
      label: '对账差异',
      channels: ['IN_APP', 'EMAIL'],
      mandatory: true,
      baselineChannel: 'IN_APP',
    },
    { type: 'MESSAGE', label: '站内信', channels: ['IN_APP'], mandatory: false, baselineChannel: null },
  ],
  windows: ['22:00-08:00'],
  timezone: 'Asia/Shanghai',
  timezoneSource: 'PREFERENCE',
  defaultTimezone: 'Asia/Shanghai',
  quietHoursScope: 'EXTERNAL_CHANNELS',
  updatedAt: '2026-10-24T10:00:00Z',
  fallbackReasons: [],
};

const capabilities: NotificationPreferenceCapabilities = {
  module: '1.3.1.2',
  channels: [
    { code: 'IN_APP', label: '站内', note: '通知进站内收件箱（1.3.1.1）' },
    { code: 'EMAIL', label: '邮件', note: '外部渠道，依赖 1.3.2.2 的投递通道' },
    { code: 'IM', label: '企业 IM', note: '外部渠道，依赖 1.3.2.2 的投递通道' },
  ],
  types: [
    { code: 'STOCKOUT', label: '库存缺货', mandatory: false, baselineChannel: null, rule: '可以只留一个渠道' },
    {
      code: 'TIMELINESS_OVERDUE',
      label: '履约超时',
      mandatory: true,
      baselineChannel: 'IN_APP',
      rule: '关键业务通知：不允许被完全关闭',
    },
    { code: 'SYNC_FAILURE', label: '同步失败', mandatory: false, baselineChannel: null, rule: '可以只留一个渠道' },
    {
      code: 'RECONCILIATION_DIFF',
      label: '对账差异',
      mandatory: true,
      baselineChannel: 'IN_APP',
      rule: '关键业务通知：不允许被完全关闭',
    },
    { code: 'MESSAGE', label: '站内信', mandatory: false, baselineChannel: null, rule: '可以只留一个渠道' },
  ],
  baselineChannel: 'IN_APP',
  maxWindows: MAX_QUIET_WINDOWS,
  defaultTimezone: 'Asia/Shanghai',
  quietHoursScope: 'EXTERNAL_CHANNELS',
  boundaries: ['1.3.2.2 外部渠道推送未交付'],
};

describe('渠道勾选态', () => {
  it('从生效值里折出类型 → 渠道', () => {
    const selection = selectionOf(view);
    expect(selection.STOCKOUT).toEqual(['IN_APP', 'EMAIL']);
    expect(selection.TIMELINESS_OVERDUE).toEqual(['IN_APP']);
    expect(Object.keys(selection)).toHaveLength(5);
  });

  it('服务端词表外的渠道码不渲染成可点的复选框', () => {
    const dirty: NotificationPreferenceView = {
      ...view,
      types: [{ type: 'MESSAGE', label: '站内信', channels: ['SMS'], mandatory: false, baselineChannel: null }],
    };
    expect(selectionOf(dirty).MESSAGE).toEqual([]);
  });

  it('底线渠道不可取消', () => {
    const selection = selectionOf(view);
    const next = toggleChannel(selection, 'TIMELINESS_OVERDUE', 'IN_APP', 'IN_APP');
    expect(next.TIMELINESS_OVERDUE).toEqual(['IN_APP']);
    expect(next).toBe(selection);
  });

  it('非底线类型的最后一个渠道不可取消 —— 渠道集合不允许为空', () => {
    const selection = selectionOf(view);
    const next = toggleChannel(selection, 'MESSAGE', 'IN_APP', null);
    expect(next.MESSAGE).toEqual(['IN_APP']);
  });

  it('可以加渠道、可以取消非底线渠道', () => {
    const selection = selectionOf(view);
    const added = toggleChannel(selection, 'MESSAGE', 'EMAIL', null);
    expect(added.MESSAGE).toEqual(['IN_APP', 'EMAIL']);
    const removed = toggleChannel(added, 'MESSAGE', 'IN_APP', null);
    expect(removed.MESSAGE).toEqual(['EMAIL']);
  });

  it('关键业务通知可以再加别的渠道', () => {
    const selection = selectionOf(view);
    const next = toggleChannel(selection, 'RECONCILIATION_DIFF', 'IM', 'IN_APP');
    expect(next.RECONCILIATION_DIFF).toEqual(['IN_APP', 'EMAIL', 'IM']);
  });

  it('空集合能被认出来', () => {
    expect(isEmptySelection([])).toBe(true);
    expect(isEmptySelection(['IN_APP'])).toBe(false);
  });
});

describe('保存入参', () => {
  it('折成列表桶（契约里没有裸 Map）', () => {
    const input = buildPreferenceInput(selectionOf(view), ['22:00-08:00'], 'Asia/Shanghai');
    expect(input.types).toHaveLength(5);
    expect(input.types?.[0]).toEqual({ type: 'STOCKOUT', channels: ['IN_APP', 'EMAIL'] });
    expect(input.windows).toEqual(['22:00-08:00']);
    expect(input.timezone).toBe('Asia/Shanghai');
  });

  it('空时段是「清空」，不是「保持原值」', () => {
    const input = buildPreferenceInput(selectionOf(view), [], 'Asia/Shanghai');
    expect(input.windows).toEqual([]);
  });
});

describe('免打扰时段', () => {
  it('形状不对的时段被拒', () => {
    for (const bad of ['22:00', '22:00-8:00', '24:00-08:00', '22:60-08:00', '晚上十点到八点']) {
      expect(normalizeWindowText(bad), bad).toBeNull();
    }
  });

  it('起止相同被拒（那等于全天免打扰）', () => {
    expect(normalizeWindowText('08:00-08:00')).toBeNull();
  });

  it('合法时段归一化后原样返回', () => {
    expect(normalizeWindowText(' 22:00-08:00 ')).toBe('22:00-08:00');
    expect(isValidWindowText('09:00-12:00')).toBe(true);
    expect(isValidWindowText('09:00-12:00 ')).toBe(true);
  });

  it('跨午夜要说清楚是「次日」', () => {
    expect(describeWindow('22:00-08:00')).toBe('22:00 → 次日 08:00');
    expect(describeWindow('09:00-12:00')).toBe('09:00 → 12:00');
  });
});

describe('时区清单', () => {
  it('从运行时取 IANA 区域 id（不写死一份数组）', () => {
    const zones = listTimezones();
    expect(zones.length).toBeGreaterThan(0);
    expect(zones).toContain('Asia/Shanghai');
    expect(zones).toContain('America/New_York');
  });

  it('按区域前缀分组', () => {
    const groups = groupTimezones(['Asia/Shanghai', 'Europe/Berlin', 'Asia/Tokyo']);
    const asia = groups.find((group) => group.region === 'Asia');
    expect(asia?.zones).toEqual(['Asia/Shanghai', 'Asia/Tokyo']);
  });

  it('当前生效的时区一定在清单里（哪怕清单里没有）', () => {
    const groups = groupTimezones(['Asia/Shanghai'], 'Pacific/Kiritimati');
    expect(groups.flatMap((group) => group.zones)).toContain('Pacific/Kiritimati');
  });
});

describe('汇总', () => {
  it('数出类型数、底线项数、只留站内的类型数、时段数', () => {
    const summary = summarizePreference(view);
    expect(summary.typeCount).toBe(5);
    expect(summary.mandatoryCount).toBe(2);
    expect(summary.inboxOnlyCount).toBe(2);
    expect(summary.quietWindowCount).toBe(1);
  });

  it('取某一类的设置', () => {
    expect(settingOf(view, 'MESSAGE')?.label).toBe('站内信');
    expect(settingOf(view, 'NOT_A_TYPE')).toBeUndefined();
  });
});

describe('免打扰状态', () => {
  it('从解析结果里取全局免打扰状态', () => {
    const resolution: NotificationPreferenceResolution = {
      type: 'MESSAGE',
      label: '站内信',
      channels: ['IN_APP', 'EMAIL'],
      suppressedChannels: ['EMAIL'],
      quietNow: true,
      quietWindows: ['22:00-08:00'],
      timezone: 'Asia/Shanghai',
      mandatory: false,
    };
    expect(quietStateOf(resolution)).toEqual({
      quietNow: true,
      quietWindows: ['22:00-08:00'],
    });
  });
});

describe('文案', () => {
  it('时区来源说清楚按哪个时区算', () => {
    expect(describeTimezoneSource(view)).toContain('你在本模块设置');
    expect(describeTimezoneSource({ ...view, timezoneSource: 'ACCOUNT_LOCALE' })).toContain(
      '语言与时区',
    );
    expect(describeTimezoneSource({ ...view, timezoneSource: 'DEFAULT' })).toContain('平台默认');
  });

  it('底线项清单只列关键业务通知', () => {
    const lines = mandatorySummary(capabilities);
    expect(lines).toHaveLength(2);
    expect(lines.join('\n')).toContain('履约超时');
    expect(lines.join('\n')).toContain('对账差异');
  });
});

describe('渠道词表', () => {
  it('与蓝图逐字一致：站内 / 邮件 / 企业 IM', () => {
    expect(PREFERENCE_CHANNELS).toEqual(['IN_APP', 'EMAIL', 'IM']);
    expect(CHANNEL_LABELS).toEqual({ IN_APP: '站内', EMAIL: '邮件', IM: '企业 IM' });
  });

  it('能力声明下发的词表与前端一致（两份词表漂移会被发现）', () => {
    expect(capabilities.channels.map((channel) => channel.code)).toEqual([...PREFERENCE_CHANNELS]);
  });
});

describe('勾选态的不可变性', () => {
  it('切换不会改到入参那份对象', () => {
    const selection: ChannelSelection = { MESSAGE: ['IN_APP'] };
    const next = toggleChannel(selection, 'MESSAGE', 'EMAIL', null);
    expect(selection.MESSAGE).toEqual(['IN_APP']);
    expect(next.MESSAGE).toEqual(['IN_APP', 'EMAIL']);
  });
});
