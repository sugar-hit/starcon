package com.starcon.warehouse;

import static com.starcon.warehouse.LabelPrintModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 8.3.4.2 面单与标签打印。
 *
 * <p>蓝图：「打印面单、装箱单、SKU 条码、库位标签、箱唛，支持批量打印、模板配置、打印次数记录 （平台对重复打印有要求）。」输出：「打印任务记录 + 打印内容（PDF/指令流）；打印日志。」
 *
 * <p>命名空间 {@code /warehouse/inventory/printing/...}，权限码 {@code warehouse.inventory.read} / {@code
 * .create} / {@code .update} / {@code .delete}。 刻意独立成 {@code LabelPrintingController} 而不是并进 {@code
 * InventoryController}： 那是 7.1.x 的文件，并行车道各改各的才不会撞。
 *
 * <p><b>下载端点吐的是落库的渲染内容，不是现算的</b>：{@code /jobs/{jobId}/items/{itemId}/content}
 * 返回打印那一刻的字节并附上校验和，所以「日志里的凭据」与「下载到的内容」永远一致。
 */
@RestController
@RequestMapping("/warehouse")
public class LabelPrintingController {

  private final AccessService access;
  private final LabelPrintingService printing;

  public LabelPrintingController(AccessService access, LabelPrintingService printing) {
    this.access = access;
    this.printing = printing;
  }

