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
package androidx.media3.exoplayer.hls;

import static com.google.common.base.Preconditions.checkNotNull;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSourceInputStream;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.spec.AlgorithmParameterSpec;
import java.util.List;
import java.util.Map;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * A {@link DataSource} that decrypts data read from an upstream source, encrypted with AES-128 with
 * a 128-bit key and PKCS7 padding.
 *
 * <p>Note that this {@link DataSource} does not support being opened from arbitrary offsets. It is
 * designed specifically for reading whole files as defined in an HLS media playlist. For this
 * reason the implementation is private to the HLS package.
 */
/* package */ class Aes128DataSource implements DataSource {

  private final DataSource upstream;
  private final byte[] encryptionKey;
  private final byte[] encryptionIv;

  @Nullable private CipherInputStream cipherInputStream;

  /**
   * @param upstream The upstream {@link DataSource}.
   * @param encryptionKey The encryption key.
   * @param encryptionIv The encryption initialization vector.
   */
  public Aes128DataSource(DataSource upstream, byte[] encryptionKey, byte[] encryptionIv) {
    this.upstream = upstream;
    this.encryptionKey = encryptionKey;
    this.encryptionIv = encryptionIv;
  }

  @Override
  public final void addTransferListener(TransferListener transferListener) {
    checkNotNull(transferListener);
    upstream.addTransferListener(transferListener);
  }

  @Override
  public final long open(DataSpec dataSpec) throws IOException {
    Cipher cipher;
    try {
      cipher = getCipherInstance();
    } catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
      throw new RuntimeException(e);
    }

    Key cipherKey = new SecretKeySpec(encryptionKey, "AES");
    AlgorithmParameterSpec cipherIV = new IvParameterSpec(encryptionIv);

    try {
      cipher.init(Cipher.DECRYPT_MODE, cipherKey, cipherIV);
    } catch (InvalidKeyException | InvalidAlgorithmParameterException e) {
      // The key or IV material is not usable for AES-CBC. Throw a HlsAesDecryptionException so
      // hosts can attribute the failure to decryption rather than to network I/O. Note that a
      // RuntimeException would be wrapped by Loader.UnexpectedLoaderException and surface as an
      // unspecified error code, indistinguishable from other failures.
      throw new HlsAesDecryptionException(e);
    }

    DataSourceInputStream inputStream = new DataSourceInputStream(upstream, dataSpec);
    cipherInputStream = new CipherInputStream(inputStream, cipher);
    inputStream.open();

    return C.LENGTH_UNSET;
  }

  @Override
  public final int read(byte[] buffer, int offset, int length) throws IOException {
    checkNotNull(cipherInputStream);
    int bytesRead;
    try {
      bytesRead = cipherInputStream.read(buffer, offset, length);
    } catch (IOException e) {
      if (e instanceof HlsAesDecryptionException) {
        // Never wrap an already tagged exception a second time.
        throw e;
      }
      // CipherInputStream reports decryption failures of the final ciphertext block (e.g. a wrong
      // key or corrupted data) as an IOException wrapping the cipher's BadPaddingException or
      // IllegalBlockSizeException, whereas read errors from the upstream data source are
      // propagated unchanged. Only the former must be tagged as a decryption failure, so that
      // upstream failures keep surfacing as-is with their original error codes.
      if (e.getCause() instanceof BadPaddingException
          || e.getCause() instanceof IllegalBlockSizeException) {
        throw new HlsAesDecryptionException(e);
      }
      throw e;
    }
    if (bytesRead < 0) {
      return C.RESULT_END_OF_INPUT;
    }
    return bytesRead;
  }

  @Override
  @Nullable
  public final Uri getUri() {
    return upstream.getUri();
  }

  @Override
  public final Map<String, List<String>> getResponseHeaders() {
    return upstream.getResponseHeaders();
  }

  @Override
  public void close() throws IOException {
    if (cipherInputStream != null) {
      cipherInputStream = null;
      upstream.close();
    }
  }

  protected Cipher getCipherInstance() throws NoSuchPaddingException, NoSuchAlgorithmException {
    return Cipher.getInstance("AES/CBC/PKCS7Padding");
  }
}
