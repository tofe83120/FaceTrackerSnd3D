/*
 * Copyright (C) The Android Open Source Project
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
package com.google.android.gms.samples.vision.face.facetrackersnd3d;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Vibrator;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.samples.vision.face.facetrackersnd3d.ui.camera.CameraSourcePreview;
import com.google.android.gms.samples.vision.face.facetrackersnd3d.ui.camera.GraphicOverlay;
import com.google.android.gms.vision.CameraSource;
import com.google.android.gms.vision.MultiProcessor;
import com.google.android.gms.vision.Tracker;
import com.google.android.gms.vision.face.Face;
import com.google.android.gms.vision.face.FaceDetector;
import com.google.vr.sdk.audio.GvrAudioEngine;

import java.io.IOException;

/**
 * Activity for the face tracker app.  This app detects faces with the rear facing camera, and draws
 * overlay graphics to indicate the position, size, and ID of each face.
 */
public final class FaceTrackerActivity extends AppCompatActivity {
    private static final String TAG = "FaceTracker";

    private CameraSource mCameraSource = null;

    private CameraSourcePreview mPreview;
    private GraphicOverlay mGraphicOverlay;

    private static final int RC_HANDLE_GMS = 9001;
    // permission request codes need to be < 256
    private static final int RC_HANDLE_CAMERA_PERM = 2;

    private boolean mIsFrontFacing = true;

    //Spacial sound
    //private static final String OBJECT_SOUND_FILE = "1942.wav";
    //private static final String OBJECT_SOUND_FILE = "success.wav";
    private static final String OBJECT_SOUND_FILE = "2041.wav";
    private GvrAudioEngine gvrAudioEngine = null;
    private volatile int sourceId = GvrAudioEngine.INVALID_ID;
    private long lastSound = System.currentTimeMillis();

    private Vibrator vibrator;

    // Nexus 4
    private final float facePosMinX = -126.7699f;
    private final float facePosMinY = -100.79914f;
    private final float facePosMaxX = 394.55118f;
    private final float facePosMaxY = 528.54156f;
    private final float faceWidthMax = 470.39392f;
    private final float facePosMoyX = (Math.abs(facePosMaxX) + Math.abs(facePosMinX)) / 2.0f;
    private final float facePosMoyY = (Math.abs(facePosMaxY) + Math.abs(facePosMinY)) / 2.0f;

    //==============================================================================================
    // Activity Methods
    //==============================================================================================

