/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer;

import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.DrmInitData;
import androidx.media3.common.DrmInitData.SchemeData;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.trackselection.TrackSelectorResult;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.test.utils.ExoPlayerTestRunner;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.FakeRenderer;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.FakeTrackSelection;
import androidx.media3.test.utils.TestExoPlayerBuilder;
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for the playback capability queries exposed by {@link ExoPlayer}. */
@RunWith(AndroidJUnit4.class)
@UnstableApi
public final class ExoPlayerCapabilityQueriesTest {

  private final Context context = ApplicationProvider.getApplicationContext();

  @Rule
  public ShadowMediaCodecConfig mediaCodecConfig =
      ShadowMediaCodecConfig.withAllDefaultSupportedCodecs();

  @Test
  public void getVideoEffectsSupport_withoutVideoTrack_returnsUnavailable() {
    Renderer[] renderers = {new MediaCodecVideoRenderer.Builder(context).build()};
    TrackSelectorResult trackSelectorResult =
        trackSelectorResult(
            new RendererConfiguration(false),
            new FakeTrackSelection(new TrackGroup(ExoPlayerTestRunner.AUDIO_FORMAT)));

    int support = ExoPlayerImpl.getVideoEffectsSupport(renderers, trackSelectorResult);

    assertThat(support).isEqualTo(ExoPlayer.VIDEO_EFFECTS_UNAVAILABLE);
  }

  @Test
  public void getVideoEffectsSupport_withDisabledVideoRenderer_returnsUnavailable() {
    Renderer[] renderers = {new MediaCodecVideoRenderer.Builder(context).build()};
    TrackSelectorResult trackSelectorResult =
        new TrackSelectorResult(
            new RendererConfiguration[] {null},
            new ExoTrackSelection[] {null},
            /* tracks= */ Tracks.EMPTY,
            /* info= */ null);

    int support = ExoPlayerImpl.getVideoEffectsSupport(renderers, trackSelectorResult);

    assertThat(support).isEqualTo(ExoPlayer.VIDEO_EFFECTS_UNAVAILABLE);
  }

  @Test
  public void getVideoEffectsSupport_withNonMediaCodecVideoRenderer_returnsUnsupportedRenderer() {
    Renderer[] renderers = {new FakeRenderer(C.TRACK_TYPE_VIDEO)};
    TrackSelectorResult trackSelectorResult =
        trackSelectorResult(
            new RendererConfiguration(false),
            new FakeTrackSelection(new TrackGroup(ExoPlayerTestRunner.VIDEO_FORMAT)));

    int support = ExoPlayerImpl.getVideoEffectsSupport(renderers, trackSelectorResult);

    assertThat(support).isEqualTo(ExoPlayer.VIDEO_EFFECTS_UNSUPPORTED_RENDERER);
  }

  @Test
  public void getVideoEffectsSupport_withTunneling_returnsUnsupportedTunneling() {
    Renderer[] renderers = {new MediaCodecVideoRenderer.Builder(context).build()};
    TrackSelectorResult trackSelectorResult =
        trackSelectorResult(
            new RendererConfiguration(/* tunneling= */ true),
            new FakeTrackSelection(new TrackGroup(ExoPlayerTestRunner.VIDEO_FORMAT)));

    int support = ExoPlayerImpl.getVideoEffectsSupport(renderers, trackSelectorResult);

    assertThat(support).isEqualTo(ExoPlayer.VIDEO_EFFECTS_UNSUPPORTED_TUNNELING);
  }

  @Test
  public void getVideoEffectsSupport_withDrmInitData_returnsUnsupportedDrm() {
    Renderer[] renderers = {new MediaCodecVideoRenderer.Builder(context).build()};
    Format drmFormat =
        ExoPlayerTestRunner.VIDEO_FORMAT
            .buildUpon()
            .setDrmInitData(
                new DrmInitData(
                    new SchemeData(C.WIDEVINE_UUID, MimeTypes.VIDEO_MP4, new byte[1])))
            .build();
    TrackSelectorResult trackSelectorResult =
        trackSelectorResult(
            new RendererConfiguration(false),
            new FakeTrackSelection(new TrackGroup(drmFormat)));

    int support = ExoPlayerImpl.getVideoEffectsSupport(renderers, trackSelectorResult);

    assertThat(support).isEqualTo(ExoPlayer.VIDEO_EFFECTS_UNSUPPORTED_DRM);
  }

  @Test
  public void getVideoEffectsSupport_withHdrTransfer_returnsUnsupportedFormat() {
    Renderer[] renderers = {new MediaCodecVideoRenderer.Builder(context).build()};
    Format hdrFormat =
        ExoPlayerTestRunner.VIDEO_FORMAT
            .buildUpon()
            .setColorInfo(
                new ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_HLG).build())
            .build();
    TrackSelectorResult trackSelectorResult =
        trackSelectorResult(
            new RendererConfiguration(false), new FakeTrackSelection(new TrackGroup(hdrFormat)));

    int support = ExoPlayerImpl.getVideoEffectsSupport(renderers, trackSelectorResult);

