import { CustomsElementsWorkspace } from '@/components/logistics/customs-elements';

/**
 * 10.5.1.2 申报要素。
 *
 * <p>蓝图：「维护申报所需的品名、规格材质、用途、品牌、数量、单价、币种、产地、
 * 原产地规则（RCEP/优惠税率适用），支持按目的地国家差异化。」输入是「3.7.1.2 目的地报关版本；
 * 批次实际信息」，输出是「完整申报要素集；**缺失项提示**」。
 * L3 锚点是 10.5.1「报关与清关」。
 *
 * <p><b>与 10.5.1.1 报关单（{@code /logistics/customs}）分页而不是合页</b>：
 * 那一页管的是「这一票货报出去了没有、结果如何」，这一页管的是「申报资料本身准不准、
 * 齐不齐」。两者写入的是不同的事实（报关明细行 vs 申报要素集），
 * 混在一页会让报关操作员在填单时改到申报口径 —— 那是清关事实，只能由专门的人维护。
 *
 * <p><b>路由取 {@code /logistics/customs-elements}</b>，刻意不叫 customs：
 * {@code /logistics/customs} 已被 10.5.1.1 占用，两条车道各写一个 page.tsx 会互相覆盖。
 */
export default function CustomsElementsPage() {
  return <CustomsElementsWorkspace />;
}
