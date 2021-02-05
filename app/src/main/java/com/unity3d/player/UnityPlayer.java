package com.unity3d.player;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Configuration;
import android.content.res.Resources.Theme;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.MessageQueue;
import android.os.Looper;
import android.os.Process;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.view.KeyEvent;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.hardware.SensorManager;
import android.hardware.Sensor;
import android.hardware.SensorEventListener;
import android.hardware.SensorEvent;
import android.content.Context;
import android.net.Uri;
import android.util.TypedValue;
import android.view.OrientationEventListener;
import android.hardware.SensorManager;

import com.self.viewtoglrendering.GLRenderable;

import static com.unity3d.player.PlatformSupport.*;

public class UnityPlayer extends FrameLayout implements IUnityPlayerServices, IUnityPlayerLifecycleEvents
{
//    public GLRenderable getExternalGLRenderable() {
//        return externalGLRenderable;
//    }
//
//    public void setExternalGLRenderable(GLRenderable externalGLRenderable) {
//        this.externalGLRenderable = externalGLRenderable;
//    }
//
//    private GLRenderable externalGLRenderable;

    public static SurfaceHolder.Callback getUnityViewHolder() {
        return unityViewHolder;
    }

    public static void setUnityViewHolder(SurfaceHolder.Callback _unityViewHolder) {
        unityViewHolder = _unityViewHolder;
    }

    private static SurfaceHolder.Callback unityViewHolder;


    public static Activity currentActivity = null;      // for external refs; we DON'T use this
    public static final class kbCommand
    {
        public static final int dontHide = 0;
        public static final int hide = 1;
    }

    private int mInitialScreenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    private boolean mMainDisplayOverride = false;
    private boolean mIsFullscreen = true;
    private UnityPlayerState mState = new UnityPlayerState();

    static { new UnityExceptionHandler().install(); }

    private final ConcurrentLinkedQueue<Runnable> m_Events = new ConcurrentLinkedQueue<Runnable>();

    private BroadcastReceiver mKillingIsMyBusiness = null;
    private OrientationEventListener mOrientationListener = null;
    private int mNaturalOrientation;

    enum RunStateEvent { PAUSE, RESUME, QUIT, SURFACE_LOST, SURFACE_ACQUIRED, FOCUS_LOST, FOCUS_GAINED, NEXT_FRAME, URL_ACTIVATED, ORIENTATION_ANGLE_CHANGE };

    private static final int ANR_TIMEOUT_SECONDS = 4;
    private static final int RUN_STATE_CHANGED_MSG_CODE = 2269;

    enum Focus { GAINED, LOST, DEFERRED };

    private class UnityMainThread extends Thread
    {
        Handler m_Handler;
        boolean m_Running = false;
        boolean m_SurfaceAvailable = false;
        Focus m_Focus = Focus.LOST;
        int m_PendingNextFrameEvents = 0;
        int m_NaturalOrientation;
        int m_OrientationAngle;

        static final int FRAMES_TO_KEEP_STATIC_SPLASH_IN_BACKGROUND = 5;    // needed to avoid showing black screen
        int framesToRemoveStaticSplash = FRAMES_TO_KEEP_STATIC_SPLASH_IN_BACKGROUND;

        @Override
        public void run()
        {
            setName("UnityMain");

            Looper.prepare();
            m_Handler = new Handler(new Handler.Callback()
            {
                // Only call nativeFocusChanged(true) when we also have a surface.
                // Otherwise we might not have a valid EGL context and some operations
                // in OnApplicationFocus callback might fail (texture uploads).
                // Note: similar handling for (deferred) RESUME if implemented in native code.
                private void checkApplyFocusGained()
                {
                    if (m_Focus == Focus.DEFERRED && m_SurfaceAvailable)
                    {
                        nativeFocusChanged(true);
                        m_Focus = Focus.GAINED;
                    }
                }

                public boolean handleMessage(Message msg)
                {
                    if (msg.what != RUN_STATE_CHANGED_MSG_CODE)
                        return false;

                    final RunStateEvent runState = (RunStateEvent)msg.obj;
                    if (runState == RunStateEvent.NEXT_FRAME)
                    {
                        --m_PendingNextFrameEvents;
                        executeGLThreadJobs();

                        if (!m_Running)
                            return true;

                        if (!m_SurfaceAvailable)
                            return true;

                        if (framesToRemoveStaticSplash >= 0)
                        {
                            if (framesToRemoveStaticSplash == 0 && getSplashEnabled())
                                DisableStaticSplashScreen();

                            --framesToRemoveStaticSplash;
                        }

                        if (!isFinishing() && !nativeRender())
                            finish();
                    }
                    else if (runState == RunStateEvent.QUIT)
                    {
                        Looper.myLooper().quit();
                    }
                    else if (runState == RunStateEvent.RESUME)
                    {
                        m_Running = true;
                    }
                    else if (runState == RunStateEvent.PAUSE)
                    {
                        m_Running = false;
                    }
                    else if (runState == RunStateEvent.SURFACE_LOST)
                    {
                        m_SurfaceAvailable = false;
                    }
                    else if (runState == RunStateEvent.SURFACE_ACQUIRED)
                    {
                        m_SurfaceAvailable = true;
                        checkApplyFocusGained();
                    }
                    else if (runState == RunStateEvent.FOCUS_LOST)
                    {
                        if (m_Focus == Focus.GAINED)
                            nativeFocusChanged(false);
                        // else: user never observed the (deferred) FOCUS_GAINED (or it didn't actually change)
                        m_Focus = Focus.LOST;
                    }
                    else if (runState == RunStateEvent.FOCUS_GAINED)
                    {
                        m_Focus = Focus.DEFERRED;
                        checkApplyFocusGained();
                    }
                    else if (runState == RunStateEvent.URL_ACTIVATED)
                    {
                        nativeSetLaunchURL(getLaunchURL());
                    }
                    else if (runState == RunStateEvent.ORIENTATION_ANGLE_CHANGE)
                    {
                        nativeOrientationChanged(m_NaturalOrientation, m_OrientationAngle);
                    }

                    // trigger next frame
                    // extra events accumulate in queue delaying other events processing (case 1168456)
                    if (m_Running && m_PendingNextFrameEvents <= 0)
                    {
                        Message.obtain(m_Handler, RUN_STATE_CHANGED_MSG_CODE, RunStateEvent.NEXT_FRAME).sendToTarget();
                        ++m_PendingNextFrameEvents;
                    }

                    return true;
                }
            });

            Looper.loop();
        }

