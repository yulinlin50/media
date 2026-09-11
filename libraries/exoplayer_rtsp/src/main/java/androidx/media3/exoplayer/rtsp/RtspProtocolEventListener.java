package androidx.media3.exoplayer.rtsp;

/**
 * 接收 RTSP 协议事件（请求/响应/传输选择/失败）的回调接口。RtspClient 在协议事件
 * 线程上派发，实现方应自行做线程切换，不得在回调内做阻塞操作。
 *
 * <p>本文件为功能重建版：原始实现随 media3-fork 工作区丢失，此处依据调用点
 * （RtspClient 的 SAM lambda 用法）反推，并非逐字节还原。
 */
public interface RtspProtocolEventListener {

  /** 有新的协议事件产生。 */
  void onProtocolEvent(RtspProtocolEvent event);
}
