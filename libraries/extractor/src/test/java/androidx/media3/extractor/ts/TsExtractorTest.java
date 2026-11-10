/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS;
import static androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS;
import static androidx.media3.extractor.ts.TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES;
import static androidx.media3.extractor.ts.TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA;
import static androidx.media3.extractor.ts.TsExtractor.MODE_MULTI_PMT;
import static androidx.media3.extractor.ts.TsExtractor.MODE_SINGLE_PMT;
import static androidx.media3.test.utils.TestUtil.createByteArray;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.truth.Truth.assertThat;

import android.util.SparseArray;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.TimestampAdjuster;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.extractor.ts.TsPayloadReader.EsInfo;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;
import androidx.media3.test.utils.ExtractorAsserts;
import androidx.media3.test.utils.FakeExtractorInput;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.primitives.Bytes;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameter;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters;

/** Unit test for {@link TsExtractor}. */
@RunWith(ParameterizedRobolectricTestRunner.class)
public final class TsExtractorTest {

  @Parameters(name = "{0},subtitlesParsedDuringExtraction={1}")
  public static List<Object[]> params() {
    List<Object[]> parameterList = new ArrayList<>();
    for (ExtractorAsserts.SimulationConfig config : ExtractorAsserts.configs()) {
      parameterList.add(new Object[] {config, /* subtitlesParsedDuringExtraction */ true});
      parameterList.add(new Object[] {config, /* subtitlesParsedDuringExtraction */ false});
    }
    return parameterList;
  }

  @Parameter(0)
  public ExtractorAsserts.SimulationConfig simulationConfig;

  @Parameter(1)
  public boolean subtitlesParsedDuringExtraction;

