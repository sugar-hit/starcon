package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;

import com.starcon.platform.NotificationTemplateModels.NotifyTemplateRenderResult;
import com.starcon.platform.NotificationTemplateModels.NotifyTemplateVariableDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 18.5.1.1 的**渲染引擎**单元测试。
 *
 * <p>🔴 这一组断言守着蓝图里最要紧的那句：「**缺变量 / 多余变量 / 未知变量 / 模板语法错误
 * 都必须明确报错（报出缺哪个变量名），绝不渲染成空串或残留 {@code {{xxx}}} 占位符**」。
 *
 * <p>纯规则、不碰数据库 —— 渲染引擎本来就**不依赖任何外部系统**。
 */
class NotificationTemplateRendererTest {

  private static final List<NotifyTemplateVariableDefinition> TWO =
      List.of(
          new NotifyTemplateVariableDefinition("buyer_name", "TEXT", true, null, "买家昵称"),
          new NotifyTemplateVariableDefinition("order_no", "TEXT", true, null, "订单号"));

  @Test
  void 缺变量时点名缺哪个且不返回正文() {
    var result =
        NotificationTemplateRenderer.render(
            "",
            "买家 {{buyer_name}} 的订单 {{order_no}} 已发货",
            TWO,
            Map.of("buyer_name", "小明"),
            null,
            null,
            null,
            null,
            null);

    assertThat(result.ok()).isFalse();
    // 🔴 渲染失败时正文是 **null**：绝不是空串，也绝不是残留 {{xxx}} 的半成品。
    assertThat(result.renderedBody()).isNull();
    assertThat(result.renderedSubject()).isNull();
    var missing = error(result, "MISSING_VARIABLE");
    assertThat(missing.variables()).containsExactly("order_no");
    assertThat(missing.message()).contains("order_no");
  }

  @Test
  void 未知变量时点名是哪个() {
    var result =
        NotificationTemplateRenderer.render(
            "",
            "你好 {{nickname}}",
            List.of(new NotifyTemplateVariableDefinition("buyer_name", "TEXT", true, null, "")),
            Map.of("buyer_name", "小明"),
            null,
            null,
            null,
            null,
            null);

    assertThat(result.ok()).isFalse();
    var unknown = error(result, "UNKNOWN_VARIABLE");
    assertThat(unknown.variables()).containsExactly("nickname");
    assertThat(result.renderedBody()).isNull();
  }

  @Test
  void 多余变量时点名是哪个() {
    var result =
        NotificationTemplateRenderer.render(
            "",
            "你好 {{buyer_name}}",
            List.of(new NotifyTemplateVariableDefinition("buyer_name", "TEXT", true, null, "")),
            Map.of("buyer_name", "小明", "leftover", "多余"),
            null,
            null,
            null,
            null,
            null);

    assertThat(result.ok()).isFalse();
    assertThat(error(result, "EXTRA_VARIABLE").variables()).containsExactly("leftover");
  }

  @Test
  void 占位符没闭合是语法错误() {
    var result =
        NotificationTemplateRenderer.render(
            "",
            "你好 {{buyer_name",
            List.of(new NotifyTemplateVariableDefinition("buyer_name", "TEXT", true, null, "")),
            Map.of("buyer_name", "小明"),
            null,
            null,
            null,
            null,
            null);

    assertThat(result.ok()).isFalse();
    assertThat(error(result, "SYNTAX_ERROR").message()).contains("没有闭合");
    assertThat(result.renderedBody()).isNull();
  }

  @Test
  void 落单的右括号是语法错误() {
    var result =
        NotificationTemplateRenderer.render(
            "",
            "你好 }} 买家",
            List.of(),
            Map.of(),
            null,
            null,
            null,
            null,
            null);

    assertThat(result.ok()).isFalse();
    assertThat(error(result, "SYNTAX_ERROR").message()).contains("落单");
  }

  @Test
  void 变量名非法是语法错误() {
    var result =
        NotificationTemplateRenderer.render(
            "",
            "你好 {{买家昵称}}",
            List.of(),
            Map.of(),
            null,
            null,
            null,
            null,
            null);

    assertThat(result.ok()).isFalse();
    assertThat(error(result, "SYNTAX_ERROR").message()).contains("变量名非法");
  }

