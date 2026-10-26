/*
 * Copyright (C) 2018 The Android Open Source Project
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
package androidx.media3.exoplayer.hls;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Test for {@link Aes128DataSource}. */
@RunWith(AndroidJUnit4.class)
public class Aes128DataSourceTest {

  @Test
  public void test_OpenCallsUpstreamOpen_CloseCallsUpstreamClose() throws IOException {
    UpstreamDataSource upstream = new UpstreamDataSource();
    Aes128DataSource testInstance = new TestAes123DataSource(upstream, new byte[16], new byte[16]);
    assertThat(upstream.opened).isFalse();

    Uri uri = Uri.parse("http.abc.com/def");
    testInstance.open(new DataSpec(uri));
    assertThat(upstream.opened).isTrue();

    testInstance.close();
    assertThat(upstream.opened).isFalse();
  }

  @Test
  public void test_OpenCallsUpstreamThrowingOpen_CloseCallsUpstreamClose() throws IOException {
    UpstreamDataSource upstream =
        new UpstreamDataSource() {
          @Override
          public long open(DataSpec dataSpec) throws IOException {
            throw new IOException();
          }
        };
    Aes128DataSource testInstance = new TestAes123DataSource(upstream, new byte[16], new byte[16]);
    assertThat(upstream.opened).isFalse();

    Uri uri = Uri.parse("http.abc.com/def");
    try {
      testInstance.open(new DataSpec(uri));
    } catch (IOException e) {
      // Expected.
    }
    assertThat(upstream.opened).isFalse();
    assertThat(upstream.closedCalled).isFalse();

    // Even though the upstream open call failed, close should still call close on the upstream as
    // per the contract of DataSource.
    testInstance.close();
    assertThat(upstream.closedCalled).isTrue();
  }

  @Test
  public void test_openWithInvalidKeyLengthThrowsHlsAesDecryptionException() {
    UpstreamDataSource upstream = new UpstreamDataSource();
    Aes128DataSource testInstance = new TestAes123DataSource(upstream, new byte[15], new byte[16]);

    HlsAesDecryptionException thrown =
        assertThrows(
            HlsAesDecryptionException.class,
            () -> testInstance.open(new DataSpec(Uri.parse("http.abc.com/def"))));

    assertThat(thrown).hasCauseThat().isInstanceOf(InvalidKeyException.class);
    // The key length check fails before any data is requested from the upstream.
    assertThat(upstream.opened).isFalse();
  }

  @Test
  public void test_readWithCorruptedCiphertextThrowsHlsAesDecryptionException() throws Exception {
    byte[] key = new byte[16];
    byte[] iv = new byte[16];
    for (int i = 0; i < 16; i++) {
      key[i] = (byte) i;
      iv[i] = (byte) (0x10 + i);
    }
    byte[] plaintext = new byte[48];
    for (int i = 0; i < plaintext.length; i++) {
      plaintext[i] = (byte) i;
    }
    byte[] ciphertext = encryptWithTestCipher(plaintext, key, iv);
    // Corrupt the last byte of the second-to-last ciphertext block. With AES-CBC this
    // deterministically corrupts the padding of the final plaintext block (the last byte changes
    // from 0x10 to 0x11, an invalid PKCS7 padding length), so the cipher is guaranteed to fail
    // while decrypting.
    ciphertext[ciphertext.length - 2] ^= (byte) 0x01;

    UpstreamDataSource upstream = new ByteDataUpstreamDataSource(ciphertext);
    Aes128DataSource testInstance = new TestAes123DataSource(upstream, key, iv);
    testInstance.open(new DataSpec(Uri.parse("http.abc.com/def")));
    byte[] readBuffer = new byte[1024];

    HlsAesDecryptionException thrown =
        assertThrows(
            HlsAesDecryptionException.class,
            // Decryption failures are only detected when the end of the input is reached, so read
            // until then.
            () -> {
              while (testInstance.read(readBuffer, 0, readBuffer.length)
                  != C.RESULT_END_OF_INPUT) {}
            });

    assertThat(thrown).hasCauseThat().isInstanceOf(IOException.class);
    // Aes128DataSource wraps the CipherInputStream's IOException, whose own cause is the cipher's
    // BadPaddingException (corrupting a CBC ciphertext block deterministically breaks the final
    // block's PKCS7 padding; IllegalBlockSizeException is unreachable for a one-byte flip).
    assertThat(thrown).hasCauseThat().hasCauseThat().isInstanceOf(BadPaddingException.class);

    testInstance.close();
  }

