import { describe, expect, it } from 'vitest';
import {
  blockerText,
  cancelableCell,
  changeKindLabels,
  holdTaskStageLabels,
  money,
  quantityCell,
  shipBlocking,
  statusLabel,
  statusLabels,
  trim,
  warningText,
  writebackStatusLabels,
} from './order-change-cancel-model';

/**
 * 9.1.1.3 前端模型单测。
 *
 * <p><b>本文件钉死的是模块最容易回归的一条纪律</b>：拿不到的数量显示
 * <b>「未接入」而不是 0</b>。
 *
 * <p>这不是洁癖。8.3.1.2 的已拣量、9.1.1.2 的七个不可得分区都因为同一件事立过规矩 ——
 * <b>0 会被读成「确实没有」</b>。本模块里这条纪律的后果最重：
 * 「未发货数量 0」会被当成「这件还没发，随便取消」，
 * 而「已发货订单不能取消」正是蓝图加粗的硬规则。
 * 所以下面这几条用例会**故意写死 '未接入'**，让任何人想改成 0 时立刻看到测试变红。
 */
describe('quantityCell：拿不到的数量必须是「未接入」而不是 0', () => {
  it('投影没接入时返回「未接入」，绝不返回 0', () => {
    expect(quantityCell(null, false)).toBe('未接入');
    expect(quantityCell(0, false)).toBe('未接入');
    expect(quantityCell(4, false)).toBe('未接入');
  });

  it('投影已接入时返回真实数字，并去掉多余的零', () => {
    expect(quantityCell(0, true)).toBe('0');
    expect(quantityCell(3, true)).toBe('3');
    expect(quantityCell('10.000000', true)).toBe('10');
    expect(quantityCell('2.500000', true)).toBe('2.5');
  });

  it('值缺失一律按未接入处理，而不是 0', () => {
    expect(quantityCell(null, true)).toBe('未接入');
    expect(quantityCell(undefined, true)).toBe('未接入');
  });
});

describe('cancelableCell：可取消量这一格最要紧', () => {
  it('未核到已发货量时整格显示「未接入，请人工核对」，不是 0', () => {
    const cell = cancelableCell(6, false);
    expect(cell.verified).toBe(false);
    expect(cell.value).toContain('未接入');
    expect(cell.value).not.toBe('0');
  });

  it('已核到已发货量时给出真实可取消量', () => {
    const cell = cancelableCell(6, true);
    expect(cell.verified).toBe(true);
    expect(cell.value).toBe('6');
  });

  it('即使服务端算出来是 0，也照实显示 0（那是真的没有可取消量）', () => {
    const cell = cancelableCell(0, true);
    expect(cell.verified).toBe(true);
    expect(cell.value).toBe('0');
  });
});

describe('shipBlocking：已发货占满的行指向转售后', () => {
  it('被发货挡住的行给出转售后归属', () => {
    const text = shipBlocking({ shipBlocking: true, cancelQuantity: 1 });
    expect(text).toContain('转售后');
    expect(text).toContain('11');
  });

  it('没被挡住时返回空串，不占位', () => {
    expect(shipBlocking({ shipBlocking: false, cancelQuantity: 3 })).toBe('');
  });
});

describe('blockerText / warningText：确认前置条件逐条摊开', () => {
  it('有阻塞项时逐条列出并标注归属模块', () => {
    const text = blockerText([
      { description: '仓库 WH-CN-SH 的停止作业尚未完成', ownerModule: '7.2.1.3' },
      { description: '平台回写尚未被平台接受', ownerModule: '2.5.1.1' },
    ]);
    expect(text).toContain('停止作业');
    expect(text).toContain('7.2.1.3');
    expect(text).toContain('平台回写');
    expect(text).toContain('2.5.1.1');
  });

  it('没有阻塞项时给一句明确的话，而不是空白', () => {
    expect(blockerText([])).toBe('没有阻塞项，可以推进到已确认');
    expect(blockerText(undefined)).toBe('没有阻塞项，可以推进到已确认');
  });

  it('数据完整性提示与阻塞项分开：它必须显示，但不当闸门', () => {
    expect(warningText([])).toBe('');
    const warning = warningText([
      { description: '本单没有任何履约投影记录，「未发货数量」是**未接入**而不是 0', ownerModule: '9.3.3.1 履约请求' },
    ]);
    expect(warning).toContain('未接入');
    expect(warning).toContain('9.3.3.1');
  });
});

describe('标签映射', () => {
  it('五个状态都有中文标签（蓝图点名的五种一个都不能少）', () => {
    expect(statusLabel('REQUESTED')).toBe('已申请');
    expect(statusLabel('PROCESSING')).toBe('处理中');
    expect(statusLabel('CONFIRMED')).toBe('已确认');
    expect(statusLabel('REJECTED')).toBe('已拒绝');
    expect(statusLabel('PENDING_VERIFICATION')).toBe('待核验');
    expect(Object.keys(statusLabels)).toHaveLength(5);
  });

  it('未知状态原样透出，不落成「未知」把真实枚举藏起来', () => {
    expect(statusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('仓库两段任务与平台回写状态都有标签', () => {
    expect(holdTaskStageLabels.STOP_WORK).toBe('停止作业');
    expect(holdTaskStageLabels.RELEASE_OCCUPANCY).toBe('释放占用');
    expect(writebackStatusLabels.CONFIRMED).toBe('平台已接受');
    expect(changeKindLabels.CANCEL).toBe('取消订单');
    expect(changeKindLabels.ADDRESS_MODIFY).toBe('修改地址');
  });
});

describe('金额与数字：前端不做浮点记账', () => {
  it('金额原样透出并带币种', () => {
    expect(money('29.9700', 'USD')).toBe('29.97 USD');
    expect(money(19.98, 'USD')).toBe('19.98 USD');
  });

  it('金额缺失显示破折号而不是 0', () => {
    expect(money(null, 'USD')).toBe('—');
    expect(money(undefined)).toBe('—');
  });

  it('trim 不把非数字悄悄变成 NaN 文本以外的任何东西', () => {
    expect(trim('3')).toBe('3');
    expect(trim(3)).toBe('3');
  });
});
