package androidx.media3.exoplayer.rtsp;

import static androidx.media3.exoplayer.rtsp.RtspMessageChannel.DEFAULT_RTSP_PORT;
import static androidx.media3.exoplayer.rtsp.RtspRequest.METHOD_ANNOUNCE;
import static androidx.media3.exoplayer.rtsp.RtspRequest.METHOD_DESCRIBE;
import static androidx.media3.exoplayer.rtsp.RtspRequest.METHOD_GET_PARAMETER;
import static androidx.media3.exoplayer.rtsp.RtspRequest.METHOD_OPTIONS;
import static androidx.media3.exoplayer.rtsp.RtspRequest.METHOD_PAUSE;
import static androidx.media3.exoplayer.rtsp.RtspRequest.METHOD_PLAY;
import static androidx.media3.exoplayer.rtsp.RtspRequest.METHOD_PLAY_NOTIFY;
import static androidx.media3.exoplayer.rtsp.RtspRequest.METHOD_RECORD;
import static androidx.media3.exoplayer.rtsp.RtspRequest.METHOD_REDIRECT;
import static androidx.media3.exoplayer.rtsp.RtspRequest.METHOD_SETUP;
import static androidx.media3.exoplayer.rtsp.RtspRequest.METHOD_SET_PARAMETER;
import static androidx.media3.exoplayer.rtsp.RtspRequest.METHOD_TEARDOWN;
import static androidx.media3.exoplayer.rtsp.RtspRequest.METHOD_UNSET;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.nullToEmpty;
import static java.lang.Math.max;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import android.util.SparseArray;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.rtsp.RtspMediaPeriod.RtpLoadInfo;
import androidx.media3.exoplayer.rtsp.RtspMediaSource.RtspPlaybackException;
import androidx.media3.exoplayer.rtsp.RtspMediaSource.RtspUdpUnsupportedTransportException;
import androidx.media3.exoplayer.rtsp.RtspMessageChannel.InterleavedBinaryDataListener;
import androidx.media3.exoplayer.rtsp.RtspMessageUtil.RtspAuthUserInfo;
import androidx.media3.exoplayer.rtsp.RtspMessageUtil.RtspSessionHeader;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import java.io.Closeable;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.SocketFactory;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** The RTSP client. */
/* package */ final class RtspClient implements Closeable {

  /**
   * The RTSP session state (RFC2326, Section A.1). One of {@link #RTSP_STATE_UNINITIALIZED}, {@link
   * #RTSP_STATE_INIT}, {@link #RTSP_STATE_READY}, or {@link #RTSP_STATE_PLAYING}.
   */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({RTSP_STATE_UNINITIALIZED, RTSP_STATE_INIT, RTSP_STATE_READY, RTSP_STATE_PLAYING})
  public @interface RtspState {}

  /** RTSP uninitialized state, the state before sending any SETUP request. */
  public static final int RTSP_STATE_UNINITIALIZED = -1;

  /** RTSP initial state, the state after sending SETUP REQUEST. */
  public static final int RTSP_STATE_INIT = 0;

  /** RTSP ready state, the state after receiving SETUP, or PAUSE response. */
  public static final int RTSP_STATE_READY = 1;

  /** RTSP playing state, the state after receiving PLAY response. */
  public static final int RTSP_STATE_PLAYING = 2;

  private static final String TAG = "RtspClient";

  /**
   * The default divisor used on the session timeout value to be set as the {@link
   * KeepAliveMonitor#intervalMs}.
   */
  private static final int DEFAULT_RTSP_KEEP_ALIVE_INTERVAL_DIVISOR = 2;

  /**
   * Maximum redirection hops (3xx responses and SMIL redirects combined) before the session is
   * failed, guarding against redirect loops.
   */
  private static final int MAX_REDIRECT_COUNT = 10;

  /**
   * Matches the {@code src} attribute of a {@code <video>} or {@code <ref>} element in a SMIL
   * document. Carrier IPTV servers occasionally answer DESCRIBE with SMIL instead of SDP; the
   * first {@code src} then holds the URI of the actual stream to DESCRIBE.
   */
  private static final Pattern SMIL_SRC_PATTERN =
      Pattern.compile(
          "<(?:video|ref)\\b[^>]*\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

  /** A listener for session information update. */
  public interface SessionInfoListener {
    /** Called when the session information is available. */
    void onSessionTimelineUpdated(RtspSessionTiming timing, ImmutableList<RtspMediaTrack> tracks);

    /**
     * Called when failed to get session information from the RTSP server, or when error happened
     * during updating the session timeline.
     */
    void onSessionTimelineRequestFailed(String message, @Nullable Throwable cause);
  }

  /** A listener for playback events. */
  public interface PlaybackEventListener {
    /** Called when setup is completed and playback can start. */
    void onRtspSetupCompleted();

    /**
     * Called when a PLAY request is acknowledged by the server and playback can start.
     *
     * @param startPositionUs The server-supplied start position in microseconds.
     * @param trackTimingList The list of {@link RtspTrackTiming} for the playing tracks.
     */
    void onPlaybackStarted(long startPositionUs, ImmutableList<RtspTrackTiming> trackTimingList);

    /** Called when errors are encountered during playback. */
    void onPlaybackError(RtspPlaybackException error);
  }

  private final SessionInfoListener sessionInfoListener;
  private final PlaybackEventListener playbackEventListener;
  private final String userAgent;
  private final SocketFactory socketFactory;
  private final boolean debugLoggingEnabled;
  private final ArrayDeque<RtpLoadInfo> pendingSetupRtpLoadInfos;
  // TODO(b/172331505) Add a timeout monitor for pending requests.
  private final SparseArray<RtspRequest> pendingRequests;
  private final SparseArray<Runnable> pendingRequestTimeouts;
  private final Handler controlRequestHandler;
  private final MessageSender messageSender;
  private final long controlRequestTimeoutMs;
  @Nullable private final Executor protocolEventExecutor;
  @Nullable private final RtspProtocolEventListener protocolEventListener;
  private final long requestGeneration;
  private final long attemptToken;
  private final String initialAuthority;
  private long protocolEventSequence;
  private boolean released;

  /** RTSP session URI. */
  private Uri uri;

  private RtspMessageChannel messageChannel;
  @Nullable private RtspAuthUserInfo rtspAuthUserInfo;
  @Nullable private String sessionId;
  private long sessionTimeoutMs;
  @Nullable private KeepAliveMonitor keepAliveMonitor;
  @Nullable private RtspAuthenticationInfo rtspAuthenticationInfo;
  private @RtspState int rtspState;
  private boolean hasUpdatedTimelineAndTracks;
  private boolean receivedAuthorizationRequest;
  private boolean hasPendingPauseRequest;
  private long pendingSeekPositionUs;
  /** Combined count of 3xx and SMIL redirection hops followed for the current DESCRIBE chain. */
  private int redirectCount;
  /**
   * The epoch millisecond start/end of the clock range override, or {@link C#TIME_UNSET} when
   * playing live without an override (see the {@code clockRangeOverride} constructor parameter).
   */
  private final long clockRangeStartEpochMs;
  private final long clockRangeEndEpochMs;

  /**
   * Creates a new instance.
   *
   * <p>The constructor must be called on the playback thread. The thread is also where {@link
   * SessionInfoListener} and {@link PlaybackEventListener} events are sent. User must {@link
   * #start} the client, and {@link #close} it when done.
   *
   * <p>Note: all method invocations must be made from the playback thread.
   *
   * @param sessionInfoListener The {@link SessionInfoListener}.
   * @param playbackEventListener The {@link PlaybackEventListener}.
   * @param userAgent The user agent.
   * @param uri The RTSP playback URI.
   * @param socketFactory A socket factory for the RTSP connection.
   * @param debugLoggingEnabled Whether to log RTSP messages.
   */
  public RtspClient(
      SessionInfoListener sessionInfoListener,
      PlaybackEventListener playbackEventListener,
      String userAgent,
      Uri uri,
      SocketFactory socketFactory,
      boolean debugLoggingEnabled) {
    this(
        sessionInfoListener,
        playbackEventListener,
        userAgent,
        uri,
        socketFactory,
        debugLoggingEnabled,
        /* credentials= */ null,
        /* protocolEventExecutor= */ null,
        /* protocolEventListener= */ null,
        /* requestGeneration= */ 0,
        /* attemptToken= */ 0,
        /* controlRequestTimeoutMs= */ 0);
  }

  /** Creates a client with credentials and redacted protocol-event delivery kept out of the URI. */
  public RtspClient(
      SessionInfoListener sessionInfoListener,
      PlaybackEventListener playbackEventListener,
      String userAgent,
      Uri uri,
      SocketFactory socketFactory,
      boolean debugLoggingEnabled,
      @Nullable RtspAuthUserInfo credentials,
      @Nullable Executor protocolEventExecutor,
      @Nullable RtspProtocolEventListener protocolEventListener,
      long requestGeneration,
      long attemptToken,
      long controlRequestTimeoutMs) {
    this(
        sessionInfoListener,
        playbackEventListener,
        userAgent,
        uri,
        socketFactory,
        debugLoggingEnabled,
        credentials,
        protocolEventExecutor,
        protocolEventListener,
        requestGeneration,
        attemptToken,
        controlRequestTimeoutMs,
        /* clockRangeOverride= */ null);
  }

  /**
   * Creates a client with credentials, redacted protocol-event delivery, and an optional clock
   * range override for replay sessions on live servers.
   *
   * @param clockRangeOverride A {@code clock=startTime-endTime} range string (RFC2326 Section 3.6),
   *     or {@code null} to play live. When set and the SDP declares a live session, the timeline is
   *     VOD-ified into this seekable window and PLAY requests carry {@code Range: clock=...}.
   * @throws IllegalArgumentException When {@code clockRangeOverride} is non-null but malformed: the
   *     override is built by the caller, so a malformed value is a caller bug and must fail fast.
   */
  public RtspClient(
      SessionInfoListener sessionInfoListener,
      PlaybackEventListener playbackEventListener,
      String userAgent,
      Uri uri,
      SocketFactory socketFactory,
      boolean debugLoggingEnabled,
      @Nullable RtspAuthUserInfo credentials,
      @Nullable Executor protocolEventExecutor,
      @Nullable RtspProtocolEventListener protocolEventListener,
      long requestGeneration,
      long attemptToken,
      long controlRequestTimeoutMs,
      @Nullable String clockRangeOverride) {
    checkArgument((protocolEventExecutor == null) == (protocolEventListener == null));
    this.sessionInfoListener = sessionInfoListener;
    this.playbackEventListener = playbackEventListener;
    this.userAgent = userAgent;
    this.socketFactory = socketFactory;
    this.debugLoggingEnabled = debugLoggingEnabled;
    this.controlRequestHandler = Util.createHandlerForCurrentLooper();
    this.pendingSetupRtpLoadInfos = new ArrayDeque<>();
    this.pendingRequests = new SparseArray<>();
    this.pendingRequestTimeouts = new SparseArray<>();
    this.messageSender = new MessageSender();
    this.uri = RtspMessageUtil.removeUserInfo(uri);
    this.initialAuthority = authorityKey(this.uri);
    this.messageChannel = new RtspMessageChannel(new MessageListener());
    this.sessionTimeoutMs = RtspMessageUtil.DEFAULT_RTSP_TIMEOUT_MS;
    this.rtspAuthUserInfo = credentials != null ? credentials : RtspMessageUtil.parseUserInfo(uri);
    this.protocolEventExecutor = protocolEventExecutor;
    this.protocolEventListener = protocolEventListener;
    this.requestGeneration = requestGeneration;
    this.attemptToken = attemptToken;
    this.controlRequestTimeoutMs = Math.max(0, controlRequestTimeoutMs);
    try {
      @Nullable long[] clockRangeEpochMs = RtspSessionTiming.parseClockRangeOverride(clockRangeOverride);
      this.clockRangeStartEpochMs =
          clockRangeEpochMs == null ? C.TIME_UNSET : clockRangeEpochMs[0];
      this.clockRangeEndEpochMs = clockRangeEpochMs == null ? C.TIME_UNSET : clockRangeEpochMs[1];
    } catch (ParserException e) {
      throw new IllegalArgumentException("Malformed clock range override", e);
    }
    this.pendingSeekPositionUs = C.TIME_UNSET;
    this.rtspState = RTSP_STATE_UNINITIALIZED;
    this.protocolEventSequence = 0;
    this.released = false;
  }

  /**
   * Starts the client and sends an OPTIONS request.
   *
   * <p>Calls {@link #close()} if {@link IOException} is thrown when opening a connection to the
   * supplied {@link Uri}.
   *
   * @throws IOException When failed to open a connection to the supplied {@link Uri}.
   */
  public void start() throws IOException {
    try {
      messageChannel.open(getSocket(uri));
    } catch (IOException e) {
      emitProtocolEvent(
          "CONNECT", C.INDEX_UNSET, "ERROR", RtspMessageUtil.redactErrorMessage(e.getMessage()), null, null);
      Util.closeQuietly(messageChannel);
      throw e;
    }
    messageSender.sendOptionsRequest(uri, sessionId);
  }

  /** Returns the current {@link RtspState RTSP state}. */
  public @RtspState int getState() {
    return rtspState;
  }

  /**
   * Triggers RTSP SETUP requests after track selection.
   *
   * <p>All selected tracks (represented by {@link RtpLoadInfo}) must have valid transport.
   *
   * @param loadInfos A list of selected tracks represented by {@link RtpLoadInfo}.
   */
  public void setupSelectedTracks(List<RtpLoadInfo> loadInfos) {
    pendingSetupRtpLoadInfos.addAll(loadInfos);
    continueSetupRtspTrack();
  }

  /**
   * Starts RTSP playback by sending RTSP PLAY request.
   *
   * @param offsetMs The playback offset in milliseconds, with respect to the stream start position.
   */
  public void startPlayback(long offsetMs) {
    messageSender.sendPlayRequest(uri, offsetMs, checkNotNull(sessionId));
  }

  public void signalPlaybackEnded() {
    rtspState = RTSP_STATE_READY;
  }

  /**
   * Seeks to a specific time using RTSP.
   *
   * <p>Call this method only when in-buffer seek is not feasible. An RTSP PAUSE, and an RTSP PLAY
   * request will be sent out to perform a seek on the server side.
   *
   * @param positionUs The seek time measured in microseconds.
   */
  public void seekToUs(long positionUs) {
    // RTSP state is PLAYING after sending out a PAUSE, before receiving the PAUSE response. Sends
    // out PAUSE only when state PLAYING and no PAUSE is sent.
    if (rtspState == RTSP_STATE_PLAYING && !hasPendingPauseRequest) {
      messageSender.sendPauseRequest(uri, checkNotNull(sessionId));
    }
    pendingSeekPositionUs = positionUs;
  }

  /** Permanently releases the client and drops protocol events already queued on the executor. */
  public void release() {
    released = true;
    try {
      closeInternal();
    } catch (IOException ignored) {
      // Release is best-effort and must not make MediaPeriod.release throw.
    }
  }

  @Override
  public void close() throws IOException {
    closeInternal();
  }

  private void closeInternal() throws IOException {
    if (keepAliveMonitor != null) {
      // Playback has started. We have to stop the periodic keep alive and send a TEARDOWN so that
      // the RTSP server stops sending RTP packets and frees up resources.
      keepAliveMonitor.close();
      keepAliveMonitor = null;
      if (sessionId != null) {
        messageSender.sendTeardownRequest(uri, sessionId);
      }
    }
    for (int i = 0; i < pendingRequestTimeouts.size(); i++) {
      controlRequestHandler.removeCallbacks(pendingRequestTimeouts.valueAt(i));
    }
    pendingRequestTimeouts.clear();
    pendingRequests.clear();
    // Drop stale SETUP entries so a 461-triggered UDP->TCP fallback re-SETUPs with the fresh
    // TCP transports instead of replaying the rejected UDP ones (review SES-013; upstream main
    // clears the same queue in close()).
    pendingSetupRtpLoadInfos.clear();
    messageChannel.close();
  }

  /**
   * Sets up a new playback session using TCP as RTP lower transport.
   *
   * <p>This mode is also known as "RTP-over-RTSP".
   */
  public void retryWithRtpTcp() {
    try {
      close();
      emitProtocolEvent("TRANSPORT", C.INDEX_UNSET, "FALLBACK", "UDP_TO_TCP", "UDP", "TCP");
      messageChannel = new RtspMessageChannel(new MessageListener());
      messageChannel.open(getSocket(uri));
      sessionId = null;
      receivedAuthorizationRequest = false;
      rtspAuthenticationInfo = null;
    } catch (IOException e) {
      playbackEventListener.onPlaybackError(new RtspPlaybackException(e));
    }
  }

  /** Registers an {@link InterleavedBinaryDataListener} to receive RTSP interleaved data. */
  public void registerInterleavedDataChannel(
      int channel, InterleavedBinaryDataListener interleavedBinaryDataListener) {
    messageChannel.registerInterleavedBinaryDataListener(channel, interleavedBinaryDataListener);
  }

  private void continueSetupRtspTrack() {
    @Nullable RtpLoadInfo loadInfo = pendingSetupRtpLoadInfos.pollFirst();
    if (loadInfo == null) {
      playbackEventListener.onRtspSetupCompleted();
      return;
    }
    messageSender.sendSetupRequest(loadInfo.getTrackUri(), loadInfo.getTransport(), sessionId);
  }

  private void maybeLogMessage(List<String> message) {
    if (debugLoggingEnabled) {
      // Never log serialized RTSP messages. They may contain URI userinfo, Authorization, cookies,
      // server-controlled locations, or other credentials. The event callback carries only fields
      // that are safe for diagnostics.
      String summary = message.isEmpty() ? "UNKNOWN" : message.get(0).split(" ")[0];
      Log.d(TAG, "RTSP message " + summary + " <redacted>");
    }
  }

  /** Returns a {@link Socket} that is connected to the {@code uri}. */
  private Socket getSocket(Uri uri) throws IOException {
    checkArgument(uri.getHost() != null);
    int rtspPort = uri.getPort() > 0 ? uri.getPort() : DEFAULT_RTSP_PORT;
    return socketFactory.createSocket(checkNotNull(uri.getHost()), rtspPort);
  }

  private void dispatchRtspError(Throwable error) {
    dispatchRtspError(error, /* request= */ null, C.INDEX_UNSET, error.getMessage());
  }

  private void dispatchRtspError(
      Throwable error,
      @Nullable RtspRequest request,
      int statusCode,
      @Nullable String failureReason) {
    RtspPlaybackException playbackException =
        error instanceof RtspPlaybackException
            ? (RtspPlaybackException) error
            : new RtspPlaybackException(error);
    emitProtocolEvent(
        request == null ? "UNKNOWN" : RtspMessageUtil.toMethodString(request.method),
        statusCode,
        "ERROR",
        RtspMessageUtil.redactErrorMessage(failureReason),
        requestedTransport(request),
        /* selectedTransport= */ null);

    if (hasUpdatedTimelineAndTracks) {
      // Playback event listener must be non-null after timeline has been updated.
      playbackEventListener.onPlaybackError(playbackException);
    } else {
      sessionInfoListener.onSessionTimelineRequestFailed(
          RtspMessageUtil.redactErrorMessage(failureReason), /* cause= */ null);
    }
  }

  /**
   * Returns whether the RTSP server supports the DESCRIBE method.
   *
   * <p>The DESCRIBE method is marked "recommended to implement" in RFC2326 Section 10. We assume
   * the server supports DESCRIBE, if the OPTIONS response does not include a PUBLIC header.
   *
   * @param serverSupportedMethods A list of RTSP methods (as defined in RFC2326 Section 10, encoded
   *     as {@link RtspRequest.Method}) that are supported by the RTSP server.
   */
  private static boolean serverSupportsDescribe(List<Integer> serverSupportedMethods) {
    return serverSupportedMethods.isEmpty() || serverSupportedMethods.contains(METHOD_DESCRIBE);
  }

  /**
   * Returns the included {@link RtspMediaTrack RtspMediaTracks} from parsing the {@link
   * SessionDescription} within the {@link RtspDescribeResponse}.
   *
   * @param rtspDescribeResponse The {@link RtspDescribeResponse} from which to retrieve the tracks.
   * @param uri The RTSP playback URI.
   */
  private static ImmutableList<RtspMediaTrack> buildTrackList(
      RtspDescribeResponse rtspDescribeResponse, Uri uri) {
    ImmutableList.Builder<RtspMediaTrack> trackListBuilder = new ImmutableList.Builder<>();
    for (int i = 0; i < rtspDescribeResponse.sessionDescription.mediaDescriptionList.size(); i++) {
      MediaDescription mediaDescription =
          rtspDescribeResponse.sessionDescription.mediaDescriptionList.get(i);
      // Includes tracks with supported formats only.
      if (RtpPayloadFormat.isFormatSupported(mediaDescription)) {
        trackListBuilder.add(
            new RtspMediaTrack(rtspDescribeResponse.headers, mediaDescription, uri));
      }
    }
    return trackListBuilder.build();
  }

  /**
   * Extracts the first stream URI from a SMIL XML response body, or {@code null} if the body is
   * not SMIL.
   *
   * <p>Some IPTV servers respond to DESCRIBE with a SMIL document instead of SDP; the real stream
   * URI then sits in the first {@code <video src>} or {@code <ref src>} element and a second
   * DESCRIBE must be issued against it. Detection prefers the {@code Content-Type} header and
   * falls back to sniffing the body, because servers omit the header in practice.
   */
  @Nullable
  private static Uri extractSmilStreamUri(String body, @Nullable String contentType, Uri baseUri) {
    String normalizedContentType =
        contentType == null ? null : contentType.toLowerCase(Locale.US);
    boolean isSmilByHeader =
        normalizedContentType != null
            && (normalizedContentType.contains("smil") || normalizedContentType.contains("/xml"));
    if (!isSmilByHeader) {
      String trimmed = body.trim();
      if (!trimmed.startsWith("<?xml") && !trimmed.regionMatches(true, 0, "<smil", 0, 5)) {
        return null;
      }
    }
    Matcher matcher = SMIL_SRC_PATTERN.matcher(body.trim());
    if (!matcher.find()) {
      return null;
    }
    String src = matcher.group(1);
    if (src == null || src.isEmpty()) {
      return null;
    }
    if (src.contains("://")) {
      return Uri.parse(src);
    }
    try {
      return Uri.parse(java.net.URI.create(baseUri.toString()).resolve(src).toString());
    } catch (IllegalArgumentException | UnsupportedOperationException e) {
      // Relative reference that java.net.URI cannot resolve against the base: fall back to
      // prefixing the base path. Never crashes on malformed server input.
      String base = baseUri.toString();
      int lastSlash = base.lastIndexOf('/');
      String resolved = lastSlash >= 0 ? base.substring(0, lastSlash + 1) + src : base + "/" + src;
      return Uri.parse(resolved);
    }
  }

  private final class MessageSender {

    private int cSeq;
    private @MonotonicNonNull RtspRequest lastRequest;

    public void sendOptionsRequest(Uri uri, @Nullable String sessionId) {
      sendRequest(
          getRequestWithCommonHeaders(
              METHOD_OPTIONS, sessionId, /* additionalHeaders= */ ImmutableMap.of(), uri));
    }

    public void sendDescribeRequest(Uri uri, @Nullable String sessionId) {
      sendRequest(
          getRequestWithCommonHeaders(
              METHOD_DESCRIBE,
              sessionId,
              /* additionalHeaders= */ ImmutableMap.of(
                  RtspHeaders.ACCEPT, MimeTypes.APPLICATION_SDP),
              uri));
    }

    public void sendSetupRequest(Uri trackUri, String transport, @Nullable String sessionId) {
      rtspState = RTSP_STATE_INIT;
      sendRequest(
          getRequestWithCommonHeaders(
              METHOD_SETUP,
              sessionId,
              /* additionalHeaders= */ ImmutableMap.of(RtspHeaders.TRANSPORT, transport),
              trackUri));
    }

    public void sendPauseRequest(Uri uri, String sessionId) {
      checkState(rtspState == RTSP_STATE_PLAYING);
      sendRequest(
          getRequestWithCommonHeaders(
              METHOD_PAUSE, sessionId, /* additionalHeaders= */ ImmutableMap.of(), uri));
      hasPendingPauseRequest = true;
    }

    public void sendPlayRequest(Uri uri, long offsetMs, @Nullable String sessionId) {
      checkState(rtspState == RTSP_STATE_READY || rtspState == RTSP_STATE_PLAYING);
      if (clockRangeStartEpochMs != C.TIME_UNSET && clockRangeEndEpochMs != C.TIME_UNSET) {
        // Replay session on a live server: PLAY carries an absolute UTC clock range shifted by the
        // in-window seek offset, with a fixed scale. The server streams the archive from that wall
        // clock position; the client timeline was VOD-ified at DESCRIBE time.
        String clockRangeHeader =
            RtspSessionTiming.formatClockRange(
                clockRangeStartEpochMs + offsetMs, clockRangeEndEpochMs);
        sendRequest(
            getRequestWithCommonHeaders(
                METHOD_PLAY,
                sessionId,
                /* additionalHeaders= */ ImmutableMap.of(
                    RtspHeaders.RANGE, clockRangeHeader, RtspHeaders.SCALE, "1.000000"),
                uri));
      } else {
        sendRequest(
            getRequestWithCommonHeaders(
                METHOD_PLAY,
                sessionId,
                /* additionalHeaders= */ ImmutableMap.of(
                    RtspHeaders.RANGE, RtspSessionTiming.getOffsetStartTimeTiming(offsetMs)),
                uri));
      }
    }

    public void sendTeardownRequest(Uri uri, @Nullable String sessionId) {
      if (sessionId == null
          || rtspState == RTSP_STATE_UNINITIALIZED
          || rtspState == RTSP_STATE_INIT) {
        // No need to perform session teardown before a session is set up, where the state is
        // RTSP_STATE_READY or RTSP_STATE_PLAYING.
        return;
      }
      rtspState = RTSP_STATE_INIT;
      sendRequest(
          getRequestWithCommonHeaders(
              METHOD_TEARDOWN, sessionId, /* additionalHeaders= */ ImmutableMap.of(), uri));
    }

    public void retryLastRequest() {
      checkNotNull(lastRequest);

      Multimap<String, String> headersMultiMap = lastRequest.headers.asMultiMap();
      Map<String, String> lastRequestHeaders = new HashMap<>();
      for (String headerName : headersMultiMap.keySet()) {
        if (headerName.equals(RtspHeaders.CSEQ)
            || headerName.equals(RtspHeaders.USER_AGENT)
            || headerName.equals(RtspHeaders.SESSION)
            || headerName.equals(RtspHeaders.AUTHORIZATION)) {
          // Clear session-specific header values.
          continue;
        }
        // Only include the header value that is written most recently.
        lastRequestHeaders.put(headerName, Iterables.getLast(headersMultiMap.get(headerName)));
      }

      sendRequest(
          getRequestWithCommonHeaders(
              lastRequest.method, sessionId, lastRequestHeaders, lastRequest.uri));
    }

    public void sendMethodNotAllowedResponse(int cSeq) {
      // RTSP status code 405: Method Not Allowed (RFC2326 Section 7.1.1).
      sendResponse(
          new RtspResponse(
              /* status= */ 405, new RtspHeaders.Builder(userAgent, sessionId, cSeq).build()));

      // The server could send a cSeq that is larger than the current stored cSeq. To maintain a
      // monotonically increasing cSeq number, this.cSeq needs to be reset to server's cSeq + 1.
      this.cSeq = max(this.cSeq, cSeq + 1);
    }

    private RtspRequest getRequestWithCommonHeaders(
        @RtspRequest.Method int method,
        @Nullable String sessionId,
        Map<String, String> additionalHeaders,
        Uri uri) {
      RtspHeaders.Builder headersBuilder = new RtspHeaders.Builder(userAgent, sessionId, cSeq++);

      if (rtspAuthenticationInfo != null) {
        checkNotNull(rtspAuthUserInfo);
        try {
          headersBuilder.add(
              RtspHeaders.AUTHORIZATION,
              rtspAuthenticationInfo.getAuthorizationHeaderValue(rtspAuthUserInfo, uri, method));
        } catch (ParserException e) {
          dispatchRtspError(new RtspPlaybackException(e));
        }
      }

      headersBuilder.addAll(additionalHeaders);
      return new RtspRequest(uri, method, headersBuilder.build(), /* messageBody= */ "");
    }

    private void sendRequest(RtspRequest request) {
      int cSeq = Integer.parseInt(checkNotNull(request.headers.get(RtspHeaders.CSEQ)));
      checkState(pendingRequests.get(cSeq) == null);
      pendingRequests.append(cSeq, request);
      emitProtocolEvent(
          RtspMessageUtil.toMethodString(request.method),
          C.INDEX_UNSET,
          "REQUEST",
          /* failureReason= */ null,
          requestedTransport(request),
          /* selectedTransport= */ null);
      if (controlRequestTimeoutMs > 0) {
        Runnable timeoutRunnable =
            () -> {
              if (pendingRequests.get(cSeq) != request) {
                return;
              }
              pendingRequests.remove(cSeq);
              pendingRequestTimeouts.remove(cSeq);
              dispatchRtspError(
                  new RtspPlaybackException("RTSP control request timeout"),
                  request,
                  C.INDEX_UNSET,
                  "RTSP control request timeout");
            };
        pendingRequestTimeouts.put(cSeq, timeoutRunnable);
        controlRequestHandler.postDelayed(timeoutRunnable, controlRequestTimeoutMs);
      }
      List<String> message = RtspMessageUtil.serializeRequest(request);
      maybeLogMessage(message);
      messageChannel.send(message);
      lastRequest = request;
    }

    private void sendResponse(RtspResponse response) {
      List<String> message = RtspMessageUtil.serializeResponse(response);
      maybeLogMessage(message);
      messageChannel.send(message);
    }
  }

  private void cancelRequestTimeout(int cSeq) {
    @Nullable Runnable timeoutRunnable = pendingRequestTimeouts.get(cSeq);
    if (timeoutRunnable != null) {
      controlRequestHandler.removeCallbacks(timeoutRunnable);
      pendingRequestTimeouts.remove(cSeq);
    }
  }

  @Nullable
  private static String requestedTransport(@Nullable RtspRequest request) {
    return request == null ? null : requestedTransport(request.headers.get(RtspHeaders.TRANSPORT));
  }

  @Nullable
  private static String requestedTransport(@Nullable String transportHeader) {
    if (transportHeader == null) {
      return null;
    }
    return transportHeader.toUpperCase(java.util.Locale.ROOT).contains("TCP") ? "TCP" : "UDP";
  }

  @Nullable
  private static String selectedTransport(@Nullable RtspResponse response) {
    return response == null ? null : selectedTransport(response.headers);
  }

  @Nullable
  private static String selectedTransport(RtspHeaders headers) {
    @Nullable String transportHeader = headers.get(RtspHeaders.TRANSPORT);
    if (transportHeader == null) {
      return null;
    }
    return transportHeader.toUpperCase(java.util.Locale.ROOT).contains("TCP") ? "TCP" : "UDP";
  }

  private static String authorityKey(Uri uri) {
    String host = uri.getHost();
    int port = uri.getPort() > 0 ? uri.getPort() : DEFAULT_RTSP_PORT;
    return (host == null ? "" : host.toLowerCase(java.util.Locale.ROOT)) + ":" + port;
  }

  private void emitProtocolEvent(
      String method,
      int statusCode,
      String phase,
      @Nullable String failureReason,
      @Nullable String requestedTransport,
      @Nullable String selectedTransport) {
    if (protocolEventExecutor == null || protocolEventListener == null || released) {
      return;
    }
    RtspProtocolEvent event =
        new RtspProtocolEvent(
            method,
            statusCode,
            phase,
            requestGeneration,
            attemptToken,
            requestedTransport == null ? "UNKNOWN" : requestedTransport,
            selectedTransport == null ? "UNKNOWN" : selectedTransport,
            failureReason == null ? null : RtspMessageUtil.redactErrorMessage(failureReason),
            protocolEventSequence++,
            SystemClock.elapsedRealtime());
    try {
      protocolEventExecutor.execute(
          () -> {
            if (released) {
              return;
            }
            try {
              protocolEventListener.onProtocolEvent(event);
            } catch (RuntimeException ignored) {
              // A diagnostics listener must never break the RTSP state machine.
            }
          });
    } catch (RuntimeException ignored) {
      // An executor shutting down must not break playback.
    }
  }

  private final class MessageListener implements RtspMessageChannel.MessageListener {

    private final Handler messageHandler;

    /**
     * Creates a new instance.
     *
     * <p>The constructor must be called on a {@link Looper} thread, on which all the received RTSP
     * messages are processed.
     */
    public MessageListener() {
      messageHandler = Util.createHandlerForCurrentLooper();
    }

    @Override
    public void onRtspMessageReceived(List<String> message) {
      messageHandler.post(() -> handleRtspMessage(message));
    }

    private void handleRtspMessage(List<String> message) {
      maybeLogMessage(message);

      if (RtspMessageUtil.isRtspResponse(message)) {
        handleRtspResponse(message);
      } else {
        handleRtspRequest(message);
      }
    }

    private void handleRtspRequest(List<String> message) {
      // Handling RTSP requests on the client is optional (RFC2326 Section 10). Decline all
      // requests with 'Method Not Allowed'.
      messageSender.sendMethodNotAllowedResponse(
          Integer.parseInt(
              checkNotNull(RtspMessageUtil.parseRequest(message).headers.get(RtspHeaders.CSEQ))));
    }

    private void handleRtspResponse(List<String> message) {
      RtspResponse response = RtspMessageUtil.parseResponse(message);

      int cSeq = Integer.parseInt(checkNotNull(response.headers.get(RtspHeaders.CSEQ)));

      @Nullable RtspRequest matchingRequest = pendingRequests.get(cSeq);
      if (matchingRequest == null) {
        return;
      } else {
        pendingRequests.remove(cSeq);
        cancelRequestTimeout(cSeq);
      }

      @RtspRequest.Method int requestMethod = matchingRequest.method;
      emitProtocolEvent(
          RtspMessageUtil.toMethodString(requestMethod),
          response.status,
          "RESPONSE",
          /* failureReason= */ null,
          requestedTransport(matchingRequest),
          selectedTransport(response.headers));

      try {
        switch (response.status) {
          case 200:
            break;
          case 301:
          case 302:
            // Redirection request.
            if (rtspState != RTSP_STATE_UNINITIALIZED) {
              rtspState = RTSP_STATE_INIT;
            }
            @Nullable String redirectionUriString = response.headers.get(RtspHeaders.LOCATION);
            if (redirectionUriString == null) {
              sessionInfoListener.onSessionTimelineRequestFailed(
                  "Redirection without new location.", /* cause= */ null);
            } else if (++redirectCount > MAX_REDIRECT_COUNT) {
              dispatchRtspError(
                  new RtspPlaybackException("REDIRECT_LIMIT_REACHED"),
                  matchingRequest,
                  response.status,
                  "REDIRECT_LIMIT_REACHED");
            } else {
              Uri redirectedUri = Uri.parse(redirectionUriString);
              Uri sanitizedRedirectUri = RtspMessageUtil.removeUserInfo(redirectedUri);
              if (!initialAuthority.equals(authorityKey(sanitizedRedirectUri))) {
                dispatchRtspError(
                    new RtspPlaybackException("REDIRECT_UNSUPPORTED"),
                    matchingRequest,
                    response.status,
                    "REDIRECT_UNSUPPORTED");
                return;
              }
              RtspClient.this.uri = sanitizedRedirectUri;
              // Source-level credentials may be reused only for the same authority. Never parse or
              // forward userinfo from a Location header.
              // Carrier servers reject a re-DESCRIBE on the redirected channel: reopen the
              // connection and restart the OPTIONS/DESCRIBE sequence on a clean session.
              emitProtocolEvent(
                  RtspMessageUtil.toMethodString(requestMethod),
                  response.status,
                  "REDIRECT",
                  "RECONNECT",
                  requestedTransport(matchingRequest),
                  /* selectedTransport= */ null);
              try {
                messageChannel.close();
                messageChannel = new RtspMessageChannel(new MessageListener());
                messageChannel.open(getSocket(RtspClient.this.uri));
                sessionId = null;
                pendingRequests.clear();
                // The new channel must not inherit the authentication state of the redirected one
                // (a 401 handshake on the old channel would poison the new session).
                receivedAuthorizationRequest = false;
                rtspAuthenticationInfo = null;
                messageSender.sendOptionsRequest(RtspClient.this.uri, null);
              } catch (IOException e) {
                dispatchRtspError(
                    new RtspPlaybackException("REDIRECT_CONNECT_FAILED", e),
                    matchingRequest,
                    response.status,
                    "REDIRECT_CONNECT_FAILED");
              }
            }
            return;
          case 401:
            if (rtspAuthUserInfo != null && !receivedAuthorizationRequest) {
              // Unauthorized.
              ImmutableList<String> wwwAuthenticateHeaders =
                  response.headers.values(RtspHeaders.WWW_AUTHENTICATE);
              if (wwwAuthenticateHeaders.isEmpty()) {
                throw ParserException.createForMalformedManifest(
                    "Missing WWW-Authenticate header in a 401 response.", /* cause= */ null);
              }

              for (int i = 0; i < wwwAuthenticateHeaders.size(); i++) {
                rtspAuthenticationInfo =
                    RtspMessageUtil.parseWwwAuthenticateHeader(wwwAuthenticateHeaders.get(i));
                if (rtspAuthenticationInfo.authenticationMechanism
                    == RtspAuthenticationInfo.DIGEST) {
                  // Prefers DIGEST when RTSP servers sends both BASIC and DIGEST auth info.
                  break;
                }
              }

              messageSender.retryLastRequest();
              receivedAuthorizationRequest = true;
              return;
            }
            // if unauthorized and no userInfo present, or previous authentication
            // unsuccessful, then dispatch RtspPlaybackException
            dispatchRtspError(
                new RtspPlaybackException(
                    RtspMessageUtil.toMethodString(requestMethod) + " " + response.status),
                matchingRequest,
                response.status,
                RtspMessageUtil.toMethodString(requestMethod) + " " + response.status);
            return;
          case 461:
            String exceptionMessage =
                RtspMessageUtil.toMethodString(requestMethod) + " " + response.status;
            // If request was SETUP with UDP transport protocol, then throw
            // RtspUdpUnsupportedTransportException.
            String transportHeaderValue =
                checkNotNull(matchingRequest.headers.get(RtspHeaders.TRANSPORT));
            dispatchRtspError(
                requestMethod == METHOD_SETUP && !transportHeaderValue.contains("TCP")
                    ? new RtspUdpUnsupportedTransportException(exceptionMessage)
                    : new RtspPlaybackException(exceptionMessage),
                matchingRequest,
                response.status,
                exceptionMessage);
            return;
          default:
            dispatchRtspError(
                new RtspPlaybackException(
                    RtspMessageUtil.toMethodString(requestMethod) + " " + response.status),
                matchingRequest,
                response.status,
                RtspMessageUtil.toMethodString(requestMethod) + " " + response.status);
            return;
        }

        switch (requestMethod) {
          case METHOD_OPTIONS:
            onOptionsResponseReceived(
                new RtspOptionsResponse(
                    response.status,
                    RtspMessageUtil.parsePublicHeader(response.headers.get(RtspHeaders.PUBLIC))));
            break;

          case METHOD_DESCRIBE:
            @Nullable String contentType = response.headers.get(RtspHeaders.CONTENT_TYPE);
            @Nullable Uri smilStreamUri =
                extractSmilStreamUri(response.messageBody, contentType, uri);
            if (smilStreamUri != null) {
              // DESCRIBE answered with SMIL instead of SDP: the hop to the real stream URI is a
              // redirect and obeys the same same-authority rule as a 3xx Location.
              if (++redirectCount > MAX_REDIRECT_COUNT) {
                dispatchRtspError(
                    new RtspPlaybackException("REDIRECT_LIMIT_REACHED"),
                    matchingRequest,
                    response.status,
                    "REDIRECT_LIMIT_REACHED");
                return;
              }
              Uri sanitizedSmilUri = RtspMessageUtil.removeUserInfo(smilStreamUri);
              if (!initialAuthority.equals(authorityKey(sanitizedSmilUri))) {
                dispatchRtspError(
                    new RtspPlaybackException("REDIRECT_UNSUPPORTED"),
                    matchingRequest,
                    response.status,
                    "REDIRECT_UNSUPPORTED");
                return;
              }
              RtspClient.this.uri = sanitizedSmilUri;
              emitProtocolEvent(
                  RtspMessageUtil.toMethodString(requestMethod),
                  response.status,
                  "REDIRECT",
                  "SMIL",
                  requestedTransport(matchingRequest),
                  /* selectedTransport= */ null);
              // SMIL targets live on the same server; reuse the current channel.
              messageSender.sendDescribeRequest(RtspClient.this.uri, sessionId);
              return;
            }
            // A DESCRIBE that answered with SDP ends the redirect chain.
            redirectCount = 0;
            onDescribeResponseReceived(
                new RtspDescribeResponse(
                    response.headers,
                    response.status,
                    SessionDescriptionParser.parse(response.messageBody)));
            break;

          case METHOD_SETUP:
            @Nullable String sessionHeaderString = response.headers.get(RtspHeaders.SESSION);
            @Nullable String transportHeaderString = response.headers.get(RtspHeaders.TRANSPORT);
            if (sessionHeaderString == null || transportHeaderString == null) {
              throw ParserException.createForMalformedManifest(
                  "Missing mandatory session or transport header", /* cause= */ null);
            }

            RtspSessionHeader sessionHeader =
                RtspMessageUtil.parseSessionHeader(sessionHeaderString);
            onSetupResponseReceived(
                new RtspSetupResponse(response.status, sessionHeader, transportHeaderString));
            break;

          case METHOD_PLAY:
            // Range header is optional for a PLAY response (RFC2326 Section 12). A clock= echo
            // (the server confirming the requested replay window) must not hit the npt-only
            // parser: it VOD-ifies into the confirmed window instead of failing the response.
            @Nullable String startTimingString = response.headers.get(RtspHeaders.RANGE);
            RtspSessionTiming timing =
                startTimingString == null
                    ? RtspSessionTiming.DEFAULT
                    : RtspSessionTiming.parsePlayResponseTiming(
                        startTimingString, clockRangeStartEpochMs, clockRangeEndEpochMs);

            // A clock= replay request answered with an explicit non-clock range means the server
            // ignored the requested window and started from live/now: the VOD-ified replay timeline
            // is not backed by the stream, so the app must not keep presenting it as a replay.
            // Emitted as its own phase (the response itself is a healthy 200). A response without a
            // Range header stays inconclusive — RFC2326 Section 12 makes it optional, so an
            // honoring-but-silent server must not be accused of ignoring the range.
            if (clockRangeStartEpochMs != C.TIME_UNSET
                && clockRangeEndEpochMs != C.TIME_UNSET
                && startTimingString != null
                && !startTimingString.trim().startsWith("clock")) {
              emitProtocolEvent(
                  RtspMessageUtil.toMethodString(METHOD_PLAY),
                  response.status,
                  "CLOCK_RANGE_UNCONFIRMED",
                  /* failureReason= */ null,
                  requestedTransport(matchingRequest),
                  selectedTransport(response.headers));
            }

            ImmutableList<RtspTrackTiming> trackTimingList;
            try {
              @Nullable String rtpInfoString = response.headers.get(RtspHeaders.RTP_INFO);
              trackTimingList =
                  rtpInfoString == null
                      ? ImmutableList.of()
                      : RtspTrackTiming.parseTrackTiming(rtpInfoString, uri);
            } catch (ParserException e) {
              trackTimingList = ImmutableList.of();
            }

            onPlayResponseReceived(new RtspPlayResponse(response.status, timing, trackTimingList));
            break;

          case METHOD_PAUSE:
            onPauseResponseReceived();
            break;

          case METHOD_GET_PARAMETER:
          case METHOD_TEARDOWN:
          case METHOD_PLAY_NOTIFY:
          case METHOD_RECORD:
          case METHOD_REDIRECT:
          case METHOD_ANNOUNCE:
          case METHOD_SET_PARAMETER:
            break;
          case METHOD_UNSET:
          default:
            throw new IllegalStateException();
        }
      } catch (ParserException | IllegalArgumentException e) {
        dispatchRtspError(new RtspPlaybackException(e), matchingRequest, response.status, e.getMessage());
      }
    }

    // Response handlers must only be called only on 200 (OK) responses.

    private void onOptionsResponseReceived(RtspOptionsResponse response) {
      if (keepAliveMonitor != null) {
        // Ignores the OPTIONS requests that are sent to keep RTSP connection alive.
        return;
      }

      if (serverSupportsDescribe(response.supportedMethods)) {
        messageSender.sendDescribeRequest(uri, sessionId);
      } else {
        sessionInfoListener.onSessionTimelineRequestFailed(
            "DESCRIBE not supported.", /* cause= */ null);
      }
    }

    private void onDescribeResponseReceived(RtspDescribeResponse response) {
      RtspSessionTiming sessionTiming = RtspSessionTiming.DEFAULT;
      @Nullable
      String sessionRangeAttributeString =
          response.sessionDescription.attributes.get(SessionDescription.ATTR_RANGE);
      if (sessionRangeAttributeString != null) {
        try {
          sessionTiming = RtspSessionTiming.parseTiming(sessionRangeAttributeString);
        } catch (ParserException e) {
          sessionInfoListener.onSessionTimelineRequestFailed("SDP format error.", /* cause= */ e);
          return;
        }
      }

      if (clockRangeStartEpochMs != C.TIME_UNSET && clockRangeEndEpochMs != C.TIME_UNSET) {
        // Replay override: a live SDP is VOD-ified into the override's seekable window (a SDP that
        // already declares a VOD range keeps its own timing).
        sessionTiming =
            RtspSessionTiming.resolveWithClockRangeOverride(
                sessionTiming,
                new long[] {clockRangeStartEpochMs, clockRangeEndEpochMs});
      }

      ImmutableList<RtspMediaTrack> tracks = buildTrackList(response, uri);
      if (tracks.isEmpty()) {
        sessionInfoListener.onSessionTimelineRequestFailed("No playable track.", /* cause= */ null);
        return;
      }

      sessionInfoListener.onSessionTimelineUpdated(sessionTiming, tracks);
      hasUpdatedTimelineAndTracks = true;
    }

    private void onSetupResponseReceived(RtspSetupResponse response) {
      checkState(rtspState != RTSP_STATE_UNINITIALIZED);

      rtspState = RTSP_STATE_READY;
      sessionId = response.sessionHeader.sessionId;
      sessionTimeoutMs = response.sessionHeader.timeoutMs;
      continueSetupRtspTrack();
    }

    private void onPlayResponseReceived(RtspPlayResponse response) {
      checkState(rtspState == RTSP_STATE_READY || rtspState == RTSP_STATE_PLAYING);

      rtspState = RTSP_STATE_PLAYING;
      if (keepAliveMonitor == null) {
        keepAliveMonitor =
            new KeepAliveMonitor(
                /* intervalMs= */ sessionTimeoutMs / DEFAULT_RTSP_KEEP_ALIVE_INTERVAL_DIVISOR);
        keepAliveMonitor.start();
      }

      pendingSeekPositionUs = C.TIME_UNSET;
      // onPlaybackStarted could initiate another seek request, which will set
      // pendingSeekPositionUs.
      playbackEventListener.onPlaybackStarted(
          Util.msToUs(response.sessionTiming.startTimeMs), response.trackTimingList);
    }

    private void onPauseResponseReceived() {
      checkState(rtspState == RTSP_STATE_PLAYING);

      rtspState = RTSP_STATE_READY;
      hasPendingPauseRequest = false;
      if (pendingSeekPositionUs != C.TIME_UNSET) {
        startPlayback(Util.usToMs(pendingSeekPositionUs));
      }
    }
  }

  /** Sends periodic OPTIONS requests to keep RTSP connection alive. */
  private final class KeepAliveMonitor implements Runnable, Closeable {

    private final Handler keepAliveHandler;
    private final long intervalMs;
    private boolean isStarted;

    /**
     * Creates a new instance.
     *
     * <p>Constructor must be invoked on the playback thread.
     *
     * @param intervalMs The time between consecutive RTSP keep-alive requests, in milliseconds.
     */
    public KeepAliveMonitor(long intervalMs) {
      this.intervalMs = intervalMs;
      keepAliveHandler = Util.createHandlerForCurrentLooper();
    }

    /** Starts Keep-alive. */
    public void start() {
      if (isStarted) {
        return;
      }

      isStarted = true;
      keepAliveHandler.postDelayed(this, intervalMs);
    }

    @Override
    public void run() {
      messageSender.sendOptionsRequest(uri, sessionId);
      keepAliveHandler.postDelayed(this, intervalMs);
    }

    @Override
    public void close() {
      isStarted = false;
      keepAliveHandler.removeCallbacks(this);
    }
  }
}
