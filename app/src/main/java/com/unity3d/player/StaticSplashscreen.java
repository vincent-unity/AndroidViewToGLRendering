package com.unity3d.player;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.content.Context;

public class StaticSplashscreen extends View
{
    enum ScaleMode
    {
        Center,
        Fit,
        Fill
    }

    final ScaleMode m_ScaleMode;
    final int       m_SplashImageResourceId;

    Bitmap    m_SplashImage;
    Bitmap    m_SplashImageScaled;

    public StaticSplashscreen(Context context, ScaleMode mode)
    {
        super(context);
        m_ScaleMode = mode;
        m_SplashImageResourceId = getResources().getIdentifier("unity_static_splash", "drawable", getContext().getPackageName());
        if (m_SplashImageResourceId != 0)
        {
            forceLayout();
        }
    }

    @Override
    public void onLayout(boolean changed, int left, int top, int right, int bottom)
    {
        if(m_SplashImageResourceId == 0)
            return;

        if (m_SplashImage == null)
        {
            BitmapFactory.Options options = new BitmapFactory.Options();
            // don't scale image according to the density (to avoid out of memory exception)
            options.inScaled = false;
            m_SplashImage = BitmapFactory.decodeResource(getResources(), m_SplashImageResourceId, options);
        }

        int width = m_SplashImage.getWidth();
        int height = m_SplashImage.getHeight();

        final int screenWidth = getWidth();
        final int screenHeight = getHeight();
        if (screenWidth == 0 || screenHeight == 0)
            return;

        final float splashAspect = (float)width / (float)height;
        final float screenAspect = (float)screenWidth / (float)screenHeight;
        final boolean splashWiderThanScreen = screenAspect <= splashAspect;

        switch(m_ScaleMode)
        {
            case Center:
            {
                if (screenWidth < width)
                {
                    width = screenWidth;
                    height = (int) ((float) width / splashAspect);
                }
                if (screenHeight < height)
                {
                    height = screenHeight;
                    width = (int) ((float) height * splashAspect);
                }
                break;
            }
            case Fit:
            case Fill:
            {
                width = screenWidth;
                height = screenHeight;
                if (splashWiderThanScreen ^ (m_ScaleMode == ScaleMode.Fill))
                    height = (int) ((float) screenWidth / splashAspect);
                else
                    width = (int) ((float) screenHeight * splashAspect);
                break;
            }
        }

        if (m_SplashImageScaled != null)
        {
            if (m_SplashImageScaled.getWidth() == width && m_SplashImageScaled.getHeight() == height)
                return;

            // createScaledBitmap can return the source bitmap instead of creating a new one if the specified width and height
            // are the same as the values of the source bitmap. Don't recycle m_SplashImageScaled in that case.
            if (m_SplashImageScaled != m_SplashImage)
            {
                m_SplashImageScaled.recycle();
                m_SplashImageScaled = null;
            }
        }

        // draw unity_static_splash as the window background
        m_SplashImageScaled = Bitmap.createScaledBitmap(m_SplashImage, width, height, true);
        // make image scale correctly when displayed on screen
        m_SplashImageScaled.setDensity(getResources().getDisplayMetrics().densityDpi);


        // setup drawables
        ColorDrawable backgroundColor = new ColorDrawable(Color.BLACK);
        BitmapDrawable backgroundImage = new BitmapDrawable(getResources(), m_SplashImageScaled);
        backgroundImage.setGravity(Gravity.CENTER);

        setBackground(new LayerDrawable(new Drawable[]{backgroundColor, backgroundImage}));
    }

    @Override
    public void onDetachedFromWindow()
    {
        super.onDetachedFromWindow();

        if (m_SplashImage != null)
        {
            m_SplashImage.recycle();
            m_SplashImage = null;
        }
        if (m_SplashImageScaled != null)
        {
            m_SplashImageScaled.recycle();
            m_SplashImageScaled = null;
        }
    }
}
