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

import static androidx.media3.exoplayer.rtsp.RtspRequest.METHOD_DESCRIBE;
import static androidx.media3.exoplayer.rtsp.RtspRequest.METHOD_PLAY;
import static androidx.media3.exoplayer.rtsp.RtspRequest.METHOD_SETUP;
import static androidx.media3.exoplayer.rtsp.RtspRequest.METHOD_TEARDOWN;
import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;
import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.play;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static java.lang.Math.min;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Timeline;
import androidx.media3.common.Player;
import androidx.media3.common.Player.Listener;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.test.utils.DumpFileAsserts;
import androidx.media3.test.utils.FakeClock;
import androidx.media3.test.utils.robolectric.CapturingRenderersFactory;
import androidx.media3.test.utils.robolectric.PlaybackOutput;
import androidx.media3.test.utils.robolectric.RobolectricUtil;
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig;
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.SocketFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/** Playback testing for RTSP. */
@Config(sdk = 29)
@RunWith(AndroidJUnit4.class)
public final class RtspPlaybackTest {

  private static final long DEFAULT_TIMEOUT_MS = 8000;
  private static final String SESSION_DESCRIPTION =
      "v=0\r\n"
          + "o=- 1606776316530225 1 IN IP4 127.0.0.1\r\n"
          + "s=Exoplayer test\r\n"
          + "t=0 0\r\n";

  private Context applicationContext;
  private CapturingRenderersFactory capturingRenderersFactory;
  private Clock clock;
  private RtpPacketStreamDump aacRtpPacketStreamDump;
  // ExoPlayer does not support extracting MP4A-LATM RTP payload at the moment.
  private RtpPacketStreamDump mpeg2tsRtpPacketStreamDump;
  // Despite the file name, the upstream mpeg2ts dump actually carries an unsupported MP4 payload;
  // mp2t is the real MPEG-2 TS dump used by the MP2T-over-RTP coverage.
  private RtpPacketStreamDump mp2tRtpPacketStreamDump;
  private RtspServer rtspServer;

  @Rule
  public ShadowMediaCodecConfig mediaCodecConfig =
      ShadowMediaCodecConfig.withAllDefaultSupportedCodecs();

  @Before
  public void setUp() throws Exception {
    applicationContext = ApplicationProvider.getApplicationContext();
    clock = new FakeClock(/* isAutoAdvancing= */ true);
    capturingRenderersFactory = new CapturingRenderersFactory(applicationContext, clock);
    aacRtpPacketStreamDump = RtspTestUtils.readRtpPacketStreamDump("media/rtsp/aac-dump.json");
    mpeg2tsRtpPacketStreamDump =
        RtspTestUtils.readRtpPacketStreamDump("media/rtsp/mpeg2ts-dump.json");
    mp2tRtpPacketStreamDump = RtspTestUtils.readRtpPacketStreamDump("media/rtsp/mp2t-dump.json");
  }

  @After
  public void tearDown() {
    Util.closeQuietly(rtspServer);
  }

  @Test
  public void prepare_withSupportedTrack_playsTrackUntilEnded() throws Exception {
    FakeUdpDataSourceRtpDataChannel fakeRtpDataChannel = new FakeUdpDataSourceRtpDataChannel();
    RtpDataChannel.Factory rtpDataChannelFactory = (trackId) -> fakeRtpDataChannel;
    ResponseProvider responseProvider =
        new ResponseProvider(
            clock,
            ImmutableList.of(aacRtpPacketStreamDump, mpeg2tsRtpPacketStreamDump),
            fakeRtpDataChannel,
            RtspMessageUtil.DEFAULT_RTSP_TIMEOUT_MS,
            /* optionsRequestCounter= */ Optional.empty());
    rtspServer = new RtspServer(responseProvider);
    ExoPlayer player = createExoPlayer(rtspServer.startAndGetPortNumber(), rtpDataChannelFactory);

    PlaybackOutput playbackOutput = PlaybackOutput.register(player, capturingRenderersFactory);
    player.prepare();
    player.play();
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);
    player.release();

