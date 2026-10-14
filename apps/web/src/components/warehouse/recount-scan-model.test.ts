import { describe, expect, it } from 'vitest';
import {
  canComplete,
  canEscalate,
  canOpenBox,
  canScan,
  completionText,
  exceptionKindLabel,
  highValueHint,
  lineStatusLabel,
  lineStatusTone,
  lineWarnings,
  needsResolveNote,
  qtyText,
  resolutionLabel,
  scanTypeLabel,
  shortfallText,
  statusLabel,
  statusTone,
  verdictLabel,
  type RecountLineStatus,
} from './recount-scan-model';

/**
 * 8.3.3.1 复核扫描前端模型的展示口径。
 *
 * <p>这些用例钉的都是「错了会让人把货发错」的判断，而不是文案排版：
 * 件数相等却判核过、把 null 说成 0、把「转人工」显示成「已放行」、
 * 把「完成复核」说成「已发货」。
 */
describe('数量口径', () => {
  it('null / undefined / 空串显示为「—」而不是 0', () => {
    // 「还没量过」与「一件都没有」是两种现场事实，混淆会让作业员以为不用扫了。
    expect(qtyText(null)).toBe('—');
    expect(qtyText(undefined)).toBe('—');
    expect(qtyText('')).toBe('—');
    expect(qtyText('0')).toBe('0');
    expect(qtyText('12')).toBe('12');
  });

  it('差值只在真的没核齐时出现，并分清少与多', () => {
    expect(shortfallText('3', '3')).toBe('');
    expect(shortfallText('3', '2')).toBe('少 1 件');
    expect(shortfallText('3', '0')).toBe('少 3 件');
    expect(shortfallText('3', '5')).toBe('多 2 件');
    // 应发未知时不给差值 —— 「不知道应发几件」不是「差 0 件」
    expect(shortfallText(null, '2')).toBe('');
    expect(shortfallText('', '2')).toBe('');
    // 实扫缺失按 0 算（列是 NOT NULL DEFAULT 0，不是没量过）
    expect(shortfallText('3', null)).toBe('少 3 件');
  });
});

describe('序列号逐件核对', () => {
  const base = {
    expectedQuantity: '3',
    scannedQuantity: '3',
    requiresSerial: true,
    serialMatch: false,
  };

  it('件数相等但序列号集合不等时必须顶出警告', () => {
    const warnings = lineWarnings({ ...base, lineStatus: 'MISMATCH' });
    expect(warnings.join('|')).toContain('序列号集合与实拣不相等');
    expect(warnings.join('|')).toContain('必须逐件核对');
  });

  it('序列号已对上时不报序列号警告', () => {
    const warnings = lineWarnings({ ...base, serialMatch: true, lineStatus: 'MATCHED' });
    expect(warnings.join('|')).not.toContain('序列号');
    expect(warnings).toHaveLength(0);
  });

  it('非逐件核对的行不报序列号警告（没有号可核）', () => {
    const warnings = lineWarnings({
      ...base,
      requiresSerial: false,
      serialMatch: false,
      lineStatus: 'MATCHED',
    });
    expect(warnings.join('|')).not.toContain('序列号');
  });

  it('漏件与多件的说法指向不同的下一步', () => {
    expect(lineWarnings({ ...base, lineStatus: 'MISSING', scannedQuantity: '2' }).join('|')).toContain(
      '少 1 件',
    );
    expect(lineWarnings({ ...base, lineStatus: 'OVER', scannedQuantity: '4' }).join('|')).toContain(
      '多 1 件',
    );
    // 一件都还没扫：说的是「还没开始」而不是「少了三件」
    expect(
      lineWarnings({ ...base, lineStatus: 'PENDING', scannedQuantity: '0' }).join('|'),
    ).toContain('一件都还没扫到');
  });

  it('序列号不符按危险色渲染，且不被「没扫齐」的说法盖过', () => {
    expect(lineStatusTone('MISMATCH')).toBe('danger');
    expect(lineStatusTone('MISSING')).toBe('danger');
    expect(lineStatusTone('OVER')).toBe('danger');
    expect(lineStatusTone('MATCHED')).toBe('success');
    expect(lineStatusTone('PENDING')).toBe('neutral');
    expect(lineStatusLabel('MISMATCH')).toBe('序列号不符');
  });
});

describe('状态词表', () => {
  it('复核四态都不含「已发货」与「已打包」', () => {
    // 复核通过只是把出库单交到待打包；发货是 8.3.4.1，打包是 8.3.3.2。
    const labels = ['OPEN', 'PASSED', 'FAILED', 'CANCELLED'].map(statusLabel);
    expect(labels).toEqual(['复核中', '复核通过', '复核异常', '已取消']);
    expect(labels.join('|')).not.toContain('发货');
    expect(labels.join('|')).not.toContain('已打包');
  });

  it('复核异常用危险色而不是黄色', () => {
    // 复核是错发拦截的最后机会，一单没过就意味着它本来会被错发出去。
    expect(statusTone('FAILED')).toBe('danger');
    expect(statusTone('PASSED')).toBe('success');
    expect(statusTone('OPEN')).toBe('info');
    expect(statusTone('CANCELLED')).toBe('neutral');
  });

  it('六类异常各有说法，不是同一类换名字', () => {
    expect(exceptionKindLabel('MISSING_ITEM')).toBe('漏件');
    expect(exceptionKindLabel('WRONG_ITEM')).toBe('错件');
    expect(exceptionKindLabel('EXTRA_ITEM')).toBe('多件');
    expect(exceptionKindLabel('DUPLICATE_SCAN')).toBe('重复扫');
    expect(exceptionKindLabel('SERIAL_MISMATCH')).toBe('序列号集合不符');
    expect(exceptionKindLabel('UNKNOWN_BARCODE')).toBe('未知码');
    // 未知值原样透出，不猜
    expect(exceptionKindLabel('WHAT_IS_THIS')).toBe('WHAT_IS_THIS');
  });

  it('转人工与「已处理」在界面上是不同的说法', () => {
    expect(resolutionLabel('PENDING')).toBe('待处理');
    expect(resolutionLabel('MANUAL')).toBe('转人工处理');
    expect(resolutionLabel('CORRECTED')).toBe('已修正');
    expect(resolutionLabel('ACCEPTED')).toBe('接受现状');
    expect(resolutionLabel('IGNORED')).toBe('忽略');
  });

  it('两种扫描方式各有说法', () => {
    expect(scanTypeLabel('BARCODE')).toBe('条码');
    expect(scanTypeLabel('SERIAL')).toBe('序列号');
  });

  it('结算结论的三种说法', () => {
    expect(verdictLabel('PASSED')).toBe('核过');
    expect(verdictLabel('FAILED')).toBe('没核过');
    expect(verdictLabel('OPEN')).toBe('待结算');
  });
});

