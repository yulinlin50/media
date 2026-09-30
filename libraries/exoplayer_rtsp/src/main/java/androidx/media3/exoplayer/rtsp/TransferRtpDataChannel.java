package androidx.media3.exoplayer.rtsp;

import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.min;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.rtsp.RtspMessageChannel.InterleavedBinaryDataListener;
import java.util.concurrent.LinkedBlockingQueue;

/** An {@link RtpDataChannel} that transfers received data in-memory. */
/* package */ final class TransferRtpDataChannel extends BaseDataSource
    implements RtpDataChannel, RtspMessageChannel.InterleavedBinaryDataListener {

  private static final String DEFAULT_TCP_TRANSPORT_FORMAT =
      "RTP/AVP/TCP;unicast;interleaved=%d-%d";

  private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

  private final LinkedBlockingQueue<byte[]> packetQueue;
  private final long pollTimeoutMs;

  private volatile boolean rtpTimedOut;
  private byte[] unreadData;
  /** Read cursor into {@link #unreadData}; avoids copying the array tail on every partial read. */
  private int unreadDataOffset;
  private int channelNumber;

  /**
   * Creates a new instance.
   *
   * @param pollTimeoutMs The number of milliseconds which {@link #read} waits for a packet to be
   *     available. After the time has expired, {@link C#RESULT_END_OF_INPUT} is returned.
   */
  public TransferRtpDataChannel(long pollTimeoutMs) {
    super(/* isNetwork= */ true);
    this.pollTimeoutMs = pollTimeoutMs;
    packetQueue = new LinkedBlockingQueue<>();
    rtpTimedOut = false;
    unreadData = EMPTY_BYTE_ARRAY;
    unreadDataOffset = 0;
    channelNumber = C.INDEX_UNSET;
  }

  @Override
  public String getTransport() {
    checkState(channelNumber != C.INDEX_UNSET); // Assert open() is called.
    return Util.formatInvariant(DEFAULT_TCP_TRANSPORT_FORMAT, channelNumber, channelNumber + 1);
  }

  @Override
  public int getLocalPort() {
    return channelNumber;
  }

  @Override
  public boolean needsClosingOnLoadCompletion() {
    // TCP channel is managed by the RTSP mesasge channel and does not need closing from here.
    return false;
  }

  @Override
  public InterleavedBinaryDataListener getInterleavedBinaryDataListener() {
    return this;
  }

  @Override
  public long open(DataSpec dataSpec) {
    this.channelNumber = dataSpec.uri.getPort();
    rtpTimedOut = false;
    return C.LENGTH_UNSET;
  }

  @Override
  public boolean isRtpTimeout() {
    return rtpTimedOut;
  }

  @Override
  public void close() {}

  @Nullable
  @Override
  public Uri getUri() {
    return null;
  }

  @Override
  public int read(byte[] buffer, int offset, int length) {
    if (length == 0) {
      return 0;
    }

    int bytesRead = 0;
    int available = unreadData.length - unreadDataOffset;
    int bytesToRead = min(length, available);
    System.arraycopy(unreadData, /* srcPos= */ unreadDataOffset, buffer, offset, bytesToRead);
    bytesRead += bytesToRead;
    unreadDataOffset += bytesToRead;
    if (unreadDataOffset >= unreadData.length) {
      unreadData = EMPTY_BYTE_ARRAY;
      unreadDataOffset = 0;
    }

    if (bytesRead == length) {
      return bytesRead;
    }

    @Nullable byte[] data;
    try {
      data = packetQueue.poll(pollTimeoutMs, MILLISECONDS);
      if (data == null) {
        rtpTimedOut = true;
        return C.RESULT_END_OF_INPUT;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return C.RESULT_END_OF_INPUT;
    }

    bytesToRead = min(length - bytesRead, data.length);
    System.arraycopy(data, /* srcPos= */ 0, buffer, offset + bytesRead, bytesToRead);
    if (bytesToRead < data.length) {
      unreadData = data;
      unreadDataOffset = bytesToRead;
    }
    return bytesRead + bytesToRead;
  }

  @Override
  public void onInterleavedBinaryDataReceived(byte[] data) {
    packetQueue.add(data);
  }
}