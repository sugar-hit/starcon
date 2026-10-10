import { CustomsDestinationVersionWorkspace } from '@/components/catalog/customs-destination-version-workspace';

/**
 * 3.7.1.2 目的地报关版本。
 *
 * <p>挂在 SKU 详情之下、与 3.7.1.1（/products/sku/[id]/customs）同一层的兄弟路由：
 * 两者问的是同一个 SKU 的同一件事的两面 ——「申报什么」（基础 HS 编码与申报要素）
 * 与「报到哪个国家去、按哪套口径报」（按目的地版本化的差异声明）。
 * 拆成两个页面而不是一个，是因为后者的每一条都要**独立审核发布**才允许报关引用，
 * 混在一页里会把「已发布可被引用」与「草稿只在内部可见」两种状态压成一个标签。
 *
 * <p>**目的地在地址里是必填的，不能通配** —— 蓝图对本模块的硬要求：
 * 不同目的地必须用不同版本，不能用一套申报资料打通全球。所以这里没有
 * 「不传目的地看全部」的入口：后端把通配 '*' 一律拒绝（规则 B5），
 * 页面再补一道同样的判定与中文报错，免得用户填出一个必然被拒的值。
 */
export default function CustomsDestinationVersionPage({ params }: { params: { id: string } }) {
  return <CustomsDestinationVersionWorkspace skuId={params.id} />;
}