        public void quit()
        {
            dispatchRunStateEvent(RunStateEvent.QUIT);
        }

        public void resumeExecution()
        {
            dispatchRunStateEvent(RunStateEvent.RESUME);
        }

        public void pauseExecution(Runnable runnable)
        {
            if (m_Handler == null)
                return;
            dispatchRunStateEvent(RunStateEvent.PAUSE);
            Message.obtain(m_Handler, runnable).sendToTarget();
        }

        public void focusGained()
        {
            dispatchRunStateEvent(RunStateEvent.FOCUS_GAINED);
        }

        public void focusLost()
        {
            dispatchRunStateEvent(RunStateEvent.FOCUS_LOST);
        }


        public void surfaceLost(Runnable runnable)
        {
            if (m_Handler == null)
                return;
            dispatchRunStateEvent(RunStateEvent.SURFACE_LOST);
            Message.obtain(m_Handler, runnable).sendToTarget();
        }

        public void surfaceAcquired(Runnable runnable)
        {
            if (m_Handler == null)
                return;
            Message.obtain(m_Handler, runnable).sendToTarget();
            dispatchRunStateEvent(RunStateEvent.SURFACE_ACQUIRED);
        }

        public void surfaceChanged(Runnable runnable)
        {
            if (m_Handler != null)
                Message.obtain(m_Handler, runnable).sendToTarget();
        }

        public void urlActivated()
        {
            dispatchRunStateEvent(RunStateEvent.URL_ACTIVATED);
        }

        private void dispatchRunStateEvent(RunStateEvent ev)
        {
            if (m_Handler != null)
                Message.obtain(m_Handler, RUN_STATE_CHANGED_MSG_CODE, ev).sendToTarget();
        }

        public void orientationChanged(int naturalOrientation, int angle)
        {
            m_NaturalOrientation = naturalOrientation;
            m_OrientationAngle = angle;
            dispatchRunStateEvent(RunStateEvent.ORIENTATION_ANGLE_CHANGE);
        }
    }

    UnityMainThread m_MainThread = new UnityMainThread();

    private class PhoneCallListener extends PhoneStateListener
    {
        @Override
        public void onCallStateChanged(int state, String incomingNumber)
        {
            nativeMuteMasterAudio(state == TelephonyManager.CALL_STATE_RINGING);
        }
    }

    private boolean m_AddPhoneCallListener = false;
    private PhoneCallListener m_PhoneCallListener = new PhoneCallListener();
    private TelephonyManager m_TelephonyManager;

    private ClipboardManager m_ClipboardManager;

    private StaticSplashscreen m_SplashScreen;
    private GoogleARCoreApi m_ARCoreApi = null;
    private FakeSensorListener m_FakeListener = new FakeSensorListener();

    private Camera2Wrapper m_Camera2Wrapper = null;
    private HFPStatus m_HFPStatus = null;
    private AudioVolumeHandler m_AudioVolumeHandler = null;

    private Uri m_launchUri = null;
    private NetworkConnectivity m_NetworkConnectivity = null;

    private IUnityPlayerLifecycleEvents m_UnityPlayerLifecycleEvents = null;

    public static String m_AndroidFilesDir;
    private static String m_InstantGameName;

    public UnityPlayer(Context context)
    {
        this(context, null);
    }

    public UnityPlayer(Context context, IUnityPlayerLifecycleEvents lifecycleEventListener)
    {
        super(context);

        m_UnityPlayerLifecycleEvents = lifecycleEventListener;

        if (context instanceof Activity)
        {
            currentActivity = (Activity)context;
            mInitialScreenOrientation = currentActivity.getRequestedOrientation();

            m_launchUri = currentActivity.getIntent().getData();

            m_InstantGameName = currentActivity.getIntent().getStringExtra("instantGame");
            m_AndroidFilesDir = context.getFilesDir().getAbsolutePath();
            if (m_InstantGameName != null)
            {
                String cmdLine = currentActivity.getIntent().getStringExtra("unity");
                if (cmdLine == null)
                    cmdLine = "";

                cmdLine += (" -instantGame " + m_InstantGameName);

                // TODO: LZ:
                //      if the user already use overrideMonoSearchPath, we need to figure out a solution.
                String playerRuntimeDir = m_AndroidFilesDir + "/UnityPlayers/2019";
                String commonManagedDir = playerRuntimeDir + "/Managed";
                cmdLine += (" -overrideMonoSearchPath " + commonManagedDir);

                currentActivity.getIntent().putExtra("unity", cmdLine);
            }
        }

        EarlyEnableFullScreenIfVrLaunched(currentActivity);

        mContext = context;

        Configuration config = getResources().getConfiguration();
        mNaturalOrientation = getNaturalOrientation(config.orientation);
        if (currentActivity != null && getSplashEnabled())
        {
            m_SplashScreen = new StaticSplashscreen(mContext, StaticSplashscreen.ScaleMode.values()[getSplashMode()]);
            addView(m_SplashScreen);
        }

        loadNative(mContext.getApplicationInfo());

        if (!UnityPlayerState.librariesHasBeenLoaded())
        {
            AlertDialog ad = new AlertDialog.Builder (mContext)
            .setTitle ("Failure to initialize!")
            .setPositiveButton ("OK", new DialogInterface.OnClickListener()
                {
                    public void onClick(DialogInterface di, int i)
                    {
                        finish();
                    }
                })
            .setMessage ("Your hardware does not support this application, sorry!")
            .create ();
            ad.setCancelable(false);
            ad.show ();
            return;
        }

        initJni(context);
        mState.setJNIInitialized(true);

        mGlView = CreateGlView();
        mGlView.setContentDescription(GetGlViewContentDescription(context));
        addView(mGlView);
        bringChildToFront(m_SplashScreen);

        mQuitting = false; // reset 'quitting' state

//        hideStatusBar();

        m_TelephonyManager = (TelephonyManager)mContext.getSystemService(Context.TELEPHONY_SERVICE);

        m_ClipboardManager = (ClipboardManager)mContext.getSystemService(Context.CLIPBOARD_SERVICE);

        m_Camera2Wrapper = new Camera2Wrapper(mContext);

        m_HFPStatus = new HFPStatus(mContext);

        m_MainThread.start();
    }

