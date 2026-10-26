/*
 * Copyright (C) 2026 The LiveSource Authors
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

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import java.io.IOException;

/**
 * Thrown when decryption of an HLS AES-128 encrypted segment fails, for example because the
 * decryption key or IV is wrong, or the encrypted segment payload is corrupted.
 *
 * <p>Host applications can catch this exception to attribute playback failures to HLS AES-128
 * decryption problems, as opposed to network problems. Inspect the {@link #getCause() cause} chain
 * for the underlying reason, e.g. a {@link javax.crypto.BadPaddingException} or {@link
 * javax.crypto.IllegalBlockSizeException} raised by the cipher, or an {@link
 * java.security.InvalidKeyException} raised for key material of invalid length.
 *
 * <p>This class is a LiveSource fork customization and is not part of upstream media3.
 */
@UnstableApi
public class HlsAesDecryptionException extends IOException {

  /**
   * Creates a new instance.
   *
   * @param message The detail message.
   */
  public HlsAesDecryptionException(String message) {
    super(message);
  }

  /**
   * Creates a new instance.
   *
   * @param message The detail message.
   * @param cause The reason of the failure.
   */
  public HlsAesDecryptionException(String message, @Nullable Throwable cause) {
    super(message, cause);
  }

  /**
   * Creates a new instance.
   *
   * @param cause The reason of the failure.
   */
  public HlsAesDecryptionException(Throwable cause) {
    super(cause);
  }
}
