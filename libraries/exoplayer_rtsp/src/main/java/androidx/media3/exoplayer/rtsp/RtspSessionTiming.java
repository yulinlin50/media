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

import static androidx.media3.common.util.Util.castNonNull;
import static androidx.media3.exoplayer.rtsp.RtspMessageUtil.checkManifestExpression;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.Util;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represent the timing (RTSP Normal Playback Time format) of an RTSP session.
 *
 * <p>Currently NPT and absolute UTC clock ranges are supported. See RFC2326 Section 3.6.
 */
/* package */ final class RtspSessionTiming {
  /** The default session timing starting from 0.000 and indefinite length, effectively live. */
  public static final RtspSessionTiming DEFAULT =
      new RtspSessionTiming(/* startTimeMs= */ 0, /* stopTimeMs= */ C.TIME_UNSET);

  // We only support npt=xxx-[xxx], but not npt=-xxx. See RFC2326 Section 3.6.
  // Supports both npt= and npt: identifier.
  private static final Pattern NPT_RANGE_PATTERN =
      Pattern.compile("npt[:=]([.\\d]+|now)\\s?-\\s?([.\\d]+)?");
  private static final String START_TIMING_NTP_FORMAT = "npt=%.3f-";

  // Clock range, RFC2326 Section 3.6: "clock=yyyyMMddTHHmmssZ-yyyyMMddTHHMMSSZ", UTC only. Both
  // endpoints are mandatory: a replay window without an end cannot be mapped onto a seekable
  // timeline.
  private static final Pattern CLOCK_RANGE_PATTERN =
      Pattern.compile("clock[:=](\\d{8}T\\d{6}Z)-(\\d{8}T\\d{6}Z)");
  private static final String CLOCK_RANGE_HEADER_FORMAT = "clock=%s-%s";
  // Strict 16-character UTC wall clock ("20260930T120000Z"); parsing is locale-independent.
  private static final DateTimeFormatter CLOCK_TIME_FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

  private static final long LIVE_START_TIME = 0;

  /** Parses an SDP range attribute (RFC2326 Section 3.6). */
  public static RtspSessionTiming parseTiming(String sdpRangeAttribute) throws ParserException {
    long startTimeMs;
    long stopTimeMs;
    Matcher matcher = NPT_RANGE_PATTERN.matcher(sdpRangeAttribute);
    checkManifestExpression(matcher.matches(), /* message= */ sdpRangeAttribute);

    @Nullable String startTimeString = matcher.group(1);
    checkManifestExpression(startTimeString != null, /* message= */ sdpRangeAttribute);
    if (castNonNull(startTimeString).equals("now")) {
      startTimeMs = LIVE_START_TIME;
    } else {
      startTimeMs = (long) (Float.parseFloat(startTimeString) * C.MILLIS_PER_SECOND);
    }

    @Nullable String stopTimeString = matcher.group(2);
    if (stopTimeString != null) {
      try {
        stopTimeMs = (long) (Float.parseFloat(stopTimeString) * C.MILLIS_PER_SECOND);
      } catch (NumberFormatException e) {
        throw ParserException.createForMalformedManifest(stopTimeString, e);
      }
      checkManifestExpression(stopTimeMs >= startTimeMs, /* message= */ sdpRangeAttribute);
    } else {
      stopTimeMs = C.TIME_UNSET;
    }

    return new RtspSessionTiming(startTimeMs, stopTimeMs);
  }

  /** Gets a Range RTSP header for an RTSP PLAY request. */
  public static String getOffsetStartTimeTiming(long offsetStartTimeMs) {
    double offsetStartTimeSec = (double) offsetStartTimeMs / C.MILLIS_PER_SECOND;
    return Util.formatInvariant(START_TIMING_NTP_FORMAT, offsetStartTimeSec);
  }

  /**
   * Parses a strict UTC clock time string ({@code yyyyMMddTHHmmssZ}, RFC2326 Section 3.6) into
   * epoch milliseconds. Anything else (including non-UTC offsets, missing Z, or impossible dates)
   * is rejected: callers build these strings themselves, so a malformed value is a bug.
   */
  public static long parseClockTimeMs(String clockTimeString) throws ParserException {
    try {
      return OffsetDateTime.parse(clockTimeString, CLOCK_TIME_FORMAT).toInstant().toEpochMilli();
    } catch (DateTimeParseException e) {
      throw ParserException.createForMalformedManifest(clockTimeString, e);
    }
  }

  /** Formats epoch milliseconds as a strict UTC clock time string (RFC2326 Section 3.6). */
  public static String formatClockTimeMs(long epochMs) {
    return CLOCK_TIME_FORMAT.format(Instant.ofEpochMilli(epochMs));
  }

  /**
   * Parses a clock range override ({@code clock=startTime-endTime} as it appears in a PLAY Range
   * header) into {@code [startEpochMs, endEpochMs]}, or returns {@code null} for {@code null}.
   *
   * @throws IllegalArgumentException When the override is non-null but malformed or unordered: the
   *     override is built by the caller, so a malformed value is a caller bug and must fail fast.
   */
  @Nullable
  public static long[] parseClockRangeOverride(@Nullable String clockRangeOverride)
      throws ParserException {
    if (clockRangeOverride == null) {
      return null;
    }
    Matcher matcher = CLOCK_RANGE_PATTERN.matcher(clockRangeOverride);
    if (!matcher.matches()) {
      throw ParserException.createForMalformedManifest(clockRangeOverride, /* cause= */ null);
    }
    long startEpochMs = parseClockTimeMs(castNonNull(matcher.group(1)));
    long endEpochMs = parseClockTimeMs(castNonNull(matcher.group(2)));
    if (endEpochMs < startEpochMs) {
      throw ParserException.createForMalformedManifest(clockRangeOverride, /* cause= */ null);
    }
    return new long[] {startEpochMs, endEpochMs};
  }

  /** Formats a clock range override as it appears in a PLAY Range header. */
  public static String formatClockRange(long startEpochMs, long endEpochMs) {
    return Util.formatInvariant(
        CLOCK_RANGE_HEADER_FORMAT, formatClockTimeMs(startEpochMs), formatClockTimeMs(endEpochMs));
  }

  /**
   * Creates the VOD-like timing of a clock range: the session starts at 0 and lasts {@code
   * endEpochMs - startEpochMs}, which makes the timeline seekable.
   */
  public static RtspSessionTiming forClockRange(long startEpochMs, long endEpochMs) {
    return new RtspSessionTiming(/* startTimeMs= */ 0, endEpochMs - startEpochMs);
  }

  /**
   * Parses the Range header of a PLAY <b>response</b>. {@code npt=} ranges go through the regular
   * parser; a {@code clock=} range is the server confirming the requested replay window and is
   * VOD-ified into that window. With a malformed clock echo and a caller-provided override, the
   * override window wins (the server did start playing); without an override the malformed header
   * is a manifest error.
   */
  public static RtspSessionTiming parsePlayResponseTiming(
      String rangeHeader, long overrideStartEpochMs, long overrideEndEpochMs)
      throws ParserException {
    Matcher matcher = CLOCK_RANGE_PATTERN.matcher(rangeHeader);
    if (matcher.matches()) {
      return forClockRange(
          parseClockTimeMs(castNonNull(matcher.group(1))), parseClockTimeMs(castNonNull(matcher.group(2))));
    }
    if (rangeHeader.startsWith("clock")
        && overrideStartEpochMs != C.TIME_UNSET
        && overrideEndEpochMs != C.TIME_UNSET) {
      return forClockRange(overrideStartEpochMs, overrideEndEpochMs);
    }
    return parseTiming(rangeHeader);
  }

  /**
   * Resolves the effective session timing of a DESCRIBE response. When a clock range override is
   * set and the SDP declares a live session, the timeline is VOD-ified into the override's seekable
   * replay window; otherwise the SDP timing wins.
   */
  public static RtspSessionTiming resolveWithClockRangeOverride(
      RtspSessionTiming sdpTiming, @Nullable long[] clockRangeOverrideEpochMs) {
    if (clockRangeOverrideEpochMs == null || !sdpTiming.isLive()) {
      return sdpTiming;
    }
    return forClockRange(clockRangeOverrideEpochMs[0], clockRangeOverrideEpochMs[1]);
  }

  /**
   * The start time of this session, in milliseconds. When playing a live session, the start time is
   * always zero.
   */
  public final long startTimeMs;

  /**
   * The stop time of the session, in milliseconds, or {@link C#TIME_UNSET} when the stop time is
   * not set, for example when playing a live session.
   */
  public final long stopTimeMs;

  private RtspSessionTiming(long startTimeMs, long stopTimeMs) {
    this.startTimeMs = startTimeMs;
    this.stopTimeMs = stopTimeMs;
  }

  /** Tests whether the timing is live. */
  public boolean isLive() {
    return stopTimeMs == C.TIME_UNSET;
  }

  /** Gets the session duration in milliseconds. */
  public long getDurationMs() {
    return stopTimeMs - startTimeMs;
  }
}
