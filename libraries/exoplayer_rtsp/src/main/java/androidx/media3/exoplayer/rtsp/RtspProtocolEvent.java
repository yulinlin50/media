package androidx.media3.exoplayer.rtsp;

import androidx.annotation.Nullable;

/**
 * 不可变的 RTSP 协议事件：RtspClient 在请求/响应/传输选择的每个关键节点构造并派发给
 * {@link RtspProtocolEventListener}，供上层（如 app 的 PlayerAdapter）做失败分类与
 * 传输降级提示。failureReason 出口前已经过 {@link RtspMessageUtil#redactErrorMessage}
 * 脱敏，不得携带凭据。
 *
 * <p>本文件为功能重建版：原始实现随 media3-fork 工作区丢失，此处依据 RtspClient 调用点
 * 与 app 侧用法反推（字段顺序/语义与调用点一一对应），并非逐字节还原。
 */
public final class RtspProtocolEvent {

  private final String method;
  private final int statusCode;
  private final String phase;
  private final long requestGeneration;
  private final long attemptToken;
  private final String requestedTransport;
  private final String selectedTransport;
  @Nullable private final String failureReason;
  private final long sequence;
  private final long elapsedRealtimeMs;

  public RtspProtocolEvent(
      String method,
      int statusCode,
      String phase,
      long requestGeneration,
      long attemptToken,
      String requestedTransport,
      String selectedTransport,
      @Nullable String failureReason,
      long sequence,
      long elapsedRealtimeMs) {
    this.method = method;
    this.statusCode = statusCode;
    this.phase = phase;
    this.requestGeneration = requestGeneration;
    this.attemptToken = attemptToken;
    this.requestedTransport = requestedTransport;
    this.selectedTransport = selectedTransport;
    this.failureReason = failureReason;
    this.sequence = sequence;
    this.elapsedRealtimeMs = elapsedRealtimeMs;
  }

  public String getMethod() {
    return method;
  }

  public int getStatusCode() {
    return statusCode;
  }

  public String getPhase() {
    return phase;
  }

  public long getRequestGeneration() {
    return requestGeneration;
  }

  public long getAttemptToken() {
    return attemptToken;
  }

  public String getRequestedTransport() {
    return requestedTransport;
  }

  public String getSelectedTransport() {
    return selectedTransport;
  }

  @Nullable
  public String getFailureReason() {
    return failureReason;
  }

  public long getSequence() {
    return sequence;
  }

  public long getElapsedRealtimeMs() {
    return elapsedRealtimeMs;
  }
}