    assertThat(support).isEqualTo(ExoPlayer.VIDEO_EFFECTS_UNSUPPORTED_FORMAT);
  }

  @Test
  public void getVideoEffectsSupport_withDolbyVision_returnsUnsupportedFormat() {
    Renderer[] renderers = {new MediaCodecVideoRenderer.Builder(context).build()};
    Format dolbyVisionFormat =
        ExoPlayerTestRunner.VIDEO_FORMAT
            .buildUpon()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .build();
    TrackSelectorResult trackSelectorResult =
        trackSelectorResult(
            new RendererConfiguration(false),
            new FakeTrackSelection(new TrackGroup(dolbyVisionFormat)));

    int support = ExoPlayerImpl.getVideoEffectsSupport(renderers, trackSelectorResult);

    assertThat(support).isEqualTo(ExoPlayer.VIDEO_EFFECTS_UNSUPPORTED_FORMAT);
  }

  @Test
  public void getVideoEffectsSupport_withH264_returnsSupported() {
    Renderer[] renderers = {new MediaCodecVideoRenderer.Builder(context).build()};
    TrackSelectorResult trackSelectorResult =
        trackSelectorResult(
            new RendererConfiguration(false),
            new FakeTrackSelection(new TrackGroup(ExoPlayerTestRunner.VIDEO_FORMAT)));

    int support = ExoPlayerImpl.getVideoEffectsSupport(renderers, trackSelectorResult);

    assertThat(support).isEqualTo(ExoPlayer.VIDEO_EFFECTS_SUPPORTED);
  }

  @Test
  public void isVideoEffectsFormatSupported_withH264_returnsTrue() {
    assertThat(MediaCodecVideoRenderer.isVideoEffectsFormatSupported(
            ExoPlayerTestRunner.VIDEO_FORMAT))
        .isTrue();
  }

  @Test
  public void isVideoEffectsFormatSupported_withDrmInitData_returnsFalse() {
    Format drmFormat =
        ExoPlayerTestRunner.VIDEO_FORMAT
            .buildUpon()
            .setDrmInitData(
                new DrmInitData(
                    new SchemeData(C.WIDEVINE_UUID, MimeTypes.VIDEO_MP4, new byte[1])))
            .build();

    assertThat(MediaCodecVideoRenderer.isVideoEffectsFormatSupported(drmFormat)).isFalse();
  }

  @Test
  public void isVideoEffectsFormatSupported_withDolbyVision_returnsFalse() {
    Format dolbyVisionFormat =
        ExoPlayerTestRunner.VIDEO_FORMAT
            .buildUpon()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .build();

    assertThat(MediaCodecVideoRenderer.isVideoEffectsFormatSupported(dolbyVisionFormat))
        .isFalse();
  }

  @Test
  public void isVideoEffectsFormatSupported_withHdrTransfer_returnsFalse() {
    Format hdrFormat =
        ExoPlayerTestRunner.VIDEO_FORMAT
            .buildUpon()
            .setColorInfo(
                new ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_HLG).build())
            .build();

    assertThat(MediaCodecVideoRenderer.isVideoEffectsFormatSupported(hdrFormat)).isFalse();
  }

  @Test
  public void audioProcessingSupport_reflectsAudioTrackLifecycle() throws Exception {
    RenderersFactory renderersFactory =
        (eventHandler,
            videoRendererEventListener,
            audioRendererEventListener,
            textRendererOutput,
            metadataRendererOutput) ->
            new Renderer[] {
              new MediaCodecAudioRenderer(
                  context,
                  MediaCodecSelector.DEFAULT,
                  /* enableDecoderFallback= */ false,
                  eventHandler,
                  audioRendererEventListener,
                  new DefaultAudioSink.Builder(context).build())
            };
    ExoPlayer player =
        new TestExoPlayerBuilder(context).setRenderersFactory(renderersFactory).build();

    assertThat(player.getAudioProcessingSupport())
        .isEqualTo(ExoPlayer.AUDIO_PROCESSING_UNAVAILABLE);
    assertThat(player.isSkipSilenceSupported()).isFalse();

    Timeline timeline = new FakeTimeline(/* windowCount= */ 1);
    MediaSource mediaSource =
        new FakeMediaSource(timeline, ExoPlayerTestRunner.AUDIO_FORMAT);
    player.setMediaSource(mediaSource);
    player.prepare();
    player.play();
    advance(player).untilState(Player.STATE_ENDED);

    assertThat(player.getAudioProcessingSupport()).isEqualTo(ExoPlayer.AUDIO_PROCESSING_SUPPORTED);
    assertThat(player.isSkipSilenceSupported()).isTrue();

    player.release();
  }

  private static TrackSelectorResult trackSelectorResult(
      RendererConfiguration configuration, ExoTrackSelection selection) {
    return new TrackSelectorResult(
        new RendererConfiguration[] {configuration},
        new ExoTrackSelection[] {selection},
        /* tracks= */ Tracks.EMPTY,
        /* info= */ null);
  }
}
