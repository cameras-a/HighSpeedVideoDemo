package cn.zhengshang.highspeedvideodemo;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import cn.zhengshang.highspeedvideodemo.utils.Camera2Log;
import cn.zhengshang.highspeedvideodemo.utils.TimesStatistics;

public class MyCamera2 {
    private static final String TAG = "MyCamera2";

    /**
     * A refernce to the opened {@link CameraDevice}.
     */
    private CameraDevice mCameraDevice;

    /**
     * A reference to the current {@link CameraCaptureSession} for
     * preview.
     */
    private CameraConstrainedHighSpeedCaptureSession mPreviewSessionHighSpeed;
    /**
     * The {@link Size} of camera preview.
     */
    private Size mPreviewSize;

    private Size mVideoSize;
    /**
     * Camera preview.
     */
    private CaptureRequest.Builder mPreviewBuilder;


    private List<Surface> surfaces = new ArrayList<>();

    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private HandlerThread mBackgroundThread;

    /**
     * A {@link Handler} for running tasks in the background.
     */
    private Handler mBackgroundHandler;

    /**
     * A {@link Semaphore} to prevent the app from exiting before closing the camera.
     */
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);

    private TimesStatistics mImageSecondTimesStatistics = new TimesStatistics("ImageSecond", 1000);
    private SurfaceTexture mSurfaceTexture;
    private int mViewWidth;
    private int mViewHeight;

    private OnCameraStateCallback mOnCameraStateCallback;

    /**
     * {@link CameraDevice.StateCallback} is called when {@link CameraDevice} changes its status.
     */
    private CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            Camera2Log.d("CameraDevice.StateCallback onOpened");
            mCameraDevice = cameraDevice;
            startPreview(mViewWidth, mViewHeight);
            mCameraOpenCloseLock.release();
            if (mOnCameraStateCallback != null) {
                mOnCameraStateCallback.onCameraOpened(MyCamera2.this, mPreviewSize);
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            Camera2Log.d("CameraDevice.StateCallback onDisconnected");
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            Camera2Log.d("CameraDevice.StateCallback onError");
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
            if (mOnCameraStateCallback != null) {
                mOnCameraStateCallback.onError(MyCamera2.this, OnCameraStateCallback.ERRO_CAMERA_OPEN_FAILED, error, "CameraDevice.StateCallback.error");
            }
        }

    };
    private CameraCaptureSession.StateCallback SpeedSessionCallback;

    private Context mContext;

    public MyCamera2(Context context) {
        this.mContext = context;
    }

    ImageReader imageReader;

    public void initImageReader() {
        imageReader = ImageReader.newInstance(mPreviewSize.getWidth(), mPreviewSize.getHeight(), ImageFormat.YUV_420_888/*YV12*/, 1);//预览数据流最好用非JPEG
        imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                Image image = reader.acquireLatestImage();//最后一帧
//                //do something
//                int len = image.getPlanes().length;
//                byte[][] bytes = new byte[len][];
//                int count = 0;
//                for (int i = 0; i < len; i++) {
//                    ByteBuffer buffer = image.getPlanes()[i].getBuffer();
//                    int remaining = buffer.remaining();
//                    byte[] data = new byte[remaining];
//                    byte[] _data = new byte[remaining];
//                    buffer.get(data);
//                    System.arraycopy(data, 0, _data, 0, remaining);
//                    bytes[i] = _data;
//                    count += remaining;
//                }
//                //数据流都在 bytes[][] 中，关于有几个plane，可以看查看 ImageUtils.getNumPlanesForFormat(int format);
//                // ...
                image.close();//一定要关闭
                mImageSecondTimesStatistics.once();
            }
        }, mBackgroundHandler);
    }

    public void onResume() {
        Camera2Log.d("onResume");
        surfaces.clear();
        startBackgroundThread();
        if (mSurfaceTexture != null) {
            openCamera(mSurfaceTexture, Math.min(mViewWidth, mViewHeight), Math.max(mViewWidth, mViewHeight));
        }
    }

    public void onPause() {
        Camera2Log.d("onPause");
        closeCamera();
        stopBackgroundThread();
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread() {
        Camera2Log.d("startBackgroundThread");
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopBackgroundThread() {
        Camera2Log.d("stopBackgroundThread");
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @SuppressLint("MissingPermission")
    public void openCamera(SurfaceTexture surface, int width, int height) {
        this.mSurfaceTexture = surface;
        this.mViewWidth = width;
        this.mViewHeight = height;
        Camera2Log.d("openCamera");
        CameraManager manager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
        try {
            Log.d(TAG, "tryAcquire");
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
//            Camera2Log.printInfo(manager);
            String cameraId = Objects.requireNonNull(manager).getCameraIdList()[0];

            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

            List<Size> highSpeedSizes = findHighSpeed(characteristics);

            if (highSpeedSizes == null) {
                if (mOnCameraStateCallback != null) {
                    mOnCameraStateCallback.onError(this, OnCameraStateCallback.ERRO_CONFIG_HIGH_SPEED_PARMA_EMPTY, 0, "CameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) == null");
                }
                return;
            } else if (highSpeedSizes.isEmpty()) {
                if (mOnCameraStateCallback != null) {
                    mOnCameraStateCallback.onError(this, OnCameraStateCallback.ERRO_CONFIG_HIGH_SPEED_UNSPORT, 0, "not hight speed suport");
                }
                return;
            }

            Collections.sort(highSpeedSizes);
            mVideoSize = highSpeedSizes.get(highSpeedSizes.size() - 1);
            mPreviewSize = mVideoSize;

//            initImageReader();
            if (mOnCameraStateCallback != null) {
                mOnCameraStateCallback.onCameraConfig(this, mPreviewSize);
            }
            manager.openCamera(cameraId, mStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            if (mOnCameraStateCallback != null) {
                mOnCameraStateCallback.onError(this, OnCameraStateCallback.ERRO_CONFIG_OPEN_EXCEPTION, -1, "Cannot access the camera.");
            }
        } catch (NullPointerException e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            if (mOnCameraStateCallback != null) {
                mOnCameraStateCallback.onError(this, OnCameraStateCallback.ERRO_CONFIG_OPEN_EXCEPTION, -2, "Cannot access the camera.");
            }
        } catch (InterruptedException e) {
            if (mOnCameraStateCallback != null) {
                mOnCameraStateCallback.onError(this, OnCameraStateCallback.ERRO_CONFIG_OPEN_EXCEPTION, -3, "Interrupted while trying to lock camera opening.");
            }
            throw new RuntimeException("Interrupted while trying to lock camera opening.");
        }
    }

    /**
     * 在characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)中找高帧率
     *
     * @param characteristics
     * @return
     */
    private List<Size> findHighSpeed(CameraCharacteristics characteristics) {
        Camera2Log.d("findHighSpeed");
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            return null;
        }
        List<Size> highSpeedSizes = new ArrayList<>();
        for (Range<Integer> fpsRange : map.getHighSpeedVideoFpsRanges()) {
            if (fpsRange.getLower().equals(fpsRange.getUpper())) {
                for (android.util.Size size : map.getHighSpeedVideoSizesFor(fpsRange)) {
                    Size videoSize = new Size(size.getWidth(), size.getHeight());
                    int fps = fpsRange.getUpper();
                    if (videoSize.hasHighSpeedCamcorder(CameraMetadata.LENS_FACING_FRONT)) {
                        videoSize.setFps(fps);
                        Log.d(TAG, "Support HighSpeed video recording for " + videoSize.toString());
                        highSpeedSizes.add(videoSize);
                    } else if (videoSize.hasHighSpeedCamcorder(CameraMetadata.LENS_FACING_BACK)) {
                        videoSize.setFps(fps);
                        Log.d(TAG, "back Support HighSpeed video recording for " + videoSize.toString());
                        highSpeedSizes.add(videoSize);
                    } else if (videoSize.hasHighSpeedCamcorder(CameraMetadata.LENS_FACING_EXTERNAL)) {
                        videoSize.setFps(fps);
                        Log.d(TAG, "EXTERNAL Support HighSpeed video recording for " + videoSize.toString());
                        highSpeedSizes.add(videoSize);
                    } else {
                        videoSize.setFps(fps);
                        Log.d(TAG, "other Support HighSpeed video recording for " + videoSize.toString());
                        highSpeedSizes.add(videoSize);
                    }
                }
            }
        }
        return highSpeedSizes;
    }

    private void closeCamera() {
        Camera2Log.d("closeCamera");
        try {
            mCameraOpenCloseLock.acquire();
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
        } catch (Exception ignored) {

        } finally {
            mCameraOpenCloseLock.release();
        }
        if (mOnCameraStateCallback != null) {
            mOnCameraStateCallback.onCameraClosed(this);
        }
    }

    /**
     * Start the camera preview.
     */
    public void startPreview(int width, int height) {
        this.mViewWidth = width;
        this.mViewHeight = height;
        if (null == mCameraDevice || /*!mTextureView.isAvailable()*/ mSurfaceTexture == null || null == mPreviewSize) {
            return;
        }

        Camera2Log.d("startPreview " + "mTextureView size[" + mViewWidth + ", " + mViewHeight);

        try {
            surfaces.clear();
            SurfaceTexture texture = mSurfaceTexture;
            assert texture != null;
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            Surface previewSurface = new Surface(texture);
            surfaces.add(previewSurface);
            mPreviewBuilder.addTarget(previewSurface);

            Surface recordSurface = null;
            if (mOnCameraStateCallback != null) {
                recordSurface = mOnCameraStateCallback.getRecordSurface(this, mPreviewSize);
            }
            if (recordSurface != null) {
                surfaces.add(recordSurface);
                mPreviewBuilder.addTarget(recordSurface);
            }

            Camera2Log.d("startPreview imageReader " + imageReader);
            if (imageReader != null) {
                surfaces.add(imageReader.getSurface());
                mPreviewBuilder.addTarget(imageReader.getSurface());
            }


            SpeedSessionCallback = new CameraCaptureSession.StateCallback() {

                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    Camera2Log.d("CameraCaptureSession onConfigured");
                    mPreviewSessionHighSpeed = (CameraConstrainedHighSpeedCaptureSession) cameraCaptureSession;
                    updatePreview();
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    Camera2Log.d("CameraCaptureSession onConfigureFailed");
                    if (mOnCameraStateCallback != null) {
                        mOnCameraStateCallback.onError(MyCamera2.this, OnCameraStateCallback.ERRO_SESSION_CONFIGURE_FAILED, -1, null);
                    }
                }
            };
            mCameraDevice.createConstrainedHighSpeedCaptureSession(surfaces, SpeedSessionCallback, mBackgroundHandler);
        } catch (Exception e) {
            Camera2Log.d("preview " + e.getMessage());
            e.printStackTrace();
        }
    }


    /**
     * Update the camera preview. {@link #startPreview(int, int)} needs to be called in advance.
     */
    private void updatePreview() {
        if (null == mCameraDevice) {
            return;
        }
        Camera2Log.d("updatePreview");
        try {
            setUpCaptureRequestBuilder(mPreviewBuilder);
            List<CaptureRequest> mPreviewBuilderBurst = mPreviewSessionHighSpeed.createHighSpeedRequestList(mPreviewBuilder.build());
            mPreviewSessionHighSpeed.setRepeatingBurst(mPreviewBuilderBurst, null, mBackgroundHandler);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setUpCaptureRequestBuilder(CaptureRequest.Builder builder) {
        Camera2Log.d("setUpCaptureRequestBuilder");
        int fps = mVideoSize.getFps();
        Camera2Log.d("set FPS: " + fps);
        Range<Integer> fpsRange = Range.create(fps, fps);
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
    }

    public void setOnCameraStateCallback(OnCameraStateCallback cb) {
        this.mOnCameraStateCallback = cb;
    }

    public interface OnCameraStateCallback {
        /**
         * open相机错误
         */
        public static final int ERRO_CAMERA_OPEN_FAILED = -1;
        /**
         * high speed 参数空
         */
        public static final int ERRO_CONFIG_HIGH_SPEED_PARMA_EMPTY = -2;
        /**
         * high speed unsport
         */
        public static final int ERRO_CONFIG_HIGH_SPEED_UNSPORT = -3;
        /**
         * open camera exception
         */
        public static final int ERRO_CONFIG_OPEN_EXCEPTION = -4;
        /**
         * Session 配置失败
         */
        public static final int ERRO_SESSION_CONFIGURE_FAILED = -5;

        /**
         * 相机配置完成
         *
         * @param camera
         * @param preSize
         */
        void onCameraConfig(MyCamera2 camera, Size preSize);

        /**
         * 相机开启
         *
         * @param camera
         * @param preSize
         */
        void onCameraOpened(MyCamera2 camera, Size preSize);

        /**
         * 相机错误回调
         *
         * @param myCamera  相机对象
         * @param errorType 错误类型
         * @param error     原错误码
         */
        void onError(MyCamera2 myCamera, int errorType, int error, String msg);

        /**
         * 相机关闭
         *
         * @param camera
         */
        void onCameraClosed(MyCamera2 camera);

        /**
         * 录制的surface
         *
         * @param camera
         * @param previewSize
         * @return
         */
        Surface getRecordSurface(MyCamera2 camera, Size previewSize);
    }
}
