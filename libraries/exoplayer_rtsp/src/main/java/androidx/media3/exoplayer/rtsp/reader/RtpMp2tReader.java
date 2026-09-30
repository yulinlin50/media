/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer.rtsp.reader;

import androidx.media3.common.C;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.extractor.ts.TsExtractor;
import java.io.IOException;

/**
 * Reads RTP packets carrying MPEG-2 TS payloads (RFC 2250, static payload type 33).
 *
 * <p>Each RTP payload is fed into a {@link TsExtractor} through a {@link FakeExtractorInput}
 * bridge that wraps the packet buffer without copying. RTP timestamps and markers are ignored on
 * purpose: a TS payload carries its own PTS/DTS clock, so the TS content is the single source of
 * timing (the RTP timestamp must never be mixed in).
 *
 * <p>Loss tolerance: a payload that cannot be parsed is dropped and counted instead of failing the
 * session. Over UDP, carrier IPTV sources routinely lose packets; killing playback for one bad
 * packet would make the reader unusable where it matters most.
 */
@UnstableApi
public final class RtpMp2tReader implements RtpPayloadReader {

  private static final String TAG = "RtpMp2tReader";

  private static final byte TS_SYNC_BYTE = (byte) 0x47;
  /** Non-standard variant of carrier servers: a 4-byte header (TP_extra_header) precedes each
   * 188-byte TS packet (BDAV/M2TS framing, 192-byte stride). */
  private static final int HEADER_SIZE = 4;
  /** Log every failure up to this count, then only every 100th, so lossy streams stay quiet. */
  private static final int LOG_THROTTLE_INTERVAL = 100;

  private final TsExtractor tsExtractor;
  private final FakeExtractorInput fakeInput;
  private final PositionHolder positionHolder;
  /** Reused destination buffer for the 192→188 byte header-stripping path. */
  private byte[] stripBuffer;

  private int droppedPacketCount;

  public RtpMp2tReader() {
    fakeInput = new FakeExtractorInput();
    positionHolder = new PositionHolder();
    tsExtractor =
        new TsExtractor(
            TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA, SubtitleParser.Factory.UNSUPPORTED);
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, int trackId) {
    tsExtractor.init(extractorOutput);
  }

  @Override
  public void onReceivingFirstPacket(long timestamp, int sequenceNumber) {}

  @Override
  public void consume(ParsableByteArray data, long timestamp, int sequenceNumber, boolean rtpMarker)
      throws ParserException {
    fakeInput.reset(maybeStripHeaders(data));
    try {
      int result;
      do {
        result = tsExtractor.read(fakeInput, positionHolder);
      } while (result == Extractor.RESULT_CONTINUE && fakeInput.hasData());
    } catch (IOException e) {
      // The TS extraction of this packet failed (usually a truncated or malformed payload after
      // packet loss). Drop the packet and keep the session alive; never propagate as a
      // ParserException, which would tear down the whole RTSP playback.
      droppedPacketCount++;
      if (droppedPacketCount <= 5 || droppedPacketCount % LOG_THROTTLE_INTERVAL == 0) {
        Log.w(TAG, "Dropped unparseable MP2T packet, total dropped: " + droppedPacketCount);
      }
    }
  }

  @Override
  public void seek(long nextRtpTimestamp, long timeUs) {
    // Position 0: the fake input restarts at the beginning of every RTP payload, so the extractor
    // must forget any byte position it has accumulated.
    tsExtractor.seek(/* position= */ 0, timeUs);
  }