    // Only setup the supported track (aac).
    assertThat(responseProvider.getDumpsForSetUpTracks()).containsExactly(aacRtpPacketStreamDump);
    DumpFileAsserts.assertOutput(applicationContext, playbackOutput, "playbackdumps/rtsp/aac.dump");
  }

  @Test
  public void prepare_setupAndPlayRequests_useUpstreamWireFormat() throws Exception {
    FakeUdpDataSourceRtpDataChannel fakeRtpDataChannel = new FakeUdpDataSourceRtpDataChannel();
    RtpDataChannel.Factory rtpDataChannelFactory = (trackId) -> fakeRtpDataChannel;
    ResponseProvider responseProvider =
        new ResponseProvider(
            clock,
            ImmutableList.of(aacRtpPacketStreamDump, mpeg2tsRtpPacketStreamDump),
            fakeRtpDataChannel,
            RtspMessageUtil.DEFAULT_RTSP_TIMEOUT_MS,
            /* optionsRequestCounter= */ Optional.empty());
    rtspServer = new RtspServer(responseProvider);
    ExoPlayer player = createExoPlayer(rtspServer.startAndGetPortNumber(), rtpDataChannelFactory);

    player.prepare();
    player.play();
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_READY);
    player.release();

    // Regression (review RTSP-001): every SETUP must carry the Transport header.
    List<RtspRequest> setupRequests = requestsOfMethod(METHOD_SETUP);
    assertThat(setupRequests).isNotEmpty();
    for (RtspRequest request : setupRequests) {
      assertThat(request.headers.get(RtspHeaders.TRANSPORT)).isNotNull();
    }
    // Regression (review SES-3/RTSP-006): PLAY always carries a well-formed open-ended NPT
    // range, including at offset zero.
    List<RtspRequest> playRequests = requestsOfMethod(METHOD_PLAY);
    assertThat(playRequests).isNotEmpty();
    for (RtspRequest request : playRequests) {
      assertThat(request.headers.get(RtspHeaders.RANGE)).isEqualTo("npt=0.000-");
    }
  }

  @Test
  public void release_afterPlaybackStarted_sendsTeardown() throws Exception {
    FakeUdpDataSourceRtpDataChannel fakeRtpDataChannel = new FakeUdpDataSourceRtpDataChannel();
    RtpDataChannel.Factory rtpDataChannelFactory = (trackId) -> fakeRtpDataChannel;
    ResponseProvider responseProvider =
        new ResponseProvider(
            clock,
            ImmutableList.of(aacRtpPacketStreamDump, mpeg2tsRtpPacketStreamDump),
            fakeRtpDataChannel,
            RtspMessageUtil.DEFAULT_RTSP_TIMEOUT_MS,
            /* optionsRequestCounter= */ Optional.empty());
    rtspServer = new RtspServer(responseProvider);
    ExoPlayer player = createExoPlayer(rtspServer.startAndGetPortNumber(), rtpDataChannelFactory);

    player.prepare();
    player.play();
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_READY);
    player.release();

    // Regression (review RTSP-004/SES-2): release() must TEARDOWN the server session so the
    // server stops pushing RTP packets and frees up the session.
    RobolectricUtil.runMainLooperUntil(() -> !requestsOfMethod(METHOD_TEARDOWN).isEmpty());
  }

  @Test
  public void prepare_noSupportedTrack_throwsPreparationError() throws Exception {
    FakeUdpDataSourceRtpDataChannel fakeRtpDataChannel = new FakeUdpDataSourceRtpDataChannel();
    RtpDataChannel.Factory rtpDataChannelFactory = (trackId) -> fakeRtpDataChannel;
    rtspServer =
        new RtspServer(
            new ResponseProvider(
                clock,
                ImmutableList.of(mpeg2tsRtpPacketStreamDump),
                fakeRtpDataChannel,
                RtspMessageUtil.DEFAULT_RTSP_TIMEOUT_MS,
                /* optionsRequestCounter= */ Optional.empty()));
    ExoPlayer player = createExoPlayer(rtspServer.startAndGetPortNumber(), rtpDataChannelFactory);

    AtomicReference<Throwable> playbackError = new AtomicReference<>();
    player.prepare();
    player.addListener(
        new Listener() {
          @Override
          public void onPlayerError(PlaybackException error) {
            playbackError.set(error);
          }
        });
    RobolectricUtil.runMainLooperUntil(() -> playbackError.get() != null);
    player.release();

    assertThat(playbackError.get()).hasCauseThat().hasMessageThat().contains("No playable track.");
  }

  @Test
  public void prepare_withMp2tTrack_playsTrackUntilEnded() throws Exception {
    FakeUdpDataSourceRtpDataChannel fakeRtpDataChannel = new FakeUdpDataSourceRtpDataChannel();
    RtpDataChannel.Factory rtpDataChannelFactory = (trackId) -> fakeRtpDataChannel;
    ResponseProvider responseProvider =
        new ResponseProvider(
            clock,
            ImmutableList.of(mp2tRtpPacketStreamDump),
            fakeRtpDataChannel,
            RtspMessageUtil.DEFAULT_RTSP_TIMEOUT_MS,
            /* optionsRequestCounter= */ Optional.empty());
    rtspServer = new RtspServer(responseProvider);
    ExoPlayer player = createExoPlayer(rtspServer.startAndGetPortNumber(), rtpDataChannelFactory);

    player.prepare();
    player.play();
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);
    player.release();

    // MP2T (RFC 2250 static payload type 33) is now a supported payload: the RTP track is SETUP
    // and its TS content plays through the embedded TS extractor.
    assertThat(responseProvider.getDumpsForSetUpTracks())
        .containsExactly(mp2tRtpPacketStreamDump);
  }

  @Test
  public void prepare_describeRespondsSmil_followsHopAndPlays() throws Exception {
    FakeUdpDataSourceRtpDataChannel fakeRtpDataChannel = new FakeUdpDataSourceRtpDataChannel();
    RtpDataChannel.Factory rtpDataChannelFactory = (trackId) -> fakeRtpDataChannel;
    AtomicInteger describeCount = new AtomicInteger();
    ResponseProvider responseProvider =
        new ResponseProvider(
            clock,
            ImmutableList.of(aacRtpPacketStreamDump),
            fakeRtpDataChannel,
            RtspMessageUtil.DEFAULT_RTSP_TIMEOUT_MS,
            /* optionsRequestCounter= */ Optional.empty()) {
          @Override
          public RtspResponse getDescribeResponse(Uri requestedUri, RtspHeaders headers) {
            if (describeCount.getAndIncrement() == 0) {
              // The first DESCRIBE answers with SMIL pointing at a path on the same server.
              String smilBody =
                  "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                      + "<smil><body><video src=\""
                      + requestedUri.toString()
                      + "/stream.sdp\"/></body></smil>";
              return new RtspResponse(
                  /* status= */ 200,
                  new RtspHeaders.Builder()
                      .add(RtspHeaders.CONTENT_TYPE, "application/smil")
                      .add(
                          RtspHeaders.CONTENT_LENGTH,
                          String.valueOf(smilBody.getBytes(RtspMessageChannel.CHARSET).length))
                      .build(),
                  /* messageBody= */ smilBody);
            }
            return super.getDescribeResponse(requestedUri, headers);
          }
        };
    rtspServer = new RtspServer(responseProvider);
    ExoPlayer player = createExoPlayer(rtspServer.startAndGetPortNumber(), rtpDataChannelFactory);

    player.prepare();
    player.play();
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);
    player.release();

    assertThat(describeCount.get()).isEqualTo(2);
    assertThat(responseProvider.getDumpsForSetUpTracks())
        .containsExactly(aacRtpPacketStreamDump);
  }

  @Test
  public void prepare_smilRedirectLoop_failsWithRedirectLimit() throws Exception {
    FakeUdpDataSourceRtpDataChannel fakeRtpDataChannel = new FakeUdpDataSourceRtpDataChannel();
    RtpDataChannel.Factory rtpDataChannelFactory = (trackId) -> fakeRtpDataChannel;
    AtomicInteger describeCount = new AtomicInteger();
    ResponseProvider responseProvider =
        new ResponseProvider(
            clock,
            ImmutableList.of(aacRtpPacketStreamDump),
            fakeRtpDataChannel,
            RtspMessageUtil.DEFAULT_RTSP_TIMEOUT_MS,
            /* optionsRequestCounter= */ Optional.empty()) {
          @Override
          public RtspResponse getDescribeResponse(Uri requestedUri, RtspHeaders headers) {
            describeCount.incrementAndGet();
            // Every DESCRIBE answers with SMIL pointing back at itself: a redirect loop.
            String smilBody =
                "<smil><body><video src=\"" + requestedUri.toString() + "\"/></body></smil>";
            return new RtspResponse(
                /* status= */ 200,
                new RtspHeaders.Builder()
                    .add(RtspHeaders.CONTENT_TYPE, "application/smil")
                    .add(
                        RtspHeaders.CONTENT_LENGTH,
                        String.valueOf(smilBody.getBytes(RtspMessageChannel.CHARSET).length))
                    .build(),
                /* messageBody= */ smilBody);
          }
        };
    rtspServer = new RtspServer(responseProvider);
    ExoPlayer player = createExoPlayer(rtspServer.startAndGetPortNumber(), rtpDataChannelFactory);

    AtomicReference<Throwable> playbackError = new AtomicReference<>();
    player.prepare();
    player.addListener(
        new Listener() {
          @Override
          public void onPlayerError(PlaybackException error) {
            playbackError.set(error);
          }
        });
    RobolectricUtil.runMainLooperUntil(() -> playbackError.get() != null);
    player.release();

    // 1 initial DESCRIBE + 10 hops, then the client refuses to follow any further.
    assertThat(describeCount.get()).isEqualTo(11);
    assertThat(playbackError.get())
        .hasCauseThat()
        .hasMessageThat()
        .contains("REDIRECT_LIMIT_REACHED");
  }

  @Test
  public void prepare_smilPointsToDifferentAuthority_failsWithRedirectUnsupported()
      throws Exception {
    FakeUdpDataSourceRtpDataChannel fakeRtpDataChannel = new FakeUdpDataSourceRtpDataChannel();
    RtpDataChannel.Factory rtpDataChannelFactory = (trackId) -> fakeRtpDataChannel;
    AtomicInteger describeCount = new AtomicInteger();
    ResponseProvider responseProvider =
        new ResponseProvider(
            clock,
            ImmutableList.of(aacRtpPacketStreamDump),
            fakeRtpDataChannel,
            RtspMessageUtil.DEFAULT_RTSP_TIMEOUT_MS,
            /* optionsRequestCounter= */ Optional.empty()) {
          @Override
          public RtspResponse getDescribeResponse(Uri requestedUri, RtspHeaders headers) {
            describeCount.incrementAndGet();
            String smilBody =
                "<smil><body><video src=\"rtsp://other.example.com:8554/stream\"/></body></smil>";
            return new RtspResponse(
                /* status= */ 200,
                new RtspHeaders.Builder()
                    .add(RtspHeaders.CONTENT_TYPE, "application/smil")
                    .add(
                        RtspHeaders.CONTENT_LENGTH,
                        String.valueOf(smilBody.getBytes(RtspMessageChannel.CHARSET).length))
                    .build(),
                /* messageBody= */ smilBody);
          }
        };
    rtspServer = new RtspServer(responseProvider);
    ExoPlayer player = createExoPlayer(rtspServer.startAndGetPortNumber(), rtpDataChannelFactory);

    AtomicReference<Throwable> playbackError = new AtomicReference<>();
    player.prepare();
    player.addListener(
        new Listener() {
          @Override
          public void onPlayerError(PlaybackException error) {
            playbackError.set(error);
          }
        });
    RobolectricUtil.runMainLooperUntil(() -> playbackError.get() != null);
    player.release();

    // The SMIL hop is a redirect: crossing to another authority is rejected, not followed.
    assertThat(describeCount.get()).isEqualTo(1);
    assertThat(playbackError.get())
        .hasCauseThat()
        .hasMessageThat()
        .contains("REDIRECT_UNSUPPORTED");
  }

  @Test
  public void prepare_describeRedirectsSameAuthority_reconnectsAndPlays() throws Exception {
    FakeUdpDataSourceRtpDataChannel fakeRtpDataChannel = new FakeUdpDataSourceRtpDataChannel();
    RtpDataChannel.Factory rtpDataChannelFactory = (trackId) -> fakeRtpDataChannel;
    AtomicInteger describeCount = new AtomicInteger();
    ResponseProvider responseProvider =
        new ResponseProvider(
            clock,
            ImmutableList.of(aacRtpPacketStreamDump),
            fakeRtpDataChannel,
            RtspMessageUtil.DEFAULT_RTSP_TIMEOUT_MS,
            /* optionsRequestCounter= */ Optional.empty()) {
          @Override
          public RtspResponse getDescribeResponse(Uri requestedUri, RtspHeaders headers) {
            if (describeCount.getAndIncrement() == 0) {
              // First DESCRIBE redirects to another path on the same server.
              return new RtspResponse(
                  /* status= */ 302,
                  new RtspHeaders.Builder()
                      .add(RtspHeaders.LOCATION, requestedUri.toString() + "/moved")
                      .build());
            }
            return super.getDescribeResponse(requestedUri, headers);
          }
        };
    rtspServer = new RtspServer(responseProvider);
    ExoPlayer player = createExoPlayer(rtspServer.startAndGetPortNumber(), rtpDataChannelFactory);

    player.prepare();
    player.play();
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);
    player.release();

    // The client must reconnect to the redirected URI instead of re-DESCRIBEing on the old
    // channel: two DESCRIBEs over two connections, then normal playback.
    assertThat(describeCount.get()).isEqualTo(2);
    assertThat(requestsOfMethod(METHOD_DESCRIBE)).hasSize(2);
    assertThat(responseProvider.getDumpsForSetUpTracks())
        .containsExactly(aacRtpPacketStreamDump);
  }

  @Test
  public void prepare_withUdpUnsupportedWithFallback_fallsbackToTcpAndPlaysUntilEnd()
      throws Exception {
    FakeTcpDataSourceRtpDataChannel fakeTcpRtpDataChannel = new FakeTcpDataSourceRtpDataChannel();
    RtpDataChannel.Factory rtpTcpDataChannelFactory = (trackId) -> fakeTcpRtpDataChannel;
    ResponseProviderSupportingOnlyTcp responseProviderSupportingOnlyTcp =
        new ResponseProviderSupportingOnlyTcp(
            clock,
            ImmutableList.of(aacRtpPacketStreamDump, mpeg2tsRtpPacketStreamDump),
            fakeTcpRtpDataChannel);
    ForwardingRtpDataChannelFactory forwardingRtpDataChannelFactory =
        new ForwardingRtpDataChannelFactory(
            new UdpDataSourceRtpDataChannelFactory(DEFAULT_TIMEOUT_MS), rtpTcpDataChannelFactory);
    rtspServer = new RtspServer(responseProviderSupportingOnlyTcp);
    ExoPlayer player =
        createExoPlayer(rtspServer.startAndGetPortNumber(), forwardingRtpDataChannelFactory);

    PlaybackOutput playbackOutput = PlaybackOutput.register(player, capturingRenderersFactory);
    player.prepare();
    player.play();
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);
    player.release();

    // Only setup the supported track (aac).
    assertThat(responseProviderSupportingOnlyTcp.getDumpsForSetUpTracks())
        .containsExactly(aacRtpPacketStreamDump);
    DumpFileAsserts.assertOutput(applicationContext, playbackOutput, "playbackdumps/rtsp/aac.dump");
    // Regression (review RTSP-002/SES-1): the TCP fallback must not restart the OPTIONS/DESCRIBE
    // negotiation or re-run SETUP/PLAY — one DESCRIBE, one UDP SETUP (461) followed by one TCP
    // SETUP, and one PLAY for the whole session.
    assertThat(requestsOfMethod(METHOD_DESCRIBE)).hasSize(1);
    assertThat(requestsOfMethod(METHOD_SETUP)).hasSize(2);
    assertThat(requestsOfMethod(METHOD_PLAY)).hasSize(1);
  }

  @Test
  public void prepare_withUdpUnsupportedWithoutFallback_throwsRtspPlaybackException()
      throws Exception {
    FakeUdpDataSourceRtpDataChannel fakeUdpRtpDataChannel = new FakeUdpDataSourceRtpDataChannel();
    RtpDataChannel.Factory rtpDataChannelFactory = (trackId) -> fakeUdpRtpDataChannel;
    ResponseProviderSupportingOnlyTcp responseProvider =
        new ResponseProviderSupportingOnlyTcp(
            clock,
            ImmutableList.of(aacRtpPacketStreamDump, mpeg2tsRtpPacketStreamDump),
            fakeUdpRtpDataChannel);
    rtspServer = new RtspServer(responseProvider);
    ExoPlayer player = createExoPlayer(rtspServer.startAndGetPortNumber(), rtpDataChannelFactory);

    AtomicReference<PlaybackException> playbackError = new AtomicReference<>();
    player.prepare();
    player.addListener(
        new Listener() {
          @Override
          public void onPlayerError(PlaybackException error) {
            playbackError.set(error);
          }
        });
    RobolectricUtil.runMainLooperUntil(() -> playbackError.get() != null);
    player.release();

    assertThat(playbackError.get())
        .hasCauseThat()
        .isInstanceOf(RtspMediaSource.RtspPlaybackException.class);
    assertThat(playbackError.get())
        .hasCauseThat()
        .hasMessageThat()
        .contains("No fallback data channel factory for TCP retry");
  }

  @Test
  public void prepare_withUdpUnsupportedWithUdpFallback_throwsRtspUdpUnsupportedTransportException()
      throws Exception {
    FakeUdpDataSourceRtpDataChannel fakeUdpRtpDataChannel = new FakeUdpDataSourceRtpDataChannel();
    RtpDataChannel.Factory rtpDataChannelFactory = (trackId) -> fakeUdpRtpDataChannel;
    ResponseProviderSupportingOnlyTcp responseProviderSupportingOnlyTcp =
        new ResponseProviderSupportingOnlyTcp(
            clock,
            ImmutableList.of(aacRtpPacketStreamDump, mpeg2tsRtpPacketStreamDump),
            fakeUdpRtpDataChannel);
    ForwardingRtpDataChannelFactory forwardingRtpDataChannelFactory =
        new ForwardingRtpDataChannelFactory(rtpDataChannelFactory, rtpDataChannelFactory);
    rtspServer = new RtspServer(responseProviderSupportingOnlyTcp);
    ExoPlayer player =
        createExoPlayer(rtspServer.startAndGetPortNumber(), forwardingRtpDataChannelFactory);

    AtomicReference<PlaybackException> playbackError = new AtomicReference<>();
    player.addListener(
        new Listener() {
          @Override
          public void onPlayerError(PlaybackException error) {
            playbackError.set(error);
          }

        });
    player.prepare();
    RobolectricUtil.runMainLooperUntil(() -> playbackError.get() != null);
    player.release();

    assertThat(playbackError.get())
        .hasCauseThat()
        .isInstanceOf(RtspMediaSource.RtspUdpUnsupportedTransportException.class);
    assertThat(playbackError.get()).hasCauseThat().hasMessageThat().isEqualTo("SETUP 461");
  }

  @Test
  public void play_withCustomSessionTimeoutDuration_sendsKeepAliveOptionsRequest()
      throws Exception {
    FakeUdpDataSourceRtpDataChannel fakeRtpDataChannel = new FakeUdpDataSourceRtpDataChannel();
    RtpDataChannel.Factory rtpDataChannelFactory = (trackId) -> fakeRtpDataChannel;
    Optional<AtomicInteger> optionsRequestCounter = Optional.of(new AtomicInteger());
    ResponseProvider responseProvider =
        new ResponseProvider(
            clock,
            ImmutableList.of(aacRtpPacketStreamDump),
            fakeRtpDataChannel,
            /* sessionTimeoutMs= */ 300L,
            optionsRequestCounter);
    rtspServer = new RtspServer(responseProvider);
    ExoPlayer player = createExoPlayer(rtspServer.startAndGetPortNumber(), rtpDataChannelFactory);
    player.prepare();
    player.play();
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_READY);
    // Reset optionsRequestCounter to count requests made by the keep-alive monitor
    optionsRequestCounter.get().getAndSet(0);

    RobolectricUtil.runMainLooperUntil(() -> optionsRequestCounter.get().get() != 0);

    player.release();
  }

  @Test
  public void seekToEnd_afterLoadingFinished_doesNotLoadAgain() throws Exception {
    FakeUdpDataSourceRtpDataChannel fakeRtpDataChannel = new FakeUdpDataSourceRtpDataChannel();
    RtpDataChannel.Factory rtpDataChannelFactory = (trackId) -> fakeRtpDataChannel;
    ResponseProvider responseProvider =
        new ResponseProvider(
            clock,
            ImmutableList.of(aacRtpPacketStreamDump, mpeg2tsRtpPacketStreamDump),
            fakeRtpDataChannel,
            RtspMessageUtil.DEFAULT_RTSP_TIMEOUT_MS,
            /* optionsRequestCounter= */ Optional.empty());
    rtspServer = new RtspServer(responseProvider);
    ExoPlayer player = createExoPlayer(rtspServer.startAndGetPortNumber(), rtpDataChannelFactory);
    Player.Listener listener = mock(Player.Listener.class);
    player.prepare();
    player.play();
    advance(player).untilBackgroundThreadCondition(() -> player.getBufferedPosition() > 0);
    advance(player).untilLoadingIs(false);

    player.addListener(listener);
    player.seekTo(player.getDuration());
    play(player).untilState(Player.STATE_ENDED);
    player.release();

    verify(listener, never()).onIsLoadingChanged(true);
  }

  @Test
  public void prepare_withClockRangeOverride_playCarriesClockRangeAndVodifiesTimeline()
      throws Exception {
    FakeUdpDataSourceRtpDataChannel fakeRtpDataChannel = new FakeUdpDataSourceRtpDataChannel();
    RtpDataChannel.Factory rtpDataChannelFactory = (trackId) -> fakeRtpDataChannel;
    ResponseProvider responseProvider =
        new ResponseProvider(
            clock,
            ImmutableList.of(aacRtpPacketStreamDump),
            fakeRtpDataChannel,
            RtspMessageUtil.DEFAULT_RTSP_TIMEOUT_MS,
            /* optionsRequestCounter= */ Optional.empty());
    rtspServer = new RtspServer(responseProvider);
    ExoPlayer player =
        new ExoPlayer.Builder(applicationContext, capturingRenderersFactory)
            .setClock(clock)
            .build();
    player.setMediaSource(
        new RtspMediaSource(
            MediaItem.fromUri(RtspTestUtils.getTestUri(rtspServer.startAndGetPortNumber())),
            rtpDataChannelFactory,
            "ExoPlayer:PlaybackTest",
            SocketFactory.getDefault(),
            /* debugLoggingEnabled= */ false,
            /* clockRangeOverride= */ "clock=20260930T120000Z-20260930T130000Z"),
        false);

    player.prepare();
    player.play();
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_READY);
    // A live SDP with a clock override must present a fixed-duration, seekable replay window.
    Timeline.Window window = player.getCurrentTimeline().getWindow(0, new Timeline.Window());
    assertThat(window.isLive()).isFalse();
    assertThat(player.getDuration()).isEqualTo(3_600_000);
    player.release();

    // PLAY carries the absolute UTC clock range (offset 0 keeps the window start) plus a fixed
    // scale, instead of the live npt= range.
    List<RtspRequest> playRequests = requestsOfMethod(METHOD_PLAY);
    assertThat(playRequests).hasSize(1);
    assertThat(playRequests.get(0).headers.get(RtspHeaders.RANGE))
        .isEqualTo("clock=20260930T120000Z-20260930T130000Z");
    assertThat(playRequests.get(0).headers.get(RtspHeaders.SCALE)).isEqualTo("1.000000");
  }
  @Test
  public void playResponseEchoesClockRange_playStillSucceeds() throws Exception {
    FakeUdpDataSourceRtpDataChannel fakeRtpDataChannel = new FakeUdpDataSourceRtpDataChannel();
    RtpDataChannel.Factory rtpDataChannelFactory = (trackId) -> fakeRtpDataChannel;
    ResponseProvider responseProvider =
        new ResponseProvider(
            clock,
            ImmutableList.of(aacRtpPacketStreamDump),
            fakeRtpDataChannel,
            RtspMessageUtil.DEFAULT_RTSP_TIMEOUT_MS,
            /* optionsRequestCounter= */ Optional.empty()) {
          @Override
          public RtspResponse getPlayResponse() {
            RtspResponse response = super.getPlayResponse();
            // 合规服务器会回显确认的回放窗口：PLAY 响应带 clock= Range
            return new RtspResponse(
                response.status,
                response.headers.buildUpon()
                    .add(RtspHeaders.RANGE, "clock=20260930T120000Z-20260930T130000Z")
                    .build(),
                response.messageBody);
          }
        };
    rtspServer = new RtspServer(responseProvider);
    ExoPlayer player =
        new ExoPlayer.Builder(applicationContext, capturingRenderersFactory)
            .setClock(clock)
            .build();
    player.setMediaSource(
        new RtspMediaSource(
            MediaItem.fromUri(RtspTestUtils.getTestUri(rtspServer.startAndGetPortNumber())),
            rtpDataChannelFactory,
            "ExoPlayer:PlaybackTest",
            SocketFactory.getDefault(),
            /* debugLoggingEnabled= */ false,
            /* clockRangeOverride= */ "clock=20260930T120000Z-20260930T130000Z"),
        false);

    player.prepare();
    player.play();
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_READY);
    Timeline.Window window = player.getCurrentTimeline().getWindow(0, new Timeline.Window());
    assertThat(window.isLive()).isFalse();
    assertThat(player.getDuration()).isEqualTo(3_600_000);
    player.release();
  }

  /**
   * 服务器忽略 clock= 回看请求、显式回一个直播 npt 范围时，客户端必须把「回看窗口未被确认」
   * 作为独立协议事件报出去（播放本身正常，200）：App 侧据此收走伪造的回看时间轴并转直播。
   */
  @Test
  public void playResponseWithNptRangeWhileClockRangeRequested_signalsUnconfirmedClockRange()
      throws Exception {
    FakeUdpDataSourceRtpDataChannel fakeRtpDataChannel = new FakeUdpDataSourceRtpDataChannel();
    List<RtspProtocolEvent> events =
        protocolEventsOfClockRangeReplay(
            clockRangeResponseProvider(fakeRtpDataChannel, /* playResponseRange= */ "npt=0.000-"),
            (trackId) -> fakeRtpDataChannel);

    assertThat(protocolPhases(events)).contains("CLOCK_RANGE_UNCONFIRMED");
  }

  /**
   * 测试台 `/mp2t-noclock` 的实际响应形态（`npt=now-`）：同样必须报「未被确认」。
   * `npt=now-` 能被 NPT 解析器正常接受（now → 0、无终止 → live），所以事件不会被
   * ParserException 路径吞掉——这正是「静默忽略」与「硬失败」的区别所在。
   */
  @Test
  public void playResponseWithNptNowRangeWhileClockRangeRequested_signalsUnconfirmedClockRange()
      throws Exception {
    FakeUdpDataSourceRtpDataChannel fakeRtpDataChannel = new FakeUdpDataSourceRtpDataChannel();
    List<RtspProtocolEvent> events =
        protocolEventsOfClockRangeReplay(
            clockRangeResponseProvider(fakeRtpDataChannel, /* playResponseRange= */ "npt=now-"),
            (trackId) -> fakeRtpDataChannel);

    assertThat(protocolPhases(events)).contains("CLOCK_RANGE_UNCONFIRMED");
  }

  /** clock= 回显（服务器确认了请求的窗口）：不得报「未被确认」。 */
  @Test
  public void playResponseEchoingClockRange_doesNotSignalUnconfirmedClockRange() throws Exception {
    FakeUdpDataSourceRtpDataChannel fakeRtpDataChannel = new FakeUdpDataSourceRtpDataChannel();
    List<RtspProtocolEvent> events =
        protocolEventsOfClockRangeReplay(
            clockRangeResponseProvider(
                fakeRtpDataChannel, /* playResponseRange= */ "clock=20260930T120000Z-20260930T130000Z"),
            (trackId) -> fakeRtpDataChannel);

    assertThat(protocolPhases(events)).doesNotContain("CLOCK_RANGE_UNCONFIRMED");
  }

  /**
   * 无 Range 头的 PLAY 响应（RFC2326 Section 12 允许省略）保持「不确定」：不能把不回显的合规
   * 服务器误判成忽略了 clock=。
   */
  @Test
  public void playResponseWithoutRangeWhileClockRangeRequested_doesNotSignalUnconfirmedClockRange()
      throws Exception {
    FakeUdpDataSourceRtpDataChannel fakeRtpDataChannel = new FakeUdpDataSourceRtpDataChannel();
    List<RtspProtocolEvent> events =
        protocolEventsOfClockRangeReplay(
            clockRangeResponseProvider(fakeRtpDataChannel, /* playResponseRange= */ null),
            (trackId) -> fakeRtpDataChannel);

    assertThat(protocolPhases(events)).doesNotContain("CLOCK_RANGE_UNCONFIRMED");
  }

  /** clock= 回放会话的响应桩：可注入 PLAY 响应的 Range 头（null = 不带 Range）。 */
  private ResponseProvider clockRangeResponseProvider(
      FakeUdpDataSourceRtpDataChannel fakeRtpDataChannel, @Nullable String playResponseRange) {
    return new ResponseProvider(
        clock,
        ImmutableList.of(aacRtpPacketStreamDump),
        fakeRtpDataChannel,
        RtspMessageUtil.DEFAULT_RTSP_TIMEOUT_MS,
        /* optionsRequestCounter= */ Optional.empty()) {
      @Override
      public RtspResponse getPlayResponse() {
        RtspResponse response = super.getPlayResponse();
        if (playResponseRange == null) {
          return response;
        }
        return new RtspResponse(
            response.status,
            response.headers.buildUpon().add(RtspHeaders.RANGE, playResponseRange).build(),
            response.messageBody);
      }
    };
  }

  /** 跑完一次 clock= 回放会话（DESCRIBE→SETUP→PLAY→READY）并返回期间派发的协议事件。 */
  private List<RtspProtocolEvent> protocolEventsOfClockRangeReplay(
      RtspServer.ResponseProvider responseProvider, RtpDataChannel.Factory rtpDataChannelFactory)
      throws Exception {
    rtspServer = new RtspServer(responseProvider);
    ConcurrentLinkedQueue<RtspProtocolEvent> events = new ConcurrentLinkedQueue<>();
    ExoPlayer player =
        new ExoPlayer.Builder(applicationContext, capturingRenderersFactory)
            .setClock(clock)
            .build();
    player.setMediaSource(
        new RtspMediaSource(
            MediaItem.fromUri(RtspTestUtils.getTestUri(rtspServer.startAndGetPortNumber())),
            rtpDataChannelFactory,
            "ExoPlayer:PlaybackTest",
            SocketFactory.getDefault(),
            /* debugLoggingEnabled= */ false,
            /* protocolEventExecutor= */ Runnable::run,
            events::add,
            /* clockRangeOverride= */ "clock=20260930T120000Z-20260930T130000Z"),
        false);

    player.prepare();
    player.play();
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_READY);
    player.release();
    return new ArrayList<>(events);
  }

  private static List<String> protocolPhases(List<RtspProtocolEvent> events) {
    List<String> phases = new ArrayList<>();
    for (RtspProtocolEvent event : events) {
      phases.add(event.getPhase());
    }
    return phases;
  }

  private List<RtspRequest> requestsOfMethod(int method) {
    List<RtspRequest> requests = new ArrayList<>();
    for (RtspRequest request : rtspServer.getReceivedRequests()) {
      if (request.method == method) {
        requests.add(request);
      }
    }
    return requests;
  }

  private ExoPlayer createExoPlayer(
      int serverRtspPortNumber, RtpDataChannel.Factory rtpDataChannelFactory) {
    ExoPlayer player =
        new ExoPlayer.Builder(applicationContext, capturingRenderersFactory)
            .setClock(clock)
            .build();
    player.setMediaSource(
        new RtspMediaSource(
            MediaItem.fromUri(RtspTestUtils.getTestUri(serverRtspPortNumber)),
            rtpDataChannelFactory,
            "ExoPlayer:PlaybackTest",
            SocketFactory.getDefault(),
            /* debugLoggingEnabled= */ false),
        false);
    return player;
  }

  private static class ResponseProvider implements RtspServer.ResponseProvider {

    protected static final String SESSION_ID = "00000000";
    private static final String SESSION_TIMEOUT_HEADER_TAG = ";timeout=";

    protected final Clock clock;
    protected final List<RtpPacketStreamDump> dumpsForSetUpTracks = new ArrayList<>();
    protected final ImmutableList<RtpPacketStreamDump> rtpPacketStreamDumps;
    private final RtspMessageChannel.InterleavedBinaryDataListener binaryDataListener;
    private final long sessionTimeoutMs;
    private final Optional<AtomicInteger> optionsRequestCounter;

    protected RtpPacketTransmitter packetTransmitter;

    /**
     * Creates a new instance.
     *
     * @param clock The {@link Clock} used in the test.
     * @param rtpPacketStreamDumps A list of {@link RtpPacketStreamDump}.
     * @param binaryDataListener A {@link RtspMessageChannel.InterleavedBinaryDataListener} to send
     *     RTP data.
     * @param sessionTimeoutMs Duration RTSP server will keep the session active without receiving
     *     any requests.
     * @param optionsRequestCounter for how many RTSP Options requests were sent.
     */
    ResponseProvider(
        Clock clock,
        List<RtpPacketStreamDump> rtpPacketStreamDumps,
        RtspMessageChannel.InterleavedBinaryDataListener binaryDataListener,
        long sessionTimeoutMs,
        Optional<AtomicInteger> optionsRequestCounter) {
      this.clock = clock;
      this.rtpPacketStreamDumps = ImmutableList.copyOf(rtpPacketStreamDumps);
      this.binaryDataListener = binaryDataListener;
      this.sessionTimeoutMs = sessionTimeoutMs;
      this.optionsRequestCounter = optionsRequestCounter;
    }

    /** Returns a list of the received SETUP requests' corresponding {@link RtpPacketStreamDump}. */
    public ImmutableList<RtpPacketStreamDump> getDumpsForSetUpTracks() {
      return ImmutableList.copyOf(dumpsForSetUpTracks);
    }

    // RtspServer.ResponseProvider implementation. Called on the main thread.

    @Override
    public RtspResponse getOptionsResponse() {
      optionsRequestCounter.ifPresent(AtomicInteger::getAndIncrement);
      return new RtspResponse(
          /* status= */ 200,
          new RtspHeaders.Builder()
              .add(RtspHeaders.PUBLIC, "OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN")
              .build());
    }

    @Override
    public RtspResponse getDescribeResponse(Uri requestedUri, RtspHeaders headers) {
      return RtspTestUtils.newDescribeResponseWithSdpMessage(
          SESSION_DESCRIPTION, rtpPacketStreamDumps, requestedUri);
    }

    @Override
    public RtspResponse getSetupResponse(Uri requestedUri, RtspHeaders headers) {
      for (RtpPacketStreamDump rtpPacketStreamDump : rtpPacketStreamDumps) {
        if (requestedUri.toString().contains(rtpPacketStreamDump.trackName)) {
          dumpsForSetUpTracks.add(rtpPacketStreamDump);
          packetTransmitter = new RtpPacketTransmitter(rtpPacketStreamDump, clock);
        }
      }
      return new RtspResponse(
          /* status= */ 200,
          headers
              .buildUpon()
              .add(
                  RtspHeaders.SESSION,
                  // Convert sessionTimeoutMs to seconds
                  SESSION_ID + SESSION_TIMEOUT_HEADER_TAG + (sessionTimeoutMs / 1000))
              .build());
    }

    @Override
    public RtspResponse getPlayResponse() {
      checkNotNull(packetTransmitter);
      packetTransmitter.startTransmitting(binaryDataListener);

      return new RtspResponse(
          /* status= */ 200,
          new RtspHeaders.Builder()
              .add(RtspHeaders.RTP_INFO, RtspTestUtils.getRtpInfoForDumps(rtpPacketStreamDumps))
              .build());
    }
  }

  private static final class ResponseProviderSupportingOnlyTcp extends ResponseProvider {

    /**
     * Creates a new instance.
     *
     * @param clock The {@link Clock} used in the test.
     * @param rtpPacketStreamDumps A list of {@link RtpPacketStreamDump}.
     * @param binaryDataListener A {@link RtspMessageChannel.InterleavedBinaryDataListener} to send
     *     RTP data.
     */
    public ResponseProviderSupportingOnlyTcp(
        Clock clock,
        List<RtpPacketStreamDump> rtpPacketStreamDumps,
        RtspMessageChannel.InterleavedBinaryDataListener binaryDataListener) {
      super(
          clock,
          rtpPacketStreamDumps,
          binaryDataListener,
          RtspMessageUtil.DEFAULT_RTSP_TIMEOUT_MS,
          /* optionsRequestCounter= */ Optional.empty());
    }

    @Override
    public RtspResponse getSetupResponse(Uri requestedUri, RtspHeaders headers) {
      String transportHeaderValue = checkNotNull(headers.get(RtspHeaders.TRANSPORT));
      if (!transportHeaderValue.contains("TCP")) {
        return new RtspResponse(
            /* status= */ 461, headers.buildUpon().add(RtspHeaders.SESSION, SESSION_ID).build());
      }
      for (RtpPacketStreamDump rtpPacketStreamDump : rtpPacketStreamDumps) {
        if (requestedUri.toString().contains(rtpPacketStreamDump.trackName)) {
          dumpsForSetUpTracks.add(rtpPacketStreamDump);
          packetTransmitter = new RtpPacketTransmitter(rtpPacketStreamDump, clock);
        }
      }
      return new RtspResponse(
          /* status= */ 200, headers.buildUpon().add(RtspHeaders.SESSION, SESSION_ID).build());
    }
  }

  private abstract static class FakeBaseDataSourceRtpDataChannel extends BaseDataSource
      implements RtpDataChannel, RtspMessageChannel.InterleavedBinaryDataListener {
    protected static final int LOCAL_PORT = 40000;

    private final ConcurrentLinkedQueue<byte[]> packetQueue;

    public FakeBaseDataSourceRtpDataChannel() {
      super(/* isNetwork= */ false);
      packetQueue = new ConcurrentLinkedQueue<>();
    }

    @Override
    public abstract String getTransport();

    @Override
    public int getLocalPort() {
      return LOCAL_PORT;
    }

    @Override
    public RtspMessageChannel.InterleavedBinaryDataListener getInterleavedBinaryDataListener() {
      return this;
    }

    @Override
    public void onInterleavedBinaryDataReceived(byte[] data) {
      packetQueue.add(data);
    }

    @Override
    public long open(DataSpec dataSpec) {
      return C.LENGTH_UNSET;
    }

    @Nullable
    @Override
    public Uri getUri() {
      return null;
    }

    @Override
    public void close() {}

    @Override
    public int read(byte[] buffer, int offset, int length) {
      if (length == 0) {
        return 0;
      }

      @Nullable byte[] data = packetQueue.poll();
      if (data == null) {
        return 0;
      }

      if (data.length == 0) {
        // Empty data signals the end of a packet stream.
        return C.RESULT_END_OF_INPUT;
      }

      int byteToRead = min(length, data.length);
      System.arraycopy(data, /* srcPos= */ 0, buffer, offset, byteToRead);
      return byteToRead;
    }
  }

  private static final class FakeUdpDataSourceRtpDataChannel
      extends FakeBaseDataSourceRtpDataChannel {
    @Override
    public String getTransport() {
      return Util.formatInvariant("RTP/AVP;unicast;client_port=%d-%d", LOCAL_PORT, LOCAL_PORT + 1);
    }

    @Override
    public boolean needsClosingOnLoadCompletion() {
      return false;
    }

    @Override
    public RtspMessageChannel.InterleavedBinaryDataListener getInterleavedBinaryDataListener() {
      return null;
    }
  }

  private static final class FakeTcpDataSourceRtpDataChannel
      extends FakeBaseDataSourceRtpDataChannel {
    @Override
    public String getTransport() {
      return Util.formatInvariant(
          "RTP/AVP/TCP;unicast;interleaved=%d-%d", LOCAL_PORT + 2, LOCAL_PORT + 3);
    }

    @Override
    public boolean needsClosingOnLoadCompletion() {
      return false;
    }
  }

  private static class ForwardingRtpDataChannelFactory implements RtpDataChannel.Factory {

    private final RtpDataChannel.Factory rtpChannelFactory;
    private final RtpDataChannel.Factory rtpFallbackChannelFactory;

    public ForwardingRtpDataChannelFactory(
        RtpDataChannel.Factory rtpChannelFactory,
        RtpDataChannel.Factory rtpFallbackChannelFactory) {
      this.rtpChannelFactory = rtpChannelFactory;
      this.rtpFallbackChannelFactory = rtpFallbackChannelFactory;
    }

    @Override
    public RtpDataChannel createAndOpenDataChannel(int trackId) throws IOException {
      return rtpChannelFactory.createAndOpenDataChannel(trackId);
    }

    @Override
    public RtpDataChannel.Factory createFallbackDataChannelFactory() {
      return rtpFallbackChannelFactory;
    }
  }
}
