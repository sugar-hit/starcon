# 2026-09-25: 视觉规范迁移 — 从能耗项目 PIONEERING V1.0 → starcon ERP

## 摘要

把 PIONEERING(能耗诊断管理系统, 跨境 SaaS 节能管理)的 V1.0 视觉规范手册整体迁移到 starcon,适配跨境电商 ERP 场景。新建 `docs/design/` 目录承载视觉规范,作为前端落地时的**约束前置**——starcon 还没有任何前端代码,先定规范再写代码。

## 决策 / 结论

1. **整体沿用 PIONEERING 的视觉风格**——Vercel/Geist 调性、黑白灰主色、14 档字号、shadcn-style 组件,这些在能耗项目里已经被反复验证,直接搬过来
2. **删除 `--energy` 能源绿 token**——ERP 没有"节能"语义,加了只会带来认知噪声
3. **StatCard 趋势映射改双向**——能耗项目单一反向(涨=坏),ERP 必须支持正负向(营收涨=绿 vs 退款涨=红)。组件 API 加 `polarity: positive | negative | neutral` 属性
4. **图表色板支持多指标**——能耗项目突出单指标对比,ERP 报表多指标(订单/库存/营收/退款),色板扩展到 4 色:主蓝 + 警示红 + 正向绿 + 中性灰
5. **侧栏导航按 ERP 领域重组**——能耗 7 组 → ERP 8 组:工作台 / 订单 / 库存 / 商品 / 履约 / 财务 / 数据 / 设置
6. **品牌色 `--brand` 暂保留蓝**——后续 starcon 有独立品牌时改 HSL 值,**不改 token 名**
7. **新增 `docs/design/` 顶级目录**——AGENTS.md 和 docs/README.md 已同步登记

## 涉及文件

### 新增

- `docs/design/README.md` — 设计文档目录索引
- `docs/design/visual-spec.md` — 主视觉规范手册 V1.0(2 万字, 10 节)

### 修改

- `docs/README.md` — 增加 `design/` 行
- `AGENTS.md` — 沟通上下文表 + 目录速记表新增 `docs/design/` 入口
- `CHANGELOG.md` — Unreleased 增加视觉规范条目

## 关键 trade-off / 讨论

### 完全照搬 vs 适度适配

- **完全照搬** = 100% 复用 PIONEERING 颜色 / 字号 / 圆角 / 阴影,starcon 立刻有视觉规范可用
- **适度适配** = 改品牌色 / 删能源绿 / 加多指标支持 / 改导航
- **选适度适配**——完全照搬会让 starcon 看起来像能耗项目,即便色板一样,语义会混乱;适度适配保留审美一致,但语义对得上 ERP

### 视觉规范要不要现在写

- 选**现在写**——理由:starcon 第一个前端模块(`apps/web`)很快会开工,如果规范滞后,前端 agent 各自发挥,后续统一成本指数级上升
- "约束前置 > 约束后置"是核心纪律

### 视觉规范归属 docs/ 还是代码仓库

- 选 `docs/design/`(markdown 规范文档),不是 `packages/ui/` 下的代码
- 理由:规范是**约束层**,代码是实现层。前端代码还没建,规范先建。

### 双向趋势映射要不要 API 暴露

- 选**暴露**(`polarity` 属性),不暴露则默认 `positive` 兜底
- 理由:不暴露的话,业务开发要在每个 StatCard 上写颜色逻辑,违反"颜色全部走 token"原则

## 后续待办

### 短期(下一个迭代)

- [ ] 当 `apps/web/` 真正落地时,把 `docs/design/visual-spec.md` § 9 的"单一真相源"映射到实际文件路径
- [ ] 实现 `packages/ui/` 组件库骨架:Button / Card / Tabs / Modal / Input + 业务组件 SectionCard / StatCard / Badge / Segmented / EmptyState
- [ ] StatCard 组件实现 `polarity` 属性
- [ ] Logo 组件先用占位(starcon 文字 + 跨境电商 ERP 副标),后续替换
- [ ] 写 ADR: "前端框架选型 (Next.js App Router)" —— 视觉规范是 Next.js 的,框架选 Next.js 是合理推论但仍需 ADR 留痕

### 中期

- [ ] 真实落地第一个页面:工作台 Dashboard,作为规范的首个验证场
- [ ] 截图入库 `.tmp-screenshots/` 做视觉对齐参考(沿用 PIONEERING 的做法)
- [ ] 跟踪 PIONEERING 项目后续规范更新,看是否要同步迁移

## 关联

- AGENTS.md 更新:[`AGENTS.md`](../../AGENTS.md)
- 视觉规范手册:[`docs/design/visual-spec.md`](../../design/visual-spec.md)
- 设计文档索引:[`docs/design/README.md`](../../design/README.md)
- 上次会话语档:[`2026-09-25-folder-structure-and-repo-bootstrap.md`](2026-09-25-folder-structure-and-repo-bootstrap.md)