/*
 * Copyright (c) 2016, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *  * Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *  * Redistributions in binary form must reproduce the above
 *    copyright notice, this list of conditions and the following
 *    disclaimer in the documentation and/or other materials provided
 *    with the distribution.
 *  * Neither the name of The Linux Foundation nor the names of its
 *    contributors may be used to endorse or promote products derived
 *    from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.codeaurora.snapcam.filter;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import org.codeaurora.snapcam.filter.ClearSightNativeEngine.CamSystemCalibrationData;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCaptureSession.CaptureCallback;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.InputConfiguration;
import android.media.Image;
import android.media.Image.Plane;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.media.ImageWriter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemProperties;
import android.util.Log;
import android.util.SparseLongArray;
import android.view.Surface;

import com.android.camera.CaptureModule;
import com.android.camera.Exif;
import com.android.camera.exif.ExifInterface;
import com.android.camera.MediaSaveService;
import com.android.camera.MediaSaveService.OnMediaSavedListener;
import com.android.camera.PhotoModule.NamedImages;
import com.android.camera.PhotoModule.NamedImages.NamedEntity;
import com.android.camera.Storage;

public class ClearSightImageProcessor {
    private static final String TAG = "ClearSightImageProcessor";
    private static final String PERSIST_TIMESTAMP_LIMIT_KEY = "persist.camera.cs.threshold";
    private static final String PERSIST_BURST_COUNT_KEY = "persist.camera.cs.burstcount";
    private static final String PERSIST_DUMP_FRAMES_KEY = "persist.camera.cs.dumpframes";
    private static final String PERSIST_DUMP_YUV_KEY = "persist.camera.cs.dumpyuv";

    private static final long DEFAULT_TIMESTAMP_THRESHOLD_MS = 10;
    private static final int DEFAULT_IMAGES_TO_BURST = 4;

    private static final int MSG_START_CAPTURE = 0;
    private static final int MSG_NEW_IMG = 1;
    private static final int MSG_NEW_CAPTURE_RESULT = 2;
    private static final int MSG_NEW_CAPTURE_FAIL = 3;
    private static final int MSG_NEW_REPROC_RESULT = 4;
    private static final int MSG_NEW_REPROC_FAIL = 5;
    private static final int MSG_END_CAPTURE = 6;

    private static final int CAM_TYPE_BAYER = 0;
    private static final int CAM_TYPE_MONO = 1;
    private static final int NUM_CAM = 2;

    private static CameraCharacteristics.Key<byte[]> OTP_CALIB_BLOB =
            new CameraCharacteristics.Key<>(
                    "org.codeaurora.qcamera3.dualcam_calib_meta_data.dualcam_calib_meta_data_blob",
                    byte[].class);

    private NamedImages mNamedImages;
    private ImageReader[] mImageReader = new ImageReader[NUM_CAM];
    private ImageReader[] mEncodeImageReader = new ImageReader[NUM_CAM];
    private ImageWriter[] mImageWriter = new ImageWriter[NUM_CAM];

    private ImageProcessHandler mImageProcessHandler;
    private ClearsightRegisterHandler mClearsightRegisterHandler;
    private ClearsightProcessHandler mClearsightProcessHandler;
    private ImageEncodeHandler mImageEncodeHandler;
    private HandlerThread mImageProcessThread;
    private HandlerThread mClearsightRegisterThread;
    private HandlerThread mClearsightProcessThread;
    private HandlerThread mImageEncodeThread;
    private Callback mCallback;

    private CameraCaptureSession[] mCaptureSessions = new CameraCaptureSession[NUM_CAM];
    private MediaSaveService mMediaSaveService;
    private OnMediaSavedListener mMediaSavedListener;

    private long mTimestampThresholdNs;
    private int mNumBurstCount;
    private int mNumFrameCount;
    private boolean mDumpImages;
    private boolean mDumpYUV;

    private static ClearSightImageProcessor mInstance;

    private ClearSightImageProcessor() {
        mNamedImages = new NamedImages();
        long threshMs = SystemProperties.getLong(PERSIST_TIMESTAMP_LIMIT_KEY, DEFAULT_TIMESTAMP_THRESHOLD_MS);
        mTimestampThresholdNs = threshMs * 1000000;
        Log.d(TAG, "mTimestampThresholdNs: " + mTimestampThresholdNs);

        mNumBurstCount = SystemProperties.getInt(PERSIST_BURST_COUNT_KEY, DEFAULT_IMAGES_TO_BURST);
        Log.d(TAG, "mNumBurstCount: " + mNumBurstCount);

        mNumFrameCount = mNumBurstCount - 1;
        Log.d(TAG, "mNumFrameCount: " + mNumFrameCount);

        mDumpImages = SystemProperties.getBoolean(PERSIST_DUMP_FRAMES_KEY, false);
        Log.d(TAG, "mDumpImages: " + mDumpImages);

        mDumpYUV = SystemProperties.getBoolean(PERSIST_DUMP_YUV_KEY, false);
        Log.d(TAG, "mDumpYUV: " + mDumpYUV);
    }

    public static void createInstance() {
        if(mInstance == null) {
            mInstance = new ClearSightImageProcessor();
            ClearSightNativeEngine.createInstance();
        }
    }

    public static ClearSightImageProcessor getInstance() {
        if(mInstance == null) {
            createInstance();
        }
        return mInstance;
    }

    public void init(int width, int height, Context context, OnMediaSavedListener mediaListener) {
        mImageProcessThread = new HandlerThread("CameraImageProcess");
        mImageProcessThread.start();
        mClearsightRegisterThread = new HandlerThread("ClearsightRegister");
        mClearsightRegisterThread.start();
        mClearsightProcessThread = new HandlerThread("ClearsightProcess");
        mClearsightProcessThread.start();
        mImageEncodeThread = new HandlerThread("CameraImageEncode");
        mImageEncodeThread.start();

        mImageProcessHandler = new ImageProcessHandler(mImageProcessThread.getLooper());
        mClearsightRegisterHandler = new ClearsightRegisterHandler(mClearsightRegisterThread.getLooper());
        mClearsightProcessHandler = new ClearsightProcessHandler(mClearsightProcessThread.getLooper());
        mImageEncodeHandler = new ImageEncodeHandler(mImageEncodeThread.getLooper());

        mImageReader[CAM_TYPE_BAYER] = createImageReader(CAM_TYPE_BAYER, width, height);
        mImageReader[CAM_TYPE_MONO] = createImageReader(CAM_TYPE_MONO, width, height);
        mEncodeImageReader[CAM_TYPE_BAYER] = createEncodeImageReader(CAM_TYPE_BAYER, width, height);
        mEncodeImageReader[CAM_TYPE_MONO] = createEncodeImageReader(CAM_TYPE_MONO, width, height);

        mMediaSavedListener = mediaListener;
        CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            CameraCharacteristics cc = cm.getCameraCharacteristics("0");
            byte[] blob = cc.get(OTP_CALIB_BLOB);
            ClearSightNativeEngine.setOtpCalibData(CamSystemCalibrationData.createFromBytes(blob));
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    public void close() {
        for(int i=0; i<mImageReader.length; i++) {
            if (null != mImageReader[i]) {
                mImageReader[i].close();
                mImageReader[i] = null;
            }
            if (null != mEncodeImageReader[i]) {
                mEncodeImageReader[i].close();
                mEncodeImageReader[i] = null;
            }
            if (null != mImageWriter[i]) {
                mImageWriter[i].close();
                mImageWriter[i] = null;
            }
        }

        if(mImageProcessThread != null) {
            mImageProcessThread.quitSafely();

            try {
                mImageProcessThread.join();
                mImageProcessThread = null;
                mImageProcessHandler = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        if(mClearsightRegisterThread != null) {
            mClearsightRegisterThread.quitSafely();

            try {
                mClearsightRegisterThread.join();
                mClearsightRegisterThread = null;
                mClearsightRegisterHandler = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        if(mClearsightProcessThread != null) {
            mClearsightProcessThread.quitSafely();

            try {
                mClearsightProcessThread.join();
                mClearsightProcessThread = null;
                mClearsightProcessHandler = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        if(mImageEncodeThread != null) {
            mImageEncodeThread.quitSafely();

            try {
                mImageEncodeThread.join();
                mImageEncodeThread = null;
                mImageEncodeHandler = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        mCaptureSessions[CAM_TYPE_MONO] = null;
        mCaptureSessions[CAM_TYPE_BAYER] = null;
        mMediaSaveService = null;
        mMediaSavedListener = null;
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    public void setMediaSaveService(MediaSaveService service) {
        mMediaSaveService = service;
    }

    public void createCaptureSession(boolean bayer, CameraDevice device, List<Surface> surfaces,
            CameraCaptureSession.StateCallback captureSessionCallback) throws CameraAccessException {

        Log.d(TAG, "createCaptureSession: " + bayer);

        int cam = bayer?CAM_TYPE_BAYER:CAM_TYPE_MONO;
        surfaces.add(mImageReader[cam].getSurface());
        surfaces.add(mEncodeImageReader[cam].getSurface());
        // Here, we create a CameraCaptureSession for camera preview.
        device.createReprocessableCaptureSession(
                new InputConfiguration(mImageReader[cam].getWidth(),
                        mImageReader[cam].getHeight(), mImageReader[cam].getImageFormat()),
                        surfaces, captureSessionCallback, null);
    }

    public void onCaptureSessionConfigured(boolean bayer, CameraCaptureSession session) {
        Log.d(TAG, "onCaptureSessionConfigured: " + bayer);

        mCaptureSessions[bayer?CAM_TYPE_BAYER:CAM_TYPE_MONO] = session;
        mImageWriter[bayer?CAM_TYPE_BAYER:CAM_TYPE_MONO] =
                ImageWriter.newInstance(session.getInputSurface(), mNumBurstCount);
    }

    public CaptureRequest.Builder createCaptureRequest(CameraDevice device) throws CameraAccessException {
        Log.d(TAG, "createCaptureRequest");

        CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG);
        return builder;
    }

    public void capture(boolean bayer, CameraCaptureSession session, CaptureRequest.Builder requestBuilder,
            Handler captureCallbackHandler) throws CameraAccessException {
        Log.d(TAG, "capture: " + bayer);

        final int cam = bayer?CAM_TYPE_BAYER:CAM_TYPE_MONO;

        CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(CameraCaptureSession session,
                    CaptureRequest request,
                    TotalCaptureResult result) {
                Log.d(TAG, "captureStillPicture onCaptureCompleted: " + cam);
                mImageProcessHandler.obtainMessage(MSG_NEW_CAPTURE_RESULT,
                        cam, 0, result).sendToTarget();
            }

            @Override
            public void onCaptureFailed(CameraCaptureSession session,
                    CaptureRequest request,
                    CaptureFailure result) {
                Log.d(TAG, "captureStillPicture onCaptureFailed: " + cam);
                mImageProcessHandler.obtainMessage(MSG_NEW_CAPTURE_FAIL,
                        cam, 0, result).sendToTarget();
            }

            @Override
            public void onCaptureSequenceCompleted(CameraCaptureSession session, int
                    sequenceId, long frameNumber) {
                Log.d(TAG, "captureStillPicture onCaptureSequenceCompleted: " + cam);
            }
        };

        List<CaptureRequest> burstList = new ArrayList<CaptureRequest>();
        requestBuilder.addTarget(mImageReader[cam].getSurface());
        for (int i = 0; i < mNumBurstCount; i++) {
            requestBuilder.setTag(new Object());
            CaptureRequest request = requestBuilder.build();
            burstList.add(request);
        }

        mImageProcessHandler.obtainMessage(MSG_START_CAPTURE, cam, burstList.size(), 0).sendToTarget();
        session.captureBurst(burstList, captureCallback, captureCallbackHandler);
    }

    private ImageReader createImageReader(final int cam, int width, int height) {
        ImageReader reader = ImageReader.newInstance(width, height,
                ImageFormat.YUV_420_888, mNumBurstCount + mNumFrameCount);
        reader.setOnImageAvailableListener(new OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                Log.d(TAG, "image available for cam: " + cam);
                mImageProcessHandler.obtainMessage(
                        MSG_NEW_IMG, cam, 0, reader.acquireNextImage()).sendToTarget();
            }
        }, null);

        return reader;
    }

    private ImageReader createEncodeImageReader(final int cam, int width, int height) {
        ImageReader reader = ImageReader.newInstance(width, height,
                ImageFormat.JPEG, mNumFrameCount);
        reader.setOnImageAvailableListener(new OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                Log.d(TAG, "jpeg image available for cam: " + cam);
                mImageEncodeHandler.obtainMessage(
                        MSG_NEW_IMG, cam, 0, reader.acquireNextImage()).sendToTarget();
            }
        }, null);

        return reader;
    }

    public interface Callback {
        public void onClearSightSuccess();
        public void onClearSightFailure();
    }

    private static class ReprocessableImage {
        final Image mImage;
        final TotalCaptureResult mCaptureResult;

        ReprocessableImage(Image image, TotalCaptureResult result) {
            mImage = image;
            mCaptureResult = result;
        }
    }

    private class ImageProcessHandler extends Handler {
        private ArrayDeque<ReprocessableImage> mBayerFrames = new ArrayDeque<ReprocessableImage>(
                mNumBurstCount);
        private ArrayDeque<ReprocessableImage> mMonoFrames = new ArrayDeque<ReprocessableImage>(
                mNumBurstCount);
        private ArrayDeque<TotalCaptureResult> mBayerCaptureResults = new ArrayDeque<TotalCaptureResult>(
                mNumBurstCount);
        private ArrayDeque<TotalCaptureResult> mMonoCaptureResults = new ArrayDeque<TotalCaptureResult>(
                mNumBurstCount);
        private ArrayDeque<Image> mBayerImages = new ArrayDeque<Image>(
                mNumBurstCount);
        private ArrayDeque<Image> mMonoImages = new ArrayDeque<Image>(
                mNumBurstCount);

        private SparseLongArray mReprocessingFrames = new SparseLongArray();
        private SparseLongArray mReprocessedFrames = new SparseLongArray();
        private NamedEntity mNamedEntity;
        private int[] mNumImagesToProcess = new int[NUM_CAM];
        private boolean mCaptureDone;

        ImageProcessHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MSG_START_CAPTURE:
                mCaptureDone = false;
                mNumImagesToProcess[msg.arg1] = msg.arg2;
                mNamedImages.nameNewImage(System.currentTimeMillis());
                mNamedEntity = mNamedImages.getNextNameEntity();
                mClearsightRegisterHandler.obtainMessage(MSG_START_CAPTURE,
                        0, 0, mNamedEntity).sendToTarget();
                break;
            case MSG_END_CAPTURE:
                break;
            case MSG_NEW_IMG:
                processImg(msg);
                break;
            case MSG_NEW_CAPTURE_RESULT:
                processNewCaptureEvent(msg);
                break;
            case MSG_NEW_REPROC_RESULT:
                processNewReprocessResult(msg);
                break;
            case MSG_NEW_CAPTURE_FAIL:
                processNewCaptureEvent(msg);
                break;
            case MSG_NEW_REPROC_FAIL:
                processNewReprocessFailure(msg);
                break;
            }
        }

        private void processImg(Message msg) {
            Log.d(TAG, "processImg: " + msg.arg1);
            Image image = (Image) msg.obj;
            if(mReprocessingFrames.size() > 0
                    && mReprocessingFrames.indexOfValue(image.getTimestamp()) >= 0) {
                // reproc frame
                processNewReprocessImage(msg);
            } else {
                // new capture frame
                processNewCaptureEvent(msg);
            }
        }

        private void processNewCaptureEvent(Message msg) {
            ArrayDeque<Image> imageQueue;
            ArrayDeque<TotalCaptureResult> resultQueue;
            ArrayDeque<ReprocessableImage> frameQueue;
            // push image onto queue
            if (msg.arg1 == CAM_TYPE_BAYER) {
                imageQueue = mBayerImages;
                resultQueue = mBayerCaptureResults;
                frameQueue = mBayerFrames;
            } else {
                imageQueue = mMonoImages;
                resultQueue = mMonoCaptureResults;
                frameQueue = mMonoFrames;
            }

            if(msg.what == MSG_NEW_IMG) {
                Log.d(TAG, "processNewCaptureEvent - newImg: " + msg.arg1);
                Image image = (Image) msg.obj;
                imageQueue.add(image);
            } else if(msg.arg2 == 1) {
                Log.d(TAG, "processNewCaptureEvent - new failed result: " + msg.arg1);
                mNumImagesToProcess[msg.arg1]--;
            } else {
                Log.d(TAG, "processNewCaptureEvent - newResult: " + msg.arg1);
                TotalCaptureResult result = (TotalCaptureResult) msg.obj;
                resultQueue.add(result);
            }

            Log.d(TAG, "processNewCaptureEvent - cam: " + msg.arg1 + " num imgs: "
                    + imageQueue.size() + " num results: " + resultQueue.size());

            if (!imageQueue.isEmpty() && !resultQueue.isEmpty()) {
                Image headImage = imageQueue.poll();
                TotalCaptureResult headResult = resultQueue.poll();
                frameQueue.add(new ReprocessableImage(headImage, headResult));
                mNumImagesToProcess[msg.arg1]--;
                checkForValidFramePairAndReprocess();
            }

            Log.d(TAG, "processNewCaptureEvent - imagestoprocess[bayer] " + mNumImagesToProcess[CAM_TYPE_BAYER] +
                    " imagestoprocess[mono]: " + mNumImagesToProcess[CAM_TYPE_MONO]);

            if (mNumImagesToProcess[CAM_TYPE_BAYER] == 0
                    && mNumImagesToProcess[CAM_TYPE_MONO] == 0) {
                processFinalPair();
            }
        }

        private void checkForValidFramePairAndReprocess() {
            // if we have images from both
            // as we just added an image onto one of the queues
            // this condition is only true when both are not empty
            Log.d(TAG,
                    "checkForValidFramePair - num bayer frames: "
                            + mBayerFrames.size() + " num mono frames: "
                            + mMonoFrames.size());

            if (!mBayerFrames.isEmpty() && !mMonoFrames.isEmpty()) {
                // peek oldest pair of images
                ReprocessableImage bayer = mBayerFrames.peek();
                ReprocessableImage mono = mMonoFrames.peek();

                Log.d(TAG,
                        "checkForValidFramePair - bayer ts: "
                                + bayer.mImage.getTimestamp() + " mono ts: "
                                + mono.mImage.getTimestamp());
                Log.d(TAG,
                        "checkForValidFramePair - difference: "
                                + Math.abs(bayer.mImage.getTimestamp()
                                        - mono.mImage.getTimestamp()));
                // if timestamps are within threshold, keep frames
                if (Math.abs(bayer.mImage.getTimestamp()
                        - mono.mImage.getTimestamp()) > mTimestampThresholdNs) {
                    if(bayer.mImage.getTimestamp() > mono.mImage.getTimestamp()) {
                        Log.d(TAG, "checkForValidFramePair - toss mono");
                        // no match, toss
                        mono = mMonoFrames.poll();
                        mono.mImage.close();
                    } else {
                        Log.d(TAG, "checkForValidFramePair - toss bayer");
                        // no match, toss
                        bayer = mBayerFrames.poll();
                        bayer.mImage.close();
                    }
                } else {
                    // send for reproc
                    sendReprocessRequest(CAM_TYPE_BAYER, mBayerFrames.poll());
                    sendReprocessRequest(CAM_TYPE_MONO, mMonoFrames.poll());
                }
            }
        }

        private void sendReprocessRequest(final int camId, ReprocessableImage reprocImg) {
            CameraCaptureSession session = mCaptureSessions[camId];
            CameraDevice device = session.getDevice();

            try {
                Log.d(TAG, "sendReprocessRequest - cam: " + camId);
                CaptureRequest.Builder reprocRequest = device
                        .createReprocessCaptureRequest(reprocImg.mCaptureResult);
                reprocRequest.addTarget(mImageReader[camId]
                        .getSurface());
                reprocRequest.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                        CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY);
                reprocRequest.set(CaptureRequest.EDGE_MODE,
                        CaptureRequest.EDGE_MODE_HIGH_QUALITY);
                reprocRequest.set(CaptureRequest.NOISE_REDUCTION_MODE,
                        CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);

                Long ts = Long.valueOf(reprocImg.mImage.getTimestamp());
                Integer hash = ts.hashCode();
                reprocRequest.setTag(hash);
                mReprocessingFrames.put(hash, ts);

                mImageWriter[camId].queueInputImage(reprocImg.mImage);

                session.capture(reprocRequest.build(),
                        new CaptureCallback() {
                    @Override
                    public void onCaptureCompleted(
                            CameraCaptureSession session,
                            CaptureRequest request,
                            TotalCaptureResult result) {
                        super.onCaptureCompleted(session, request, result);
                        Log.d(TAG, "reprocess - onCaptureCompleted: "
                                + camId);
                        Integer ts = (Integer)request.getTag();
                        obtainMessage(
                                MSG_NEW_REPROC_RESULT, camId, ts.intValue(), result)
                                .sendToTarget();
                    }

                    @Override
                    public void onCaptureFailed(
                            CameraCaptureSession session,
                            CaptureRequest request,
                            CaptureFailure failure) {
                        super.onCaptureFailed(session, request, failure);
                        Log.d(TAG, "reprocess - onCaptureFailed: "
                                + camId);
                        Integer ts = (Integer)request.getTag();
                        obtainMessage(
                                MSG_NEW_REPROC_FAIL, camId, ts.intValue(), failure)
                                .sendToTarget();
                    }
                }, null);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        private void releaseBayerFrames() {
            for (ReprocessableImage reprocImg : mBayerFrames) {
                reprocImg.mImage.close();
            }

            mBayerFrames.clear();
        }

        private void releaseMonoFrames() {
            for (ReprocessableImage reprocImg : mMonoFrames) {
                reprocImg.mImage.close();
            }

            mMonoFrames.clear();
        }

        private void processFinalPair() {
            Log.d(TAG, "processFinalPair");
            releaseBayerFrames();
            releaseMonoFrames();

            mCaptureDone = true;
        }

        private void processNewReprocessImage(Message msg) {
            boolean isBayer = (msg.arg1 == CAM_TYPE_BAYER);

            Image image = (Image) msg.obj;
            long ts = image.getTimestamp();
            Log.d(TAG, "processNewReprocessImage - ts: " + ts);

            if(mDumpImages) {
                saveDebugImageAsJpeg(mMediaSaveService, image, isBayer, mNamedEntity,
                        ClearSightNativeEngine.getInstance().getImageCount(isBayer));
            }
            if(mDumpYUV) {
                saveDebugImageAsNV21(image, isBayer, mNamedEntity,
                        ClearSightNativeEngine.getInstance().getImageCount(isBayer));
            }

            if (!ClearSightNativeEngine.getInstance()
                    .hasReferenceImage(isBayer)) {
                // reference not yet set
                ClearSightNativeEngine.getInstance().setReferenceImage(isBayer,
                        image);
            } else {
                mClearsightRegisterHandler.obtainMessage(MSG_NEW_IMG,
                        msg.arg1, 0, msg.obj).sendToTarget();
            }

            mReprocessingFrames.removeAt(mReprocessingFrames.indexOfValue(ts));
            checkReprocessDone();
        }

        private void processNewReprocessResult(Message msg) {
            Log.d(TAG, "processNewReprocessResult: " + msg.arg1);
            boolean isBayer = (msg.arg1 == CAM_TYPE_BAYER);

            if (ClearSightNativeEngine.getInstance()
                    .getReferenceResult(isBayer) == null) {
                // reference not yet set
                Log.d(TAG, "reprocess - setReferenceResult: " + msg.obj);
                ClearSightNativeEngine.getInstance().setReferenceResult(isBayer,
                        (TotalCaptureResult)msg.obj);
            }

            checkReprocessDone();
        }

        private void processNewReprocessFailure(Message msg) {
            Log.d(TAG, "processNewReprocessFailure: " + msg.arg1);
            boolean isBayer = (msg.arg1 == CAM_TYPE_BAYER);
            mReprocessingFrames.delete(msg.arg2);
            checkReprocessDone();
        }

        private void checkReprocessDone() {
            Log.d(TAG, "checkReprocessDone capture done: " + mCaptureDone
                    + ", reproc frames: " + mReprocessingFrames.size());
            if(mCaptureDone && mReprocessingFrames.size() == 0
                    && ClearSightNativeEngine.getInstance().getReferenceResult(true) != null
                    && ClearSightNativeEngine.getInstance().getReferenceResult(false) != null) {
                mClearsightRegisterHandler.obtainMessage(MSG_END_CAPTURE).sendToTarget();
                mImageProcessHandler.removeMessages(MSG_NEW_REPROC_RESULT);
                mImageProcessHandler.removeMessages(MSG_NEW_REPROC_FAIL);
                mCaptureDone = false;
            }
        }
    };

    private class ClearsightRegisterHandler extends Handler {
        private NamedEntity mNamedEntity;

        ClearsightRegisterHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MSG_START_CAPTURE:
                mNamedEntity = (NamedEntity) msg.obj;
                break;
            case MSG_NEW_IMG:
                registerImage(msg);
                break;
            case MSG_END_CAPTURE:
                mClearsightProcessHandler.obtainMessage(MSG_START_CAPTURE,
                        0, 0, mNamedEntity).sendToTarget();
                break;
            }
        }

        private void registerImage(Message msg) {
            boolean isBayer = (msg.arg1 == CAM_TYPE_BAYER);
            Image image = (Image)msg.obj;

            // if ref images set, register this image
            if(ClearSightNativeEngine.getInstance().registerImage(
                    isBayer, image) == false) {
                Log.w(TAG, "registerImage : terminal error with input image");
            }
        }
    }

    private class ClearsightProcessHandler extends Handler {
        ClearsightProcessHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MSG_START_CAPTURE:
                processClearSight((NamedEntity) msg.obj);
                break;
            }
        }

        private void processClearSight(NamedEntity namedEntity) {
            boolean clearSightEncode = false;
            long csTs = ClearSightNativeEngine.getInstance().getReferenceImage(true).getTimestamp();
            CaptureRequest.Builder csRequest = createEncodeReprocRequest(
                    ClearSightNativeEngine.getInstance().getReferenceResult(true), CAM_TYPE_BAYER);

            boolean processInit = ClearSightNativeEngine.getInstance().initProcessImage();
            sendReferenceEncodeRequests();
            ClearSightNativeEngine.getInstance().reset();

            if(processInit) {
                if(mCallback != null)
                    mCallback.onClearSightSuccess();

                ClearSightNativeEngine.ClearsightImage csImage = ClearSightNativeEngine.getInstance().processImage();
                if(csImage != null) {
                    Log.d(TAG, "reprocess - processClearSight, roiRect: "
                            + csImage.getRoiRect().toString());

                    clearSightEncode = true;
                    Image encodeImage = mImageWriter[CAM_TYPE_BAYER].dequeueInputImage();
                    encodeImage.setCropRect(csImage.getRoiRect());
                    encodeImage.setTimestamp(csTs);
                    Plane[] planes = encodeImage.getPlanes();
                    planes[0].getBuffer().put(csImage.mYplane);
                    planes[2].getBuffer().put(csImage.mVUplane);

                    sendReprocessRequest(csRequest, encodeImage, CAM_TYPE_BAYER);
                }
            } else {
                if(mCallback != null)
                    mCallback.onClearSightFailure();
            }

            mImageEncodeHandler.obtainMessage(MSG_END_CAPTURE,
                    clearSightEncode?1:0, 0, namedEntity).sendToTarget();
        }

        private void sendReferenceEncodeRequests() {
            // First Mono
            CaptureRequest.Builder monoRequest = createEncodeReprocRequest(
                    ClearSightNativeEngine.getInstance().getReferenceResult(false), CAM_TYPE_MONO);
            sendReprocessRequest(monoRequest,
                    ClearSightNativeEngine.getInstance().getReferenceImage(false),
                    CAM_TYPE_MONO);

            // bayer
            CaptureRequest.Builder bayerRequest = createEncodeReprocRequest(
                    ClearSightNativeEngine.getInstance().getReferenceResult(true), CAM_TYPE_BAYER);
            sendReprocessRequest(bayerRequest,
                    ClearSightNativeEngine.getInstance().getReferenceImage(true),
                    CAM_TYPE_BAYER);
        }

        private CaptureRequest.Builder createEncodeReprocRequest(TotalCaptureResult captureResult, int camType) {
            CaptureRequest.Builder reprocRequest = null;
            try {
                reprocRequest = mCaptureSessions[camType].getDevice()
                        .createReprocessCaptureRequest(captureResult);
                reprocRequest.addTarget(mEncodeImageReader[camType]
                        .getSurface());
                reprocRequest.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                        CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF);
                reprocRequest.set(CaptureRequest.EDGE_MODE,
                        CaptureRequest.EDGE_MODE_OFF);
                reprocRequest.set(CaptureRequest.NOISE_REDUCTION_MODE,
                        CaptureRequest.NOISE_REDUCTION_MODE_OFF);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }

            return reprocRequest;
        }

        private void sendReprocessRequest(CaptureRequest.Builder reprocRequest, Image image, final int camType) {

            try {
                Rect cropRect = image.getCropRect();
                if(cropRect != null &&
                        !cropRect.isEmpty()) {
                    // has crop rect. apply to jpeg request
                    reprocRequest.set(CaptureModule.JpegCropEnableKey, (byte)1);
                    reprocRequest.set(CaptureModule.JpegCropRectKey,
                           new int[] {cropRect.left, cropRect.top, cropRect.width(), cropRect.height()});
                }

                mImageWriter[camType].queueInputImage(image);

                mCaptureSessions[camType].capture(reprocRequest.build(),
                        new CaptureCallback() {
                    @Override
                    public void onCaptureCompleted(
                            CameraCaptureSession session,
                            CaptureRequest request,
                            TotalCaptureResult result) {
                        super.onCaptureCompleted(session, request, result);
                        Log.d(TAG, "encode - onCaptureCompleted: " + camType);
                        mImageEncodeHandler.obtainMessage(
                                MSG_NEW_CAPTURE_RESULT, camType, 0, result)
                                .sendToTarget();
                    }

                    @Override
                    public void onCaptureFailed(
                            CameraCaptureSession session,
                            CaptureRequest request,
                            CaptureFailure failure) {
                        super.onCaptureFailed(session, request, failure);
                        Log.d(TAG, "encode - onCaptureFailed: " + camType);
                        mImageEncodeHandler.obtainMessage(
                                MSG_NEW_CAPTURE_RESULT, camType, 1)
                                .sendToTarget();
                    }
                }, null);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
    }

    private class ImageEncodeHandler extends Handler {
        private boolean mClearsightEncode;
        private boolean mReadyToSave;
        private Image mMonoImage;
        private Image mBayerImage;
        private Image mClearSightImage;
        private NamedEntity mNamedEntity;

        public ImageEncodeHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MSG_END_CAPTURE:
                Log.d(TAG, "ImageEncodeEvent - END_CAPTURE");
                mNamedEntity = (NamedEntity) msg.obj;
                mClearsightEncode = (msg.arg1 == 1);
                mReadyToSave = true;
                saveMpoImage();
                break;
            case MSG_NEW_IMG:
            case MSG_NEW_CAPTURE_RESULT:
                processNewEvent(msg);
                saveMpoImage();
                break;
            }
        }

        private void processNewEvent(Message msg) {
            if(msg.what == MSG_NEW_IMG) {
                Log.d(TAG, "processNewEncodeEvent - newImg: " + msg.arg1);
                if(msg.arg1 == CAM_TYPE_MONO) {
                    mMonoImage = (Image)msg.obj;
                } else if(mBayerImage == null){
                    mBayerImage = (Image)msg.obj;
                } else {
                    mClearSightImage = (Image)msg.obj;
                }
            } else if (msg.arg2 == 0) {
                Log.d(TAG, "processNewEncodeEvent - newResult: " + msg.arg1);
            } else {
                Log.d(TAG, "processNewEncodeEvent - newFailure: " + msg.arg1);
            }
        }

        private void saveMpoImage() {
            if(!mReadyToSave || mMonoImage == null || mBayerImage == null
                    || (mClearsightEncode && mClearSightImage == null)) {
                Log.d(TAG, "saveMpoImage - not yet ready to save");
                return;
            }

            Log.d(TAG, "saveMpoImage");
            String title = (mNamedEntity == null) ? null : mNamedEntity.title;
            long date = (mNamedEntity == null) ? -1 : mNamedEntity.date;
            int width = mBayerImage.getWidth();
            int height = mBayerImage.getHeight();

            if(mClearsightEncode) {
                width = mClearSightImage.getWidth();
                height = mClearSightImage.getHeight();
            }

            byte[] bayerBytes = getJpegData(mBayerImage);
            ExifInterface exif = Exif.getExif(bayerBytes);
            int orientation = Exif.getOrientation(exif);

            mMediaSaveService.addMpoImage(
                    getJpegData(mClearSightImage),
                    bayerBytes,
                    getJpegData(mMonoImage), width, height, title,
                    date, null, orientation, mMediaSavedListener,
                    mMediaSaveService.getContentResolver(), "jpeg");

            mBayerImage.close();
            mBayerImage = null;
            mMonoImage.close();
            mMonoImage = null;
            if(mClearSightImage != null) {
                mClearSightImage.close();
                mClearSightImage = null;
            }
            mNamedEntity = null;
            mReadyToSave = false;
            mClearsightEncode = false;
        }
    }

    public void saveDebugImageAsJpeg(MediaSaveService service, byte[] data,
            int width, int height, boolean isBayer, NamedEntity namedEntity, int count) {
        String type = isBayer?"bayer":"mono";
        long date = (namedEntity == null) ? -1 : namedEntity.date;
        String title = String.format("%s_%s_%02d", namedEntity.title, type, count);

        service.addImage(data, title, date, null,
                width, height, 0, null, null,
                service.getContentResolver(), "jpeg");
    }

    public void saveDebugImageAsJpeg(MediaSaveService service, YuvImage image, boolean isBayer,
            NamedEntity namedEntity, int count) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        image.compressToJpeg(
                new Rect(0, 0, image.getWidth(), image.getHeight()), 100, baos);

        saveDebugImageAsJpeg(service, baos.toByteArray(), image.getWidth(), image.getHeight(),
                isBayer, namedEntity, count);
    }

    public void saveDebugImageAsJpeg(MediaSaveService service, Image image, boolean isBayer,
            NamedEntity namedEntity, int count) {
        if(image.getFormat() == ImageFormat.YUV_420_888)
            saveDebugImageAsJpeg(service, createYuvImage(image), isBayer, namedEntity, count);
        else if (image.getFormat() == ImageFormat.JPEG) {
            saveDebugImageAsJpeg(service, getJpegData(image), image.getWidth(), image.getHeight(),
                    isBayer, namedEntity, count);
        }
    }

    public void saveDebugImageAsNV21(Image image, boolean isBayer, NamedEntity namedEntity, int count) {
        if(image.getFormat() != ImageFormat.YUV_420_888) {
            Log.d(TAG, "saveDebugImageAsNV21 - invalid param");
        }

        String type = isBayer?"bayer":"mono";
        String title = String.format("%s_%dx%d_NV21_%s_%02d", namedEntity.title,
                image.getWidth(), image.getHeight(), type, count);

        YuvImage yuv = createYuvImage(image);
        String path = Storage.generateFilepath(title, "yuv");
        Storage.writeFile(path, yuv.getYuvData(), null, "yuv");
    }

    public YuvImage createYuvImage(Image image) {
        if (image == null) {
            Log.d(TAG, "createYuvImage - invalid param");
            return null;
        }
        Plane[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer vuBuffer = planes[2].getBuffer();
        int sizeY = yBuffer.capacity();
        int sizeVU = vuBuffer.capacity();
        byte[] data = new byte[sizeY + sizeVU];
        yBuffer.rewind();
        yBuffer.get(data, 0, sizeY);
        vuBuffer.rewind();
        vuBuffer.get(data, sizeY, sizeVU);
        int[] strides = new int[] { planes[0].getRowStride(),
                planes[2].getRowStride() };

        return new YuvImage(data, ImageFormat.NV21, image.getWidth(),
                image.getHeight(), strides);
    }

    public byte[] getJpegData(Image image) {
        if (image == null) {
            Log.d(TAG, "getJpegData - invalid param");
            return null;
        }
        Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int size = buffer.capacity();
        byte[] data = new byte[size];
        buffer.rewind();
        buffer.get(data, 0, size);

        return data;
    }
}