  /**
   * Returns the packet bytes as plain TS packets, stripping {@link #HEADER_SIZE} leading bytes per
   * 188-byte packet when the payload uses the 192-byte stride variant. Standard 188-byte payloads
   * are returned unchanged (zero copy). Payloads matching neither shape are returned unchanged too;
   * the downstream TS extraction drops them with a log instead of failing the session.
   */
  /* package */ ParsableByteArray maybeStripHeaders(ParsableByteArray data) {
    byte[] src = data.getData();
    int pos = data.getPosition();
    int length = data.bytesLeft();
    if (length % TsExtractor.TS_PACKET_SIZE == 0 && src[pos] == TS_SYNC_BYTE) {
      return data;
    }
    int stride = TsExtractor.TS_PACKET_SIZE + HEADER_SIZE;
    if (length < stride || length % stride != 0 || src[pos + HEADER_SIZE] != TS_SYNC_BYTE) {
      return data;
    }
    int packetCount = length / stride;
    int requiredBytes = packetCount * TsExtractor.TS_PACKET_SIZE;
    if (stripBuffer == null || stripBuffer.length < requiredBytes) {
      stripBuffer = new byte[requiredBytes];
    }
    ParsableByteArray reader = new ParsableByteArray(src, data.limit());
    reader.setPosition(pos);
    int dstOffset = 0;
    for (int i = 0; i < packetCount; i++) {
      reader.skipBytes(HEADER_SIZE);
      reader.readBytes(stripBuffer, dstOffset, TsExtractor.TS_PACKET_SIZE);
      dstOffset += TsExtractor.TS_PACKET_SIZE;
    }
    return new ParsableByteArray(stripBuffer, requiredBytes);
  }

  /**
   * A minimal {@link ExtractorInput} over an in-memory RTP payload.
   *
   * <p>Peeking is unsupported on purpose: the TS extractor consumes packets strictly forward while
   * parsing, and never sniffs (the payload type is already known from the SDP). Any peek attempt is
   * an implementation error and fails loudly.
   */
  private static final class FakeExtractorInput implements ExtractorInput {

    private final ParsableByteArray buffer = new ParsableByteArray();
    private long streamPosition;

    void reset(ParsableByteArray source) {
      buffer.reset(source.getData(), source.limit());
      buffer.setPosition(source.getPosition());
    }

    boolean hasData() {
      return buffer.bytesLeft() > 0;
    }

    @Override
    public int read(byte[] target, int offset, int length) {
      int available = buffer.bytesLeft();
      if (available == 0) {
        return C.RESULT_END_OF_INPUT;
      }
      int toRead = Math.min(length, available);
      buffer.readBytes(target, offset, toRead);
      streamPosition += toRead;
      return toRead;
    }

    @Override
    public long getPosition() {
      return streamPosition;
    }

    @Override
    public long getLength() {
      return C.LENGTH_UNSET;
    }

    @Override
    public boolean readFully(byte[] target, int offset, int length, boolean allowEndOfInput)
        throws IOException {
      int available = buffer.bytesLeft();
      if (available == 0 && allowEndOfInput) {
        return false;
      }
      if (available < length) {
        throw new java.io.EOFException();
      }
      buffer.readBytes(target, offset, length);
      streamPosition += length;
      return true;
    }

    @Override
    public void readFully(byte[] target, int offset, int length) throws IOException {
      readFully(target, offset, length, /* allowEndOfInput= */ false);
    }

    @Override
    public int skip(int length) {
      int toSkip = Math.min(length, buffer.bytesLeft());
      buffer.skipBytes(toSkip);
      streamPosition += toSkip;
      return toSkip == 0 ? C.RESULT_END_OF_INPUT : toSkip;
    }

    @Override
    public boolean skipFully(int length, boolean allowEndOfInput) throws IOException {
      if (buffer.bytesLeft() == 0 && allowEndOfInput) {
        return false;
      }
      if (buffer.bytesLeft() < length) {
        throw new java.io.EOFException();
      }
      buffer.skipBytes(length);
      streamPosition += length;
      return true;
    }

    @Override
    public void skipFully(int length) throws IOException {
      skipFully(length, /* allowEndOfInput= */ false);
    }

    @Override
    public int peek(byte[] target, int offset, int length) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean peekFully(byte[] target, int offset, int length, boolean allowEndOfInput) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void peekFully(byte[] target, int offset, int length) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean advancePeekPosition(int length, boolean allowEndOfInput) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void advancePeekPosition(int length) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void resetPeekPosition() {
      throw new UnsupportedOperationException();
    }

    @Override
    public long getPeekPosition() {
      throw new UnsupportedOperationException();
    }

    @Override
    public <E extends Throwable> void setRetryPosition(long position, E throwable) throws E {
      throw throwable;
    }
  }
}
