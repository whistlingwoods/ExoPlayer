/*
 * Copyright 2023 The Android Open Source Project
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

package com.google.android.exoplayer2.text;

import com.google.android.exoplayer2.C;
import com.google.common.collect.ImmutableList;
import java.util.List;

/**
 * A list of {@link Cue} instances with a start time and duration.
 *
 * @deprecated com.google.android.exoplayer2 is deprecated. Please migrate to androidx.media3 (which
 *     contains the same ExoPlayer code). See <a
 *     href="https://developer.android.com/guide/topics/media/media3/getting-started/migration-guide">the
 *     migration guide</a> for more details, including a script to help with the migration.
 */
@Deprecated
public class CuesWithTiming {

  /** The cues to show on screen. */
  public final ImmutableList<Cue> cues;

  /**
   * The time at which {@link #cues} should be shown on screen, in microseconds, or {@link
   * C#TIME_UNSET} if not known.
   *
   * <p>The time base of this depends on the context from which this instance was obtained.
   */
  public final long startTimeUs;

  /**
   * The duration for which {@link #cues} should be shown on screen, in microseconds, or {@link
   * C#TIME_UNSET} if not known.
   *
   * <p>If this value is set then {@link #cues} from multiple instances may be shown on the screen
   * simultaneously (if their durations overlap). If this value is {@link C#TIME_UNSET} then {@link
   * #cues} should be shown on the screen until the {@link #startTimeUs} of the next instance.
   */
  public final long durationUs;

  /** Creates an instance. */
  public CuesWithTiming(List<Cue> cues, long startTimeUs, long durationUs) {
    this.cues = ImmutableList.copyOf(cues);
    this.startTimeUs = startTimeUs;
    this.durationUs = durationUs;
  }
}
