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
package androidx.media3.extractor.flv;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.common.util.ParsableByteArray;
import com.google.common.primitives.Bytes;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** Unit test for {@link ScriptTagPayloadReader}, focusing on malformed metadata robustness. */
@RunWith(RobolectricTestRunner.class)
public final class ScriptTagPayloadReaderTest {

  /** AMF string with a 2-byte big-endian length prefix. */
  private static byte[] amfString(String value) {
    byte[] raw = value.getBytes(StandardCharsets.UTF_8);
    return Bytes.concat(new byte[] {0x02, (byte) (raw.length >>> 8), (byte) raw.length}, raw);
  }

  @Test
  public void truncatedEcmaArrayIsIgnoredWithoutCrashing() {
    ScriptTagPayloadReader reader = new ScriptTagPayloadReader();
    // onMetaData declared as ECMA array with 3 entries, but the payload ends immediately.
    ParsableByteArray tag =
        new ParsableByteArray(
            Bytes.concat(amfString("onMetaData"), new byte[] {0x08, 0x00, 0x00, 0x00, 0x03}));

    assertThat(reader.parsePayload(tag, /* timeUs= */ 0L)).isFalse();
    assertThat(reader.getDurationUs()).isEqualTo(C.TIME_UNSET);
  }

  @Test
  public void truncatedStringIsIgnoredWithoutCrashing() {
    ScriptTagPayloadReader reader = new ScriptTagPayloadReader();
    // String declaring 10 bytes but carrying none.
    ParsableByteArray tag = new ParsableByteArray(new byte[] {0x02, 0x00, 0x0A});

    assertThat(reader.parsePayload(tag, /* timeUs= */ 0L)).isFalse();
    assertThat(reader.getDurationUs()).isEqualTo(C.TIME_UNSET);
  }

  @Test
  public void keyframesWithMismatchedArrayLengthsAreIgnored() {
    ScriptTagPayloadReader reader = new ScriptTagPayloadReader();
    // onMetaData with duration=0 (unset) and keyframes: filepositions has 1 entry, times has 0.
    byte[] keyframesObject =
        Bytes.concat(
            amfString("filepositions"),
            new byte[] {0x0A, 0x00, 0x00, 0x00, 0x01}, // strict array, 1 element
            new byte[] {0x00, 0x40, 0x59, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}, // number 100.0
            amfString("times"),
            new byte[] {0x0A, 0x00, 0x00, 0x00, 0x00}); // strict array, 0 elements
    byte[] objectEnd = new byte[] {0x00, 0x00, 0x09}; // object end marker
    ParsableByteArray tag =
        new ParsableByteArray(
            Bytes.concat(
                amfString("onMetaData"),
                new byte[] {0x08, 0x00, 0x00, 0x00, 0x01}, // ECMA array, 1 entry
                amfString("keyframes"),
                new byte[] {0x03}, // AMF object
                keyframesObject,
                objectEnd));

    assertThat(reader.parsePayload(tag, /* timeUs= */ 0L)).isFalse();
    assertThat(reader.getDurationUs()).isEqualTo(C.TIME_UNSET);
    assertThat(reader.getKeyFrameTimesUs()).isEmpty();
  }
}
