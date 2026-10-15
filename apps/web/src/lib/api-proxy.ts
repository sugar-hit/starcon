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
      // onboardings 是 17.2.2.2 成员开通与入职：列表、开通（创建）与完成入职。
      //   形状逐字列出：onboardings 是集合读 + POST 开通；
      //   onboardings/{id}/complete 是完成入职的状态流转（POST）。
      //   **刻意不放行 onboardings/{id}**：后端没有「按 id 取单条入职记录」这个端点，
      //   放行它会让界面以为已交付——放行不该存在的路径比 404 更糟。
      /^(permissions|owner-transfer|legal-entities(?:\/[0-9a-f-]+)?|legal-entities\/(?:[0-9a-f-]+\/accounting-books|references)|(?:roles|members|departments|children)(?:\/[0-9a-f-]+)?|tenants(?:\/current)?|audit(?:\/(?:export|[0-9a-f-]+))?|operation-log(?:\/(?:export|trace\/[0-9a-zA-Z-]+|[0-9a-f-]+))?|hierarchy-authorizations(?:\/check|\/[0-9a-f-]+(?:\/revoke)?)?|accounting-books(?:\/[0-9a-f-]+)?|business-parameters(?:\/(?:effective|history|overrides(?:\/[0-9a-f-]+)?|freezes(?:\/[^/]+\/[^/]+)?))?|numbering(?:\/(?:entities|preview|sequences|issued|rules(?:\/conflicts|\/[0-9a-f-]+(?:\/next)?)?))?|approval-flows(?:\/(?:document-types|registrations|active|positions(?:\/[0-9a-f-]+)?|[0-9a-f-]+(?:\/validation)?))?|approval-rules(?:\/(?:effective-policy|evaluate|[0-9a-f-]+(?:\/validation)?))?|onboardings(?:\/[0-9a-f-]+\/complete)?|department-leaders|departments\/[0-9a-f-]+\/(?:leader(?:\/(?:proxies|grants)(?:\/[0-9a-f-]+)?)?|approval-chain)|offboarding(?:\/[0-9a-f-]+(?:\/revert|\/responsibility-items(?:\/[0-9a-f-]+(?:\/transfer)?)?|\/reclaim-items(?:\/[0-9a-f-]+(?:\/reclaim)?)?)?)?|legal-entities\/(?:[0-9a-f-]+\/(?:accounting-books|ledger-books|default-book)|references)|accounting-books(?:\/[0-9a-f-]+(?:\/fact-assignments(?:\/[0-9a-f-]+)?)?)?)$/,
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
      /^(products(?:\/[0-9a-f-]+(?:\/(?:attributes|skus)(?:\/[0-9a-f-]+)?)?)?|attributes(?:\/(?:overview|category-rules|options(?:\/(?:lifecycle|order))?|[0-9a-f-]+(?:\/(?:options|category-rules))?))?|brands(?:\/(?:overview|candidates|listing-eligibility|[0-9a-f-]+(?:\/(?:listing-eligibility|enabled|authorizations(?:\/[0-9a-f-]+(?:\/enabled)?)?))?))?|parties(?:\/(?:overview|candidates|[0-9a-f-]+(?:\/(?:status|roles(?:\/[0-9a-f-]+)?|role-profiles|(?:supplier|customer|carrier)-profile|addresses(?:\/(?:defaults|candidates|[0-9a-f-]+(?:\/(?:usages|default|versions(?:\/[0-9]+)?))?))?|contacts(?:\/(?:default|candidates|[0-9a-f-]+(?:\/primary)?))?))?))?|categories(?:\/(?:overview|trees(?:\/[0-9a-f-]+(?:\/nodes)?)?|nodes\/[0-9a-f-]+(?:\/(?:move|enabled))?|spus\/[0-9a-f-]+|attribute-requirements(?:\/overview|\/[0-9a-f-]+(?:\/[0-9a-f-]+(?:\/enabled)?|\/(?:effective|entry-form-schema|validate))?)))|records\/(research|compliance|assets)(?:\/[0-9a-f-]+)?|media(?:\/(?:overview|verify-sweep|references|tags|processing(?:\/(?:overview|recipes(?:\/[0-9a-f-]+(?:\/enabled)?)?|jobs(?:\/[0-9a-f-]+(?:\/(?:items|cancel))?)?|assets\/[0-9a-f-]+\/history)?)?)|[0-9a-f-]+(?:\/(?:versions|references(?:\/[0-9a-f-]+)?|integrity-checks|enabled))?|tags\/[0-9a-f-]+(?:\/enabled)?)?|skus(?:\/(?:capabilities|batch|export))?|unit-groups(?:\/(?:overview|convert|[0-9a-f-]+(?:\/(?:enabled|conversions(?:\/[0-9a-f-]+)?))?))?|unit-conversion(?:\/(?:units(?:\/[0-9a-f-]+)?|overview|skus\/[0-9a-f-]+(?:\/conversions)?|convert|snapshots))?|spec-axis(?:\/(?:overview|axes(?:\/[0-9a-f-]+(?:\/options)?)?|spus\/[0-9a-f-]+(?:\/axes(?:\/[0-9a-f-]+)?)?|category-rules))?|variant-combination(?:\/(?:overview|spus\/[0-9a-f-]+(?:\/(?:combinations|check|drafts(?:\/[0-9a-f-]+)?))?))?|sku-attribute-override\/skus\/[0-9a-f-]+(?:\/attribute-overrides\/[0-9a-f-]+(?:\/clear)?)?|titles(?:\/(?:overview|templates(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/enabled)?)?|keywords(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/enabled)?)?|generations(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/skus)?)?|records(?:\/export|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:select|deselect|reject))?)?))?|descriptions(?:\/(?:overview|templates(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:enabled|translation-coveragetranslations(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?))?)?|phrases(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/enabled)?)?|renders(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:skus|missing-variables))?)?|translations\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|sections(?:\/export)?))?|bundles(?:\/(?:overview|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:status|effective|versions))?))?|versions(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:publish|close|components|component-check))?)?|components(?:\/(?:overview|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}))?|bundle-skus(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|boms(?:\/(?:overview|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:status|effective|versions))?))?|bom-versions(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:publish|close|copy|components|requirement))?)?|bom-components\/usage|bom-explosions(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|import-templates(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/file)?)?|import-field-mappings(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?|import-jobs(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:mapping|prevalidate|execute|cancel|rows|errors|report(?:\/(?:issues|exports(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/file)?))?))?)?|supplier-quotes(?:\/(?:overview|options|effective(?:\/resolve)?|[0-9a-f-]+(?:\/tiers)?))?|role-profiles(?:\/(?:overview|options))?|translations(?:\/(?:overview|locales(?:\/(?:requirements|sync|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/enabled)?))?|spus\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/entries)?|skus\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/entries)?|entries(?:\/export|\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/status)?)?|coverage|suggestions(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:adopt|reject))?)?|batches(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?))|duplicate-scans(?:\/(?:overview|[0-9a-f-]+(?:\/candidates)?)?)?|duplicate-candidates\/[0-9a-f-]+(?:\/(?:decision|merge-preview|merge)?)?|export-sources|export-jobs(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:file|cancel))?)?|zombie-candidates(?:\/(?:overview|options|rules(?:\/[A-Za-z0-9_]+)?|evidence|reference-stats|deactivate|records|deactivation-targets))?|completeness(?:\/(?:overview|options|gaps(?:\/summary)?|evaluations(?:\/[0-9a-f-]+)?|precheck\/(?:listing|customs)|customs\/skus\/[0-9a-f-]+|certifications\/skus\/[0-9a-f-]+(?:\/[0-9a-f-]+)?|(?:SPU|SKU)\/[0-9a-f-]+))?|customs-attributes(?:\/profiles|\/skus\/[0-9a-f-]+(?:\/(?:submit|review))?|\/declaration-ready)|sku-merges(?:\/overview|\/by-candidate\/[0-9a-f-]+|\/by-sku\/[0-9a-f-]+|\/[0-9a-f-]+)?|description-templates(?:\/(?:overview|options|templates(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\/(?:enabled|sections))?)?|renders(?:\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})?))?|kb(?:\/(?:overview|options|entries(?:\/[0-9a-f-]+(?:\/(?:enabled|translations(?:\/[0-9a-f-]+)?|translation-coverage|rule-bindings(?:\/[0-9a-f-]+)?|usages))?)?|translations\/[0-9a-f-]+|rule-versions(?:\/[0-9a-f-]+)?|review-tasks(?:\/[0-9a-f-]+)?|usages|hit-rates))?|customs-destination-versions(?:\/effective|\/requirements|\/skus\/[0-9a-f-]+|\/[0-9a-f-]+\/publish)|customs-declaration\/resolve|supply\/vendors(?:\/(?:options|overview)|\/[0-9a-f-]+(?:\/(?:profile|panorama|purchase-history|quotes|settlement-terms|deliveries(?:\/[0-9a-f-]+\/void)?|quality-inspections(?:\/[0-9a-f-]+\/void)?|qualifications(?:\/[0-9a-f-]+)?))?)?)$/,
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
    pattern:
      /^(platforms(?:\/[A-Za-z0-9_]+\/enums(?:\/[0-9a-f-]+)?)?|connections(?:\/(?:reminders|[0-9a-f-]+(?:\/(?:credential|health|revoke))?))?|shops(?:\/[0-9a-f-]+(?:\/(?:marketplaces(?:\/[0-9a-f-]+)?|transfer))?)?|sync-cursors(?:\/[0-9a-f-]+(?:\/(?:pause|resume|rewind|trigger))?)?|sync-runs(?:\/[0-9a-f-]+(?:\/(?:complete|resolve))?)?|sync-failures(?:\/summary|[0-9a-f-]+\/replay)?|platform-operations(?:\/[0-9a-f-]+(?:\/(?:start|succeed|fail|unknown|verify))?)?|oauth\/(?:callback|sessions(?:\/[0-9a-f-]+(?:\/(?:shops|confirm|cancel))?)?)|warehouse-providers(?:\/(?:connections(?:\/[0-9a-f-]+(?:\/(?:sync-paused|accounts))?)?|sync-runs|accounts(?:\/[0-9a-f-]+(?:\/(?:inbound-stock-lines|sync|observations(?:\/latest)?))?)?))?|handover-batches(?:\/(?:capabilities|group-preview|candidates(?:\/sync)?|[0-9a-f-]+(?:\/(?:manifest|timeline|dispatch|receipt|cancel))?))?|carriers(?:\/(?:capabilities|overview|sync|[0-9a-f-]+))?)$/,
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
      /^(inventory\/(?:overview|buckets|bucket-actions|capabilities|policies(?:\/[0-9a-f-]+)?|sellable-policies(?:\/(?:effective|pools(?:\/[^/]+)?(?:\/calculation)?|calculation)|\/[0-9a-f-]+(?:\/(?:versions|retire))?)?|sellable(?:\/(?:capabilities|skus))?|safety-stock(?:\/capabilities|\/policies(?:\/effective|\/[0-9a-f-]+(?:\/(?:versions|retire))?)?|\/sales|\/evaluate|\/alerts(?:\/[0-9a-f-]+(?:\/(?:acknowledge|resolve))?)?|\/suggestions(?:\/[0-9a-f-]+\/(?:acknowledge|dismiss))?|\/evaluations)?|stocktakes(?:\/capabilities|\/[0-9a-f-]+(?:\/lines(?:\/[0-9a-f-]+\/count)?|\/tasks(?:\/[0-9a-f-]+\/assign)?)?)?|expiry(?:\/(?:alerts|policies|allocation|policy(?:\/[0-9a-f-]+)?|warehouses|lots(?:\/[0-9a-f-]+)?))?|lots(?:\/[0-9a-f-]+(?:\/(?:distribution|movements))?)?|ledger(?:\/ref-types|\/[0-9a-f-]+)?|reconciliation(?:\/[0-9a-f-]+(?:\/findings)?)?|distribution(?:\/spus)?|platform-observations|serial-vocabularies|serials(?:\/(?:import|movements|validate-movement|stats)|\/[0-9a-f-]+(?:\/movements)?)?|transfers(?:\/[0-9a-f-]+(?:\/(?:movements|confirm|ship|receive|close))?)?|in-transit(?:\/(?:alerts|netting)|\/[0-9a-f-]+(?:\/(?:arrive|discrepancy))?)?|stocktake-variances(?:\/capabilities|\/sheets\/[0-9a-f-]+\/(?:lines(?:\/[0-9a-f-]+\/attribute)?|report|audit|adjustments)|\/adjustments(?:\/[0-9a-f-]+(?:\/(?:submit|approve|reject|cancel|post))?)?)?|cross-entity-transfers(?:\/[0-9a-f-]+(?:\/(?:movements|settlement|source-confirm|target-confirm|ship|receive|close|cancel))?)?|health(?:\/(?:trend|gate|issues|assess))?|ownership(?:\/(?:capabilities|resolved|report|drift|reference-options|reassign|owners(?:\/[0-9a-f-]+(?:\/status)?)?|rules(?:\/[0-9a-f-]+(?:\/(?:versions|retire))?)?))?|allocation-policies(?:\/(?:capabilities|effective|calculation|runs(?:\/[0-9a-f-]+)?|[0-9a-f-]+(?:\/(?:versions|retire))?)?)?|allocation-sales|consignments(?:\/capabilities|\/[0-9a-f-]+(?:\/(?:lines|stock-view|settlement-window|settlements|returns|audit|activate|suspend|close))?|\/lines\/[0-9a-f-]+\/sales|\/settlements(?:\/[0-9a-f-]+(?:\/confirm)?)?|\/returns(?:\/[0-9a-f-]+(?:\/(?:execute|cancel))?)?)?|outbound-orders(?:\/(?:capabilities|detail-capabilities|pick-requests|print|cancel)|\/[0-9a-f-]+(?:\/transition)?)?|printing(?:\/capabilities|\/templates(?:\/[0-9a-f-]+)?|\/devices|\/reprint-rules|\/targets(?:\/[0-9a-f-]+(?:\/history)?)?|\/jobs(?:\/[0-9a-f-]+(?:\/(?:execute|cancel)|\/items\/[0-9a-f-]+\/content)?)?|\/logs)?|serial-movements|serial-operations|reservations(?:\/options|\/capabilities|\/release-candidates|\/expiry-runs(?:\/[0-9a-f-]+)?|\/release-records|\/release-options|\/release-capabilities|\/expire-overdue|\/[0-9a-f-]+(?:\/allocations(?:\/[0-9a-f-]+\/(?:commit|consume))?|\/timeline|\/allocation-options|\/capabilities|\/release|\/expiry)?)?|aging(?:\/(?:buckets|slow-moving|policies|policy(?:\/[0-9a-f-]+)?))?|serials(?:\/(?:import|movements|validate-movement|stats)|\/[0-9a-f-]+(?:\/movements)?)?|allocation-priority(?:\/(?:capabilities|calculation|runs|resolutions|floors(?:\/[0-9a-f-]+(?:\/(?:versions|retire))?)?))?)|facilities(?:\/(?:options|capabilities))?|warehouses|legal-entities|skus|quality-statuses|locations|labor(?:\/capabilities|\/report|\/ranking|\/trend|\/work-tasks(?:\/[0-9a-f-]+(?:\/(?:assign|start|complete|exception))?)?)?|goods-receipts(?:\/capabilities|\/[0-9a-f-]+(?:\/(?:movements|receive|close))?)?|goods-receipt-discrepancies(?:\/capabilities|\/[0-9a-f-]+(?:\/(?:evidence|decide|claim(?:\/(?:submit|withdraw))?|close))?)?|processing-orders(?:\/capabilities|\/boundary|\/issues|\/executions)?|first-mile-shipments(?:\/capabilities|\/source-candidates|\/[0-9a-f-]+(?:\/(?:transit-legs|confirm|dispatch|cancel))?)?|transit-trackings(?:\/capabilities|\/alerts(?:\/evaluate-all|\/[0-9a-f-]+\/(?:acknowledge|close))?|\/thresholds(?:\/[0-9a-f-]+)?|\/[0-9a-f-]+(?:\/(?:events|receipt|close)|\/alerts\/evaluate)?)?|facilities(?:\/(?:options|capabilities)|\/[0-9a-f-]+(?:\/(?:locations|entrypoints))?)?|lots|customs-declarations(?:\/capabilities|\/[0-9a-f-]+(?:\/(?:documents|results|events|submit-for-review|review|reject-review|withdraw-review|submit|amend|cancel)|\/documents\/[0-9a-f-]+\/finalize)?)?|duty-taxes(?:\/capabilities|\/variance-analysis|\/rates(?:\/[0-9a-f-]+)?|\/[0-9a-f-]+(?:\/(?:lines|payments|variances|recalculate|lock|void))?)?|trade-documents(?:\/capabilities|\/rules(?:\/[0-9a-f-]+)?|\/templates(?:\/[0-9a-f-]+)?|\/certifications(?:\/[0-9a-f-]+)?|\/preference-rules(?:\/[0-9a-f-]+)?|\/preference-confirmations|\/reminders(?:\/[0-9a-f-]+\/resolve)?|\/[0-9a-f-]+(?:\/(?:documents|checks|reminders|events|submit-for-review|review|reject-review|withdraw-review|check|submit|acknowledge|send-back|cancel)|\/documents\/[0-9a-f-]+(?:\/finalize|\/preference-claim|\/preference-confirm))?)?|inventory\/(?:overview|buckets|bucket-actions|capabilities|policies(?:\/[0-9a-f-]+)?|sellable-policies(?:\/(?:effective|pools(?:\/[^/]+)?(?:\/calculation)?|calculation)|\/[0-9a-f-]+(?:\/(?:versions|retire))?)?|stocktakes(?:\/capabilities|\/[0-9a-f-]+(?:\/lines(?:\/[0-9a-f-]+\/count)?|\/tasks(?:\/[0-9a-f-]+\/assign)?)?)?|expiry(?:\/(?:alerts|policies|allocation|policy(?:\/[0-9a-f-]+)?|warehouses|lots(?:\/[0-9a-f-]+)?))?|lots(?:\/[0-9a-f-]+(?:\/(?:distribution|movements))?)?|ledger(?:\/ref-types|\/[0-9a-f-]+)?|reconciliation(?:\/[0-9a-f-]+(?:\/findings)?)?|distribution(?:\/spus)?|platform-observations|serial-vocabularies|serials(?:\/(?:import|movements|validate-movement|stats)|\/[0-9a-f-]+(?:\/movements)?)?|transfers(?:\/[0-9a-f-]+(?:\/(?:movements|confirm|ship|receive|close))?)?|in-transit(?:\/(?:alerts|netting)|\/[0-9a-f-]+(?:\/(?:arrive|discrepancy))?)?|stocktake-variances(?:\/capabilities|\/sheets\/[0-9a-f-]+\/(?:lines(?:\/[0-9a-f-]+\/attribute)?|report|audit|adjustments)|\/adjustments(?:\/[0-9a-f-]+(?:\/(?:submit|approve|reject|cancel|post))?)?)?|cross-entity-transfers(?:\/[0-9a-f-]+(?:\/(?:movements|settlement|source-confirm|target-confirm|ship|receive|close|cancel))?)?|health(?:\/(?:trend|gate|issues|assess))?|ownership(?:\/(?:capabilities|resolved|report|drift|reference-options|reassign|owners(?:\/[0-9a-f-]+(?:\/status)?)?|rules(?:\/[0-9a-f-]+(?:\/(?:versions|retire))?)?))?|allocation-policies(?:\/(?:capabilities|effective|calculation|runs(?:\/[0-9a-f-]+)?|[0-9a-f-]+(?:\/(?:versions|retire))?)?)?|allocation-sales|consignments(?:\/capabilities|\/[0-9a-f-]+(?:\/(?:lines|stock-view|settlement-window|settlements|returns|audit|activate|suspend|close))?|\/lines\/[0-9a-f-]+\/sales|\/settlements(?:\/[0-9a-f-]+(?:\/confirm)?)?|\/returns(?:\/[0-9a-f-]+(?:\/(?:execute|cancel))?)?)?|vmi(?:\/capabilities|\/agreements(?:\/[0-9a-f-]+(?:\/(?:activate|suspend|close|water-levels|settlement-window|audit|sales|lines(?:\/[0-9a-f-]+\/sales)?|replenishment-signals|snapshots|settlements(?:\/[0-9a-f-]+)?))?)?|\/replenishment-signals(?:\/[0-9a-f-]+(?:\/(?:acknowledge|dispatch|fulfil|cancel))?)?|\/settlements(?:\/[0-9a-f-]+(?:\/confirm)?)?)|outbound-orders(?:\/(?:capabilities|detail-capabilities|pick-requests|print|cancel)|\/[0-9a-f-]+(?:\/transition)?)?|printing(?:\/capabilities|\/templates(?:\/[0-9a-f-]+)?|\/devices|\/reprint-rules|\/targets(?:\/[0-9a-f-]+(?:\/history)?)?|\/jobs(?:\/[0-9a-f-]+(?:\/(?:execute|cancel)|\/items\/[0-9a-f-]+\/content)?)?|\/logs)?|serial-movements|serial-operations|aging(?:\/(?:buckets|slow-moving|policies|policy(?:\/[0-9a-f-]+)?))?|serials(?:\/(?:import|movements|validate-movement|stats)|\/[0-9a-f-]+(?:\/movements)?)?|allocation-priority(?:\/(?:capabilities|calculation|runs|resolutions|floors(?:\/[0-9a-f-]+(?:\/(?:versions|retire))?)?))?)|facilities(?:\/(?:options|capabilities)|\/[0-9a-f-]+(?:\/(?:locations|entrypoints|config(?:\/(?:options|impacts|changes)|\/(?:shifts)(?:\/[0-9a-f-]+)?)?))?)?|location-trees(?:\/capabilities|\/[0-9a-f-]+(?:\/locations(?:\/[0-9a-f-]+)?)?)|location-utilization(?:\/capabilities|\/[0-9a-f-]+(?:\/locations\/[0-9a-f-]+)?)|location-allocations(?:\/(?:capabilities|recommend|list|by-receipt\/[0-9a-f-]+|[0-9a-f-]+(?:\/release)?))?|assembly-orders(?:\/capabilities|\/[0-9a-f-]+(?:\/(?:issue|cancel))?)?|disassembly-orders(?:\/capabilities|\/[0-9a-f-]+(?:\/(?:execute|cancel))?)?|putaway-tasks(?:\/capabilities|\/[0-9a-f-]+(?:\/(?:assign|start|scan|complete|cancel))?)?|pick-tasks(?:\/capabilities|\/[0-9a-f-]+(?:\/(?:assign|picks|exceptions(?:\/[0-9a-f-]+\/resolve)?|complete|reopen|cancel))?)?|pick-waves(?:\/capabilities|\/rules(?:\/[0-9a-f-]+\/retire)?|\/candidates|\/[0-9a-f-]+(?:\/(?:release|complete|cancel)|\/tasks\/[0-9a-f-]+\/detach)?)?|recount-scans(?:\/capabilities|\/rules(?:\/[0-9a-f-]+(?:\/retire)?)?|\/[0-9a-f-]+(?:\/(?:open-box|scans|settle|complete|escalate|cancel)|\/exceptions\/[0-9a-f-]+\/resolve)?)?|packing-orders(?:\/capabilities|\/materials(?:\/[0-9a-f-]+(?:\/retire)?)?|\/[0-9a-f-]+(?:\/completion|\/parcels(?:\/[0-9a-f-]+\/(?:items|measure|label))?|\/complete|\/fail|\/cancel)?)?|shipment-handovers(?:\/capabilities|\/[0-9a-f-]+(?:\/completion|\/waybills|\/confirm|\/cancel)?)?|parcels(?:\/capabilities|\/[0-9a-f-]+))$/,
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
            /^(?:fulfillment\/routing\/(?:capabilities|mcf-authorizations|cost-profiles|stock-snapshots|evaluations|switches|decisions(?:\/[0-9a-f-]+(?:\/status)?)?)|fulfillment\/allocation\/(?:capabilities|rules|warehouses|stock-estimates|shipping-estimates|simulations|holds|allocations(?:\/[0-9a-f-]+(?:\/status)?)?)|fulfillment\/dropship\/(?:capabilities|shop-refs|supplier-refs|supplier-stocks|events|configs(?:\/[0-9a-f-]+(?:\/(?:status|lines))?)?|evaluations)|fulfillment\/dropship-orders\/(?:capabilities|endpoints|events|orders(?:\/[0-9a-f-]+(?:\/(?:confirm-payment|dispatch|supplier-ack|shipment|fail))?)?|alerts(?:\/[0-9a-f-]+\/acknowledge)?)|fulfillment\/pending-shipment\/(?:capabilities|board|units(?:\/[0-9a-f-]+(?:\/(?:sla|cancel))?)?|batch-tasks(?:\/[0-9a-f-]+(?:\/(?:execute|cancel|waybill-acks|labels))?)?|sync)|processing\/(?:capabilities|requirements|orders(?:\/[0-9a-f-]+(?:\/(?:status|progress|components\/issue|bom-binding(?:\/lines)?))?)?|factories(?:\/[0-9a-f-]+)?)|sales-orders(?:\/(?:capabilities|[0-9a-f-]+(?:\/submit)?))?|sales-orders\/list(?:\/(?:capabilities|filters))?|sales-orders\/detail(?:\/(?:capabilities|fulfillment-reference|[0-9a-f-]+))?|fulfillment\/dropship-tracking\/(?:capabilities|policy|events|exceptions\/evaluate|exceptions(?:\/[0-9a-f-]+\/(?:dispose|dismiss))?|dispositions\/[0-9a-f-]+\/status|trackings(?:\/[0-9a-f-]+(?:\/(?:supplier-leg|own-leg))?)?|tasks(?:\/[0-9a-f-]+\/(?:claim|complete))?)|sales-orders\/change\/(?:capabilities|platform-rules(?:\/[0-9a-f-]+)?|fulfillment-progress|requests(?:\/[0-9a-f-]+(?:\/(?:status|writeback-tasks))?)?|cancellations(?:\/[0-9a-f-]+(?:\/(?:status|warehouse-tasks(?:\/[0-9a-f-]+\/status)?|writeback-tasks))?)?|writeback-tasks\/[0-9a-f-]+\/status)|b2b-orders(?:\/(?:capabilities|receivables|customer-terms|price-quote|price-books(?:\/[0-9a-f-]+)?|[0-9a-f-]+(?:\/(?:confirm|shipments|invoice-requests))?))?|fulfillment\/shipment-records\/(?:capabilities|board|performance|events|records(?:\/[0-9a-f-]+(?:\/(?:tracking|visibility|callbacks|status))?)?|sync)|fulfillment\/timeliness\/(?:capabilities|report|dimensions|overdue|events|facts(?:\/[0-9a-f-]+(?:\/(?:promise|delivery))?)?|sync)|logistics\/waybills\/(?:capabilities|filter-options|list|actions\/(?:apply|cancel|print)|[0-9a-f-]+(?:\/freight)?)|logistics\/waybill-applications(?:\/(?:capabilities|carriers|batches|queue|requests(?:\/dispatch|\/[0-9a-f-]+(?:\/(?:retry|cancel))?)?))?|logistics\/waybill-address-change\/(?:capabilities|rules|notifications|redirects(?:\/[0-9a-f-]+(?:\/submit)?)?|cancels(?:\/[0-9a-f-]+(?:\/submit)?)?|precheck)|fulfillment\/dropship-margin\/(?:capabilities|fee-rates|fx-rates|cycles|events|facts(?:\/evaluate)?|report|alerts(?:\/evaluate)?|alerts\/[0-9a-f-]+\/(?:acknowledge|dismiss)|reconciliations(?:\/[0-9a-f-]+(?:\/status)?)?|tasks(?:\/[0-9a-f-]+\/(?:claim|complete))?)|dealers\/(?:capabilities|candidates|profiles|quota-attainment|sales|authorizations(?:\/[0-9a-f-]+(?:\/status)?)?|quotas|rebates(?:\/accrue)?|diversion-alerts(?:\/[0-9a-f-]+\/state)?))$/,
    // 裸路径 fulfillment/routing、fulfillment/allocation 与 processing **刻意不放行**：
    // 后端没有对应映射，放行它会让界面以为「一条不存在的列表接口」可用。
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
