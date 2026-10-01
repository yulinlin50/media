/*
 * Copyright 2021 The Android Open Source Project
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
import static org.junit.Assert.assertThrows;

import androidx.media3.common.C;
import androidx.media3.common.ParserException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link RtspSessionTiming}. */
@RunWith(AndroidJUnit4.class)
public class RtspSessionTimingTest {
  @Test
  public void parseTiming_withNowLiveTiming() throws Exception {
    RtspSessionTiming sessionTiming = RtspSessionTiming.parseTiming("npt=now-");
    assertThat(sessionTiming.getDurationMs()).isEqualTo(C.TIME_UNSET);
    assertThat(sessionTiming.isLive()).isTrue();
  }

  @Test
  public void parseTiming_withZeroLiveTiming() throws Exception {
    RtspSessionTiming sessionTiming = RtspSessionTiming.parseTiming("npt=0-");
    assertThat(sessionTiming.getDurationMs()).isEqualTo(C.TIME_UNSET);
    assertThat(sessionTiming.isLive()).isTrue();
  }

  @Test
  public void parseTiming_withDecimalZeroLiveTiming() throws Exception {
    RtspSessionTiming sessionTiming = RtspSessionTiming.parseTiming("npt=0.000-");
    assertThat(sessionTiming.getDurationMs()).isEqualTo(C.TIME_UNSET);
    assertThat(sessionTiming.isLive()).isTrue();
  }

  @Test
  public void parseTiming_withRangeTiming() throws Exception {
    RtspSessionTiming sessionTiming = RtspSessionTiming.parseTiming("npt=0.000-32.054");
    assertThat(sessionTiming.getDurationMs()).isEqualTo(32054);
    assertThat(sessionTiming.isLive()).isFalse();
  }

  @Test
  public void parseTiming_withRangeTimingAndColonSeparator() throws Exception {
    RtspSessionTiming sessionTiming = RtspSessionTiming.parseTiming("npt:0.000-32.054");
    assertThat(sessionTiming.getDurationMs()).isEqualTo(32054);
    assertThat(sessionTiming.isLive()).isFalse();
  }

  @Test
  public void parseTiming_withInvalidRangeTiming_throwsParserException() {
    assertThrows(ParserException.class, () -> RtspSessionTiming.parseTiming("npt=10.000-2.054"));
  }

  @Test
  public void parseClockTimeMs_validUtcString() throws Exception {
    long expected = java.time.Instant.parse("2026-09-30T12:00:00Z").toEpochMilli();
    assertThat(RtspSessionTiming.parseClockTimeMs("20260930T120000Z")).isEqualTo(expected);
  }

  @Test
  public void parseClockTimeMs_rejectsMissingZ() {
    assertThrows(ParserException.class, () -> RtspSessionTiming.parseClockTimeMs("20260930T120000"));
  }

  @Test
  public void parseClockTimeMs_rejectsShortString() {
    assertThrows(ParserException.class, () -> RtspSessionTiming.parseClockTimeMs("2026093T12000Z"));
  }

  @Test
  public void parseClockTimeMs_rejectsImpossibleDate() {
    // Strict formatter: month 13 and hour 25 must not silently roll over.
    assertThrows(
        ParserException.class, () -> RtspSessionTiming.parseClockTimeMs("20261330T250000Z"));
  }

  @Test
  public void formatClockTimeMs_roundTrip() throws Exception {
    long epochMs = java.time.Instant.parse("2026-09-30T12:34:56Z").toEpochMilli();
    assertThat(RtspSessionTiming.formatClockTimeMs(epochMs)).isEqualTo("20260930T123456Z");
    assertThat(RtspSessionTiming.parseClockTimeMs("20260930T123456Z")).isEqualTo(epochMs);
  }

  @Test
  public void parseClockRangeOverride_nullReturnsNull() throws Exception {
    assertThat(RtspSessionTiming.parseClockRangeOverride(null)).isNull();
  }

  @Test
  public void parseClockRangeOverride_validRange() throws Exception {
    long[] range =
        RtspSessionTiming.parseClockRangeOverride("clock=20260930T120000Z-20260930T130000Z");
    assertThat(range).hasLength(2);
    assertThat(range[0])
        .isEqualTo(java.time.Instant.parse("2026-09-30T12:00:00Z").toEpochMilli());
    assertThat(range[1])
        .isEqualTo(java.time.Instant.parse("2026-09-30T13:00:00Z").toEpochMilli());
  }

  @Test
  public void parseClockRangeOverride_malformedThrows() {
    assertThrows(
        ParserException.class,
        () -> RtspSessionTiming.parseClockRangeOverride("clock=20260930T120000Z-"));
    assertThrows(
        ParserException.class, () -> RtspSessionTiming.parseClockRangeOverride("npt=0-3600"));
  }

  @Test
  public void parseClockRangeOverride_endBeforeStartThrows() {
    assertThrows(
        ParserException.class,
        () -> RtspSessionTiming.parseClockRangeOverride("clock=20260930T130000Z-20260930T120000Z"));
  }

  @Test
  public void forClockRange_isVodWindowStartingAtZero() {
    RtspSessionTiming timing =
        RtspSessionTiming.forClockRange(
            java.time.Instant.parse("2026-09-30T12:00:00Z").toEpochMilli(),
            java.time.Instant.parse("2026-09-30T13:00:00Z").toEpochMilli());
    assertThat(timing.isLive()).isFalse();
    assertThat(timing.startTimeMs).isEqualTo(0);
    assertThat(timing.getDurationMs()).isEqualTo(3_600_000);
  }

  @Test
  public void resolveWithClockRangeOverride_liveSdpVodifies() {
    long start = java.time.Instant.parse("2026-09-30T12:00:00Z").toEpochMilli();
    long end = java.time.Instant.parse("2026-09-30T13:00:00Z").toEpochMilli();
    RtspSessionTiming resolved =
        RtspSessionTiming.resolveWithClockRangeOverride(
            RtspSessionTiming.DEFAULT, new long[] {start, end});
    assertThat(resolved.isLive()).isFalse();
    assertThat(resolved.getDurationMs()).isEqualTo(3_600_000);
  }

  @Test
  public void resolveWithClockRangeOverride_withoutOverrideKeepsSdpTiming() {
    RtspSessionTiming resolved =
        RtspSessionTiming.resolveWithClockRangeOverride(RtspSessionTiming.DEFAULT, null);
    assertThat(resolved.isLive()).isTrue();
  }

  @Test
  public void resolveWithClockRangeOverride_vodSdpKeepsOwnTiming() throws Exception {
    RtspSessionTiming sdpTiming = RtspSessionTiming.parseTiming("npt=0-50.46");
    RtspSessionTiming resolved =
        RtspSessionTiming.resolveWithClockRangeOverride(sdpTiming, new long[] {0, 3_600_000});
    assertThat(resolved.getDurationMs()).isEqualTo(50_460);
  }
}
