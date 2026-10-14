# Starcon Styleguide 审查记录 — 2026-09-26

## 范围与方法

检查 localhost:3000/styleguide 实际页面、390px 响应式布局、弹窗及时间切换交互；阅读 /Users/tommy/Project/starcon 的 AGENTS.md、architecture/overview.md、data-flow.md、multi-tenancy.md、security.md、design/visual-spec.md、四系统及品牌修订记录，并核对页面、共享 UI 和 token 源码。审查未修改产品源码。启动了已有本地开发服务。

## 结论

基础视觉方向适合专业 ERP：中性色、克制装饰、统一导航、等宽数字、共享组件和指标趋势 polarity 均值得保留。当前仍是基础组件展示，尚不足以作为完整 ERP 交互规范。产品目录目前只发现 README 模板，缺少实际 PRD、persona、user story，业务判断以架构文档为依据，不能视为已确认的细分需求。

## 已验证问题

- 页面版本 v1.0，视觉文档 v1.5；文档仍含占位 Logo、没有前端代码等过期描述，与后文品牌规范和实际代码冲突。
- 三个语义色块将 rose-600 / emerald-700 / amber-600 当 CSS background，实际为空白；其他色块也用手填色值而非 token。
- brand token 为 HSL 217 91% 50%，约 #0B64F4，说明及品牌 SVG 用 #1D4ED8。
- 库存预警 SKU 减少使用 positive polarity，显示红色下降；需要按业务指标明确好坏方向。
- 时间 Segmented 固定 value 且 onChange 空实现，点击本月不改变选中项。
- Modal 可以键盘打开，取消后 dialog 仍存在；示例取消按钮未使用 ModalClose。
- 390px 屏幕 document 宽 406px；顶部系统名称换行，菜单被挤；表格宽约 512px，父容器约 298px 且 overflow hidden，内容被裁切。
- success 按钮实测背景 RGB(43,151,101)、白字，对比度约 3.67:1，低于普通文字 AA 的 4.5:1。
- 表单 label 未通过 htmlFor/id 关联；自制 Segmented 使用 tab 角色但无完整方向键、roving tabindex、面板关联实现；导航缺 aria-current。
- Badge/StatCard 直接使用 emerald/rose/amber，而按钮引用语义 token；修改 token 无法统一所有组件。
- 共享 UI lint 脚本是 echo skip；规范没有对应自动约束。common/badge.tsx 内容实际为 SectionCard，存在组件文件名漂移。

## 优先级建议

### P0：接入真实资金、库存和多租户数据前的门槛

这些是设计缺项导致的上线风险，不代表已发现线上事故。

1. Money/ExchangeRate：明确原币、结算币、基准币、汇率时间与来源；禁止 USD 外都显示 ¥ 的示例逻辑进入业务代码。空值、零值、负数、JPY 等精度分别规范。
2. TenantScope/PermissionState：显示当前租户、资源范围；区分无数据、无查看权限、只读和敏感字段脱敏。切换租户时重置选择及缓存上下文；后端仍负责授权。
3. BulkAction/JobStatus：批量操作明确当前页与全筛选范围，提交预览影响数量/仓库/金额；异步展示已提交、处理中、部分成功、失败、完成，重试只针对失败项并依赖服务端幂等。

### P1：下一轮开发应修复

1. 修正色块、品牌色、版本及过期规范；从 token 元数据生成展示。
2. 修复 KPI polarity、时间切换和弹窗取消；为示例补加载、失败、重试、成功反馈。
3. DataTable：列排序/显隐/固定、分页、批量选择、行权限、长文本、加载与错误、局部横向滚动。FilterBar：平台→店铺、仓库、日期类型/时区、状态、清空、保存视图及 URL 恢复。
4. FormField：标签、必填、帮助、错误关联；补 Select、Combobox、DateRange、MoneyInput、SKUPicker、上传、只读及脏数据离开保护。
5. OrderStatus 拆分支付、履约、售后、同步维度；退款中不默认等于失败，区分进行中与真正异常。
6. AsyncBoundary/EmptyState：首次使用、无筛选结果、无权限、同步失败、部分加载、过期数据分开，提供相应动作。
7. 响应式：窄屏 Logo markOnly，系统切换放抽屉或独立行；表格局部滚动、重要列固定；输入 w-full，弹窗保留边距并限高滚动。
8. 可访问性：普通文字对比度达标、清晰 focus、正确标签和键盘模式、状态文本播报、reduced-motion；不能仅凭采用 Radix 认定全站可访问性通过。
9. AppShell：保留开发/运营/物流/仓储既定分组，补系统上下文记忆、全局页语义、租户/店铺上下文、权限过滤。全局搜索的 ⌘K 需真实行为或去掉提示。
10. 工程：统一状态 token 的 fg/bg/border；规范 spacing/density；完成 UI lint，并为响应式、取消、键盘和状态映射加行为回归。

### P2：体系化完善

- Brand 区增加 Logo 三种 tone、全部尺寸、深浅底、16/24px 识别、安全区、最小尺寸、禁用示例和资产下载。
- 保留 Star connection 的节点汇聚表达；将四节点与四个当前部门的关系作为产品叙事而非永久品牌约束。
- 增加页面模板：订单工作台、库存异常、财务对账、平台连接、批量任务。
- 排版采用页面标题/区块标题/正文/辅助/数值角色；保持 13–14px 正文，通过行高和留白控制密度，建议舒适/紧凑两档及独立仓储触控档。
- 图表明确币种、单位、日期时区、统计范围、数据更新时间；提供文字摘要/表格与非颜色区分。
- Styleguide 增加目录锚点、使用边界、API、状态矩阵、可操作示例及变更记录。

## 与业务文档的直接关联

- overview.md：多平台订单、库存、履约、多币种结算闭环 → 多维状态、跨模块入口、平台/店铺筛选。
- data-flow.md：异步事件、幂等、库存版本锁、汇率快照 → 处理中/部分成功、失败项重试、冲突提示、数据新鲜度与汇率明细。
- multi-tenancy.md 和 security.md：租户隔离、RBAC、资源范围、审计 → 租户上下文、权限态、脱敏、操作记录。
- 四系统修订日志：用户已确认职能分组及紧凑偏好 → 保留命名与框架，改进任务流与密度策略。
- visual-spec.md §9：单色品牌及中心负空间 → 保留现有方向，补完整品牌使用规范。

## 验证限制

没有实际业务数据或完整业务页联调，未做完整读屏、全部断点或后端安全测试。P0 是上线门槛而非已确认事故。此记录为建议，未实施设计或源码变更。

## 标准参考

- https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html
- https://www.w3.org/WAI/ARIA/apg/patterns/tabs/