  /** 统一入口：身份与权限由 identity 解析（租户**不**来自任何客户端头），再开事务绑定 RLS 租户。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 能力

  /** 能力声明：五种打印对象的渲染形态与必填字段、重复打印判据的三段区间与强制重打口径， 以及「面单数据来自调用方」这一事实。 */
  @GetMapping("/inventory/printing/capabilities")
  public LabelPrintCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> printing.capabilities());
  }

  // ------------------------------------------------------------ 打印机配置

  @GetMapping("/inventory/printing/devices")
  public List<LabelPrintDevice> devices(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "true") boolean activeOnly) {
    return call(auth, "inventory.read", a -> printing.devices(activeOnly));
  }

  @PostMapping("/inventory/printing/devices")
  public LabelPrintDevice createDevice(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody LabelPrintDeviceRequest request) {
    return call(auth, "inventory.create", a -> printing.createDevice(request));
  }

  @PutMapping("/inventory/printing/devices/{deviceId}")
  public LabelPrintDevice updateDevice(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID deviceId,
      @Valid @RequestBody LabelPrintDeviceRequest request) {
    return call(auth, "inventory.update", a -> printing.updateDevice(deviceId, request));
  }

  // ------------------------------------------------------------ 模板

  @GetMapping("/inventory/printing/templates")
  public LabelPrintTemplatePage templates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String objectType,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "inventory.read", a -> printing.templates(objectType, keyword, page, size));
  }

  @GetMapping("/inventory/printing/templates/{templateId}")
  public LabelPrintTemplate template(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID templateId) {
    return call(auth, "inventory.read", a -> printing.templateById(templateId));
  }

  @PostMapping("/inventory/printing/templates")
  public LabelPrintTemplate createTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody LabelPrintTemplateRequest request) {
    return call(auth, "inventory.create", a -> printing.createTemplate(request));
  }

  @PutMapping("/inventory/printing/templates/{templateId}")
  public LabelPrintTemplate updateTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID templateId,
      @Valid @RequestBody LabelPrintTemplateRequest request) {
    return call(auth, "inventory.update", a -> printing.updateTemplate(templateId, request));
  }

  // ------------------------------------------------------------ 打印对象（快照）

  /**
   * 打印对象列表，行上带<b>「下一次打印的判定」</b>。
   *
   * <p>判定前置到列表：现场最需要的判断是「现在按打印会不会超限」，等点了打印才被拒， 标签纸已经进机器了。
   */
  @GetMapping("/inventory/printing/targets")
  public LabelPrintTargetPage targets(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String objectType,
      @RequestParam(required = false) String externalRef,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Integer minPrintCount,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a -> printing.targets(objectType, externalRef, keyword, minPrintCount, page, size));
  }

  @GetMapping("/inventory/printing/targets/{targetId}")
  public LabelPrintTarget target(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID targetId) {
    return call(auth, "inventory.read", a -> printing.targetById(targetId));
  }

  /** 登记打印对象（面单数据入口）。重复的 external_ref 返回已有记录，不报错（上游重推很常见）。 */
  @PostMapping("/inventory/printing/targets")
  public LabelPrintTarget registerTarget(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody LabelPrintTargetRequest request) {
    return call(auth, "inventory.create", a -> printing.registerTarget(request));
  }

  /** 某对象的完整打印历史：每一次是第几次、有没有告警、是不是强制放行。 */
  @GetMapping("/inventory/printing/targets/{targetId}/history")
  public List<LabelPrintLog> targetHistory(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID targetId) {
    return call(auth, "inventory.read", a -> printing.targetHistory(targetId));
  }

  // ------------------------------------------------------------ 重复打印规则

  @GetMapping("/inventory/printing/reprint-rules")
  public List<LabelReprintRule> rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> printing.rules());
  }

  @PostMapping("/inventory/printing/reprint-rules")
  public LabelReprintRule upsertRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody LabelReprintRuleRequest request) {
    return call(auth, "inventory.update", a -> printing.upsertRule(request));
  }

  // ------------------------------------------------------------ 打印任务

  @GetMapping("/inventory/printing/jobs")
  public LabelPrintJobPage jobs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String objectType,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth, "inventory.read", a -> printing.jobs(status, objectType, keyword, page, size));
  }

  /**
   * 建打印任务（批量：一次给多个对象）。
   *
   * <p>建单时就判一次重复打印并把结论落在任务上；超限且未强制重打则<b>整单被拒</b>， 并逐条列出是哪些对象超了限。
   */
  @PostMapping("/inventory/printing/jobs")
  public LabelPrintJob createJob(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody LabelPrintJobRequest request) {
    return call(auth, "inventory.create", a -> printing.createJob(request, a.userId()));
  }

  @GetMapping("/inventory/printing/jobs/{jobId}")
  public LabelPrintJobDetail jobDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID jobId) {
    return call(auth, "inventory.read", a -> printing.jobDetail(jobId));
  }

  /**
   * 执行打印：真正累加权威次数并落打印凭据。
   *
   * <p>返回的不只是任务状态，还有逐份的打印凭据（{@code printSequence} 是第几次）与 打印后的权威次数 —— 界面据此说清「第几次、告警了几条」，而不是只回一句「成功」。
   */
  @PostMapping("/inventory/printing/jobs/{jobId}/execute")
  public LabelPrintExecution executeJob(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID jobId) {
    return call(auth, "inventory.create", a -> printing.executeJob(jobId, a.userId()));
  }

  @PostMapping("/inventory/printing/jobs/{jobId}/cancel")
  public LabelPrintJob cancelJob(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID jobId) {
    return call(auth, "inventory.update", a -> printing.cancelJob(jobId));
  }

  /**
   * 下载打印内容：按落库的 {@code content_format} 原样吐字节。
   *
   * <p>ZPL → {@code text/vnd.zpl}（热敏机直接吃指令流）；HTML → {@code text/html}
   * （浏览器排版打印）。<b>不是</b>现算的：返回的就是当初打印时落库并校验过的那一份， 所以日志里的凭据永远指向下载到的内容。
   */
  @GetMapping("/inventory/printing/jobs/{jobId}/items/{itemId}/content")
  public ResponseEntity<byte[]> content(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID jobId,
      @PathVariable UUID itemId,
      @RequestParam(required = false) PrintContentFormat format) {
    return call(
        auth,
        "inventory.read",
        a -> {
          var actual = printing.itemFormat(jobId, itemId);
          var rendered = printing.renderedContent(jobId, itemId, format);
          var bytes = rendered.getBytes(java.nio.charset.StandardCharsets.UTF_8);
          var headers = new HttpHeaders();
          headers.setContentType(MediaType.parseMediaType(actual.contentType()));
          headers.setContentDisposition(
              ContentDisposition.attachment()
                  .filename("label-" + itemId + "." + actual.extension())
                  .build());
          headers.setCacheControl("no-store");
          return ResponseEntity.ok().headers(headers).body(bytes);
        });
  }

  // ------------------------------------------------------------ 打印日志

  @GetMapping("/inventory/printing/logs")
  public LabelPrintLogPage logs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID jobId,
      @RequestParam(required = false) UUID targetId,
      @RequestParam(required = false) Boolean warningOnly,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth, "inventory.read", a -> printing.logs(jobId, targetId, warningOnly, page, size));
  }
}
