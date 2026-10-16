
package activity;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.daljeet.screenrecording.R;

import service.ScreenRecordingService;

public class MainActivity extends AppCompatActivity {

    private Button btnStart;
    private Button btnStop;
    private TextView txtStatus;

    private MediaProjectionManager projectionManager;

    private ActivityResultLauncher<Intent> screenCaptureLauncher;
    private ActivityResultLauncher<String> microphonePermissionLauncher;

    private boolean receiverRegistered = false;
    private boolean startRequestPending = false;

    private static final String ACTION_RECORDING_STATUS =
            "com.daljeet.screenrecording.RECORDING_STATUS";

    private final BroadcastReceiver recordingReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;

                    String status = intent.getStringExtra("status");
                    String message = intent.getStringExtra("message");
                    String path = intent.getStringExtra("path");

                    if (message != null) {
                        txtStatus.setText(message);
                    }

                    if ("RECORDING".equals(status)) {
                        startRequestPending = false;
                        btnStart.setEnabled(false);
                        btnStop.setEnabled(true);

                    } else if ("STOPPING".equals(status)) {
                        btnStart.setEnabled(false);
                        btnStop.setEnabled(false);

                    } else if ("SAVED".equals(status)) {
                        startRequestPending = false;
                        btnStart.setEnabled(true);
                        btnStop.setEnabled(false);

                        String savedLocation = path != null
                                ? path
                                : "Movies/ScreenRecordings";

                        Toast.makeText(
                                MainActivity.this,
                                "Recording saved:\n" + savedLocation,
                                Toast.LENGTH_LONG
                        ).show();

                    } else if ("ERROR".equals(status)) {
                        startRequestPending = false;
                        btnStart.setEnabled(true);
                        btnStop.setEnabled(false);

                        Toast.makeText(
                                MainActivity.this,
                                message != null
                                        ? message
                                        : "Recording failed",
                                Toast.LENGTH_LONG
                        ).show();
                    }
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        txtStatus = findViewById(R.id.txtStatus);

        projectionManager = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        txtStatus.setText("Ready to record");
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);

        microphonePermissionLauncher =
                registerForActivityResult(
                        new ActivityResultContracts.RequestPermission(),
                        granted -> {
                            if (granted) {
                                requestScreenCapture();
                            } else {
                                // Screen recording can still be possible
                                // without microphone audio, if the service
                                // is configured for video-only recording.
                                new android.app.AlertDialog.Builder(
                                        MainActivity.this
                                )
                                        .setTitle("Microphone permission")
                                        .setMessage(
                                                "Allow microphone access to record audio, " +
                                                        "or continue without microphone audio if " +
                                                        "your recording service supports it."
                                        )
                                        .setNegativeButton(
                                                "Cancel",
                                                (dialog, which) -> {
                                                    txtStatus.setText(
                                                            "Recording cancelled"
                                                    );
                                                    resetButtons();
                                                }
                                        )
                                        .setPositiveButton(
                                                "Open Settings",
                                                (dialog, which) -> {
                                                    Intent settingsIntent =
                                                            new Intent(
                                                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                                    Uri.parse(
                                                                            "package:" +
                                                                                    getPackageName()
                                                                    )
                                                            );
                                                    startActivity(settingsIntent);
                                                    resetButtons();
                                                }
                                        )
                                        .show();
                            }
                        }
                );

        screenCaptureLauncher =
                registerForActivityResult(
                        new ActivityResultContracts.StartActivityForResult(),
                        result -> {
                            if (result.getResultCode() != Activity.RESULT_OK
                                    || result.getData() == null) {
                                txtStatus.setText(
                                        "Screen capture permission denied"
                                );
                                resetButtons();
                                return;
                            }

                            Intent serviceIntent = new Intent(
                                    MainActivity.this,
                                    ScreenRecordingService.class
                            );

                            serviceIntent.setAction(
                                    ScreenRecordingService.ACTION_START
                            );

                            serviceIntent.putExtra(
                                    "resultCode",
                                    result.getResultCode()
                            );

                            serviceIntent.putExtra(
                                    "data",
                                    result.getData()
                            );

                            try {
                                startRequestPending = true;
                                txtStatus.setText("Starting recording...");
                                btnStart.setEnabled(false);
                                btnStop.setEnabled(false);

                                ContextCompat.startForegroundService(
                                        MainActivity.this,
                                        serviceIntent
                                );

                            } catch (Exception e) {
                                startRequestPending = false;
                                showStartError(e.getMessage());
                            }
                        }
                );

        btnStart.setOnClickListener(v -> checkMicrophonePermission());

        btnStop.setOnClickListener(v -> {
            txtStatus.setText("Stopping recording...");
            btnStart.setEnabled(false);
            btnStop.setEnabled(false);

            Intent stopIntent = new Intent(
                    MainActivity.this,
                    ScreenRecordingService.class
            );

            stopIntent.setAction(ScreenRecordingService.ACTION_STOP);

            try {
                startService(stopIntent);
            } catch (Exception e) {
                txtStatus.setText(
                        "Could not stop recording: " + e.getMessage()
                );
                resetButtons();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();

        IntentFilter filter = new IntentFilter(ACTION_RECORDING_STATUS);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                    recordingReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
            );
        } else {
            registerReceiver(recordingReceiver, filter);
        }

        receiverRegistered = true;
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(recordingReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            receiverRegistered = false;
        }

        super.onStop();
    }

    private void checkMicrophonePermission() {
        if (startRequestPending) return;

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED) {
            requestScreenCapture();
        } else {
            microphonePermissionLauncher.launch(
                    Manifest.permission.RECORD_AUDIO
            );
        }
    }

    private void requestScreenCapture() {
        if (projectionManager == null) {
            txtStatus.setText(
                    "Screen recording is unavailable on this device"
            );
            resetButtons();
            return;
        }

        try {
            txtStatus.setText("Requesting screen capture permission...");
            screenCaptureLauncher.launch(
                    projectionManager.createScreenCaptureIntent()
            );
        } catch (Exception e) {
            showStartError(e.getMessage());
        }
    }

    private void resetButtons() {
        startRequestPending = false;
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
    }

    private void showStartError(String message) {
        txtStatus.setText("Could not start recording");

        resetButtons();

        Toast.makeText(
                this,
                "Recording error: " +
                        (message != null ? message : "Unknown error"),
                Toast.LENGTH_LONG
        ).show();
    }
}
