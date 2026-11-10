/*
 * Copyright (C) 2025 The Android Open Source Project
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
package androidx.media3.extractor.ts;

import static androidx.media3.extractor.ts.TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR;
import static androidx.media3.test.utils.TestUtil.createByteArray;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.container.DolbyVisionConfig;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Bytes;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link H265Reader}. */
@RunWith(AndroidJUnit4.class)
public final class H265ReaderTest {

  private static final byte[] START_CODE = createByteArray(0x00, 0x00, 0x01);

  // VPS, SPS and PPS NAL units (NAL unit header included, start code excluded) taken from the
  // first access unit of media/ts/sample_h265_rps_pred.ts. The SPS carries no VUI colour
  // description, so the parsed colour space, range and transfer are all unset.
  private static final byte[] VPS_NAL_UNIT =
      createByteArray(
          0x40, 0x01, 0x0C, 0x07, 0xFF, 0xFF, 0x01, 0x60, 0x00, 0x00, 0x03, 0x00, 0x90, 0x00, 0x00,
          0x03, 0x00, 0x00, 0x03, 0x00, 0x3F, 0x00, 0x00, 0x99, 0x4C, 0xE6, 0x24, 0xC5, 0x81, 0x20);
  private static final byte[] SPS_NAL_UNIT =
      createByteArray(
          0x42, 0x01, 0x07, 0x01, 0x60, 0x00, 0x00, 0x03, 0x00, 0x90, 0x00, 0x00, 0x03, 0x00, 0x00,
          0x03, 0x00, 0x3F, 0x00, 0x00, 0xA0, 0x07, 0x32, 0x00, 0xAC, 0x72, 0x56, 0x26, 0x65, 0x33,
          0x98, 0x93, 0x17, 0x24, 0x8A, 0x41, 0xD2, 0x47, 0x4F, 0x3E, 0x3F, 0xC4, 0xFC, 0xB3, 0xF9,
          0xFB, 0x3C, 0xBF, 0xD7, 0xCC, 0x87, 0x92, 0xFF, 0x9F, 0xB3, 0xCB, 0xFD, 0x7C, 0x12, 0x3A,
          0x79, 0xF1, 0xFE, 0x27, 0xE5, 0x3F, 0xCF, 0xDF, 0xCB, 0x3F, 0x5F, 0x32, 0x1E, 0x4B, 0xFE,
          0x7E, 0xCF, 0x2F, 0xF5, 0xF1, 0xFF, 0xE1, 0x56, 0x81, 0x56, 0x60, 0x20, 0x00, 0x00, 0x7D,
          0x00, 0x00, 0x07, 0x53, 0x01);
  private static final byte[] PPS_NAL_UNIT =
      createByteArray(0x44, 0x01, 0xC1, 0x14, 0xA1, 0xC2, 0xB4, 0x13, 0x24);

  // IDR_W_RADL slice segment (type 19) with first_slice_segment_in_pic_flag set, followed by
  // arbitrary slice data.
  private static final byte[] IDR_SLICE_NAL_UNIT = createByteArray(0x26, 0x01, 0x80, 0x11, 0x22);

  // Dolby Vision RPU NAL unit (unspecified type 62). The reader treats it as part of the body of
  // the current access unit without interpreting its payload.
  private static final byte[] DV_RPU_NAL_UNIT = createByteArray(0x7C, 0x01, 0x19, 0x40);

  // Dolby Vision EL NAL unit (unspecified type 63) with arbitrary payload.
  private static final byte[] DV_EL_NAL_UNIT = createByteArray(0x7E, 0x01, 0x2A, 0x3B);

  private static final long PES_TIME_US = 1_234_567;

