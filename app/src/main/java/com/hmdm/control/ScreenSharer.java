package com.hmdm.control;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.AsyncTask;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.Surface;

import net.majorkernelpanic.streaming.rtp.AbstractPacketizer;
import net.majorkernelpanic.streaming.rtp.H264Packetizer;
import net.majorkernelpanic.streaming.rtp.MediaCodecInputStream;

import java.io.IOException;
import java.net.InetAddress;

public class ScreenSharer {
    private int mScreenDensity;
    private int mScreenWidth;
    private int mScreenHeight;

    private MediaProjectionManager mProjectionManager;
    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;
    private MediaProjection.Callback mMediaProjectionCallback;

    private MediaCodec mMediaCodec;
    private Surface mInputSurface;
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    private AbstractPacketizer mPacketizer;

    private boolean mRecordAudio;
    private String mRtpHost;
    private int mRtpAudioPort;
    private int mRtpVideoPort;
    private int mVideoFrameRate;
    private int mVideoBitrate;

    private static final String MIME_TYPE_VIDEO = "video/avc";

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    public ScreenSharer(Activity activity) {
        DisplayMetrics metrics = new DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getMetrics(metrics);
        mScreenDensity = metrics.densityDpi;
        mScreenWidth = metrics.widthPixels;
        mScreenHeight = metrics.heightPixels;

        // Adjust translated screencast size for phones with high screen resolutions
        if (mScreenWidth > Const.MAX_SHARED_SCREEN_WIDTH || mScreenHeight > Const.MAX_SHARED_SCREEN_HEIGHT) {
            float widthScale = (float)mScreenWidth / Const.MAX_SHARED_SCREEN_WIDTH;
            float heightScale = (float)mScreenHeight / Const.MAX_SHARED_SCREEN_HEIGHT;
            float maxScale = widthScale > heightScale ? widthScale : heightScale;
            mScreenWidth /= maxScale;
            mScreenHeight /= maxScale;
        }

        float videoScale = (float)mScreenWidth / metrics.widthPixels;
        SettingsHelper.getInstance(activity).setFloat(SettingsHelper.KEY_VIDEO_SCALE, videoScale);
        Log.i(Const.LOG_TAG, "screenWidth=" + mScreenWidth + ", screenHeight=" + mScreenHeight + ", scale=" + videoScale);
        // Workaround against the codec bug: https://stackoverflow.com/questions/36915383/what-does-error-code-1010-in-android-mediacodec-mean
        // Making height and width divisible by 2
        mScreenHeight = mScreenHeight & 0xFFFE;
        mScreenWidth = mScreenWidth & 0xFFFE;

        try {
            mMediaCodec = MediaCodec.createEncoderByType(MIME_TYPE_VIDEO);
        } catch (IOException e) {
            e.printStackTrace();
        }
        mPacketizer = new H264Packetizer();

        mProjectionManager = (MediaProjectionManager)activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
    }

    public void configure(boolean audio, int videoFrameRate, int videoBitRate, String host, int audioPort, int videoPort) {
        mRecordAudio = audio;
        mVideoFrameRate = videoFrameRate;
        mVideoBitrate = videoBitRate;
        mRtpAudioPort = audioPort;
        mRtpVideoPort = videoPort;

        new AsyncTask<Void,Void,Void>() {

            @Override
            protected Void doInBackground(Void... voids) {
                try {
                    // Here I set RTCP port to videoPort+1 (conventional), but RTCP is not used, and 0 or -1 cause errors in libstreaming
                    mPacketizer.setDestination(InetAddress.getByName(host), videoPort, videoPort + 1);
                    // TEST
                    // mPacketizer.setDestination(InetAddress.getByName("192.168.1.127"), 1234, 1235);
                    mPacketizer.setTimeToLive(64);

                } catch (Exception e) {
                    // We should not be here because configure() is called after successful connection to the host
                    e.printStackTrace();
                }
                return null;
            }

        }.execute();
    }

    public void setMediaProjectionCallback(MediaProjection.Callback callback) {
        mMediaProjectionCallback = callback;
    }

    public void onSharePermissionGranted(Activity activity, int resultCode, Intent data) {
        mMediaProjection = mProjectionManager.getMediaProjection(resultCode, data);
        mMediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                super.onStop();
                stopShare(activity);
                mMediaProjectionCallback.onStop();
            }
        }, null);
        mVirtualDisplay = createVirtualDisplay();
        mMediaCodec.start();
        startSending();
    }

    public boolean startShare(Activity activity) {
        if (!initRecorder(activity)) {
            return false;
        }
        shareScreen(activity);
        return true;
    }

    public void stopShare(Activity activity) {
        try {
            mPacketizer.stop();
            mMediaCodec.stop();
            Log.v(Const.LOG_TAG, "Stopping Recording");
            stopScreenSharing();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void destroy() {
        destroyMediaProjection();
    }

    public int getScreenWidth() {
        return mScreenWidth;
    }

    public int getScreenHeight() {
        return mScreenHeight;
    }

    private void shareScreen(Activity activity) {
        if (mMediaProjection == null) {
            activity.startActivityForResult(mProjectionManager.createScreenCaptureIntent(), Const.REQUEST_SCREEN_SHARE);
            return;
        }
        mVirtualDisplay = createVirtualDisplay();
        mMediaCodec.start();
        startSending();
    }

    private void startSending() {
        MediaCodecInputStream mcis = new MediaCodecInputStream(mMediaCodec);
        mPacketizer.setInputStream(mcis);
        mcis.setH264Packetizer((H264Packetizer) mPacketizer);
        mPacketizer.start();
    }

    private VirtualDisplay createVirtualDisplay() {
        return mMediaProjection.createVirtualDisplay("MainActivity",
                mScreenWidth, mScreenHeight, mScreenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mInputSurface, null /*Callbacks*/, null
                /*Handler*/);
    }

    private boolean initRecorder(Activity activity) {
        MediaFormat mediaFormat = MediaFormat.createVideoFormat(MIME_TYPE_VIDEO, mScreenWidth, mScreenHeight);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, mVideoBitrate);
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, mVideoFrameRate);
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        // This method call may throw CodecException!
        try {
            mMediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (Exception e) {
            Log.e(Const.LOG_TAG, "Failed to configure codec with parameters: screenWidth=" + mScreenWidth +
                    ", screenHeight=" + mScreenHeight + ", bitrate=" + mVideoBitrate + ", frameRate=" + mVideoFrameRate +
                    ", colorFormat=" + MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface + ", frameInterval=1");
            e.printStackTrace();
            return false;
        }
        mInputSurface = mMediaCodec.createInputSurface();
        return true;
    }

    private void stopScreenSharing() {
        if (mVirtualDisplay == null) {
            return;
        }
        mPacketizer.stop();
        mVirtualDisplay.release();
        //mMediaRecorder.release(); //If used: mMediaRecorder object cannot
        // be reused again
        destroyMediaProjection();
    }

    private void destroyMediaProjection() {
        if (mMediaProjection != null) {
            mMediaProjection.unregisterCallback(mMediaProjectionCallback);
            mMediaProjection.stop();
            mMediaProjection = null;
        }
        Log.i(Const.LOG_TAG, "MediaProjection Stopped");
    }
}
