package com.self.viewtoglrendering;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;

import com.unity3d.player.R;
import com.self.viewtoglrendering.cuberenerer.CubeGLRenderer;
import com.unity3d.player.UnityPlayer;

public class MainActivity extends AppCompatActivity
{
    private SurfaceView mSurfaceView;
    private UnityPlayer mUnityPlayer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initViews();
    }

    private void initViews() {
        mSurfaceView = new SurfaceView(this);
        mSurfaceView.setZOrderOnTop(true);
        mSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback(){
            public final void surfaceCreated(SurfaceHolder surfaceHolder) {
                Log.v("Unity", "Change Surface");
                surfaceHolder.setFixedSize(150, 200);
                mUnityPlayer.displayChanged(0, surfaceHolder.getSurface());
            }

            public final void surfaceChanged(SurfaceHolder surfaceHolder, int format, int width, int height) {
                mUnityPlayer.displayChanged(0, surfaceHolder.getSurface());
            }

            public final void surfaceDestroyed(SurfaceHolder surfaceHolder) {
                mUnityPlayer.displayChanged(0, null);
            }
        });
        setContentView(mSurfaceView);

        mUnityPlayer = new UnityPlayer(this);
//        addContentView(mUnityPlayer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mUnityPlayer.requestFocus();
    }

//    protected void onDestroy() {
//        this.mUnityPlayer.destroy();
//        super.onDestroy();
//    }
//
//    protected void onPause() {
//        super.onPause();
//        this.mUnityPlayer.pause();
//    }

    protected void onResume() {
        super.onResume();
        this.mUnityPlayer.resume();
    }

//    public void onConfigurationChanged(Configuration newConfig) {
//        super.onConfigurationChanged(newConfig);
//        this.mUnityPlayer.configurationChanged(newConfig);
//    }

    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        this.mUnityPlayer.windowFocusChanged(hasFocus);
    }
}