  @Test
  void 取值与类型不符报错() {
    var result =
        NotificationTemplateRenderer.render(
            "",
            "金额 {{amount}}",
            List.of(new NotifyTemplateVariableDefinition("amount", "NUMBER", true, null, "")),
            Map.of("amount", "十二块"),
            null,
            null,
            null,
            null,
            null);

    assertThat(result.ok()).isFalse();
    assertThat(error(result, "INVALID_VALUE").variables()).containsExactly("amount（NUMBER 必须是数字，实际是「十二块」）");
  }

  @Test
  void 变量定义本身写错要报错() {
    // 重名。
    var duplicated =
        NotificationTemplateRenderer.validateDeclarations(
            List.of(
                new NotifyTemplateVariableDefinition("a", "TEXT", true, null, ""),
                new NotifyTemplateVariableDefinition("a", "TEXT", true, null, "")));
    assertThat(duplicated).isNotEmpty();
    assertThat(duplicated.get(0).code()).isEqualTo("INVALID_DECLARATION");

    // 必填又带默认值。
    var both =
        NotificationTemplateRenderer.validateDeclarations(
            List.of(new NotifyTemplateVariableDefinition("a", "TEXT", true, "默认值", "")));
    assertThat(both).isNotEmpty();
    assertThat(both.get(0).message()).contains("必填");

    // 类型非法。
    var badType =
        NotificationTemplateRenderer.validateDeclarations(
            List.of(new NotifyTemplateVariableDefinition("a", "WHATEVER", true, null, "")));
    assertThat(badType).isNotEmpty();
    assertThat(badType.get(0).message()).contains("TEXT / NUMBER / DATE / CURRENCY");
  }

  @Test
  void 渲染成功时正文里不留任何占位符() {
    var result =
        NotificationTemplateRenderer.render(
            "发货通知",
            "买家 {{buyer_name}} 的订单 {{order_no}} 已发货，金额 {{amount}}",
            List.of(
                new NotifyTemplateVariableDefinition("buyer_name", "TEXT", true, null, ""),
                new NotifyTemplateVariableDefinition("order_no", "TEXT", true, null, ""),
                new NotifyTemplateVariableDefinition("amount", "CURRENCY", false, "USD 0.00", "")),
            Map.of("buyer_name", "小明", "order_no", "SO-1"),
            null,
            null,
            null,
            null,
            null);

    assertThat(result.ok()).isTrue();
    assertThat(result.errors()).isEmpty();
    assertThat(result.renderedBody())
        .isEqualTo("买家 小明 的订单 SO-1 已发货，金额 USD 0.00");
    assertThat(result.renderedSubject()).isEqualTo("发货通知");
    assertThat(result.renderedBody()).doesNotContain("{{").doesNotContain("}}");
  }

  @Test
  void 可选变量用默认值兜底() {
    var result =
        NotificationTemplateRenderer.render(
            "",
            "买家 {{buyer_name}}，备注 {{remark}}",
            List.of(
                new NotifyTemplateVariableDefinition("buyer_name", "TEXT", true, null, ""),
                new NotifyTemplateVariableDefinition("remark", "TEXT", false, "无", "")),
            Map.of("buyer_name", "小明"),
            null,
            null,
            null,
            null,
            null);

    assertThat(result.ok()).isTrue();
    assertThat(result.renderedBody()).isEqualTo("买家 小明，备注 无");
  }

  @Test
  void 可选变量既没值也没默认值照样报错() {
    // 🔴 「声明了就要绑定」：不绑就会在正文里留下 {{xxx}} —— 那是要发给买家的正文。
    var result =
        NotificationTemplateRenderer.render(
            "",
            "买家 {{buyer_name}}，备注 {{remark}}",
            List.of(
                new NotifyTemplateVariableDefinition("buyer_name", "TEXT", true, null, ""),
                new NotifyTemplateVariableDefinition("remark", "TEXT", false, null, "")),
            Map.of("buyer_name", "小明"),
            null,
            null,
            null,
            null,
            null);

    assertThat(result.ok()).isFalse();
    assertThat(error(result, "MISSING_VARIABLE").variables()).containsExactly("remark");
  }

  @Test
  void 占位符名字能提取出来() {
    assertThat(
            NotificationTemplateRenderer.placeholderNames("标题 {{a}}", "正文 {{b}} {{a}} {{c}}"))
        .containsExactly("a", "b", "c");
  }

  private static NotificationTemplateModels.NotifyTemplateRenderError error(
      NotifyTemplateRenderResult result, String code) {
    return result.errors().stream()
        .filter(e -> code.equals(e.code()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("没有 " + code + " 错误：" + result.errors()));
  }
}