  @Test
  public void sampleWithH262AndMpegAudio() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(subtitlesParsedDuringExtraction),
        "media/ts/sample_h262_mpeg_audio.ts",
        simulationConfig);
  }

  @Test
  public void sampleWithH263() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(subtitlesParsedDuringExtraction),
        "media/ts/sample_h263.ts",
        simulationConfig);
  }

  @Test
  public void sampleWithH264() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(subtitlesParsedDuringExtraction),
        "media/ts/sample_h264.ts",
        simulationConfig);
  }

  @Test
  public void sampleWithH264AndIFramesOnly() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(subtitlesParsedDuringExtraction),
        "media/ts/sample_h264_iframes_only.ts",
        simulationConfig);
  }

  @Test
  public void sampleWithH264AndMpegAudio() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(subtitlesParsedDuringExtraction),
        "media/ts/sample_h264_mpeg_audio.ts",
        simulationConfig);
  }

  @Test
  public void sampleWithH264NoAccessUnitDelimiters() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(
            subtitlesParsedDuringExtraction,
            MODE_SINGLE_PMT,
            new TimestampAdjuster(0),
            new DefaultTsPayloadReaderFactory(FLAG_DETECT_ACCESS_UNITS)),
        "media/ts/sample_h264_no_access_unit_delimiters.ts",
        simulationConfig);
  }

  @Test
  public void sampleWithH264AndDtsAudio() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(
            subtitlesParsedDuringExtraction,
            MODE_SINGLE_PMT,
            new TimestampAdjuster(0),
            new DefaultTsPayloadReaderFactory(FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)),
        "media/ts/sample_h264_dts_audio.ts",
        simulationConfig);
  }

  @Test
  public void sampleWithH265() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(subtitlesParsedDuringExtraction),
        "media/ts/sample_h265.ts",
        simulationConfig);
  }

  @Test
  public void sampleWithH265RpsPred() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(subtitlesParsedDuringExtraction),
        "media/ts/sample_h265_rps_pred.ts",
        simulationConfig);
  }

  @Test
  public void sampleWithScte35() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(subtitlesParsedDuringExtraction),
        "media/ts/sample_scte35.ts",
        new ExtractorAsserts.AssertionConfig.Builder()
            .setDeduplicateConsecutiveFormats(true)
            .build(),
        simulationConfig);
  }

  @Test
  public void sampleWithAit() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(subtitlesParsedDuringExtraction),
        "media/ts/sample_ait.ts",
        new ExtractorAsserts.AssertionConfig.Builder()
            .setDeduplicateConsecutiveFormats(true)
            .build(),
        simulationConfig);
  }

  @Test
  public void sampleWithDts() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(subtitlesParsedDuringExtraction),
        "media/ts/sample_dts.ts",
        simulationConfig);
  }

  @Test
  public void sampleWithDtsExpress() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(subtitlesParsedDuringExtraction),
        "media/ts/sample_dts_express.ts",
        simulationConfig);
  }

  @Test
  public void sampleWithDtsHdMa() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(
            subtitlesParsedDuringExtraction,
            MODE_SINGLE_PMT,
            new TimestampAdjuster(0),
            new DefaultTsPayloadReaderFactory(FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)),
        "media/ts/sample_dts_hd_ma.ts",
        simulationConfig);
  }

  @Test
  public void sampleWithDtsHdAcrossPesBoundary() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(
            subtitlesParsedDuringExtraction,
            MODE_SINGLE_PMT,
            new TimestampAdjuster(0),
            new DefaultTsPayloadReaderFactory(FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)),
        "media/ts/sample_dts_hd_across_pes.ts",
        simulationConfig);
  }

  @Test
  public void sampleWithDtsUhd() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(subtitlesParsedDuringExtraction),
        "media/ts/sample_dts_uhd.ts",
        simulationConfig);
  }

  @Test
  public void sampleWithAc3() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(subtitlesParsedDuringExtraction),
        "media/ts/sample_ac3.ts",
        simulationConfig);
  }

  @Test
  public void sampleWithAc4() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(subtitlesParsedDuringExtraction),
        "media/ts/sample_ac4.ts",
        simulationConfig);
  }

  @Test
  public void sampleWithEac3() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(subtitlesParsedDuringExtraction),
        "media/ts/sample_eac3.ts",
        simulationConfig);
  }

  @Test
  public void sampleWithEac3joc() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(subtitlesParsedDuringExtraction),
        "media/ts/sample_eac3joc.ts",
        simulationConfig);
  }

  @Test
  public void sampleWithLatm() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(subtitlesParsedDuringExtraction),
        "media/ts/sample_latm.ts",
        simulationConfig);
  }

  @Test
  public void streamWithJunkData() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(subtitlesParsedDuringExtraction),
        "media/ts/sample_with_junk",
        simulationConfig);
  }

  @Test
  public void streamWithAudioTypeOfMpegAudio() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(subtitlesParsedDuringExtraction),
        "media/ts/sample_mpeg_audio_with_audio_type.ts",
        simulationConfig);
  }

  @Test
  public void sampleWithMpeghBlCicp1Single() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(subtitlesParsedDuringExtraction),
        "media/ts/sample_mpegh_bl_cicp1_single.ts",
        simulationConfig);
  }

  @Test
  public void sampleWithMpeghLcBlCicp1Single() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(subtitlesParsedDuringExtraction),
        "media/ts/sample_mpegh_lcbl_cicp1_single.ts",
        simulationConfig);
  }

  @Test
  public void sampleWithMpeghBlConfigChangeSingle() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(subtitlesParsedDuringExtraction),
        "media/ts/sample_mpegh_bl_configchange_single.ts",
        simulationConfig);
  }

  @Test
  public void sampleWithMpeghLcBlConfigChangeSingle() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(subtitlesParsedDuringExtraction),
        "media/ts/sample_mpegh_lcbl_configchange_single.ts",
        simulationConfig);
  }

  @Test
  public void sampleWithMpeghBlCicp1Multi() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(subtitlesParsedDuringExtraction),
        "media/ts/sample_mpegh_bl_cicp1_multi.ts",
        simulationConfig);
  }

  @Test
  public void sampleWithMpeghLcBlCicp1Multi() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(subtitlesParsedDuringExtraction),
        "media/ts/sample_mpegh_lcbl_cicp1_multi.ts",
        simulationConfig);
  }

  @Test
  public void sampleWithMpeghBlConfigChangeMulti() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(subtitlesParsedDuringExtraction),
        "media/ts/sample_mpegh_bl_configchange_multi.ts",
        simulationConfig);
  }

  @Test
  public void sampleWithMpeghLcBlConfigChangeMulti() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(subtitlesParsedDuringExtraction),
        "media/ts/sample_mpegh_lcbl_configchange_multi.ts",
        simulationConfig);
  }

  @Test
  public void sampleWithMpeghBlCicp1Cont() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(subtitlesParsedDuringExtraction),
        "media/ts/sample_mpegh_bl_cicp1_cont.ts",
        simulationConfig);
  }

  @Test
  public void sampleWithMpeghLcBlCicp1Cont() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(subtitlesParsedDuringExtraction),
        "media/ts/sample_mpegh_lcbl_cicp1_cont.ts",
        simulationConfig);
  }

  @Test
  public void sampleWithMpeghBlConfigChangeCont() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(subtitlesParsedDuringExtraction),
        "media/ts/sample_mpegh_bl_configchange_cont.ts",
        simulationConfig);
  }

  @Test
  public void sampleWithMpeghLcBlConfigChangeCont() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(subtitlesParsedDuringExtraction),
        "media/ts/sample_mpegh_lcbl_configchange_cont.ts",
        simulationConfig);
  }

  @Test
  public void sampleWithMpeghBlContSetRaiUnsetDai() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(subtitlesParsedDuringExtraction),
        "media/ts/sample_mpegh_bl_cicp1_cont_setrai_unsetdai.ts",
        simulationConfig);
  }

  @Test
  public void sampleWithMpeghBlContSplitHeader() throws Exception {
    ExtractorAsserts.assertBehavior(
        getExtractorFactory(subtitlesParsedDuringExtraction),
        "media/ts/sample_mpegh_bl_cicp1_cont_splitheader.ts",
        simulationConfig);
  }

  @Test
  public void customPesReader() throws Exception {
    CustomTsPayloadReaderFactory factory = new CustomTsPayloadReaderFactory(true, false);
    TsExtractor tsExtractor =
        (TsExtractor)
            getExtractorFactory(
                    subtitlesParsedDuringExtraction,
                    MODE_MULTI_PMT,
                    new TimestampAdjuster(0),
                    factory)
                .create();
    FakeExtractorInput input =
        new FakeExtractorInput.Builder()
            .setData(
                TestUtil.getByteArray(
                    ApplicationProvider.getApplicationContext(),
                    "media/ts/sample_h262_mpeg_audio.ts"))
            .setSimulateIOErrors(false)
            .setSimulateUnknownLength(false)
            .setSimulatePartialReads(false)
            .build();
    FakeExtractorOutput output = new FakeExtractorOutput();
    tsExtractor.init(output);
    PositionHolder seekPositionHolder = new PositionHolder();
    int readResult = Extractor.RESULT_CONTINUE;
    while (readResult != Extractor.RESULT_END_OF_INPUT) {
      readResult = tsExtractor.read(input, seekPositionHolder);
      if (readResult == Extractor.RESULT_SEEK) {
        input.setPosition((int) seekPositionHolder.position);
      }
    }
    CustomEsReader reader = factory.esReader;
    assertThat(reader.packetsRead).isEqualTo(2);
    TrackOutput trackOutput = reader.getTrackOutput();
    assertThat(trackOutput == output.trackOutputs.get(257 /* PID of audio track. */)).isTrue();
    assertThat(((FakeTrackOutput) trackOutput).lastFormat)
        .isEqualTo(
            new Format.Builder()
                .setId("1/257")
                .setSampleMimeType("mime")
                .setLanguage("und")
                .build());
    tsExtractor.release();
  }

  @Test
  public void customInitialSectionReader() throws Exception {
    CustomTsPayloadReaderFactory factory = new CustomTsPayloadReaderFactory(false, true);
    TsExtractor tsExtractor =
        (TsExtractor)
            getExtractorFactory(
                    subtitlesParsedDuringExtraction,
                    MODE_MULTI_PMT,
                    new TimestampAdjuster(0),
                    factory)
                .create();
    FakeExtractorInput input =
        new FakeExtractorInput.Builder()
            .setData(
                TestUtil.getByteArray(
                    ApplicationProvider.getApplicationContext(), "media/ts/sample_with_sdt.ts"))
            .setSimulateIOErrors(false)
            .setSimulateUnknownLength(false)
            .setSimulatePartialReads(false)
            .build();
    tsExtractor.init(new FakeExtractorOutput());
    PositionHolder seekPositionHolder = new PositionHolder();
    int readResult = Extractor.RESULT_CONTINUE;
    while (readResult != Extractor.RESULT_END_OF_INPUT) {
      readResult = tsExtractor.read(input, seekPositionHolder);
      if (readResult == Extractor.RESULT_SEEK) {
        input.setPosition((int) seekPositionHolder.position);
      }
    }
    assertThat(factory.sdtReader.consumedSdts).isEqualTo(2);
    tsExtractor.release();
  }

  @Test
  public void pmtDolbyVisionDescriptorOnPrivateDataStream_outputsDolbyVisionFormat()
      throws Exception {
    // Profile 5 signalled by a "DOVI" registration descriptor followed by a Dolby Vision video
    // descriptor (0xB0) on a private data PES (0x06) stream type.
    byte[] ts =
        buildSyntheticTs(
            /* streamType= */ 0x06,
            DOVI_REGISTRATION_DESCRIPTOR,
            buildDolbyVisionDescriptor(
                /* dvVersionMajor= */ 1,
                /* profile= */ 5,
                /* rpuPresentFlag= */ true,
                /* blPresentFlag= */ true));

    FakeExtractorOutput output = extractTs(ts);

    assertThat(output.numberOfTracks).isEqualTo(1);
    FakeTrackOutput trackOutput = output.trackOutputs.get(VIDEO_PID);
    assertThat(trackOutput).isNotNull();
    assertThat(trackOutput.lastFormat).isNotNull();
    assertThat(trackOutput.lastFormat.sampleMimeType).isEqualTo(MimeTypes.VIDEO_DOLBY_VISION);
    assertThat(trackOutput.lastFormat.codecs).isEqualTo("dvhe.05.00");
    // The fed SPS carries no VUI colour signalling, so profile 5 colour info is synthesized.
    assertThat(trackOutput.lastFormat.colorInfo).isNotNull();
    assertThat(trackOutput.lastFormat.colorInfo.colorSpace).isEqualTo(C.COLOR_SPACE_BT2020);
    assertThat(trackOutput.lastFormat.colorInfo.colorRange).isEqualTo(C.COLOR_RANGE_LIMITED);
    assertThat(trackOutput.lastFormat.colorInfo.colorTransfer).isEqualTo(C.COLOR_TRANSFER_ST2084);
  }

  @Test
  public void pmtDolbyVisionDescriptorOnHevcStream_outputsDolbyVisionFormat() throws Exception {
    // Profile 8 signalled by only a Dolby Vision video descriptor (0xB0) on a HEVC (0x24) stream
    // type, without a registration descriptor.
    byte[] ts =
        buildSyntheticTs(
            /* streamType= */ 0x24,
            buildDolbyVisionDescriptor(
                /* dvVersionMajor= */ 1,
                /* profile= */ 8,
                /* rpuPresentFlag= */ true,
                /* blPresentFlag= */ true));

    FakeExtractorOutput output = extractTs(ts);

    assertThat(output.numberOfTracks).isEqualTo(1);
    FakeTrackOutput trackOutput = output.trackOutputs.get(VIDEO_PID);
    assertThat(trackOutput).isNotNull();
    assertThat(trackOutput.lastFormat).isNotNull();
    assertThat(trackOutput.lastFormat.sampleMimeType).isEqualTo(MimeTypes.VIDEO_DOLBY_VISION);
    assertThat(trackOutput.lastFormat.codecs).isEqualTo("dvhe.08.00");
    // Cross-compatible profiles keep the SPS-signalled colour info, which stays unset here.
    assertThat(trackOutput.lastFormat.colorInfo).isNotNull();
    assertThat(trackOutput.lastFormat.colorInfo.colorSpace).isEqualTo(Format.NO_VALUE);
    assertThat(trackOutput.lastFormat.colorInfo.colorRange).isEqualTo(Format.NO_VALUE);
    assertThat(trackOutput.lastFormat.colorInfo.colorTransfer).isEqualTo(Format.NO_VALUE);
  }

  @Test
  public void pmtDolbyVisionDescriptorWithUnsupportedVersionMajor_dropsTrack() throws Exception {
    byte[] ts =
        buildSyntheticTs(
            /* streamType= */ 0x06,
            buildDolbyVisionDescriptor(
                /* dvVersionMajor= */ 2,
                /* profile= */ 5,
                /* rpuPresentFlag= */ true,
                /* blPresentFlag= */ true));

    FakeExtractorOutput output = extractTs(ts);

    assertThat(output.numberOfTracks).isEqualTo(0);
    assertThat(output.trackOutputs.get(VIDEO_PID)).isNull();
  }

  @Test
  public void pmtDolbyVisionDescriptorWithoutRpuPresentFlag_dropsTrack() throws Exception {
    byte[] ts =
        buildSyntheticTs(
            /* streamType= */ 0x06,
            buildDolbyVisionDescriptor(
                /* dvVersionMajor= */ 1,
                /* profile= */ 5,
                /* rpuPresentFlag= */ false,
                /* blPresentFlag= */ true));

    FakeExtractorOutput output = extractTs(ts);

    assertThat(output.numberOfTracks).isEqualTo(0);
    assertThat(output.trackOutputs.get(VIDEO_PID)).isNull();
  }

  @Test
  public void pmtDolbyVisionDescriptorWithoutBlPresentFlag_dropsTrack() throws Exception {
    // An enhancement-layer-only PID, which cannot be decoded on its own and is ignored.
    byte[] ts =
        buildSyntheticTs(
            /* streamType= */ 0x06,
            buildDolbyVisionDescriptor(
                /* dvVersionMajor= */ 1,
                /* profile= */ 5,
                /* rpuPresentFlag= */ true,
                /* blPresentFlag= */ false));

    FakeExtractorOutput output = extractTs(ts);

    assertThat(output.numberOfTracks).isEqualTo(0);
    assertThat(output.trackOutputs.get(VIDEO_PID)).isNull();
  }

  @Test
  public void pmtDolbyVisionProfile9OnAvcStream_outputsPlainAvcTrack() throws Exception {
    // Profile 9 (AVC base layer) on an AVC (0x1B) stream type is exposed as plain H.264, without a
    // Dolby Vision configuration.
    byte[] ts =
        buildSyntheticTs(
            /* streamType= */ 0x1B,
            buildDolbyVisionDescriptor(
                /* dvVersionMajor= */ 1,
                /* profile= */ 9,
                /* rpuPresentFlag= */ true,
                /* blPresentFlag= */ true));

    FakeExtractorOutput output = extractTs(ts);

    assertThat(output.numberOfTracks).isEqualTo(1);
    FakeTrackOutput trackOutput = output.trackOutputs.get(VIDEO_PID);
    assertThat(trackOutput).isNotNull();
    // The fed parameter sets are H.265 NAL units that the H.264 reader does not recognize, so no
    // format is emitted for the H.264 track.
    assertThat(trackOutput.lastFormat).isNull();
  }

  @Test
  public void pmtDolbyVisionDescriptorOnPrivateSectionsStream_dropsTrack() throws Exception {
    // The 0xB0 tag lies in the ISO/IEC 13818-1 user private range and must not be trusted on
    // stream types other than HEVC (0x24), AVC (0x1B) and private data PES (0x06).
    byte[] ts =
        buildSyntheticTs(
            /* streamType= */ 0x05,
            buildDolbyVisionDescriptor(
                /* dvVersionMajor= */ 1,
                /* profile= */ 5,
                /* rpuPresentFlag= */ true,
                /* blPresentFlag= */ true));

    FakeExtractorOutput output = extractTs(ts);

    assertThat(output.numberOfTracks).isEqualTo(0);
    assertThat(output.trackOutputs.get(VIDEO_PID)).isNull();
  }

  // Synthetic TS support for the Dolby Vision PMT tests above.

  private static final int TS_PACKET_SIZE = 188;
  private static final int PAT_PID = 0x0000;
  private static final int PMT_PID = 0x1000;
  private static final int VIDEO_PID = 0x101;

  // CRC-32 with polynomial 0x04C11DB7 in most significant bit first order, as required for PSI
  // sections. See ISO/IEC 13818-1, Annex A.
  private static final int[] CRC32_MSBF_TABLE = buildCrc32MsbfTable();

  private static int[] buildCrc32MsbfTable() {
    int[] table = new int[256];
    for (int i = 0; i < 256; i++) {
      int crc = i << 24;
      for (int bit = 0; bit < 8; bit++) {
        crc = ((crc & 0x80000000) != 0) ? ((crc << 1) ^ 0x04C11DB7) : (crc << 1);
      }
      table[i] = crc;
    }
    return table;
  }

  private static int crc32(byte[] data, int length) {
    int crc = 0xFFFFFFFF;
    for (int i = 0; i < length; i++) {
      crc = (crc << 8) ^ CRC32_MSBF_TABLE[((crc >>> 24) ^ (data[i] & 0xFF)) & 0xFF];
    }
    return crc;
  }

  /** Appends the CRC-32 of {@code section[0:offset]} to {@code section} at {@code offset}. */
  private static void appendCrc32(byte[] section, int offset) {
    int crc = crc32(section, offset);
    section[offset] = (byte) ((crc >>> 24) & 0xFF);
    section[offset + 1] = (byte) ((crc >>> 16) & 0xFF);
    section[offset + 2] = (byte) ((crc >>> 8) & 0xFF);
    section[offset + 3] = (byte) (crc & 0xFF);
  }

  private static final byte[] DOVI_REGISTRATION_DESCRIPTOR =
      createByteArray(0x05, 0x04, 0x44, 0x4F, 0x56, 0x49); // registration_descriptor("DOVI")

  /**
   * Builds a Dolby Vision video descriptor (tag 0xB0) carrying the given version major, profile
   * and RPU/base-layer presence flags, at level 0.
   */
  private static byte[] buildDolbyVisionDescriptor(
      int dvVersionMajor, int profile, boolean rpuPresentFlag, boolean blPresentFlag) {
    int flagBits = (rpuPresentFlag ? 0x04 : 0x00) | (blPresentFlag ? 0x01 : 0x00);
    return createByteArray(
        0xB0,
        0x04,
        dvVersionMajor,
        0x00, // dv_version_minor
        profile << 1, // dv_profile(7), dv_level(6) bit 5
        flagBits); // dv_level(6) bits 4:0, rpu_present_flag, el_present_flag, bl_present_flag
  }

  /**
   * Builds a TS stream consisting of a PAT, a PMT with a single ES entry carrying the given
   * descriptors on {@code streamType}, and one video PES packet with an H.265 access unit.
   */
  private static byte[] buildSyntheticTs(int streamType, byte[]... esDescriptors) {
    return Bytes.concat(
        psiPacket(PAT_PID, buildPatSection()),
        psiPacket(PMT_PID, buildPmtSection(streamType, esDescriptors)),
        pesPacket(VIDEO_PID, buildH265AccessUnit()));
  }

  private static byte[] buildPatSection() {
    byte[] section = new byte[12 + 4];
    section[0] = 0x00; // table_id
    section[1] = (byte) 0xB0; // section_syntax_indicator, reserved bits, section_length
    section[2] = 0x0D; // section_length
    section[3] = 0x00;
    section[4] = 0x01; // transport_stream_id
    section[5] = (byte) 0xC1; // reserved, version_number, current_next_indicator
    section[6] = 0x00; // section_number
    section[7] = 0x00; // last_section_number
    section[8] = 0x00;
    section[9] = 0x01; // program_number = 1
    section[10] = (byte) 0xF0; // reserved, program_map_PID = PMT_PID
    section[11] = 0x00;
    appendCrc32(section, 12);
    return section;
  }

  private static byte[] buildPmtSection(int streamType, byte[]... esDescriptors) {
    int esDescriptorsLength = 0;
    for (byte[] descriptor : esDescriptors) {
      esDescriptorsLength += descriptor.length;
    }
    // Bytes after the section_length field: program_number (2), version/current_next (1),
    // section_number (1), last_section_number (1), PCR_PID (2), program_info_length (2), the ES
    // entry (5 + esDescriptorsLength), and the CRC_32 (4).
    int sectionLength = 18 + esDescriptorsLength;
    byte[] section = new byte[3 + sectionLength];
    section[0] = 0x02; // table_id
    section[1] = (byte) (0xB0 | ((sectionLength >> 8) & 0x0F));
    section[2] = (byte) (sectionLength & 0xFF);
    section[3] = 0x00;
    section[4] = 0x01; // program_number = 1
    section[5] = (byte) 0xC1; // reserved, version_number, current_next_indicator
    section[6] = 0x00; // section_number
    section[7] = 0x00; // last_section_number
    section[8] = (byte) 0xFF;
    section[9] = (byte) 0xFF; // reserved, PCR_PID = 0x1FFF
    section[10] = (byte) 0xF0; // reserved, program_info_length = 0
    section[11] = 0x00;
    int position = 12;
    section[position++] = (byte) streamType;
    section[position++] = (byte) 0xE1; // reserved, elementary_PID = VIDEO_PID
    section[position++] = 0x01;
    section[position++] = (byte) (0xF0 | ((esDescriptorsLength >> 8) & 0x0F));
    section[position++] = (byte) (esDescriptorsLength & 0xFF); // ES_info_length
    for (byte[] descriptor : esDescriptors) {
      System.arraycopy(descriptor, 0, section, position, descriptor.length);
      position += descriptor.length;
    }
    appendCrc32(section, position);
    return section;
  }

  /**
   * Returns VPS, SPS, PPS and an IDR slice as one Annex B access unit. The parameter sets are taken
   * from media/ts/sample_h265_rps_pred.ts and match the ones used by {@link H265ReaderTest}.
   */
  private static byte[] buildH265AccessUnit() {
    byte[] startCode = createByteArray(0x00, 0x00, 0x01);
    byte[] vps =
        createByteArray(
            0x40, 0x01, 0x0C, 0x07, 0xFF, 0xFF, 0x01, 0x60, 0x00, 0x00, 0x03, 0x00, 0x90, 0x00,
            0x00, 0x03, 0x00, 0x00, 0x03, 0x00, 0x3F, 0x00, 0x00, 0x99, 0x4C, 0xE6, 0x24, 0xC5,
            0x81, 0x20);
    byte[] sps =
        createByteArray(
            0x42, 0x01, 0x07, 0x01, 0x60, 0x00, 0x00, 0x03, 0x00, 0x90, 0x00, 0x00, 0x03, 0x00,
            0x00, 0x03, 0x00, 0x3F, 0x00, 0x00, 0xA0, 0x07, 0x32, 0x00, 0xAC, 0x72, 0x56, 0x26,
            0x65, 0x33, 0x98, 0x93, 0x17, 0x24, 0x8A, 0x41, 0xD2, 0x47, 0x4F, 0x3E, 0x3F, 0xC4,
            0xFC, 0xB3, 0xF9, 0xFB, 0x3C, 0xBF, 0xD7, 0xCC, 0x87, 0x92, 0xFF, 0x9F, 0xB3, 0xCB,
            0xFD, 0x7C, 0x12, 0x3A, 0x79, 0xF1, 0xFE, 0x27, 0xE5, 0x3F, 0xCF, 0xDF, 0xCB, 0x3F,
            0x5F, 0x32, 0x1E, 0x4B, 0xFE, 0x7E, 0xCF, 0x2F, 0xF5, 0xF1, 0xFF, 0xE1, 0x56, 0x81,
            0x56, 0x60, 0x20, 0x00, 0x00, 0x7D, 0x00, 0x00, 0x07, 0x53, 0x01);
    byte[] pps = createByteArray(0x44, 0x01, 0xC1, 0x14, 0xA1, 0xC2, 0xB4, 0x13, 0x24);
    // IDR_W_RADL slice segment with first_slice_segment_in_pic_flag set, followed by arbitrary
    // slice data.
    byte[] idrSlice = createByteArray(0x26, 0x01, 0x80, 0x11, 0x22);
    return Bytes.concat(
        startCode, vps, startCode, sps, startCode, pps, startCode, idrSlice);
  }

  /** Wraps a PSI section in a TS packet, preceded by a pointer_field of 0x00. */
  private static byte[] psiPacket(int pid, byte[] section) {
    byte[] payload = new byte[section.length + 1];
    payload[0] = 0x00; // pointer_field
    System.arraycopy(section, 0, payload, 1, section.length);
    return tsPacket(pid, /* payloadUnitStartIndicator= */ true, payload);
  }

  private static byte[] pesPacket(int pid, byte[] esData) {
    int pesPacketLength = 3 /* header flags */ + 5 /* PTS */ + esData.length;
    byte[] payload = new byte[14 + esData.length];
    payload[0] = 0x00;
    payload[1] = 0x00;
    payload[2] = 0x01;
    payload[3] = (byte) 0xE0; // video stream_id
    payload[4] = (byte) ((pesPacketLength >> 8) & 0xFF);
    payload[5] = (byte) (pesPacketLength & 0xFF);
    payload[6] = (byte) 0x84; // '10', data_alignment_indicator set
    payload[7] = (byte) 0x80; // PTS_DTS_flags = '10'
    payload[8] = 0x05; // PES_header_data_length
    long pts = 90_000;
    payload[9] = (byte) (0x21 | ((pts >> 29) & 0x0E));
    payload[10] = (byte) ((pts >> 22) & 0xFF);
    payload[11] = (byte) (0x01 | ((pts >> 14) & 0xFE));
    payload[12] = (byte) ((pts >> 7) & 0xFF);
    payload[13] = (byte) (0x01 | ((pts << 1) & 0xFE));
    System.arraycopy(esData, 0, payload, 14, esData.length);
    return tsPacket(pid, /* payloadUnitStartIndicator= */ true, payload);
  }

  private static byte[] tsPacket(int pid, boolean payloadUnitStartIndicator, byte[] payload) {
    checkArgument(payload.length <= TS_PACKET_SIZE - 4);
    byte[] packet = new byte[TS_PACKET_SIZE];
    packet[0] = TsExtractor.TS_SYNC_BYTE;
    packet[1] = (byte) ((payloadUnitStartIndicator ? 0x40 : 0x00) | ((pid >> 8) & 0x1F));
    packet[2] = (byte) (pid & 0xFF);
    packet[3] = (byte) (0x10 /* payload only, no adaptation field */ | 0 /* continuity_counter */);
    System.arraycopy(payload, 0, packet, 4, payload.length);
    // Fill the remainder of the packet with stuffing bytes.
    for (int i = 4 + payload.length; i < TS_PACKET_SIZE; i++) {
      packet[i] = (byte) 0xFF;
    }
    return packet;
  }

  private FakeExtractorOutput extractTs(byte[] ts) throws Exception {
    TsExtractor tsExtractor =
        (TsExtractor)
            getExtractorFactory(/* subtitlesParsedDuringExtraction= */ true).create();
    FakeExtractorInput input = new FakeExtractorInput.Builder().setData(ts).build();
    FakeExtractorOutput output = new FakeExtractorOutput();
    tsExtractor.init(output);
    PositionHolder seekPositionHolder = new PositionHolder();
    int readResult = Extractor.RESULT_CONTINUE;
    while (readResult != Extractor.RESULT_END_OF_INPUT) {
      readResult = tsExtractor.read(input, seekPositionHolder);
      if (readResult == Extractor.RESULT_SEEK) {
        input.setPosition((int) seekPositionHolder.position);
      }
    }
    tsExtractor.release();
    return output;
  }

  private static ExtractorAsserts.ExtractorFactory getExtractorFactory(
      boolean subtitlesParsedDuringExtraction) {
    return getExtractorFactory(
        subtitlesParsedDuringExtraction,
        MODE_SINGLE_PMT,
        new TimestampAdjuster(0),
        new DefaultTsPayloadReaderFactory(0));
  }

  private static ExtractorAsserts.ExtractorFactory getExtractorFactory(
      boolean subtitlesParsedDuringExtraction,
      @TsExtractor.Mode int mode,
      TimestampAdjuster timestampAdjuster,
      TsPayloadReader.Factory payloadReaderFactory) {
    SubtitleParser.Factory subtitleParserFactory;
    @TsExtractor.Flags int flags;
    if (subtitlesParsedDuringExtraction) {
      subtitleParserFactory = new DefaultSubtitleParserFactory();
      flags = 0;
    } else {
      subtitleParserFactory = SubtitleParser.Factory.UNSUPPORTED;
      flags = FLAG_EMIT_RAW_SUBTITLE_DATA;
    }
    return () ->
        new TsExtractor(
            mode,
            flags,
            subtitleParserFactory,
            timestampAdjuster,
            payloadReaderFactory,
            DEFAULT_TIMESTAMP_SEARCH_BYTES);
  }

  private static final class CustomTsPayloadReaderFactory implements TsPayloadReader.Factory {

    private final boolean provideSdtReader;
    private final boolean provideCustomEsReader;
    private final TsPayloadReader.Factory defaultFactory;
    private CustomEsReader esReader;
    private SdtSectionReader sdtReader;

    public CustomTsPayloadReaderFactory(boolean provideCustomEsReader, boolean provideSdtReader) {
      this.provideCustomEsReader = provideCustomEsReader;
      this.provideSdtReader = provideSdtReader;
      defaultFactory = new DefaultTsPayloadReaderFactory();
    }

    @Override
    public SparseArray<TsPayloadReader> createInitialPayloadReaders() {
      if (provideSdtReader) {
        assertThat(sdtReader).isNull();
        SparseArray<TsPayloadReader> mapping = new SparseArray<>();
        sdtReader = new SdtSectionReader();
        mapping.put(17, new SectionReader(sdtReader));
        return mapping;
      } else {
        return defaultFactory.createInitialPayloadReaders();
      }
    }

    @Override
    @Nullable
    public TsPayloadReader createPayloadReader(int streamType, EsInfo esInfo) {
      if (provideCustomEsReader && streamType == 3) {
        esReader = new CustomEsReader(esInfo.language);
        return new PesReader(esReader);
      } else {
        return defaultFactory.createPayloadReader(streamType, esInfo);
      }
    }
  }

  private static final class CustomEsReader implements ElementaryStreamReader {

    private final String language;
    private TrackOutput output;
    public int packetsRead = 0;

    public CustomEsReader(String language) {
      this.language = language;
    }

    @Override
    public void seek() {}

    @Override
    public void createTracks(ExtractorOutput extractorOutput, TrackIdGenerator idGenerator) {
      idGenerator.generateNewId();
      output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_UNKNOWN);
      output.format(
          new Format.Builder()
              .setId(idGenerator.getFormatId())
              .setSampleMimeType("mime")
              .setLanguage(language)
              .build());
    }

    @Override
    public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {}

    @Override
    public void consume(ParsableByteArray data) {}

    @Override
    public void packetFinished() {
      packetsRead++;
    }

    public TrackOutput getTrackOutput() {
      return output;
    }
  }

  private static final class SdtSectionReader implements SectionPayloadReader {

    private int consumedSdts;

    @Override
    public void init(
        TimestampAdjuster timestampAdjuster,
        ExtractorOutput extractorOutput,
        TrackIdGenerator idGenerator) {
      // Do nothing.
    }

    @Override
    public void consume(ParsableByteArray sectionData) {
      // table_id(8), section_syntax_indicator(1), reserved_future_use(1), reserved(2),
      // section_length(12), transport_stream_id(16), reserved(2), version_number(5),
      // current_next_indicator(1), section_number(8), last_section_number(8),
      // original_network_id(16), reserved_future_use(8)
      sectionData.skipBytes(11);
      // Start of the service loop.
      assertThat(sectionData.readUnsignedShort()).isEqualTo(0x5566 /* arbitrary service id */);
      // reserved_future_use(6), EIT_schedule_flag(1), EIT_present_following_flag(1)
      sectionData.skipBytes(1);
      // Assert there is only one service.
      // Remove running_status(3), free_CA_mode(1) from the descriptors_loop_length with the mask.
      assertThat(sectionData.readUnsignedShort() & 0xFFF).isEqualTo(sectionData.bytesLeft());
      while (sectionData.bytesLeft() > 0) {
        int descriptorTag = sectionData.readUnsignedByte();
        int descriptorLength = sectionData.readUnsignedByte();
        if (descriptorTag == 72 /* service descriptor */) {
          assertThat(sectionData.readUnsignedByte()).isEqualTo(1); // Service type: Digital TV.
          int serviceProviderNameLength = sectionData.readUnsignedByte();
          assertThat(sectionData.readString(serviceProviderNameLength)).isEqualTo("Some provider");
          int serviceNameLength = sectionData.readUnsignedByte();
          assertThat(sectionData.readString(serviceNameLength)).isEqualTo("Some Channel");
        } else {
          sectionData.skipBytes(descriptorLength);
        }
      }
      consumedSdts++;
    }
  }
}
