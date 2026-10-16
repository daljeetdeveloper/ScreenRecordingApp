
package service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;

import android.content.Context;

public class ScreenRecordingService extends Service {

    public static final String ACTION_START =
            "com.daljeet.screenrecording.START";

    public static final String ACTION_STOP =
            "com.daljeet.screenrecording.STOP";

    private static final String ACTION_RECORDING_STATUS =
            "com.daljeet.screenrecording.RECORDING_STATUS";

    private static final String TAG = "ScreenRecorder";
    private static final String CHANNEL_ID = "screen_recording_channel";
    private static final int NOTIFICATION_ID = 1001;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private MediaRecorder mediaRecorder;
    private File outputFile;

    private boolean isRecording = false;
    private boolean isStopping = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        String action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            sendStatus("STOPPING", "Stopping recording...", null);
            stopRecording();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            if (isRecording) {
                sendStatus("RECORDING", "Recording in progress", null);
                return START_NOT_STICKY;
            }

            try {
                startForegroundCompat();

                int resultCode = intent.getIntExtra(
                        "resultCode",
                        0
                );

                Intent projectionData =
                        getProjectionIntent(intent);

                if (projectionData == null) {
                    throw new IOException(
                            "Screen capture permission data is missing"
                    );
                }

                MediaProjectionManager projectionManager =
                        (MediaProjectionManager)
                                getSystemService(
                                        Context.MEDIA_PROJECTION_SERVICE
                                );

                if (projectionManager == null) {
                    throw new IOException(
                            "MediaProjection is unavailable"
                    );
                }

                mediaProjection =
                        projectionManager.getMediaProjection(
                                resultCode,
                                projectionData
                        );

                if (mediaProjection == null) {
                    throw new IOException(
                            "Could not initialize screen capture"
                    );
                }

                mediaProjection.registerCallback(
                        new MediaProjection.Callback() {
                            @Override
                            public void onStop() {
                                if (!isStopping) {
                                    stopRecording();
                                }
                            }
                        },
                        new android.os.Handler(
                                android.os.Looper.getMainLooper()
                        )
                );

                startRecording();

            } catch (Exception e) {
                Log.e(TAG, "Could not start recording", e);

                sendStatus(
                        "ERROR",
                        "Recording failed: " + e.getMessage(),
                        null
                );

                stopRecording();
            }
        }

        return START_NOT_STICKY;
    }

    private Intent getProjectionIntent(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(
                    "data",
                    Intent.class
            );
        }

        return intent.getParcelableExtra("data");
    }

    private void startForegroundCompat() {
        Notification notification = createNotification();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int serviceType =
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                serviceType |=
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }

            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    serviceType
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void startRecording() throws Exception {

        DisplayMetrics metrics = new DisplayMetrics();

        WindowManager windowManager =
                (WindowManager) getSystemService(WINDOW_SERVICE);

        if (windowManager == null) {
            throw new IOException("Window manager unavailable");
        }

        windowManager.getDefaultDisplay().getRealMetrics(metrics);

        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int density = metrics.densityDpi;

        // H.264 commonly requires dimensions divisible by 2.
        width = width - (width % 2);
        height = height - (height % 2);

        if (width <= 0 || height <= 0) {
            throw new IOException("Invalid display dimensions");
        }

        File moviesDir = new File(
                getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                "ScreenRecordings"
        );

        if (!moviesDir.exists() && !moviesDir.mkdirs()) {
            throw new IOException(
                    "Could not create recording directory"
            );
        }

        outputFile = new File(
                moviesDir,
                "Recording_" + System.currentTimeMillis() + ".mp4"
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            mediaRecorder = new MediaRecorder(this);
        } else {
            mediaRecorder = new MediaRecorder();
        }

        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);

        // Audio is optional; if microphone setup fails, retry video-only.
        try {
            mediaRecorder.setAudioSource(
                    MediaRecorder.AudioSource.MIC
            );
        } catch (Exception e) {
            Log.w(TAG, "Microphone source unavailable", e);
        }

        mediaRecorder.setOutputFormat(
                MediaRecorder.OutputFormat.MPEG_4
        );

        mediaRecorder.setOutputFile(outputFile.getAbsolutePath());

        mediaRecorder.setVideoEncoder(
                MediaRecorder.VideoEncoder.H264
        );

        mediaRecorder.setVideoSize(width, height);
        mediaRecorder.setVideoFrameRate(30);
        mediaRecorder.setVideoEncodingBitRate(6_000_000);

        // Audio is configured only when its source was accepted.
        try {
            mediaRecorder.setAudioEncoder(
                    MediaRecorder.AudioEncoder.AAC
            );
            mediaRecorder.setAudioEncodingBitRate(128_000);
            mediaRecorder.setAudioSamplingRate(44100);
        } catch (Exception e) {
            Log.w(TAG, "Audio encoder setup failed", e);
        }

        mediaRecorder.prepare();

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenRecordingDisplay",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder.getSurface(),
                null,
                null
        );

        if (virtualDisplay == null) {
            throw new IOException(
                    "Could not create virtual display"
            );
        }

        mediaRecorder.start();
        isRecording = true;

        Log.i(TAG, "Recording started: " +
                outputFile.getAbsolutePath());

        sendStatus(
                "RECORDING",
                "Recording screen...",
                null
        );
    }

    private void stopRecording() {
        if (isStopping) return;
        isStopping = true;

        boolean stoppedSuccessfully = false;

        try {
            if (isRecording && mediaRecorder != null) {
                try {
                    mediaRecorder.stop();
                    stoppedSuccessfully = true;
                } catch (RuntimeException e) {
                    Log.e(TAG, "Recorder stopped without valid output", e);
                }
            }

            isRecording = false;

            if (mediaRecorder != null) {
                try {
                    mediaRecorder.reset();
                } catch (Exception ignored) {
                }

                try {
                    mediaRecorder.release();
                } catch (Exception ignored) {
                }

                mediaRecorder = null;
            }

            if (virtualDisplay != null) {
                virtualDisplay.release();
                virtualDisplay = null;
            }

            if (mediaProjection != null) {
                try {
                    mediaProjection.stop();
                } catch (Exception ignored) {
                }

                mediaProjection = null;
            }

            if (stoppedSuccessfully
                    && outputFile != null
                    && outputFile.exists()
                    && outputFile.length() > 0) {

                Uri galleryUri = saveVideoToGallery(outputFile);

                if (galleryUri != null) {
                    sendStatus(
                            "SAVED",
                            "Recording saved to Gallery",
                            galleryUri.toString()
                    );
                    Log.i(TAG, "Video saved: " + galleryUri);
                } else {
                    sendStatus(
                            "SAVED",
                            "Recording saved in app storage: " +
                                    outputFile.getAbsolutePath(),
                            outputFile.getAbsolutePath()
                    );
                    Log.i(TAG, "Video saved: " +
                            outputFile.getAbsolutePath());
                }

            } else {
                sendStatus(
                        "SAVED",
                        "Recording saved in app storage: " +
                                outputFile.getAbsolutePath(),
                        outputFile.getAbsolutePath()
                );
            }

        } catch (Exception e) {
            Log.e(TAG, "Error stopping recording", e);
            sendStatus(
                    "ERROR",
                    "Error saving recording: " + e.getMessage(),
                    null
            );
        } finally {
            stopForeground(true);
            stopSelf();
            isStopping = false;
        }
    }

    private Uri saveVideoToGallery(File source) {

        if (source == null || !source.exists() || source.length() == 0) {
            return null;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri collection = MediaStore.Video.Media.getContentUri(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY
            );

            ContentValues values = new ContentValues();
            values.put(
                    MediaStore.Video.Media.DISPLAY_NAME,
                    source.getName()
            );
            values.put(
                    MediaStore.Video.Media.MIME_TYPE,
                    "video/mp4"
            );
            values.put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES +
                            "/ScreenRecordings"
            );
            values.put(
                    MediaStore.Video.Media.IS_PENDING,
                    1
            );

            Uri uri = null;

            try {
                uri = getContentResolver().insert(collection, values);

                if (uri == null) return null;

                try (InputStream input = new FileInputStream(source);
                     OutputStream output =
                             getContentResolver().openOutputStream(uri)) {

                    if (output == null) {
                        throw new IOException(
                                "Could not open Gallery output"
                        );
                    }

                    byte[] buffer = new byte[8192];
                    int count;

                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                    }

                    output.flush();
                }

                ContentValues completed = new ContentValues();
                completed.put(
                        MediaStore.Video.Media.IS_PENDING,
                        0
                );

                getContentResolver().update(
                        uri,
                        completed,
                        null,
                        null
                );

                return uri;

            } catch (Exception e) {
                Log.e(TAG, "Gallery save failed", e);

                if (uri != null) {
                    try {
                        getContentResolver().delete(uri, null, null);
                    } catch (Exception ignored) {
                    }
                }

                return null;
            }
        }

        // Android 9 and earlier: public Movies directory.
        File publicDir = new File(
                Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_MOVIES
                ),
                "ScreenRecordings"
        );

        if (!publicDir.exists() && !publicDir.mkdirs()) {
            return null;
        }

        File destination = new File(publicDir, source.getName());

        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(destination)) {

            byte[] buffer = new byte[8192];
            int count;

            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }

            output.flush();

            Intent scanIntent = new Intent(
                    Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                    Uri.fromFile(destination)
            );
            sendBroadcast(scanIntent);

            return Uri.fromFile(destination);

        } catch (Exception e) {
            Log.e(TAG, "Public storage save failed", e);
            return null;
        }
    }

    private void sendStatus(
            String status,
            String message,
            String path
    ) {
        Intent broadcast = new Intent(ACTION_RECORDING_STATUS);
        broadcast.setPackage(getPackageName());
        broadcast.putExtra("status", status);
        broadcast.putExtra("message", message);

        if (path != null) {
            broadcast.putExtra("path", path);
        }

        sendBroadcast(broadcast);
    }

    private Notification createNotification() {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Screen Recorder")
                .setContentText("Recording your screen")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Screen Recording",
                    NotificationManager.IMPORTANCE_LOW
            );

            NotificationManager manager =
                    getSystemService(NotificationManager.class);

            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        if (!isStopping && (isRecording || mediaRecorder != null)) {
            stopRecording();
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
