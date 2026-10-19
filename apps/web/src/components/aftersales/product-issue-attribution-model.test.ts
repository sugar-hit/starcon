import { describe, expect, it } from 'vitest';
import {
  concludeBlockers,
  dominantStageText,
  flagTone,
  HONEST_STAGES,
  STAGE_LABELS,
  stageBreakdownRows,
  stageRequiresLot,
  stageTone,
} from './product-issue-attribution-model';

/**
 * 11.1.2.2 商品问题归因的纯展示派生逻辑。
 *
 * <p>这几条函数承载蓝图加粗句「<b>同一个批次退回来 10 件 ≠ 10 个批次各退 1 件</b>」
 * 在界面上的语义，所以单独覆盖：
 *
 * <ul>
 *   <li><b>平票不许随便挑一个主导环节</b> —— 「一半生产一半运输」是一个需要人看的结论，
 *       挑一个等于把这个信息抹掉。</li>
 *   <li><b>「待定」不是失败态</b> —— 把它标成危险色等于告诉运营「你还没干活」，
 *       而「责任待定」是一个<b>合法</b>的结论。</li>
 *   <li><b>生产责任必须挂批次</b> —— 本地预检提前说清，但真正的校验在后端与 DB 各一次。</li>
 * </ul>
 */
describe('11.1.2.2 归因环节', () => {
  it('四个环节逐字对应职责句，另有��个诚实出口', () => {
    expect(STAGE_LABELS.PRODUCTION).toBe('生产');
    expect(STAGE_LABELS.TRANSPORT).toBe('运输');
    expect(STAGE_LABELS.WAREHOUSE).toBe('仓储');
    expect(STAGE_LABELS.DESCRIPTION).toBe('描述');
    // 诚实出口：「责任待定」必须能被如实记下来。
    expect(STAGE_LABELS.UNDETERMINED).toBe('待定');
    expect(STAGE_LABELS.BUYER_FACTOR).toBe('买家因素');
  });

  it('只有生产责任必须挂批次', () => {
    expect(stageRequiresLot('PRODUCTION')).toBe(true);
    for (const stage of ['TRANSPORT', 'WAREHOUSE', 'DESCRIPTION', 'BUYER_FACTOR', 'UNDETERMINED']) {
      expect(stageRequiresLot(stage)).toBe(false);
    }
  });

  it('「待定」给警告语气，其余给正向 —— 责任待定不是失败态', () => {
    expect(stageTone('UNDETERMINED')).toBe('warning');
    expect(stageTone('PRODUCTION')).toBe('success');
    expect(stageTone(null)).toBe('neutral');
    // 诚实出口不属于「已完成的责任判定」，不该和四环节一个语气。
    expect(HONEST_STAGES.has('BUYER_FACTOR')).toBe(true);
    expect(HONEST_STAGES.has('PRODUCTION')).toBe(false);
  });

  it('批次标记三档语气', () => {
    expect(flagTone('NONE')).toBe('neutral');
    expect(flagTone('SUSPECT')).toBe('warning');
    expect(flagTone('CONFIRMED')).toBe('danger');
  });
});

describe('11.1.2.2 环节分布', () => {
  const one = {
    production: 1,
    transport: 0,
    warehouse: 0,
    description: 0,
    buyerFactor: 0,
    undetermined: 0,
  };

  it('主导环节取件数最多的那个', () => {
    expect(dominantStageText({ ...one, production: 5 })).toBe('生产');
    expect(dominantStageText({ ...one, transport: 5 })).toBe('运输');
    // ⚠️ 平票返回「责任分散」而不是随便挑一个。
    expect(dominantStageText({ ...one, production: 2, transport: 2 })).toBe('责任分散');
  });

  it('全零与缺失都显示破折号而不是空白', () => {
    // 空白会被读成「没有数据」而不是「真的是 0」。
    expect(dominantStageText({
      production: 0,
      transport: 0,
      warehouse: 0,
      description: 0,
      buyerFactor: 0,
      undetermined: 0,
    })).toBe('—');
    expect(dominantStageText(undefined)).toBe('—');
  });

  it('六个环节全部出现，缺键补 0', () => {
    const rows = stageBreakdownRows(one);
    expect(rows).toHaveLength(6);
    expect(rows.map((r) => r.stage)).toEqual([
      'PRODUCTION',
      'TRANSPORT',
      'WAREHOUSE',
      'DESCRIPTION',
      'BUYER_FACTOR',
      'UNDETERMINED',
    ]);
    expect(rows.every((r) => typeof r.count === 'number')).toBe(true);
    // 缺失时也列出全部六个（值为 0），界面不会显示成空白。
    expect(stageBreakdownRows(undefined)).toHaveLength(6);
  });
});

describe('11.1.2.2 出结论前的本地预检', () => {
  it('三项齐全且不涉及生产责任时没有阻塞', () => {
    expect(concludeBlockers('TRANSPORT', '外箱压损', '要求加固', false)).toEqual([]);
  });

  it('归因到生产但没挂批次时明确说出来', () => {
    const blockers = concludeBlockers('PRODUCTION', '产线不良', '换模', false);
    expect(blockers).toContain('归因到「生产」必须挂批次：没有批次就指不到某一次投料');
    // 挂了批次就放行。
    expect(concludeBlockers('PRODUCTION', '产线不良', '换模', true)).toEqual([]);
  });

  it('理由与改进建议都必填 —— 改进建议是职责句明文要求的输出', () => {
    expect(concludeBlockers('', '理由', '建议', true)).toContain('必须选归因环节');
    expect(concludeBlockers('TRANSPORT', '  ', '建议', true)).toContain('必须写归因理由');
    expect(concludeBlockers('TRANSPORT', '理由', '  ', true)).toContain(
      '必须写改进建议（职责句要求的输出）',
    );
  });

  it('⚠️ 本地预检全绿不等于后端会放行（真正的校验在后端与 DB 各一次）', () => {
    // 这里刻意只断言本地预检**不**替代服务端校验：
    // 环节与售后原因码的相容性（如「描述不符」不许归到「生产」）本地无从判断，
    // 所以本地全绿时后端仍可能拒 —— UI 必须把后端消息原样显示。
    expect(concludeBlockers('PRODUCTION', '理由', '建议', true)).toEqual([]);
  });
});
