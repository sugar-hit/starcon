package com.starcon.catalog;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrorHandler {
  @ExceptionHandler({
    org.jooq.exception.DataAccessException.class,
    org.springframework.dao.DataAccessException.class
  })
  ResponseEntity<ProblemDetail> database(Exception e) {
    return ResponseEntity.status(409)
        .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "编码或规格组合重复，或记录仍被引用。请刷新后检查输入。"));
  }

  @ExceptionHandler({IllegalArgumentException.class, java.time.format.DateTimeParseException.class})
  ResponseEntity<ProblemDetail> invalid(Exception e) {
    return ResponseEntity.badRequest()
        .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "字段格式不正确，请检查类型、日期和必填项"));
  }

  /**
   * 3.1.3.2 组合唯一性校验未通过。
   *
   * <p>单独一个 handler：蓝图要求输出是**结构化冲突清单**，而通用 409 只给一句"编码或规格组合重复"—— 那样运营没法按条改。把 {@code conflicts} 挂在
   * ProblemDetail 上退回前端， 界面才能逐条显示「撞了谁、出现在第几条」。
   */
  @ExceptionHandler(VariantCombinationService.CombinationConflictException.class)
  ResponseEntity<ProblemDetail> combinationConflict(
      VariantCombinationService.CombinationConflictException e) {
    var result = e.result();
    var body =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT, "组合校验未通过：存在 " + result.conflictCount() + " 条冲突，草稿未生成");
    body.setProperty("conflicts", result.conflicts());
    body.setProperty("submittedCount", result.submittedCount());
    body.setProperty("plannedCount", result.plannedCount());
    body.setProperty("conflictCount", result.conflictCount());
    return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
  }

  /**
   * 3.2.2.2 类目必填校验未通过。
   *
   * <p>蓝图要求输出是**缺失必填项时阻止保存**，并且要把缺失项说清楚：只回一句"必填未填" 的话，运营在有二十条属性的 SKU 上没法知道该去补哪几条。所以把 {@code
   * missing} 清单 挂在 ProblemDetail 上，界面逐条显示「缺哪条 / 为什么算缺」—— 其中「被显式清空」与「从未设置」必须分开说，因为这两者的下一步动作不一样：
   * 前者要先取消类目必填，后者直接填值。
   *
   * <p>状态码用 400 而不是 409：请求本身合法，是当前商品档案不满足类目要求， 换成"冲突"会让人以为是并发写坏了。
   */
  @ExceptionHandler(SkuAttributeOverrideService.RequiredMissingException.class)
  ResponseEntity<ProblemDetail> requiredMissing(
      SkuAttributeOverrideService.RequiredMissingException e) {
    var body =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            "该商品所属类目要求的必填属性没有生效值，本次修改未保存（已回滚）：" + "共 " + e.missing().size() + " 项");
    body.setProperty("missing", e.missing());
    body.setProperty("missingCount", e.missing().size());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
  }

  /**
   * 3.6.2.1 供应商报价优先级歧义（规则 **R2**）。
   *
   * <p>蓝图点名的那条规则：**同一优先级多条有效报价必须报错让用户选，不能随机取一条**。 「报错让用户选」的后半句要求错误里**带得出候选** —— 只回一句"存在多条有效报价"
   * 的话，运营不知道该去改哪几条的优先级，界面就只能显示一个红条然后让他自己去猜。
   *
   * <p>所以把并列候选的清单挂在 ProblemDetail 上（与 3.1.3.2 的 {@code conflicts} 同一手法）： 每条带
   * id、供应商、SKU、货号、币种、单价档数，界面逐条显示并给出「把这条设为首选」的入口。
   */
  @ExceptionHandler(SupplierQuoteService.SupplierQuoteAmbiguityException.class)
  ResponseEntity<ProblemDetail> quoteAmbiguity(
      SupplierQuoteService.SupplierQuoteAmbiguityException e) {
    var result = e.result();
    var candidates =
        result.candidates().stream()
            .filter(q -> "AMBIGUOUS".equals(q.selectionState()))
            .map(
                q ->
                    java.util.Map.of(
                        "id", q.id().toString(),
                        "priority", q.priority(),
                        "supplierCode", q.supplierCode(),
                        "supplierName", q.supplierName(),
                        "skuCode", q.skuCode(),
                        "supplierSkuCode", q.supplierSkuCode(),
                        "currency", q.currency(),
                        "validityLabel", q.validityLabel(),
                        "tierCount", q.tiers().size()))
            .toList();
    var body = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    body.setProperty("ambiguous", true);
    body.setProperty("candidates", candidates);
    body.setProperty("ambiguityProblem", result.ambiguityProblem());
    body.setProperty("candidatesSkuId", result.skuId());
    return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
  }

  /**
   * 3.9.2.2 僵尸主数据批量停用被拒（规则 **Z4**）。
   *
   * <p>批量停用**整批拒收**而不是逐条跳过：跳过会让运营以为这一批都处理完了， 而被跳过的那几条不会有人回来处理。所以 body 里带 {@code blocked} 清单，
   * 界面逐条显示「哪一条、为什么不算僵尸」。
   *
   * <p>「无法判定」必须能和「在用」分开显示 —— 前者的下一步动作是去接上游事件或刷新快照， 后者是"这条有人在用，别动"。混成一句话的话，运营只会当成"系统不让我停"。
   */
  @ExceptionHandler(ZombieCandidateService.ZombieBatchRejectedException.class)
  ResponseEntity<ProblemDetail> zombieBatchRejected(
      ZombieCandidateService.ZombieBatchRejectedException e) {
    var body = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    body.setProperty("blocked", e.blocked());
    body.setProperty("blockedCount", e.blocked().size());
    return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
  }
}
