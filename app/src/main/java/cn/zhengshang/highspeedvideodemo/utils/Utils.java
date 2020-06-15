package cn.zhengshang.highspeedvideodemo.utils;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

import java.io.File;

public final class Utils {
    /**
     * 如果传入的文件为空文件(文件存在,但大小为0kb),则删除掉
     * 这些0长度的文件, 是因为MediaRecorder调用了prepare之后没有开始录制生成的. 他们数据无效数据
     *
     * @param filePath 文件路径
     */
    public static void deleteEmptyFile(String filePath) {
        if (TextUtils.isEmpty(filePath)) {
            return;
        }
        File file = new File(filePath);
        if (file.isFile() && file.length() <= 0) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    /**
     * 添加记录到媒体库
     */
    public static void addToMediaStore(Context context, String path) {
        Intent sanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        Uri uri = Uri.fromFile(new File(path));
        sanIntent.setData(uri);
        context.sendBroadcast(sanIntent);
    }
}
