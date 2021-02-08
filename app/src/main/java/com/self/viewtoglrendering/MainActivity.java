package com.self.viewtoglrendering;

import android.content.res.Configuration;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;

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
        // Create a SurfaceView and bring it to the front.
        mSurfaceView = new SurfaceView(this);
        mSurfaceView.setZOrderOnTop(true);

        // Set the SurfaceView layout parameters.
        FrameLayout.LayoutParams unityPlayerLayoutParams = new FrameLayout.LayoutParams(600, 800, Gravity.TOP);
        unityPlayerLayoutParams.setMargins(100, 100, 0, 0);
        mSurfaceView.setLayoutParams(unityPlayerLayoutParams);

        // Add callbacks for Surface events.
        mSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback(){
            public final void surfaceCreated(SurfaceHolder surfaceHolder) {
                // This is where we set the Surface to UnityPlayer to draw.
                surfaceHolder.setFixedSize(600, 800);
                mUnityPlayer.displayChanged(0, surfaceHolder.getSurface());
            }

            public final void surfaceChanged(SurfaceHolder surfaceHolder, int format, int width, int height) {
                mUnityPlayer.displayChanged(0, surfaceHolder.getSurface());
            }

            public final void surfaceDestroyed(SurfaceHolder surfaceHolder) {
                mUnityPlayer.displayChanged(0, null);
            }
        });

        // Set the content view to the created SurfaceView.
        setContentView(mSurfaceView);

        // Create UnityPlayer
        mUnityPlayer = new UnityPlayer(this);
        mUnityPlayer.requestFocus();
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

    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        this.mUnityPlayer.configurationChanged(newConfig);
    }

    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        this.mUnityPlayer.windowFocusChanged(hasFocus);
    }
}
