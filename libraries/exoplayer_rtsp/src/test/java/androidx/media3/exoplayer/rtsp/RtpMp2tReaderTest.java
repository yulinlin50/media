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
package androidx.media3.exoplayer.rtsp;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.rtsp.reader.RtpMp2tReader;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Consumption tests for {@link RtpMp2tReader}: full-payload parsing and loss tolerance. */
@RunWith(AndroidJUnit4.class)
public class RtpMp2tReaderTest {

  private static final int TS_PACKET_SIZE = 188;

  private RtpMp2tReader reader;
  private FakeExtractorOutput extractorOutput;

  @Before
  public void setUp() {
    reader = new RtpMp2tReader();
    extractorOutput = new FakeExtractorOutput();
    reader.createTracks(extractorOutput, /* trackId= */ 0);
  }

  @Test
  public void consume_nullPacketTsPayload_doesNotThrow() throws ParserException {
    reader.onReceivingFirstPacket(/* timestamp= */ 90000, /* sequenceNumber= */ 20000);
    byte[] payload = createNullPacketTsPayload(TS_PACKET_SIZE * 7);

    reader.consume(
        new ParsableByteArray(payload),
        /* timestamp= */ 90000,
        /* sequenceNumber= */ 20000,
        /* rtpMarker= */ false);
  }

  @Test
  public void consume_truncatedPayload_dropsPacketWithoutFailingSession() throws ParserException {
    reader.onReceivingFirstPacket(/* timestamp= */ 90000, /* sequenceNumber= */ 20000);
    // Starts with a sync byte, but the length matches neither the 188 nor the 192 stride: the TS
    // extraction runs off the end of the payload.
    byte[] truncatedPayload = new byte[100];
    truncatedPayload[0] = (byte) 0x47;

    reader.consume(
        new ParsableByteArray(truncatedPayload),
        /* timestamp= */ 90000,
        /* sequenceNumber= */ 20000,
        /* rtpMarker= */ false);

    // The reader survives malformed input and keeps consuming subsequent packets.
    byte[] validPayload = createNullPacketTsPayload(TS_PACKET_SIZE * 7);
    reader.consume(
        new ParsableByteArray(validPayload),
        /* timestamp= */ 90001,
        /* sequenceNumber= */ 20001,
        /* rtpMarker= */ true);
  }

  @Test
  public void consume_realMp2tStream_extractsTrackFormatAndSamples()
      throws IOException, ParserException {
    RtpPacketStreamDump dump =
        RtpPacketStreamDump.parse(
            TestUtil.getString(
                ApplicationProvider.getApplicationContext(), "media/rtsp/mp2t-dump.json"));

    long timestamp = dump.firstTimestamp;
    int sequenceNumber = dump.firstSequenceNumber;
    for (String packetHex : dump.packets) {
      byte[] rtpPacket = Util.getBytesFromHexString(packetHex);
      // Strip the 12-byte RTP header; the reader only ever sees the payload.
      byte[] payload = new byte[rtpPacket.length - 12];
      System.arraycopy(rtpPacket, 12, payload, 0, payload.length);
      reader.consume(
          new ParsableByteArray(payload), timestamp, sequenceNumber, /* rtpMarker= */ false);
      timestamp += 3600;
      sequenceNumber++;
    }

    // The AAC elementary stream inside the TS must surface as one track with a format and samples.
    assertThat(extractorOutput.numberOfTracks).isEqualTo(1);
    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.valueAt(0);
    assertThat(trackOutput.lastFormat).isNotNull();
    assertThat(trackOutput.getSampleCount()).isAtLeast(1);
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
