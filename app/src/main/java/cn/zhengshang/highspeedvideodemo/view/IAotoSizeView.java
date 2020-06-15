package cn.zhengshang.highspeedvideodemo.view;

import android.graphics.Matrix;

public interface IAotoSizeView {
    void setAspectRatio(int width, int height);

    void setTransform(Matrix matrix);

    int getWidth();

    int getHeight();
}
