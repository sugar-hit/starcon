import { NextRequest, NextResponse } from 'next/server';

const cookieName = 'starcon_session';
const legacyCookieName = 'starcon_catalog_session';

/**
 * Upstream routing.
 *
 * <p>Each backend service owns a prefix and its own database; the browser never talks to them
 * directly. A path is only proxied when it matches a service's allowlist — unknown paths must 404
 * here rather than being forwarded, so a typo cannot reach a service that would treat it as valid.
 *
 * <p>The full path is forwarded upstream, prefix included: the services expose their own namespace
 * (`/identity/roles`, `/catalog/products`) and the browser's /api mount is only a local alias.
 */
const services: readonly {
  prefix: string;
  pattern: RegExp;
  env: string;
  fallback: string;
}[] = [
  {
    prefix: 'auth/',
    env: 'IDENTITY_URL',
    fallback: 'http://127.0.0.1:8081',
    pattern: /^(login|logout|session|memberships|switch)$/,
  },
  {
    prefix: 'identity/',
    env: 'IDENTITY_URL',
    fallback: 'http://127.0.0.1:8081',
    pattern:
      // audit 是 17.5.3.1 审计日志：列表、变更详情与 CSV 导出。
      // 导出走 GET + query（与列表同一套条件），所以 allowlist 只需放行读路径。
      // operation-log 是 17.5.3.2 操作日志：检索、单条详情、CSV 导出与链路关联视图。
      //   trace/{requestId} 放行但**不接 uuid 段**——请求 ID 可能是网关传来的非 UUID 串，
      //   用字符集而不是 {uuid} 段来界定。purge 是 POST 的留存期清理，
      //   同样在读路径正则里放行（前端页面只读，清理动作留给运维脚本调用）。
      // tenants 是 17.1.1.1 租户列表：只有 tenants 与 tenants/current 两个形状，
      //   **刻意不接 {uuid} 段** —— 按任意 tenant id 读取租户不是这个模块该有的能力，
      //   可切换集合只能由服务端从成员关系推导（实体目录 §4 的窄权限路径）。
      // department-leaders 是 17.2.1.2 部门负责人：负责人清单（没有负责人的部门也在列，
      //   因为它们要在审批流里配兜底）。departments/{id}/leader 下面挂 leader、proxies、
      //   grants 与 approval-chain 四类**已实现**的子资源：多放一段会让一个还不存在的
      //   路径看起来像已交付，所以逐段列出而不是一条通配。
      // hierarchy-authorizations 是 17.1.1.3 层级授权：授权记录的增改查与判定。
      //   check 放在 {uuid} 之前判别（第一段是字面量，不是 UUID），两条不冲突；
      //   {id}/revoke 是撤销（POST，见下），撤销是审计事件所以不是 DELETE。
      // accounting-books 是 17.1.2.2 本位币与账簿：账簿挂在**法人**上，所以写入形状是
      //   legal-entities/{uuid}/accounting-books（POST 新建），而更新/删除按账簿自身 id 寻址：
      //   accounting-books/{uuid}。两个前缀第一段不同，不会互相吃掉。
      // offboarding 是 17.2.2.3 离职与停用：只登记「停用」，不提供任何 DELETE，
      //   所以这一支没有删除动作分支。四种形状分别是案卷本身、撤销、
      //   责任转移条目（登记 / 执行转移）、回收条目（登记 / 执行回收），
      //   后两者的动作后缀各只认一个，多一层仍然 404。
      //   第一段 offboarding 是字面量，与 members/{uuid} 靠首段区分，不会互相吃掉。
      // hierarchy-authorizations 是 17.1.1.3 层级授权：授权记录的增改查与判定。
      //   check 放在 {uuid} 之前判别（第一段是字面量，不是 UUID），两条不冲突；
      //   {id}/revoke 是撤销（POST，见下），撤销是审计事件所以不是 DELETE。
      // accounting-books 是 17.1.2.2 本位币与账簿：账簿挂在**法人**上，所以写入形状是
      //   legal-entities/{uuid}/accounting-books（POST 新建），而更新/删除按账簿自身 id 寻址：
      //   accounting-books/{uuid}。两个前缀第一段不同，不会互相吃掉。
      // business-parameters 是 17.5.1.1 业务参数：三种形状分处同一前缀下——
      //   business-parameters（参数码表）/effective（生效值解析）/history（变更历史）
      //   都是单个字面量子路径；overrides 是集合读 + POST + {uuid} 的 PUT/DELETE；
      //   freezes 是 POST，读取是 {documentType}/{documentNo} 两段普通字符串。
      //   两段路径里都不含横杠，与 overrides 后只接一段 [0-9a-f-]+ 的形状不会互相吃掉。
      // numbering 是 17.5.1.2 编号规则：entities（单据实体码表）/rules（集合读 + POST）/
      //   rules/conflicts（冲突检测）/rules/{uuid}（GET·PUT·DELETE）/rules/{uuid}/next（取号）
      //   /preview（POST 预览）/sequences（计数器）/issued（已发放编号）。
      //   conflicts 与 {uuid} 靠第一段是字面量还是 UUID 区分；next 与 {uuid}/preview 不冲突。
      // approval-flows 是 17.4.1.1 审批流定义：approval-flows 集合读 + POST 新建；
      //   document-types / registrations / active / positions 是字面量子路径（**刻意不接 id 段**——
      //   那是读出厂码表与本租户注册情况，不按 id 寻址）；
      //   {uuid} 是单条流程的 GET·PUT·DELETE，其下的 validation 是结构体检（GET）；
      //   positions 下面挂 {uuid} 是岗位的 PUT/DELETE（岗位是被节点引用还是被删，按 id 寻址）。
      //   字面量段一律排在 [0-9a-f-]+ 之前判别：首段是字面量还是 UUID 两者不会互相吃掉。
      //   同样**没有**额外动作段：审批实例的发起与处理是 17.4.1.2 / 17.4.2.1 的职责，
      //   这里放行一个不存在的形状只会让界面以为已交付。
      // approval-rules 是 17.4.1.2 审批规则：approval-rules 是规则集集合读 + POST 新建；
      //   {uuid} 是规则集的 GET·PUT·DELETE，其下的 validation 是结构体检（GET）；
      //   effective-policy 是某单据类型的生效策略读出口，evaluate 是对已保存规则的只读试算（POST）。
      //   字面量段（effective-policy / evaluate）排在 [0-9a-f-]+ 之前判别，两类不会互相吃掉。
      //   同样**没有**审批实例相关的动作段：触发规则只产出规则集与生效策略，
      //   真正发起 / 处理审批是 17.4.2.1 的职责，放行一个不存在的形状只会让界面以为已交付。
      // my-approvals 是 17.4.2.1 待我审批：my-approvals 是列表读（筛选/排序/分页全在服务端）；
      //   capabilities 是对接状态读出口；batch 是批量审批/转交（POST）；
      //   {uuid} 是单条待办（GET）；其下 decide（POST 审批）与 transfer（POST 转交）两个字面量段。
      //   字面量段一律排在 [0-9a-f-]+ 之前判别，首段是字面量还是 UUID 两者不会互相吃掉。
      // approval-instances 是 17.4.2.1 的提交入口（POST）：各业务系统登记待审批用，
      //   与上面那些只读页面同属一次交付。
      //   **刻意不放行 approval-instances/{uuid}**：没有「按 id 取单条实例」这个端点。
      // approval-records 是 17.4.2.2 审批记录：集合读（筛选/排序/分页全在服务端）；
      //   statistics / capabilities / export 三个**字面量子路径**排在 [0-9a-f-]+ 之前判别；
      //   by-actor/{actorId} 是按人视角，第一段 by-actor 同样是字面量，排在 {uuid} 之前；
      //   {uuid} 是单条实例的完整链路（GET），字面量段与 UUID 段两类不会互相吃掉。
      //   **刻意不放行任何写动作段**：审批记录只读（上游只给 approval_instance_action 授
      //   SELECT,INSERT，从不授 UPDATE/DELETE），放行一个不存在的 POST/PUT/DELETE
      //   只会让界面以为已交付。
      // onboardings 是 17.2.2.2 成员开通与入职：列表、开通（创建）与完成入职。
      //   形状逐字列出：onboardings 是集合读 + POST 开通；
      //   onboardings/{id}/complete 是完成入职的状态流转（POST）。
      //   **刻意不放行 onboardings/{id}**：后端没有「按 id 取单条入职记录」这个端点，
      //   放行它会让界面以为已交付——放行不该存在的路径比 404 更糟。
      /^(permissions|owner-transfer|legal-entities(?:\/[0-9a-f-]+)?|legal-entities\/(?:[0-9a-f-]+\/accounting-books|references)|(?:roles|members|departments|children)(?:\/[0-9a-f-]+)?|tenants(?:\/current)?|audit(?:\/(?:export|[0-9a-f-]+))?|operation-log(?:\/(?:export|trace\/[0-9a-zA-Z-]+|[0-9a-f-]+))?||approval-records(?:\/(?:statistics|capabilities|export|by-actor\/[0-9a-f-]+|[0-9a-f-]+))?|hierarchy-authorizations(?:\/check|\/[0-9a-f-]+(?:\/revoke)?)?|accounting-books(?:\/[0-9a-f-]+)?|business-parameters(?:\/(?:effective|history|overrides(?:\/[0-9a-f-]+)?|freezes(?:\/[^/]+\/[^/]+)?))?|numbering(?:\/(?:entities|preview|sequences|issued|rules(?:\/conflicts|\/[0-9a-f-]+(?:\/next)?)?))?|approval-flows(?:\/(?:document-types|registrations|active|positions(?:\/[0-9a-f-]+)?|[0-9a-f-]+(?:\/validation)?))?|approval-rules(?:\/(?:effective-policy|evaluate|[0-9a-f-]+(?:\/validation)?))?|my-approvals(?:\/(?:capabilities|batch|[0-9a-f-]+(?:\/(?:decide|transfer))?))?|approval-instances|onboardings(?:\/[0-9a-f-]+\/complete)?|department-leaders|departments\/[0-9a-f-]+\/(?:leader(?:\/(?:proxies|grants)(?:\/[0-9a-f-]+)?)?|approval-chain)|offboarding(?:\/[0-9a-f-]+(?:\/revert|\/responsibility-items(?:\/[0-9a-f-]+(?:\/transfer)?)?|\/reclaim-items(?:\/[0-9a-f-]+(?:\/reclaim)?)?)?)?|legal-entities\/(?:[0-9a-f-]+\/(?:accounting-books|ledger-books|default-book)|references)|accounting-books(?:\/[0-9a-f-]+(?:\/fact-assignments(?:\/[0-9a-f-]+)?)?)?)$/,
  },
  {
    prefix: 'catalog/',
    env: 'CATALOG_URL',
    fallback: 'http://127.0.0.1:8082',
    pattern:
      // skus 是 3.1.2.1 SKU 列表：分页检索、能力声明、批量操作与 CSV 导出。
      // unit-conversion 是 3.1.2.4 单位与换算：单位字典、SKU 换算版本、基础数量换算与单据快照。
      //   units 后面可选一段 id（字典行的更新/删除）；skus/{id} 取面板，skus/{id}/conversions 建换算版本；
      //   snapshots 后面不接 id —— 按 ?documentRef= 查询，快照是按单据号寻址的不可变行。
      // spec-axis 是 3.1.3.1 规格轴定义：租户级规格轴字典、选项、SPU 绑定与类目规则。
      //   axes/{id}/options 按 ?value= 删除，不接第三段 id；category-rules 用 query 传 specAxisId。
      // variant-combination 是 3.1.3.2 组合唯一性校验：组合清单、预校验、矩阵草稿。
      //   spus/{id} 下面挂 combinations / check / drafts；drafts/{draftId} 可选一段 id（详情与作废）。
      // attributes 是 3.2.1.1 属性字典：租户级字典分页、概览、选项集合与类目规则。
      //   与 products/{id}/attributes（3.2.2.1 商品的 SPU 属性）**不是同一个前缀**：
      //   后者第一段是 UUID，这里第一段是字面量 attributes，字典页不需要带商品 id。
      //   options 按 ?value= 寻址，不接第三段 id；category-rules 用 query 传 category/attributeId。
      //   `options`（不带 id 段）是 3.2.1.2 的**跨属性选项总览**：
      //   它与 `{uuid}/options` 靠第一段区分——一个是字面量 options，一个是 UUID。
      //   `options/lifecycle` 与 `options/order` 是 3.2.1.2 的启用停用与批量重排，两者都用 PUT。
      // sku-attribute-override 是 3.2.2.2 SKU 属性覆写：按 SKU 寻址的有效值解析面板。
      //   与 SkuCodeController / UnitConversionController 同形（`<模块>/skus/{id}`），
      //   所以第一段是字面量 sku-attribute-override，不是 products —— 两者不冲突。
      //   下面挂 attribute-overrides/{attributeId}（PUT 覆写、DELETE 取消覆写）
      //   与 attribute-overrides/{attributeId}/clear（PUT 显式清空）。
      // categories 是 3.3.1.1 分类树管理：多棵独立的分类树、多级层级、拖拽、启用停用、
      //   多语言分类名与 SPU 主/辅分类。
      //   第一段是字面量 categories，与 `products/{uuid}`（UUID）、`attributes`（字面量）
      //   三者互不冲突：products/attributes 首段是 products，其余两个首段各不相同。
      //   trees/{id}/nodes 后面**不接第三段 id** —— 一次取回整棵树（结构 + 路径串 + 商品数
      //   来自同一次查询，拆成多个端点会让前端拿到互相不一致的三个瞬间）。
      //   nodes/{id}/move 是拖拽落点（PUT），nodes/{id}/enabled 是启用停用（PUT）。
      // category-mappings 是 4.2.3.1 平台类目映射：内部分类 ↔ 平台类目的**多对多**映射，
      //   带 platform + site 两个独立维度，外加「平台某属性对应内部哪个属性」的规则。
      //   刻意**不挂 categories 之下**（虽然语义相邻）：分类树管「类目本身是什么」，
      //   本模块管「这个类目在某个平台某个站点对应哪个平台类目」，后者随平台改版频繁变动，
      //   挂在同一前缀下会让「改类目名」和「改平台映射」挤进一个页面。首段字面量不同，不会互吃。
      //   字面量子路径（overview / platform-sites / attribute-options / resolve）排在 [0-9a-f-]+ 之前判别。
      //   resolve 是按「内部分类 × 平台 × 站点」解析刊登目标与必填属性清单（4.2.1.1 表单生成的输入）。
      //   attributes/{ruleId}/enabled 是规则级启用停用；default 与 enabled 是映射级的两个动作段。
      //   spus/{id} 是 SPU 的主分类与辅助分类（GET 读、PUT 整体替换）。
      //   attribute-requirements 是 3.3.1.2 类目属性要求，按类目寻址：
      //   overview 是覆盖率概览；{categoryId} 是配置视图（GET）与规则保存（POST，同一路径两种方法）；
      //   {categoryId}/effective 只要生效规则集；entry-form-schema（POST 生成录入表单 schema）
      //   与 validate（POST 保存前逐字段校验）是本模块的两个输出；
      //   {categoryId}/{attributeId} 同一路径 DELETE 删规则、PUT .../enabled 启停。
      // brands 是 3.3.2.1 品牌管理：租户级品牌字典 + 授权凭证 + 刊登判定。
      //   第一段是字面量 brands，与 `products/{uuid}`（UUID）不冲突。
      //   overview / candidates 是只读聚合；listing-eligibility 有两条路径——GET 单个品牌
      //   与 POST 批量（id 数量由调用方决定，放请求体而不是拼进 URL，避免撞长度上限）。
      //   {id}/authorizations 下面挂 {authorizationId} 与 {authorizationId}/enabled（撤销，不是删除）。
      // unit-groups 是 3.3.3.1 计量单位（字典视角）：单位组 + 组内换算版本 + 换算查询。
      //   与 3.1.2.4 的 unit-conversion **并存不合并**：后者是 SKU 视角的换算与单据冻结快照，
      //   前者是字典视角的单位组，两条路径共用同一份 unit_of_measure 单位字典。
      //   {id}/conversions 下面挂 {conversionId}（DELETE 删已闭合的历史版本；
      //   当前开放生效的那一版后端会拒，否则等于静默让该单位不可换算）。
      //   convert 是 POST（带请求体），不是 GET + query——试算的数量与基准日放 body 更合适。
      // media 是 3.4.1.1 媒体库：素材上传与版本、分类标签、引用计数、失效检测。
      //   与 `records/assets` **不是同一个东西**：后者是 3.1.x 的通用记录面
      //   （落 product_asset，形状是 title/notes/reference），不是媒体表。
      //   overview / verify-sweep / references 是只读或聚合路径；
      //   {id} 下面挂 versions / references / integrity-checks / enabled；
      //   references 下面可再挂一段 {referenceId}（DELETE 解除引用）；
      //   tags 是标签字典，tags/{tagId} 后面可挂 enabled。
      //   上传走 POST + multipart（文件是字节，不能走 JSON），路径与其它 POST 同形。
      //   processing 是 3.4.1.2 图片处理：处理配方（目标平台规格）+ 异步处理任务。
      //   overview / recipes / jobs / assets/{id}/history 是聚合与只读路径；
      //   recipes/{id} 后面可挂 enabled；jobs/{jobId} 下面挂 items（条目与产物）与 cancel（请求取消）。
      //   提交走 POST /processing/jobs（JSON：配方 id + 素材 id 列表），语义是「已受理」
      //   而不是「已完成」——像素加工在线程池里跑，前端轮询任务详情看进度。
      // titles 是 3.4.2.1 标题库：标题模板（变量占位）+ 词根字典 + 批量生成批次 + 候选选用。
      //   与 `media`（3.4.1.x）不是一回事：那是二进制素材资产，这里是文本文案资产，
      //   被 04 刊登同时引用但维护节奏不同（素材跟拍摄批次走，文案跟平台规则走）。
      //   templates/{id} 与 keywords/{id} 后面各可挂 enabled；
      //   generations/{id} 下面挂 skus（逐 SKU 结果与未产出原因）；
      //   records/{id} 下面挂 select / deselect / reject（选用状态机：
      //   同一 SKU 同一平台只能选用一条，所以要能取消与淘汰）。
      //   records/export 是 CSV 导出，走 GET + query 而不是 POST——导出结果与当前筛选完全一致。
      //   提交走 POST /generations，语义是「已生成」而不是「已受理」：标题生成是纯字符串计算
      //   （重复度比较范围收在单个 SKU 内），几十毫秒就完事，所以返回时候选已经落库，
      //   前端**不轮询**。
      // descriptions 是 3.4.2.2 描述与卖点库：内容模板 + 多语言译文 + 卖点短语库 + 批量填充。
      //   与 `titles` 并列而不是合并：那边是**有平台硬字符上限的短文本**（矛盾是重复铺货判定），
      //   这边是**无字符上限的长文本**（矛盾是「变量缺失必须报告」）。排版约束不同，路由分开。
      //   templates/{id} 下面挂 enabled、translations（可只到二级，也可带 {translationId}）
      //   与 translation-coverage（GET + locale query：这几种语言能不能直接填）；
      //   phrases/{id} 与 renders/{id} 后面可挂 enabled / skus / missing-variables；
      //   translations/{id} 是**不带模板 id** 的一级寻址（与 phrases/{id} 同一形状：
      //   删一条译文时前端手上只有译文 id，而 URL 的首段是字面量 translations 而非 UUID）；
      //   sections 与 sections/export 分别是段落列表与 CSV 导出（GET + query，
      //   导出结果与当前筛选完全一致）。
      //   提交走 POST /renders，语义是「已填充」而不是「已受理」。
      // bundles 与 versions 是 3.5.1.1 套装版本管理：套装档案 + 版本（草稿/已发布）+ 生效区间 + 组件清单。
      //   与 titles / descriptions / translations 并列而不合并：那几套的「版本」是**文案版本**，
      //   这里的「版本」是**套装版本**，两者名字一样而语义完全不同，合并会让「v2 指哪个版本」无法回答。
      //   bundles 下面挂 overview（概览）、{id}/status（归档启用）、{id}/effective（**当前生效版本的时刻查询**，
      //   04 刊登与 09 订单取版本走的就是它）、{id}/versions（某个套装的版本列表 / 建草稿版本）；
      //   versions 是跨套件的版本列表与单版本操作：publish（发布）、close（**向前关闭生效区间**，
      //   已发布版本上唯一合法的写操作）、components（组件清单的读与整体替换）。
      //   虚拟套装不持有独立库存：扣的是组件库存（实体目录 §3.4），本模块不写库存。
      // components 与 bundle-skus 是 3.5.1.2 组件维护：跨版本组件清单 + 逐行增删改 +
      //   循环引用检测。与 versions 下的 components **并列而不合并**：
      //   PUT /versions/{id}/components 是 3.5.1.1 的「整体替换清单」，
      //   POST /versions/{id}/components 是本模块的「加一行」，两者语义不同必须能被 URL 区分；
      //   PUT|DELETE /components/{id} 改/删单行（带行级乐观锁 rowVersion）。
      //   components 下的 overview 是组件工作台概览，{id} 是单行组件详情。
      //   versions/{id}/component-check 是**结构化校验结果**（一次列全循环/数量非法/组件不存在），
      //   与「保存时报错」分开：保存只给一条原因，校验让运营一次改完而不是反复提交试错。
      //   bundle-skus 是「哪些 SKU 代表哪些套装」这条指向 —— 嵌套套装在数据上靠它表达，
      //   没有它「套装 A 含套装 B」无法表达，循环也就无从发生；改指向同样要过循环检测。
      // boms / bom-versions / bom-components 是 3.5.2.1 BOM 版本管理：物料清单（成品由哪些组件组成）
      //   的版本。与 bundles / versions **并列而不合并**：名字都叫「版本」但语义不同 ——
      //   那边是「几个 SKU 捆在一起卖」（套装：虚拟扣组件、不持有独立库存），
      //   这边是「这个成品要领哪些料」（BOM：产实物成品、有自己的成品 SKU）。
      //   合并会让「v2 指哪个版本」在 URL 上无法回答。
      //   boms 下面挂 overview（概览）、{id}/status（归档启用）、{id}/effective（**当前生效版本的
      //   时刻查询**，06 采购建议与 08 加工单取版本走的就是它）、{id}/versions（建草稿版本）。
      //   bom-versions 是跨 BOM 的版本列表与单版本操作：publish（发布）、close（**向前关闭生效区间**，
      //   已发布版本上唯一合法的写操作）、copy（版本复制：整份复制成新草稿，用量与损耗率都带走）、
      //   components（组件清单的读与整体替换）、requirement（**单层**需求量，规则 R7，不取整）。
      //   bom-components/usage 是**倒排查询**：某个 SKU 被哪些 BOM 版本当组件用着 ——
      //   改用量之前要先看这个方向。它不接 id 段（按 ?skuId= 寻址，首段是字面量 bom-components）。
      //   多级递归展开不在本模块：那是 3.5.2.2（BOM 展开）的独立职责。
      // bom-explosions 是 3.5.2.2 BOM 展开：**多层递归展开树**（子 BOM 当作组件继续往下走）、
      //   按仓库过滤、需求量/可用/在途/缺口，以及**展开运行记录**（读与列表）。
      //   展开结果会落库成一条运行记录，因为 06 的采购单引用的是**某一次展开的 id** ——
      //   「现在的 BOM × 现在的库存」随时会变，单子发出去之后就无法解释「当时为什么按这个数下单」。
      //   与 bom-versions 并列而不合并：那边是「物料清单长什么样」，这边是「按它生产 N 个成品缺多少料」。
      //   它只有 {id} 段（读与列表），没有 overview 之类的子路径 —— 展开是一次动作不是一个子资源。
      //   循环引用返回 409（不放行任何路径去绕过它）。
      // parties 是 3.6.1.1 往来单位档案：业务主体（企业或个人）+ 角色指派
      //   （供应商 / 客户 / 承运商）+ 状态流转 + 软删除。
      //   与 products/** 并列而不挂在它下面：往来单位不是商品的属性，而是与商品平级的主数据
      //   —— 06 采购、11 售后、12 财务也引用它，而那些都不是商品页面。
      //   第一段是字面量 parties，与 products/{uuid}、attributes、brands 互不冲突。
      //   overview / candidates 是只读聚合（candidates 是 06/11/12 的下拉数据源，默认只给可用的）；
      //   {id}/status 是状态流转（进出拉黑要理由，**走独立端点**而不是复用 PUT {id}，
      //   否则「改个名字」也会被迫填理由）；
      //   {id}/roles 下面可挂 {roleId}（PUT 改状态备注、DELETE 解除指派）——
      //   角色指派与主体是「整体替换 + 单条增删改」两种语义并存，所以两条路径都要放。
      //   {id}/role-profiles 是 3.6.1.2 的汇总读出口（三个身份一次拿全，避免详情页拼出旧数据）；
      //   {id}/{supplier|customer|carrier}-profile 是 3.6.1.2 的角色档案读写（GET / PUT / DELETE），
      //   字面量三个而不是一条通配 —— 放行 {id}/xxx 的任意子段会让「一个还没实现的子路径」
      //   看起来像已交付（那正是本文件之前对这三条刻意不放行的原因）。
      // role-profiles 是 3.6.1.2 角色档案台账：overview（覆盖率 + 两个脏数据探针）、
      //   options（词表，与后端同源）、列表（按身份分页 + 缺档案筛选）。
      //   它与 parties 并列而不是挂在 {id} 下面：运营问的是「哪些供应商还没建档案」，
      //   主体不固定，所以那是集合资源。
      // supplier-quotes 是 3.6.2.1 供应商报价：报价台账 + 蓝图点名的「有效报价」输出。
      //   第一段是**字面量** supplier-quotes，与 products/{uuid}、parties、role-profiles
      //   各自首段都不同，不存在冲突。
      //   子路径逐字列出而不是一条通配：
      //     overview / options  —— 概览与词表（只读聚合）
      //     effective           —— 蓝图输出「有效报价列表」（歧义时 selected 为 null 并逐条标状态）
      //     effective/resolve   —— 06 采购带出价格走它，歧义时**必须 409**（规则 R2）
      //     {id}                —— 单条报价的读写（GET / PUT / DELETE）
      //     {id}/tiers          —— 阶梯价明细（编辑面板单独刷新阶梯时用）
      //   放行 {id} 之后任意一段会让「一个还没实现的子路径」看起来像已交付，
      //   所以 {id} 只带 /tiers 一个字面量子段。
      // translations 是 3.4.3.1 商品多语言内容：SPU/SKU 的多语言名称与描述 + 语言需求 + AI 建议。
      //   与 descriptions 并列而不是合并：那边带 locale 的是「描述模板与其渲染段落」的多语言，
      //   这边是「商品档案本身」的多语言（V22 文件头有完整边界说明）。
      //   locales/requirements 与 locales/sync 是「需要哪些语言由店铺启用站点推导」的一侧：
      //   sync 走 POST，02 渠道未接入时返回 409 并写明归属，不是一个 HTTP 错误页。
      //   spus/{id} 与 skus/{id} 是商品翻译工作台（可带 entries 建译文）；
      //   entries 下的 status 是人工校对（PUT，会记校对人与校对时刻），entries/export 是 CSV 导出；
      // export-sources 与 export-jobs 是 3.8.2.1 导出：能力声明（字段/排序/筛选词表）、
      //   导出记录（条件、字段、行数、时间、操作人）、以及 {id}/file 下载与 {id}/cancel 作废。
      //   第一段是字面量 export-*，与 products/{uuid}、import-jobs 三者互不冲突。
      //   {id} 后面最多再挂一段 file 或 cancel，不放行更深层 —— 放行一个还没实现的子路径
      //   会让它看起来像已交付。
      //   suggestions/{id}/adopt 与 /reject 是建议处置（POST，处置是终态）；
      //   batches 可带 {batchId} 读批次详情（逐条结果）。
      // import-jobs 是 3.8.1.1 导入向导的任务面，3.8.1.2 的校验报告挂在它的 report 子树上
      //   （报告不是独立资源：它是「某一个导入任务那一轮预校验结论」的视图，
      //   挂在任务下之后租户隔离只需守住一条父路径，父任务读不到则子报告读不到）。
      //   {id}/report 是总览（第一次 GET 顺带生成，之后原样返回快照 + 过期标记），
      //   同一个 POST 是显式重新生成；
      //   {id}/report/issues 是错误明细表（分页 + severity/category/rowNo 三种筛选，可组合）；
      //   {id}/report/exports 是导出的登记与下载（GET 列表 / POST 发起 / {exportId}/file 下载字节），
      //   文件走普通链接下载而不是 JSON，与模板下载同一处理方式。
      // completeness 是 3.9.2.1 完整度评分：评分台账 + 评分卡 + 缺失项清单 +
      //   批量补全入口 + 报关/认证声明读写 + 刊登/清关预检。
      //   第一段是字面量 completeness，与 products/{uuid}、parties、supplier-quotes
      //   各自首段都不同，不存在冲突。
      //   子路径逐字列出（不放行任意一段）：
      //     overview / options                     —— 概览与词表（六维度、分档、阈值）
      //     gaps / gaps/summary                    —— 缺失项清单（按缺失码聚合）与统计
      //     evaluations / evaluations/{id}          —— 评估快照（回答「当时为什么被拦住」）
      //     precheck/listing                       —— 4.2.3.2 刊登预检（POST，带 SPU 清单）
      //     precheck/customs                       —— 报关流程的清关预检（POST）
      //     customs/skus/{skuId}                   —— 报关属性声明（GET / PUT / DELETE）
      //     certifications/skus/{skuId}[/{certId}] —— 认证声明（一个 SKU 多条）
      //     SPU|SKU/{ownerId}                      —— 评分卡；主体类型写成字面量而不是
      //       {ownerType}，否则它会连带放行「一个还没实现的子路径」——
      // supply 是 06 采购与供应的**采购视角**供应商档案（6.1.1.1 列表 / 6.1.1.2 详情，
      //   catalog 服务，路由 /catalog/supply/vendors/**）。
      //   注意这整段此前**不在 allowlist 里**：6.1.1.1 交付时后端端点与页面都在，
      //   但浏览器侧请求会在代理层 404 —— 放行与否是「界面以为已交付」与「真的能调通」的区别。
      //   子路径逐字列出（不放行任意一段）：
      //     options / overview                   —— 6.1.1.1 清单与列表下钻
      //     {roleId}/profile                     —— 采购侧运营档案（PUT）
      //     {roleId}/deliveries[/{id}/void]      —— 到货事实（只追加 + 作废）
      //     {roleId}/quality-inspections[/{id}/void] —— 质检事实（只追加 + 作废）
      //     detail-options                       —— 6.1.1.2 词表
      //     {roleId}/panorama                    —— 6.1.1.2 全景
      //     {roleId}/purchase-history            —— 6.1.1.2 采购历史（分页 + 聚合）
      //     {roleId}/quotes                      —— 6.1.1.2 报价清单（只读）
      //     {roleId}/settlement-terms            —— 6.1.1.2 账期与开票（GET / PUT）
      //     {roleId}/qualifications[/{id}]       —— 6.1.1.2 附件资质（POST / PUT / DELETE）
      //   刻意**不**放行 6.3 采购单与 6.5 对账：那两个模块尚未交付，
      //   放行不该存在的路径比 404 更糟（界面会以为功能已交付）。
      //       放行不该存在的路径比 404 更糟：界面会以为功能已交付。
      /^(products(?:\/[0-9a-f-]+(?:\/(?:attributes|skus)(?:\/[0-9a-f-]+)?)?)?|attributes(?:\/(?:overview|category-rules|options(?:\/(?:lifecycle|order))?|[0-9a-f-]+(?:\/(?:options|category-rules))?))?|brands(?:\/(?:overview|candidates|listing-eligibility|[0-9a-f-]+(?:\/(?:listing-eligibility|enabled|authorizations(?:\/[0-9a-f-]+(?:\/enabled)?)?))?))?|parties(?:\/(?:overview|candidates|[0-9a-f-]+(?:\/(?:status|roles(?:\/[0-9a-f-]+)?|role-profiles|(?:supplier|customer|carrier)-profile|addresses(?:\/(?:defaults|candidates|[0-9a-f-]+(?:\/(?:usages|default|versions(?:\/[0-9]+)?))?))?|contacts(?:\/(?:default|candidates|[0-9a-f-]+(?:\/primary)?))?))?))?|categories(?:\/(?:overview|trees(?:\/[0-9a-f-]+(?:\/nodes)?)?|nodes\/[0-9a-f-]+(?:\/(?:move|enabled))?|spus\/[0-9a-f-]+|attribute-requirements(?:\/overview|\/[0-9a-f-]+(?:\/[0-9a-f-]+(?:\/enabled)?|\/(?:effective|entry-form-schema|validate))?)))|category-mappings(?:\/(?:overview|platform-sites|attribute-options|resolve|[0-9a-f-]+(?:\/(?:default|enabled|attributes(?:\/[0-9a-f-]+(?:\/enabled)?)?))?))?|records\/(research|compliance|assets)(?:\/[0-9a-f-]+)?|media(?:\/(?:overview|verify-sweep|references|tags|processing(?:\/(?:overview|recipes(?:\/[0-9a-f-]+(?:\/enabled)?)?|jobs(?:\/[0-9a-f-]+(?:\/(?:items|cancel))?)?|assets\/[0-9a-f-]+\/history)?)?)|[0-9a-f-]+(?:\/(?:versions|references(?:\/[0-9a-f-]+)?|integrity-checks|enabled))?|tags\/[0-9a-f-]+(?:\/enabled)?)?|skus(?:\/(?:capabilities|batch|export))?|unit-groups(?:\/(?:overview|convert|[0-9a-f-]+(?:\/(?:enabled|conversions(?:\/[0-9a-f-]+)?))?))?|unit-conversion(?:\/(?:units(?:\/[0-9a-f-]+)?|overview|skus\/[0-9a-f-]+(?:\/conversions)?|convert|snapshots))?|spec-axis(?:\/(?:overview|axes(?:\/[0-9a-f-]+(?:\/options)?)?|spus\/[0-9a-f-]+(?:\/axes(?:\/[0-9a-f-]+)?)?|category-rules))?|variant-combination(?:\/(?:overview|spus\/[0-9a-f-]+(?:\/(?:combinations|check|drafts(?:\/[0-9a-f-]+)?))?))?|sku-attribute-override\/skus\/[0-9a-f-]+(?:\/attribute-overrides\/[0-9a-f-]+(?:\/clear)?)?|titles(?:\/(?:overview|templates(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/enabled)?)?|keywords(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/enabled)?)?|generations(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/skus)?)?|records(?:\/export|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:select|deselect|reject))?)?))?|descriptions(?:\/(?:overview|templates(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:enabled|translation-coveragetranslations(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?))?)?|phrases(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/enabled)?)?|renders(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:skus|missing-variables))?)?|translations\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|sections(?:\/export)?))?|bundles(?:\/(?:overview|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:status|effective|versions))?))?|versions(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:publish|close|components|component-check))?)?|components(?:\/(?:overview|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}))?|bundle-skus(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|boms(?:\/(?:overview|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:status|effective|versions))?))?|bom-versions(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:publish|close|copy|components|requirement))?)?|bom-components\/usage|bom-explosions(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|import-templates(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/file)?)?|import-field-mappings(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|import-jobs(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:mapping|prevalidate|execute|cancel|rows|errors|report(?:\/(?:issues|exports(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/file)?))?))?)?|supplier-quotes(?:\/(?:overview|options|effective(?:\/resolve)?|[0-9a-f-]+(?:\/tiers)?))?|role-profiles(?:\/(?:overview|options))?|translations(?:\/(?:overview|locales(?:\/(?:requirements|sync|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/enabled)?))?|spus\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/entries)?|skus\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/entries)?|entries(?:\/export|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/status)?)?|coverage|suggestions(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:adopt|reject))?)?|batches(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?))|duplicate-scans(?:\/(?:overview|[0-9a-f-]+(?:\/candidates)?)?)?|duplicate-candidates\/[0-9a-f-]+(?:\/(?:decision|merge-preview|merge)?)?|export-sources|export-jobs(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:file|cancel))?)?|zombie-candidates(?:\/(?:overview|options|rules(?:\/[A-Za-z0-9_]+)?|evidence|reference-stats|deactivate|records|deactivation-targets))?|completeness(?:\/(?:overview|options|gaps(?:\/summary)?|evaluations(?:\/[0-9a-f-]+)?|precheck\/(?:listing|customs)|customs\/skus\/[0-9a-f-]+|certifications\/skus\/[0-9a-f-]+(?:\/[0-9a-f-]+)?|(?:SPU|SKU)\/[0-9a-f-]+))?|customs-attributes(?:\/profiles|\/skus\/[0-9a-f-]+(?:\/(?:submit|review))?|\/declaration-ready)|sku-merges(?:\/overview|\/by-candidate\/[0-9a-f-]+|\/by-sku\/[0-9a-f-]+|\/[0-9a-f-]+)?|description-templates(?:\/(?:overview|options|templates(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:enabled|sections))?)?|renders(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?))?|kb(?:\/(?:overview|options|entries(?:\/[0-9a-f-]+(?:\/(?:enabled|translations(?:\/[0-9a-f-]+)?|translation-coverage|rule-bindings(?:\/[0-9a-f-]+)?|usages))?)?|translations\/[0-9a-f-]+|rule-versions(?:\/[0-9a-f-]+)?|review-tasks(?:\/[0-9a-f-]+)?|usages|hit-rates))?|customs-destination-versions(?:\/effective|\/requirements|\/skus\/[0-9a-f-]+|\/[0-9a-f-]+\/publish)|customs-declaration\/resolve|supply\/vendors(?:\/(?:options|overview)|\/[0-9a-f-]+(?:\/(?:profile|panorama|purchase-history|quotes|settlement-terms|deliveries(?:\/[0-9a-f-]+\/void)?|quality-inspections(?:\/[0-9a-f-]+\/void)?|qualifications(?:\/[0-9a-f-]+)?))?)?)$/,
  },
  {
    // 02 渠道与店铺：平台目录、授权连接、店铺与站点。
    // health/reminders 属于连接健康，marketplace 挂在店铺下，transfer 是负责人转让。
    // platforms/{code}/enums 后面可选一段 id：租户映射覆盖按 id 删除。
    // oauth/* 是 2.2.1.2 授权向导：callback 由平台重定向直接打进来（浏览器导航，GET），
    // sessions 后面可选一段 id 与 shops/confirm/cancel。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    // warehouse-providers 是 10.4.2.1 海外仓连接：服务商目录、连接、仓账户、
    // 库存同步与外部库存观测（只读）。
    // 形状逐个列出：
    //   warehouse-providers                     服务商目录（集合读）
    //   connections / connections/{id}           连接读写
    //   connections/{id}/sync-paused             暂停/恢复同步（POST + query）
    //   connections/{id}/accounts                建仓账户（POST）
    //   accounts / accounts/{id}                 仓账户读
    //   accounts/{id}/inbound-stock-lines        登记服务商报送（POST）——适配器的真实输入
    //   accounts/{id}/sync                       触发库存同步（POST）
    //   sync-runs                                同步记录（集合读）
    //   accounts/{id}/observations               观测流水（集合读）
    //   accounts/{id}/observations/latest        各 SKU 最新观测
    // ⚠️ sync-runs 与 connections / accounts 是**兄弟**不是子路径：
    //    早先一版把它写进了 connections 的组里，warehouse-providers/sync-runs
    //    被静默拦成 404（typecheck/lint 全绿，只有真跑 proxy 才看得见）。
    // 动作段逐字列出而不是通配：多放一层会让「一个还不存在的动作」看起来像已交付。
    // 刻意**没有** DELETE 段：观测快照不可删（蓝图「不能覆盖」），连接/仓账户也不靠物理删收回。
    // 字面量段（latest）排在 [0-9a-f-]+ 之前判别，两类不会互相吃掉。
    // handover-batches 是 10.3.2.1 交运批次：候选池、按承运商+交运时间的组批预览、
    // 批次与逐件交接清单。形状逐个列出：
    //   handover-batches                     批次列表（GET）+ 组批（POST）
    //   handover-batches/capabilities        能力声明（未交付段 available=false）
    //   handover-batches/group-preview       组批预览（真 GROUP BY 读端点）
    //   handover-batches/candidates          候选池（上游 9.4.1.1 的只读投影）
    //   handover-batches/candidates/sync     同步候选池（唯一写入口）
    //   handover-batches/{id}                批次详情
    //   handover-batches/{id}/manifest       交接清单（法律凭据，追责时导出）
    //   handover-batches/{id}/timeline       事件时间线
    //   handover-batches/{id}/dispatch       登记交接（POST）
    //   handover-batches/{id}/receipt        承运商回执登记（POST）
    //   handover-batches/{id}/cancel         作废（POST）
    // carriers 是 10.1.1.1 承运商列表：承运商档案 + 可用性状态。
    //   carriers                           列表（GET，真分页 + 筛选）
    //   carriers/capabilities              能力声明（未交付段 available=false）
    //   carriers/overview                  全量概览（不受筛选与分页影响）
    //   carriers/sync                      同步承运商身份（POST，唯一写上游快照的入口）
    //   carriers/{id}                      详情（GET）/ 改物流侧档案（PUT）/ 撤下物流侧档案（DELETE）
    // 字面量子路径（capabilities/overview/sync）排在 [0-9a-f-]+ **之前**判别，
    // 与 handover-batches 同款次序：让没交付的形状（如 carriers/{id}/channels）
    // 继续 404，而不是被 id 段静默吃掉后看起来像已交付。
    // 刻意**不**放行 carriers/{id}/channels 之类 —— 那是 10.1.1.2 渠道配置的路径，
    // 模块未开发，代理层放开会让一个还不存在的模块看起来像已交付。
    // 字面量子路径（capabilities / group-preview / candidates）排在 [0-9a-f-]+ **之前**判别，
    // 否则会被当成一个 id 段静默吃掉 —— 只在真跑 proxy 时才看得见。
    // candidates/sync 是**两段**：candidates 后面多跟一个字面量 sync，
    // 刻意不带任何可选量 —— 多放一层会让「一个还不存在的动作」看起来像已交付。
    // 刻意**没有** DELETE 段：交接清单是法律凭据，交运批次不能靠物理删除收回。
    // 02.6.1.2 去重视图（webhook-dedup）与 webhook-receipts 同一处置，仅**新增**分支：
    // summary 排在 [0-9a-f-]+ 之前判别（同 webhook-receipts/summary 的次序），
    // 动作只有 claim 一个且不带任何可选量 —— 放行一个还不存在的动词比 404 更糟。
    pattern:
      /^(platforms(?:\/[A-Za-z0-9_]+\/enums(?:\/[0-9a-f-]+)?)?|connections(?:\/(?:reminders|[0-9a-f-]+(?:\/(?:credential|health|revoke))?))?|shops(?:\/[0-9a-f-]+(?:\/(?:marketplaces(?:\/[0-9a-f-]+)?|transfer))?)?|sync-cursors(?:\/[0-9a-f-]+(?:\/(?:pause|resume|rewind|trigger))?)?|sync-runs(?:\/[0-9a-f-]+(?:\/(?:complete|resolve))?)?|sync-failures(?:\/summary|[0-9a-f-]+\/replay)?|platform-operations(?:\/[0-9a-f-]+(?:\/(?:start|succeed|fail|unknown|verify))?)?|oauth\/(?:callback|sessions(?:\/[0-9a-f-]+(?:\/(?:shops|confirm|cancel))?)?)|warehouse-providers(?:\/(?:connections(?:\/[0-9a-f-]+(?:\/(?:sync-paused|accounts))?)?|sync-runs|accounts(?:\/[0-9a-f-]+(?:\/(?:inbound-stock-lines|sync|observations(?:\/latest)?))?)?))?|handover-batches(?:\/(?:capabilities|group-preview|candidates(?:\/sync)?|[0-9a-f-]+(?:\/(?:manifest|timeline|dispatch|receipt|cancel))?))?|carriers(?:\/(?:capabilities|overview|sync|[0-9a-f-]+))?|webhook-dedup(?:\/summary|\/[0-9a-f-]+(?:\/claim)?)?|webhook-endpoints(?:\/[0-9a-f-]+\/disable)?|webhook-receipts(?:\/summary|\/[0-9a-f-]+(?:\/(?:claim|complete))?)?|webhooks\/[A-Za-z0-9_-]+)?$/,
  },
  {
    // 07 库存：库存总览（7.1.1.1）、库存明细 bucket（7.1.1.2）、筛选下拉参照数据与超储阈值。
    // overview/capabilities/policies 是总览本身；warehouses/legal-entities/skus/quality-statuses
    // 是筛选下拉项。policies/{id} 后面接一段 id（更新与删除）。
    // buckets/bucket-actions 是 7.1.1.2 的明细读端点（纯读，不需要写权限）；
    // locations/lots 是逐级下钻的库位与批次下拉项，与其余参照数据同形。
    // sellable-policies 是 7.2.2.1 可售量口径：列表 / effective / pools / pools/{poolKey} / {id}
    // 五个读形状，calculation（POST 试算）、{id}/versions（换版）、{id}/retire（作废）三个动作。
    // transfers 是 7.5.1.1 调拨单：列表 / {id} / {id}/movements 三个读形状，
    // 以及 {id}/confirm、{id}/ship、{id}/receive、{id}/close 四个动作端点
    // （刻意**没有** DELETE 段：调拨单要靠分录与在途行回溯，不能物理删掉一条单据）。
    // cross-entity-transfers 是 7.5.2.1 跨法人调拨，**刻意不复用 transfers 前缀**：
    //   两者语义不同（那边货权前后同一个法人、不定价、无内部结算凭据），
    //   挂在同一前缀下会让「跨法人」看起来只是普通调拨的一个筛选项 ——
    //   而那正是蓝图点名要避免的「当作普通同仓调拨」。
    //   读形状：列表 / {id} / {id}/movements / {id}/settlement；
    //   动作形状：{id}/source-confirm 与 {id}/target-confirm（**两个独立端点**，
    //   让调用方在请求体里声明代表哪一方等于让它可以自称另一方）、
    //   {id}/ship、{id}/receive、{id}/close、{id}/cancel。
    //   同样**没有** DELETE 段：已发运的货权变更不能物理删除，只能结案。
    // in-transit 是 7.5.1.2 在途库存：调拨 / 头程 / 采购 / FBA 发运四类同一个口径。
    //   in-transit 同一路径两种方法：GET 清单 / POST 登记（登记是对上游发运事件的镜像）。
    //   alerts 与 netting 是两个字面量聚合路径（到达预警 / 补货可扣在途净额）。
    //   {legId} 是详情；{legId}/arrive 与 {legId}/discrepancy 是两个写动作，都带 expectedVersion 乐观锁。
    //   动作后缀**逐个列出**而不是一条通配：多放一层会让「一个还不存在的动作」看起来像已交付。
    //   刻意**没有** DELETE / PUT 段：在途腿靠事件时间线回溯，货已上路不该改历史。
    // 口径路径上刻意**没有** DELETE 段：作废是审计事件，档位行永久保留，
    // 放行一个删除形状会让"能不能物理删掉一条防超卖配置"看起来是能。
    // effective / pools / pools/{poolKey} 是字面量，排在 {id} 之前判别，两者不会互相吃掉。
    // stocktakes 是 7.4.2.1 盘点单：列表 / capabilities / {id} / {id}/lines /
    // {id}/lines/{lineId}/count / {id}/tasks / {id}/tasks/{taskId}/assign。
    // 盘点路径上同样**没有** DELETE：账面数是冻结快照，删一张盘点单等于让这次盘从未发生过。
    // capabilities 是字面量，排在 {id} 之前判别。
    // stocktake-variances 是 7.4.2.2 盘点差异处理：capabilities / sheets/{sheetId}/
    // lines|report|audit|adjustments / adjustments/{id}(+submit|approve|reject|cancel|post)。
    // 同样**没有** DELETE：差异处理全程只追加审计，归因/调整单/审计行都不可删，
    // 放行一个删除形状会让「能不能把一条差异的痕迹抹掉」看起来是能。
    // facilities 是 8.1.1.1 仓库列表 + 8.1.1.2 仓库详情（同一个页面，详情挂在列表之下）。
    // facilities/options 与 facilities/capabilities 是两个**单段字面量**子路径
    // （筛选下拉项与作业维度接入情况），排在任何 {uuid} 之前判别。
    // {uuid} 后面可选接一段 locations / entrypoints：库位清单与子模块入口清单。
    // 这三段是 8.1.1.2 交付时**追加**进来的，8.1.1.1 已放行的四个形状
    // （facilities、facilities/options、facilities/capabilities、POST facilities）
    // 一条都不许被挤掉 —— 白名单是求并，不是二选一。
    // PUT {uuid}（改基础信息与地址）走同一路径：代理按方法透传，写方法由后端端点裁决。
    // 刻意**没有** DELETE 段：仓库不是可物理删除的主数据，停用与归档才是出口，
    // 放行删除形状会让「能不能把一个仓连同它的库存痕迹抹掉」看起来是能。
    // 也不放行 {uuid}/config：行为参数属 8.1.1.3，尚未交付。
    // 与 warehouses|legal-entities|skus 的区别：那几个是 7.1.x 各页共用的**下拉参照项**
    // （只有 id/code/name 等少数列），facilities 是**列表本体**（带库存量、作业量、
    // 货权法人、当地时间）。名字相近但形状不同，混用会让筛选下拉退化成全量传输。
    // ownership 是 7.7.1.1 货主与所有权：capabilities / resolved / report / drift /
    // reference-options / reassign 六个读与动作形状，owners[/{id}[/status]]、
    // rules[/{id}[/versions|/retire]] 两组读与动作。
    // 同样**没有** DELETE 段：货主与货权档都是「当时这批货归谁」的唯一凭据，
    // 停用走 status / 收区间，放行一个删除形状会让「能不能抹掉一条货权痕迹」看起来是能。
    // capabilities/resolved/report/drift/reference-options 是字面量，排在 {id} 之前判别。
    // allocation-policies 是 7.8.1.1 分配策略配置：capabilities / effective / calculation（试算）
    // / runs（落库分摊）/ runs/{runId}（批次明细）/ {id}（详情）/ {id}/versions（换版）
    // / {id}/retire（作废）七个形状。allocation-sales 是销量登记与窗口名册。
    // 同样**没有** DELETE：策略档与分摊批次都是不可改的事实，作废走收口区间，
    // 放行一个删除形状会让「能不能把一条分摊算法配置抹掉」看起来是能。
    // effective / calculation / runs 是字面量，排在 {id} 之前判别，彼此不会互相吃掉。
    // allocation-priority 是 7.8.1.2 优先级与保底：capabilities / floors / floors/{id} /
    // floors/{id}/versions（换版）/ floors/{id}/retire（作废）/ calculation（带保底试算）/
    // runs（带保底落库）/ resolutions（保底生效记录）七个形状。
    // 同样**不新增**任何形状：保底档走 retire（收口区间）、保底生效记录 append-only，
    // 后端根本没有 DELETE 端点，V76 另有 REVOKE DELETE 与不可变触发器各挡一次。
    // 注意：白名单是**按路径**登记的，不区分方法 —— 对同一条已登记路径发 DELETE 会被转发到
    // 后端（并在后端拿到 405）。所以「不能删」这件事的防线在服务端与 DB，不在这里；
    // 这里只负责保证前端源码里真实出现的路径一个都不漏。
    // capabilities / floors / calculation / runs / resolutions 是字面量，排在 {id} 之前判别。
    // allocation-results 是 7.8.2.1 分摊结果查看：capabilities / results（分摊明细）/
    // runs（分摊轮次）/ reconciliation（平台回写一致性核对）/ writebacks（回写状态观测，
    // POST 登记用同一路径）五个形状。
    // 同样**不新增**任何形状：回写观测是 append-only 事实（V189 的 REVOKE UPDATE/DELETE
    // + 不可变触发器各挡一次），后端根本没有 DELETE / PUT 端点。
    // 插入锚点固定在 `allocation-policies` 之后、`allocation-sales` 之前 ——
    // 那个位置**确定在 `inventory\/` 这一组内部**：另一处候选锚点
    // （`allocation-priority` 之后）在这条正则里是**组外**的顶层分支，
    // 插在那里会让 `inventory/allocation-results/...` 整组匹配不上（表现是页面 404，
    // 而 typecheck / lint / vitest 全绿）。
    // capabilities / results / runs / reconciliation / writebacks 是字面量，
    // 彼此不会互相吃掉。
    // consignments 是 7.7.1.2 寄售与代销：capabilities / {id}(+lines|stock-view|settlements|
    // settlement-window|returns|audit|activate|suspend|close) / {id}/lines/{lineId}/sales /
    // settlements/{id}(+confirm) / returns/{id}(+execute|cancel)。
    // 同样**没有** DELETE：销售事实与审计是凭据（append-only），协议行是结算的依据，
    // 放行删除形状等于让「把一笔应付的痕迹抹掉」看起来是能。
    // outbound-orders 是 8.3.1.1 出库单列表：capabilities / 集合读 / 集合写（建单）/
    //   pick-requests（生成拣货任务）/ print / cancel，以及 {id}/transition（状态推进）。
    //   同样**没有** DELETE 出库单，也没有 {id} 单条详情：详情是 8.3.1.2 的职责，
    //   放行一个不存在的形状比 404 更糟（放行不该存在的路径会让界面以为已交付）。
    //   capabilities / pick-requests / print / cancel 是字面量首段，与 {uuid}/transition
    //   靠「首段是字面量还是 UUID」区分，两条不冲突。
    // labor 是 8.5.1.1 作业效率：capabilities / report / ranking / trend /
    // work-tasks / work-tasks/{id} / work-tasks/{id}/(assign|start|complete|exception)。
    // 与 warehouses 同级（不在 inventory/ 之下），因为前端打的是 warehouse/labor/...。
    // 同样**没有** DELETE：人效是提成核算与排班的凭据 —— 作业任务定稿后计量列由数据库
    // 触发器冻结、作业事件 append-only，放行删除等于让「改不掉也能抹掉」。
    // goods-receipts 是 8.2.1.1 收货单：capabilities / {id} / {id}/movements，
    // 以及 POST 建单、PUT 改单、POST {id}/receive 实收、POST {id}/close 关单。
    // 同样**没有** DELETE：收货单是账实凭证，草稿可以改、实收可以追加，
    // 但删一张收货单等于让这次收货从未发生过。
    // capabilities 是字面量，排在 {id} 之前判别。
    // customs-declarations 是 10.5.1.1 报关单：capabilities / 集合读 / 集合写（建单）/
    //   {id} / {id}/(documents|results|events) / PUT {id} 改单 /
    //   {id}/(submit-for-review|review|reject-review|withdraw-review) /
    //   POST {id}/documents 生成单据、POST {id}/documents/{docId}/finalize 定稿、
    //   POST {id}/submit 提交报关行、POST {id}/results 登记海关结果、
    //   POST {id}/amend 改单、POST {id}/cancel 取消。
    // 命名空间与 first-mile-shipments 同级（都在 /warehouse 之下，不在 inventory/ 之下），
    // 因为前端打的是 warehouse/customs-declarations/... —— 报关单的来源就是那张头程发运单。
    // 同样**没有** DELETE：报关单是海关举证凭据，提交痕迹、三单据版本链与结果回执
    // 都是 append-only 审计，放行删除形状等于让「这张单报没报过」变得不可追责。
    // 三条判别顺序不能调换：capabilities 是字面量排在 {id} 之前；
    // documents/{docId}/finalize 是两段 uuid，必须排在单段 {id} 组里用 \/ 分支匹配，
    // 否则会被 {id}(documents) 提前吃掉半截、把 finalize 留在匹配之外。
    // duty-taxes 是 10.5.1.3 关税与税费：capabilities / 集合读 / 税率表(rates + rates/{id} PUT) /
    //   集合写（建估算）/ PUT {id} 改基础 / {id}/(lines|payments|variances) /
    //   POST {id}/(recalculate|lock|void|payments) / GET /variance-analysis。
    // 命名空间与 customs-declarations **同级**（都在 /warehouse 之下、都不在 inventory/ 之下）：
    // 它的输入就是那张报关单，放进 inventory/ 会让 8.2 现场作业与 10 物流税费混成一个前缀。
    // variance-analysis 同样是字面量子路径，必须排在单段 {id} 之前判别，
    // 否则会被 \/[0-9a-f-]+ 之外的分支漏掉（variance-analysis 不是 uuid，{id} 也接不住它）。
    // 同样**没有** DELETE：实缴与差异是 append-only 的举证凭据（V90 收回 UPDATE/DELETE 授权
    // 并钉了触发器），放行删除形状等于让「这票货缴了多少税、差在哪」变得不可追责。
    // trade-documents 是 10.5.2.1 贸易单证：capabilities / 集合读 / 集合写（建包）/
    //   PUT {id} / rules（GET 集合 + POST + {id} 的 PUT）/ templates（同样三形状）/
    //   certifications（同样三形状）/ preference-rules（同样三形状）/
    //   preference-confirmations（只读）/ reminders（GET + {id}/resolve 的 POST）/
    //   {id} / {id}/(documents|checks|reminders|events) /
    //   POST {id}/(submit-for-review|review|reject-review|withdraw-review|check|submit|acknowledge|send-back|cancel) /
    //   POST {id}/documents 生成单证、POST {id}/documents/{docId}/finalize 定稿、
    //   POST {id}/documents/{docId}/preference-claim 登记优惠主张、
    //   POST {id}/documents/{docId}/preference-confirm 业务确认。
    // 命名空间与 customs-declarations / duty-taxes **同级**（都在 /warehouse 之下、都不在 inventory/ 之下）：
    // 它的上游就是那张报关单，放进 inventory/ 会让 8.2 现场作业与 10 物流贸易单证混成一个前缀。
    // 判别顺序：字面量子路径（capabilities/rules/templates/certifications/preference-rules/
    //   preference-confirmations/reminders）排在单段 {id} 之前，否则会被 [0-9a-f-]+ 之外的分支漏掉；
    //   两段 uuid 的 documents/{docId}/xxx 排在单段 {id}/documents 组之后，用 \/ 分支匹配三段，
    //   否则会被单段 {id} 吃掉半截、把 finalize 留在匹配之外。
    // 同样**没有** DELETE：备证资料、校验结论与缺失提醒都是 append-only 的举证凭据，
    //   放行删除形状等于让「这套单证备过没有、被退回过没有」变得不可追责。
    // serials / lots 的明细子路径此前漏登记：白名单里只有集合读
    // （serials 与 lots 各一条），而页面实际在调 serials/stats、serials/{id}、
    // serials/{id}/movements、lots/{id}、lots/{id}/distribution、lots/{id}/movements，
    // 后端 InventorySerialController / InventoryLotController 这些端点都真实存在。
    // 漏一条 = 那些页面在浏览器里拿代理 404，而 pnpm test 直连服务层发现不了。
    // 两组都刻意**没有**在 movements 之外再放行别的动作段：
    // 序列号的 validate-movement 与导入是既有形状，批次只读不写。
    // location-allocations 是 8.1.2.2 库位自动分配，七个形状：
    //   capabilities（能力声明）/ recommend（推荐，纯计算不落库）/ list（决策清单，真分页）
    //   / by-receipt/{receiptId}（某张收货单上的决策）/ 裸 POST（选定一个库位）
    //   / {allocationId}（DELETE = 撤销决策，带原因，不是删数据）/ {allocationId}/release。
    //   判别顺序：capabilities / recommend / list / by-receipt 四条**字面量**子路径排在
    //   [0-9a-f-]+ 之前 —— 「ca」「de」「ad」这类前缀本身就是合法十六进制字符，顺序反了会被
    //   UUID 分支先吃掉半截、再因多出后缀而整条落空。by-receipt 后面那层 uuid 写死，
    //   多一层（/by-receipt/{id}/xxx）仍然 404。
    //   末位**保留**可选量（`)?`），而 8.1.2.1 的 location-trees 刻意不带：
    //   本模块的写入形状就是裸 POST location-allocations（选定），不带就永远 404；
    //   而 location-trees 没有「对整棵树」这一个动作，所以那边必须拒绝裸路径。
    prefix: 'warehouse/',
    env: 'WAREHOUSE_URL',
    fallback: 'http://127.0.0.1:8084',
    // printing 是 8.3.4.2 面单与标签打印：capabilities / devices / templates(/{id}) /
    //   targets(/{id}(/history)) / reprint-rules / jobs(/{id}(/execute|/cancel|/items/{itemId}/content)) / logs。
    //   jobs/{id}/items/{itemId}/content 是打印内容下载端点：它返回的是打印那一刻落库并校验过的那份
    //   渲染内容（ZPL 指令流或 HTML 打印视图），因此**不能**走下面 text/csv 那条原样透传分支 ——
    //   走 JSON.parse 会把条码指令流解析失败变成 503。这里只放行路径，字节由响应体原样带回。
    //   刻意**没有** DELETE 段：打印凭据（label_print_log）与任务明细都是 append-only 审计凭据，
    //   放行一个删除形状会让「能不能把一条打印痕迹抹掉」看起来是能。
    pattern:
      // distribution / platform-observations / serials / lots / internal-access —— 这些路径此前漏登记，
      // 白名单只在浏览器端生效、单测直连服务层发现不了，漏一条就等于该页面在浏览器里拿代理 404。
      //
      // 7.2.1.2 预占分配明细（只动 reservations 这一支，别人的分支一律没碰）：
      //   reservations                              集合级（7.2.1.1 预占需求列表）
      //   reservations/options                      字面量子路径，7.2.1.1
      //   reservations/capabilities                 字面量子路径，7.2.1.1
      //   reservations/{id}/allocations             本模块：分配明细行（GET）+ 登记分配（POST）
      //   reservations/{id}/allocations/{id}/commit 本模块：承诺分配（POST）
      //   reservations/{id}/allocations/{id}/consume 本模块：消耗出库（POST）
      //   reservations/{id}/timeline                本模块：状态迁移时间线（GET）
      //   reservations/{id}/allocation-options      本模块：下拉项（状态 / bucket 候选 / 登记目标）
      //   reservations/{id}/capabilities            本模块：能力声明（释放与过期见下方 7.2.1.3 段落）
      //
      // 为什么 options / capabilities 排在 [0-9a-f-]+ 那支**前面**：
      //   这两支是字面量子路径，而 [0-9a-f-]+ 能吃下任意十六进制串。若把字面量支放到后面，
      //   `reservations/options` 会先被通配支判成「id=options 的明细」而放行 —— 白名单宁可少放，
      //   不可放错：放错等于凭空开出一个后端不存在的端点入口。
      // 为什么 [0-9a-f-]+ 支排在最后：
      //   它必须排在所有字面量子路径之后，否则 options/capabilities/allocation-options/timeline
      //   全都会被它当成「带 id 的子路径」提前匹配掉（正则 alternation 是**首个命中即返回**，
      //   不是最长匹配 —— 这是与「贪婪」直觉相反、但在这一行正则上真实生效的行为）。
      // reservations/{id}/entries **仍然不放行**：本链路的时间线叫 /timeline，
      //   /entries 不是对外形状。放行一个后端不存在的形状，比 404 更糟。
      //   （reservations/{id}/release 与 /expiry 由 7.2.1.3 就地加进同一支的最内层，
      //     见下方 7.2.1.3 段落。）
      //
      // 尾部几条分支的相对位置（8.5.2.1 库位利用率 / 8.4.2.1 拆解单 / 8.2.2.1 上架 / 8.3.2.1 拣货）：
      //   location-trees → location-utilization → location-allocations → assembly-orders
      //   → disassembly-orders → putaway-tasks → pick-tasks
      // 七条都是**字面量前缀**，两两之间不存在前缀歧义（`location-` 之后第 9 个字符就分叉：
      //   trees 的 t / utilization 的 u / allocations 的 a；assembly / disassembly 在第 2 个字符
      //   分叉；putaway-tasks 与 pick-tasks 在第 1 个字符就分叉），因此彼此调换不改变匹配结果。
      // 仍保留各自车道当时选的位置，只为让下一条车道插进来时看得出该挨着谁：
      //   location-utilization 挨着 location-trees（同属库位命名空间，8.5.2.1 选的位置）；
      //   disassembly-orders 挨着 assembly-orders（8.4.2.1 选的位置）。
      //
      // ⚠️ 8.2.2.1（putaway-tasks）与 8.3.2.1（pick-tasks）**选的是同一个插入点**
      //   （disassembly-orders 之后、当时的最末一条），两条车道必然互撞。
      //   求并时必须**先后排成两条**（如上），**绝不能二选一** —— 少一条就是那个模块
      //   在浏览器里静默 404，而 typecheck / lint / vitest / Playwright 全绿。
      //   本行最终顺序按模块号升序（8.2.2.1 在 8.3.2.1 前）。
      //
      // 8.5.3.1 出库时效：**就地追加在最后一支 parcels 之后**，既有条目一条没动、也没重排。
      //   四条形状，全部是字面量子路径，没有 [0-9a-f-]+ 通配：
      //     outbound-timeliness/capabilities  四环节能力 + 生效阈值 + 口径声明（GET）
      //     outbound-timeliness/report       时效分布 + 瓶颈 + 超时明细（GET）
      //     outbound-timeliness/order-times   投递订单下单时刻（POST，幂等 upsert）
      //     outbound-timeliness/thresholds    配四环节阈值（PUT，幂等 upsert）
      //   为什么排在 parcels 之后：本模块与 parcels/assembly-orders 等一样是
      //   **顶层字面量分支**（不在 inventory/ 之下），彼此第 1 个字符就分叉
      //   （o / p / a / d），换位置不改变匹配结果；留在末尾是本车道选的位置。
      //   order-times 与 thresholds 是**写入**形状：前者是上游 trade 投递订单时刻的授权入口，
      //   后者是配置。它们只被 /capabilities 的口径声明与本页的阈值展示读到，
      //   但列出它们的代价是零（字面量逐条列，不引入通配）。
      // 7.1.1.3 可用量计算（本行已并入集成头；下面这段留作后续车道的定位参照）：
      //   本车道插在 warehouse 的**第一处** `inventory\/` 分组里，紧跟
      //   `sellable-policies(...)` 之后、`stocktakes(...)` 之前：
      //     ...|policies(?:\/[0-9a-f-]+)?|sellable-policies(...)|sellable(?:\/(?:capabilities|skus))?|stocktakes(...)
      //   **只改了第一处**：该正则里 `inventory\/` 分组出现两次（内容逐字相同的历史重复），
      //   alternation 首个命中即返回，所以第一处生效即足够，第二处不动以免求并时多一处冲突点。
      //   为什么排在 sellable-policies 之后而不是之前：`sellable` 是 `sellable-policies` 的前缀，
      //   放在其后即使 `sellable(?:\/(?:capabilities|skus))?` 只吃掉 `sellable` 五字符，
      //   锚点 `$` 也会让它失败并回退到 sellable-policies 分支，结论无歧义。
      //   为什么没有 `[0-9a-f-]+` 通配歧义：`s` / `l` 非十六进制字符，
      //   `sellable` 永远匹配不上任何 `\/[0-9a-f-]+` 分支（`b`、`e`、`d`、`c`、`a`、`f` 是，
      //   但整串匹配不上），所以不存在「被当成 id 凭空放行」的风险。
      //   放行形状：GET /warehouse/inventory/sellable、/sellable/skus、/sellable/capabilities。
      //   刻意**不含** DELETE/PUT/POST —— 可售量是纯读算式，算一次不构成一次库存业务。
      //
      // 7.2.1.3 释放与过期（**就地扩展 reservations 同一支**，别人的分支一条没碰）：
      //   reservations/release-candidates          字面量集合级：过期候选清单 + 汇总
      //   reservations/expiry-runs                 字面量集合级：批量过期清理的运行历史
      //   reservations/expiry-runs/{id}            单次运行详情
      //   reservations/release-records             字面量集合级：释放凭据（可按预占过滤）
      //   reservations/release-options             字面量集合级：下拉项 + 过期规则
      //   reservations/release-capabilities        字面量集合级：本链路的能力声明
      //   reservations/expire-overdue              字面量集合级：批量过期清理（POST）
      //   reservations/{id}/release                手动释放（POST）
      //   reservations/{id}/expiry                 补写 / 清空过期时刻（POST）
      //
      //   本车道新增的 7 条**字面量子路径全部插在 [0-9a-f-]+ 那支之前**，位置在既有
      //   `options` / `capabilities` 之后。7.2.1.2 的注释已写明原因：alternation 是
      //   **首个命中即返回**，字面量支放到通配支后面，`release-candidates` 会被判成
      //   「id=release-candidates 的明细」而凭空放行一个不存在的形状。
      //   `release` / `expiry` 挂在 `[0-9a-f-]+(...)?` 最内层，与 allocations / timeline 同级。
      //
      // 8.2.2.1 上架任务 / 8.3.2.1 拣货任务：见上方「尾部几条分支的相对位置」段落 ——
      //   两条都是**顶层**分支（warehouse/pick-tasks/... 、warehouse/putaway-tasks/...），
      //   不在 inventory/ 之下，也不挂在 goods-receipts / outbound-orders 底下。
      //   各自形状逐条列在自己的 allowlist 测试里，两条车道各自新增的分支都保留。
      // 7.2.2.1 的 sellable-policies 刻意**不含** DELETE/PUT/POST —— 可售量是纯读算式，
      //   算一次不构成一次库存业务（7.8.1.2 那条车道已查证：白名单按路径登记、不区分方法，
      //   所以同一路径的 DELETE 会被转发到后端，「不能删」的防线因此在服务端而非白名单）。
      // 8.3.2.2 波次与合并拣货：**顶层**分支 warehouse/pick-waves/...，不挂在 outbound-orders 之下 ——
      //   一个波次横跨多张出库单，它不属于任何一张出库单（同 pick-tasks 的理由）。
      //   放行形状：capabilities / rules / rules/{id}/retire / candidates / {id} /
      //   {id}/release / {id}/complete / {id}/cancel / {id}/tasks/{taskId}/detach，加集合级 GET 与 POST。
      //   **capabilities / rules / candidates 三个字面量子路径全部排在 [0-9a-f-]+ 之前**：
      //   alternation 是首个命中即返回，而 `candidates` 的 c/a/n/d/i/t/e/s 全落在 [0-9a-f-] 里，
      //   排到通配支后面会被判成「id=candidates 的波次」而凭空放行一个不存在的形状。
      //   刻意**不含** pick-tasks/{id}/waves 那种形状：波次不是某一张拣货任务的子资源。
      // 8.3.3.1 复核扫描：**顶层**分支 warehouse/recount-scans/...，不挂在 outbound-orders 之下 ——
      //   一张出库单可以复核多次（判异常 → 退回拣货 → 重拣 → 再复核），每次是一张独立复核单，
      //   挂成 outbound-orders/{id}/recount 会让契约看起来像「一张单只复核一次」。
      //   放行形状：capabilities / rules / rules/{id} / rules/{id}/retire / {id} /
      //   {id}/(open-box|scans|settle|complete|escalate|cancel) / {id}/exceptions/{exceptionId}/resolve，
      //   加集合级 GET 与 POST。
      //   **capabilities / rules 两个字面量子路径排在 [0-9a-f-]+ 之前**：alternation 是首个命中即返回。
      //   **两段 uuid 的 exceptions/{exceptionId}/resolve 排在单段 {id} 组里用 \/ 分支匹配**，
      //   否则会被 {id}(exceptions) 提前吃掉半截、把 resolve 留在匹配之外。
      //   刻意**不含** pick-tasks/{id}/recount 那种形状：复核接的是「已完成拣货 + 出库单待复核」，
      //   不接某一张拣货任务的子资源。
      // 7.2.2.2 安全库存（safety-stock，17 个形状）：阈值分档（policies / policies\/effective /
      //   policies\/{id} / policies\/{id}\/versions / policies\/{id}\/retire）、日均销量登记
      //   （sales 读写）、评估与预警（evaluate / evaluations / alerts / alerts\/{id} /
      //   alerts\/{id}\/acknowledge / alerts\/{id}\/resolve）、补货建议
      //   （suggestions / suggestions\/{id}\/acknowledge / suggestions\/{id}\/dismiss）与能力声明
      //   （capabilities）。
      //   判别顺序：`capabilities` / `effective` / `sales` / `evaluate` / `evaluations` 这些
      //   **字面量**子路径一律排在各自的 `[0-9a-f-]+` 通配**之前**判别，否则 `alerts/capabilities`
      //   会被当成 `alerts/{id}` 凭空放行一个后端并不存在的形状。
      //   刻意**不含** DELETE 段：阈值作废走 retire（关区间），预警与建议都要留档复盘，
      //   能不能物理删掉的答案是不能 —— 服务端无 DELETE 端点（405）+ V114 的 REVOKE DELETE。
      //   注意本文件的 pattern 是**按路径登记、不区分方法**的，所以读写形状合并在一支里。
      //   刻意**不含** DELETE/PUT/POST —— 可售量是纯读算式，算一次不构成一次库存业务。
      //
      // shipment-handovers 是 8.3.4.1 发货交接的六个形状（追加在 packing-orders 之后，
      // 仍是同一个 alternation 里的一个分支，没有改动任何既有分支）：
      //   capabilities   能力与边界声明（含「轨迹归 10」「平台回写归 2」）
      //   [id]           详情；[id]/completion 判据；[id]/waybills 登记承运商与运单号
      //   [id]/confirm   交接确认 = 实发扣减 + 预占消费（同一事务）
      //   [id]/cancel    作废（库存不动）
      // 同样**不含** DELETE/PUT：交接是事实、流水是 append-only（V123 的守卫会拒），
      // 作废一次就不能复活 —— 要更正就作废重开一张。
      // 10.2.2.1 包裹详情：**顶层**分支 warehouse/parcels/...，不挂在 packing-orders 之下 ——
      //   10 物流查轨迹、12 财务做运费归集时手里拿的是 parcel_id，不是作业单 id
      //   （打包作业单可以失败退回复核再重打包，每次一张独立作业单，挂上去会让契约看起来
      //   像「一个包裹只有一个作业单」）。
      //   放行形状：capabilities / {id}。
      //   **capabilities 排在 [0-9a-f-]+ 之前**：alternation 是首个命中即返回，而
      //   capabilities 的 c/a/p 里 p 落在 [0-9a-f-] 之外，判别顺序错了会凭空放行一个
      //   后端并不存在的「id=capabilities 的包裹」。
      //   刻意**不含任何写操作**：本模块是 8.3.3.2 包裹凭据的只读视图，
      //   包裹与明细项是事实（V121 的守卫直接拒已结束的打包单再改），没有 POST/PUT/DELETE 端点。
      /^(inventory\/(?:overview|buckets|bucket-actions|capabilities|policies(?:\/[0-9a-f-]+)?|sellable-policies(?:\/(?:effective|pools(?:\/[^/]+)?(?:\/calculation)?|calculation)|\/[0-9a-f-]+(?:\/(?:versions|retire))?)?|sellable(?:\/(?:capabilities|skus))?|safety-stock(?:\/capabilities|\/policies(?:\/effective|\/[0-9a-f-]+(?:\/(?:versions|retire))?)?|\/sales|\/evaluate|\/alerts(?:\/[0-9a-f-]+(?:\/(?:acknowledge|resolve))?)?|\/suggestions(?:\/[0-9a-f-]+\/(?:acknowledge|dismiss))?|\/evaluations)?|stocktakes(?:\/capabilities|\/[0-9a-f-]+(?:\/lines(?:\/[0-9a-f-]+\/count)?|\/tasks(?:\/[0-9a-f-]+\/assign)?)?)?|expiry(?:\/(?:alerts|policies|allocation|policy(?:\/[0-9a-f-]+)?|warehouses|lots(?:\/[0-9a-f-]+)?))?|lots(?:\/[0-9a-f-]+(?:\/(?:distribution|movements))?)?|ledger(?:\/ref-types|\/[0-9a-f-]+)?|reconciliation(?:\/[0-9a-f-]+(?:\/findings)?)?|distribution(?:\/spus)?|platform-observations|serial-vocabularies|serials(?:\/(?:import|movements|validate-movement|stats)|\/[0-9a-f-]+(?:\/movements)?)?|transfers(?:\/[0-9a-f-]+(?:\/(?:movements|confirm|ship|receive|close))?)?|in-transit(?:\/(?:alerts|netting)|\/[0-9a-f-]+(?:\/(?:arrive|discrepancy))?)?|stocktake-variances(?:\/capabilities|\/sheets\/[0-9a-f-]+\/(?:lines(?:\/[0-9a-f-]+\/attribute)?|report|audit|adjustments)|\/adjustments(?:\/[0-9a-f-]+(?:\/(?:submit|approve|reject|cancel|post))?)?)?|cross-entity-transfers(?:\/[0-9a-f-]+(?:\/(?:movements|settlement|source-confirm|target-confirm|ship|receive|close|cancel))?)?|health(?:\/(?:trend|gate|issues|assess))?|ownership(?:\/(?:capabilities|resolved|report|drift|reference-options|reassign|owners(?:\/[0-9a-f-]+(?:\/status)?)?|rules(?:\/[0-9a-f-]+(?:\/(?:versions|retire))?)?))?|allocation-policies(?:\/(?:capabilities|effective|calculation|runs(?:\/[0-9a-f-]+)?|[0-9a-f-]+(?:\/(?:versions|retire))?)?)?|allocation-results(?:\/(?:capabilities|results|runs|reconciliation|writebacks))?|allocation-sales|consignments(?:\/capabilities|\/[0-9a-f-]+(?:\/(?:lines|stock-view|settlement-window|settlements|returns|audit|activate|suspend|close))?|\/lines\/[0-9a-f-]+\/sales|\/settlements(?:\/[0-9a-f-]+(?:\/confirm)?)?|\/returns(?:\/[0-9a-f-]+(?:\/(?:execute|cancel))?)?)?|outbound-orders(?:\/(?:capabilities|detail-capabilities|pick-requests|print|cancel)|\/[0-9a-f-]+(?:\/transition)?)?|printing(?:\/capabilities|\/templates(?:\/[0-9a-f-]+)?|\/devices|\/reprint-rules|\/targets(?:\/[0-9a-f-]+(?:\/history)?)?|\/jobs(?:\/[0-9a-f-]+(?:\/(?:execute|cancel)|\/items\/[0-9a-f-]+\/content)?)?|\/logs)?|serial-movements|serial-operations|reservations(?:\/options|\/capabilities|\/release-candidates|\/expiry-runs(?:\/[0-9a-f-]+)?|\/release-records|\/release-options|\/release-capabilities|\/expire-overdue|\/[0-9a-f-]+(?:\/allocations(?:\/[0-9a-f-]+\/(?:commit|consume))?|\/timeline|\/allocation-options|\/capabilities|\/release|\/expiry)?)?|aging(?:\/(?:buckets|slow-moving|policies|policy(?:\/[0-9a-f-]+)?))?|serials(?:\/(?:import|movements|validate-movement|stats)|\/[0-9a-f-]+(?:\/movements)?)?|allocation-priority(?:\/(?:capabilities|calculation|runs|resolutions|floors(?:\/[0-9a-f-]+(?:\/(?:versions|retire))?)?))?)|facilities(?:\/(?:options|capabilities))?|warehouses|legal-entities|skus|quality-statuses|locations|labor(?:\/capabilities|\/report|\/ranking|\/trend|\/work-tasks(?:\/[0-9a-f-]+(?:\/(?:assign|start|complete|exception))?)?)?|goods-receipts(?:\/capabilities|\/[0-9a-f-]+(?:\/(?:movements|receive|close))?)?|goods-receipt-discrepancies(?:\/capabilities|\/[0-9a-f-]+(?:\/(?:evidence|decide|claim(?:\/(?:submit|withdraw))?|close))?)?|processing-orders(?:\/capabilities|\/boundary|\/issues|\/executions)?|first-mile-shipments(?:\/capabilities|\/source-candidates|\/[0-9a-f-]+(?:\/(?:transit-legs|confirm|dispatch|cancel))?)?|transit-trackings(?:\/capabilities|\/alerts(?:\/evaluate-all|\/[0-9a-f-]+\/(?:acknowledge|close))?|\/thresholds(?:\/[0-9a-f-]+)?|\/[0-9a-f-]+(?:\/(?:events|receipt|close)|\/alerts\/evaluate)?)?|facilities(?:\/(?:options|capabilities)|\/[0-9a-f-]+(?:\/(?:locations|entrypoints))?)?|lots|customs-declarations(?:\/capabilities|\/[0-9a-f-]+(?:\/(?:documents|results|events|submit-for-review|review|reject-review|withdraw-review|submit|amend|cancel)|\/documents\/[0-9a-f-]+\/finalize)?)?|duty-taxes(?:\/capabilities|\/variance-analysis|\/rates(?:\/[0-9a-f-]+)?|\/[0-9a-f-]+(?:\/(?:lines|payments|variances|recalculate|lock|void))?)?|trade-documents(?:\/capabilities|\/rules(?:\/[0-9a-f-]+)?|\/templates(?:\/[0-9a-f-]+)?|\/certifications(?:\/[0-9a-f-]+)?|\/preference-rules(?:\/[0-9a-f-]+)?|\/preference-confirmations|\/reminders(?:\/[0-9a-f-]+\/resolve)?|\/[0-9a-f-]+(?:\/(?:documents|checks|reminders|events|submit-for-review|review|reject-review|withdraw-review|check|submit|acknowledge|send-back|cancel)|\/documents\/[0-9a-f-]+(?:\/finalize|\/preference-claim|\/preference-confirm))?)?|inventory\/(?:overview|buckets|bucket-actions|capabilities|policies(?:\/[0-9a-f-]+)?|sellable-policies(?:\/(?:effective|pools(?:\/[^/]+)?(?:\/calculation)?|calculation)|\/[0-9a-f-]+(?:\/(?:versions|retire))?)?|stocktakes(?:\/capabilities|\/[0-9a-f-]+(?:\/lines(?:\/[0-9a-f-]+\/count)?|\/tasks(?:\/[0-9a-f-]+\/assign)?)?)?|expiry(?:\/(?:alerts|policies|allocation|policy(?:\/[0-9a-f-]+)?|warehouses|lots(?:\/[0-9a-f-]+)?))?|lots(?:\/[0-9a-f-]+(?:\/(?:distribution|movements))?)?|ledger(?:\/ref-types|\/[0-9a-f-]+)?|reconciliation(?:\/[0-9a-f-]+(?:\/findings)?)?|distribution(?:\/spus)?|platform-observations|serial-vocabularies|serials(?:\/(?:import|movements|validate-movement|stats)|\/[0-9a-f-]+(?:\/movements)?)?|transfers(?:\/[0-9a-f-]+(?:\/(?:movements|confirm|ship|receive|close))?)?|in-transit(?:\/(?:alerts|netting)|\/[0-9a-f-]+(?:\/(?:arrive|discrepancy))?)?|stocktake-variances(?:\/capabilities|\/sheets\/[0-9a-f-]+\/(?:lines(?:\/[0-9a-f-]+\/attribute)?|report|audit|adjustments)|\/adjustments(?:\/[0-9a-f-]+(?:\/(?:submit|approve|reject|cancel|post))?)?)?|cross-entity-transfers(?:\/[0-9a-f-]+(?:\/(?:movements|settlement|source-confirm|target-confirm|ship|receive|close|cancel))?)?|health(?:\/(?:trend|gate|issues|assess))?|ownership(?:\/(?:capabilities|resolved|report|drift|reference-options|reassign|owners(?:\/[0-9a-f-]+(?:\/status)?)?|rules(?:\/[0-9a-f-]+(?:\/(?:versions|retire))?)?))?|allocation-policies(?:\/(?:capabilities|effective|calculation|runs(?:\/[0-9a-f-]+)?|[0-9a-f-]+(?:\/(?:versions|retire))?)?)?|allocation-results(?:\/(?:capabilities|results|runs|reconciliation|writebacks))?|allocation-sales|consignments(?:\/capabilities|\/[0-9a-f-]+(?:\/(?:lines|stock-view|settlement-window|settlements|returns|audit|activate|suspend|close))?|\/lines\/[0-9a-f-]+\/sales|\/settlements(?:\/[0-9a-f-]+(?:\/confirm)?)?|\/returns(?:\/[0-9a-f-]+(?:\/(?:execute|cancel))?)?)?|vmi(?:\/capabilities|\/agreements(?:\/[0-9a-f-]+(?:\/(?:activate|suspend|close|water-levels|settlement-window|audit|sales|lines(?:\/[0-9a-f-]+\/sales)?|replenishment-signals|snapshots|settlements(?:\/[0-9a-f-]+)?))?)?|\/replenishment-signals(?:\/[0-9a-f-]+(?:\/(?:acknowledge|dispatch|fulfil|cancel))?)?|\/settlements(?:\/[0-9a-f-]+(?:\/confirm)?)?)|outbound-orders(?:\/(?:capabilities|detail-capabilities|pick-requests|print|cancel)|\/[0-9a-f-]+(?:\/transition)?)?|printing(?:\/capabilities|\/templates(?:\/[0-9a-f-]+)?|\/devices|\/reprint-rules|\/targets(?:\/[0-9a-f-]+(?:\/history)?)?|\/jobs(?:\/[0-9a-f-]+(?:\/(?:execute|cancel)|\/items\/[0-9a-f-]+\/content)?)?|\/logs)?|serial-movements|serial-operations|aging(?:\/(?:buckets|slow-moving|policies|policy(?:\/[0-9a-f-]+)?))?|serials(?:\/(?:import|movements|validate-movement|stats)|\/[0-9a-f-]+(?:\/movements)?)?|allocation-priority(?:\/(?:capabilities|calculation|runs|resolutions|floors(?:\/[0-9a-f-]+(?:\/(?:versions|retire))?)?))?)|facilities(?:\/(?:options|capabilities)|\/[0-9a-f-]+(?:\/(?:locations|entrypoints|config(?:\/(?:options|impacts|changes)|\/(?:shifts)(?:\/[0-9a-f-]+)?)?))?)?|location-trees(?:\/capabilities|\/[0-9a-f-]+(?:\/locations(?:\/[0-9a-f-]+)?)?)|location-utilization(?:\/capabilities|\/[0-9a-f-]+(?:\/locations\/[0-9a-f-]+)?)|location-allocations(?:\/(?:capabilities|recommend|list|by-receipt\/[0-9a-f-]+|[0-9a-f-]+(?:\/release)?))?|assembly-orders(?:\/capabilities|\/[0-9a-f-]+(?:\/(?:issue|cancel))?)?|disassembly-orders(?:\/capabilities|\/[0-9a-f-]+(?:\/(?:execute|cancel))?)?|putaway-tasks(?:\/capabilities|\/[0-9a-f-]+(?:\/(?:assign|start|scan|complete|cancel))?)?|pick-tasks(?:\/capabilities|\/[0-9a-f-]+(?:\/(?:assign|picks|exceptions(?:\/[0-9a-f-]+\/resolve)?|complete|reopen|cancel))?)?|pick-waves(?:\/capabilities|\/rules(?:\/[0-9a-f-]+\/retire)?|\/candidates|\/[0-9a-f-]+(?:\/(?:release|complete|cancel)|\/tasks\/[0-9a-f-]+\/detach)?)?|recount-scans(?:\/capabilities|\/rules(?:\/[0-9a-f-]+(?:\/retire)?)?|\/[0-9a-f-]+(?:\/(?:open-box|scans|settle|complete|escalate|cancel)|\/exceptions\/[0-9a-f-]+\/resolve)?)?|packing-orders(?:\/capabilities|\/materials(?:\/[0-9a-f-]+(?:\/retire)?)?|\/[0-9a-f-]+(?:\/completion|\/parcels(?:\/[0-9a-f-]+\/(?:items|measure|label))?|\/complete|\/fail|\/cancel)?)?|shipment-handovers(?:\/capabilities|\/[0-9a-f-]+(?:\/completion|\/waybills|\/confirm|\/cancel)?)?|parcels(?:\/capabilities|\/[0-9a-f-]+)|outbound-timeliness(?:\/(?:capabilities|report)|\/order-times|\/thresholds))$/,
  },
  {
    // trade（09 订单履约）：第一条 9.x 模块车道 9.3.2.2（FBA 与自发货选择）落地，
    // 所以这里**首次**放行子路径 —— 仍然逐条列出真实存在的形状，不做通配。
    //
    // fulfillment/routing 是 9.3.2.2 的全部端点，七类：
    //   capabilities  能力与上游可用性声明
    //   mcf-authorizations / cost-profiles / stock-snapshots
    //                   三张输入表：集合读 + POST 维护（各两个形状）
    //   evaluations    路径评估（只读试算，POST）
    //   decisions      集合读 + POST 落决策 + {uuid} 详情 + {uuid}/status 状态流转
    //   switches       缺货切换记录（集合读；append-only，**没有**任何写形状）
    //
    // processing 是 6.4.1.1 加工单（卷 06 采购与供应，本车道首次放行）：
    //   capabilities  能力与上下游可用性
    //   requirements   按 BOM 版本试算组件需求量（catalog 规则 R7，只读不落库）
    //   orders         集合读 + POST 建单 + {uuid} 详情
    //   orders/{uuid}/status    推进状态（下达 / 开工 / 报完工 / 登记入库 / 作废）
    //   orders/{uuid}/progress  进度登记（不改生命周期）
    //   orders/{uuid}/components/issue  登记组件领用进度（**不改库存**）
    //   factories      加工厂集合读 + POST 新建；factories/{uuid} 取档案与更新
    // 三段形状（orders/{uuid}/components/issue）排在单段 {uuid} 之后，
    // 否则会被单段分支吃掉半截、把 /issue 留在匹配之外。
    // 刻意**没有** DELETE：加工单与事件流都是 append-only 的凭据。
    //
    // 判别顺序：字面量子路径（capabilities / mcf-authorizations / cost-profiles /
    //   stock-snapshots / evaluations / switches）排在单段 {uuid} 之前，
    //   否则会被 [0-9a-f-]+ 分支漏掉。两段形状（decisions/{id}/status）排在
    //   单段 decisions/{id} 之后。
    //
    // 刻意**没有** DELETE：决策、切换与事件流都是 append-only 的举证凭据
    // （V92 的 REVOKE 与触发器已经这么钉了），放行删除形状等于让界面以为可以。
    // fulfillment/allocation 是 9.3.2.1 分仓规则的全部端点（**新的一组**，不并进上面的
    // fulfillment/routing 组 —— 那是 9.3.2.2 的形状，混在一起会让「路由决策」与
    // 「分仓决策」在权限与日志上分不开）：
    //   capabilities  能力与上游可用性声明
    //   rules         分仓规则集合读 + POST 维护（优先级、兜底策略与五维权重）
    //   warehouses    仓库档案集合读 + POST 维护（**货权法人登记在这里**）
    //   stock-estimates / shipping-estimates
    //                 可用量与运费时效估算：集合读 + POST 维护（各两个形状）
    //   simulations   分仓试算（POST，只读不落库）
    //   allocations   集合读 + POST 执行分配 + {uuid} 详情 + {uuid}/status 状态流转
    //   holds         挂起清单（集合读；挂起是 append-only 的凭据，**没有**任何写形状）
    // 判别顺序与 routing 组一致：字面量子路径排在 allocations/{uuid} 之前，
    // 否则会被 [0-9a-f-]+ 分支吃掉；两段形状排在单段 {uuid} 之后。
    // fulfillment/dropship 是 9.6.1.1 代发模式配置（**新的一组**，不并进 routing / allocation）：
    //   capabilities   能力与上游可用性声明
    //   shop-refs      2.3.1 店铺只读投影：集合读 + POST 维护
    //   supplier-refs  3.6.2 供应商报价只读投影：集合读 + POST 维护
    //   supplier-stocks 供应商可供货量观测：集合读 + POST 维护
    //   configs        集合读 + POST 维护 + {uuid} 详情 + {uuid}/status + {uuid}/lines
    //   evaluations    代发可售标记：集合读 + POST 评估
    //   events         事件流（集合读；append-only，**没有**任何写形状）
    // 判别顺序与既有两组一致：字面量子路径排在 configs/{uuid} 之前，
    // 两段形状（configs/{uuid}/status、configs/{uuid}/lines）排在单段 {uuid} 之后。
    // 刻意**没有** DELETE：可售标记与事件流是 append-only 的判定凭据。
    // fulfillment/dropship-orders 是 9.6.1.2 代发单与供应商下单（**新的一组**）：
    //   capabilities    能力与上游可用性声明
    //   endpoints        供应商代发接口登记：集合读 + POST 维护（只存 credential_ref）
    //   orders           集合读 + POST 建单 + {uuid} 详情
    //   orders/{uuid}/confirm-payment 人工确认收款（9.1 无支付状态列，见 V122 文件头二）
    //   orders/{uuid}/dispatch        提交供应商
    //   orders/{uuid}/supplier-ack    人工录入供应商回执（单号 + 实际成本）
    //   orders/{uuid}/shipment        记录供应商发货与运单号
    //   orders/{uuid}/fail            人工置失败（必落 ORDER_FAILED 告警）
    //   alerts           差异告警工作台：集合读
    //   alerts/{uuid}/acknowledge     处置告警（只改处置字段，不删历史行）
    //   events           事件流（集合读；append-only，**没有**任何写形状）
    // 刻意**没有** DELETE：告警与事件流是「已发生差异」的凭据，删掉等于抹掉历史。
    // fulfillment/pending-shipment 是 9.4.1.1 待发货（**新的一组**，不并进上面四组）：
    //   capabilities  能力与上游可用性声明（含「平台 SLA 无上游、8.3.4.2/8.3.4.1/10 未交付」）
    //   sync          从 8.3.3.2 授权只读接口同步已完成打包的出库单（幂等投影，**唯一的写入口**）
    //   board         分组看板：仓库 / 承运渠道 / 承运商 / 发货时限 四选一（真 GROUP BY）
    //   units         待发货清单：集合读（真分页 + 真筛选 + 排序白名单）
    //   units/{uuid}  单元详情（单头 + 包裹 + 运单 + 时间线 + 完成判据）
    //   units/{uuid}/sla      登记发货时限（平台 SLA 无上游可读，登记而不伪造）
    //   units/{uuid}/cancel   作废单元（退回仓库，不产生发货记录）
    //   batch-tasks   集合读 + POST 建批量任务（{uuid} 形状见下）
    //   batch-tasks/{uuid}            任务详情（任务头 + 逐条执行结果 + 时间线）
    //   batch-tasks/{uuid}/execute     执行（逐条推进；任务状态由结果算出，不接受直接指定）
    //   batch-tasks/{uuid}/cancel      取消任务（终态不可回退）
    //   batch-tasks/{uuid}/waybill-acks 登记承运商运单回执
    //   batch-tasks/{uuid}/labels       回填包裹面单打印凭据（8.3.4.2 未交付，本模块不渲染 ZPL）
    // 判别顺序与既有两组一致：字面量子路径（capabilities / board / units / batch-tasks）
    // 排在 [0-9a-f-]+ 分支之前，否则会被单段分支吃掉半截。
    // 刻意**没有** DELETE：待发货单元与批量任务都带凭据，要更正就作废重开一张。
    // 10.1.1.2 渠道配置（**本条目是新增一条 services[] 元素，不是往既有 channel 分支的
    // alternation 里并**）。同批 4 条车道共享这一个文件，而每个服务在 services[] 里是**一段
    // 超长单行正则**：只要两条车道都往同一服务的 alternation 里并分支，git 必然判冲突
    // （结构性的，retry 永远不会自愈）。所以本车道走「新加一条元素」这条路，
    // 插入锚点固定在 `prefix: 'trade/'` **之前** —— 那样本车道与其他三条车道的插入点
    // 被既有条目隔开，git 能干净合并。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   logistics-channels                    集合读（GET，真分页 + 筛选）/ POST 登记渠道
    //   logistics-channels/capabilities       能力声明（未交付段 available=false）
    //   logistics-channels/overview           全量概览（不受筛选与分页影响）
    //   logistics-channels/eligibility        🔴 渠道限制判定（POST，只读无副作用）
    //   logistics-channels/{uuid}             详情（GET）/ DELETE 撤下渠道配置
    //
    // 判别顺序：字面量子路径（capabilities / overview / eligibility）**排在
    // [0-9a-f-]+ 分支之前**。写反了的话 `p`（eligibility 的首字母）落在
    // `[0-9a-f-]` 里，会被 uuid 分支提前吃掉 —— 而 `logistics-channels/p` 根本不是 uuid，
    // 正则会静默匹配失败，或者更糟：拼出一个「id=capabilities 的渠道」被凭空放行。
    // 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`，
    // 只锚定首尾两项，中间分支静默失效且不报错）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `logistics-channels/{uuid}/transit-times` / `/limits`
    //     —— 10.1.1.3 时效与限制未交付
    //   * `logistics-channels/{uuid}/rates` —— 10.1.2.1 运费试算未交付
    //   * `logistics-channels/{uuid}/quotes`    —— 10.1.2.2 渠道比价未交付
    //   * `logistics-channels/sync` —— 本模块不经任何跨服务同步：承运商档案同库
    //     （10.1.1.1 已落在 channel），没有需要投影的上游
    //   * `logistics-channels/{uuid}/carriers` —— 所属承运商是**建渠道时**的入参，
    //     不是一个可以单独 GET 的资源（承运商档案在 /channel/carriers 读）
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是
    //    `[0-9a-f-]+`：后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行，
    //    症状是有人 GET /api/channel/logistics-channels/c 会拿到一个「渠道 id 是 c」
    //    的请求打到后端，而不是被白名单当场 404。
    // ⚠️ 字面量子路径**排在** uuid 段之前：写反的话 `capabilities/overview/eligibility`
    //    里以 [0-9a-f] 开头的那个会被 uuid 分支吃掉一半。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^logistics\-channels(?:\/(?:capabilities|overview|eligibility|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}))?$/,
    // 裸路径 logistics-channels **放行**：它就是本模块的集合读 + POST 登记入口。
  },
  {
    // 10.1.1.3 时效与限制（**本条目是新增一条 services[] 元素**，不是往既有 channel
    // 分支的 alternation 里并）。插入锚点与 10.1.1.2 同款：固定在 `prefix: 'trade/'`
    // 之前，纯增量，不重排既有条目与 import。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   logistics-transit-limits                       裸路径（保留：形态对齐其余模块）
    //   logistics-transit-limits/capabilities          能力声明（未交付段 available=false）
    //   logistics-transit-limits/overview              全量概览（不受筛选与分页影响）
    //   logistics-transit-limits/transit-rules         时效规则集合读 / POST 登记
    //   logistics-transit-limits/transit-rules/{uuid}  详情（GET）/ DELETE 撤下
    //   logistics-transit-limits/size-limits           尺寸 / 重量上限集合读 / POST
    //   logistics-transit-limits/size-limits/{uuid}    详情（GET）/ DELETE
    //   logistics-transit-limits/markings              标记要求集合读 / POST
    //   logistics-transit-limits/markings/{uuid}       详情（GET）/ DELETE
    //   logistics-transit-limits/precheck              🔴 限制命中提示（POST，只读无副作用）
    //   logistics-transit-limits/estimate              🔴 时效估算（POST，只读无副作用）
    //
    // 判别顺序：字面量子路径（capabilities / overview / transit-rules / size-limits /
    // markings / precheck / estimate）**排在 uuid 段之前**。写反的话 `e`(estimate) /
    // `c`(capabilities) 落在 [0-9a-f] 里会被 uuid 分支提前吃掉，拼出一个「id 是 e 的
    // 时效规则」被凭空放行。
    // ⚠️ uuid 尾巴**只挂在三个集合名后面**，不挂在 precheck / estimate / capabilities
    // 后面：那两个是判定端点，不存在 `/precheck/{uuid}` 这种形状；写成
    // `(?:literal|uuid)` 的单层 alternation 会把 `/precheck/{uuid}` 一起放行。
    // 同理**裸 uuid 也不放行**：`logistics-transit-limits/{uuid}` 不是任何端点。
    // id 段用**完整 UUID 形状**（照抄本文件里其余 20 处），不是 `[0-9a-f-]+`：
    // 后者把单字符 `e` 当合法 id，症状是有人 GET .../transit-rules/e 会打到后端。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^logistics\-transit\-limits(?:\/(?:capabilities|overview|precheck|estimate|transit-rules|size-limits|markings|(?:transit-rules|size-limits|markings)\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}))?$/,
  },
  {
    // 10.1.2.1 运费计算器（**本条目是新增一条 services[] 元素**，与 10.1.1.2 渠道配置
    // 那条并列，**不改也不重排**它）。同批多车道共享这一个文件，而每个服务在
    // services[] 里是**一段超长单行正则**：两条车道都往同一服务的 alternation 里并分支，
    // git 必然判冲突（结构性的，retry 永远不会自愈）。所以本车道走「新加一条元素」，
    // 插入锚点固定在 `prefix: 'trade/'` **之前** —— 那样本车道与其他车道的插入点被
    // 既有条目隔开，git 能干净合并。
    //
    // 并入集成头时本条目排在 10.1.1.3 的 `logistics-transit-limits` 那条**之后**：
    // 两条用的是同一个锚点，形状也一样（各自一条独立的 `channel/` 元素），
    // 所以解法是**保留两条、彼此并列**，既不并成 alternation 也不重排。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   freight/capabilities            能力声明（未交付段 available=false）
    //   freight/estimate               🔴 运费试算（POST，无副作用，不落库）
    //   freight/quotes                 试算并落库（POST）/ 试算单集合读（GET，真分页 + 筛选）
    //   freight/quotes/{uuid}          试算单详情（GET，含逐渠道计算轨迹）
    //   freight/rate-cards             费率卡与分国家规则（GET）/ 登记改费率卡（POST）
    //   freight/country-rules          登记改分国家规则（POST）
    //
    // 判别顺序与既有两组一致：字面量子路径（capabilities / estimate / quotes /
    // rate-cards / country-rules）排在 [0-9a-f-]+ 分支之前，否则会被 uuid 分支提前吃掉。
    // 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`，
    // 只锚定首尾两项，中间分支静默失效且不报错）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `freight/quotes/{uuid}/settlement` —— 结算用实际成本，口径不在本模块
    //     （试算用规则、结算用实际，不能拿试算当实际成本）
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法）。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    // 10.1.2.1 运费计算器的 `quotes/{uuid}` **在改本车道之前就没被放行**：
    // 原正则里 `quotes` 后面没有再接一段 uuid，所以「试算单详情」这一页在浏览器里
    // 静默 404（后端端点存在、契约里有、typecheck / lint / vitest 全绿）。本车道顺手补上，
    // 因为比价单详情页与它是同一类形状，留着这个洞会让它继续骗人。
    pattern:
      /^freight(?:\/(?:capabilities|estimate|quotes|rate\-cards|country\-rules|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})|\/quotes\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?$/,
    // 裸路径 freight **不放行**：本模块没有裸集合读，运费计算器的一切入口都带子路径。
  },
  {
    // 10.1.2.2 渠道比价（channel 服务）。纯**增量**追加一条独立的 `channel/` 元素：
    // 既有的 `freight/`（10.1.2.1）、`logistics-transit-limits/`（10.1.1.3）、
    // `logistics-channels/`（10.1.1.2）三条**一字未动、也不重排** —— 同批多车道共享
    // 这一个文件，每条服务规则是一段超长单行正则，往同一条 alternation 里并分支会让
    // git 必然判冲突且 retry 永远不会自愈。插入锚点固定在本文件里 `freight/` 那条
    // **之后**、`label-templates` 那条**之前**，彼此的插入点被既有条目隔开。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   rate-comparison/capabilities        能力声明（未交付段 available=false + ownerModule）
    //   rate-comparison/compare             🔴 比价（POST，无副作用，不落库）
    //   rate-comparison/comparisons         比价并落库（POST）/ 比价单集合读（GET，真分页 + 筛选）
    //   rate-comparison/comparisons/{uuid}  比价单详情（GET，含逐渠道评分明细与算式）
    //   rate-comparison/ratings             渠道评分集合读（GET）/ 登记改评分（POST）
    //   rate-comparison/shop-priorities     店铺预设优先级集合读（GET）/ 登记改（POST）
    //
    // 判别顺序与既有两组一致：字面量子路径（capabilities / compare / comparisons /
    // ratings / shop-priorities）排在 uuid 分支之前，否则会被 uuid 分支提前吃掉。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `rate-comparison` —— 本模块没有裸集合读，一切入口都带子路径
    //   * `rate-comparison/ratings/{uuid}` —— 评分不是按 id 单独取的（列表带筛选即可）
    //   * `rate-comparison/shop-priorities/{uuid}` —— 同上
    //   * `rate-comparison/settlement` —— 结算用实际成本，口径不在本模块
    //     （比价成本来自 10.1.2.1 的试算，试算用规则、结算用实际）
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法）。
    // 末尾**刻意不带 `?`**：带了就等于放行裸路径 `rate-comparison` —— 本模块没有裸集合读，
    // 放行它会让界面以为「有一条列表接口」，而它转发过去只会拿到 503 / 404。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^rate\-comparison(?:\/(?:capabilities|compare|comparisons|ratings|shop\-priorities)|\/comparisons\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})$/,
  },
  {
    // 14.1.2.2 广告类型差异（channel 服务）。纯**增量**追加一条独立的 `channel/` 元素：
    // 既有的 `rate-comparison/`（10.1.2.2）、`label-templates`（10.2.2.2）、`freight/`
    // （10.1.2.1）、`logistics-transit-limits/`（10.1.1.3）、`logistics-channels/`
    // （10.1.1.2）**一字未动、也不重排** —— 同批多车道共享这一个文件，每条服务规则是一段
    // 超长单行正则，往同一条 alternation 里并分支会让 git 必然判冲突且 retry 永远不会自愈。
    // 插入锚点固定在本文件里 `rate-comparison/` 那条**之后**、`label-templates` 那条**之前**，
    // 彼此的插入点被既有条目隔开。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   ad-type-diffs/capabilities              能力声明（未交付段 available=false + ownerModule）
    //   ad-type-diffs/resolve                   🔴 走 2.1.2.1 两层解析归一口径（GET，无副作用）
    //   ad-type-diffs/profiles                  档案集合读（GET，真分页 + 筛选）/ 登记档案（POST）
    //   ad-type-diffs/profiles/{uuid}           档案详情（GET，含特有字段定义与未定义原始键）
    //   ad-type-diffs/profiles/{uuid}/field-specs  特有字段定义集合读（GET）/ 登记（POST）
    //   ad-type-diffs/payloads                  归一后广告集合读（GET）/ 登记（POST）
    //   ad-type-diffs/payloads/{uuid}           详情（GET，含原始报文原文与特有字段补充展示）
    //   ad-type-diffs/comparable                归一后的广告类型视图（GET）
    //   ad-type-diffs/fidelity                  保真概览（GET）
    //
    // 判别顺序与既有两组一致：字面量子路径（capabilities / resolve / profiles / payloads /
    // comparable / fidelity）排在 uuid 分支之前，否则会被 uuid 分支提前吃掉。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `ad-type-diffs` —— 本模块没有裸集合读，一切入口都带子路径
    //   * `ad-type-diffs/payloads/{uuid}/raw` —— 原文随详情一起返回，不单独开一条
    //   * `ad-type-diffs/profiles/{uuid}/payloads` —— 广告按自己的集合读筛选，不按档案下钻
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法）。
    // 末尾**刻意不带 `?`**：带了就等于放行裸路径 `ad-type-diffs` —— 本模块没有裸集合读，
    // 放行它会让界面以为「有一条列表接口」，而它转发过去只会拿到 503 / 404。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^ad\-type\-diffs(?:\/(?:capabilities|resolve|profiles|payloads|comparable|fidelity)|\/profiles\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/field\-specs)?|\/payloads\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})$/,
  },
  {
    // 10.2.2.2 面单模板（channel 服务）。纯**增量**追加：既有条目一字未动，
    // 插入锚点固定在 `logistics-channels` 那组**之后**、`prefix: 'trade/'` **之前** ——
    // 那样本车道与其它车道的插入点被既有条目隔开，git 能干净合并。
    //
    // 并入集成头时本条目排在 10.1.1.3 的 `logistics-transit-limits` 那条**之后**：
    // 两条用的是同一个锚点，形状也一样（各自一条独立的 `channel/` 元素），
    // 所以解法是**保留两条、彼此并列**，既不并成 alternation 也不重排。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   label-templates                    集合读（GET，真分页 + 筛选）/ POST 登记模板
    //   label-templates/capabilities       能力声明（未交付段 available=false）
    //   label-templates/overview           全量概览（不受筛选与分页影响）
    //   label-templates/binding            🔴 渠道规则强绑定判定（POST，只读无副作用）
    //   label-templates/{uuid}             详情（GET）/ DELETE 撤下模板
    //   label-templates/{uuid}/status      启用 / 停用（POST）
    //   label-templates/{uuid}/preview     🔴 真渲染预览（POST，出 ZPL + 真条码）
    //   label-templates/{uuid}/renders     渲染历史（GET，真分页）
    //
    // ⚠️ 字面量子路径（capabilities / overview / binding）**排在** [0-9a-f-] 分支之前：
    //    写反的话 `binding` 的首字母 b 落在 [0-9a-f] 里，会被 uuid 分支提前吃掉，
    //    正则静默匹配失败，或更糟：拼出一个「id=binding 的模板」被凭空放行。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`。
    // ⚠️ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `label-templates/{uuid}/print` —— 打印下发是 8.3.4.2 的主权，本模块不建打印引擎
    //   * `label-templates/{uuid}/deploy` —— 同上，本模块只到模板与渲染产物
    //   * `label-templates/resolve` —— 运单 ↔ 模板的自动选版未做（见能力声明 ownerModule）
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^label\-templates(?:\/(?:capabilities|overview|binding|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:status|preview|renders))?))?$/,
  },
  {
    // 2.5.2.1 常用回写操作（channel 服务）。纯**增量**追加一条独立的 `channel/` 元素：
    // 既有的 `label-templates`（10.2.2.2）、`ad-type-diffs`（14.1.2.2）、`rate-comparison`
    // （10.1.2.2）、`logistics-channels`（10.1.1.2）等**一字未动、也不重排** —— 本文件是
    // 多车道共用的累积型白名单，每条服务规则是一段超长单行正则，往同一条 alternation 里并分支
    // 会让 git 必然判冲突且 retry 永远不会自愈。
    // 插入锚点固定在 `label-templates` 那条**之后**、`prefix: 'trade/'` **之前**。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   writeback-targets                    候选池集合读（GET，真分页 + 筛选）/ POST 登记目标
    //   writeback-targets/{uuid}/status      封禁 / 解封目标（POST）
    //   writeback-batches                    批次集合读（GET，真分页 + 结局筛选）/ POST 执行批量
    //   writeback-batches/preview            🔴 执行前试算（POST，只读无副作用，不落任何一行）
    //   writeback-batches/{uuid}             批次详情（GET，逐条结果）
    //
    // 判别顺序：字面量子路径 `preview` 排在 uuid 分支之前（与既有两组同款次序）。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+` ——
    // 后者会把 `p`（preview 的首字母）当 id 吃掉，`writeback-batches/p` 会被凭空放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `writeback-batches/{uuid}/items` —— 逐条结果随详情一起返回，不单独开一条
    //   * `writeback-targets/{uuid}` —— 目标没有独立详情端点，候选池集合读已带全部字段
    //   * `writeback-batches/{uuid}/retry` —— 批量不等于事务，整批重发是有资损风险的动作，
    //     重试按 2.5.1.1 的任务清单逐条判断（2.5.1.2 未知结果核验），本模块刻意不提供整批重发入口
    //   * `writeback-targets/sync` —— 本模块不经任何跨服务同步：上游（04/07/09/10）推事件或
    //     运营在工作区登记，没有需要本服务投影的对象
    // ⚠️ 末尾**刻意不带 `?`**：两个裸路径（writeback-targets / writeback-batches）都是真实的
    // 集合读写入口，必须放行。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^writeback\-(?:targets(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/status)?|batches(?:\/preview|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?)$/,
  },
  {
    // 4.1.1.1 Listing 列表（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 channel 分支的超长 alternation 里并分支 —— 那一支是单行超长正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //
    // 插入锚点固定在本文件里**最后一条 `channel/` 元素**（10.2.2.2 `label-templates`）
    // **之后**、`prefix: 'trade/'` **之前**：与既有条目被隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   listings                    分页列表（GET，七个筛选维度 + 真分页）/ 登记平台商品（POST）
    //   listings/capabilities       能力声明（价格 / 销量 / 内部可售量 available=false + ownerModule）
    //   listings/filter-options     筛选面板词表（不硬编码在前端）
    //   listings/{uuid}             单条读取（列表行点进去的最小载荷；全景详情归 4.1.1.2）
    //   listings/batch              批量操作（POST：分配负责人 / 下架；改价改库存显式拒绝并留痕）
    //   listings/batch-tasks        批量操作任务列表（GET，被拒绝的改价同样可回溯）
    //
    // ⚠️ 判别顺序：`batch-tasks` 必须排在 `batch` **之前**。写反的话 `batch` 先吃掉前缀，
    //    `batch-tasks` 永远匹配不到（症状是「批量任务列表 404」，很容易被误判成正则写错）。
    // ⚠️ 那个 `\/` 必须写在**字面量子路径与 uuid 段共用的那一层**上
    //    （`(?:\/(?:batch|capabilities|…|[0-9a-f]{8}…))`）。写成
    //    `(?:\/batch|[0-9a-f]{8}…)` 会让斜杠只属于第一个分支 —— 字面量路径照样放行，
    //    而 `listings/{id}` 静默 404，症状是「点进详情就 404，但列表和批量都正常」。
    // ⚠️ 裸路径 `listings` **放行**：本模块的列表就是 `GET /channel/listings?...`，
    //    query string 不参与这里的比对（proxy() 拿的是 params.path，不含 search），
    //    不放行裸路径等于列表页在浏览器里直接 404。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`。
    // ⚠️ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `listings/{uuid}/mapping`    —— 映射的建立 / 版本化 / 批量匹配归 4.1.2.1 / 4.1.2.2
    //   * `listings/{uuid}/lifecycle`  —— 七态状态机与平台回写归 4.1.1.3
    //   * `listings/{uuid}/diff`       —— 差异对照是 4.1.1.2 的核心价值，不在本模块
    //   * `listings/{uuid}/publish`    —— 发布流程归 4.3.1，本模块只登记不上线
    //   * `listings/{uuid}/history`    —— 状态历史随列表行返回，不单独开一条
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listings(?:\/(?:batch\-tasks|batch|capabilities|filter\-options|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}))?$/,
  },
  {
    // 4.1.1.3 生命周期与下架（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 channel 分支的超长 alternation 里并分支 —— 那一支是单行超长正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //
    // 插入锚点固定在 4.1.1.1 `listings` 那一条**之后** ——
    // 与本批另外三条车道的锚点（`listing-claims` / `listing-status-events` / `one-click-publish` 之后）互不重叠，
    // git 能干净求并。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   listing-lifecycles                    生命周期列表（GET，真分页 + 七态/来源/店铺/平台/关键词筛选）
    //   listing-lifecycles/capabilities       能力声明（未交付段 available=false + ownerModule）
    //   listing-lifecycles/filter-options     筛选面板词表（不硬编码在前端）
    //   listing-lifecycles/diff               🔴 平台下架 vs 内部下架的差异区（真分页）
    //   listing-lifecycles/sync               🔴 批量同步：把还没联动过的平台事件应用到内部状态（POST）
    //   listing-lifecycles/{uuid}             单个 listing 的生命周期视图（GET）
    //   listing-lifecycles/{uuid}/timeline    🔴 状态变更时间线（GET，真分页 + 按来源筛）
    //   listing-lifecycles/{uuid}/actions     🔴 人工动作：主动下架 / 重新上架 / 提交审核 / 标记封禁（POST）
    //   listing-lifecycles/{uuid}/platform-status 🔴 平台状态被动同步（POST）
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities / filter-options / diff / sync）必须排在 uuid 段**之前**。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+` ——
    //    后者会把 `d`（diff 的首字母）当 id 吃掉，`listing-lifecycles/d` 会被凭空放行。
    // ⚠️ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `listing-lifecycles/{uuid}/publish`  —— 发布流程归 4.3.1，本模块只管状态
    //   * `listing-lifecycles/{uuid}/batch`     —— 批量生命周期动作归 4.1.1.1（见能力声明 ownerModule）
    //   * `listing-lifecycles/{uuid}/appeal`    —— 被封禁的申诉工单归 4.3.2.2 / 16.2.1.2，未交付
    //   * `listing-lifecycles/{uuid}/republish` —— 重新上架的平台回写归 4.3.1.2 / 4.3.2.2，未交付
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listing\-lifecycles(?:\/(?:capabilities|filter\-options|diff|sync)|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:timeline|actions|platform\-status))?)?$/,
  },
  {
    // 4.1.2.1 映射管理（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往 4.1.1.1 的 `listings` 分支里并子路径 —— 那条分支是已交付车道的成果，
    // 多车道同时改同一段正则必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 4.1.1.1 的 `listings` 元素**之后**、`prefix: 'trade/'` **之前**：
    // 与既有条目被隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 🔴 为什么用 `listing-mappings` 这个独立前缀而不是 `listings/{id}/mapping`：
    // 4.1.1.1 交付时把 `/channel/listings/{uuid}/mapping` 显式登记成「不放行」
    // （那是本模块的形状），在同一棵路径树上开子资源要同时改它的白名单与它的拒绝清单，
    // 而那份清单是别人的成果。映射本身也是独立资源：它挂在**平台变体**上，
    // 不挂在 listing 上（一个 listing 有多个变体，映射是逐变体成立的）。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   listing-mappings                       列表（GET 真分页 + 筛选）/ 建立映射（POST）
    //   listing-mappings/capabilities          能力声明（未交付段 available=false + ownerModule）
    //   listing-mappings/filter-options        筛选词表（不硬编码在前端）
    //   listing-mappings/{uuid}                详情（当前映射 + 完整版本历史）/ 解除映射（DELETE）
    //   listing-mappings/batch-candidates      批量候选（POST，只写候选表）
    //   listing-mappings/candidates            候选列表（GET 真分页）
    //   listing-mappings/candidates/{uuid}/decide 候选裁决（POST 确认 / 否决）
    //   listing-mappings/unmapped              未映射清单（GET 真分页）
    //   listing-mappings/resolve               订单行解析入口（POST）
    //   listing-mappings/by-sku/{uuid}         多对一反查（GET）
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities / filter-options / batch-candidates /
    //    candidates / unmapped / resolve / by-sku）必须排在 `{uuid}` **之前**，
    //    否则会被 uuid 分支吃掉半截（症状是「能力声明 404，但列表页正常」）。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    短前缀会被放行到一条根本不存在的路由上。
    // ⚠️ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `listing-mappings/{uuid}/versions`   —— 版本历史随详情一起返回，不单独开一条
    //   * `listing-mappings/{uuid}/affected-orders` —— 「按版本回溯受影响订单」归 4.1.2.2，未交付
    //   * `listing-mappings/{uuid}/components` —— 套装组件明细归 4.1.2.3，未交付
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listing\-mappings(?:\/(?:capabilities|filter\-options|batch\-candidates|candidates(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/decide)?|unmapped|resolve|by\-sku\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}))?$/,
  },
  {
    // 2.6.2.1 增量补拉（channel 服务）。纯**增量**追加一条独立的 `channel/` 元素：
    // 既有的 `listings`（4.1.1.1）、`writeback-batches`（2.5.2.1）、`label-templates`
    // （10.2.2.2）、`ad-type-diffs`（14.1.2.2）等**一字未动、也不重排** —— 本文件是
    // 多车道共用的累积型白名单，每条服务规则是一段超长单行正则，往同一条 alternation 里
    // 并分支会让 git 必然判冲突且 retry 永远不会自愈。
    // 插入锚点固定在 `listings` 那条**之后**、`prefix: 'trade/'` **之前**。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   backfills                 集合读（GET，按连接/状态筛选）/ POST 发起补拉
    //   backfills/capabilities    能力声明（下游同步处理器 available=false + ownerModule）
    //   backfills/summary         补拉状态分布
    //   backfills/gaps            漏推缺口检测（按 2.6.1.1 接收记录的静默区间）
    //   backfills/{uuid}          任务详情
    //   backfills/{uuid}/records  处理记录集合读（GET）/ 提交一批补拉结果（POST，逐条判定幂等）
    //   backfills/{uuid}/segments 分段轮询留痕（证明「只给最近 N 条」时分段轮询真的发生了）
    //   backfills/{uuid}/complete 收尾：结算四个数字 + 推进游标
    //   backfills/{uuid}/cancel   人工中止
    //
    // 判别顺序：字面量子路径（capabilities / summary / gaps）必须排在 uuid 段**之前**。
    // 写反的话 `c`（capabilities）与 `s`（summary）落在 [0-9a-f] 里，会被 uuid 段提前吃掉 ——
    // 正则静默匹配失败，或者更糟：拼出一个「id=capabilities 的补拉任务」被凭空放行。
    // ⚠️ 那个 `\/` 必须写在**字面量子路径与 uuid 段共用的那一层**上，与 listings 同款。
    // ⚠️ 裸路径 `backfills` **放行**：集合读与发起补拉都是它的入口，
    //    query string 不参与这里的比对（proxy() 拿的是 params.path，不含 search）。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`。
    // ⚠️ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `backfills/{uuid}/records/{recordId}` —— 逐条结果随详情返回，不单开一条
    //   * `backfills/{uuid}/retry` —— 补拉重试就是「再发起一次」：幂等键相同会拿回同一任务，
    //     另开一条 retry 只会让人以为重试会绕过幂等
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^backfills(?:\/(?:capabilities|summary|gaps|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:records|segments|complete|cancel))?))?$/,
  },
  {
    // 4.2.1.2 批量编辑（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 channel 分支的超长 alternation 里并分支（理由同上面 4.1.1.1 那条：
    // 那一支是单行超长正则，多车道同时并进去 git 必然判冲突）。
    // 插入锚点固定在 4.1.1.1 `listings` 条目**之后**、`prefix: 'trade/'` **之前**：
    // 与兄弟模块相邻但仍是独立元素，各车道插入点被既有条目隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   listing-bulk-edit/capabilities             能力声明（草稿隔离 + 卖点/图片线上基线未接入 + 发布归 4.3.1.2）
    //   listing-bulk-edit/templates                平台差异化模板列表（GET）+ 新建/更新（POST）
    //   listing-bulk-edit/preview                 操作预览：逐条真实 before/after（POST，落 PREVIEWED）
    //   listing-bulk-edit/confirm                 预览确认（POST，凭 operationId 推进，只写草稿）
    //   listing-bulk-edit/regex-check             正则 ReDoS 预检（POST）
    //   listing-bulk-edit/operations              修改记录列表（GET）+ 单次详情 operations/{uuid}（GET）
    //   listing-bulk-edit/drafts                  待发布草稿清单（GET）+ 单条 drafts/{uuid}（GET）
    //   listing-bulk-edit/drafts/{uuid}/discard   废弃草稿（POST，保留留痕不删除）
    //   listing-bulk-edit/scope-check             目标集合可见性预检（GET，跨租户/不存在的 id）
    //
    // ⚠️ **刻意不放行裸路径 `listing-bulk-edit`**：本模块没有裸路径资源，
    //    所以最外层分组**不带 `?`** —— 写了 `?` 就会连裸路径一起放行。
    //    放行一个后端不存在的映射比 404 更糟：界面会以为功能已交付。
    // ⚠️ **刻意没有 publish / apply 形状**：蓝图加粗句「批量编辑只改草稿不直接改线上」。
    //    批量编辑**没有任何**改线上的端点，放行 `…/publish` 等于在代理层凭空造一个
    //    绕过草稿的入口 —— 那正是「误改全店标题」事故要走的那条路。
    // ⚠️ 字面量子路径（capabilities/templates/preview/confirm/regex-check/scope-check/
    //    operations/drafts）各自成一支，不与 uuid 段混在一个 alternation 里：
    //    uuid 段的 `\/` 必须**与字面量路径的 `\/` 写在同一层**，否则 `drafts/{uuid}` 会静默 404。
    // ⚠️ `drafts/{uuid}/discard` 的 `discard` 挂在 **uuid 段之后**：
    //    写成 `/drafts/discard` 会放行一个不存在的「按 listing 废弃全部」的形状。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listing\-bulk\-edit(?:\/(?:capabilities|templates|preview|confirm|regex\-check|scope\-check)|\/operations(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|\/drafts(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/discard)?)?)$/,
  },
  {
    // 4.1.1.2 Listing 详情（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往 4.1.1.1 的 `listings` 分支里并子路径 —— 那条分支是已交付车道的成果，
    // 多车道同时改同一段正则必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 4.1.1.1 的 `listings` 元素**之后**、`prefix: 'trade/'` **之前**：
    // 与既有条目被隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 🔴 为什么用 `listing-details` 这个独立前缀而不是 `listings/{id}/...`：
    // 4.1.1.1 交付时把 `/channel/listings/{id}/diff` 显式登记成「不放行」（那是本模块的形状），
    // 也把 `/channel/listings/{id}` 定义成「列表行点进去的最小载荷」。在同一棵路径树上开子资源，
    // 要同时改它的白名单与它的拒绝清单 —— 而那份清单是别人的成果。
    // 「列表」与「详情全景」本来也是两种资源：列表行是筛选结果的一行，
    // 详情是聚合了四份上游事实的全景视图，两者连字段集合都不一样。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   listing-details/capabilities            能力声明（售价 / 内部可售量 / 内容草稿 / 销量 available=false + ownerModule）
    //   listing-details/{uuid}                  全景（七块一次给全 + 差异高亮计数）
    //   listing-details/{uuid}/diff             差异对照（本模块的核心价值）
    //   listing-details/{uuid}/content          内容对照（平台当前内容 vs 内部草稿内容）
    //   listing-details/{uuid}/publish-history  发布历史（GET 真分页）
    //   listing-details/{uuid}/platform-operations 平台操作记录（GET 真分页）
    //   listing-details/{uuid}/content-snapshots 登记平台当前内容快照（POST）
    //   listing-details/{uuid}/publish-records  登记发布动作结果（POST）
    //
    // ⚠️ 判别顺序：`capabilities` 必须排在 `{uuid}` **之前**，否则会被 uuid 分支吃掉半截
    //    （症状是「能力声明 404，但详情页正常」）。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    短前缀会被放行到一条根本不存在的路由上。
    // ⚠️ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `listing-details/{uuid}/lifecycle`  —— 七态状态机与平台回写归 4.1.1.3
    //   * `listing-details/{uuid}/reprice`     —— 改价归 05 定价，本服务不建影子价格表
    //   * `listing-details/{uuid}/restock`     —— 改库存归 07 库存
    //   * `listing-details/{uuid}/draft`       —— 内容草稿归 4.2.1.1，尚未交付
    //   * `listing-details/{uuid}/writeback`   —— 内容回写归 4.3.2.1，尚未交付
    //   * `listing-details/{uuid}/publish`     —— 发布流程归 4.3.1，本模块只留痕不发起
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listing\-details(?:\/(?:capabilities|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:content|diff|publish\-history|platform\-operations|content\-snapshots|publish\-records))?))?$/,
  },
  {
    // 4.1.2.2 映射版本（channel 服务）。本条目是**新增一条 services[] 元素**，不是往既有
    // trade / channel 分支的 alternation 里并分支 —— 那些分支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 4.1.1.2 `listing-details` 元素**之后**、`prefix: 'trade/'`（9.2.1.2）**之前**：
    // 各车道的插入点被既有条目隔开，git 能干净求并（与 4.2.1.2 / 4.1.1.2 同一手法）。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，`mapping-versions` 这个前缀
    // 与 `listings` / `listing-mappings` / `listing-details` / `listing-bulk-edit` 都不同首段，互不吃。
    //
    // 🔴 为什么用 `mapping-versions` 这个独立前缀而不是 `listing-mappings/{uuid}/versions`：
    // 4.1.2.1 交付时把 `/channel/listing-mappings/{uuid}/affected-orders` 显式登记成「不放行」
    // 并在它自己的白名单测试里断言 404（理由：归 4.1.2.2）。往那条分支里加子路径会
    // 让两个车道改同一行正则，合并必冲突。版本是**跨映射**的查询面（列表按 SKU / 店铺 /
    // 生效区间筛，不是挂在某条映射下面），独立前缀也更贴它的实际形状。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   mapping-versions                          版本列表（GET 真分页 + 生效区间重叠筛选）
    //   mapping-versions/capabilities             能力声明（受影响订单 / 订单→版本 available=false + ownerModule）
    //   mapping-versions/filter-options           筛选词表（不硬编码在前端）
    //   mapping-versions/{uuid}                   单版详情（与上一版的真比对 + 商品范围）
    //   mapping-versions/{uuid}/product-scope     按版本回溯商品范围（冻结快照 + 平台当前值对照）
    //   mapping-versions/{uuid}/affected-orders   受影响订单范围（返回 available=false，无 items 字段）
    // ⚠️ 判别顺序：`capabilities` / `filter-options` 必须排在 `{uuid}` **之前**，否则会被
    //    uuid 分支吃掉半截（症状是「能力声明 404，但详情页正常」）。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余分支的同一写法），不是 `[0-9a-f-]+`：
    //    短前缀会被放行到一条根本不存在的路由上。
    // ⚠️ 本模块**全是 GET**：映射变更生成新版本是 4.1.2.1 的写路径
    //    （`POST /channel/listing-mappings`），这里刻意**不放行任何写方法** ——
    //    放行一个能改写历史版本的形状，比 404 更糟。
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `mapping-versions/{uuid}/versions`      版本历史随详情一起返回，不单独开一条
    //   * `mapping-versions/{uuid}/orders`        「查看某订单用的是哪个版本」是**订单 → 版本**
    //                                              反查，归 09 订单（trade 冻结 order_line_component），
    //                                              本服务查不到订单，放行它只会让人反复试一个不存在的路
    //   * `mapping-versions/{uuid}/components`    套装组件明细归 4.1.2.3
    //   * `mapping-versions/{uuid}/revert`        版本不可回滚：改映射只会生成新版本
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^mapping\-versions(?:\/(?:capabilities|filter\-options|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:product\-scope|affected\-orders))?))?$/,
  },
  {
    // 2.4.2.3 映射缺失队列（channel 服务）。本条目是**新增一条独立的 `channel/` 元素**，
    // 不是往既有 channel 分支的超长 alternation 里并分支 —— 那一支是单行超长正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 4.1.2.2 `mapping-versions` 条目**之后**、4.1.2.3 `bundle-components`
    // 条目**之前**：落在**映射族**（`listing-mappings` / `mapping-versions`）旁边。
    // ⚠️ 与 4.4.2.1（`listing-content-writeback` 之后）、11.2.3.3（trade 组内）的插入点
    //    都拉开 30 行以上 —— 同一处插入两个元素会让 git 判冲突（结构性，retry 不会自愈）。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   mapping-gaps                 待映射清单（GET 真分页 + 六个筛选维度）
    //   mapping-gaps/summary         队列概览（状态 / 原因 / 店铺分布 + 最老的一条）
    //   mapping-gaps/filter-options  筛选面板词表（不硬编码在前端）
    //   mapping-gaps/capabilities    能力声明（未交付段 available=false + ownerModule）
    //   mapping-gaps/orders          09 订单缺映射的**事件接入点**（POST；当前 trade 侧未推）
    //   mapping-gaps/{uuid}          缺口详情（GET）
    //   mapping-gaps/{uuid}/map      映射到已有 SKU（POST）
    //   mapping-gaps/{uuid}/create-sku  登记「新建 SKU」请求（POST；**只登记意图，不建档**）
    //   mapping-gaps/{uuid}/ignore   标记忽略（POST）
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities / filter-options / summary / orders）
    //    必须排在 `{uuid}` 分支**之前**，否则会被 uuid 段吃掉半截
    //    （症状是「能力声明 404，但列表页正常」）。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    短前缀会被放行到一条根本不存在的路由上。
    // ⚠️ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`）。
    // ⚠️ 裸路径 `mapping-gaps` **放行**：本模块的列表就是 `GET /channel/mapping-gaps?...`，
    //    query string 不参与这里的比对（proxy() 拿的是 params.path，不含 search）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `mapping-gaps/{uuid}/backfill`        —— 回填由映射版本落库的 DB 触发器完成，没有独立端点
    //   * `mapping-gaps/{uuid}/affected-orders` —— 「回填受影响订单」未接线（09 订单在 trade 服务）
    //   * `mapping-gaps/{uuid}/suggest`         —— 🔴 **绝不按名称猜 SKU**：本模块不提供任何自动匹配
    //   * `mapping-gaps/batch`                  —— 批量建立映射归 4.1.2.1，本模块只逐条决策
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^mapping\-gaps(?:\/(?:capabilities|filter\-options|summary|orders|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:map|create\-sku|ignore))?))?$/,
  },
  {
    // 4.1.2.3 套装组件映射（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往 4.1.2.1 的 `listing-mappings` 或 4.1.2.2 的 `mapping-versions` 分支里并子路径
    // —— 那两条是已交付车道的成果，多车道同时改同一段正则必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 `mapping-versions` 条目**之后**、既有 `listing-publish` 条目**之前**：
    // 与同 L3 的两个兄弟相邻但仍是独立元素，git 能干净求并。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   bundle-components/capabilities                    能力声明（未交付段 available=false）
    //   bundle-components/filter-options                   筛选词表（不硬编码在前端）
    //   bundle-components/bindings                         套装版本绑定列表（GET）+ 新建绑定（POST）
    //   bundle-components/bindings/{uuid}                  单个绑定详情：版本选择 + 逐组件明细
    //   bundle-components/bindings/{uuid}/sellable-check  🔴 可售性校验结果（缺任一组件 ⇒ 整套不可售）
    //   bundle-components/components                       组件映射记录列表（GET）+ 新增组件（POST）
    //   bundle-components/components/{uuid}                改组件（PUT）/ 删除组件登记（DELETE）
    //
    // ⚠️ 判别顺序：`capabilities` / `filter-options` 必须排在 `{uuid}` **之前**，否则会被
    //    uuid 分支吃掉半截（症状是「能力声明 404，但详情页正常」）。
    // ⚠️ 两个字面量段 `bindings` / `components` 也必须在 uuid 分支之前，理由同上。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余分支的同一写法），不是 `[0-9a-f-]+`：
    //    短前缀会被放行到一条根本不存在的路由上。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `bundle-components/bindings/{uuid}/components`  组件明细随详情一起返回，不单独开一条
    //   * `bundle-components/bindings/{uuid}/orders`       「按组件预占库存」是 07 库存自己的写路径
    //                                                     （warehouse 执行），本服务不发起预占
    //   * `bundle-components/components/{uuid}/sellable-check`  可售性校验按**绑定**判定，
    //                                                     因为整套的可售性是所有组件的合取
    //   * `bundle-components/bindings/{uuid}/revert`       换版不靠 revert：生成新绑定并退役旧的，
    //                                                     已退役绑定的组件是历史，不可复活
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^bundle\-components(?:\/(?:capabilities|filter\-options|bindings(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/sellable\-check)?)?|components(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?))?$/,
  },
  {
    // 4.3.1.2 批量发布（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往 4.1.1.1 的 `listings` 分支里并子路径 —— 那条分支是已交付车道的成果，
    // 多车道同时改同一段正则必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 4.1.1.2 `listing-details` 条目**之后**、既有 `prefix: 'trade/'` 元素**之前**：
    // 与兄弟模块相邻但仍是独立元素，各车道插入点被既有条目隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 🔴 为什么用 `listing-publish` 这个独立前缀而不是 `listings/{id}/publish`：
    // 4.1.1.1 与 4.1.1.2 都把 `listings/{uuid}/publish` 显式登记成「不放行」（那是单条发布，
    // 归 4.3.1.1 / 4.3.1.3）。批量发布是**队列**（批次 + 限流 + 顺序 + 重试），
    // 与「列表行」不是同一种资源 —— 强行开在同一棵路径树上要同时改别人的白名单与拒绝清单。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   listing-publish/capabilities            能力声明（SPU 状态筛选 / 平台实际调用 available=false + ownerModule）
    //   listing-publish/rate-limits             🔴 平台限流配置（GET 查 / POST 配，可配置可查的数据）
    //   listing-publish/batches                 建批次（POST，按筛选规则）+ 批次列表（GET）
    //   listing-publish/batches/{uuid}          批次详情（总体进度五计数 + 百分比）
    //   listing-publish/batches/{uuid}/tasks    批次任务列表（GET，可按状态筛；失败明细用 state=FAILED）
    //   listing-publish/batches/{uuid}/dispatch 🔴 一轮派发：按各平台自己的配额排队
    //   listing-publish/batches/{uuid}/pause    暂停（停止派发新任务；在途不受影响）
    //   listing-publish/batches/{uuid}/resume   恢复
    //   listing-publish/batches/{uuid}/cancel   人工中止
    //   listing-publish/batches/{uuid}/reorder  调整顺序（按给的新次序重排待派发任务）
    //   listing-publish/batches/{uuid}/retry    批量重试（只重试 FAILED，不重跑已成功的）
    //   listing-publish/tasks/{uuid}/report     在途任务的成功/失败回执（4.3.1.3 回报）
    //
    // ⚠️ 判别顺序：`capabilities` 与 `rate-limits` 必须排在 `{uuid}` **之前**，
    //    否则会被 uuid 分支吃掉半截（症状是「能力声明 404，但列表页正常」）。
    // ⚠️ 四个批次动作（dispatch / pause / resume / cancel / reorder / retry）挂在
    //    **uuid 段之后**且各自成支：写成 `/batches/dispatch` 会放行一个不存在的
    //    「按批次编码派发」的形状。
    // ⚠️ 裸路径 `listing-publish` **刻意不放行**（最外层分组不带 `?`）：本模块没有裸路径资源。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `listing-publish/batches/{uuid}/retry-all` —— 没有「连成功的一起重跑」的入口，
    //     那是本模块最危险的假实现（重复发布 / 重复建 listing）
    //   * `listing-publish/tasks/{uuid}`            —— 单条任务详情随批次任务列表返回，不单开一条
    //   * `listing-publish/rate-limits/{uuid}`      —— 限流配置按 (平台, 店铺) upsert，没有单条删除
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listing\-publish(?:\/(?:capabilities|rate\-limits)|\/batches(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:tasks|dispatch|pause|resume|cancel|reorder|retry))?)?|\/tasks\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/report)$/,
  },
  {
    // 4.3.2.1 内容回写（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 上面那条 `listing-publish`（4.3.1.2 批量发布）**一字未动、也不重排** —— 本文件是多车道
    // 共用的累积型正则，取错一侧会静默删掉别人已交付的路由，症状是页面 404 而 lint/typecheck 全绿。
    // 插入锚点固定在 `listing-publish` 那组**之后**、既有 `prefix: 'trade/'` 那条**之前** ——
    // 各车道的插入点被既有条目隔开，git 能干净求并（与 9.2.1.2 / 10.1.1.2 等条目同一手法）。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 🔴 为什么用 `listing-content-writeback` 这个独立前缀，而不是 `listings/{uuid}/writeback`：
    //   4.1.1.1 已把 `listings/{uuid}` 那一段登记成「不放行」，在同一棵路径树上开子资源要同时改
    //   别人已交付的白名单与拒绝清单；而「内容回写」是**批量触发 + 队列 + 限流 + 变更历史**，
    //   与「列表行」不是同一种资源 —— 强行开在同一棵树上会与 4.1.1.1 / 4.1.1.2 的既有决定打架。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities                                能力声明（平台往返 / 上游变更事件 / 描述与 A+ 基线 available=false）
    //   writebacks                                  建一次回写（POST，按变更类型 / 批量）+ 列表（GET）
    //   writebacks/{uuid}                           单次触发的总体进度（六计数 + 百分比）
    //   writebacks/{uuid}/tasks                     任务列表（GET，可按状态筛；跳过用 SKIPPED_NO_CHANGE）
    //   writebacks/{uuid}/dispatch                  🔴 一轮派发：按各平台配额排队 + 写 02 队列
    //   writebacks/{uuid}/retry                     🔴 批量重试（只重试 FAILED，不重跑已成功的）
    //   writebacks/{uuid}/cancel                    人工中止（已在途的不取消）
    //   tasks/{uuid}/changes                        🔴 逐字段改前 / 改后（含两侧来源）
    //   tasks/{uuid}/report                         在途任务的成功 / 失败回执（4.3.2.2 回报）
    //   listings/{uuid}/content-history             某商品的变更历史（按时间倒序）
    //
    // ⚠️ 判别顺序：`capabilities` 必须排在所有 `{uuid}` 分支**之前**，否则会被 uuid 分支吃掉半截
    //    （症状是「能力声明 404，但列表页正常」）。
    // ⚠️ 三个 uuid 段各自分支：`writebacks/{uuid}` / `tasks/{uuid}/changes|report` /
    //    `listings/{uuid}/content-history`。⚠️ `tasks` 那段的**动作段是必选的**
    //    （`(?:...)?` 会连 `tasks/{uuid}` 裸路径一起放行 —— 后端没有这个资源，
    //    单条任务详情随任务列表返回），写成可选就成了一个凭空多出来的 GET。
    // ⚠️ 裸路径 `listing-content-writeback` **刻意不放行**（最外层分组不带 `?`）：本模块没有裸路径资源。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `listing-content-writeback/writebacks/{uuid}/writebacks` —— 一次触发只回写一次，没有嵌套触发
    //   * `listing-content-writeback/tasks/{uuid}`                    —— 单条任务详情随任务列表返回，不单开一条
    //   * `listing-content-writeback/writebacks/{uuid}/preview`        —— 回写不是编辑，没有「确认前先看看」
    //   * `listing-content-writeback/prices|stocks`                    —— 🔴 那是 05 定价 / 07 库存两条独立通道
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listing\-content\-writeback(?:\/capabilities|\/writebacks(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:tasks|dispatch|retry|cancel))?)?|\/tasks\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/(?:changes|report)|\/listings\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/content\-history)$/,
  },
  {
    // 4.2.1.1 刊登编辑器（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 channel 分支的超长 alternation 里并分支 —— 那一支是单行超长正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 4.4.2.1 `listing-claims` 元素**之前** —— 与其它车道
    // （`listing-claims` 之后 / `crm/` 之前 / `services` 数组最末尾）都不重叠。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 🔴 为什么用 `listing-editor` 这个独立前缀而不是 `listings/{id}/editor`：
    //   4.1.1.1 交付时把 `/channel/listings/{uuid}/...` 那一段登记成「不放行」，
    //   在同一棵路径树上开子资源要同时改它的白名单与拒绝清单，而那份清单是别人的成果。
    //   而且「编辑」本来就不是 listing 的一种状态 —— 草稿按 **平台 + 站点 + 语言** 存，
    //   一条 listing 在不同语言下就是多份草稿，挂 listing 上会把这个维度压扁。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   listing-editor/capabilities            能力声明（未交付段 available=false + ownerModule）
    //   listing-editor/form-schema             🔴 动态表单契约（字段集由平台要求推导）
    //   listing-editor/validate                字段级校验试算（不落库）
    //   listing-editor/drafts                  草稿列表（GET）
    //   listing-editor/drafts/{uuid}           单条草稿（GET）
    //   listing-editor/drafts/{uuid}/submit    🔴 提交审核 = 入队（POST）
    //   listing-editor/submissions             入队队列（GET）
    //   listing-editor/source-product          源商品（03，只读）
    //   listing-editor/media-assets            媒体库素材（03，只读）
    //   listing-editor/drafts（POST）          保存草稿（同一形状两种方法）
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities / form-schema / validate / drafts / submissions /
    //    source-product / media-assets）**排在** {uuid} 段之前，否则会被 uuid 段吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    // ⚠️ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`，
    //    只锚定首尾两项，中间分支静默失效且不报错）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `listing-editor/drafts/{uuid}/publish`  —— 🔴 派发归 4.3.1.1，本模块只入队
    //   * `listing-editor/drafts/{uuid}/dispatch` —— 同上
    //   * `listing-editor/drafts/{uuid}/apply-template` —— 🔴 4.2.2.1 刊登模板未交付
    //   * `listing-editor/drafts/{uuid}/delete`   —— 草稿是内容事实，不提供删除
    //   * `listing-editor/templates`              —— 4.2.2.1 刊登模板未交付
    //   * `listing-editor/listing/{uuid}`        —— 没有"按 listing 取编辑器"这个形状
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listing\-editor(?:\/capabilities|\/form\-schema|\/validate|\/submissions|\/source\-product|\/media\-assets|\/drafts(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/submit)?)?)?$/,
  },
  {
    // 4.2.3.2 合规预检（违禁词/侵权）（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 channel 分支的超长 alternation 里并分支 —— 那一支是单行超长正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 4.2.1.1 `listing-editor` 元素**之后**、4.4.2.1 `listing-claims` 元素**之前**。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 🔴 为什么用 `listing-compliance-precheck` 这个独立前缀：
    //   * 它**不是** `compliance-precheck` —— 那是同批并行那条车道（16.2.2.1 合规体检报告，
    //     合规域 D7）的前缀。两边名字几乎一样但**不共表、不共端点、不共页面**，
    //     用错前缀会打到别人的端点上去。
    //   * 它也**不是** `listings/{id}/compliance` —— `/channel/listings/{uuid}/...` 那一段
    //     由 4.1.1.1 登记成「不放行」，在同一棵路径树上开子资源要同时改它的白名单与拒绝清单，
    //     而那份清单是别人的成果。而且「合规预检」不是 listing 的一种状态，
    //     它是**发布前对一份草稿的一次扫描**（按 平台 + 站点 + 语言 分别存的那一份内容）。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   listing-compliance-precheck/capabilities              能力声明（未交付段 available=false + ownerModule）
    //   listing-compliance-precheck/filter-options           筛选项词表
    //   listing-compliance-precheck/drafts                   可扫描的编辑器草稿（GET）
    //   listing-compliance-precheck/drafts/{uuid}/publish-gate  🔴 服务端发布闸门（GET）
    //   listing-compliance-precheck/scans                    触发扫描（POST）/ 扫描历史（GET）
    //   listing-compliance-precheck/scans/{uuid}             单次扫描详情（GET）
    //   listing-compliance-precheck/risks/decisions          风险批量处置（POST）
    //   listing-compliance-precheck/words                    词库（GET）/ 新增词（POST）
    //   listing-compliance-precheck/words/{uuid}             改词（PUT）/ 删词（DELETE）
    //   listing-compliance-precheck/words/{uuid}/enabled     启停词（PUT）
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities / filter-options / drafts / scans / risks / words）
    //    **排在** {uuid} 段之前，否则会被 uuid 段吃掉半截。
    //    `drafts/{uuid}/publish-gate` 这一支排在裸 `drafts/{uuid}` 段之前（首段是字面量 `drafts`，
    //    与 uuid 段不会互相吃掉，但顺序保持「字面量在前」这一条文件的统一口径）。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    // ⚠️ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`，
    //    只锚定首尾两项，中间分支静默失效且不报错）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `listing-compliance-precheck/drafts/{uuid}/publish`  —— 🔴 发布归 4.3.1.1，本模块只出闸门结论
    //   * `listing-compliance-precheck/drafts/{uuid}/dispatch` —— 同上
    //   * `listing-compliance-precheck/scans/{uuid}/publish`   —— 同上
    //   * `listing-compliance-precheck/compliance-precheck`   —— 🔴 那是 16.2.2.1 的前缀，不属于本模块
    //   * `listing-compliance-precheck/words/{uuid}/scan`     —— 扫描是针对草稿的，不是针对词的
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listing\-compliance\-precheck(?:\/capabilities|\/filter\-options|\/risks\/decisions|\/drafts(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/publish\-gate)?|\/scans(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|\/words(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/enabled)?)?)$/,
  },
  {
    // 4.5.2.1 跟卖监控（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 channel 分支的超长 alternation 里并分支 —— 那一支是单行超长正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 4.2.3.2 `listing-compliance-precheck` 那条**之后**、
    // 4.4.2.1 `listing-claims` 那条**之前** —— 与既有条目被隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 🔴 为什么用 `listing-hijackers` 这个独立前缀：
    //   * 跟卖在跨境电商语境里英文是 **hijacker**（抢listing的人），所以前缀是 `listing-hijackers`。
    //   * 它**不是** `listing-claims`（4.4.2.1 商品认领，跨店搬家的 listing 草稿）——
    //     那是"把 listing 搬走"，本模块是"盯着别人抢我的 listing"，两码事。
    //   * 它**不是** `listing-compliance-precheck`（4.2.3.2 发布前的违禁词/侵权扫描）——
    //     那是"扫内容"，本模块是"看价格"。
    //   * 它**不是** `listing-keyword-rankings`（4.5.1.2 关键词排名，未交付）——
    //     那是"看排名"，与跟卖价无关。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   listing-hijackers/capabilities              能力声明（未交付段 available=false + ownerModule）
    //   listing-hijackers/filter-options           筛选项词表
    //   listing-hijackers/watches                   监控对象列表（GET）/ 新建监控对象（POST）
    //   listing-hijackers/watches/{uuid}           改监控对象（PUT）/ 删除监控对象（DELETE）
    //   listing-hijackers/records                   跟卖记录列表（GET）/ 接收跟卖信息（POST）
    //   listing-hijackers/alerts                    预警记录列表（GET）
    //   listing-hijackers/alerts/{uuid}/acknowledge 标记预警已阅（PUT）
    //   listing-hijackers/comparison               价格对比视图（GET）
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities / filter-options / watches / records / alerts /
    //    comparison）**排在** {uuid} 段之前，否则会被 uuid 段吃掉半截。
    //    `alerts/{uuid}/acknowledge` 这一支排在裸 `alerts` 段之后 —— 首段是字面量 `alerts`，
    //    与 uuid 段不会互相吃掉，但顺序保持「字面量在前」这一条文件的统一口径。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    // ⚠️ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`，
    //    只锚定首尾两项，中间分支静默失效且不报错）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `listing-hijackers/{uuid}/complaint`       —— 🔴 投诉/举报/申诉在**平台侧**完成，
    //     本模块只负责发现与提醒，**没有**这些动作端点
    //   * `listing-hijackers/{uuid}/report`          —— 同上
    //   * `listing-hijackers/{uuid}/appeal`          —— 同上
    //   * `listing-hijackers/notifications`          —— 🔴 1 工作台预警未交付（available=false）
    //   * `listing-hijackers/brand-protection`        —— 🔴 16 合规品牌保护未交付（available=false）
    //   * `listing-hijackers/crawlers`                —— 🔴 02 抓取调度器未交付（available=false）
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listing\-hijackers(?:\/capabilities|\/filter\-options|\/comparison|\/watches(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|\/records|\/alerts(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/acknowledge)?)$/,
  },
  {
    // 4.4.2.1 商品认领（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 channel 分支的超长 alternation 里并分支 —— 那一支是单行超长正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 `listing-content-writeback` 那条**之后**、既有 `prefix: 'trade/'`
    // 元素**之前** —— 与既有条目被隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 🔴 为什么用 `listing-claims` 这个独立前缀而不是 `listings/{id}/claim`：
    //   4.1.1.1 交付时把 `/channel/listings/{uuid}/...` 那一段登记成「不放行」，
    //   在同一棵路径树上开子资源要同时改它的白名单与拒绝清单，而那份清单是别人的成果。
    //   而且「认领」本来就不是 listing 的一种状态 —— 它是**一次跨店搬家的操作**，
    //   挂在 listing 上会让"这个商品的状态"多出第七种含义。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   listing-claims                        认领历史（GET 真分页 + 状态/目标店铺筛选）/ 发起认领（POST）
    //   listing-claims/capabilities           能力声明（未交付段 available=false + ownerModule）
    //   listing-claims/{uuid}                 单次认领详情（GET）
    //   listing-claims/listings/{uuid}/claims 某个商品的认领历史（作为源或作为目标）
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities）**排在** uuid 段之前，
    //    `listings/{uuid}/claims` 这一支也排在裸 uuid 段之前（首段是字面量 `listings`，
    //    与 uuid 段不会互相吃掉，但顺序保持"字面量在前"这一条文件的统一口径）。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    // ⚠️ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`，
    //    只锚定首尾两项，中间分支静默失效且不报错）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `listing-claims/{uuid}/publish`   —— 🔴 派发归 4.3.1.2 批量发布，本模块只入队
    //   * `listing-claims/{uuid}/dispatch`  —— 同上，平台限流与派发都在 4.3.1.2 那条通道
    //   * `listing-claims/{uuid}/abandon`   —— 认领记录是只追加的事实，没有"作废"这个动作
    //   * `listing-claims/{uuid}/prices|stocks` —— 🔴 那是 05 定价 / 07 库存两条独立通道
    //   * `listing-claims/{uuid}/fields`     —— 逐字段决策随详情一起返回，不单开一条
    //   * 裸 `listing-claims/{uuid}/listings` —— 没有"某次认领的商品列表"这个形状
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listing\-claims(?:\/capabilities|\/listings\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/claims|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?$/,
  },
  {
    // 4.4.1.1 链接采集（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 channel 分支的超长 alternation 里并分支 —— 那一支是单行超长正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //
    // 插入锚点固定在 4.4.2.1 `listing-claims` 那一条**之后** ——
    // 与本批另外三条车道的锚点（`listings` / `listing-status-events` / `one-click-publish` 之后）互不重叠，
    // git 能干净求并。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   listing-collects                    采集任务列表（GET，真分页 + 状态/来源筛选）/ 发起一次链接采集（POST）
    //   listing-collects/capabilities        能力声明（未交付段 available=false + ownerModule）
    //   listing-collects/{uuid}              采集任务详情（GET）
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities）必须排在 uuid 段**之前**判别。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `listing-collects/{uuid}/draft`   —— 草稿随任务详情一起返回，不单开一条
    //   * `listing-collects/{uuid}/retry`   —— 失败重试就是再发一次同一个 URL（后端自己去重）
    //   * `listing-collects/{uuid}/claim`   —— 🔴 认领是 4.4.1.3 采集箱的职责，尚未交付
    //   * `listing-collects/batch`          —— 🔴 批量/插件采集是 4.4.1.2 的职责，尚未交付
    //   * `listing-collects/box`            —— 🔴 采集箱是 4.4.1.3 的职责，尚未交付
    //   * `listing-collects/sources`        —— 白名单随能力声明一起返回，不单开一条
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern: /^listing\-collects(?:\/capabilities|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?$/,
  },
  {
    // 4.4.1.2 批量/插件采集（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 channel 分支的超长 alternation 里并分支 —— 那一支是单行超长正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //
    // 插入锚点固定在 4.4.1.1 `listing-collects` 那一条**之后** ——
    // 与本批另外三条车道的锚点互不重叠，git 能干净求并。
    //
    // 🔴 为什么用 `listing-bulk-collects` 这个**独立前缀**而不是 `listing-collects/batch`：
    //   4.4.1.1 的 `listing-collects` 元素刻意**没有**放行 `/batch`（那是本模块的职责）。
    //   若把批量采集挂在它下面，就必须去改别人已交付的那一条元素 —— 那是别人的成果。
    //   批量采集本来就不是「一次链接采集」的一种，它是一批 URL 的批量动作，
    //   独立前缀更贴切（与 4.4.2.1 `listing-claims`、4.3.2.2 `listing-status-events` 同一手法）。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   listing-bulk-collects                     批量任务列表（GET，真分页 + 模式/状态/来源筛选）/ 发起批量采集（POST）
    //   listing-bulk-collects/capabilities        能力声明（未交付段 available=false + ownerModule）
    //   listing-bulk-collects/plugin-reports      浏览器插件上报入口（POST）
    //   listing-bulk-collects/{uuid}              批量任务详情（GET）
    //   listing-bulk-collects/{uuid}/results      结果清单（GET，真分页 + 状态筛选）
    //   listing-bulk-collects/{uuid}/run          执行一次批量采集（POST）
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities / plugin-reports）必须排在 uuid 段**之前**，
    //    否则会被 uuid 段吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `listing-bulk-collects/box`             —— 🔴 采集箱是 4.4.1.3 的职责，尚未交付
    //   * `listing-bulk-collects/{uuid}/claim`     —— 🔴 认领是 4.4.1.3 采集箱的职责，尚未交付
    //   * `listing-bulk-collects/{uuid}/draft`    —— 草稿随结果清单一起返回，不单开一条
    //   * `listing-bulk-collects/{uuid}/retry`    —— 继续采集就是再调一次 `/run`（服务端保留断点）
    //   * `listing-bulk-collects/undeclared-action` —— 不存在的动作
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listing\-bulk\-collects(?:\/capabilities|\/plugin\-reports|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/run|\/results)?|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?$/,
  },
  {
    // 4.3.2.2 平台状态回流（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 channel 分支的超长 alternation 里并分支 —— 那一支是单行超长正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在既有 `listing-claims` 那一条**之后**（4.4.2.1 商品认领已交付），
    // 与既有条目被隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 🔴 为什么用 `listing-status-events` 这个独立前缀而不是 `listings/{id}/status-events`：
    //   4.1.1.1 交付时把 `/channel/listings/{uuid}/...` 那一段登记成「不放行」，
    //   在同一棵路径树上开子资源要同时改它的白名单与拒绝清单，而那份清单是别人的成果。
    //   而且「平台状态回流」与「内容回写」是**两条独立通道**（一个把内部变更推出去、
    //   一个把平台状态收回来），状态机、限流与重试互不干涉 —— 挂在 listing 上会让
    //   "这个商品的状态"多出第九种含义。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   listing-status-events                            ✗ 刻意**不放行**：本模块没有裸路径资源
    //   listing-status-events/capabilities               能力声明（未交付段 available=false + ownerModule）
    //   listing-status-events/events                     状态变更记录（GET 真分页）/ 接收一条平台事件（POST）
    //   listing-status-events/events/{uuid}              单条状态变更记录（GET）
    //   listing-status-events/queue                      需处理队列（GET 真分页 + 按驳回原因筛选）
    //   listing-status-events/queue/{uuid}               单条队列项（GET）
    //   listing-status-events/queue/{uuid}/acknowledge   人工确认（POST）
    //   listing-status-events/queue/{uuid}/resolve       人工处理完成（POST）
    //   listing-status-events/conflicts                  冲突与告警（GET 真分页）
    //   listing-status-events/actions/republish          一键处理：按驳回原因批量修正重发（POST）
    //   listing-status-events/listings/{uuid}/history     某个商品的状态变更记录（GET）
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities / events / queue / conflicts / actions/republish）
    //    **排在** uuid 段之前，否则会被 uuid 段吃掉半截；`listings/{uuid}/history` 这一支的
    //    首段是字面量 `listings`，与 uuid 段不会互相吃掉，但顺序保持"字面量在前"这一条文件的统一口径。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    // ⚠️ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`，
    //    只锚定首尾两项，中间分支静默失效且不报错）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `listing-status-events`                      —— 裸路径：本模块没有裸资源
    //   * `listing-status-events/{uuid}`                —— 单条事件在 `events/{uuid}` 下，不在裸 uuid 下
    //   * `listing-status-events/events/{uuid}/dispatch` —— 🔴 派发归 4.3.1.2 批量发布与 02 限流队列
    //   * `listing-status-events/events/{uuid}/report`  —— 4.3.2.1 的任务回执形状在**它自己的**前缀下
    //   * `listing-status-events/events/{uuid}/republish` —— 动作统一在 `actions/republish`，不按事件寻址
    //   * `listing-status-events/queue/{uuid}/prices|stocks` —— 🔴 那是 05 定价 / 07 库存两条独立通道
    //   * `listing-status-events/actions/publish`       —— 4.3.1.2 批量发布
    //   * `listing-status-events/actions/preview`       —— 本模块没有"确认前先看看"这个状态
    //   * `listing-status-events/lifecycle`             —— 4.1.1.3 生命周期与下架（未交付）
    //   * `listing-status-events/publish-tasks`         —— 4.3.1.3 发布任务（未交付）
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listing\-status\-events(?:\/capabilities|\/events(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|\/queue(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:acknowledge|resolve))?)?|\/conflicts|\/actions\/republish|\/listings\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/history)$/,
  },
  {
    // 4.5.1.1 Listing 表现（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 channel 分支的超长 alternation 里并分支 —— 那一支是单行超长正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //
    // 插入锚点固定在 4.3.2.2 `listing-status-events` 那一条**之后** ——
    // 与本批另外三条车道的锚点（`listings` / `listing-claims` / `one-click-publish` 之后）互不重叠，
    // git 能干净求并。
    //
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 🔴 为什么用 `listing-performance` 这个独立前缀而不是 `listings/{id}/performance`：
    //   4.1.1.1 交付时把 `/channel/listings/{uuid}/...` 那一段路径登记成「不放行」，
    //   在同一棵路径树上开子资源要同时改它的白名单与拒绝清单，而那份清单是别人的成果。
    //   而且「平台表现」与「平台状态回流」本来就是**两条独立通道**（一个把状态收回来、
    //   一个把表现收回来），口径、限流与重试互不干涉。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   listing-performance                    表现快照列表（GET 真分页 + 商品/店铺/平台/时间范围筛选）
    //                                          / 接收平台表现数据同步结果（POST，批次级幂等）
    //   listing-performance/capabilities       能力声明（卷 13 / 卷 14 未交付 ⇒ available=false + ownerModule）
    //   listing-performance/{uuid}             单个 listing 的表现明细（GET）+ 时间范围对比
    //   listing-performance/{uuid}/trend       趋势序列（GET，日/周/月）
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities）必须排在 uuid 段**之前**判别。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    // ⚠️ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`，
    //    只锚定首尾两项，中间分支静默失效且不报错）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `listing-performance/syncs`         —— 同步结果是入库回执，不单开一条查询形状
    //   * `listing-performance/{uuid}/orders`  —— 🔴 内部订单是 09 的事实，本模块不跨服务读它
    //   * `listing-performance/{uuid}/ads`     —— 🔴 14 广告关联投放（整卷未交付）
    //   * `listing-performance/{uuid}/product` —— 🔴 13 产品表现下钻（整卷未交付）
    //   * `listing-performance/{uuid}/sync`    —— 同步走 POST 集合入口，不按商品寻址
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listing\-performance(?:\/capabilities|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/trend)?)?$/,
  },
  {
    // 4.5.1.2 关键词排名（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 channel 分支的超长 alternation 里并分支 —— 那一支是单行超长正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //
    // 插入锚点固定在 4.5.1.1 `listing-performance` 那一条**之后** ——
    // 与本批另外三条车道的锚点互不重叠，git 能干净求并。
    //
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 🔴 为什么用 `listing-keyword-rankings` 这个独立前缀而不是 `listings/{id}/keywords`：
    //   4.1.1.1 交付时把 `/channel/listings/{uuid}/...` 那一段路径登记成「不放行」，
    //   在同一棵路径树上开子资源要同时改它的白名单与拒绝清单，而那份清单是别人的成果。
    //   而且「关键词排名」与「平台表现」「平台状态回流」本来就是**三条独立通道**
    //   （一个把名次收回来、一个把表现收回来、一个把状态收回来），
    //   口径、限流与重试互不干涉。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   listing-keyword-rankings                     关键词集合列表（GET 真分页）/ 新建关键词（POST）
    //   listing-keyword-rankings/capabilities        能力声明（1 工作台 / 14 广告 / 02 抓取调度器未交付
    //                                               ⇒ available=false + ownerModule）
    //   listing-keyword-rankings/filter-options      筛选面板选项（平台 / 店铺 / 站点）
    //   listing-keyword-rankings/crawls              接收排名抓取任务（POST，批次级幂等）
    //   listing-keyword-rankings/alerts              变化预警列表（GET 真分页）
    //   listing-keyword-rankings/alerts/{uuid}/acknowledge  人工确认一条预警（PUT）
    //   listing-keyword-rankings/report              关键词维度报表（GET，真 SQL 聚合）
    //   listing-keyword-rankings/{uuid}              单个关键词的排名明细（GET）/ 改阈值（PUT）/ 停用（DELETE）
    //   listing-keyword-rankings/{uuid}/history      排名历史（GET，真分页）
    //   listing-keyword-rankings/{uuid}/trend        趋势序列（GET，日/周/月）
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities / filter-options / crawls / alerts）必须排在
    //    {uuid} 段**之前**判别，否则会被 {uuid} 通配支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    // ⚠️ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`，
    //    只锚定首尾两项，中间分支静默失效且不报错）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `listing-keyword-rankings/undeclared-action` —— 本模块**确定没有**的动作
    //   * `listing-keyword-rankings/{uuid}/crawls`      —— 抓取走集合入口，不按关键词寻址
    //   * `listing-keyword-rankings/{uuid}/alerts`     —— 预警是集合查询，不按关键词寻址
    //   * `listing-keyword-rankings/listing-hijackers` —— 🔴 4.5.2.1 跟卖监控（别的模块的职责）
    //   * `listing-keyword-rankings/{uuid}/ads`        —— 🔴 14 广告关联投放（整卷未交付）
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listing\-keyword\-rankings(?:\/capabilities|\/filter\-options|\/crawls|\/report|\/alerts(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/acknowledge)?|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/history|\/trend)?)?$/,
  },
  {
    // 4.4.2.2 批量搬迁（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 channel 分支的超长 alternation 里并分支 —— 那一支是单行超长正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在既有 `prefix: 'trade/'`（9.2.1.2 审单规则）那一条**之前** ——
    // 与既有条目被隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 🔴 为什么用 `listing-migrations` 这个独立前缀而不是 `listings/{id}/migrations`：
    //   4.1.1.1 交付时把 `/channel/listings/{uuid}/...` 那一段登记成「不放行」，
    //   在同一棵路径树上开子资源要同时改它的白名单与拒绝清单，而那份清单是别人的成果。
    //   而且「批量搬迁」是**一次批量操作**，不是某个商品的状态 —— 挂在 listing 上
    //   会让"这个商品的状态"多出第八种含义。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   listing-migrations                        搬迁历史（GET 真分页）/ 执行搬迁（POST）
    //   listing-migrations/capabilities           能力声明（未交付段 available=false + ownerModule）
    //   listing-migrations/filter-options         筛选面板选项（源店铺 + 真实类目）
    //   listing-migrations/preview                预览：筛选命中什么 + 风险 + 标题差异化（POST，不写库）
    //   listing-migrations/duplicate-risks        「重复铺货风险」查询（GET 真分页）
    //   listing-migrations/migrated               「已搬迁」查询（GET 真分页）
    //   listing-migrations/{uuid}                 单个批次详情（GET）
    //   listing-migrations/{uuid}/items           批次的结果明细（含差异化留痕与风险依据）
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities / filter-options / preview / duplicate-risks / migrated）
    //    **排在** uuid 段之前，否则会被 uuid 段吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    // ⚠️ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`，
    //    只锚定首尾两项，中间分支静默失效且不报错）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `listing-migrations/{uuid}/publish`   —— 🔴 派发归 4.3.1.2 批量发布，本模块只入队
    //   * `listing-migrations/{uuid}/dispatch`  —— 同上，平台限流与派发都在 4.3.1.2 那条通道
    //   * `listing-migrations/{uuid}/re-run`    —— 幂等键变了就是**另一个批次**，没有"重跑"这个动作
    //   * `listing-migrations/{uuid}/abort`     —— 搬迁结果是只追加的事实，没有"作废"这个动作
    //   * `listing-migrations/{uuid}/diffs`     —— 差异化留痕随结果明细返回，不单开一条
    //   * `listing-migrations/prices|stocks`    —— 🔴 那是 05 定价 / 07 库存两条独立通道
    //   * `listing-migrations/risks`            —— 风险查询的路径是 `duplicate-risks`
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listing\-migrations(?:\/capabilities|\/filter\-options|\/preview|\/duplicate\-risks|\/migrated|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/items)?)?$/,
  },
  {
    // 4.3.1.1 一键刊登（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 channel 分支的超长 alternation 里并分支 —— 那一支是单行超长正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在既有 `listing-migrations`（4.4.2.2 批量搬迁）那一条**之后** ——
    // 与既有条目被隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 🔴 为什么用 `one-click-publish` 这个独立前缀：
    //   4.3.1.2 批量发布已经占了 `/channel/listing-publish`（batches / rate-limits /
    //   dispatch / pause / resume / cancel / reorder / retry）。两条通道的语义完全不同 ——
    //   一键刊登是「一个 SKU → N 个目标，每个目标各自成败、各自重试」，
    //   批量发布是「一个批次 → 一条队列，按平台 API 配额排队」。
    //   共用前缀会让「一个平台失败」被误写成「整批失败」，正是蓝图那句加粗要防的事。
    //   也不用 `listing-publish-jobs`：既有正则锚定 `^listing\-publish(...)?$`，
    //   虽不匹配，但极易被后来人误写成 `listing\-publish\-jobs` 而互相吃掉。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   one-click-publish/capabilities            能力声明（未交付段 available=false + ownerModule）
    //   one-click-publish/target-options          目标店铺 / 站点候选（只列已启用站点）
    //   one-click-publish/content-sources         内容来源候选（编辑器入队记录 + 刊登模板）
    //   one-click-publish/jobs                    作业列表（GET）+ 建作业（POST，幂等）
    //   one-click-publish/jobs/{uuid}             单个作业详情（GET）
    //   one-click-publish/jobs/{uuid}/targets     该作业的发布任务（每个目标一个）
    //   one-click-publish/jobs/{uuid}/steps       该作业的步骤时间线
    //   one-click-publish/jobs/{uuid}/dispatch    派发（逐目标独立，一个失败不影响其他）
    //   one-click-publish/targets/{uuid}          单个发布任务（GET）
    //   one-click-publish/targets/{uuid}/report   平台回执（成功 / 驳回 / 失败）
    //   one-click-publish/targets/{uuid}/retry    独立重试（只重试失败的那一个）
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities / target-options / content-sources）
    //    **排在** {uuid} 段之前，否则会被 uuid 段吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    // ⚠️ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`，
    //    只锚定首尾两项，中间分支静默失效且不报错）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸 `one-click-publish`            —— 后端没有「列出全部」的映射在裸路径上
    //   * `one-click-publish/jobs/{uuid}/cancel` —— 撤销未实现
    //   * `one-click-publish/jobs/{uuid}/timeline` —— 单次发布任务的时间线页归 4.3.1.3（未交付）
    //   * `one-click-publish/jobs/{uuid}/prices|stocks` —— 🔴 那是 05 定价 / 07 库存两条独立通道
    //   * `one-click-publish/{uuid}`        —— 没有「按 id 直接取作业」的裸形状
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^one\-click\-publish(?:\/capabilities|\/target\-options|\/content\-sources|\/jobs(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:targets|steps|dispatch))?)?|\/targets\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:report|retry))?)$/,
  },
  {
    // 4.3.1.3 发布任务（channel 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 channel 分支的超长 alternation 里并分支 —— 那一支是单行超长正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    //
    // 插入锚点固定在 4.3.1.1 `one-click-publish` 那一条**之后** ——
    // 与本批另外三条车道的锚点（`listings` / `listing-claims` / `listing-status-events` 之后）互不重叠，
    // git 能干净求并。
    //
    // 🔴 为什么用 `listing-publish-tasks` 这个独立前缀：
    //   4.3.1.1 一键刊登占了 `/channel/one-click-publish`（一个 SKU → N 个目标，各自成败），
    //   4.3.1.2 批量发布占了 `/channel/listing-publish`（一个批次 → 一条队列，按平台配额排队），
    //   本模块是**单次发布任务的进度视图**（步骤时间线 + 错误明细 + 重试操作）。
    //   三者是三套独立的状态机，共用前缀会让「一个平台失败」被误写成「整批失败」。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   listing-publish-tasks/capabilities               能力声明（阶段→步骤键映射 + 未交付段）
    //   listing-publish-tasks/tasks                      任务列表（GET，真分页 + 来源/阶段/平台/店铺筛选）
    //   listing-publish-tasks/tasks/{uuid}               任务详情（步骤时间线 + 错误明细 + 重试尝试）
    //   listing-publish-tasks/tasks/{uuid}/steps         步骤时间线（单独一条，便于只刷新时间线）
    //   listing-publish-tasks/tasks/{uuid}/retry         任务级重试（整条任务重来）
    //   listing-publish-tasks/tasks/{uuid}/steps/{key}/retry  步骤级重试
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities / tasks）必须排在 uuid 段**之前**判别。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    // ⚠️ steps 段下的 stepKey 只放行 4.3.1.1 落的八个步骤键（PRICE / STOCK 是作业级步骤，
    //    也在同一段里 —— 它们出现在**作业级**时间线上，不是目标级）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸 `listing-publish-tasks`        —— 后端没有「列出全部」的映射在裸路径上
    //   * `listing-publish-tasks/jobs`      —— 作业归 4.3.1.1（one-click-publish）
    //   * `listing-publish-tasks/batches`   —— 批次归 4.3.1.2（listing-publish）
    //   * `listing-publish-tasks/tasks/{uuid}/cancel` —— 撤销未实现
    //   * `listing-publish-tasks/tasks/{uuid}/report` —— 平台回执归 4.3.1.1 / 4.3.2.2
    //   * `listing-publish-tasks/tasks/{uuid}/prices|stocks` —— 🔴 那是 05 定价 / 07 库存两条独立通道
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listing\-publish\-tasks(?:\/capabilities|\/tasks(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:steps(?:\/(?:TEMPLATE|CONTENT|MAPPING|MEDIA|PRICE|STOCK|SUBMIT|RESULT)\/retry)?|retry))?)?)$/,
  },
  {
    // 9.2.1.2 审单规则（trade 服务）。本条目是**新增一条 services[] 元素**，不是往既有
    // trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在既有 `prefix: 'trade/'` **之前**：各车道的插入点被既有条目隔开，
    // git 能干净求并（与 9.3.1.2 合单 / 10.1.1.2 等条目同一手法）。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities                     能力与上下游可用性（含 shop_code / 买家等级的缺口）
    //   rules                            规则列表（GET 真分页 + POST 新建）
    //   rules/{uuid}                     规则详情（GET 头+条件 / PUT 更新）
    //   rules/{uuid}/status              切换规则状态（DRAFT / ACTIVE / DISABLED）
    //   buyer-profiles                   买家画像列表（GET）+ 登记更新（PUT upsert）
    //   orders/{uuid}/review             对真实订单跑一次审单判定（POST，落规则生效记录）
    //   orders/{uuid}/trial              拿真实订单试跑规则（POST，标 DRY_RUN 不冒充结论）
    //   evaluations                      判定记录列表（GET 真分页）
    //   evaluations/{uuid}               判定详情（结论 + 完整命中链 + 订单事实）
    // 判别顺序：字面量子路径（capabilities / rules / buyer-profiles / evaluations）
    // **排在** {uuid} 分支之前，否则会被 uuid 分支吃掉半截。
    // ⚠️ orders/{uuid}/review|trial 两段都排在规则子资源之后 —— orders 这一层先由字面量
    //    `orders` 认领，再进 uuid 与动作段，不会与 rules 的分支互相干扰。
    // ⚠️ 刻意**没有** DELETE：规则一旦参与过判定，它就是「这单为什么被拦」的凭据；
    //    删掉只会停用（status=DISABLED），不会让历史命中链断掉。
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `order-review/orders`（裸路径）—— 后端没有「列出全部订单」的映射，
    //     本模块只对调用方给定的 order_id 判定
    //   * `order-review/rules/{uuid}/delete` —— 见上，规则不提供删除
    //   * `order-review/evaluations/{uuid}/events` —— 判定是 append-only 留痕，没有事件流
    //   * `order-review/bulk-review` —— 批量判定属 9.2.1.1 待审队列（未交付）
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^order\-review\/(?:capabilities|buyer\-profiles|evaluations(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|rules(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/status)?)?|orders\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/(?:review|trial))$/,
  },
  {
    // 9.1.2.1 平台订单同步（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在既有 `order-review` 那一条**之后**：各车道的插入点被既有条目隔开，
    // git 能干净求并（与 9.2.1.2 / 11.3.2.1 等条目同一手法）。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities                能力声明（状态词表 / 下游缺口 / 金额与归一口径）
    //   webhook                     平台事件实时受理（POST）
    //   poll                        按来源 + 时间窗轮询补拉（POST）
    //   batches                     同步批次列表（GET，真分页 + 汇总）
    //   batches/{uuid}              批次详情（逐条受理结果 + 异常明细）
    //   orders                      平台单列表（GET，真分页 + 汇总）
    //   orders/{uuid}               平台单详情（投影 + 组件展开 + 受理记录）
    //   exceptions                  异常明细列表（GET，真分页 + 按错误码汇总）
    //   mappings                    映射副本列表（GET）+ 租户自维护登记（POST）
    //   mappings/sync               从 2.1.2.1（channel）同步映射副本（POST）
    //
    // ⚠️ 首段 `platform-orders` 是**独立字面量**：与既有 trade 分支的 `order-review`、
    //    `order-intercept`、`orders\/risk-validation` 互不吃（首段不同），
    //    所以本条放在哪个位置都不会误伤它们。
    // ⚠️ 判别顺序：字面量子路径（capabilities / webhook / poll / exceptions /
    //    mappings / batches / orders）必须排在 {uuid} 段**之前**。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），
    //    不是 `[0-9a-f-]+` —— 后者把 `c`、`e`、`f` 这类单字符也当成合法 id 放行。
    // ⚠️ 裸路径 `platform-orders` 本身**不放行**：后端没有这个映射，
    //    放行它会让界面以为「一条不存在的概览接口」可用。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `platform-orders/orders/{uuid}/review` —— 9.2 审单的批量判定不属本车道
    //   * `platform-orders/orders/{uuid}/fulfillment` —— 9.3 履约各动作不属本车道
    //   * `platform-orders/sales` —— 13 销量的形状不属本车道
    //   * `platform-orders/mappings/{uuid}` —— 映射副本只有列表与登记，没有按 id 寻址
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^platform\-orders\/(?:capabilities|webhook|poll|exceptions|mappings(?:\/sync)?|batches(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|orders(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?)$/,
  },
  {
    // 11.3.2.1 换货授权（trade 服务）。本条目同样是**新增一条 services[] 元素**，
    // 不往既有 trade 分支的超长单行 alternation 里并分支 —— 那一支多车道同时并进去
    // git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 11.3.1.2 `refunds` 那条**之前**：前缀形状互不相交
    // （`aftersales/exchanges/…` vs `refunds/…`），谁先被匹配都互不吃。
    //
    // ⚠️ **这条是「页面能真的通」的必要条件**：allowlist 不放行时浏览器侧一律 404，
    //    界面永远停在错误态而后端日志一次请求都没有 —— 放行与不通的区别只在浏览器侧。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   aftersales/exchanges                      列表（GET）+ 批准换货（POST）
    //   aftersales/exchanges/capabilities         能力声明 + 两条硬规则原文
    //   aftersales/exchanges/filters              可授权的换货售后单下拉
    //   aftersales/exchanges/{uuid}               详情（头 + 逐行 + 可执行动作）
    //   aftersales/exchanges/{uuid}/cancel        撤销换货（POST，释放真实预占）
    //
    // ⚠️ `capabilities` / `filters` 必须写在 id 形状**之前**：否则 `{uuid}` 那一支
    //   会把它们当 id 放行 —— 而 id 段是完整 UUID 形状，本来匹配不上，
    //   真正会出事的是顺序颠倒后字面量段被当成 id 段的一部分。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `aftersales/exchanges/{uuid}` 的 PUT / DELETE —— 换货授权行在 V162 里是
    //     append-only 的（运行角色的 UPDATE/DELETE 已被 REVOKE），改写等于毁掉
    //     「这笔换货当初预占了多少、约了谁、什么时候撤的」这条追溯链；
    //   * `aftersales/exchanges/{uuid}/outbound` —— 9.3.3.1 新出库接手未交付，
    //     capabilities 里 outboundHandoffDelivered=false 如实报出；
    //   * `aftersales/exchanges/{uuid}/return` —— 11.2 退回旧货未交付，
    //     capabilities 里 oldGoodsReturnDelivered=false 如实报出。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^aftersales\/exchanges(?:\/(?:capabilities|filters)|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/cancel)?)?$/,
  },
  {
    // 11.3.2.2 换货新单（trade 服务）。本条目同样是**新增一条 services[] 元素**，
    // 不往既有 trade 分支的超长单行 alternation 里并分支 —— 那一支多车道同时并进去
    // git 必然判冲突（结构性，retry 不会自愈），而**求并**又会在取错一侧时静默删掉
    // 同批兄弟车道已交付的路由（表现是页面 404，而 typecheck / lint / vitest 全绿）。
    // 插入锚点固定在 11.3.2.1 `exchanges` 那条**之前**：两条的前缀形状互不相交
    // （`aftersales/exchange-orders/…` vs `aftersales/exchanges/…`），
    // 谁先被匹配都互不吃 —— 注意 `exchange-orders` 与 `exchanges` 是**前缀相近但不相等**
    // 的两个字面量，正则里 `exchanges` 那一支并不会吃掉 `exchange-orders`。
    //
    // ⚠️ **这条是「页面能真的通」的必要条件**：allowlist 不放行时浏览器侧一律 404，
    //    界面永远停在错误态而后端日志一次请求都没有 —— 放行与不通的区别只在浏览器侧。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   aftersales/exchange-orders                      列表（GET）+ 建换货新单（POST）
    //   aftersales/exchange-orders/capabilities         能力声明 + 硬规则原文 + 闭环口径
    //   aftersales/exchange-orders/filters              可建新单的换货授权下拉
    //   aftersales/exchange-orders/{uuid}               详情（头 + 逐行 + 时间线 + 没闭环原因）
    //   aftersales/exchange-orders/{uuid}/submit        提交进审单队列（POST）
    //   aftersales/exchange-orders/{uuid}/sync          同步闭环状态（POST，只读真实事实）
    //   aftersales/exchange-orders/{uuid}/cancel        撤销换货新单（POST）
    //
    // ⚠️ `capabilities` / `filters` 必须写在 id 形状**之前**：否则 `{uuid}` 那一支
    //   会把它们当 id 放行 —— 而 id 段是完整 UUID 形状，本来匹配不上，
    //   真正会出事的是顺序颠倒后字面量段被当成 id 段的一部分。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `aftersales/exchange-orders/{uuid}` 的 PUT / DELETE —— 换货关联与逐行在 V167 里
    //     是 append-only 的（运行角色的 UPDATE/DELETE 已被 REVOKE），改写等于毁掉
    //     「这笔换货当初换了什么、发出去了没有、谁撤的」这条追溯链；
    //   * `.../{uuid}/dispatch` —— **本模块刻意没有「手工标记已发货」的入口**：
    //     发货事实归 8 出库（读 9.4.1.1 的 shipment_record），放行它就等于给假交付开门；
    //   * `.../{uuid}/close` —— 同理，闭环由 sync 按真实事实推进，不接受手工指定状态。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^aftersales\/exchange-orders(?:\/(?:capabilities|filters)|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:submit|sync|cancel))?)?$/,
  },
  {
    // 11.3.1.2 退款状态（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 11.1.1.2 售后单详情那条**之前**：本条与 11.1.1.2 的前缀形状
    // （`aftersales/…` vs `refunds/…`）互不相交，谁先被匹配都互不吃。
    //
    // ⚠️ **这条是「页面能真的通」的必要条件**：后端 11 个端点已经交付、页面也已经存在，
    //    但 allowlist 不放行时浏览器这一侧一律 404 —— 界面会永远停在错误态，
    //   而后端日志里一次请求都没有（放行与不通的区别只在浏览器这一侧，极难定位）。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   refunds                                    列表（GET）+ 登记待发起（POST，幂等键）
    //   refunds/capabilities                       能力声明 + 状态机逐条转换规则
    //   refunds/filters                            筛选项词表（来自真实数据 DISTINCT）
    //   refunds/discrepancies                      差异清单（GET，默认只列未裁定）
    //   refunds/{uuid}                             详情聚合（GET）
    //   refunds/{uuid}/dispatch                    派发 / 查证后重试（POST）
    //   refunds/{uuid}/verifications               登记「先查证交易」（POST）
    //   refunds/{uuid}/reports                     登记外部执行结果回报（POST）
    //   refunds/{uuid}/platform-sync               平台侧同步结果回报（POST）
    //   refunds/{uuid}/discrepancies               登记一条对账差异（POST）
    //   refunds/{uuid}/discrepancies/{uuid}/resolve 人工裁定差异（POST）
    //
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `refunds/{uuid}` 的 PUT / DELETE —— 退款执行与其派发/查证/回报/差异都是留痕，
    //     改写或删除等于毁掉「这笔钱到底退没退、查过没有」的证据链
    //   * `refunds/{uuid}/refund-request` —— 11.3.1.1 退款申请（可退余额校验 + 审批）**未交付**，
    //     capabilities 里 refundRequestDelivered=false 如实报出
    //   * `refunds/{uuid}/returns` —— 11.2 退回旧货未交付
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^refunds(?:\/(?:capabilities|filters|discrepancies)|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:dispatch|verifications|reports|platform-sync|discrepancies(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/resolve)?)?)?)?$/,
  },
  {
    // 11.3.1.1 退款申请（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 11.2.1.1 退货授权单那条**之后**（本条紧贴同为 11.3.1 锚点的
    // 11.3.1.2 那条下方），与其他车道的新增元素被既有条目隔开，git 能干净求并。
    //
    // 命名空间**独立于 `refunds/`**：那是 11.3.1.2 退款状态的命名空间（执行 / 查证 /
    // 派发 / 对账差异），本模块是**申请与审批**的那一半。两者的写入所有者不同 ——
    // 退款申请单归本模块，退款执行记录归 11.3.1.2 —— 混在一个命名空间里迟早有人
    // 代写对方那张表，而「这笔钱退没退」会出现两个来源各说各话。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，本条一律以
    // `refund-applications/` 开头，与 `refunds/` 的形状互不相交（`refunds` 后面必须
    // 是 `/` 或结尾，`refund-applications` 的第 8 个字符是 `-`），谁先被匹配都不误吃。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities                    能力与上下游可用性（**明说 12 财务未交付**、
    //                                  可退余额五桶口径、fail-closed 审批规则）
    //   refundable-balance?caseId=      可退余额校验（GET，五个占用桶服务端实时查）
    //   approval-policy?currency=       审批规则读取 / 维护（GET + PUT）
    //   filters                         筛选项词表（来自真实数据 DISTINCT）
    //   （裸）                          列表（GET，真分页 + 多维筛选 + 排序白名单）
    //   （裸）                          发起退款申请（POST，**建草稿不是提交**）
    //   {uuid}                          详情聚合（GET，头 + 余额 + 规则 + 执行状态 + 留痕 + 动作）
    //   {uuid}                          改草稿要素（PUT，**只有 DRAFT 可改**）
    //   {uuid}/submit                   提交审批（POST，**这一刻重新校验可退余额**）
    //   {uuid}/decide                   审批：同意 / 驳回（POST，**落单签还是双签由规则决定**）
    //   {uuid}/dispatch                 交财务执行（POST，必须挂一条已存在的执行记录）
    //   {uuid}/execution-outcome        登记执行结果（POST，成功 / 失败）
    //   {uuid}/cancel                   人工撤销（POST，钱还没出去时）
    //
    // ⚠️ 判别顺序：三个**字面量子路径**（capabilities / refundable-balance /
    //   approval-policy / filters）**排在** uuid 段之前 —— 否则 `filters`
    //   会被当成 uuid 段匹配掉（uuid 段只认完整 UUID 形状，`filters` 不是，
    //   但仍然把字面量写在前面才不必依赖这一层推理）。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //   后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `{uuid}` 的 DELETE —— 退款审批是资金凭据，删掉一行等于抹掉「这笔钱为什么退、
    //     谁批的」这段证据。
    //   * `{uuid}/verify` —— 「先查证交易再重试」属 **11.3.1.2**。放行它会让界面显示成
    //     本模块能查证，而真相是执行记录定论与否由 11.3.1.2 说了算，
    //     查证不了却显示按钮 = 点了必然失败。
    //   * `{uuid}/pay` —— 真实付款执行属 **12.2.2.2，未交付**。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^refund-applications(?:\/(?:capabilities|refundable-balance|approval-policy|filters)|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:submit|decide|dispatch|execution-outcome|cancel))?)?$/,
  },
  {
    // 11.2.1.1 退货授权单（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 11.3.1.2（refunds）那条**之后**、11.1.1.2 那条**之前**：
    // 与其他车道的插入点被既有条目隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，本条与相邻两条的前缀形状互不相交
    // （本条一律以 `rma-authorizations/` 开头），谁先谁后都不误吃。
    //
    // 命名空间**独立于 `/trade/aftersales`**：退货授权单是另一张单据（行级授权数量 +
    // 退货地址 + 回寄物流 + 寄回时限），不是售后单的另一个视图。塞进
    // `aftersales/cases/{uuid}/returns` 会让人以为它是 11.1.1.2 详情的一个子资源，
    // 而本模块的下游是 11.2.1.2 退货指令，不是售后详情页。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities                 能力与上下游可用性（**明说 11.2.1.2 / 11.2.2.x /
    //                               11.2.3.x 全部未交付，「授权 ≠ 已收到」**）
    //   returnable-quantities        原订单行可退量校验（GET，三个来源服务端实时查）
    //   （裸）                       列表（GET，真分页 + 多维筛选 + 排序白名单）
    //   （裸）                       登记退货授权单（POST，建草稿，**不是审批**）
    //   {uuid}                       详情聚合（GET，头 + 行 + 审批留痕 + 可执行动作）
    //   {uuid}                       改草稿要素（PUT，**只有 DRAFT 可改**）
    //   {uuid}/decide                审批：授权 / 部分授权 / 拒绝（POST）
    //   {uuid}/return-logistics      登记买家回寄物流（POST，三件套同进同出）
    //   {uuid}/cancel                人工撤销（POST，可退量立刻还给订单行）
    //   expire-overdue               超时未寄自动关闭（POST）
    //
    // ⚠️ 判别顺序：两个**字面量子路径**（capabilities / returnable-quantities /
    //   expire-overdue）**排在** uuid 段之前 —— 否则 `returnable-quantities`
    //   会被当成 uuid 段匹配掉。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `{uuid}` 的 DELETE —— 退货授权是审批单据，删掉一行等于抹掉纠纷证据
    //   * `{uuid}/receive` —— 登记退货实收属 **11.2.2.1，未交付**。
    //     蓝图加粗句「授权不等于已收到」：货可能还在路上，也可能根本没寄回；
    //     放行一个「标记已收到」会让界面显示成货已到手并开始做入库分录，
    //     而退回商品**默认进待检**，直接当已收货处理会把残次品当新品卖出去。
    //   * `{uuid}/instruction` —— 退货指引与标签属 **11.2.1.2，未交付**
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^rma-authorizations\/(?:capabilities|returnable-quantities|expire-overdue|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:decide|return-logistics|cancel))?)$|^rma-authorizations$/,
  },
  {
    // 11.2.1.2 退货指令下发（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 11.2.1.1（rma-authorizations）那条**之后**、11.1.1.2 那条**之前**：
    // 与相邻两条的插入点被既有条目隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，本条与相邻两条的前缀形状互不相交
    // （本条一律以 `rma-instructions/` 开头），谁先谁后都不误吃。
    //
    // 命名空间**独立于 `/trade/rma-authorizations`**：本模块是 11.2.1.1 的**下游**
    // （指引 / 标签 / 轨迹），不是退货授权单的另一个视图。塞进
    // `rma-authorizations/{uuid}/instructions` 会让两个模块共用一个前缀，
    // 而它们各自的写入所有权与状态机是分开的。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities                能力与上下游可用性（**明说 10 物流 / 15 客服 /
    //                                11.2.2.1 / 11.2.3.x 全部未交付，「已下发 ≠ 已寄回」**）
    //   （裸）                      列表（GET，真分页 + 多维筛选 + 按承运商/运单号反查 + 排序白名单）
    //   （裸）                      登记退货指引草稿（POST，挂在已授权的授权单下）
    //   {uuid}                      详情聚合（GET，头 + 行 + 留痕 + 轨迹 + 当前标签 + 动作）
    //   {uuid}                      改草稿要素（PUT，**只有 DRAFT 可改**：下发即冻结）
    //   {uuid}/issue                下发指引（POST，内容在这一刻冻结）
    //   {uuid}/acknowledge          登记买家已确认收到（POST，**主动回执**，不是我们推测）
    //   {uuid}/cancel               人工撤销（POST）
    //   expire-overdue              寄回时限到期自动失效（POST，**只判时限，不判货有没有回来**）
    //   {uuid}/label                生成退货标签（POST，真字节 + SHA-256 校验和）
    //   {uuid}/label                取标签文件内容（GET，服务端重算校验和比对）
    //   {uuid}/label/deliver        交付标签（POST，**生成 ≠ 交付**）
    //   {uuid}/label/void           作废标签（POST，理由必填，作废后可重出）
    //   {uuid}/tracking             回寄轨迹时间线（GET，追加式事件 + 按运单号分组的当前状态）
    //   {uuid}/tracking             登记一条轨迹（POST，追加式；承运商重复推送去重）
    //
    // ⚠️ 判别顺序：字面量子路径（capabilities / expire-overdue）**排在** uuid 段之前 ——
    //   否则 `capabilities` 会被当成 uuid 段匹配掉。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    // ⚠️ `label` 后面**只接** `deliver` 与 `void` 两个字面量段：多一层仍然 404 ——
    //   放行一个后端不存在的形状只会让界面以为已交付。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `{uuid}` 的 DELETE —— 指引是发给买家的一份文件，删掉一行等于抹掉纠纷证据
    //   * `{uuid}/receive` —— 登记退货实收属 **11.2.2.1，未交付**。
    //     蓝图加粗句「本系统只负责指引与跟踪，**不控制实物**」：放行「标记已收到」
    //     会让界面显示成货已到手并开始做入库分录，而退回商品**默认进待检**，
    //     直接当已收货处理会把残次品当新品卖出去。
    //   * `{uuid}/waybill` —— 退货运单属 **10 物流，未交付**。本模块出的模板标签
    //     **不含承运商运单号**，带了就等于声称已对接面单接口。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^rma-instructions\/(?:capabilities|expire-overdue|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:issue|acknowledge|cancel|tracking|label(?:\/(?:deliver|void))?))?)$|^rma-instructions$/,
  },
  {
    // 11.2.2.1 退货入库（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的超长单行 alternation 里并分支 —— 那一支多车道同时并进去
    // git 必然判冲突（结构性，retry 永远不会自愈），而**求并**又会在取错一侧时静默删掉
    // 同批兄弟车道已交付的路由（表现是页面 404，而 typecheck / lint / vitest 全绿）。
    // 插入锚点固定在 11.2.1.2 `rma-instructions` 那条**之后**、11.1.1.2 那条**之前**：
    // 三条的前缀形状互不相交（`return-receipts/` vs `rma-instructions/` vs `aftersales/cases/`），
    // 谁先被匹配都不误吃，且插入点被既有条目隔开，git 能干净求并。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   `return-receipts`                            集合读（GET，真分页 + 多维筛选 + 排序白名单）
    //                                               / 登记实收草稿（POST）
    //   `return-receipts/capabilities`               能力与上游可用性声明（GET）
    //   `return-receipts/receivable`                 剩余可收量（GET，扫码前先看还能收几件）
    //   `return-receipts/{uuid}`                     详情（GET） / 改草稿（PUT，仅 DRAFT）
    //   `return-receipts/{uuid}/post`                入库（POST）—— **生成待检分录，不是可售**
    //   `return-receipts/{uuid}/cancel`              撤销（POST，仅 DRAFT，已入库不可撤销）
    //   `return-receipts/{uuid}/photos`              追加拍照留证（POST，仅 DRAFT）
    //
    //   * **刻意不放行** `{uuid}/resell` / `{uuid}/inspect`：处置与质检属
    //     11.2.3.2 / 11.2.3.1，**未交付**。放行它们会让界面显示成「这批货可以直接转可售」，
    //     而蓝图加粗句「退回商品**默认进待检状态**，直接进可售会把残次品当新品卖出去」正是
    //     这条规则要防的事。
    //   * **刻意不放行** `{uuid}/receive`：11.2.1.2 的指引下发 ≠ 货已到手，
    //     登记实收只能走本模块的草稿 → 入库两段式。
    //   * **刻意没有** `{uuid}` 上的 DELETE：行与照片是纠纷证据（运行角色 UPDATE/DELETE
    //     已在 DB 层收回），放行删除形状等于让「这批货收了几件、判定成什么样」不可追责。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^return-receipts(?:\/(?:capabilities|receivable|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:post|cancel|photos))?))?$/,
  },
  {
    // 11.2.2.2 实收分配（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 11.2.3.1 质检与判定（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的超长单行 alternation 里并分支 —— 那一支多车道同时并进去
    // git 必然判冲突（结构性，retry 永远不会自愈），而**求并**又会在取错一侧时静默删掉
    // 同批兄弟车道已交付的路由（表现是页面 404，而 typecheck / lint / vitest 全绿）。
    // 插入锚点固定在 11.2.2.1 `return-receipts` 那条**之后**、11.1.1.2 那条**之前**：
    // 三条的前缀形状互不相交（`receipt-allocations/` vs `return-receipts/` vs `cases/`），
    // 谁先被匹配都不误吃，且插入点被既有条目隔开，git 能干净求并。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   `receipt-allocations`                        集合读（GET，真分页 + 多维筛选 + 排序白名单）
    //                                               / 按收货事件分配（POST，**重复提交幂等**）
    //   `receipt-allocations/capabilities`           能力与硬规则声明（GET）
    //   `receipt-allocations/order-returns`          订单行已退数量（GET，9 订单的取数面）
    //   `receipt-allocations/{uuid}`                 详情（GET）
    //   `receipt-allocations/{uuid}/reconcile`       对账：超收/少收/超期结转（POST）
    //   `receipt-allocations/{uuid}/reverse`         撤销分配（POST，仅 ALLOCATED，必须写理由）
    //
    //   * **刻意不放行** 任何「重复分配 / 强制再记一次已退数量」的形状：蓝图加粗句
    //     「**按收货事件去重分配，同一批退货不重复记已收数量**」。已退数量是可退余额的
    //     事实来源，多记一次就是资损；传输层**没有** force / allowDuplicate 开关。
    //   * **刻意不放行** `{uuid}` 上的 DELETE：分配是资金动作，撤销只能走
    //     `/reverse`（写理由、走版本校验、由 DB 触发器回冲已退数量）。
    //   * **刻意不放行** 任何「把差额标记为已解决」的形状：处置结论只有
    //     「待人工核实 / 按实际退回认可」—— 超收多退的钱要追回还是认了是财务决定，
    //     编一个「已解决」会让财务侧以为这笔钱已经结清了。
    // 三条的前缀形状互不相交（`return-quality/` vs `return-receipts/` vs `aftersales/cases/`），
    // 谁先被匹配都不误吃，且插入点被既有条目隔开，git 能干净求并。
    //
    // 命名空间**独立于 `/trade/return-receipts`**：本模块的产物是**另一种单据**
    // （质检判定 = 一个结论 + 一张交给下游的指令），不是实收单的另一个视图。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   `return-quality/capabilities`               能力与上下游可用性声明（GET）
    //   `return-quality/categories`                类目集合读（GET） / 建类目（POST）
    //   `return-quality/categories/{uuid}`         改类目名称与启停（PUT，**刻意不给改编码的入口**）
    //   `return-quality/categories/{uuid}/skus`    把 SKU 归到质检类目（POST）
    //   `return-quality/templates`                 模板集合读（GET） / 建模板草稿（POST）
    //   `return-quality/templates/{uuid}`          模板详情（GET） / 改草稿（PUT，**仅 DRAFT**）
    //   `return-quality/templates/{uuid}/activate` 启用模板（POST）
    //   `return-quality/templates/{uuid}/retire`   停用模板（POST，**刻意用 POST 不是 DELETE**：
    //                                               停用是口径变更，历史质检单要能复现当时的口径）
    //   `return-quality/queue`                     待检队列（GET，**质检员上班第一屏**）
    //   `return-quality/verdict-preview`          判定预览（GET，提交前就知道能判成什么）
    //   `return-quality/inspections`               质检单集合读（GET） / 开单（POST）
    //   `return-quality/inspections/{uuid}`        详情（GET） / 录逐项结果（PUT，**仅 DRAFT**）
    //   `return-quality/inspections/{uuid}/conclude` 判定（POST，结论 + 理由 → **残值由公式算出**）
    //   `return-quality/inspections/{uuid}/cancel`   撤销（POST，仅 DRAFT，已判定是终态）
    //
    // ⚠️ 四段 alternation 各自**整段锚定到 $**，所以不存在「字面量子路径被 uuid 段吃掉」
    //   的顺序陷阱（capabilities / queue 这些字面量段与 UUID 形状互不相交）。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `{uuid}/resell` / `{uuid}/dispose` / `{uuid}/scrap` —— 处置属 **11.2.3.2，未交付**。
    //     蓝图加粗句「**报废是不可逆的**，需审批与原因，不能由客服随手处理」正是这条规则的落点：
    //     放行它等于把不可逆动作做成一个按钮。
    //   * `{uuid}/salvage-posting` —— 残值入账属 **12 财务，未交付**，且是**独立数据库**，
    //     跨服务写账被 AGENTS.md 模块边界纪律明令禁止。
    //   * `{uuid}/claim` —— 供应商索赔属 **11.2.3.3，未交付**。本模块只提供举证材料。
    //   * `{uuid}/verdict` —— 结论**终态不可逆**：判定只走 `conclude`，判错了只能作废重判。
    //     再开一个「直接改结论」的形状，会让「这批货到底判成什么」出现两个答案。
    //   * 任何 **DELETE** —— 质检结论与逐项结果是要拿去索赔的举证材料
    //     （运行角色对该表的 UPDATE/DELETE 已在 DB 层收回）。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^receipt-allocations(?:\/(?:capabilities|order-returns|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:reconcile|reverse))?))?$/,
  },
  {
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^return-quality\/(?:capabilities|categories|templates|queue|verdict-preview|inspections)$|^return-quality\/categories\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/skus)?$|^return-quality\/templates\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:activate|retire))?$|^return-quality\/inspections\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:conclude|cancel))?$/,
  },
  {
    // 11.1.2.2 商品问题归因（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的超长单行 alternation 里并分支 —— 那一支多车道同时并进去
    // git 必然判冲突（结构性，retry 永远不会自愈），而**求并**又会在取错一侧时静默删掉
    // 同批兄弟车道已交付的路由（表现是页面 404，而 typecheck / lint / vitest 全绿）。
    // 插入锚点固定在 11.2.3.1 `return-quality` 那条**之后**、11.1.1.2 那条**之前**：
    // 三条的前缀形状互不相交（`product-attribution/` vs `return-quality/` vs `cases/`），
    // 谁先被匹配都不误吃，且插入点被既有条目隔开，git 能干净求并。
    //
    // 命名空间**独立于 `/trade/return-quality`**：本模块判的是「这事该谁负责」，
    // 11.2.3.1 判的是「这批货怎么样」—— 两者的输出句不同，形状就不能混。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   `product-attribution/capabilities`                   能力声明与边界（GET）
    //   `product-attribution/attributions`                   集合读（GET）+ 建单（POST）
    //   `product-attribution/attributions/{uuid}`            详情（GET）
    //   `product-attribution/attributions/{uuid}/photos`      登记 / 替换照片（PUT，整单替换）
    //   `product-attribution/attributions/{uuid}/photos/compare` 照片比对（POST，**内容指纹**口径）
    //   `product-attribution/attributions/{uuid}/attribute`   出归因结论（POST）
    //   `product-attribution/attributions/{uuid}/supersede`  改判留痕（POST，终态不可逆）
    //   `product-attribution/attributions/{uuid}/lot-flag/refresh` 刷新批次质量标记（POST）
    //   `product-attribution/lot-flags`                      批次质量标记列表（GET）
    //   `product-attribution/lot-flags/by-lot`               单批次标记（GET）
    //
    // ⚠️ 四段 alternation 各自**整段锚定到 $**，所以不存在「字面量子路径被 uuid 段吃掉」
    //   的顺序陷阱。id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），
    //   不是 `[0-9a-f-]+`：后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `{uuid}/claim` —— 供应商索赔属 **6.4.2.2，未交付**。归因结论与批次标记是索赔的
    //     **举证材料**，但索赔单的创建、协商记录与赔付处理都不在本模块。
    //   * `{uuid}/rating` —— 供应商评级属 **6.1.1.3，未交付**。
    //   * `{uuid}/similarity` —— **图像相似度**：本仓没有图像库、没有训练好的模型、
    //     也没有第三方视觉服务的接入点。放行它等于渲染一个冒充图像识别能力的控件。
    //   * `{uuid}/verdict` / `PUT {uuid}` —— 归因结论**终态不可逆**：改判只走 `/supersede`
    //     留痕另起一版。再开一个「直接改结论」的形状，会让「这批货到底归给谁」出现两个答案。
    //   * 任何 **DELETE** —— 归因结论、照片与比对是要拿去索赔的举证材料
    //     （留痕表的 UPDATE/DELETE 已在 DB 层收回）。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^product-attribution\/(?:capabilities|attributions|lot-flags|lot-flags\/by-lot)$|^product-attribution\/attributions\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:photos|photos\/compare|attribute|supersede|lot-flag\/refresh))?$/,
  },
  {
    // 11.2.3.3 供应商索赔（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 永远不会自愈），而**求并**又会在取错一侧时
    // 静默删掉同批兄弟车道已交付的路由（表现是页面 404，而 typecheck / lint / vitest 全绿）。
    // 插入锚点固定在 11.1.2.2 `product-attribution` 那条**之后**、11.5.2.1 `quality-analysis` 那条**之前**：
    // 三条的前缀形状互不相交（`supplier-claims/` vs `product-attribution/` vs `quality-analysis/`），
    // 谁先被匹配都不误吃，且插入点被既有条目隔开，git 能干净求并。
    //
    // 命名空间**独立于 `/trade/return-quality` 与 `/trade/product-attribution`**：
    // 本模块的产物是**另一种单据**（一次对外的商务主张 + 一串协商 + 一笔待确认的赔付），
    // 不是那两个模块的另一个视图。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   `supplier-claims`                                    集合读（GET，真分页 + 多维筛选 + 排序白名单）
    //                                                        / 发起索赔（POST，建单恒为草稿）
    //   `supplier-claims/capabilities`                       能力与边界声明（GET）
    //   `supplier-claims/candidates`                         可索赔候选（GET，**发起索赔的唯一入口素材**）
    //   `supplier-claims/{uuid}`                             详情（GET）
    //   `supplier-claims/{uuid}/items`                      加索赔明细（POST）
    //   `supplier-claims/{uuid}/items/{itemUuid}`           移除索赔明细（DELETE，**仅草稿**）
    //   `supplier-claims/{uuid}/submit`                     提交索赔（POST）
    //   `supplier-claims/{uuid}/start-negotiation`          进入协商（POST）
    //   `supplier-claims/{uuid}/negotiations`               记一条协商（POST）
    //   `supplier-claims/{uuid}/agree`                      置为已达成（POST）
    //   `supplier-claims/{uuid}/reject`                     驳回索赔（POST）
    //   `supplier-claims/{uuid}/withdraw`                   撤回索赔（POST）
    //   `supplier-claims/{uuid}/settlements`                登记赔付（POST，**幂等**）
    //   `supplier-claims/{uuid}/settlements/{uuid}/confirm`  财务确认冲减（POST）
    //   `supplier-claims/{uuid}/settlements/{uuid}/reject`   财务驳回赔付（POST）
    //
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `{uuid}/settle` —— 蓝图加粗句「**索赔不是自动到账**」：已结算只能由
    //     「财务确认赔付分录」这一个动作驱动。放行一个「直接置为已结算」等于让界面显示成
    //     「登记完赔付就等于钱到账了」，而真相恰恰相反。
    //   * `{uuid}/confirm` —— 财务确认必须挂在**赔付分录**上（`/settlements/{uuid}/confirm`），
    //     否则「确认的是哪一笔赔付」在库里没有答案。
    //   * 任何对**索赔单本身**的 DELETE —— 索赔单与协商记录是要拿去和供应商对质的凭据。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^supplier-claims(?:\/(?:capabilities|candidates|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:items(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|submit|start-negotiation|negotiations|agree|reject|withdraw|settlements(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/(?:confirm|reject))?))?))?$/,
  },
  {
    // 11.5.2.1 质量分析（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 11.1.2.2 `product-attribution` 那条**之后**：
    // 两条的前缀形状互不相交（`quality-analysis/` vs `product-attribution/`），
    // 与其他车道的新增元素被既有条目隔开，git 能干净求并。
    //
    //   `quality-analysis/capabilities`                 能力声明与边界（GET）
    //   `quality-analysis/overview`                     质量总览（GET）
    //   `quality-analysis/defect-distribution`          缺陷类型分布（GET）
    //   `quality-analysis/lot-trend`                    批次质量趋势（GET）
    //   `quality-analysis/supplier-comparison`          供应商质量对比（GET）
    //   `quality-analysis/photo-clusters`               缺陷照片聚类（GET，**内容指纹**口径）
    //   `quality-analysis/open-box-rate`                开箱率（GET，**必须与覆盖率一起读**）
    //   `quality-analysis/quality-cost`                 质量成本估算（GET，逐项标依据等级）
    //   `quality-analysis/improvements`                 责任分布 + 改进清单（GET）
    //   `quality-analysis/defect-observations`          集合读（GET）+ 登记（POST）
    //   `quality-analysis/defect-observations/{uuid}/void`        作废（POST，**终态**）
    //   `quality-analysis/cost-parameters`              参数集合读（GET）+ 建参数（POST）
    //   `quality-analysis/cost-parameters/{uuid}/supersede`      换版（POST，**终态**）
    //   `quality-analysis/supplier-refs`                引用集合读（GET）+ 登记（POST）
    //   `quality-analysis/supplier-refs/{uuid}/status`  引用启停（POST）
    //
    // ⚠️ 五段 alternation 各自**整段锚定到 $**。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `{uuid}/similarity` / `photo-clusters?similarity=` —— **图像相似度**：本仓没有图像库、
    //     没有训练好的模型、也没有第三方视觉服务的接入点。放行它等于渲染一个冒充图像
    //     识别能力的控件；聚类口径是 content_sha256 **完全相同**。
    //   * 任何 `negative-review` 形状 —— 全库**没有商品评价表**，本模块不产出
    //     「买家给了差评」这个事实，只在成本里按租户参数**估算**其影响并标成估算项。
    //   * `PUT {uuid}` —— 缺陷观察与成本参数**都是终态**：作废 / 换版只走各自的
    //     一个入口留痕。再开一个「直接改」的形状，会让同一个数字出现两个答案。
    //   * 任何 **DELETE** —— 缺陷观察的发现环节是开箱率的**唯一来源**，
    //     成本参数换版必须留痕（否则三个月后答不出「上次是多少」）。
    //     两者都只在 POST 里留痕，删除不在任何路径上。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^quality-analysis\/(?:capabilities|overview|defect-distribution|lot-trend|supplier-comparison|photo-clusters|open-box-rate|quality-cost|improvements|defect-observations|cost-parameters|supplier-refs)$|^quality-analysis\/defect-observations\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/void$|^quality-analysis\/cost-parameters\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/supersede$|^quality-analysis\/supplier-refs\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/status$/,
  },
  {
    // 11.1.1.2 售后单详情（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在既有 `prefix: 'trade/'` **之前**（此处即紧邻 11.1.1.1 那条之前），
    // 与其他车道的新增元素被既有条目隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，本条先于 11.1.1.1 那条被匹配，
    // 但两者前缀形状互不相交：11.1.1.1 管的是 `cases` / `cases/bulk`（裸路径），
    // 本条管的是 `cases/{uuid}` 与它下面的子路径，各自互不吃。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   case-detail/capabilities        详情页能力声明（**九个区块逐块报可用性 + 归属模块**）
    //   cases/{uuid}                    详情聚合（GET，一次返回九个区块 + 操作入口 + 时间线）
    //   cases/{uuid}/timeline           时间线单独分页读（GET，真分页）
    //   cases/{uuid}/notes              追加单条备注（POST，note_source=TENANT_SINGLE）
    //   cases/{uuid}/messages           登记沟通记录（POST）
    //   cases/{uuid}/attachments        登记附件与凭证（POST）
    //   cases/{uuid}/lines              登记商品行（POST）
    //
    // 判别顺序：字面量子路径（case-detail/capabilities）**排在** uuid 段之前。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`：
    //    后者把 `c`、`e`、`f` 这类**单字符**也当成合法 id 放行。
    // ⚠️ uuid 尾巴只挂在 `cases` 后面，`cases/bulk` 仍归 11.1.1.1 那条 ——
    //    本模块刻意不提供批量端点，批量授权 / 备注 / 转工单是 11.1.1.1 的三件。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `cases/{uuid}` 的 PUT / DELETE —— 留痕与单据在本服务内**不可改写**
    //     （四张留痕表在数据库层连 UPDATE/DELETE 权限都没有），改掉或删掉一行等于毁掉纠纷证据
    //   * `cases/{uuid}/refunds` / `/returns` / `/disputes` —— 11.2 / 11.3 / 11.4 **均未交付**，
    //     capabilities 里逐条报 delivered=false 与归属模块，页面据此显示「该能力未交付」
    //   * `cases/{uuid}/attribution` —— 11.1.2.2 商品问题归因未交付
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^aftersales\/(?:case-detail\/capabilities|cases\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:timeline|lines|messages|attachments|notes))?)$/,
  },
  {
    // 11.1.1.1 售后单列表（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在既有 `prefix: 'trade/'` **之前**：与 9.3.1.2 合单、9.2.2.2 风控两条
    // 并排，各条之间被既有条目隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的第二条不会误吃前一条的路径。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities     能力与上下游可用性（**明说店铺维度不可得、15 客服与卷 2 未交付**）
    //   filters          筛选项下拉的租户级词表（币种 / 平台来源来自真实数据）
    //   cases            售后单列表（GET，真分页 + 多维筛选 + 时效预警）
    //   cases            POST 登记一张售后单（11.1.1.3 受理未交付期间的登记入口）
    //   cases/bulk       POST 批量：授权 / 备注 / 转工单（逐条结果回报）
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `cases/{uuid}`  —— 单条详情是 11.1.1.2 的职责，**未交付**。
    //     列表行已经带齐分页、筛选、时效、责任人、授权状态与工单号，
    //     而一个点进去 404 的入口比没有这个入口更糟。
    //   * `cases/{uuid}/notes` 之类的子资源 —— 同上，属 11.1.1.2。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern: /^aftersales\/(?:capabilities|filters|cases(?:\/bulk)?)$/,
  },
  {
    // 11.1.1.3 售后受理（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 11.1.1.1 那条**之后**、11.1.2.1 那条**之前**：
    // 与并行车道的插入点被既有条目隔开，git 能干净求并。
    // 当前实际顺序：11.1.1.2 → 11.1.1.1 → **本条** → 11.1.2.1。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities                        能力声明 + **当前生效**的期限天数与必退词表
    //   cases                    POST        受理一张售后请求（判四项 + 建单 + 派责任人）
    //   cases/{uuid}/rejudge     POST        重新判定（追加一版，旧判定留档）
    //   verdicts                             判定凭据列表（真分页 + 与分页无关的汇总）
    //   reply-sla                  GET/POST  回复时限策略：当前生效档 / 全部档 / 新开一档
    //   reply-sla/effective        GET       按平台档解析当前生效档（**可以返回 null = 没配**）
    //   reply-sla/{uuid}/retire    POST      收口一档（内容不可改，只能换版）
    //   owner-roster               GET/POST  责任人名册：列表 / 登记
    //   owner-roster/{uuid}        PUT       调整名册行（是否接新单 / 权重）
    //
    // 与 11.1.1.1 / 11.1.1.2 / 11.1.2.1 三条**形状互斥**：它们分别是
    // `aftersales/(capabilities|filters|cases…)`、`aftersales/cases/{uuid}/…`、
    // `aftersales/reasons/…`，本条全部带 `intake/` 这一段，谁先被匹配都互不吃。
    //
    // 刻意**不放行**的形状：
    //   * `cases/{uuid}/refunds|returns|disputes` —— 11.2 / 11.3 / 11.4 均未交付
    //   * `verdicts/{uuid}` 的 PUT / DELETE —— 判定凭据 append-only，改写等于毁掉纠纷证据
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^aftersales\/intake\/(?:capabilities|verdicts|reply-sla(?:\/effective|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/retire)?|owner-roster(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|cases(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/rejudge)?)$/,
  },
  {
    // 11.1.2.1 售后原因分类（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 11.1.1.1 那条**之后**、既有 `prefix: 'trade/'` 之前：
    // 与并行车道的插入点被既有条目隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的相邻两条前缀互不相交，
    // 谁先谁后都不误吃：`aftersales/(capabilities|filters|cases)` 与
    // `aftersales/reasons/…` 在正则上是**互斥**的（后者都带 `reasons/` 这一段）。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities                 能力与上下游可用性（**明说 channel 跨服务读不可得、**
    //                                11.5.2 质量分析与 6 采购索赔未交付**）
    //   tree                         分类树（GET，真递归 CTE 算路径/深度/可归因性）
    //   categories                   POST 新建分类（建根必给 rootKind，建子节点必不给）
    //   categories/{uuid}            PATCH 改名称/状态/排序/说明/父分类（**只读只写白名单**）
    //   categories/{uuid}            DELETE 删叶子且从未被引用的分类
    //   attributions                 GET  按 caseId 读当前归因
    //   attributions                 POST 登记一次归因（**至少一个原因、恰好一个主因**）
    //   mappings                     GET  平台原因码映射列表（trade 自有表）
    //   mappings                     POST 新建映射
    //   mappings/{uuid}              PATCH 改内部分类/平台原因名/状态（匹配键不可改）
    //   mappings/{uuid}              DELETE 删映射（**同步器写的行只许停用不许删**）
    //   reports                      GET  报表（七类零也出现，主因与次因分开计数）
    // 判别顺序：字面量子路径（capabilities / tree / categories / attributions /
    // mappings / reports）排在 {uuid} 分支之前，否则会被 uuid 分支吃掉半截。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `reasons/categories/{uuid}/move` —— 提升为根要一个 rootKind，而 rootKind 是
    //     **不可改列**（改了会把全部历史归因重新归到另一个口径）。刻意不提供这个端点。
    //   * `reasons/export` —— 导出的可用性取决于能导出哪些维度；当前报表的口径由
    //     /reports 原样报出就够了，真要导出必须连排序键一起稳定，不给半成品出口。
    //   * `reasons/attributions/bulk` —— 批量归因：逐单判定 + 逐单回报的成本远高于
    //     「打开这张单、选主因、次因、给权重」本身，批量入口只会诱导乱归因。
    //   * 裸路径 `reasons` —— 后端没有对应映射。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^aftersales\/reasons\/(?:capabilities|tree|categories(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|attributions|mappings(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|reports)$/,
  },
  {
    // 9.2.1.3 异常拦截（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在既有 `prefix: 'trade/'` **之前**：与 9.2.2.2 / 9.3.3.1 / 9.3.1.2
    // 那些条目各自并排，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities                     能力与上下游可用性（**明说 9.2.2.1 / 16 合规 / 5 定价没接通**）
    //   overview                          全量概览（不受筛选与分页影响）
    //   rule 不对 —— 本模块是 policy：capabilities / overview / policy(GET+PUT)
    //   restrictions                     限制清单（GET 真分页 + 真搜索 / POST 登记一条）
    //   sku-costs                         成本基线（GET 真分页 + 真搜索 / POST 登记一条）
    //   checks                            拦截清单（GET，真分页 + 真搜索 + 汇总）
    //   checks                            POST 对一张订单触发一次检查
    //   {uuid}                            检查详情（头 + 八行命中明细 + 人工处置留痕）
    //   {uuid}/disposition                POST 人工处置 —— **取消的唯一合法入口**
    // 判别顺序：字面量子路径（capabilities / overview / policy / restrictions / sku-costs / checks）
    // 排在 {uuid} 分支之前，否则会被 uuid 分支吃掉半截。
    // ⚠️ 本白名单是**按 path 判别、不看 HTTP 方法**的，所以「刻意不放行 DELETE」在这里做不到；
    //   真正的拦截点在后端：V153 对留痕表做了 REVOKE UPDATE/DELETE，
    //   Spring 也没有任何 DELETE 映射，删除请求只会拿到 405/404。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `checks/{uuid}/auto-cancel` / `checks/{uuid}/confirm-cancel` —— **本模块刻意没有**：
    //     蓝图加粗句「拦截不自动取消，默认转人工」，改最终处置的唯一入口是 /disposition
    //   * `checks/{uuid}/batch-disposition` / `checks/batch-release` —— 批量处置
    //     正是加粗句点名要避免的那种动作
    //   * `checks/{uuid}/decision` —— 同「auto-cancel」，是上面那个陷阱的另一个常见叫法
    //   * `address-validations` —— 9.2.2.1 未交付，本模块**不假装**有地址校验接口；
    //     capabilities 里的 addressValidationAvailable = false 已经说明这件事
    //   * 裸路径 `order-intercept` —— 后端没有对应映射
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^order\-intercept\/(?:capabilities|overview|policy|restrictions|sku\-costs|checks(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/disposition)?)?)$/,
  },
  {
    // 9.2.1.1 待审队列（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 9.2.1.3 `order-intercept` 那一条**之后**：
    // 与并行车道（4.4.1.3 → listing-bulk-collects、9.2.2.1 → fulfillment/consolidation、
    // 4.5.2.2 → listing-keyword-rankings）的插入点两两相隔 ≥ 74 行，
    // 默认 3 行上下文窗口不会互相覆盖，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的后一条不会误吃前一条的路径。
    //
    // ⚠️ **首 token 为什么是 `order-pending-review`**：
    //   本仓既有白名单的正则**全部锚定**（`/^token(...)?$/`），所以只要**首 token 不同**就互不吃。
    //   逐条核对过本仓全部 46 条 pattern 的首 token：
    //     `order-review`（9.2.1.2 审单规则，形状是 `order-review/...`）—— 不同；
    //     `order-intercept`（9.2.1.3 异常拦截）—— 不同；
    //     `orders`（9.2.2.2 风控校验，形状是 `orders/risk-validation/...`）—— 不同；
    //     `platform-orders`（9.1.2.1 平台订单同步）—— 不同；
    //     `fulfillment`（9.3.x 履约）、`aftersales`（11.x 售后）、`sales-orders`（9.1.x 订单）……
    //     —— 首 token 全部不同。
    //   ⇒ `order-pending-review` 与既有任何一条都不撞车；
    //   而它**读起来也不会被误认**成 `order-review`（审单规则）——
    //   「排队等人审」与「配规则」是两件事，权限与日志都该分得开。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities                     能力与上下游可用性 + **可作为批量通过依据的规则清单**
    //   summary                          队列概览（待审 / 已挂起 / 已通过 / 已驳回 / 已超时 / 未评估 / 高风险）
    //   queue                            待审列表（GET，真分页 + 真筛选 + DB 层真排序）
    //   queue/{uuid}                     单条详情（订单事实 + 风险五维 + 完整命中链 + 批次历史）
    //   queue/{uuid}/evaluate            按当前生效规则试算（POST，标 DRY_RUN 不冒充结论）
    //   batches                          批量回执列表（GET，真分页 + 真搜索）
    //   batches                          POST 批量通过 / 驳回 / 挂起（**必须带幂等键**）
    //   batches/{uuid}                   批量回执详情（含逐条结果）
    // 判别顺序：字面量子路径（capabilities / summary / queue / batches）排在 {uuid} 分支之前，
    // 否则会被 uuid 分支吃掉半截。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `order-pending-review` —— 后端没有对应映射
    //   * `order-pending-review/bulk-approve` / `order-pending-review/decisions` ——
    //     批量动作只有一个入口：`POST /batches`（带幂等键）。放行一个「一键全过」形状
    //     就是在接口层给蓝图加粗句「批量通过必须基于明确的规则，而不是默认全过」开后门
    //   * `order-pending-review/queue/{uuid}/auto-approve` / `/bulk` / `/status` ——
    //     改审单结论的唯一入口是批量端点；单条「改状态」的裸端点不存在
    //   * `order-pending-review/queue/{uuid}/evaluate/extra` —— 多一层就 404
    //   * `order-pending-review/batches/{uuid}/items` —— 逐条结果在 `batches/{uuid}` 里，
    //     不另开一个形状
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^order\-pending\-review\/(?:capabilities|summary|queue(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/evaluate)?)?|batches(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?)$/,
  },
  {
    // 9.2.2.2 风控校验（trade 服务）。本条目是**新增一条 services[] 元素**，不是往既有
    // trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在既有 `prefix: 'trade/'` **之前**：与 9.3.1.2 合单那条并排，
    // 两条都被既有条目与其它车道的插入点隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的第二条不会误吃前一条的路径。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities                          能力与上下游可用性（**明说 15 客户 / 16 合规没接通**）
    //   overview                             全量概览（不受筛选与分页影响）
    //   rule / rule                           风控规则（GET 读 + PUT upsert，五维权重合计必须 1.0000）
    //   signals                              信号列表（GET，真分页 + 真搜索）
    //   signals                              POST 登记一条风险信号（15 客户 / 16 合规未接通期间的唯一数据入口）
    //   assessments                          评估列表（GET，真分页 + 真搜索 + 汇总）
    //   assessments                          POST 对一张订单触发一次评估
    //   {uuid}                                评估详情（头 + 五行组件 + 人工复核留痕）
    //   {uuid}/review                         POST 人工复核 —— **拒发的唯一合法入口**
    // 判别顺序：字面量子路径（capabilities / overview / rule / signals / assessments）
    // 排在 {uuid} 分支之前，否则会被 uuid 分支吃掉半截。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `assessments/{uuid}/reject` —— **本模块刻意没有**：蓝图加粗句「风控分数不能单独
    //     作为拒绝依据」，改最终动作的唯一入口是 /review（要人工身份）；
    //     若将来下游 9.2.1.3 落拦截表，它也不该走 trade 的这个命名空间
    //   * `assessments/{uuid}/decision` —— 同上，是上面那个陷阱的另一个常见叫法
    //   * `signals/{uuid}` —— 信号是时间序列（按 captured_at 追加），没有「改一条」的语义；
    //     观测值变了就再采一次，同一 (码, 主体, 采集时刻) 走 ON CONFLICT 覆盖
    //   * `signal-codes` / `rules` —— 本模块只有**一租户一条**规则，没有集合读；规则名说清楚更省事
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^orders\/risk\-validation\/(?:capabilities|overview|rule|signals|assessments(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/review)?)?)$/,
    // 裸路径 orders/risk-validation **刻意不放行**：后端没有对应映射。
  },
  {
    // 9.3.3.2 履约流程监控（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 9.3.3.1 `fulfillment/requests` 那条**之前**：与并行车道的插入点
    // 被既有条目隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的第二条不会误吃前一条的路径。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities                                    能力与上下游可用性（含 7.2.1.3 / 1 工作台
    //                                                    两个未交付下游的显式 false）
    //   cases                                            异常流程清单（真分页 + 真 SQL 筛选）
    //   cases/{uuid}                                     详情（案例 + 查证 + 处置 + 释放登记
    //                                                    + 待办 + 事件，带 retryAllowed）
    //   scan                                             扫描检出（判定用数据库时钟）
    //   cases/{uuid}/verify                              查证（加粗句的落点）
    //   cases/{uuid}/disposition                         发起处置（重试 / 切换路径 / 人工介入 /
    //                                                    释放预占）
    //   cases/{uuid}/disposition/{uuid}/status           推进处置状态（状态上的 CAS）
    //   cases/{uuid}/close                               结案（要求至少一条 COMPLETED 处置）
    //   cases/{uuid}/release/{uuid}/ack                  登记 7.2.1.3 释放回执
    //   cases/{uuid}/todos                               登记人工介入待办项
    //   thresholds        GET / PUT                      租户可维护的超时阈值（带解析来源）
    //   todos                                               人工介入待办清单（真分页）
    //   todos/{uuid}/action                               待办认领 / 完成 / 取消
    //   events                                             事件流
    //
    // 判别顺序：**字面量子路径（capabilities / cases / scan / thresholds / todos / events）
    // 必须排在 {uuid} 分支之前**，否则它们的 id 段会被 uuid 分支吃掉半截。
    // ⚠ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`。
    // ⚠ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 fulfillment/monitor —— 后端没有对应映射，放行只会拿到 404。
    //   * 任何**本模块不存在的 path**：放行只会拿到 404/405。
    //   * `fulfillment/requests/{uuid}/release` 与 `/exceptions` —— 那是 9.3.3.1
    //     那条正则刻意不放行的形状；本模块的释放登记走 `/cases/{uuid}/release/{uuid}/ack`，
    //     异常清单走 `/cases`，**不经** `/requests/{uuid}/exceptions`。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^fulfillment\/monitor\/(?:capabilities|scan|thresholds|todos(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/action)?|events|cases(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:verify|disposition|close|todos|disposition\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/status|release\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/ack))?)?)$/,
  },
  {
    // 9.3.3.1 履约请求（trade 服务）。本条目是**新增一条 services[] 元素**，
    // 不是往既有 trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在既有 `prefix: 'trade/'` **之前**：与另外三条并行车道的插入点
    // 被既有条目隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的第二条不会误吃前一条的路径。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities                        能力与上下游可用性（**下游三个全部未接通**，
    //                                        含 reservesUpstreamStock=false 这类必须显式报出的事实）
    //   requests/{uuid}                     详情（头 + 步骤 + 预占 + 出库单 + 作业 + 超时 + 事件）
    //   requests/{uuid}/advance             推进状态（expectedVersion 必填，冲突回 409）
    //   requests/{uuid}/block               挂起（超时 / 步骤失败 / 回执陈旧）
    //   requests/{uuid}/retry               重试 / 断点恢复（resumeReason 必填）
    //   requests/{uuid}/cancel              取消（终态，reason 必填）
    //   requests/{uuid}/complete            登记跟踪完成（TRACK 步）并收尾
    //   requests/{uuid}/reservation         发出锁定预占指令（登记 7.2，**不假装已锁**）
    //   requests/{uuid}/reservation/ack     登记 7.2 的预占回执
    //   requests/{uuid}/outbound-order      发出库单指令（登记 8.3.1）
    //   requests/{uuid}/outbound-order/ack  登记 8.3.1 的出库单回执
    //   requests/{uuid}/work-orders         批量编排作业（只有 POST，没有 GET 集合读）
    //   requests/{uuid}/work-orders/{uuid2}/status  登记单条作业状态
    //   requests/resumable                  可恢复清单（断点恢复入口）
    //   requests/scan-timeouts              扫描已超时的流程并挂起（只有 POST）
    //   requests/timeouts                   超时 / 失败检出记录列表
    //
    // 判别顺序：**字面量子路径（capabilities / resumable / scan-timeouts / timeouts）
    // 必须排在 {uuid} 分支之前**，否则它们的 id 段会被 uuid 分支吃掉半截，
    // 正则静默匹配失败，或更糟：拼出一个「id=timeouts 的请求」被凭空放行。
    // ⚠ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+`。
    // ⚠ 外层 `^` / `$` 包住完整分组，不写成 `^a|b|c$`（那实际是 `(^a)|b|(c$)`）。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * `fulfillment/requests/{uuid}/release` —— 释放预占是 9.3.3.2 的处置入口，未开发
    //   * `fulfillment/requests/{uuid}/exceptions` —— 异常清单同样判给 9.3.3.2
    //   * `fulfillment/requests/{uuid}/waybill` —— 运单主权在 9.4.1.1 / 8.3.4.2
    //   * 任何**本模块不存在的 path**：后端没有对应映射，放行只会拿到 404/405。
    // ⚠ 关于 DELETE：本白名单是**按 path 判别、不看 HTTP 方法**的，所以
    //   「刻意不放行 DELETE」这句在本文件里**做不到** —— DELETE 打到上面任一形状
    //   仍会被转发。真正的拦截点是后端：V152 第 11 节把 UPDATE/DELETE 从 trade_app
    //   全部收回，Spring 也没有任何 DELETE 映射（trade.openapi.json 里 delete 段为 0），
    //   所以删除请求只会拿到 405/404，且数据库层也删不掉留痕表。这里如实说明，
    //   不写一句「白名单挡住了 DELETE」这种看起来更漂亮但不成立的话。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^fulfillment\/requests(?:\/(?:capabilities|resumable|scan-timeouts|timeouts|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:advance|block|retry|cancel|complete|reservation(?:\/ack)?|outbound-order(?:\/ack)?|work-orders(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/status)?))?))?$/,
  },
  {
    // 9.3.1.2 合单（trade 服务）。本条目是**新增一条 services[] 元素**，不是往既有
    // trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在既有 `prefix: 'trade/'` **之前**：两条车道的插入点被既有条目隔开，
    // git 能干净求并（与 10.1.1.2 / 10.1.1.3 / 10.2.2.2 的 channel 条目同一手法）。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的第二条不会误吃前一条的路径。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities                          能力与上下游可用性（含 D5 未交付导致的 shop_code 缺口）
    //   rule / rule                           合单规则（GET 读 + PUT upsert）
    //   candidates                            候选订单检索（POST 只读，不落库）
    //   consolidations                        合并单列表（真分页）
    //   consolidations                        POST 落一次合并（头 + 成员 + 逐单回传任务）
    //   {uuid}                                合并单详情（头 + 成员 + 回传任务 + 事件流）
    //   {uuid}/status                         推进状态（CONFIRMED / DISPATCHED / CANCELLED）
    //   writeback-tasks                       回传任务列表
    //   {uuid}/members/{memberUuid}/writeback 按**平台订单**逐单登记物流号回传结果
    // 判别顺序：字面量子路径（capabilities / rule / candidates / writeback-tasks）
    // 排在 {uuid} 分支之前，否则会被 [0-9a-f-]+ 分支吃掉半截。
    // 刻意**没有** DELETE：合并单与事件流是履约凭据，删掉等于抹掉「这批货一起发过」的证据。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^fulfillment\/consolidation\/(?:capabilities|rule|candidates|writeback-tasks|consolidations(?:\/[0-9a-f-]+(?:\/(?:status|members\/[0-9a-f-]+\/writeback))?)?)$/,
    // 裸路径 fulfillment/consolidation **刻意不放行**：后端没有对应映射，
    // 放行它会让界面以为「一条不存在的列表接口」可用。
  },
  {
    // 9.3.1.1 拆单（trade 服务）。本条目是**新增一条 services[] 元素**，不是往既有
    // trade 分支的 alternation 里并分支 —— 那一支是一段超长单行正则，
    // 多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在既有 `prefix: 'trade/'` **之前**（即 9.3.1.2 合单那条之前）：
    // 两条车道的插入点被既有条目隔开，git 能干净求并。
    // resolve() 是「按顺序逐条匹配、首个命中即返回」，同前缀的第二条不会误吃前一条的路径。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   capabilities                     能力与上下游可用性（含「审单未接通」「不预占上游库存」）
    //   rule / rule                      拆单规则（GET 读 + PUT upsert）
    //   warehouses                       发货仓清单（GET；含 activeOnly 筛选）
    //   warehouses                       POST 登记 / 更新发货仓
    //   stock-snapshots                  可用库存分布清单（GET；按仓 / SKU 筛选）
    //   stock-snapshots                  POST 登记 / 更新可用库存分布
    //   preview                          拆单预览（POST，**无副作用**，与落库同一段判定逻辑）
    //   splits                           拆单列表（真分页 + 状态/维度/订单/仓筛选）
    //   splits                           POST 落一次拆单
    //   {uuid}                           拆单详情（头 + 单元 + 行级明细 + 候选 + 预占 + 回传 + 事件流）
    //   {uuid}/status                    推进状态（CONFIRMED / DISPATCHED / CANCELLED）
    //   reservation-commands             预占指令清单（真分页 + 状态/SKU 筛选）
    //   writeback-tasks                  发货回传任务清单（真分页）
    //   {uuid}/units/{unitUuid}/reservation 按履约单元登记**预占回执**（warehouse 侧）
    //   {uuid}/units/{unitUuid}/writeback     按履约单元登记**发货回传结果**（一单元一条）
    // 判别顺序：字面量子路径（capabilities / rule / warehouses / stock-snapshots /
    // preview / reservation-commands / writeback-tasks）排在 {uuid} 分支之前，
    // 否则会被 [0-9a-f-]+ 分支吃掉半截。
    // 刻意**没有** DELETE：拆单方案、候选依据与事件流都是履约凭据，
    // 删掉等于抹掉「这批货当时为什么这么拆」的证据。
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      /^fulfillment\/split\/(?:capabilities|rule|warehouses|stock-snapshots|preview|reservation-commands|writeback-tasks|splits(?:\/[0-9a-f-]+(?:\/(?:status|units\/[0-9a-f-]+\/(?:reservation|writeback)))?)?)$/,
    // 裸路径 fulfillment/split **刻意不放行**：后端没有对应映射，
    // 放行它会让界面以为「一条不存在的拆单列表接口」可用。
  },
  {
    prefix: 'trade/',
    env: 'TRADE_URL',
    fallback: 'http://127.0.0.1:8085',
    pattern:
      // fulfillment/dropship-tracking 是 9.6.1.3 代发履约跟踪与异常（**新的一组**，
      // 与上面两组并列而不是并进去）：配置说「走不走代发」，代发单说「怎么下的」，
      // 跟踪说「现在到哪了、卡在哪、怎么处置」。三件事混在一组里，权限与日志分不开。
      //   capabilities     能力与上下游可用性声明（含三条检出规则的自述）
      //   policy           检出策略（租户级单例）：集合读 + POST 维护
      //   trackings        集合读 + POST 建单 + {uuid} 详情
      //   trackings/{uuid}/supplier-leg  录入供应商段（发货 / 轨迹 / 妥投）
      //   trackings/{uuid}/own-leg       录入我方段（交运 / 妥投）
      //   exceptions       异常清单（集合读）；exceptions/evaluate 是跑检出规则
      //   exceptions/{uuid}/dispose      处置（催供应商 / 改自营发货 / 发起退款）
      //   exceptions/{uuid}/dismiss      判为误报（必须写明理由）
      //   dispositions/{uuid}/status     处置状态流转
      //   tasks            待回传 / 待处置任务（集合读）
      //   tasks/{uuid}/claim             下游领取
      //   tasks/{uuid}/complete          下游回执（受理 / 失败）
      //   events           事件流（集合读；append-only，**没有**任何写形状）
      // 判别顺序：字面量子路径（capabilities / policy / events / exceptions/evaluate）
      // 排在 {uuid} 分支之前，否则会被 [0-9a-f-]+ 分支吃掉半截。
      // 刻意**没有** DELETE：跟踪事件流与异常判定证据都是凭据，删掉等于抹掉历史。
      // logistics/customs-elements 是 10.5.1.2 申报要素（**新的一组**，与上面各组并列）：
      //   capabilities       能力与上下游可用性声明（含三张词表：申报币种 / 品牌类型 / 原产地规则）
      //   filter-options     筛选下拉（目的地 / 状态 / SKU，**真实数据 DISTINCT**）
      //   list               清单（真分页 + 真筛选 + 排序白名单；**汇总不受分页影响**）
      //   upstream-preview   上游合成申报资料（3.7.1.2，**跨服务只读**；不可达返 503 不是空对象）
      //   {uuid}             详情（清单行 + 补充要素项 + **缺失项提示** + 可执行动作）
      //   {uuid}/publish     发布（上游版本存在 / 产地一致 / 要素齐备 三道闸）
      //   {uuid}/close       向前关闭生效区间（只允许收紧到仍在未来的时刻）
      // 裸路径 logistics/customs-elements = POST 建集：@PostMapping 刻意用**无值**写法，
      // 后端没有 .../customs-elements/（尾斜杠）那个映射，所以**不放行**尾斜杠那一支。
      // b2b-orders 是 9.7.1.1 批发订单与账期（卷 09，本车道追加这一支）：
      //   capabilities          能力与上下游可用性
      //   customer-terms        客户档案投影（3.6.1.2 → trade 侧只读投影）：GET 集合 + PUT upsert
      //   price-books           阶梯价投影（05 价目 5.1.1.2 的 trade 侧投影）：GET 集合 + PUT upsert + {uuid} 详情
      //   price-quote           阶梯价试算（POST，只读不落库）
      //   receivables           账期应收列表（只读；核销属于 12 财务，本服务不提供写形状）
      //   {uuid}                订单详情
      //   {uuid}/confirm        确认订单（**确认不产生应收**）
      //   {uuid}/shipments      发货确认 —— 应收的**唯一**产生入口
      //   {uuid}/invoice-requests 登记开票需求
      // 判别顺序：字面量子路径排在 {uuid} 之前，否则会被 [0-9a-f-]+ 分支吃掉。
      // 裸路径 b2b-orders 与集合读共用一支（本文件的 pattern 按路径登记、不区分方法）。
      // dealers 是 9.7.1.2 经销与分销管理（卷 09，本车道追加这一支）：
      //   capabilities          能力与上下游可用性（16 合规 / 12 返点 / 5.5.2 / 13 分析均为 available=false）
      //   candidates            经销商候选（**跨服务只读** 3.6.1.2；上游不可达返 503，不是空清单）
      //   profiles              经销商档案投影（3.6.1.2 → trade 侧只读投影）：GET 集合 + PUT upsert
      //   authorizations        授权台账（输出①）：GET 集合 + POST 建授权
      //   authorizations/{uuid}            授权详情（台账 + 配额 + 计提 + 预警 + 事件流）
      //   authorizations/{uuid}/status     授权状态流转（暂停 / 恢复 / 到期）
      //   quotas               销量与回款考核目标（输出②的目标侧，POST）
      //   quota-attainment     配额完成率（输出②）；完成率目标为 0 时是 **null**，不是 0
      //   sales                销售与回款记录（配额与窜货判定的唯一来源）：GET 集合 + POST 登记
      //   rebates              返点计提台账（输出③，GET；financeConfirmed 恒为 false = 本模块口径未与财务确认）
      //   rebates/accrue       计提动作（POST，只读不代写 12 财务）
      //   diversion-alerts           窜货预警（输出④，GET）
      //   diversion-alerts/{uuid}/state  预警处置（判为误报必须写理由）
      // 刻意**不放行**裸路径 `dealers`（后端没有这个映射）与 `dealers/events`
      // （事件流只在授权详情里给，放行它会让界面以为有独立的事件查询端点）。
      // fulfillment/shipment-writeback 是 9.4.2.1 平台回传（卷 09，本车道追加这一支）：
      //   capabilities          能力与边界声明（三态词汇、店铺目录读不到 ⇒ shopId 由调用方给）
      //   board                 分组看板（status / platform / carrier / verification 四选一）
      //   events                事件流（append-only，真分页）
      //   retry-batch           批量重试（逐条说清谁被重投、谁被哪道闸门拦下）
      //   tasks                 回传任务清单：GET 集合 + POST 从发货记录建任务（幂等）
      //   tasks/{uuid}          任务明细（冻结 payload + 尝试凭据 + 查证 + 时间线）
      //   tasks/{uuid}/submit   提交投递（入 2 队列）
      //   tasks/{uuid}/outcome  登记平台结果三态（成功 / 失败 / **结果未知**）
      //   tasks/{uuid}/verify   查证结论（未知态必须先走这里才谈得上重发）
      //   tasks/{uuid}/retry    单条重试
      //   tasks/{uuid}/corrections 人工修正（新建一版，幂等键随之变化）
      // 刻意**不放行** PUT / DELETE：凭据行 append-only，要更正走 corrections。
      // 字面量子路径（capabilities / board / events / retry-batch / tasks）排在
      // [0-9a-f-]+ 之前判别，否则 tasks 会被 id 分支吃掉。
      /^(?:fulfillment\/routing\/(?:capabilities|mcf-authorizations|cost-profiles|stock-snapshots|evaluations|switches|decisions(?:\/[0-9a-f-]+(?:\/status)?)?)|fulfillment\/allocation\/(?:capabilities|rules|warehouses|stock-estimates|shipping-estimates|simulations|holds|allocations(?:\/[0-9a-f-]+(?:\/status)?)?)|fulfillment\/dropship\/(?:capabilities|shop-refs|supplier-refs|supplier-stocks|events|configs(?:\/[0-9a-f-]+(?:\/(?:status|lines))?)?|evaluations)|fulfillment\/dropship-orders\/(?:capabilities|endpoints|events|orders(?:\/[0-9a-f-]+(?:\/(?:confirm-payment|dispatch|supplier-ack|shipment|fail))?)?|alerts(?:\/[0-9a-f-]+\/acknowledge)?)|fulfillment\/pending-shipment\/(?:capabilities|board|units(?:\/[0-9a-f-]+(?:\/(?:sla|cancel))?)?|batch-tasks(?:\/[0-9a-f-]+(?:\/(?:execute|cancel|waybill-acks|labels))?)?|sync)|processing\/(?:capabilities|requirements|orders(?:\/[0-9a-f-]+(?:\/(?:status|progress|components\/issue|bom-binding(?:\/lines)?))?)?|factories(?:\/[0-9a-f-]+)?)|sales-orders(?:\/(?:capabilities|[0-9a-f-]+(?:\/submit)?))?|sales-orders\/list(?:\/(?:capabilities|filters))?|sales-orders\/detail(?:\/(?:capabilities|fulfillment-reference|[0-9a-f-]+))?|fulfillment\/dropship-tracking\/(?:capabilities|policy|events|exceptions\/evaluate|exceptions(?:\/[0-9a-f-]+\/(?:dispose|dismiss))?|dispositions\/[0-9a-f-]+\/status|trackings(?:\/[0-9a-f-]+(?:\/(?:supplier-leg|own-leg))?)?|tasks(?:\/[0-9a-f-]+\/(?:claim|complete))?)|sales-orders\/change\/(?:capabilities|platform-rules(?:\/[0-9a-f-]+)?|fulfillment-progress|requests(?:\/[0-9a-f-]+(?:\/(?:status|writeback-tasks))?)?|cancellations(?:\/[0-9a-f-]+(?:\/(?:status|warehouse-tasks(?:\/[0-9a-f-]+\/status)?|writeback-tasks))?)?|writeback-tasks\/[0-9a-f-]+\/status)|b2b-orders(?:\/(?:capabilities|receivables|customer-terms|price-quote|price-books(?:\/[0-9a-f-]+)?|[0-9a-f-]+(?:\/(?:confirm|shipments|invoice-requests))?))?|fulfillment\/shipment-records\/(?:capabilities|board|performance|events|records(?:\/[0-9a-f-]+(?:\/(?:tracking|visibility|callbacks|status))?)?|sync)|fulfillment\/shipment-writeback\/(?:capabilities|board|events|retry-batch|tasks(?:\/[0-9a-f-]+(?:\/(?:submit|outcome|verify|retry|corrections))?)?)|fulfillment\/timeliness\/(?:capabilities|report|dimensions|overdue|events|facts(?:\/[0-9a-f-]+(?:\/(?:promise|delivery))?)?|sync)|fulfillment\/quality\/(?:capabilities|report|dimensions|drilldown|events|after-sale-signals|exceptions|alert-rules(?:\/[A-Z_]+)?|alerts(?:\/evaluate|\/[0-9a-f-]+\/acknowledge)?)|logistics\/waybills\/(?:capabilities|filter-options|list|actions\/(?:apply|cancel|print)|[0-9a-f-]+(?:\/freight)?)||logistics\/customs-elements(?:\/(?:capabilities|filter-options|list|upstream-preview|[0-9a-f-]+(?:\/(?:publish|close))?))?|logistics\/waybill-applications(?:\/(?:capabilities|carriers|batches|queue|requests(?:\/dispatch|\/[0-9a-f-]+(?:\/(?:retry|cancel))?)?))?|logistics\/waybill-address-change\/(?:capabilities|rules|notifications|redirects(?:\/[0-9a-f-]+(?:\/submit)?)?|cancels(?:\/[0-9a-f-]+(?:\/submit)?)?|precheck)|fulfillment\/dropship-margin\/(?:capabilities|fee-rates|fx-rates|cycles|events|facts(?:\/evaluate)?|report|alerts(?:\/evaluate)?|alerts\/[0-9a-f-]+\/(?:acknowledge|dismiss)|reconciliations(?:\/[0-9a-f-]+(?:\/status)?)?|tasks(?:\/[0-9a-f-]+\/(?:claim|complete))?)|dealers\/(?:capabilities|candidates|profiles|quota-attainment|sales|authorizations(?:\/[0-9a-f-]+(?:\/status)?)?|quotas|rebates(?:\/accrue)?|diversion-alerts(?:\/[0-9a-f-]+\/state)?))$/,
    // 裸路径 fulfillment/routing、fulfillment/allocation 与 processing **刻意不放行**：
    // 后端没有对应映射，放行它会让界面以为「一条不存在的列表接口」可用。
  },
  {
    // listing-templates 是 4.2.2.1 刊登模板的**独立前缀**（channel 服务，端口 8083）。
    // 本条目是**新增一条 services[] 元素**，不是往既有 channel 长正则的 alternation 里并 ——
    // 那样本车道与并行车道的插入点会被隔开，git 能干净求并。
    //
    // 形状逐条列出：
    //   listing-templates                      集合读（真分页 + 真筛选：作用域/平台/店铺/类目/状态/关键字）
    //   listing-templates/capabilities         能力声明（编辑器侧套用入口 available=false + ownerModule=4.2.1.1）
    //   listing-templates/filter-options       筛选项词表（平台 / 店铺 / 站点，不硬编码在前端）
    //   listing-templates/apply                一键套用（POST，产出的是**草稿**，不写线上）
    //   listing-templates/applies              套用记录列表（GET 真分页）
    //   listing-templates/applies/{uuid}       单次套用详情（逐字段求值明细 + 必填违规清单）
    //   listing-templates/{uuid}               模板详情（GET）/ 新建与更新（POST）/ 删除（DELETE）
    //   listing-templates/{uuid}/status        启停模板（PUT）
    //   listing-templates/{uuid}/matches      适用范围命中的 listing（GET 真分页 —— 判定有真实数据支撑）
    //
    // 字面量子路径（capabilities / filter-options / apply / applies）一律排在 {uuid} 段**之前**判别，
    // 否则 apply / applies 会被 UUID 分支吃掉。id 段用**完整 UUID 形状**
    // [0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}（不是 [0-9a-f-]+）——
    // 宽松的字符集会把 `listing-templates/apply` 之外的任意串也当成 id 放行。
    //
    // **刻意不放行**：
    //   * 裸的 `listing-templates/{uuid}/{任意动作}` —— {uuid} 之后只认 status 与 matches 两个
    //     已实现的子资源；多放一段会让一个还不存在的路径看起来像已交付。
    //   * 任何「直接上线 / 发布」形状 —— 刊登模板只产出草稿，发布归 4.3.1.2；
    //     放行一个发布形状只会让界面以为有直接上线入口。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^listing-templates(?:\/(?:capabilities|filter-options|apply|applies(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:status|matches))?))?$/,
  },
  {
    // crm 是 15 客户与客服的服务（端口 8086，数据库 starcon_crm），15.4.2.1 知识库
    // 是它的第一条业务端点。本条目是**新增一条 services[] 元素**，
    // 不是往既有分支的 alternation 里并 —— 所以前六个分支逐字未动
    // （由 crm/knowledge-allowlist.test.ts 的「不误伤」一组反向钉住）。
    //
    // ⚠️ 与 catalog 那一支里的 `kb` **不是同一个东西**：那是 11.4.2.2「知识库与话术」
    // （catalog 服务、按平台规则版本组织、页面 /aftersales/knowledge）。
    // 本模块是 crm 服务的客服知识（按店铺/类目/语言组织、页面 /crm/knowledge）。
    // 两个模块都叫「知识库」但回答的不是同一个问题，**刻意不共用命名空间**，
    // 也不共用任何一张表（理由见 backend/services/crm/.../V99__crm_knowledge_base.sql 文件头第零节）。
    //
    // 形状逐条列出：
    //   capabilities / scopes / review-due 是**字面量单段**（刻意不接 id 段 ——
    //     那是能力声明与两条聚合查询，不按 id 寻址）；
    //   entries 是集合读 + POST 新建；entries/effective 是「此刻生效的那些」；
    //     entries/{uuid} 是单条详情；entries/{uuid}/versions 是版本列表 + 建草稿版本；
    //     entries/{uuid}/references 是该知识全部版本的引用记录；
    //   versions/{uuid} 刻意**不放行** —— 后端没有「按 id 取单条版本」的端点；
    //     其下的 publish / close / references 是三个不同的动作，各自寻址，
    //     所以这段后缀是**必需**的（写成可选会让裸的 versions/{uuid} 一起通过，
    //     那等于放行一个后端不存在的映射）。
    //   字面量段一律排在 [0-9a-f-]+ 之前判别：首段是字面量还是 UUID 两者不会互相吃掉。
    //
    // 刻意**没有** DELETE：引用记录是只追加的审计事实（V99 第五节），
    // 已发布版本只能向前关闭（POST close）而不是被改写或删除。
    // 放行删除形状等于让界面以为可以删掉一条引用 —— 那会把「没人用过」这个答案做出来。
    prefix: 'crm/',
    env: 'CRM_URL',
    fallback: 'http://127.0.0.1:8086',
    pattern:
      /^knowledge\/(?:capabilities|scopes|review-due|entries(?:\/effective|\/[0-9a-f-]+(?:\/(?:versions|references))?)?|versions\/[0-9a-f-]+\/(?:publish|close|references))$/,
    // 裸路径 knowledge **刻意不放行**：后端没有这个映射，
    // 放行它会让界面以为「一条不存在的列表接口」可用。
  },
  {
    // 7.8.2.2 缺货与饥饿预警（warehouse 服务，7.8.2 的第二个 L3 锚点）。
    //
    // 蓝图：「识别因分摊/优先级导致的店铺"饥饿"（长期分不到货、影响绩效），以及真正的全网缺货，
    // 区分两者给出不同处置建议。输入：分摊结果；店铺销售与绩效；总可用量。
    // 输出：饥饿店铺清单 + 缺货清单 + 建议（调优先级/调保留/补货）。」
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 warehouse 那条巨型正则里并 ——
    // 那一行被好几条车道共用，同时改它必然撞车。插入锚点固定在 services[] 的**最末尾**
    // （紧挨着 `];`），与另外三条车道的插入点互不重叠。
    //
    // 形状逐条列出（11 个）：
    //   capabilities            能力声明（两类判定的差别 / 未知态语义 / 未交付的三环）
    //   evaluate                一次评估（POST）—— 分别判定「缺货」与「饥饿」，各自落各自的表
    //   starvations             饥饿店铺清单（GET）
    //   starvations/{uuid}      单条详情（GET）
    //   starvations/{uuid}/acknowledge   认领（POST）
    //   starvations/{uuid}/resolve       处置（POST，处置说明必填）
    //   stockouts               缺货清单（GET）
    //   stockouts/{uuid}        单条详情（GET）
    //   stockouts/{uuid}/acknowledge     认领（POST）
    //   stockouts/{uuid}/resolve         处置（POST）
    //
    // 判别顺序：**字面量子路径（capabilities / evaluate / starvations / stockouts）排在
    // {uuid} 段之前** —— alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    // id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`（不是
    // `[0-9a-f-]+`）：后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    // 两段形状（{uuid}/acknowledge、{uuid}/resolve）挂在单段 {uuid} 组的可选后缀里，
    // 否则会被单段分支吃掉半截、把动作名留在匹配之外。
    //
    // 刻意**不放行**的形状：
    //   裸路径 inventory/stockout-alerts —— 后端没有这个映射（清单在 /starvations 与 /stockouts 两个子路径上）。
    //   任何 {uuid} 之外的第三层子路径 —— 后端只有 acknowledge 与 resolve 两个动作。
    //   任何 DELETE / PUT 形状 —— 后端根本没有对应端点（405），而 V192 的 REVOKE DELETE
    //   与不可删触发器还会各挡一次。注意白名单是**按路径登记、不区分方法**的，
    //   所以「不能删」的防线在服务端与 DB，不在这里。
    //
    // 与既有分支不重叠：既有 warehouse 那条巨型正则的 alternation 里
    // 没有 `stockout-alerts`（`stocktakes` / `stocktake-variances` 与它不是一回事），
    // 所以 `inventory/stockout-alerts/...` 不会先被既有分支吃掉。
    prefix: 'warehouse/',
    env: 'WAREHOUSE_URL',
    fallback: 'http://127.0.0.1:8084',
    pattern:
      /^inventory\/stockout-alerts\/(?:capabilities|evaluate|starvations(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:acknowledge|resolve))?)?|stockouts(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:acknowledge|resolve))?)?)$/,
  },
  {
    // 16.2.2.1 合规预检（channel 服务，卷 16「合规与风控」的 L3 锚点 16.2.2）。
    //
    // 蓝图：「发布前的合规体检：内容违规、认证缺失、尺寸规格不符、材质与描述不符、危险品与运输限制、
    // 标签与说明书要求，多维度检查并给出整改建议。输入：listing 内容草稿；商品属性；认证状态；平台要求。
    // 输出：体检报告（分维度得分 + 问题清单 + 整改建议）。关系：上游 = 4.2.1.1 编辑器；下游 = 16.2.1.2 拦截。
    // **预检在保存草稿时就跑一次**（即时反馈），发布时再跑一次（强制校验）。」
    //
    // 本条目是**新增一条 services[] 元素**，不是往既有 channel 分支的超长 alternation 里并 ——
    // 那一支是单行超长正则，多车道同时并进去 git 必然判冲突（结构性，retry 不会自愈）。
    // 插入锚点固定在 services[] 的**最末尾**（紧挨着 7.8.2.2 的 `inventory/stockout-alerts` 元素
    // 之后、`];` 之前），与另外三条车道的插入点互不重叠。
    //
    // 🔴 为什么用 `compliance-precheck` 这个独立前缀：
    //   * `listing-compliance-precheck` 是同批 4.2.3.2 车道的前缀（卷 04 的「合规预检（违禁词/侵权）」）——
    //     那是**另一个模块**：那边出「阻断项 + 提醒项」的风险清单，这边出「分维度得分 + 问题清单 +
    //     整改建议」的体检报告。两边不共用表、不共用端点、不共用页面，也不共用白名单元素。
    //   * `listing-*` 开头的既有前缀已有 8 条，视觉上再挤一条进去更容易看错行。
    //   * `compliance-*` 也不选：16.2.1.1 规则库未来会占那个命名空间。
    //
    // 形状逐个列出（path 只有本模块自己的）：
    //   compliance-precheck/capabilities            能力声明（逐维度量得到 / 量不到 + 归属模块）
    //   compliance-precheck/drafts                  可供体检的内容草稿（GET，4.2.1.1 的草稿，只读）
    //   compliance-precheck/drafts/{uuid}/check      🔴 保存草稿时的即时反馈（POST）
    //   compliance-precheck/drafts/{uuid}/precheck   🔴 发布前的强制校验（POST）
    //   compliance-precheck/reports                 报告历史（GET，真分页）
    //   compliance-precheck/reports/{uuid}          单份报告（GET：分维度得分 + 问题清单 + 整改建议）
    //
    // ⚠️ 判别顺序：**字面量子路径（capabilities / drafts / reports）排在 {uuid} 段之前** ——
    //    alternation 是首个命中即返回，顺序反了会被通配支吃掉半截。
    // ⚠️ `drafts/{uuid}` 的**动作段是必选的**（`(?:check|precheck)` 不带 `?`）：
    //    后端没有「按草稿取单条体检入口」这个资源，放行 `drafts/{uuid}` 就是凭空多出一条 GET。
    // ⚠️ id 段用**完整 UUID 形状**（照抄本文件里其余 20 处的同一写法），不是 `[0-9a-f-]+` ——
    //    后者会把 `capabilities` 这种非 UUID 形状也当成 id 放行。
    //
    // 刻意**不放行**的形状（放行后端不存在的映射比 404 更糟 —— 界面会以为功能已交付）：
    //   * 裸路径 `compliance-precheck` —— 本模块没有裸路径资源。
    //   * `compliance-precheck/drafts/{uuid}` —— 没有「按草稿取单条体检入口」这个资源。
    //   * `compliance-precheck/reports/{uuid}/publish|block|exempt|approve` ——
    //     🔴 拦截、豁免与审批归 16.2.1.2（未交付），本模块只出报告与结论。
    //   * `compliance-precheck/rules|library|certifications` ——
    //     🔴 16.2.1.1 规则库与 16.2.2.2 认证与资质都未交付，本模块不代它们开形状。
    //   * `compliance-precheck/reports/{uuid}/delete` —— 报告是那一次判定的凭据，不可删。
    //   * `listing-compliance-precheck/...` —— 同批 4.2.3.2 车道的形状，不是本模块的。
    //
    // 与既有分支不重叠：既有 channel 分支的 alternation 里没有 `compliance-precheck`，
    // 所以 `compliance-precheck/...` 不会先被既有分支吃掉。
    prefix: 'channel/',
    env: 'CHANNEL_URL',
    fallback: 'http://127.0.0.1:8083',
    pattern:
      /^compliance-precheck(?:\/capabilities|\/drafts(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/(?:check|precheck))?|\/reports(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?)$/,
  },
];

