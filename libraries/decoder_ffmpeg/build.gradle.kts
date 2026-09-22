// Copyright (C) 2016 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
plugins { id("media3.android-library") }

android {
  namespace = "androidx.media3.decoder.ffmpeg"

  sourceSets { getByName("androidTest").assets.directories.add("../test_data/src/test/assets") }
}

// FFmpeg static libs (jni/ffmpeg/android-libs) are only produced for the two
// ABIs the app ships (scripts/build-ffmpeg-ubuntu.sh in the app repo);
// restrict the native build so ninja doesn't demand the missing ones.
// (AGP 9 removed the module-level ndk {} block; ABI selection lives on the
// variant API's externalNativeBuild.)
androidComponents {
  onVariants { variant ->
    variant.externalNativeBuild?.abiFilters?.set(setOf("armeabi-v7a", "arm64-v8a"))
  }
}

// Configure the native build only if ffmpeg is present to avoid gradle sync
// failures if ffmpeg hasn't been built according to the README instructions.
if (project.file("src/main/jni/ffmpeg").exists()) {
  android.externalNativeBuild.cmake.path = file("src/main/jni/CMakeLists.txt")
  // LINT.IfChange
  // Should match cmake_minimum_required.
  android.externalNativeBuild.cmake.version = "3.21.0+"
  // LINT.ThenChange(src/main/jni/CMakeLists.txt)
} else {
  // Not just a sync-failure guard: without jni/ffmpeg the AAR ships no
  // libffmpegJNI.so and FFmpeg software decoding silently disappears from
  // builds that would otherwise succeed. Make that state loud.
  logger.warn(
      "[decoder_ffmpeg] src/main/jni/ffmpeg is MISSING — libffmpegJNI.so will NOT be built and "
          + "FFmpeg software audio decoding (AC-3/E-AC-3/DTS/MP2/...) will silently be unavailable"
          + " at runtime. Build FFmpeg first (see libraries/decoder_ffmpeg/README.md).")
}

dependencies {
  api(project(":lib-decoder"))
  // TODO(b/203752526): Remove this dependency.
  implementation(project(":lib-exoplayer"))
  implementation(libs.androidx.annotation)
  testImplementation(project(":test-utils"))
  testImplementation(libs.robolectric)
  androidTestImplementation(project(":test-utils"))
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.truth)
  androidTestImplementation(libs.androidx.core.ktx)
  androidTestImplementation(libs.kotlinx.coroutines.android)
  androidTestImplementation(libs.test.parameter.injector)
}
