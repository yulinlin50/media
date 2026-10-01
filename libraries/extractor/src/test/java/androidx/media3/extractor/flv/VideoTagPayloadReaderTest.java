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
package androidx.media3.extractor.flv;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.container.NalUnitUtil;
import androidx.media3.test.utils.FakeTrackOutput;
import com.google.common.primitives.Bytes;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** Unit test for {@link VideoTagPayloadReader}, including enhanced FLV (FourCC) video tags. */
@RunWith(RobolectricTestRunner.class)
public final class VideoTagPayloadReaderTest {

  private static final byte[] FOURCC_HVC1 = {'h', 'v', 'c', '1'};
  private static final byte[] FOURCC_AV01 = {'a', 'v', '0', '1'};

  private static final long BASE_TIME_US = 1_000_000L;

  @Test
  public void enhancedHevcSequenceStartThenCodedFramesOutputsHevcSample() throws Exception {
    FakeTrackOutput trackOutput = new FakeTrackOutput(C.TRACK_TYPE_VIDEO, false);
    VideoTagPayloadReader reader = new VideoTagPayloadReader(trackOutput);

    // Sequence start: IsExHeader | keyframe | PacketType 0, FourCC, minimal hvcC record.
    ParsableByteArray sequenceTag =
        new ParsableByteArray(
            Bytes.concat(
                new byte[] {(byte) 0x90}, FOURCC_HVC1, minimalHevcConfigRecord()));
    assertThat(reader.parseHeader(sequenceTag)).isTrue();
    assertThat(reader.parsePayload(sequenceTag, BASE_TIME_US)).isFalse();

    // Coded frames: PacketType 1 carries a signed 24-bit composition time offset.
    ParsableByteArray codedTag =
        new ParsableByteArray(
            Bytes.concat(
                new byte[] {(byte) 0x91},
                FOURCC_HVC1,
                new byte[] {0x00, 0x00, 0x05}, // compositionTimeMs = 5
                nalUnit(/* payload= */ 0x2A, 0x01, 0x02)));
    assertThat(reader.parseHeader(codedTag)).isTrue();
    assertThat(reader.parsePayload(codedTag, BASE_TIME_US)).isTrue();

    assertThat(trackOutput.lastFormat).isNotNull();
    assertThat(trackOutput.lastFormat.sampleMimeType).isEqualTo(MimeTypes.VIDEO_H265);
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.getSampleTimeUs(0)).isEqualTo(BASE_TIME_US + 5_000L);
    assertThat(trackOutput.getSampleFlags(0)).isEqualTo(C.BUFFER_FLAG_KEY_FRAME);
    assertThat(trackOutput.getSampleData(0))
        .isEqualTo(Bytes.concat(NalUnitUtil.NAL_START_CODE, new byte[] {0x2A, 0x01, 0x02}));
  }

  @Test
  public void enhancedHevcCodedFramesXOmitsCompositionTimeOffset() throws Exception {
    FakeTrackOutput trackOutput = new FakeTrackOutput(C.TRACK_TYPE_VIDEO, false);
    VideoTagPayloadReader reader = new VideoTagPayloadReader(trackOutput);

    ParsableByteArray sequenceTag =
        new ParsableByteArray(
            Bytes.concat(new byte[] {(byte) 0x90}, FOURCC_HVC1, minimalHevcConfigRecord()));
    assertThat(reader.parseHeader(sequenceTag)).isTrue();
    assertThat(reader.parsePayload(sequenceTag, BASE_TIME_US)).isFalse();

    // PacketType 3 (CodedFramesX): NAL units follow the FourCC directly, no time offset.
    ParsableByteArray codedTag =
        new ParsableByteArray(
            Bytes.concat(new byte[] {(byte) 0x93}, FOURCC_HVC1, nalUnit(0x2A)));
    assertThat(reader.parseHeader(codedTag)).isTrue();
    assertThat(reader.parsePayload(codedTag, BASE_TIME_US)).isTrue();

    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.getSampleTimeUs(0)).isEqualTo(BASE_TIME_US);
  }

  @Test
  public void enhancedUnsupportedFourCcThrows() {
    VideoTagPayloadReader reader =
        new VideoTagPayloadReader(new FakeTrackOutput(C.TRACK_TYPE_VIDEO, false));
    ParsableByteArray tag =
        new ParsableByteArray(Bytes.concat(new byte[] {(byte) 0x90}, FOURCC_AV01));

    assertThrows(
        TagPayloadReader.UnsupportedFormatException.class, () -> reader.parseHeader(tag));
  }

  @Test
  public void truncatedEnhancedHeaderThrows() {
    VideoTagPayloadReader reader =
        new VideoTagPayloadReader(new FakeTrackOutput(C.TRACK_TYPE_VIDEO, false));
    // IsExHeader set but the FourCC is missing.
    ParsableByteArray tag = new ParsableByteArray(new byte[] {(byte) 0x90, 'h'});

    assertThrows(
        TagPayloadReader.UnsupportedFormatException.class, () -> reader.parseHeader(tag));
  }

  @Test
  public void legacyHevcCodecId12SequenceStartThenCodedFramesOutputsHevcSample() throws Exception {
    FakeTrackOutput trackOutput = new FakeTrackOutput(C.TRACK_TYPE_VIDEO, false);
    VideoTagPayloadReader reader = new VideoTagPayloadReader(trackOutput);

    // Legacy tag: frame type 1 in bits 4-6, codec id 12 in the low nibble. The payload starts
    // with the packet type byte and a (zero) composition time offset even for sequence headers.
    ParsableByteArray sequenceTag =
        new ParsableByteArray(
            Bytes.concat(new byte[] {0x1C, 0x00, 0x00, 0x00, 0x00}, minimalHevcConfigRecord()));
    assertThat(reader.parseHeader(sequenceTag)).isTrue();
    assertThat(reader.parsePayload(sequenceTag, BASE_TIME_US)).isFalse();

    ParsableByteArray codedTag =
        new ParsableByteArray(
            Bytes.concat(
                new byte[] {0x1C, 0x01, 0x00, 0x00, 0x03}, nalUnit(0x2A)));
    assertThat(reader.parseHeader(codedTag)).isTrue();
    assertThat(reader.parsePayload(codedTag, BASE_TIME_US)).isTrue();

    assertThat(trackOutput.lastFormat).isNotNull();
    assertThat(trackOutput.lastFormat.sampleMimeType).isEqualTo(MimeTypes.VIDEO_H265);
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.getSampleTimeUs(0)).isEqualTo(BASE_TIME_US + 3_000L);
    assertThat(trackOutput.getSampleFlags(0)).isEqualTo(C.BUFFER_FLAG_KEY_FRAME);
  }

  @Test
  public void legacyUnsupportedCodecIdThrows() {
    VideoTagPayloadReader reader =
        new VideoTagPayloadReader(new FakeTrackOutput(C.TRACK_TYPE_VIDEO, false));
    ParsableByteArray tag = new ParsableByteArray(new byte[] {0x14}); // codec id 4

    assertThrows(
        TagPayloadReader.UnsupportedFormatException.class, () -> reader.parseHeader(tag));
  }

  @Test
  public void videoInfoFrameIsSkipped() throws Exception {
    VideoTagPayloadReader reader =
        new VideoTagPayloadReader(new FakeTrackOutput(C.TRACK_TYPE_VIDEO, false));
    // Frame type 5 (video info/command): the tag payload must not be parsed.
    ParsableByteArray tag =
        new ParsableByteArray(Bytes.concat(new byte[] {0x57}, new byte[] {0x00, 0x00, 0x00}));

    assertThat(reader.parseHeader(tag)).isFalse();
  }

  /** Returns a minimal but valid HEVCDecoderConfigurationRecord with zero arrays. */
  private static byte[] minimalHevcConfigRecord() {
    byte[] record = new byte[23];
    record[0] = 0x01; // configurationVersion
    record[21] = 0x03; // lengthSizeMinusOne = 3 -> 4-byte NAL unit length fields
    record[22] = 0x00; // numOfArrays = 0
    return record;
  }

  /** Returns a single NAL unit in length-delimited form (4-byte big-endian length prefix). */
  private static byte[] nalUnit(int... payload) {
    byte[] nal = new byte[4 + payload.length];
    nal[0] = (byte) (payload.length >>> 24);
    nal[1] = (byte) (payload.length >>> 16);
    nal[2] = (byte) (payload.length >>> 8);
    nal[3] = (byte) payload.length;
    for (int i = 0; i < payload.length; i++) {
      nal[4 + i] = (byte) payload[i];
    }
    return nal;
  }
}