  @Test
  public void consume_rpuNalUnitAfterSlice_keepsRpuInSample() throws Exception {
    byte[] stream =
        byteStream(
            VPS_NAL_UNIT, SPS_NAL_UNIT, PPS_NAL_UNIT, IDR_SLICE_NAL_UNIT, DV_RPU_NAL_UNIT);

    FakeTrackOutput trackOutput = feed(/* dolbyVisionConfig= */ null, stream);

    // The RPU trails the slice segment of the access unit, so the whole stream is emitted as one
    // keyframe sample.
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.getSampleData(0)).isEqualTo(stream);
    assertThat(trackOutput.getSampleFlags(0)).isEqualTo(C.BUFFER_FLAG_KEY_FRAME);
    assertThat(trackOutput.getSampleTimeUs(0)).isEqualTo(PES_TIME_US);
  }

  @Test
  public void consume_elNalUnitAfterSlice_keepsElInSample() throws Exception {
    byte[] stream =
        byteStream(VPS_NAL_UNIT, SPS_NAL_UNIT, PPS_NAL_UNIT, IDR_SLICE_NAL_UNIT, DV_EL_NAL_UNIT);

    FakeTrackOutput trackOutput = feed(/* dolbyVisionConfig= */ null, stream);

    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.getSampleData(0)).isEqualTo(stream);
    assertThat(trackOutput.getSampleFlags(0)).isEqualTo(C.BUFFER_FLAG_KEY_FRAME);
    assertThat(trackOutput.getSampleTimeUs(0)).isEqualTo(PES_TIME_US);
  }

  @Test
  public void consume_withoutDolbyVisionConfig_outputsHevcFormat() throws Exception {
    FakeTrackOutput trackOutput =
        feed(
            /* dolbyVisionConfig= */ null,
            byteStream(
                VPS_NAL_UNIT, SPS_NAL_UNIT, PPS_NAL_UNIT, IDR_SLICE_NAL_UNIT, DV_RPU_NAL_UNIT));

    Format format = checkNotNull(trackOutput.lastFormat);
    assertThat(format.sampleMimeType).isEqualTo(MimeTypes.VIDEO_H265);
    assertThat(format.codecs).isEqualTo("hvc1.1.6.L63.90");
    assertThat(format.width).isEqualTo(914);
    assertThat(format.height).isEqualTo(686);
    assertThat(format.initializationData).hasSize(1);
    assertThat(format.initializationData.get(0))
        .isEqualTo(
            Bytes.concat(START_CODE, VPS_NAL_UNIT, START_CODE, SPS_NAL_UNIT, START_CODE,
                PPS_NAL_UNIT));
    ColorInfo colorInfo = checkNotNull(format.colorInfo);
    assertThat(colorInfo.colorSpace).isEqualTo(Format.NO_VALUE);
    assertThat(colorInfo.colorRange).isEqualTo(Format.NO_VALUE);
    assertThat(colorInfo.colorTransfer).isEqualTo(Format.NO_VALUE);
  }

  @Test
  public void consume_withProfile5Config_outputsDolbyVisionFormatWithSynthesizedColorInfo()
      throws Exception {
    FakeTrackOutput trackOutput =
        feed(
            dolbyVisionConfig(/* profile= */ 5),
            byteStream(
                VPS_NAL_UNIT, SPS_NAL_UNIT, PPS_NAL_UNIT, IDR_SLICE_NAL_UNIT, DV_RPU_NAL_UNIT));

    Format format = checkNotNull(trackOutput.lastFormat);
    assertThat(format.sampleMimeType).isEqualTo(MimeTypes.VIDEO_DOLBY_VISION);
    assertThat(format.codecs).isEqualTo("dvhe.05.06");
    // Profile 5 SPSs commonly omit VUI colour signalling, so it is synthesized as BT.2020 with
    // PQ (ST 2084) transfer and limited range.
    ColorInfo colorInfo = checkNotNull(format.colorInfo);
    assertThat(colorInfo.colorSpace).isEqualTo(C.COLOR_SPACE_BT2020);
    assertThat(colorInfo.colorRange).isEqualTo(C.COLOR_RANGE_LIMITED);
    assertThat(colorInfo.colorTransfer).isEqualTo(C.COLOR_TRANSFER_ST2084);
    // Sample data is still emitted.
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
  }

  @Test
  public void consume_withProfile8Config_outputsDolbyVisionFormatWithoutColorInfoOverride()
      throws Exception {
    FakeTrackOutput trackOutput =
        feed(
            dolbyVisionConfig(/* profile= */ 8),
            byteStream(
                VPS_NAL_UNIT, SPS_NAL_UNIT, PPS_NAL_UNIT, IDR_SLICE_NAL_UNIT, DV_RPU_NAL_UNIT));

    Format format = checkNotNull(trackOutput.lastFormat);
    assertThat(format.sampleMimeType).isEqualTo(MimeTypes.VIDEO_DOLBY_VISION);
    assertThat(format.codecs).isEqualTo("dvhe.08.06");
    // Cross-compatible profiles keep the SPS-signalled colour info (which stays unset here).
    ColorInfo colorInfo = checkNotNull(format.colorInfo);
    assertThat(colorInfo.colorSpace).isEqualTo(Format.NO_VALUE);
    assertThat(colorInfo.colorRange).isEqualTo(Format.NO_VALUE);
    assertThat(colorInfo.colorTransfer).isEqualTo(Format.NO_VALUE);
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
  }

  /**
   * Feeds {@code stream} to an {@link H265Reader} built with {@code dolbyVisionConfig} and returns
   * the {@link FakeTrackOutput} it was assigned.
   */
  private static FakeTrackOutput feed(
      @Nullable DolbyVisionConfig dolbyVisionConfig, byte[] stream) {
    FakeExtractorOutput extractorOutput = new FakeExtractorOutput();
    H265Reader reader =
        new H265Reader(
            new SeiReader(ImmutableList.of(), MimeTypes.VIDEO_MP2T),
            MimeTypes.VIDEO_MP2T,
            dolbyVisionConfig);
    reader.createTracks(
        extractorOutput,
        new TrackIdGenerator(/* firstTrackId= */ 0, /* trackIdIncrement= */ 1));
    reader.packetStarted(PES_TIME_US, FLAG_DATA_ALIGNMENT_INDICATOR);
    reader.consume(new ParsableByteArray(stream));
    reader.endOfInputReached();
    return checkNotNull(extractorOutput.trackOutputs.get(0));
  }

  /** Returns {@code nalUnits} as one Annex B byte stream, each prefixed with a start code. */
  private static byte[] byteStream(byte[]... nalUnits) {
    byte[] stream = new byte[0];
    for (byte[] nalUnit : nalUnits) {
      stream = Bytes.concat(stream, START_CODE, nalUnit);
    }
    return stream;
  }

  /**
   * Returns a {@link DolbyVisionConfig} for {@code profile} at level 6 with {@code
   * rpu_present_flag = 1} and {@code bl_present_flag = 1}, parsed from the dvcC-like 4-byte record
   * used by the TS Dolby Vision video descriptor (tag 0xB0).
   */
  private static DolbyVisionConfig dolbyVisionConfig(int profile) {
    // dv_version_major(8) = 1, dv_version_minor(8) = 0, dv_profile(7) = profile, dv_level(6) = 6,
    // rpu_present_flag(1) = 1, el_present_flag(1) = 0, bl_present_flag(1) = 1.
    return checkNotNull(
        DolbyVisionConfig.parse(
            new ParsableByteArray(createByteArray(0x01, 0x00, profile << 1, 0x35))));
  }
}
