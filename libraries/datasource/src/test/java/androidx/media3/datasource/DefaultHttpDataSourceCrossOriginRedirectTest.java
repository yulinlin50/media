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
package androidx.media3.datasource;

import static com.google.common.truth.Truth.assertThat;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Unit tests for credential header stripping on cross-origin redirect hops in {@link
 * DefaultHttpDataSource} ({@link
 * DefaultHttpDataSource.Factory#setStripCredentialHeadersOnCrossOriginRedirects(boolean)}).
 *
 * <p>Two {@link MockWebServer} instances on different ports act as two origins (same host, but
 * host:port pairs differ), so a redirect from one server to the other is a cross-origin redirect.
 */
@RunWith(AndroidJUnit4.class)
public class DefaultHttpDataSourceCrossOriginRedirectTest {

  private static final String COOKIE = "session=secret-cookie";
  private static final String AUTHORIZATION = "Bearer secret-token";
  private static final String REFERER = "https://origin.example/watch";
  private static final String USER_AGENT = "test-agent";

  private MockWebServer serverA;
  private MockWebServer serverB;

  @Before
  public void setUpServers() throws Exception {
    serverA = new MockWebServer();
    serverA.start();
    serverB = new MockWebServer();
    serverB.start();
  }

  @After
  public void tearDownServers() throws Exception {
    serverA.shutdown();
    serverB.shutdown();
  }

  @Test
  public void crossOriginRedirect_stripsCookieAndAuthorization_keepsOtherHeaders() throws Exception {
    serverA.enqueue(
        new MockResponse().setResponseCode(302).setHeader("Location", serverB.url("/target")));
    serverB.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

    DefaultHttpDataSource dataSource =
        new DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setStripCredentialHeadersOnCrossOriginRedirects(true)
            .setDefaultRequestProperties(
                ImmutableMap.of(
                    "Cookie", COOKIE, "Referer", REFERER, "Accept", "application/vnd.apple.mpegurl"))
            .createDataSource();
    dataSource.setRequestProperty("Authorization", AUTHORIZATION);

    openAndDrain(
        dataSource, new DataSpec.Builder().setUri(Uri.parse(serverA.url("/start").toString())).build());

    RecordedRequest targetRequest = serverB.takeRequest(10, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(targetRequest).isNotNull();
    assertThat(targetRequest.getHeader("Cookie")).isNull();
    assertThat(targetRequest.getHeader("Authorization")).isNull();
    // Non-credential headers are preserved so that redirect chains keep working (anti-hotlink
    // checks, CDN compatibility).
    assertThat(targetRequest.getHeader("Referer")).isEqualTo(REFERER);
    assertThat(targetRequest.getHeader("Accept")).isEqualTo("application/vnd.apple.mpegurl");
    assertThat(targetRequest.getHeader("User-Agent")).isEqualTo(USER_AGENT);
  }

  @Test
  public void crossOriginRedirect_stripsCredentialHeadersFromDataSpec() throws Exception {
    serverA.enqueue(
        new MockResponse().setResponseCode(302).setHeader("Location", serverB.url("/target")));
    serverB.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

    DefaultHttpDataSource dataSource =
        new DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setStripCredentialHeadersOnCrossOriginRedirects(true)
            .createDataSource();
    Map<String, String> requestHeaders =
        ImmutableMap.of("Cookie", COOKIE, "Accept", "application/vnd.apple.mpegurl");
    DataSpec dataSpec =
        new DataSpec.Builder()
            .setUri(Uri.parse(serverA.url("/start").toString()))
            .setHttpRequestHeaders(requestHeaders)
            .build();
    openAndDrain(dataSource, dataSpec);

    RecordedRequest targetRequest = serverB.takeRequest(10, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(targetRequest).isNotNull();
    assertThat(targetRequest.getHeader("Cookie")).isNull();
    assertThat(targetRequest.getHeader("Accept")).isEqualTo("application/vnd.apple.mpegurl");
  }

  @Test
  public void sameOriginRedirect_keepsCookie() throws Exception {
    serverA.enqueue(
        new MockResponse().setResponseCode(302).setHeader("Location", serverA.url("/target")));
    serverA.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

    DefaultHttpDataSource dataSource =
        new DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setStripCredentialHeadersOnCrossOriginRedirects(true)
            .setDefaultRequestProperties(ImmutableMap.of("Cookie", COOKIE))
            .createDataSource();
    openAndDrain(
        dataSource, new DataSpec.Builder().setUri(Uri.parse(serverA.url("/start").toString())).build());

    RecordedRequest targetRequest = serverA.takeRequest(10, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(targetRequest).isNotNull();
    assertThat(targetRequest.getHeader("Cookie")).isEqualTo(COOKIE);
  }

  @Test
  public void strippingDisabled_crossOriginRedirect_keepsCookie() throws Exception {
    serverA.enqueue(
        new MockResponse().setResponseCode(302).setHeader("Location", serverB.url("/target")));
    serverB.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

    DefaultHttpDataSource dataSource =
        new DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(ImmutableMap.of("Cookie", COOKIE))
            .createDataSource();
    openAndDrain(
        dataSource, new DataSpec.Builder().setUri(Uri.parse(serverA.url("/start").toString())).build());

    RecordedRequest targetRequest = serverB.takeRequest(10, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(targetRequest).isNotNull();
    assertThat(targetRequest.getHeader("Cookie")).isEqualTo(COOKIE);
  }

  @Test
  public void multiHop_stripsAtFirstCrossOriginHop() throws Exception {
    // Same-origin hop (A -> A) keeps the cookie, the subsequent cross-origin hop (A -> B) strips.
    serverA.enqueue(
        new MockResponse().setResponseCode(302).setHeader("Location", serverA.url("/hop2")));
    serverA.enqueue(
        new MockResponse().setResponseCode(302).setHeader("Location", serverB.url("/target")));
    serverB.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

    DefaultHttpDataSource dataSource =
        new DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setStripCredentialHeadersOnCrossOriginRedirects(true)
            .setDefaultRequestProperties(ImmutableMap.of("Cookie", COOKIE, "Referer", REFERER))
            .createDataSource();
    openAndDrain(
        dataSource, new DataSpec.Builder().setUri(Uri.parse(serverA.url("/start").toString())).build());

    RecordedRequest hop2Request = serverA.takeRequest(10, java.util.concurrent.TimeUnit.SECONDS);
    RecordedRequest targetRequest = serverB.takeRequest(10, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(hop2Request).isNotNull();
    assertThat(hop2Request.getHeader("Cookie")).isEqualTo(COOKIE);
    assertThat(targetRequest).isNotNull();
    assertThat(targetRequest.getHeader("Cookie")).isNull();
    assertThat(targetRequest.getHeader("Referer")).isEqualTo(REFERER);
  }

  @Test
  public void crossOriginRedirect_rangeHeaderFromDataSpec_isPreserved() throws Exception {
    serverA.enqueue(
        new MockResponse().setResponseCode(302).setHeader("Location", serverB.url("/target")));
    serverB.enqueue(new MockResponse().setResponseCode(206).setBody("partial"));

    DefaultHttpDataSource dataSource =
        new DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setStripCredentialHeadersOnCrossOriginRedirects(true)
            .createDataSource();
    DataSpec dataSpec =
        new DataSpec.Builder()
            .setUri(Uri.parse(serverA.url("/start").toString()))
            .setPosition(1_234L)
            .setLength(C.LENGTH_UNSET)
            .build();
    openAndDrain(dataSource, dataSpec);

    RecordedRequest targetRequest = serverB.takeRequest(10, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(targetRequest).isNotNull();
    assertThat(targetRequest.getHeader("Range")).isEqualTo("bytes=1234-");
  }

  /**
   * Opens the data source and reads it to the end, so that the full redirect chain is followed.
   */
  private static void openAndDrain(DefaultHttpDataSource dataSource, DataSpec dataSpec)
      throws Exception {
    dataSource.open(dataSpec);
    byte[] buffer = new byte[8192];
    while (dataSource.read(buffer, /* offset= */ 0, buffer.length) != C.RESULT_END_OF_INPUT) {
      // Read until the response is fully consumed.
    }
    dataSource.close();
  }
}
