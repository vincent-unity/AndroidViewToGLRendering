//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by Fernflower decompiler)
//

package com.self.viewtoglrendering;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;

import com.unity3d.player.R;
import com.self.viewtoglrendering.cuberenerer.CubeGLRenderer;
import com.unity3d.player.IUnityPlayerLifecycleEvents;
import com.unity3d.player.UnityPlayer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class UnityPlayerActivity extends Activity implements IUnityPlayerLifecycleEvents {
    protected UnityPlayer mUnityPlayer;

    public UnityPlayerActivity() {
    }
    Surface surface;
    SurfaceHolder unityViewHolder;
    protected String updateUnityCommandLineArguments(String cmdLine) {
        return cmdLine;
    }

    protected void onCreate(Bundle savedInstanceState) {
        this.requestWindowFeature(1);
        super.onCreate(savedInstanceState);
        String cmdLine = this.updateUnityCommandLineArguments(this.getIntent().getStringExtra("unity"));
        this.getIntent().putExtra("unity", cmdLine);
        UnityPlayer.setUnityViewHolder(new SurfaceHolder.Callback2() {
            @Override
            public void surfaceRedrawNeeded(@NonNull SurfaceHolder holder) {

            }

            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {

            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                Log.v("holder", "surfaceChanged");
                unityViewHolder = holder;
                surface = holder.getSurface();
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {

            }
        });
        this.mUnityPlayer = new UnityPlayer(this, this);
        this.initViews();
        this.mUnityPlayer.requestFocus();
    }

    private void initViews() {
        this.setContentView(R.layout.activity_main);

//        ViewToGLRenderer viewToGlRenderer = new CubeGLRenderer(this);
        GLSurfaceView mGLSurfaceView = (GLSurfaceView)this.findViewById(R.id.gl_surface_view);
        Parcel sharedSurface = Parcel.obtain();
        surface.writeToParcel(sharedSurface, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
        mGLSurfaceView.getHolder().getSurface().readFromParcel(sharedSurface);
        mGLSurfaceView.setRenderer(new UnitySurfaceViewRenderer());
        this.mUnityPlayer.requestFocus();
    }

    class UnitySurfaceViewRenderer implements GLSurfaceView.Renderer {
        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {

        }

        @Override
        public void onDrawFrame(GL10 gl) {
        }
    }

    public void onUnityPlayerUnloaded() {
        this.moveTaskToBack(true);
    }

    public void onUnityPlayerQuitted() {
        Process.killProcess(Process.myPid());
    }

    protected void onNewIntent(Intent intent) {
        this.setIntent(intent);
        this.mUnityPlayer.newIntent(intent);
    }

    protected void onDestroy() {
        this.mUnityPlayer.destroy();
        super.onDestroy();
    }

    protected void onPause() {
        super.onPause();
        this.mUnityPlayer.pause();
    }

    protected void onResume() {
        super.onResume();
        this.mUnityPlayer.resume();
    }

    public void onLowMemory() {
        super.onLowMemory();
        this.mUnityPlayer.lowMemory();
    }

    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level == 15) {
            this.mUnityPlayer.lowMemory();
        }

    }

    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        this.mUnityPlayer.configurationChanged(newConfig);
    }

    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        this.mUnityPlayer.windowFocusChanged(hasFocus);
    }

    public boolean dispatchKeyEvent(KeyEvent event) {
        return event.getAction() == 2 ? this.mUnityPlayer.injectEvent(event) : super.dispatchKeyEvent(event);
    }

    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return this.mUnityPlayer.injectEvent(event);
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return this.mUnityPlayer.injectEvent(event);
    }

    public boolean onTouchEvent(MotionEvent event) {
        return this.mUnityPlayer.injectEvent(event);
    }

    public boolean onGenericMotionEvent(MotionEvent event) {
        return this.mUnityPlayer.injectEvent(event);
    }
}
