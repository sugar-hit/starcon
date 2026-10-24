import { ConsistencyCheckWorkspace } from '@/components/system-health/consistency-check-workspace';

/**
 * 1.5.1.2 数据一致性体检 —— 独立页（`/system-health/consistency`）。
 *
 * <p>蓝图给本模块的定位是「跨域一致性自检 …… 给出健康结论」，输出是
 * 「一致性体检报告（分项健康 + 异常清单 + 处置入口）」。
 *
 * <p><b>为什么是 `/system-health` 下的独立页</b>：1.5 系统健康中心的两个子项要回答的是
 * 两种不同的排查 —— 1.5.1.1 回答「现在能不能正常同步」（已交付在 `/system-health`），
 * 1.5.1.2 回答「账能不能信」。后者要同时看 07 库存、02 渠道与（未交付的）12 财务，
 * 塞进任何一个归属系统的页面里都会让人误以为那只是那个系统的事。
 *
 * <p><b>它不是占位页</b>：真实数据、真实分项结论、真实差异明细与可疑区间，
 * 真实错误 / 空 / 加载三态。处置入口一律是**跳转**到归属系统 —— 本页只读
 * （卷 01 边界：工作台只读 + 跳转）。
 */
export default function ConsistencyCheckPage() {
  return <ConsistencyCheckWorkspace />;
}
