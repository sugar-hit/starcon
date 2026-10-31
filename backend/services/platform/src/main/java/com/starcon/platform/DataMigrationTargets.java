package com.starcon.platform;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 18.1.3.2「数据迁移」的**目标内部模型目录**。
 *
 * <p>蓝图原文：「映射配置（<b>外部字段 → 内部模型</b>）」「<b>关系</b>：上游 = 18.1.3.1；下游 = 各业务域。」
 *
 * <p>🔴 <b>「各业务域」不等于「所有业务域都能迁」</b>。本目录只收录
 * <b>有真实写入 / 删除对</b>的域 —— 没有 create/delete 对的域，
 * 「失败回滚」就只能是假的（页面上写着「已回滚」，目标库里的数据其实还在）。
 * 那些域在能力声明里<b>如实标 {@code available=false}</b>，并写明缺什么、归谁。
 *
 * <p>🔴 <b>字段清单只有一份，就在这里</b>：映射校验、迁移执行、页面上的
 * 「目标模型」下拉**共用**这一份。两处各写一份字段名 ⇒ 迟早对不上，
 * 而对不上的后果是「页面说能迁，跑起来报「未知字段」」。
 *
 * <p>⚠️ <b>状态字段不在清单里</b>：catalog 新建商品强制草稿
 * （{@code 新商品先保存为草稿，补齐 SKU 后再提交待上架}），
 * 迁移落库的记录一律是草稿 —— 外部系统里的「在售」直接写进去会被 catalog 拒。
 * ⇒ 状态由模块决定，**不开放给映射**。
 */
public final class DataMigrationTargets {

  private DataMigrationTargets() {}

  /**
   * 一个内部模型的字段。
   *
   * <p>🔴 <b>名字带模块前缀</b>：springdoc 用 record 的**简单名**当 schema 名，
   * 而这份契约是从 {@code packages/api-contracts} 的桶文件导出的（全局可见）——
   * 叫 {@code Field} 的话，桶文件里就多一个谁都能 import 的 {@code Field}，
   * 与别的模块的同名类型迟早撞车。叫 {@code DataMigrationTargetField} 才不会。
   *
   * @param key 字段键（程序内稳定标识）
   * @param label 中文名
   * @param required 是否必填（源文件缺这一列 / 值为空 ⇒ 该行失败）
   * @param defaultValue 源值为空时的兜底值（只对非必填字段有意义）
   * @param help 填写说明
   */
  public record DataMigrationTargetField(
      String key, String label, boolean required, String defaultValue, String help) {}

  /**
   * 一个可迁移的内部模型。
   *
   * @param code 稳定标识
   * @param label 中文名
   * @param ownerService 属主服务（迁移要由属主服务写）
   * @param ownerModule 归属模块
   * @param fields 字段清单
   * @param limitation 接通的边界（写明哪些没做、为什么）
   */
  public record Model(
      String code,
      String label,
      String ownerService,
      String ownerModule,
      List<DataMigrationTargetField> fields,
      String limitation) {}

  private static DataMigrationTargetField required(String key, String label, String help) {
    return new DataMigrationTargetField(key, label, true, "", help);
  }

  private static DataMigrationTargetField optional(String key, String label, String defaultValue, String help) {
    return new DataMigrationTargetField(key, label, false, defaultValue, help);
  }

  // ================================================================ 目录本体

