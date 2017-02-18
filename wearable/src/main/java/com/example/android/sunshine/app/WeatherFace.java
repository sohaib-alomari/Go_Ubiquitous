/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import static com.example.android.sunshine.app.DigitalWatchFaceUtil.day;
import static com.example.android.sunshine.app.DigitalWatchFaceUtil.getIconResourceForWeatherCondition;
import static com.example.android.sunshine.app.DigitalWatchFaceUtil.getstringforweatherCondition;
import static com.example.android.sunshine.app.DigitalWatchFaceUtil.month;


public class WeatherFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(500);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<WeatherFace.Engine> mWeakReference;

        public EngineHandler(WeatherFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            WeatherFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
        final Handler mUpdateTime = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaint,mSmallTmpText;
        Paint timePaint,caPaint;
        boolean mAmbient;
        Calendar mCalendar;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        float mXOffset;
        float mYOffset;
        String maxTemp = "0", minTemp = "0";
        int weatherId = 200;


        boolean mLowBitAmbient;
        private GoogleApiClient googleApiClient;
        float textSize;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(WeatherFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());

            Resources resources = WeatherFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.primary));


            timePaint=new Paint();
            timePaint=createTextPaint(resources.getColor(R.color.digital_time));

            caPaint=new Paint();
            caPaint=createTextPaint(resources.getColor(R.color.digital_time));
            caPaint.setTypeface(Typeface.defaultFromStyle(Typeface.BOLD));

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mSmallTmpText = new Paint();
            mSmallTmpText = createTextPaint(resources.getColor(R.color.digital_text));

            mCalendar = Calendar.getInstance();

            //For Weather Update

            googleApiClient = new GoogleApiClient.Builder(WeatherFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();
        }

        @Override
        public void onDestroy() {
            mUpdateTime.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Connect for receiving message from mobile
                googleApiClient.connect();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
            }
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            WeatherFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            WeatherFace.this.unregisterReceiver(mTimeZoneReceiver);
            if (googleApiClient != null && googleApiClient.isConnected()) {
                googleApiClient.disconnect();
            }
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);
            Resources resources = WeatherFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            timePaint.setTextSize(textSize);
            caPaint.setTextSize(textSize/2);
            mTextPaint.setTextSize(textSize);
            mSmallTmpText.setTextSize(textSize/2);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    timePaint.setAntiAlias(!inAmbientMode);
                    mTextPaint.setAntiAlias(!inAmbientMode);
                    mSmallTmpText.setAntiAlias(!inAmbientMode);
                    caPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }
            updateTimer();
        }



        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
                timePaint.setColor(getColor(R.color.digital_text));
            } else {
                timePaint.setColor(getColor(R.color.digital_time));
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
                canvas.drawRect(0, bounds.centerY()+10, bounds.width(), bounds.height(), mTextPaint);

            }


            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            String text =  String.format("%02d:%02d", mCalendar.get(Calendar.HOUR),
                    mCalendar.get(Calendar.MINUTE));

            Rect dim=new Rect();

            timePaint.getTextBounds(text,0,text.length(),dim);
            float timeCenter=dim.width()/2;
            float timeHeight=dim.height();

            //Draw the Time
            canvas.drawText(text,timeCenter, bounds.centerY()+timeHeight+22 , timePaint);



            float widthOfTime = timePaint.measureText(text);
            float afterTimeXOffset = mXOffset + widthOfTime;


            canvas.drawText(maxTemp+"\u00b0"+"c", afterTimeXOffset+15,60, mSmallTmpText);
            canvas.drawText(minTemp+"\u00b0"+"c", afterTimeXOffset+15,100, mSmallTmpText);


            float dateH=bounds.centerY()+2*timeHeight+20;
            canvas.drawText(mCalendar.get(Calendar.DAY_OF_MONTH) + " " +
                            month(mCalendar.get(Calendar.MONTH)) + " " +
                            new String(mCalendar.get(Calendar.YEAR)+"").substring(2,4) + ", " +
                            day(mCalendar.get(Calendar.DAY_OF_WEEK))

                    , timeCenter-10,dateH, caPaint);

            //Draw The Status of the Weather as a String (Rainy, Sunny....etc
            Rect dimns= new Rect();
            String wthrID=getstringforweatherCondition(weatherId);
            mSmallTmpText.getTextBounds(wthrID,0,wthrID.length(),dimns);
            float wthId_Center=dimns.width()/2;

            canvas.drawText(getstringforweatherCondition(weatherId), wthId_Center/2, bounds.centerY()-5, mSmallTmpText);
            canvas.drawLine(0,bounds.centerY()+11,bounds.right,bounds.centerY()+11,timePaint);


            if(!isInAmbientMode()) {
                int icon = getIconResourceForWeatherCondition(weatherId);
                Bitmap weatherIcon = BitmapFactory.decodeResource(getResources(), icon);
                Bitmap resized = Bitmap.createScaledBitmap(weatherIcon, weatherIcon.getWidth()*2,weatherIcon.getHeight()*2, true);

                canvas.drawBitmap(resized, 30, 10, timePaint);
            }
        }


        /**
         * Starts the {@link #mUpdateTime} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTime.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTime.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTime} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTime.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Wearable.DataApi.addListener(googleApiClient, Engine.this);
        }


        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            Log.d("Data Change", "Called");
            for (DataEvent event : dataEvents) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem item = event.getDataItem();
                    processConfigurationFor(item);
                }
            }

            dataEvents.release();
            invalidate();
        }

        private void processConfigurationFor(DataItem item) {
            if ("/wear_face".equals(item.getUri().getPath())) {
                DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                if (dataMap.containsKey("HIGH_TEMP"))
                    maxTemp = dataMap.getString("HIGH_TEMP");
                if (dataMap.containsKey("LOW_TEMP"))
                    minTemp = dataMap.getString("LOW_TEMP");
                if (dataMap.containsKey("WEATHER_ID"))
                    weatherId = dataMap.getInt("WEATHER_ID");
            }
        }


        @Override
        public void onConnectionSuspended(int i) {
            Log.d("Connection","Fail");
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.d("Connection","Fail"+connectionResult.getErrorMessage());
        }
    }
}