  @Test
  public void test_upstreamOpenFailureSurfacesAsIs() {
    IOException openFailure = new IOException("upstream open failed");
    UpstreamDataSource upstream =
        new UpstreamDataSource() {
          @Override
          public long open(DataSpec dataSpec) throws IOException {
            throw openFailure;
          }
        };
    Aes128DataSource testInstance = new TestAes123DataSource(upstream, new byte[16], new byte[16]);

    IOException thrown =
        assertThrows(
            IOException.class,
            () -> testInstance.open(new DataSpec(Uri.parse("http.abc.com/def"))));

    // Failures of the upstream data source must not be tagged as decryption failures.
    assertThat(thrown).isSameInstanceAs(openFailure);
    assertThat(thrown).isNotInstanceOf(HlsAesDecryptionException.class);
  }

  @Test
  public void test_upstreamReadFailureSurfacesAsIs() throws IOException {
    IOException readFailure = new IOException("upstream read failed");
    UpstreamDataSource upstream =
        new UpstreamDataSource() {
          @Override
          public int read(byte[] buffer, int offset, int length) throws IOException {
            throw readFailure;
          }
        };
    Aes128DataSource testInstance = new TestAes123DataSource(upstream, new byte[16], new byte[16]);
    testInstance.open(new DataSpec(Uri.parse("http.abc.com/def")));

    IOException thrown =
        assertThrows(IOException.class, () -> testInstance.read(new byte[1024], 0, 1024));

    // Failures of the upstream data source must pass through CipherInputStream unchanged and must
    // not be tagged as decryption failures.
    assertThat(thrown).isSameInstanceAs(readFailure);
    assertThat(thrown).isNotInstanceOf(HlsAesDecryptionException.class);

    testInstance.close();
  }

  private static class TestAes123DataSource extends Aes128DataSource {

    public TestAes123DataSource(DataSource upstream, byte[] encryptionKey, byte[] encryptionIv) {
      super(upstream, encryptionKey, encryptionIv);
    }

    @Override
    protected Cipher getCipherInstance() throws NoSuchPaddingException, NoSuchAlgorithmException {
      try {
        return super.getCipherInstance();
      } catch (NoSuchAlgorithmException e) {
        // Some host machines may not provide an algorithm for "AES/CBC/PKCS7Padding", however on
        // such machines it's possible to get a functionally identical algorithm by requesting
        // "AES/CBC/PKCS5Padding".
        return Cipher.getInstance("AES/CBC/PKCS5Padding");
      }
    }
  }

  private static class UpstreamDataSource implements DataSource {

    public boolean opened;
    public boolean closedCalled;

    @Override
    public void addTransferListener(TransferListener transferListener) {}

    @Override
    public long open(DataSpec dataSpec) throws IOException {
      opened = true;
      return C.LENGTH_UNSET;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      return C.RESULT_END_OF_INPUT;
    }

    @Override
    public Uri getUri() {
      return null;
    }

    @Override
    public void close() {
      opened = false;
      closedCalled = true;
    }
  }

  /** An {@link UpstreamDataSource} that serves a fixed byte array, as a decryptable stream would. */
  private static final class ByteDataUpstreamDataSource extends UpstreamDataSource {

    private final byte[] data;
    private int position;

    public ByteDataUpstreamDataSource(byte[] data) {
      this.data = data;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) {
      if (position >= data.length) {
        return C.RESULT_END_OF_INPUT;
      }
      int bytesToRead = Math.min(length, data.length - position);
      System.arraycopy(data, position, buffer, offset, bytesToRead);
      position += bytesToRead;
      return bytesToRead;
    }
  }

  private static byte[] encryptWithTestCipher(byte[] data, byte[] key, byte[] iv)
      throws GeneralSecurityException {
    // Encrypt with the same cipher transformation that TestAes123DataSource uses for decryption,
    // including its PKCS5Padding fallback for host machines without PKCS7Padding support.
    Cipher cipher = new TestAes123DataSource(new UpstreamDataSource(), key, iv).getCipherInstance();
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
    return cipher.doFinal(data);
  }
}
