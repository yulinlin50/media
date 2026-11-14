/*
 * Copyright (C) 2026 The Android Open Source Project
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
package androidx.media3.exoplayer;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.exoplayer.analytics.PlayerId;
import org.junit.Test;

/** Unit tests for the per-player target-buffer warning episode tracker. */
public final class TargetBufferWarningTrackerTest {

  @Test
  public void logsImmediatelyThenAtMostEveryFiveSecondsAndSummarizesOnExit() {
    DefaultLoadControl.TargetBufferWarningTracker tracker =
        new DefaultLoadControl.TargetBufferWarningTracker();
    PlayerId playerId = new PlayerId("player");

    DefaultLoadControl.TargetBufferWarningTracker.Event first = tracker.onWarning(playerId, 100);
    assertThat(first).isNotNull();
    assertThat(first.occurrenceCount).isEqualTo(1);
    assertThat(first.durationMs).isEqualTo(0);

    assertThat(tracker.onWarning(playerId, 1_000)).isNull();
    assertThat(tracker.onWarning(playerId, 4_999)).isNull();

    DefaultLoadControl.TargetBufferWarningTracker.Event interval =
        tracker.onWarning(playerId, 5_100);
    assertThat(interval).isNotNull();
    assertThat(interval.occurrenceCount).isEqualTo(4);
    assertThat(interval.durationMs).isEqualTo(5_000);

    DefaultLoadControl.TargetBufferWarningTracker.Event summary = tracker.finish(playerId, 6_300);
    assertThat(summary).isNotNull();
    assertThat(summary.occurrenceCount).isEqualTo(4);
    assertThat(summary.durationMs).isEqualTo(6_200);
    assertThat(tracker.hasEpisode(playerId)).isFalse();
    assertThat(tracker.finish(playerId, 7_000)).isNull();
  }

  @Test
  public void playerIdsHaveIndependentEpisodesAndExitStartsANewEpisode() {
    DefaultLoadControl.TargetBufferWarningTracker tracker =
        new DefaultLoadControl.TargetBufferWarningTracker();
    PlayerId firstPlayer = new PlayerId("first");
    PlayerId secondPlayer = new PlayerId("second");

    assertThat(tracker.onWarning(firstPlayer, 10).occurrenceCount).isEqualTo(1);
    assertThat(tracker.onWarning(secondPlayer, 20).occurrenceCount).isEqualTo(1);
    assertThat(tracker.onWarning(firstPlayer, 30)).isNull();

    DefaultLoadControl.TargetBufferWarningTracker.Event firstSummary = tracker.finish(firstPlayer, 40);
    assertThat(firstSummary.occurrenceCount).isEqualTo(2);
    assertThat(tracker.hasEpisode(firstPlayer)).isFalse();
    assertThat(tracker.hasEpisode(secondPlayer)).isTrue();

    assertThat(tracker.onWarning(firstPlayer, 100).occurrenceCount).isEqualTo(1);
    assertThat(tracker.onWarning(secondPlayer, 5_020).occurrenceCount).isEqualTo(2);
  }
}
