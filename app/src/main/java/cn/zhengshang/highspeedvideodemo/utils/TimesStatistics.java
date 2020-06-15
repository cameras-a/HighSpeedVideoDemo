package cn.zhengshang.highspeedvideodemo.utils;

import android.text.TextUtils;

public class TimesStatistics {

    private long mStartTime;
    private long mPeriodTime;
    private long mTimes;
    private String mTag;

    public TimesStatistics(String tag, long period) {
        this.mTag = tag;
        this.mPeriodTime = period;
        if (TextUtils.isEmpty(mTag)) {
            mTag = "FrameStatistics";
        }
    }

    public void once() {
        if (mStartTime <= 0) {
            mStartTime = System.currentTimeMillis();
        }
        ++mTimes;
        if (mPeriodTime > 0 && System.currentTimeMillis() - mStartTime > mPeriodTime) {
            long periodTime = System.currentTimeMillis() - mStartTime;
            android.util.Log.d(mTag, periodTime + " : " + mTimes);
            reset();
        }
    }

    public void reset() {
        mStartTime = 0;
        mTimes = 0;
    }
}
