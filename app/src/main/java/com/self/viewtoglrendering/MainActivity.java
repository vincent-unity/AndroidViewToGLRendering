package com.self.viewtoglrendering;

import android.content.Intent;
import android.content.res.Configuration;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;

import com.unity3d.player.R;
import com.self.viewtoglrendering.cuberenerer.CubeGLRenderer;
import com.unity3d.player.UnityPlayer;

public class MainActivity extends AppCompatActivity
{
    private GLSurfaceView mGLSurfaceView;
    private UnityPlayer mUnityPlayer;
    private WebView mWebView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initViews();
    }

    private void initViews() {
        //setContentView(R.layout.activity_main);

        //ViewToGLRenderer viewToGlRenderer = new CubeGLRenderer(this);

        //mGLSurfaceView = (GLSurfaceView) findViewById(R.id.gl_surface_view);
        mUnityPlayer = new UnityPlayer(this);

        //mGLSurfaceView.setEGLContextClientVersion(2);
        //mGLSurfaceView.setRenderer(viewToGlRenderer);

        //mUnityPlayer.setViewToGLRenderer(viewToGlRenderer);
        setContentView(mUnityPlayer);
        mUnityPlayer.requestFocus();

//        mWebView.setWebViewClient(new WebViewClient());
//        mWebView.setWebChromeClient(new WebChromeClient());
//        mWebView.loadUrl("http://stackoverflow.com/questions/12499396/is-it-possible-to-render-an-android-view-to-an-opengl-fbo-or-texture");
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
