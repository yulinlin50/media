/*
 * Copyright 2026 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertSame;

import androidx.media3.common.util.ParsableByteArray;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for the TP_extra_header stripping of {@link RtpMp2tReader}. */
@RunWith(AndroidJUnit4.class)
public class RtpMp2tReaderHeaderTest {

  private static final int TS_PACKET_SIZE = 188;
  private static final int M2TS_STRIDE = TS_PACKET_SIZE + 4;

  private RtpMp2tReader reader;

  @Before
  public void setUp() {
    reader = new RtpMp2tReader();
  }

  @Test
  public void maybeStripHeaders_standardPayload_passthroughWithoutCopy() {
    byte[] payload = createNullPacketTsPayload(TS_PACKET_SIZE * 3);
    ParsableByteArray data = new ParsableByteArray(payload);

    ParsableByteArray result = reader.maybeStripHeaders(data);

    assertSame(payload, result.getData());
    assertThat(result.bytesLeft()).isEqualTo(payload.length);
  }

  @Test
  public void maybeStripHeaders_m2tsPayload_stripsTpExtraHeaders() {
    int packetCount = 3;
    byte[] payload = new byte[M2TS_STRIDE * packetCount];
    byte[] tsPacket = createNullPacketTsPayload(TS_PACKET_SIZE);
    for (int i = 0; i < packetCount; i++) {
      int offset = i * M2TS_STRIDE;
      // TP_extra_header: 4 arbitrary bytes followed by the TS packet.
      payload[offset] = 0x01;
      payload[offset + 1] = 0x02;
      payload[offset + 2] = 0x03;
      payload[offset + 3] = 0x04;
      System.arraycopy(tsPacket, 0, payload, offset + 4, TS_PACKET_SIZE);
    }
    ParsableByteArray data = new ParsableByteArray(payload);

    ParsableByteArray result = reader.maybeStripHeaders(data);

    assertThat(result.bytesLeft()).isEqualTo(TS_PACKET_SIZE * packetCount);
    byte[] stripped = result.getData();
    for (int i = 0; i < packetCount; i++) {
      assertThat(stripped[i * TS_PACKET_SIZE]).isEqualTo((byte) 0x47);
      for (int j = 1; j < TS_PACKET_SIZE; j++) {
        assertThat(stripped[i * TS_PACKET_SIZE + j]).isEqualTo(tsPacket[j]);
      }
    }
  }

  @Test
  public void maybeStripHeaders_m2tsPayload_reusesStripBufferAcrossCalls() {
    byte[] payload = new byte[M2TS_STRIDE * 2];
    byte[] tsPacket = createNullPacketTsPayload(TS_PACKET_SIZE);
    for (int i = 0; i < 2; i++) {
      System.arraycopy(tsPacket, 0, payload, i * M2TS_STRIDE + 4, TS_PACKET_SIZE);
    }

    ParsableByteArray first = reader.maybeStripHeaders(new ParsableByteArray(payload));
    byte[] firstData = first.getData();
    ParsableByteArray second = reader.maybeStripHeaders(new ParsableByteArray(payload));

    assertSame(firstData, second.getData());
  }

  @Test
  public void maybeStripHeaders_unrecognizedPayload_passthrough() {
    // Not a multiple of 188 or 192, and does not start with a sync byte either way.
    byte[] payload = new byte[100];
    payload[0] = 0x00;
    ParsableByteArray data = new ParsableByteArray(payload);

    ParsableByteArray result = reader.maybeStripHeaders(data);

    assertSame(payload, result.getData());
  }

  /**
   * Returns RTP payload bytes made of null TS packets (PID 0x1FFF), which the TS extractor
   * ignores.
   */
  private static byte[] createNullPacketTsPayload(int totalBytes) {
    byte[] payload = new byte[totalBytes];
    for (int offset = 0; offset + TS_PACKET_SIZE <= totalBytes; offset += TS_PACKET_SIZE) {
      payload[offset] = (byte) 0x47; // Sync byte.
      payload[offset + 1] = 0x1F; // PID 0x1FFF (null packet), high bits.
      payload[offset + 2] = (byte) 0xFF; // PID 0x1FFF, low bits.
      payload[offset + 3] = 0x10; // No adaptation field, continuity counter 0.
      // The remaining 184 bytes stay zero-filled; the extractor drops null packets unread.
    }
    return payload;
  }
}
