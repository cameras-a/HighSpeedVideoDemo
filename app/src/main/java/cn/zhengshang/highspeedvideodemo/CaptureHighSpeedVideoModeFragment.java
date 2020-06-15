package cn.zhengshang.highspeedvideodemo;

/**
 * Created by troels on 2/16/16.
 */

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Chronometer;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatCheckBox;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.io.IOException;

import cn.zhengshang.highspeedvideodemo.utils.Camera2Log;
import cn.zhengshang.highspeedvideodemo.utils.TimesStatistics;
import cn.zhengshang.highspeedvideodemo.utils.Utils;
import cn.zhengshang.highspeedvideodemo.view.AutoFitGLSurfaceView;
import cn.zhengshang.highspeedvideodemo.view.AutoFitTextureView;
import cn.zhengshang.highspeedvideodemo.view.IAotoSizeView;
import wu.a.egl.core.render.L11_1_CameraRenderer;

public class CaptureHighSpeedVideoModeFragment extends Fragment
        implements View.OnClickListener {
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    private static final String TAG = "HSVFragment";
    private static final int REQUEST_VIDEO_PERMISSIONS = 1;
    private static final String FRAGMENT_DIALOG = "dialog";

    private static final String[] VIDEO_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    /**
     * MediaRecorder
     */
    private MediaRecorder mMediaRecorder;

    /**
     * Whether the app is recording video now
     */
    private boolean mIsRecordingVideo = false;
    private String mNextVideoFilePath;

    /**
     * An {@link AutoFitTextureView} for camera preview.
     */
    private AutoFitTextureView mTextureView;

    /**
     * Button to record video
     */
    private AppCompatCheckBox mRecButtonVideo;
    private Chronometer mChronometer;
    private TextView mInfo;

    private MyCamera2 mCamera;

    private TimesStatistics mSecondTimesStatistics = new TimesStatistics("Second", 1000);

    /**
     * {@link TextureView.SurfaceTextureListener} handles several lifecycle events on a
     * {@link TextureView}.
     */
    private TextureView.SurfaceTextureListener mSurfaceTextureListener
            = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
            Camera2Log.d("onSurfaceTextureAvailable size[" + width + ", " + mTextureView.getHeight());
            if (!hasPermissionsGranted(VIDEO_PERMISSIONS)) {
                requestVideoPermissions();
                return;
            }
            final Activity activity = getActivity();
            if (null == activity || activity.isFinishing()) {
                return;
            }
            mCamera.openCamera(surfaceTexture, width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
            Camera2Log.d("onSurfaceTextureSizeChanged size[" + width + ", " + mTextureView.getHeight());
            mCamera.startPreview(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
            mSecondTimesStatistics.once();
        }

    };


    public static CaptureHighSpeedVideoModeFragment newInstance() {
        return new CaptureHighSpeedVideoModeFragment();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        root = (ViewGroup) inflater.inflate(R.layout.camera, container, false);
        return root;
    }

    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        Camera2Log.d("onViewCreated");
        mChronometer = view.findViewById(R.id.chronometer);
        mInfo = view.findViewById(R.id.info);
        mRecButtonVideo = view.findViewById(R.id.video_record);
        mRecButtonVideo.setOnClickListener(this);

//        initGLSurfaceView(getContext(), view);
        initTextView(view);

        mMediaRecorder = new MediaRecorder();
        mCamera = new MyCamera2(getContext());
        mCamera.setOnCameraStateCallback(new MyCamera2.OnCameraStateCallback() {

            @Override
            public void onCameraConfig(MyCamera2 camera, Size preSize) {
                mInfo.setText(getString(R.string.video_info, preSize.getWidth(), preSize.getHeight(), preSize.getFps()));

                int orientation = getResources().getConfiguration().orientation;
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    mCameraView.setAspectRatio(preSize.getWidth(), preSize.getHeight());
                } else {
                    mCameraView.setAspectRatio(preSize.getHeight(), preSize.getWidth());
                }
                configureTransform(mCameraView.getWidth(), mCameraView.getHeight(), preSize);
            }

            @Override
            public void onCameraOpened(MyCamera2 camera, Size previewSize) {
                if (null != mTextureView) {
                    configureTransform(mTextureView.getWidth(), mTextureView.getHeight(), previewSize);
                }
            }

            @Override
            public Surface getRecordSurface(MyCamera2 camera, Size previewSize) {
                if (mMediaRecorder != null) {
                    //这个东西在上一次打开预览的时候,已经设置成了prepare,但是没有使用. 所以需要reset
                    mMediaRecorder.reset();
                }

                if (mMediaRecorder != null) {
                    try {
                        setUpMediaRecorder(previewSize);
                        return mMediaRecorder.getSurface();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                return null;
            }

            @Override
            public void onCameraClosed(MyCamera2 camera) {
                Utils.deleteEmptyFile(mNextVideoFilePath);
                if (null != mMediaRecorder) {
                    mMediaRecorder.release();
                    mMediaRecorder = null;
                }
            }

            @Override
            public void onError(MyCamera2 myCamera, int errorType, int error, String msg) {
                switch (errorType) {
                    case MyCamera2.OnCameraStateCallback.ERRO_CAMERA_OPEN_FAILED:
                        Activity activity = getActivity();
                        if (null != activity) {
                            activity.finish();
                        }
                        break;
                    case MyCamera2.OnCameraStateCallback.ERRO_CONFIG_HIGH_SPEED_PARMA_EMPTY:
                        ErrorDialog.newInstance(getString(R.string.open_failed_of_map_null)).show(getFragmentManager(), "TAG");
                        break;
                    case MyCamera2.OnCameraStateCallback.ERRO_CONFIG_HIGH_SPEED_UNSPORT:
                        ErrorDialog.newInstance(getString(R.string.open_failed_of_not_support_high_speed)).show(getFragmentManager(), "TAG");
                        break;
                    case MyCamera2.OnCameraStateCallback.ERRO_CONFIG_OPEN_EXCEPTION:
                        if (error == -1) {
                            activity = getActivity();
                            Toast.makeText(activity, "Cannot access the camera.", Toast.LENGTH_SHORT).show();
                            activity.finish();
                        } else if (error == -2) {
                            ErrorDialog.newInstance(getString(R.string.camera_error))
                                    .show(getChildFragmentManager(), FRAGMENT_DIALOG);
                        } else {
                            if (TextUtils.isEmpty(msg)) {
                                msg = "unknown error";
                            }
                            ErrorDialog.newInstance(msg).show(getChildFragmentManager(), FRAGMENT_DIALOG);
                        }
                        break;
                    case MyCamera2.OnCameraStateCallback.ERRO_SESSION_CONFIGURE_FAILED:
                        activity = getActivity();
                        if (null != activity) {
                            Toast.makeText(activity, "Failed", Toast.LENGTH_SHORT).show();
                        }
                        break;
                }
            }
        });
    }

    private void initTextView(View view) {
        mTextureView = view.findViewById(R.id.texture);
        mTextureView.setVisibility(View.VISIBLE);
        mCameraView = mTextureView;
    }

    private ViewGroup root;
    private AutoFitGLSurfaceView glSurfaceView;
    L11_1_CameraRenderer renderer;

    private IAotoSizeView mCameraView;

    private void initGLSurfaceView(Context context, View view) {
        glSurfaceView = view.findViewById(R.id.GLSurfaceView);
        mCameraView = glSurfaceView;
        glSurfaceView.setVisibility(View.VISIBLE);
        glSurfaceView.setEGLContextClientVersion(2);
        renderer = new L11_1_CameraRenderer(context);
        glSurfaceView.setRenderer(renderer);
        glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        renderer.setOnSurfaceTextureChanged(new L11_1_CameraRenderer.OnSurfaceTextureChanged() {
            @Override
            public void onSurfaceTexute(SurfaceTexture st) {
                Camera2Log.d("onSurfaceTexute");
                mCamera.openCamera(st, mCameraView.getWidth(), mCameraView.getHeight());
                mCamera.startPreview(mCameraView.getWidth(), mCameraView.getHeight());
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mTextureView != null) {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }

        if (!hasPermissionsGranted(VIDEO_PERMISSIONS)) {
            requestVideoPermissions();
            return;
        }
        final Activity activity = getActivity();
        if (null == activity || activity.isFinishing()) {
            return;
        }
        mCamera.onResume();
    }

    @Override
    public void onPause() {
        if (mIsRecordingVideo) {
            stopRecordingVideo();
        }
        mCamera.onPause();
        super.onPause();
    }

    @Override
    public void onClick(View view) {
        if (view.getId() == R.id.video_record) {
            if (mIsRecordingVideo) {
                stopRecordingVideo();
                mCamera.startPreview(mCameraView.getWidth(), mCameraView.getHeight());
            } else {
                startRecordingVideo();
            }
        }
    }

    /**
     * Gets whether you should show UI with rationale for requesting permissions.
     *
     * @param permissions The permissions your app wants to request.
     * @return Whether you can show permission rationale UI.
     */
    private boolean shouldShowRequestPermissionRationale(String[] permissions) {
        for (String permission : permissions) {
            if (shouldShowRequestPermissionRationale(permission)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Requests permissions needed for recording video.
     */
    private void requestVideoPermissions() {
        if (shouldShowRequestPermissionRationale(VIDEO_PERMISSIONS)) {
            ConfirmationDialog.newInstance(R.string.permission_request)
                    .setOkListener(new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            requestPermissions(VIDEO_PERMISSIONS, REQUEST_VIDEO_PERMISSIONS);
                        }
                    })
                    .setCancelListener(new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            getActivity().finish();
                        }
                    })
                    .show(getFragmentManager(), "TAG");
        } else {
            requestPermissions(VIDEO_PERMISSIONS, REQUEST_VIDEO_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        Log.d(TAG, "onRequestPermissionsResult");
        if (requestCode == REQUEST_VIDEO_PERMISSIONS) {
            if (grantResults.length == VIDEO_PERMISSIONS.length) {
                for (int result : grantResults) {
                    if (result != PackageManager.PERMISSION_GRANTED) {
                        ErrorDialog.newInstance(getString(R.string.permission_request))
                                .show(getChildFragmentManager(), FRAGMENT_DIALOG);
                        break;
                    }
                }
            } else {
                ErrorDialog.newInstance(getString(R.string.permission_request))
                        .show(getChildFragmentManager(), FRAGMENT_DIALOG);
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    private boolean hasPermissionsGranted(String[] permissions) {
        for (String permission : permissions) {
            if (ActivityCompat.checkSelfPermission(getActivity(), permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Configures the necessary {@link Matrix} transformation to `mTextureView`.
     * This method should not to be called until the camera preview size is determined in
     * openCamera, or until the size of `mTextureView` is fixed.
     *
     * @param viewWidth  The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private void configureTransform(int viewWidth, int viewHeight, Size mPreviewSize) {
        Camera2Log.d("configureTransform view[" + viewWidth + ", " + viewHeight + "] preSize " + mPreviewSize);
        Activity activity = getActivity();
        if (null == mTextureView || null == mPreviewSize || null == activity) {
            return;
        }
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        }
        mCameraView.setTransform(matrix);
    }

    //    private MediaFormat mMediaFormat;
    private void setUpMediaRecorder(Size previewSize) throws IOException {
        final Activity activity = getActivity();
        if (null == activity) {
            return;
        }

        Camera2Log.d("setUpMediaRecorder");
        CamcorderProfile profile = previewSize.getCamcorderProfile();
        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mMediaRecorder.setProfile(profile);
        mNextVideoFilePath = getVideoFile();
        mMediaRecorder.setOutputFile(mNextVideoFilePath);

        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        int orientation = ORIENTATIONS.get(rotation);
        mMediaRecorder.setOrientationHint(orientation);
        mMediaRecorder.prepare();
    }

    /**
     * This method chooses where to save the video and what the name of the video file is
     *
     * @return path + filename
     */
    private String getVideoFile() {

        final File dcimFile = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DCIM);
        final File camera2VideoImage = new File(dcimFile, "HighSpeedVideo");
        if (!camera2VideoImage.exists()) {
            camera2VideoImage.mkdirs();
        }
        String path = camera2VideoImage.getAbsolutePath() + "/HIGH_SPEED_VIDEO_" + System.currentTimeMillis()
                + ".mp4";
        Log.d(TAG, "videoPath: " + path);
        return path;
    }

    private void startRecordingVideo() {
        Camera2Log.d("startRecordingVideo");
        mIsRecordingVideo = true;
        mRecButtonVideo.setText(R.string.stop);
        mMediaRecorder.start();
        mChronometer.setBase(SystemClock.elapsedRealtime());
        mChronometer.start();
        mChronometer.setVisibility(View.VISIBLE);
    }

    private void stopRecordingVideo() {
        Camera2Log.d("stopRecordingVideo");
        // UI
        mIsRecordingVideo = false;
        mRecButtonVideo.setText(R.string.start);
        mChronometer.stop();
        mChronometer.setVisibility(View.GONE);
        // Stop recording
        mMediaRecorder.stop();
        mMediaRecorder.reset();
        Activity activity = getActivity();
        if (null != activity) {
            Toast.makeText(activity, "Video saved: " + mNextVideoFilePath,
                    Toast.LENGTH_SHORT).show();
        }
        Camera2Log.d("stopRecordingVideo: [saved] = " + mNextVideoFilePath);

        Utils.addToMediaStore(getContext(), mNextVideoFilePath);
    }

}
