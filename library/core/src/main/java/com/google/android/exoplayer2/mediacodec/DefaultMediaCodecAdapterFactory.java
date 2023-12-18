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
package com.google.android.exoplayer2.mediacodec;

import static java.lang.annotation.ElementType.TYPE_USE;

import android.media.MediaCodec;
import androidx.annotation.IntDef;
import com.google.android.exoplayer2.util.Log;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.Util;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The default {@link MediaCodecAdapter.Factory}.
 *
 * <p>By default, this factory {@link #createAdapter creates} {@link AsynchronousMediaCodecAdapter}
 * instances on devices with API level &gt;= 31 (Android 12+). For devices with older API versions,
 * the default behavior is to create {@link SynchronousMediaCodecAdapter} instances. The factory
 * offers APIs to force the creation of {@link AsynchronousMediaCodecAdapter} (applicable for
 * devices with API &gt;= 23) or {@link SynchronousMediaCodecAdapter} instances.
 *
 * @deprecated com.google.android.exoplayer2 is deprecated. Please migrate to androidx.media3 (which
 *     contains the same ExoPlayer code). See <a
 *     href="https://developer.android.com/guide/topics/media/media3/getting-started/migration-guide">the
 *     migration guide</a> for more details, including a script to help with the migration.
 */
@Deprecated
public final class DefaultMediaCodecAdapterFactory implements MediaCodecAdapter.Factory {

  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({MODE_DEFAULT, MODE_ENABLED, MODE_DISABLED})
  private @interface Mode {}

  private static final int MODE_DEFAULT = 0;
  private static final int MODE_ENABLED = 1;
  private static final int MODE_DISABLED = 2;

  private static final String TAG = "DMCodecAdapterFactory";

  private @Mode int asynchronousMode;
  private boolean asyncCryptoFlagEnabled;

  public DefaultMediaCodecAdapterFactory() {
    asynchronousMode = MODE_DEFAULT;
    asyncCryptoFlagEnabled = true;
  }

  /**
   * Forces this factory to always create {@link AsynchronousMediaCodecAdapter} instances, provided
   * the device API level is &gt;= 23. For devices with API level &lt; 23, the factory will create
   * {@link SynchronousMediaCodecAdapter SynchronousMediaCodecAdapters}.
   *
   * @return This factory, for convenience.
   */
  @CanIgnoreReturnValue
  public DefaultMediaCodecAdapterFactory forceEnableAsynchronous() {
    asynchronousMode = MODE_ENABLED;
    return this;
  }

  /**
   * Forces the factory to always create {@link SynchronousMediaCodecAdapter} instances.
   *
   * @return This factory, for convenience.
   */
  @CanIgnoreReturnValue
  public DefaultMediaCodecAdapterFactory forceDisableAsynchronous() {
    asynchronousMode = MODE_DISABLED;
    return this;
  }

  /**
   * Sets whether to enable {@link MediaCodec#CONFIGURE_FLAG_USE_CRYPTO_ASYNC} on API 34 and above
   * for {@link AsynchronousMediaCodecAdapter} instances.
   *
   * <p>This method is experimental. Its default value may change, or it may be renamed or removed
   * in a future release.
   */
  @CanIgnoreReturnValue
  public DefaultMediaCodecAdapterFactory experimentalSetAsyncCryptoFlagEnabled(
      boolean enableAsyncCryptoFlag) {
    asyncCryptoFlagEnabled = enableAsyncCryptoFlag;
    return this;
  }

  @Override
  public MediaCodecAdapter createAdapter(MediaCodecAdapter.Configuration configuration)
      throws IOException {
    if (Util.SDK_INT >= 23
        && (asynchronousMode == MODE_ENABLED
            || (asynchronousMode == MODE_DEFAULT && Util.SDK_INT >= 31))) {
      int trackType = MimeTypes.getTrackType(configuration.format.sampleMimeType);
      Log.i(
          TAG,
          "Creating an asynchronous MediaCodec adapter for track type "
              + Util.getTrackTypeString(trackType));
      AsynchronousMediaCodecAdapter.Factory factory =
          new AsynchronousMediaCodecAdapter.Factory(trackType);
      factory.experimentalSetAsyncCryptoFlagEnabled(asyncCryptoFlagEnabled);
      return factory.createAdapter(configuration);
    }
    return new SynchronousMediaCodecAdapter.Factory().createAdapter(configuration);
  }
}
