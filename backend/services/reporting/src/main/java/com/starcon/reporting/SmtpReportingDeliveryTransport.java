package com.starcon.reporting;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 邮件投递的**真实 SMTP 客户端**（13.6.2.1 的 EMAIL 通道）。
 *
 * <p>配置全部来自环境变量：
 *
 * <ul>
 *   <li>{@code REPORTING_SMTP_HOST} —— 必填；**没配就 configured()=false**，投递会记 FAILED；</li>
 *   <li>{@code REPORTING_SMTP_PORT} —— 默认 587；</li>
 *   <li>{@code REPORTING_SMTP_USERNAME} / {@code REPORTING_SMTP_PASSWORD} —— 可选，配了就走 AUTH LOGIN；</li>
 *   <li>{@code REPORTING_SMTP_FROM} —— 默认 {@code starcon@localhost}；</li>
 *   <li>{@code REPORTING_SMTP_STARTTLS} —— 默认 true（连上后先 STARTTLS 再 EHLO）。</li>
 * </ul>
 *
 * <p>🔴 **这是一个真的 SMTP 对话**（EHLO / STARTTLS / AUTH / MAIL FROM / RCPT TO / DATA / QUIT），
 * 不是「打个 HTTP 就算发了」。它会把报表结果作为 **base64 附件**发出去
 * （base64 避免了行首点号与本地编码两个坑，收件方拿到的是与导出文件同源的字节）。
 *
 * <p>🔴 **没配置 ⇒ 不发，也不标成功**：{@link #configured()} 返回 false 时，
 * 调用方必须把投递记成 FAILED 并写明「未配置 SMTP」。
 */
@Component
public class SmtpReportingDeliveryTransport implements ReportingDeliveryTransport {

  private static final Logger log = LoggerFactory.getLogger(SmtpReportingDeliveryTransport.class);

  /** SMTP 的标准超时（毫秒）。 */
  private static final int CONNECT_TIMEOUT_MS = 10_000;

  /** 读写超时（毫秒）。 */
  private static final int READ_TIMEOUT_MS = 30_000;

  /** 附件的 MIME 类型。 */
  private static final String ATTACHMENT_TYPE = "text/csv";

  private final Environment env;

  public SmtpReportingDeliveryTransport(Environment env) {
    this.env = env;
  }

  /**
   * 读配置（**每次投递时读**，不在构造时读）。
   *
   * <p>🔴 这样运维改完环境变量/配置后**不用重启**就能生效，
   * 集成测试也能逐例翻转配置（配了 SMTP 的那一条与没配的那一条要在同一个上下文里跑）。
   */
  private String host() {
    return prop("REPORTING_SMTP_HOST", "reporting.smtp.host", "");
  }

  /**
   * 读一个配置项（**两种写法都试**）。
   *
   * <p>⚠️ 环境变量名（{@code REPORTING_SMTP_HOST}）与 Spring 的宽松写法
   * （{@code reporting.smtp.host}）在不同的 PropertySource 下命中方式不同，
   * 两个都试一遍 ⇒ 容器注入、{@code @TestPropertyValues}、系统属性三种来源都能生效。
   */
  private String prop(String envName, String relaxedName, String fallback) {
    var value = env.getProperty(envName);
    if (value == null || value.isBlank()) value = env.getProperty(relaxedName);
    return value == null ? fallback : value;
  }

  private int port() {
    var value = prop("REPORTING_SMTP_PORT", "reporting.smtp.port", "587");
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      return 587;
    }
  }

  private String username() {
    return prop("REPORTING_SMTP_USERNAME", "reporting.smtp.username", "");
  }

  private String password() {
    return prop("REPORTING_SMTP_PASSWORD", "reporting.smtp.password", "");
  }

  private String from() {
    var value = prop("REPORTING_SMTP_FROM", "reporting.smtp.from", "starcon@localhost");
    return value == null || value.isBlank() ? "starcon@localhost" : value.trim();
  }

  private boolean startTls() {
    var value = prop("REPORTING_SMTP_STARTTLS", "reporting.smtp.starttls", "true");
    return !"false".equalsIgnoreCase(value.trim());
  }

  @Override
  public String channel() {
    return "EMAIL";
  }

  @Override
  public boolean configured() {
    return !host().isEmpty();
  }

  /** 没配时写清缺什么（页面与台账上照着显示）。 */
  public String unconfiguredReason() {
    return "未配置邮件通道（REPORTING_SMTP_HOST 为空）—— 本次投递没有发出，"
        + "请配置 SMTP 后重试。";
  }

  @Override
  public Outcome send(Message message) throws IOException {
    if (!configured()) {
      return Outcome.failed(unconfiguredReason());
    }
    var session = new SmtpSession(host(), port(), startTls());
    session.open();
    try {
      session.expect(220);
      session.command("EHLO starcon", 250);
      if (startTls()) {
        session.command("STARTTLS", 220);
        session.startTls(host(), port());
        session.command("EHLO starcon", 250);
      }
      if (!username().isEmpty()) {
        session.command("AUTH LOGIN", 334);
        session.command(base64(username()), 334);
        session.command(base64(password()), 235);
      }
      session.command("MAIL FROM:<" + from() + ">", 250);
      session.command("RCPT TO:<" + message.target() + ">", 250);
      session.command("DATA", 354);
      session.write(buildMime(message));
      session.write("\r\n.\r\n");
      session.expect(250);
      try {
        session.command("QUIT", 221);
      } catch (IOException e) {
        // 邮件已经被对方收下（DATA 之后的 250），QUIT 失败不影响投递结果。
        log.debug("SMTP QUIT 失败（不影响已送达的邮件）：{}", e.toString());
      }
      return Outcome.ok("邮件已通过 SMTP 发出（" + host() + ":" + port() + "）。");
    } finally {
      session.close();
    }
  }

  /** 拼一封 MIME 邮件（正文与附件都走 base64）。 */
  private String buildMime(Message message) {
    var boundary = "----starcon-" + java.util.UUID.randomUUID().toString().replace("-", "");
    var out = new StringBuilder();
    out.append("From: ").append(from()).append("\r\n");
    out.append("To: ").append(message.target()).append("\r\n");
    out.append("Subject: ").append(encodedSubject(message.subject())).append("\r\n");
    out.append("MIME-Version: 1.0\r\n");
    out.append("Content-Type: multipart/mixed; boundary=\"").append(boundary).append("\"\r\n");
    out.append("\r\n");
    out.append("--").append(boundary).append("\r\n");
    out.append("Content-Type: text/plain; charset=UTF-8\r\n");
    out.append("Content-Transfer-Encoding: base64\r\n");
    out.append("\r\n");
    out.append(base64(message.bodyText() == null ? "" : message.bodyText())).append("\r\n");
    if (message.attachment() != null && message.attachment().length > 0) {
      out.append("--").append(boundary).append("\r\n");
      out.append("Content-Type: ").append(ATTACHMENT_TYPE).append("; name=\"")
          .append(safeFileName(message.attachmentName())).append("\"\r\n");
      out.append("Content-Transfer-Encoding: base64\r\n");
      out.append("Content-Disposition: attachment; filename=\"")
          .append(safeFileName(message.attachmentName())).append("\"\r\n");
      out.append("\r\n");
      out.append(base64(new String(message.attachment(), StandardCharsets.UTF_8))).append("\r\n");
    }
    out.append("--").append(boundary).append("--\r\n");
    return out.toString();
  }

  /** 主题里的非 ASCII 字符用 RFC 2047 的 base64 编码字（否则收件方显示乱码）。 */
  static String encodedSubject(String subject) {
    var text = subject == null ? "" : subject;
    var ascii = text.chars().allMatch(c -> c < 128);
    if (ascii) return text;
    return "=?UTF-8?B?" + base64(text) + "?=";
  }

  /** 附件名里不能有引号与换行（否则能塞进邮件头）。 */
  static String safeFileName(String name) {
    var text = name == null || name.isBlank() ? "report.csv" : name;
    return text.replaceAll("[^A-Za-z0-9._\\-]", "_");
  }

  static String base64(String text) {
    return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * 一条 SMTP 会话（连接 + 行协议）。
   *
   * <p>⚠️ **响应是可能多行的**：{@code 250-...} 开头的是续行，最后一行才是
   * {@code 250 ...}（空格分隔）。只读一行会把续行当最终响应 ⇒ 后面全乱。
   */
  static final class SmtpSession {
    private final String host;
    private final int port;
    private final boolean startTls;
    private Socket socket;
    private BufferedReader in;
    private BufferedWriter out;

    SmtpSession(String host, int port, boolean startTls) {
      this.host = host;
      this.port = port;
      this.startTls = startTls;
    }

    void open() throws IOException {
      socket = new Socket();
      socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
      socket.setSoTimeout(READ_TIMEOUT_MS);
      bind(socket);
    }

    private void bind(Socket target) throws IOException {
      in = new BufferedReader(new InputStreamReader(target.getInputStream(), StandardCharsets.UTF_8));
      out =
          new BufferedWriter(new OutputStreamWriter(target.getOutputStream(), StandardCharsets.UTF_8));
    }

    /** 升级到 TLS（STARTTLS 之后）。 */
    void startTls(String tlsHost, int tlsPort) throws IOException {
      var factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
      var ssl = (SSLSocket) factory.createSocket(socket, tlsHost, tlsPort, true);
      ssl.startHandshake();
      socket = ssl;
      bind(ssl);
    }

    /** 发一条命令并期待某个状态码。 */
    void command(String command, int expected) throws IOException {
      write(command + "\r\n");
      expect(expected);
    }

    void write(String text) throws IOException {
      out.write(text);
      out.flush();
    }

    /** 读一整段响应（可能多行），并校验最终状态码。 */
    void expect(int expected) throws IOException {
      var builder = new StringBuilder();
      while (true) {
        var line = in.readLine();
        if (line == null) {
          throw new IOException("SMTP 连接被对端关闭（期待 " + expected + "）");
        }
        builder.append(line).append('\n');
        if (line.length() >= 4 && line.charAt(3) == ' ') {
          var code = Integer.parseInt(line.substring(0, 3));
          if (code != expected) {
            throw new IOException(
                "SMTP 响应不是预期的 " + expected + "，实际是 " + code + "：" + builder.toString().trim());
          }
          return;
        }
      }
    }

    void close() {
      try {
        if (socket != null) socket.close();
      } catch (IOException e) {
        log.debug("关闭 SMTP 连接失败：{}", e.toString());
      }
    }
  }
}
