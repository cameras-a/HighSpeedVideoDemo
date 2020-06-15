package cn.zhengshang.highspeedvideodemo.utils;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Log;
import android.util.Range;

import java.util.Arrays;
import java.util.List;

public final class Camera2Log {

    private static final String TAG = "DDDD";

    public static void d(String msg) {
        android.util.Log.d(TAG, msg);
    }

    public static void printInfo(CameraManager manager) throws CameraAccessException {

        d("cameraCount: " + manager.getCameraIdList().length);
        for (String cameraId : manager.getCameraIdList()) {
            d("cameraId: " + cameraId);
            // 特征
            CameraCharacteristics characteristics
                    = manager.getCameraCharacteristics(cameraId);

            List cs = characteristics.getAvailableCaptureRequestKeys();
            d("getAvailableCaptureRequestKeys: " + cs.size());
            for (Object item : cs) {
                d(item.toString());
            }

            // 该相机的FPS范围
            Range<Integer>[] fpsRanges =
                    characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            d("dddd-FPS SYNC_MAX_LATENCY_PER_FRAME_CONTROL: " + Arrays.toString(fpsRanges));

            StreamConfigurationMap map = characteristics
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            if (map == null) {
                d("dddd-FPS-high empty");
            }

            for (Range<Integer> fpsRange : map.getHighSpeedVideoFpsRanges()) {
                d("dddd-FPS-high openCamera: [width, height] = " + fpsRange.toString());
            }


            cs = characteristics.getAvailableCaptureResultKeys();
            d("getAvailableCaptureResultKeys: " + cs.size());
            for (Object item : cs) {
                d(item.toString());
            }


            cs = characteristics.getKeys();
            d("getKeys: " + cs.size());
            for (Object item : cs) {
                d(item.toString());
            }
        }
    }
}