function resolve(path: string) {
  for (const service of services) {
    if (!path.startsWith(service.prefix)) continue;
    if (service.pattern.test(path.slice(service.prefix.length)))
      return { base: process.env[service.env] ?? service.fallback };
  }
  return null;
}

export async function proxy(
  request: NextRequest,
  context: { params: { path: string[] } | Promise<{ path: string[] }> },
) {
  const path = (await context.params).path.join('/');
  const target = resolve(path);
  if (!target) return NextResponse.json({ message: '接口不存在' }, { status: 404 });
  if (
    !['GET', 'HEAD'].includes(request.method) &&
    request.headers.get('origin') !== request.nextUrl.origin
  ) {
    return NextResponse.json({ message: '请求来源不正确' }, { status: 403 });
  }
  const { base } = target;
  const body = request.method === 'GET' ? undefined : await request.text();
  if (body && body.length > 200_000)
    return NextResponse.json({ message: '请求内容过大' }, { status: 413 });
  try {
    const upstream = await fetch(`${base}/${path}${request.nextUrl.search}`, {
      method: request.method,
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${request.cookies.get(cookieName)?.value ?? request.cookies.get(legacyCookieName)?.value ?? ''}`,
      },
      ...(body ? { body } : {}),
      cache: 'no-store',
      signal: AbortSignal.timeout(15_000),
    });
    const text = await upstream.text();
    const contentType = upstream.headers.get('content-type') ?? '';
    // CSV 导出必须原样透传：走 JSON.parse 会把 CSV 解析失败变成 503，而且即使解析成功
    // 也会被重新序列化成 JSON，下载到的是一串引号转义的文本。
    // 只认 text/csv：JSON 接口一律走下面的正常分支，不受这里影响。
    //
    // 8.3.4.2 面单与标签打印的**打印内容下载**（ZPL 指令流 / HTML 打印视图）同理：
    // 两者都不是 JSON，走 JSON.parse 只会得到一个假的 503，现场看到的就是「下载失败」。
    // 所以把这两个 content-type 一并纳入原样透传（content-disposition 也一起带走，
    // 否则浏览器会拿不到文件名）。白名单式列举而不是「非 JSON 就透传」：
    // 后者会让上游一个返回 HTML 的错误页被当成文件下载下来，更难排查。
    const passthrough =
      contentType.includes('text/csv') ||
      contentType.includes('text/vnd.zpl') ||
      contentType.includes('text/html');
    if (text && passthrough) {
      const raw = new NextResponse(text, { status: upstream.status, headers: {} });
      raw.headers.set('Content-Type', contentType);
      const disposition = upstream.headers.get('content-disposition');
      if (disposition) raw.headers.set('Content-Disposition', disposition);
      raw.headers.set('Cache-Control', 'no-store');
      if (upstream.status === 401) {
        raw.cookies.delete(cookieName);
        raw.cookies.delete(legacyCookieName);
      }
      return raw;
    }
    const data = text ? JSON.parse(text) : null;
    let token: string | undefined;
    if (path === 'auth/login' && upstream.ok && data?.token) {
      token = data.token;
      delete data.token;
    }
    const response = NextResponse.json(data, {
      status: upstream.status,
      headers: { 'Cache-Control': 'no-store' },
    });
    if (token) {
      response.cookies.delete(legacyCookieName);
      response.cookies.set(cookieName, token, {
        httpOnly: true,
        sameSite: 'strict',
        secure: request.nextUrl.protocol === 'https:',
        path: '/',
        maxAge: 8 * 3600,
      });
    }
    if ((path === 'auth/logout' && upstream.ok) || upstream.status === 401) {
      response.cookies.delete(cookieName);
      response.cookies.delete(legacyCookieName);
    }
    return response;
  } catch {
    return NextResponse.json({ message: '服务暂时无法连接，请稍后重试' }, { status: 503 });
  }
}

/** Next.js route handlers receive asynchronously resolved route parameters. */
export async function routeProxy(
  request: NextRequest,
  context: { params: Promise<{ path: string[] }> },
) {
  return proxy(request, context);
}