    /**
     * Initializes the UI and initiates the creation of a face detector.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(R.layout.main);

        // Init UI
        mPreview = (CameraSourcePreview) findViewById(R.id.preview);
        mGraphicOverlay = (GraphicOverlay) findViewById(R.id.faceOverlay);

        final Button button = (Button) findViewById(R.id.flipButton);
        button.setOnClickListener(mFlipButtonListener);

        if (savedInstanceState != null) {
            mIsFrontFacing = savedInstanceState.getBoolean("IsFrontFacing");
        }

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        // Initialize 3D audio engine.
        gvrAudioEngine = new GvrAudioEngine(this, GvrAudioEngine.RenderingMode.BINAURAL_HIGH_QUALITY);
        if(gvrAudioEngine != null) {
            // Avoid any delays during start-up due to decoding of sound files.
            new Thread(
                    new Runnable() {
                        @Override
                        public void run() {
                            // Start spatial audio playback of OBJECT_SOUND_FILE at the model position. The
                            // returned sourceId handle is stored and allows for repositioning the sound object
                            // whenever the cube position changes.
                            if(gvrAudioEngine.preloadSoundFile(OBJECT_SOUND_FILE)) {
                                Log.e(TAG, "Failed to preload sound file !");
                            }

                            while(true) {
                                if(gvrAudioEngine != null) {
                                    if (gvrAudioEngine.isSoundPlaying(sourceId)) {
                                        // Regular update call to GVR audio engine.
                                        gvrAudioEngine.update();
                                    }
                                }
                            }
                        }
                    })
                    .start();
        } else {
            Log.e(TAG, "Failed to start GvrAudioEngine !");
        }

        // Check for the camera permission before accessing the camera.  If the
        // permission is not granted yet, request permission.
        int rc = ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA);
        if (rc == PackageManager.PERMISSION_GRANTED) {
            createCameraSource();
        } else {
            requestCameraPermission();
        }
    }

    /**
     * Handles the requesting of the camera permission.  This includes
     * showing a "Snackbar" message of why the permission is needed then
     * sending the request.
     */
    private void requestCameraPermission() {
        Log.w(TAG, "Camera permission is not granted. Requesting permission");

        final String[] permissions = new String[]{Manifest.permission.CAMERA};

        if (!ActivityCompat.shouldShowRequestPermissionRationale(this,
                Manifest.permission.CAMERA)) {
            ActivityCompat.requestPermissions(this, permissions, RC_HANDLE_CAMERA_PERM);
            return;
        }

        final Activity thisActivity = this;

        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ActivityCompat.requestPermissions(thisActivity, permissions,
                        RC_HANDLE_CAMERA_PERM);
            }
        };

        Snackbar.make(mGraphicOverlay, R.string.permission_camera_rationale,
                Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.ok, listener)
                .show();
    }

    /**
     * Creates and starts the camera.  Note that this uses a higher resolution in comparison
     * to other detection examples to enable the barcode detector to detect small barcodes
     * at long distances.
     */
    private void createCameraSource() {

        Context context = getApplicationContext();
        FaceDetector detector = new FaceDetector.Builder(context)
                .setClassificationType(FaceDetector.ALL_CLASSIFICATIONS)
                .build();

        detector.setProcessor(
                new MultiProcessor.Builder<>(new GraphicFaceTrackerFactory())
                        .build());

        if (!detector.isOperational()) {
            // Note: The first time that an app using face API is installed on a device, GMS will
            // download a native library to the device in order to do detection.  Usually this
            // completes before the app is run for the first time.  But if that download has not yet
            // completed, then the above call will not detect any faces.
            //
            // isOperational() can be used to check if the required native library is currently
            // available.  The detector will automatically become operational once the library
            // download completes on device.
            Log.w(TAG, "Face detector dependencies are not yet available.");
            Toast.makeText(this, "Face detector dependencies are not yet available.", Toast.LENGTH_SHORT).show();

            // Check for low storage.  If there is low storage, the native library will not be
            // downloaded, so detection will not become operational.
            IntentFilter lowstorageFilter = new IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW);
            boolean hasLowStorage = registerReceiver(null, lowstorageFilter) != null;

            if (hasLowStorage) {
                Toast.makeText(this, R.string.low_storage_error, Toast.LENGTH_LONG).show();
                Log.w(TAG, getString(R.string.low_storage_error));
            }
        }

        int facing = CameraSource.CAMERA_FACING_FRONT;
        if (!mIsFrontFacing) {
            facing = CameraSource.CAMERA_FACING_BACK;
        }

        mCameraSource = new CameraSource.Builder(context, detector)
                .setRequestedPreviewSize(640, 480)
                .setFacing(facing)
                .setRequestedFps(30.0f)
                .setAutoFocusEnabled(true)
                .build();
    }

    /**
     * Restarts the camera.
     */
    @Override
    protected void onResume() {
        super.onResume();
        gvrAudioEngine.resume();
        startCameraSource();
    }

    /**
     * Stops the camera.
     */
    @Override
    protected void onPause() {
        super.onPause();
        gvrAudioEngine.pause();
        mPreview.stop();
    }

    /**
     * Releases the resources associated with the camera source, the associated detector, and the
     * rest of the processing pipeline.
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mCameraSource != null) {
            mCameraSource.release();
        }
    }

    /**
     * Callback for the result from requesting permissions. This method
     * is invoked for every call on {@link #requestPermissions(String[], int)}.
     * <p>
     * <strong>Note:</strong> It is possible that the permissions request interaction
     * with the user is interrupted. In this case you will receive empty permissions
     * and results arrays which should be treated as a cancellation.
     * </p>
     *
     * @param requestCode  The request code passed in {@link #requestPermissions(String[], int)}.
     * @param permissions  The requested permissions. Never null.
     * @param grantResults The grant results for the corresponding permissions
     *                     which is either {@link PackageManager#PERMISSION_GRANTED}
     *                     or {@link PackageManager#PERMISSION_DENIED}. Never null.
     * @see #requestPermissions(String[], int)
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != RC_HANDLE_CAMERA_PERM) {
            Log.d(TAG, "Got unexpected permission result: " + requestCode);
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            return;
        }

        if (grantResults.length != 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Camera permission granted - initialize the camera source");
            // we have permission, so create the camerasource
            createCameraSource();
            return;
        }

        Log.e(TAG, "Permission not granted: results len = " + grantResults.length +
                " Result code = " + (grantResults.length > 0 ? grantResults[0] : "(empty)"));

        DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                finish();
            }
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Face Tracker sample")
                .setMessage(R.string.no_camera_permission)
                .setPositiveButton(R.string.ok, listener)
                .show();
    }

    //==============================================================================================
    // UI
    //==============================================================================================

    /**
     * Saves the camera facing mode, so that it can be restored after the device is rotated.
     */
    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        super.onSaveInstanceState(savedInstanceState);
        savedInstanceState.putBoolean("IsFrontFacing", mIsFrontFacing);
    }

    /**
     * Toggles between front-facing and rear-facing modes.
     */
    private View.OnClickListener mFlipButtonListener = new View.OnClickListener() {
        public void onClick(View v) {
            mIsFrontFacing = !mIsFrontFacing;

            if (mCameraSource != null) {
                mCameraSource.release();
                mCameraSource = null;
            }

            createCameraSource();
            startCameraSource();
        }
    };

    //==============================================================================================
    // Camera Source Preview
    //==============================================================================================

    /**
     * Starts or restarts the camera source, if it exists.  If the camera source doesn't exist yet
     * (e.g., because onResume was called before the camera source was created), this will be called
     * again when the camera source is created.
     */
    private void startCameraSource() {

        // check that the device has play services available.
        int code = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(
                getApplicationContext());
        if (code != ConnectionResult.SUCCESS) {
            Dialog dlg =
                    GoogleApiAvailability.getInstance().getErrorDialog(this, code, RC_HANDLE_GMS);
            dlg.show();
        }

        if (mCameraSource != null) {
            try {
                mPreview.start(mCameraSource, mGraphicOverlay);
            } catch (IOException e) {
                Log.e(TAG, "Unable to start camera source.", e);
                mCameraSource.release();
                mCameraSource = null;
            }
        }
    }

    //==============================================================================================
    // Graphic Face Tracker
    //==============================================================================================

    /**
     * Factory for creating a face tracker to be associated with a new face.  The multiprocessor
     * uses this factory to create face trackers as needed -- one for each individual.
     */
    private class GraphicFaceTrackerFactory implements MultiProcessor.Factory<Face> {
        @Override
        public Tracker<Face> create(Face face) {
            return new GraphicFaceTracker(mGraphicOverlay);
        }
    }

    /**
     * Face tracker for each detected individual. This maintains a face graphic within the app's
     * associated face overlay.
     */
    private class GraphicFaceTracker extends Tracker<Face> {
        private GraphicOverlay mOverlay;
        private FaceGraphic mFaceGraphic;

        GraphicFaceTracker(GraphicOverlay overlay) {
            mOverlay = overlay;
            mFaceGraphic = new FaceGraphic(overlay);
        }

        /**
         * Start tracking the detected face instance within the face overlay.
         */
        @Override
        public void onNewItem(int faceId, Face face) {
            vibrator.vibrate(15);
            mFaceGraphic.setId(faceId);

            // Play sound reflecting face position
            if(gvrAudioEngine != null) {
                sourceId = gvrAudioEngine.createSoundObject(OBJECT_SOUND_FILE);
                if (sourceId == GvrAudioEngine.INVALID_ID) {
                    Log.e(TAG, "Failed to create sound object !");
                } else {
                    Log.v(TAG, "Sound file preloaded and object created !");
                    mFaceGraphic.setSourceId(sourceId);
                }
                gvrAudioEngine.setSoundObjectDistanceRolloffModel(mFaceGraphic.getSourceId(), GvrAudioEngine.DistanceRolloffModel.LINEAR, 50.0f, faceWidthMax);
                gvrAudioEngine.setHeadPosition(0.0f, 0.0f, 0.0f);
                gvrAudioEngine.setHeadRotation(0.0f, 0.0f, 0.0f, 0.0f);

                Log.v(TAG, "New face detected: " + faceId + " @ x:" + face.getPosition().x + " y:" + face.getPosition().y + " W:" + face.getWidth());
                Log.v(TAG, "...... x:" + (face.getPosition().x - (facePosMinX + facePosMoyX)) + " y:" + (face.getPosition().y - (facePosMinY + facePosMoyY)) + " W:" + (face.getWidth() - faceWidthMax));
                gvrAudioEngine.setSoundObjectPosition(mFaceGraphic.getSourceId(), (face.getPosition().x - (facePosMinX + facePosMoyX)), (face.getWidth() - faceWidthMax), (face.getPosition().y - (facePosMinY + facePosMoyY)));
                gvrAudioEngine.playSound(mFaceGraphic.getSourceId(), false /* no looped playback */);
                Log.v(TAG, "... start playing sound");
                } else {
                    Log.e(TAG, "onUpdate with sourceId invalid !");
            }
        }

        /**
         * Update the position/characteristics of the face within the overlay.
         */
        @Override
        public void onUpdate(FaceDetector.Detections<Face> detectionResults, Face face) {
            mOverlay.add(mFaceGraphic);
            mFaceGraphic.updateFace(face);

            // Play sound reflecting face position
            if(gvrAudioEngine != null) {
                if((System.currentTimeMillis() - lastSound) > 250) {
                    lastSound = System.currentTimeMillis();
                    Log.v(TAG, "... update face position: " + face.getId() + " @ x:" + face.getPosition().x + " y:" + face.getPosition().y + " W:" + face.getWidth());
                    Log.v(TAG, "...... x:" + (face.getPosition().x - (facePosMinX + facePosMoyX)) + " y:" + (face.getPosition().y - (facePosMinY + facePosMoyY)) + " W:" + (face.getWidth() - faceWidthMax));
                    if (mFaceGraphic.getSourceId() != GvrAudioEngine.INVALID_ID) {

                        sourceId = gvrAudioEngine.createSoundObject(OBJECT_SOUND_FILE);
                        if (sourceId == GvrAudioEngine.INVALID_ID) {
                            Log.e(TAG, "Failed to create sound object !");
                        } else {
                            Log.v(TAG, "Sound file preloaded and object created !");
                            mFaceGraphic.setSourceId(sourceId);
                        }

                        gvrAudioEngine.setSoundObjectDistanceRolloffModel(mFaceGraphic.getSourceId(), GvrAudioEngine.DistanceRolloffModel.LINEAR, 50.0f, faceWidthMax);
                        gvrAudioEngine.setSoundObjectPosition(mFaceGraphic.getSourceId(), (face.getPosition().x - (facePosMinX + facePosMoyX)), (face.getWidth() - faceWidthMax), (face.getPosition().y - (facePosMinY + facePosMoyY)));
                        gvrAudioEngine.playSound(mFaceGraphic.getSourceId(), false /* no looped playback */);
                        Log.v(TAG, "... start playing sound");
                    } else {
                        Log.e(TAG, "onUpdate with sourceId invalid !");
                    }
                }

                // Regular update call to GVR audio engine.
                gvrAudioEngine.update();
            }
        }

        /**
         * Hide the graphic when the corresponding face was not detected.  This can happen for
         * intermediate frames temporarily (e.g., if the face was momentarily blocked from
         * view).
         */
        @Override
        public void onMissing(FaceDetector.Detections<Face> detectionResults) {
            if(gvrAudioEngine != null) {
                if (mFaceGraphic.getSourceId() != GvrAudioEngine.INVALID_ID) {
                    if (gvrAudioEngine.isSoundPlaying(mFaceGraphic.getSourceId())) {
                        Log.v(TAG, "... need to stop playing sound - onMissing");
                        gvrAudioEngine.stopSound(mFaceGraphic.getSourceId());
                    }
                }
            }

            mOverlay.remove(mFaceGraphic);
        }

        /**
         * Called when the face is assumed to be gone for good. Remove the graphic annotation from
         * the overlay.
         */
        @Override
        public void onDone() {
            if(gvrAudioEngine != null) {
                if (mFaceGraphic.getSourceId() != GvrAudioEngine.INVALID_ID) {
                    if (gvrAudioEngine.isSoundPlaying(mFaceGraphic.getSourceId())) {
                        Log.v(TAG, "... need to stop playing sound - onDone");
                        gvrAudioEngine.stopSound(mFaceGraphic.getSourceId());
                    }
                }
            }
            mOverlay.remove(mFaceGraphic);
        }
    }
}
