/*
 * Copyright 2022 The Android Open Source Project
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
package com.google.android.exoplayer2.effect;

import static com.google.android.exoplayer2.util.Assertions.checkStateNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.graphics.SurfaceTexture;
import android.view.Surface;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.effect.GlShaderProgram.InputListener;
import com.google.android.exoplayer2.util.FrameInfo;
import com.google.android.exoplayer2.util.GlObjectsProvider;
import com.google.android.exoplayer2.util.GlTextureInfo;
import com.google.android.exoplayer2.util.GlUtil;
import com.google.android.exoplayer2.util.Log;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.util.VideoFrameProcessingException;
import com.google.common.base.Ascii;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Forwards externally produced frames that become available via a {@link SurfaceTexture} to an
 * {@link ExternalShaderProgram} for consumption.
 *
 * @deprecated com.google.android.exoplayer2 is deprecated. Please migrate to androidx.media3 (which
 *     contains the same ExoPlayer code). See <a
 *     href="https://developer.android.com/guide/topics/media/media3/getting-started/migration-guide">the
 *     migration guide</a> for more details, including a script to help with the migration.
 */
@Deprecated
/* package */ final class ExternalTextureManager implements TextureManager {

  private static final String TAG = "ExtTexMgr";
  private static final String TIMER_THREAD_NAME = "ExtTexMgr:Timer";

  /**
   * The time out in milliseconds after calling signalEndOfCurrentInputStream after which the input
   * stream is considered to have ended, even if not all expected frames have been received from the
   * decoder. This has been observed on some decoders.
   *
   * <p>Some emulator decoders are slower, hence using a longer timeout. Also on some emulators, GL
   * operation takes a long time to finish, the timeout could be a result of slow GL operation back
   * pressured the decoder, and the decoder is not able to decode another frame.
   */
  private static final long SURFACE_TEXTURE_TIMEOUT_MS =
      Ascii.toLowerCase(Util.DEVICE).contains("emulator")
              || Ascii.toLowerCase(Util.DEVICE).contains("generic")
          ? 10_000
          : 500;

  private final GlObjectsProvider glObjectsProvider;
  private final VideoFrameProcessingTaskExecutor videoFrameProcessingTaskExecutor;
  private final ExternalShaderProgram externalShaderProgram;
  private final int externalTexId;
  private final Surface surface;
  private final SurfaceTexture surfaceTexture;
  private final float[] textureTransformMatrix;
  private final Queue<FrameInfo> pendingFrames;
  private final ScheduledExecutorService forceEndOfStreamExecutorService;

  // Incremented on any thread, decremented on the GL thread only.
  private final AtomicInteger externalShaderProgramInputCapacity;
  // Counts the frames that are registered before flush but are made available after flush.
  // Read and written only on GL thread.
  private int numberOfFramesToDropOnBecomingAvailable;

  // Read and written only on GL thread.
  private int availableFrameCount;

  // Read and written on the GL thread only.
  private boolean currentInputStreamEnded;

  // The frame that is sent downstream and is not done processing yet.
  // Set to null on any thread. Read and set to non-null on the GL thread only.
  @Nullable private volatile FrameInfo currentFrame;

  // TODO(b/238302341) Remove the use of after flush task, block the calling thread instead.
  @Nullable private volatile VideoFrameProcessingTaskExecutor.Task onFlushCompleteTask;
  @Nullable private Future<?> forceSignalEndOfStreamFuture;

  // Whether to reject frames from the SurfaceTexture. Accessed only on GL thread.
  private boolean shouldRejectIncomingFrames;

  /**
   * Creates a new instance.
   *
   * @param glObjectsProvider The {@link GlObjectsProvider} for using EGL and GLES.
   * @param externalShaderProgram The {@link ExternalShaderProgram} for which this {@code
   *     ExternalTextureManager} will be set as the {@link InputListener}.
   * @param videoFrameProcessingTaskExecutor The {@link VideoFrameProcessingTaskExecutor}.
   * @throws VideoFrameProcessingException If a problem occurs while creating the external texture.
   */
  // The onFrameAvailableListener will not be invoked until the constructor returns.
  @SuppressWarnings("nullness:method.invocation.invalid")
  public ExternalTextureManager(
      GlObjectsProvider glObjectsProvider,
      ExternalShaderProgram externalShaderProgram,
      VideoFrameProcessingTaskExecutor videoFrameProcessingTaskExecutor)
      throws VideoFrameProcessingException {
    this.glObjectsProvider = glObjectsProvider;
    this.externalShaderProgram = externalShaderProgram;
    this.videoFrameProcessingTaskExecutor = videoFrameProcessingTaskExecutor;
    try {
      externalTexId = GlUtil.createExternalTexture();
    } catch (GlUtil.GlException e) {
      throw new VideoFrameProcessingException(e);
    }
    surfaceTexture = new SurfaceTexture(externalTexId);
    textureTransformMatrix = new float[16];
    pendingFrames = new ConcurrentLinkedQueue<>();
    forceEndOfStreamExecutorService = Util.newSingleThreadScheduledExecutor(TIMER_THREAD_NAME);
    externalShaderProgramInputCapacity = new AtomicInteger();
    surfaceTexture.setOnFrameAvailableListener(
        unused ->
            videoFrameProcessingTaskExecutor.submit(
                () -> {
                  DebugTraceUtil.logEvent(
                      DebugTraceUtil.EVENT_VFP_SURFACE_TEXTURE_INPUT, C.TIME_UNSET);
                  if (numberOfFramesToDropOnBecomingAvailable > 0) {
                    numberOfFramesToDropOnBecomingAvailable--;
                    surfaceTexture.updateTexImage();
                    maybeExecuteAfterFlushTask();
                  } else if (shouldRejectIncomingFrames) {
                    surfaceTexture.updateTexImage();
                    Log.w(
                        TAG,
                        "Dropping frame received on SurfaceTexture after forcing EOS: "
                            + surfaceTexture.getTimestamp() / 1000);
                  } else {
                    if (currentInputStreamEnded) {
                      restartForceSignalEndOfStreamTimer();
                    }
                    availableFrameCount++;
                    maybeQueueFrameToExternalShaderProgram();
                  }
                }));
    surface = new Surface(surfaceTexture);
  }

  @Override
  public void setDefaultBufferSize(int width, int height) {
    surfaceTexture.setDefaultBufferSize(width, height);
  }

  @Override
  public Surface getInputSurface() {
    return surface;
  }

  @Override
  public void onReadyToAcceptInputFrame() {
    videoFrameProcessingTaskExecutor.submit(
        () -> {
          externalShaderProgramInputCapacity.incrementAndGet();
          maybeQueueFrameToExternalShaderProgram();
        });
  }

  @Override
  public void onInputFrameProcessed(GlTextureInfo inputTexture) {
    videoFrameProcessingTaskExecutor.submit(
        () -> {
          currentFrame = null;
          if (currentInputStreamEnded && pendingFrames.isEmpty()) {
            // Reset because there could be further input streams after the current one ends.
            currentInputStreamEnded = false;
            externalShaderProgram.signalEndOfCurrentInputStream();
            DebugTraceUtil.logEvent(
                DebugTraceUtil.EVENT_EXTERNAL_TEXTURE_MANAGER_SIGNAL_EOS, C.TIME_END_OF_SOURCE);
            cancelForceSignalEndOfStreamTimer();
          } else {
            maybeQueueFrameToExternalShaderProgram();
          }
        });
  }

  @Override
  public void setOnFlushCompleteListener(@Nullable VideoFrameProcessingTaskExecutor.Task task) {
    onFlushCompleteTask = task;
  }

  @Override
  public void onFlush() {
    videoFrameProcessingTaskExecutor.submit(this::flush);
  }

  /**
   * Notifies the {@code ExternalTextureManager} that a frame with the given {@link FrameInfo} will
   * become available via the {@link SurfaceTexture} eventually.
   *
   * <p>Can be called on any thread. The caller must ensure that frames are registered in the
   * correct order.
   */
  @Override
  public void registerInputFrame(FrameInfo frame) {
    pendingFrames.add(frame);
    videoFrameProcessingTaskExecutor.submit(() -> shouldRejectIncomingFrames = false);
  }

  /**
   * Returns the number of {@linkplain #registerInputFrame(FrameInfo) registered} frames that have
   * not been sent to the downstream {@link ExternalShaderProgram} yet.
   *
   * <p>Can be called on any thread.
   */
  @Override
  public int getPendingFrameCount() {
    return pendingFrames.size();
  }

  @Override
  public void signalEndOfCurrentInputStream() {
    videoFrameProcessingTaskExecutor.submit(
        () -> {
          if (pendingFrames.isEmpty() && currentFrame == null) {
            externalShaderProgram.signalEndOfCurrentInputStream();
            DebugTraceUtil.logEvent(
                DebugTraceUtil.EVENT_EXTERNAL_TEXTURE_MANAGER_SIGNAL_EOS, C.TIME_END_OF_SOURCE);
            cancelForceSignalEndOfStreamTimer();
          } else {
            currentInputStreamEnded = true;
            restartForceSignalEndOfStreamTimer();
          }
        });
  }

  @Override
  public void release() {
    surfaceTexture.release();
    surface.release();
    forceEndOfStreamExecutorService.shutdownNow();
  }

  private void maybeExecuteAfterFlushTask() {
    if (onFlushCompleteTask == null || numberOfFramesToDropOnBecomingAvailable > 0) {
      return;
    }
    videoFrameProcessingTaskExecutor.submitWithHighPriority(onFlushCompleteTask);
  }

  // Methods that must be called on the GL thread.

  private void restartForceSignalEndOfStreamTimer() {
    cancelForceSignalEndOfStreamTimer();
    forceSignalEndOfStreamFuture =
        forceEndOfStreamExecutorService.schedule(
            () -> videoFrameProcessingTaskExecutor.submit(this::forceSignalEndOfStream),
            SURFACE_TEXTURE_TIMEOUT_MS,
            MILLISECONDS);
  }

  private void cancelForceSignalEndOfStreamTimer() {
    if (forceSignalEndOfStreamFuture != null) {
      forceSignalEndOfStreamFuture.cancel(/* mayInterruptIfRunning= */ false);
    }
    forceSignalEndOfStreamFuture = null;
  }

  private void forceSignalEndOfStream() {
    // Reset because there could be further input streams after the current one ends.
    Log.w(
        TAG,
        Util.formatInvariant(
            "Forcing EOS after missing %d frames for %d ms, with available frame count: %d",
            pendingFrames.size(), SURFACE_TEXTURE_TIMEOUT_MS, availableFrameCount));
    // Reset because there could be further input streams after the current one ends.
    currentInputStreamEnded = false;
    currentFrame = null;
    pendingFrames.clear();
    shouldRejectIncomingFrames = true;

    // Frames could be made available while waiting for OpenGL to finish processing. That is,
    // time out is triggered while waiting for the downstream shader programs to process a frame,
    // when there are frames available on the SurfaceTexture. This has only been observed on
    // emulators.
    removeAllSurfaceTextureFrames();
    signalEndOfCurrentInputStream();
  }

  private void flush() {
    // A frame that is registered before flush may arrive after flush.
    numberOfFramesToDropOnBecomingAvailable = pendingFrames.size() - availableFrameCount;
    removeAllSurfaceTextureFrames();
    externalShaderProgramInputCapacity.set(0);
    currentFrame = null;
    pendingFrames.clear();
    maybeExecuteAfterFlushTask();
  }

  private void removeAllSurfaceTextureFrames() {
    while (availableFrameCount > 0) {
      availableFrameCount--;
      surfaceTexture.updateTexImage();
    }
  }

  private void maybeQueueFrameToExternalShaderProgram() {
    if (externalShaderProgramInputCapacity.get() == 0
        || availableFrameCount == 0
        || currentFrame != null) {
      return;
    }

    surfaceTexture.updateTexImage();
    availableFrameCount--;
    this.currentFrame = pendingFrames.peek();

    FrameInfo currentFrame = checkStateNotNull(this.currentFrame);
    externalShaderProgramInputCapacity.decrementAndGet();
    surfaceTexture.getTransformMatrix(textureTransformMatrix);
    externalShaderProgram.setTextureTransformMatrix(textureTransformMatrix);
    long frameTimeNs = surfaceTexture.getTimestamp();
    long offsetToAddUs = currentFrame.offsetToAddUs;
    // Correct the presentation time so that GlShaderPrograms don't see the stream offset.
    long presentationTimeUs = (frameTimeNs / 1000) + offsetToAddUs;
    externalShaderProgram.queueInputFrame(
        glObjectsProvider,
        new GlTextureInfo(
            externalTexId,
            /* fboId= */ C.INDEX_UNSET,
            /* rboId= */ C.INDEX_UNSET,
            currentFrame.width,
            currentFrame.height),
        presentationTimeUs);
    checkStateNotNull(pendingFrames.remove());
    DebugTraceUtil.logEvent(DebugTraceUtil.EVENT_VFP_QUEUE_FRAME, presentationTimeUs);
    // If the queued frame is the last frame, end of stream will be signaled onInputFrameProcessed.
  }
}