  /**
   * 全部可迁移的目标模型。
   *
   * <p>🔴 <b>顺序即分组</b>：先 catalog 的两个（SPU / SKU），再是
   * <b>如实标未接通</b>的那些（能力声明里逐条写明缺什么、归谁）。
   */
  public static final List<Model> ALL =
      List.of(
          new Model(
              "SPU",
              "商品 SPU",
              "catalog",
              "3.1.2",
              List.of(
                  required("code", "商品编码", "catalog 的编码规则：字母数字 / 下划线 / 短横线，最长 64 位"),
                  required("name", "商品名称", "最长 200 位"),
                  optional("description", "商品描述", "", "最长 10000 位；留空写空串（catalog 不接受 null）"),
                  optional("brand", "品牌", "", "最长 100 位"),
                  required("category", "类目", "最长 100 位")),
              "落库的 SPU 一律是**草稿**：catalog 的新建规则要求先存草稿、补齐 SKU 再提交待上架。"
                  + "外部系统里的「在售」直接写进去会被 catalog 拒 —— 迁移不绕过这条规则，"
                  + "上架要走商品页自己的生命周期。"),
          new Model(
              "SKU",
              "商品 SKU",
              "catalog",
              "3.1.3",
              List.of(
                  required("code", "SKU 编码", "catalog 的编码规则：字母数字 / 下划线 / 短横线，最长 64 位"),
                  required("name", "SKU 名称", "最长 200 位"),
                  required("barcode", "条码", "最长 128 位"),
                  required("baseUnit", "基本单位", "最长 32 位，例如「件」"),
                  required("spuCode", "所属商品编码", "指向**已存在**的 SPU；本模块不自动建父商品")),
              "落库的 SKU 一律是**草稿**（与 SPU 同理）。"
                  + "⚠️ **属性值不在本模块范围内**：属性是 SPU 级的定义（catalog 的属性字典），"
                  + "迁移属性值要先重建属性字典再逐 SKU 覆写 —— 那是另一件事，本模块不假装做了。"),
          new Model(
              "ORDER",
              "销售订单",
              "trade",
              "9.1",
              List.of(),
              "trade 没有面向迁移的写入端点，而且订单不是可以「导进去」的数据 —— "
                  + "下单要占库存、要过信用校验、要触发平台回传。"
                  + "历史订单的补录走 9.x 自己的处置流程，不在本模块范围内。"),
          new Model(
              "INVENTORY",
              "库存头寸",
              "warehouse",
              "7.1",
              List.of(),
              "库存是**由单据推导出来的余量**（入库 / 出库 / 调拨 / 盘点），不是可以「导进去」的数据 —— "
                  + "直接写库存会把账实关系写坏，让后续单据算出的可用量全错。"),
          new Model(
              "VOUCHER",
              "会计凭证",
              "finance",
              "12.5.2.1",
              List.of(),
              "凭证是**会计事实**：导入一份凭证等于凭空记账，试算平衡表会与银行流水对不上。"
                  + "finance 侧没有面向迁移的凭证写入端点。"));

  // ================================================================ 查找

  private static final Map<String, Model> BY_CODE = index();

  private static Map<String, Model> index() {
    var map = new LinkedHashMap<String, Model>();
    for (var m : ALL) map.put(m.code(), m);
    return Map.copyOf(map);
  }

  /**
   * 取一个目标模型。
   *
   * @throws org.springframework.web.server.ResponseStatusException 400（模型不存在）
   */
  public static Model require(String code) {
    if (code == null) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "目标模型不能为空");
    }
    var model = BY_CODE.get(code.trim().toUpperCase(Locale.ROOT));
    if (model == null) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "未知的目标模型「" + code + "」（可用：" + BY_CODE.keySet() + "）");
    }
    return model;
  }

  /** 这个模型**真的接通**了吗（有没有 create/delete 对）。 */
  public static boolean available(String code) {
    if (code == null) return false;
    var model = BY_CODE.get(code.trim().toUpperCase(Locale.ROOT));
    return model != null && !model.fields().isEmpty();
  }

  /** 模型的字段表（key → field）。 */
  public static Map<String, DataMigrationTargetField> fieldsOf(String code) {
    var model = require(code);
    var map = new LinkedHashMap<String, DataMigrationTargetField>();
    for (var f : model.fields()) map.put(f.key(), f);
    return map;
  }

  /** 这个模型里**必填**的字段键。 */
  public static List<String> requiredFields(String code) {
    return require(code).fields().stream().filter(DataMigrationTargetField::required).map(DataMigrationTargetField::key).toList();
  }
}