describe('动作可点性', () => {
  const verdict = {
    blocking: true,
    blockingReasons: ['还有 1 行没核过'],
  } as never;
  const clean = { blocking: false, blockingReasons: [] } as never;

  it('「完成复核」只看后端给的 blocking，不在前端自己数行', () => {
    expect(canComplete('OPEN', verdict)).toBe(false);
    expect(canComplete('OPEN', clean)).toBe(true);
    // 没有结论时不给可点 —— 「还没结算」不等于「可以放行」
    expect(canComplete('OPEN', null)).toBe(false);
    // 终态不可点：复核单已定论
    expect(canComplete('PASSED', clean)).toBe(false);
    expect(canComplete('FAILED', clean)).toBe(false);
    expect(canComplete('CANCELLED', clean)).toBe(false);
  });

  it('只有「复核中」能扫、才能开箱', () => {
    expect(canScan('OPEN')).toBe(true);
    expect(canScan('PASSED')).toBe(false);
    expect(canScan('FAILED')).toBe(false);
    expect(canScan('CANCELLED')).toBe(false);
    expect(canOpenBox('OPEN', false)).toBe(true);
    expect(canOpenBox('OPEN', true)).toBe(false);
    expect(canOpenBox('PASSED', false)).toBe(false);
  });

  it('没有待处理异常时没有东西可转人工', () => {
    expect(canEscalate(2, 'OPEN')).toBe(true);
    expect(canEscalate(0, 'OPEN')).toBe(false);
    // 已取消的单不再转人工
    expect(canEscalate(2, 'CANCELLED')).toBe(false);
  });

  it('四个处置值全都要求写下处理意见', () => {
    expect(needsResolveNote('MANUAL')).toBe(true);
    expect(needsResolveNote('CORRECTED')).toBe(true);
    expect(needsResolveNote('ACCEPTED')).toBe(true);
    expect(needsResolveNote('IGNORED')).toBe(true);
    expect(needsResolveNote('PENDING')).toBe(false);
  });
});

describe('提示文案', () => {
  it('通过时逐字写明货在打包区、预占仍在、这不是发货', () => {
    const text = completionText({ verdict: 'PASSED', blockingReasons: [] } as never);
    expect(text).toContain('待打包');
    expect(text).toContain('不释放预占');
    expect(text).toContain('打包区');
    expect(text).toContain('8.3.4.1');
    expect(text).not.toContain('已发货');
  });

  it('没通过时给出拦路原因与正确出口（退回拣货，不是改库存）', () => {
    const text = completionText({
      verdict: 'FAILED',
      blockingReasons: ['还有 1 行没核过', '还有 2 条异常没人处理'],
    } as never);
    expect(text).toContain('还有 1 行没核过');
    expect(text).toContain('还有 2 条异常没人处理');
    expect(text).toContain('退回拣货');
    expect(text).toContain('不改库存');
  });

  it('没有结论时不编文案', () => {
    expect(completionText(null)).toBe('');
    expect(completionText(undefined)).toBe('');
  });

  it('高价值提示只说件数，绝不说金额', () => {
    // 出库单上没有金额列（V72 的 COPY 语义），界面上编一个金额比不给更糟。
    const text = highValueHint({
      openBoxRequired: true,
      openBox: false,
      baselineQuantity: '12',
    } as never);
    expect(text).toContain('应发件数');
    expect(text).toContain('12');
    expect(text).toContain('开箱');
    // 蓝图原话就是「高价值订单」，这个词可以照用；**不能出现的是金额口径** ——
    // 出库单上没有金额列（V72 的 COPY 语义），界面上编一个金额比不给更糟。
    expect(text).not.toContain('金额');
    expect(text).not.toContain('货值');
    expect(text).not.toMatch(/[¥$￥]\s*\d/);

    expect(highValueHint({ openBoxRequired: true, openBox: true } as never)).toBe(
      '本单已开箱复核。',
    );
    expect(highValueHint({ openBoxRequired: false, openBox: false } as never)).toBe('');
    expect(highValueHint(null)).toBe('');
  });
});

/** 类型层面的守卫：五态词表与后端 CHECK 逐字一致，改一处要改两处。 */
it('行状态五态与后端 V117 的 CHECK 一致', () => {
  const states: RecountLineStatus[] = ['PENDING', 'MATCHED', 'MISSING', 'OVER', 'MISMATCH'];
  expect(states.map(lineStatusLabel)).toEqual(['未核', '核过', '漏件', '多件', '序列号不符']);
});