    private int getNaturalOrientation(int orientation)
    {
        int angle = ((WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
        if (((angle == Surface.ROTATION_0 || angle == Surface.ROTATION_180) && orientation == Configuration.ORIENTATION_LANDSCAPE) ||
            ((angle == Surface.ROTATION_90 || angle == Surface.ROTATION_270) && orientation == Configuration.ORIENTATION_PORTRAIT))
        {
            return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        }
        else
        {
            return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        }
    }

    @Override
    public void onUnityPlayerUnloaded() {
        android.util.Log.i( "onUnityPlayerUnloaded"," pass IUnityPlayerLifecycleEvents to UnityPlayer constructor or override this method in child class");
    }

    @Override
    public void onUnityPlayerQuitted() {
        android.util.Log.i(  "onUnityPlayerQuitted"," pass IUnityPlayerLifecycleEvents to UnityPlayer constructor or override this method in child class");
    }

    class FakeSensorListener implements SensorEventListener
    {
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        public void onSensorChanged(SensorEvent event) {}
    }

    // The sole purpose of this method is to workaround native code gyroscope lag issues
    // This is an android/google issue, it's been reported here: https://issuetracker.google.com/u/1/issues/69360770
    // We also have a case in fogbugz: 912848
    // Once the issues have been resolved by google this method should be removed along with the FakeSensorListener
    protected void toggleGyroscopeSensor(boolean activate)
    {
        SensorManager mSensorManager = (SensorManager)mContext.getSystemService(Context.SENSOR_SERVICE);
        Sensor mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if(activate)
            mSensorManager.registerListener(m_FakeListener, mAccelerometer, SensorManager.SENSOR_DELAY_GAME);
        else
            mSensorManager.unregisterListener(m_FakeListener);
    }

    // Used as an alternative to the glView by accessibility tools
    private String GetGlViewContentDescription(Context context)
    {
        return context.getResources().getString(context.getResources().getIdentifier("game_view_content_description", "string", context.getPackageName()));
    }

    private void DisableStaticSplashScreen()
    {
        runOnUiThread (new Runnable ()
        {
            public void run()
            {
                removeView(m_SplashScreen);
                m_SplashScreen = null;
            }
        });
    }

    private void EarlyEnableFullScreenIfVrLaunched(Activity currentActivity)
    {
        if (currentActivity != null &&
            currentActivity.getIntent().getBooleanExtra("android.intent.extra.VR_LAUNCH", false) &&
            currentActivity.getWindow() != null)
        {
            View decorView = currentActivity.getWindow().getDecorView();
            if (decorView != null)
            {
                int uiOptions = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LOW_PROFILE
                    | View.SYSTEM_UI_FLAG_FULLSCREEN;
                decorView.setSystemUiVisibility(uiOptions);
            }
        }
    }

    private boolean IsWindowTranslucent()
    {
        if (currentActivity == null)
            return false;
        TypedValue windowIsTranslucent = new TypedValue();
        if (currentActivity.getTheme().resolveAttribute(android.R.attr.windowIsTranslucent, windowIsTranslucent, true))
        {
            if (windowIsTranslucent.type == TypedValue.TYPE_INT_BOOLEAN)
                return windowIsTranslucent.data != 0;
        }
        return false;
    }

    private SurfaceView CreateGlView()
    {
        SurfaceView ret = new SurfaceView(mContext);
        ret.getHolder().addCallback(unityViewHolder);
        ret.setId(mContext.getResources().getIdentifier("unitySurfaceView", "id", mContext.getPackageName()));
        if (IsWindowTranslucent())
        {
            ret.getHolder().setFormat(PixelFormat.TRANSLUCENT);
            ret.setZOrderOnTop(true);
        }
        else
            ret.getHolder().setFormat(PixelFormat.OPAQUE);

        ret.getHolder().addCallback(new SurfaceHolder.Callback() {
            public void surfaceCreated(SurfaceHolder holder)
            {
                updateGLDisplay(0, holder.getSurface());
            }

            public void surfaceChanged(SurfaceHolder holder, final int _format, final int _width, final int _height)
            {
                updateGLDisplay(0, holder.getSurface());
                sendSurfaceChangedEvent();
            }

            public void surfaceDestroyed(SurfaceHolder holder)
            {
                updateGLDisplay(0, null);
            }
        });
        ret.setFocusable(true);
        ret.setFocusableInTouchMode(true);
        return ret;
    }

    private void sendSurfaceChangedEvent()
    {
        if (!mState.librariesHasBeenLoaded() || !mState.isJNIInitialized())
            return;

        Runnable runnable = new Runnable(){
            public void run()
            {
                nativeSendSurfaceChangedEvent();
            }
        };

        m_MainThread.surfaceChanged(runnable);
    }

    private void updateGLDisplay(int index, Surface surface)
    {
        if (mMainDisplayOverride)
            return;
        updateDisplayInternal(index, surface);
    }

    private boolean updateDisplayInternal(final int index, final Surface surface)
    {
        if (!mState.librariesHasBeenLoaded() || !mState.isJNIInitialized())
            return false;

        final Semaphore synchronize = new Semaphore(0);

        Runnable runnable = new Runnable(){
            public void run()
            {
                nativeRecreateGfxState(index, surface);
                synchronize.release();
            }
        };

        if (index == 0)
        {
            // primary surface is required to setup GL context
            // so enqueue into Unity thread to avoid triggering frames without a valid surface
            if (surface == null)
                m_MainThread.surfaceLost(runnable);
            else
                m_MainThread.surfaceAcquired(runnable);
        }
        else
        {
            runnable.run();
        }

        // Wait until primary window is actually detached, so that we are sure that the current frame no longer uses it.
        if (surface == null && index == 0)
        {
            try
            {
                if (!synchronize.tryAcquire(ANR_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                {
                    android.util.Log.w("", "Timeout while trying detaching primary window.");
                }
            }
            catch (InterruptedException e)
            {
                android.util.Log.w("",  "UI thread got interrupted while trying to detach the primary window from the Unity Engine.");
            }
        }

        return true;
    }

    public boolean displayChanged(int index, Surface surface)
    {
        if (index == 0)
        {
            mMainDisplayOverride = (surface != null);
            runOnUiThread (new Runnable () { public void run()
            {
                if (mMainDisplayOverride)
                    removeView(mGlView);
                else
                    addView(mGlView);
            }});
        }
        return updateDisplayInternal(index, surface);
    }

    public static void UnitySendMessage(String objectName, String methodName, String messageStr)
    {
        if (!UnityPlayerState.librariesHasBeenLoaded())
        {
            android.util.Log.w("", "Native libraries not loaded - dropping message for " + objectName + "." + methodName);
            return;
        }
        try
        {
            // Passing a byte array instead of a string because otherwise supplementary Unicode characters don't work in native
            nativeUnitySendMessage(objectName, methodName, messageStr.getBytes("UTF-8"));
        }
        catch (UnsupportedEncodingException ex)
        {
            // Ignore
        }
    }

    private static native void nativeUnitySendMessage(String objectName, String methodName, byte[] messageStr);

    private void finish()
    {
        if (mContext instanceof Activity)
        {
            if (!((Activity) mContext).isFinishing())
            ((Activity) mContext).finish();
        }
        else
        {
            // how do we kill wallpaper? or, do we?
        }
    }

    void runOnAnonymousThread(Runnable action)
    {
        new Thread(action).start();
    }

    void runOnUiThread(Runnable action)
    {
        if (mContext instanceof Activity)
        {
            ((Activity) mContext).runOnUiThread(action);
        }
        else
        {
            android.util.Log.w("",  "Not running Unity from an Activity; ignored...");
        }
    }

    void postOnUiThread(Runnable action)
    {
        new Handler(Looper.getMainLooper()).post(action);
    }

    private Context mContext;
    private SurfaceView mGlView;

    private boolean mQuitting;
    private boolean mProcessKillRequested = true;

    private VideoPlayerProxy mVideoPlayerProxy;

    SoftInputDialog mSoftInputDialog = null;

    /** @deprecated No longer needed */                 public void init(final int glesMode, final boolean translucentSurface) {}
    /** @deprecated Use UnityPlayer directly instead */ public View getView() { return this; }
    /** @deprecated settings.xml no longer exist */     public Bundle getSettings() { return Bundle.EMPTY; }
    /** @deprecated Use destroy() */                    public void quit() { destroy(); }

    public void newIntent(Intent intent)
    {
        android.util.Log.i("",  "onNewIntent");
        m_launchUri = intent.getData();
        m_MainThread.urlActivated();
    }

    public void destroy()
    {
        android.util.Log.i("", "onDestroy");

        GoogleVrProxy googleVrProxy = GoogleVrApi.getGoogleVrProxy();
        if (googleVrProxy != null)
        {
            GoogleVrApi.destroyGoogleVrProxy();
            googleVrProxy = null;
        }

        if (m_Camera2Wrapper != null)
        {
            m_Camera2Wrapper.destroy();
            m_Camera2Wrapper = null;
        }

        if (m_HFPStatus != null)
        {
            m_HFPStatus.destroy();
            m_HFPStatus = null;
        }

        if (m_NetworkConnectivity != null)
        {
            m_NetworkConnectivity.destroy();
            m_NetworkConnectivity = null;
        }

        mQuitting = true;
        if (!mState.isPaused())
            pause();

        m_MainThread.quit();
        try
        {
            m_MainThread.join(ANR_TIMEOUT_SECONDS * 1000);
        }
        catch (InterruptedException e)
        {
            m_MainThread.interrupt();
        }

        if (mKillingIsMyBusiness != null)
            mContext.unregisterReceiver(mKillingIsMyBusiness);
        mKillingIsMyBusiness = null;

        if (UnityPlayerState.librariesHasBeenLoaded())
            removeAllViews();

        if (mProcessKillRequested) {
            if(m_UnityPlayerLifecycleEvents != null)
                m_UnityPlayerLifecycleEvents.onUnityPlayerQuitted();
            else
                onUnityPlayerQuitted();
            kill();
        }

        unloadNative();
    }

    // Used by native side
    protected void kill()
    {
        Process.killProcess(Process.myPid());
    }

    public void pause()
    {
        android.util.Log.w("",  "onPause");

        if (m_ARCoreApi != null)
        {
            m_ARCoreApi.pauseARCore();
        }

        if (mVideoPlayerProxy != null)
            mVideoPlayerProxy.onPause();

        GoogleVrProxy googleVrProxy = GoogleVrApi.getGoogleVrProxy();
        if (googleVrProxy != null)
            googleVrProxy.pauseGvrLayout();

        if (m_AudioVolumeHandler != null)
        {
            m_AudioVolumeHandler.destroy();
            m_AudioVolumeHandler = null;
        }

        pauseUnity();
    }

    private void pauseUnity()
    {
        reportSoftInputStr (null, kbCommand.hide, true);

        if (!mState.isRunning())
        {
            return;
        }

        if (mState.librariesHasBeenLoaded())
        {
            final Semaphore synchronize = new Semaphore(0);

            Runnable runnable = null;
            if (isFinishing())
                runnable = new Runnable(){ public void run(){ shutdown(); synchronize.release(); } };
            else
                runnable = new Runnable(){ public void run(){
                    if (nativePause()) { mQuitting = true; shutdown(); synchronize.release(2); }
                    else synchronize.release();
                }};

            m_MainThread.pauseExecution(runnable);

            try
            {
                if (!synchronize.tryAcquire(ANR_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                {
                    android.util.Log.w("",  "Timeout while trying to pause the Unity Engine.");
                }
            }
            catch (InterruptedException e)
            {
                android.util.Log.w("", "UI thread got interrupted while trying to pause the Unity Engine.");
            }
            if (synchronize.drainPermits() > 0)
                destroy();
        }

        mState.setIsRunning(false);
        mState.setPaused(true);

        if (m_AddPhoneCallListener)
        {
            m_TelephonyManager.listen(m_PhoneCallListener, PhoneStateListener.LISTEN_NONE);
        }


    }

    public void resume()
    {
        android.util.Log.i("", "onResume");

        if (m_ARCoreApi != null)
        {
            m_ARCoreApi.resumeARCore();
        }

        mState.setPaused(false);

        if (mVideoPlayerProxy != null)
            mVideoPlayerProxy.onResume();

        checkResumePlayer();
        // On resume if activity indicator is active it will have focus, thus preventing calling unity's m_MainThread.resumeExecution()
        // which is needed to resume running scripts by which activity indicator should be stopped.
        // Therefore need to stop activity indicator here to regain focus for the main window to resume scripts
        // and then activity indicator can be started again for normal operation.
        nativeRestartActivityIndicator();
        // This shouldn't be needed here but there is a hang that is causing nativeFocusChanges to lock up on resume and so nativeResume is never called.
        // TODO: Remove this when/if we get the resume issues worked out.
        GoogleVrProxy googleVrProxy = GoogleVrApi.getGoogleVrProxy();
        if (googleVrProxy != null) googleVrProxy.resumeGvrLayoutFix();

        m_AudioVolumeHandler = new AudioVolumeHandler(mContext);
    }

    public void lowMemory()
    {
        if (!UnityPlayerState.librariesHasBeenLoaded())
            return;
        queueGLThreadEvent(new Runnable(){ public void run(){ nativeLowMemory(); } } );
    }

    private void shutdown()
    {
        mProcessKillRequested = nativeDone();
        mState.setJNIInitialized(false);
    }

    // Have same effect as Application.Unload: put player on Pause in Sceneless state and
    // fire onUnityPlayerUnloaded ( by default UnityPlayerActivity moves task to background )
    public void unload() {
        nativeApplicationUnload();
    }

    private void checkResumePlayer()
    {

        if (!mState.canStart())
            return;

        mState.setIsRunning(true);

        queueGLThreadEvent(new Runnable(){ public void run(){ nativeResume(); } } );

        m_MainThread.resumeExecution();

        android.util.Log.v("",  mState.toString());
    }

    protected boolean skipPermissionsDialog()
    {
        if (MARSHMALLOW_SUPPORT)
        {
            if (currentActivity != null)
            {
                return MARSHMALLOW.skipPermissionsDialog(currentActivity);
            }
        }
        return false;
    }

    protected void requestUserAuthorization(final String permission)
    {
        if (MARSHMALLOW_SUPPORT)
        {
            if (permission != null && !permission.isEmpty() && currentActivity != null)
            {
                MARSHMALLOW.requestUserPermission(currentActivity, permission);
            }
        }
    }

    protected int getNetworkConnectivity()
    {
        if (NOUGAT_SUPPORT)
        {
            if (m_NetworkConnectivity == null)
                m_NetworkConnectivity= new NetworkConnectivity(mContext);

            return m_NetworkConnectivity.getNetworkConnectivity();
        }
        return 0;
    }

    public void configurationChanged(Configuration config)
    {
        android.util.Log.i("", "onConfigurationChanged");
        if (mGlView instanceof SurfaceView)
        {
            final SurfaceView view = ((SurfaceView)mGlView);
            view.getHolder().setSizeFromLayout();
        }
        if (mVideoPlayerProxy != null)
            mVideoPlayerProxy.onConfigurationChanged();

        GoogleVrProxy googleVrProxy = GoogleVrApi.getGoogleVrProxy();
        if (googleVrProxy != null)
        {
            googleVrProxy.configurationChanged$308b225b();
        }
    }

    public void windowFocusChanged(final boolean hasFocus)
    {
        android.util.Log.i("",  "windowFocusChanged: " + hasFocus);
        mState.setFocus(hasFocus);

        if (!mState.isJNIInitialized())
            return;

        if (hasFocus)
            m_MainThread.focusGained();
        else
            m_MainThread.focusLost();
        checkResumePlayer();
    }

    protected static boolean loadLibraryStatic(String libName)
    {
        try
        {
            System.loadLibrary(libName);
        }
        catch(UnsatisfiedLinkError ex)
        {
            android.util.Log.e("",  "Unable to find " + libName);
            return false;
        }
        catch(Exception ex)
        {
            android.util.Log.e("",  "Unknown error " + ex);
            return false;
        }
        return true;
    }

    protected boolean loadLibrary(String libName)
    {
        return loadLibraryStatic(libName);
    }


    protected void addPhoneCallListener()
    {
            m_AddPhoneCallListener = true;
            m_TelephonyManager.listen(m_PhoneCallListener, PhoneStateListener.LISTEN_CALL_STATE);
    }

    private final native void initJni (Context context);
    private final native boolean nativeRender ();
    private final native void nativeSetInputArea (int x, int y, int width, int height);
    private final native void nativeSetKeyboardIsVisible (boolean isVisible);
    private final native void nativeSetInputString (String inputString);
    private final native void nativeSetInputSelection (int start, int length);
    private final native void nativeSoftInputCanceled();
    private final native void nativeSoftInputLostFocus();
    private final native void nativeReportKeyboardConfigChanged();
    private final native boolean nativePause();
    private final native void nativeResume();
    private final native void nativeLowMemory();
    private final native void nativeApplicationUnload();
    private final native void nativeFocusChanged(boolean hasFocus);
    private final native void nativeRecreateGfxState(int index, Surface surface);
    private final native void nativeSendSurfaceChangedEvent();
    private final native boolean nativeDone ();
    private final native void nativeSoftInputClosed ();
    private final native boolean nativeInjectEvent(InputEvent event);
    private final native boolean nativeIsAutorotationOn();
    private final native void nativeMuteMasterAudio(boolean muteAudio);
    private final native void nativeRestartActivityIndicator();
    private final native void nativeSetLaunchURL(String url);
    private final native void nativeOrientationChanged (int naturalOrientation, int angle);

    static
    {
        // main / NativeActivity helper library
        // Note: Don't use loadLibraryStatic, because it swallows exception, and provides poor error message
        //       Instead let System.loadLibrary throw an exception if libmain.so is not found.
        //       It provides detailed information what paths were checked, etc.
        try
        {
            System.loadLibrary("main");
        }
        catch (UnsatisfiedLinkError ex)
        {
            android.util.Log.e("", "Failed to load 'libmain.so', the application will terminate.");
            throw ex;
        }
    }

    private void loadNative(ApplicationInfo applicationInfo)
    {
        String playerRuntimeDir;
        if(m_InstantGameName == null)
        {
            playerRuntimeDir = applicationInfo.nativeLibraryDir;
        }
        else
        {
            playerRuntimeDir = m_AndroidFilesDir + "/UnityPlayers/2019";
        }

        if (NativeLoader.load(playerRuntimeDir))
            UnityPlayerState.setLibrariesLoaded();
        else
        {
            android.util.Log.e("", "NativeLoader.load failure, Unity libraries were not loaded.");
        }
    }
    private static void unloadNative()
    {
        if (!UnityPlayerState.librariesHasBeenLoaded())
            return;
        if (!NativeLoader.unload()) {
            throw new UnsatisfiedLinkError("Unable to unload libraries from libmain.so");
        }
        UnityPlayerState.setLibrariesUnloaded();
    }

    protected void showSoftInput (final String initialText, final int type,
                                  final boolean correction,
                                  final boolean multiline,
                                  final boolean secure, final boolean alert,
                                  final String placeholder, final int characterLimit,
                                  final boolean isInputFieldHidden)
    {
        final UnityPlayer _this = this;
        postOnUiThread(new Runnable ()
        {
            public void run ()
            {
                mSoftInputDialog = new SoftInputDialog (mContext, _this,
                                                        initialText,
                                                        type, correction,
                                                        multiline, secure,
                                                        placeholder, characterLimit, isInputFieldHidden);

                mSoftInputDialog.setOnCancelListener(new DialogInterface.OnCancelListener()
                {
                      @Override
                      public void onCancel(DialogInterface dialog)
                      {
                          nativeSoftInputLostFocus();
                          reportSoftInputStr (null, kbCommand.hide, false);
                      }
                });

                mSoftInputDialog.show ();
                nativeReportKeyboardConfigChanged();
            }
        });
    }

    protected void hideSoftInput ()
    {
        postOnUiThread(new Runnable ()
        {
            public void run()
            {
                reportSoftInputArea(new Rect());
                reportSoftInputIsVisible(false);

                if (mSoftInputDialog != null)
                {
                    mSoftInputDialog.dismiss ();
                    mSoftInputDialog = null;
                    nativeReportKeyboardConfigChanged();
                }
            }
        });
    }

    protected void setSoftInputStr (final String text)
    {
        runOnUiThread (new Runnable () {
            public void run () {
                if(mSoftInputDialog != null && text != null)
                    mSoftInputDialog.setSoftInputStr(text);
            }
        });
    }

    protected void setCharacterLimit(final int characterLimit)
    {
        runOnUiThread (new Runnable () {
            public void run () {
                if(mSoftInputDialog != null)
                    mSoftInputDialog.setCharacterLimit(characterLimit);
            }
        });
    }

    protected void setHideInputField(final boolean isInputFieldHidden)
    {
       runOnUiThread(new Runnable() {
            public void run() {
                if (mSoftInputDialog != null)
                    mSoftInputDialog.setHideInputField(isInputFieldHidden);
            }
        });
    }

    protected void setSelection(final int start, final int length) {

        runOnUiThread(new Runnable() {
            public void run() {
                if (mSoftInputDialog != null)
                    mSoftInputDialog.setSelection(start, length);
            }
        });
    }

    protected String getKeyboardLayout() {
        if (mSoftInputDialog == null)
            return null;
        return mSoftInputDialog.getKeyboardLayout();
    }

    protected void reportSoftInputStr (final String str, final int cmd, final boolean canceled)
    {
        if (cmd == kbCommand.hide)
            hideSoftInput ();

        queueGLThreadEvent(new UnityRunnable () {
            public void doWork () {
                if (canceled)
                {
                    nativeSoftInputCanceled();
                }
                else if (str != null)
                {
                    nativeSetInputString (str);
                }

                if (cmd == kbCommand.hide)
                    nativeSoftInputClosed ();
            }
        });
    }

    protected void reportSoftInputSelection (final int start, final int length)
    {
        queueGLThreadEvent(new UnityRunnable () {
            public void doWork () {
                nativeSetInputSelection(start, length);
            }
        });
    }

    protected void reportSoftInputArea (final Rect area)
    {
        queueGLThreadEvent(new UnityRunnable () {
            public void doWork () {
                nativeSetInputArea(area.left, area.top, area.right, area.bottom);
            }
        });
    }

    protected void reportSoftInputIsVisible (final boolean isVisible)
    {
        queueGLThreadEvent(new UnityRunnable () {
            public void doWork () {
                nativeSetKeyboardIsVisible(isVisible);
            }
        });
    }

    protected void setClipboardText (final String text)
    {
        ClipData clipData = ClipData.newPlainText("Text", text);
        m_ClipboardManager.setPrimaryClip(clipData);
    }

    protected String getClipboardText () {
        String clipboardText = "";
        ClipData clipData = m_ClipboardManager.getPrimaryClip();
        if (clipData != null)
            clipboardText = clipData.getItemAt(0).coerceToText(mContext).toString();
        return clipboardText;
    }

    protected String getLaunchURL () {
        return m_launchUri != null ? m_launchUri.toString() : null;
    }

    protected boolean initializeGoogleAr()
    {
        // Initialize ARCore if have not done so already
        if (m_ARCoreApi == null && currentActivity != null && getTangoEnabled())
        {
            m_ARCoreApi = new GoogleARCoreApi();

            m_ARCoreApi.initializeARCore(currentActivity);

            if (!mState.isPaused())
            {
                m_ARCoreApi.resumeARCore();
            }
        }

        return false;
    }

    protected boolean initializeGoogleVr()
    {

        GoogleVrProxy googleVrProxy = GoogleVrApi.getGoogleVrProxy();
        if (googleVrProxy == null)
        {
            GoogleVrApi.createGoogleVrProxy(this);
            googleVrProxy = GoogleVrApi.getGoogleVrProxy();
            if (googleVrProxy == null)
            {
                android.util.Log.e("",  "Unable to create Google VR subsystem.");
                return false;
            }
        }

        final GoogleVrProxy googleVrProxyForThread = googleVrProxy;
        final Semaphore syncState = new Semaphore(0);

        final Runnable closeButtonHandler = new Runnable () {
                @Override
                public void run()
                {
                    injectEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
                    injectEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
                }
            };

        runOnUiThread(new Runnable () {
            @Override
            public void run()
            {
                if (!googleVrProxyForThread.initialize(currentActivity, mContext, CreateGlView(), closeButtonHandler))
                {
                    android.util.Log.e("",  "Unable to initialize Google VR subsystem.");
                }

                if (currentActivity != null)
                {
                    googleVrProxyForThread.setStateFromIntent(currentActivity.getIntent());
                }
                syncState.release();
            }
        });

        try
        {
            if(!syncState.tryAcquire(ANR_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            {
                android.util.Log.w("",  "Timeout while trying to initialize Google VR.");
                return false;
            }
        }
        catch(InterruptedException e)
        {
            android.util.Log.w("",  "UI thread was interrupted while initializing Google VR. " + e.getLocalizedMessage());
            return false;
        }

        return googleVrProxy.getVrInitialized();
    }

    protected boolean showVideoPlayer (final String fileName, final int backgroundColor,
                                    final int controlMode, final int scalingMode, final boolean isURL,
                                    final int videoOffset, final int videoLength)
    {
        if (mVideoPlayerProxy == null)
            mVideoPlayerProxy = new VideoPlayerProxy(this);
        boolean success = mVideoPlayerProxy.Initialize(mContext, fileName, backgroundColor, controlMode, scalingMode, isURL, videoOffset, videoLength,
            new VideoPlayerProxy.PlayerDestructionCallback() { public void playerDestroyed() {
                mVideoPlayerProxy = null;
            }});
        if(success)
        {
            runOnUiThread (new Runnable () { public void run()
            {
                if (nativeIsAutorotationOn() && mContext instanceof Activity)
                {
                    ((Activity)mContext).setRequestedOrientation(mInitialScreenOrientation);
                }
            }});
        }
        return success;
    }

    protected void notifyOnUnityPlayerUnloaded()
    {
        runOnUiThread (new Runnable () { public void run()
        {
            pause();
            windowFocusChanged(false);
            if(m_UnityPlayerLifecycleEvents != null)
                m_UnityPlayerLifecycleEvents.onUnityPlayerUnloaded();
            else
                onUnityPlayerUnloaded();
        }});
    }

    private static final String SPLASH_ENABLE_METADATA_NAME = "unity.splash-enable";
    private static final String SPLASH_MODE_METADATA_NAME = "unity.splash-mode";
    private static final String TANGO_ENABLE_METADATA_NAME = "unity.tango-enable";

    private ApplicationInfo getApplicationInfo() throws NameNotFoundException
    {
        return mContext.getPackageManager().getApplicationInfo(mContext.getPackageName(), PackageManager.GET_META_DATA);
    }

    private boolean getSplashEnabled()
    {
        try
        {
            return getApplicationInfo().metaData.getBoolean(SPLASH_ENABLE_METADATA_NAME);
        }
        catch (Exception ignore) { }
        return false;
    }

    private boolean getTangoEnabled()
    {
        try
        {
            return getApplicationInfo().metaData.getBoolean(TANGO_ENABLE_METADATA_NAME);
        }
        catch (Exception ignore) { }
        return false;
    }


    protected int getSplashMode()
    {
        try
        {
            return getApplicationInfo().metaData.getInt(SPLASH_MODE_METADATA_NAME);
        }
        catch (Exception ignore) { }
        return 0;// Centered
    }

    protected void executeGLThreadJobs()
    {
        Runnable job;
        while ((job = m_Events.poll()) != null)
            job.run();
    }
    protected void disableLogger()
    {
        Log.sDisableLog = true;
    }

    private abstract class UnityRunnable implements Runnable
    {
        final public void run() { if (!isFinishing()) doWork(); }
        public abstract void doWork();
    }

    private void queueGLThreadEvent(final Runnable r)
    {
        if (!mState.librariesHasBeenLoaded())
            return;
        if (Thread.currentThread() == m_MainThread)
            r.run();
        else
            m_Events.add(r);
    }

    private void queueGLThreadEvent(final UnityRunnable r)
    {
        // If process shutdown was already started, stop reporting
        // things into native code.
        if (isFinishing())
            return;

        queueGLThreadEvent((Runnable)r);
    }

    protected boolean isFinishing() {
        return mQuitting || (mQuitting = mContext instanceof Activity && ((Activity) mContext).isFinishing());
    }

    private void hideStatusBar()
    {
        if (mContext instanceof Activity)
        {
            Window window = ((Activity)mContext).getWindow();
            window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
    }

    public boolean injectEvent(InputEvent event)
    {
        if (!UnityPlayerState.librariesHasBeenLoaded())
            return false;
        return nativeInjectEvent(event);
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event)                  { return injectEvent(event); }
    @Override public boolean onKeyDown(int keyCode, KeyEvent event)                { return injectEvent(event); }
    @Override public boolean onKeyMultiple(int keyCode, int count, KeyEvent event) { return injectEvent(event); }
    @Override public boolean onKeyLongPress(int keyCode, KeyEvent event)           { return injectEvent(event); }
    @Override public boolean onTouchEvent(MotionEvent event)                       { return injectEvent(event); }
    /*API12*/ public boolean onGenericMotionEvent(MotionEvent event)               { return injectEvent(event); }


    private void swapViews(View viewToAdd, View viewToRemove)
    {
        ViewParent parent = null;
        boolean didPauseUnity = false;

        if (!mState.isPaused())
        {
            pause();
            didPauseUnity = true;
        }


        if (viewToAdd != null)
        {
            parent = viewToAdd.getParent();
            if (!(parent instanceof UnityPlayer) || ((UnityPlayer)parent) != this)
            {
                if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(viewToAdd);
                addView(viewToAdd);
                bringChildToFront(viewToAdd);
                viewToAdd.setVisibility(View.VISIBLE);
            }
        }

        if (viewToRemove != null)
        {
            parent = viewToRemove.getParent();
            if (parent == this)
            {
                viewToRemove.setVisibility(View.GONE);
                removeView(viewToRemove);
            }
        }

        if (didPauseUnity)
        {
            resume();
        }
    }

    public boolean addViewToPlayer(View view, boolean shouldReplaceContent)
    {
        swapViews(view, shouldReplaceContent ? mGlView : null);
        boolean viewIsParentedToUs = view.getParent() == this;
        boolean oldViewWasReplaced = shouldReplaceContent && mGlView.getParent() == null;
        boolean oldViewIsParentedToUs = mGlView.getParent() == this;
        boolean ret = (viewIsParentedToUs && (oldViewWasReplaced || oldViewIsParentedToUs));
        if (!ret)
        {
            if (!viewIsParentedToUs)
            {
                android.util.Log.e("",  "addViewToPlayer: Failure adding view to hierarchy");
            }

            if (!oldViewWasReplaced && !oldViewIsParentedToUs)
            {
                android.util.Log.e("",  "addViewToPlayer: Failure removing old view from hierarchy");
            }
        }

        return ret;
    }

    public void removeViewFromPlayer(View view)
    {
        swapViews(mGlView, view);
        boolean viewWasRemoved = view.getParent() == null;
        boolean oldViewIsParentedToUs = mGlView.getParent() == this;
        boolean ret = (viewWasRemoved && oldViewIsParentedToUs);
        if (!ret)
        {
            if (!viewWasRemoved)
            {
                android.util.Log.e("",  "removeViewFromPlayer: Failure removing view from hierarchy");
            }

            if (!oldViewIsParentedToUs)
            {
                android.util.Log.e("",  "removeVireFromPlayer: Failure agging old view to hierarchy");
            }
        }
    }

    public void reportError(String title, String message)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(title);
        sb.append(": ");
        sb.append(message);
        android.util.Log.e("",  sb.toString());
        return;
    }

    public String getNetworkProxySettings(String url)
    {
        String hostProp, portProp, bypassProp;
        if (url.startsWith("http:"))
        {
            hostProp = "http.proxyHost";
            portProp = "http.proxyPort";
            bypassProp = "http.nonProxyHosts";
        }
        else if (url.startsWith("https:"))
        {
            hostProp = "https.proxyHost";
            portProp = "https.proxyPort";
            bypassProp = "http.nonProxyHosts";  // same as for http:
        }
        else
            return null;
        String host = System.getProperties().getProperty(hostProp);
        if (host == null || "".equals(host))
            return null;
        StringBuilder proxy = new StringBuilder(host);
        String port = System.getProperties().getProperty(portProp);
        if (port != null && !"".equals(port))
            proxy.append(":").append(port);
        String bypass = System.getProperties().getProperty(bypassProp);
        if (bypass != null && !"".equals(bypass))
            proxy.append('\n').append(bypass);
        return proxy.toString();
    }

    public boolean startOrientationListener(int rate)
    {
        if (mOrientationListener != null)
        {
            android.util.Log.w("",  "Orientation Listener already started.");
            return false;
        }
        mOrientationListener = new OrientationEventListener(mContext, rate)
        {
            @Override
            public void onOrientationChanged(int angle)
            {
                m_MainThread.orientationChanged(mNaturalOrientation, angle);
            }
        };

        if (mOrientationListener.canDetectOrientation())
        {
            mOrientationListener.enable();
            return true;
        }
        else
        {
            android.util.Log.w("",  "Orientation Listener cannot detect orientation.");
            return false;
        }
    }

    public boolean stopOrientationListener()
    {
        if (mOrientationListener == null)
        {
            android.util.Log.w("",  "Orientation Listener was not started.");
            return false;
        }

        mOrientationListener.disable();
        mOrientationListener = null;
        return true;
    }
}
