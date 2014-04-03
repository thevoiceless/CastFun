package com.thevoiceless.castfun.cast;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.app.MediaRouteActionProvider;
import android.support.v7.media.MediaRouteSelector;
import android.support.v7.media.MediaRouter;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;

import com.google.android.gms.cast.ApplicationMetadata;
import com.google.android.gms.cast.Cast;
import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.thevoiceless.castfun.test.R;

import java.io.IOException;


public class MainActivity extends ActionBarActivity {

    private static final String TAG = "cast";

    private MediaRouter mediaRouter;
    private MediaRouteSelector mediaRouteSelector;
    private MediaRouter.Callback mediaCallback;
    private Cast.Listener castListener;
    private GoogleApiClient apiClient;
    private CastDevice selectedDevice;
    private GoogleApiClient.ConnectionCallbacks connectionCallbacks;
    private GoogleApiClient.OnConnectionFailedListener connectionFailedListener;
    private MyCustomChannel customChannel;

    private String sessionId;
    private boolean waitingForReconnect;
    private boolean applicationStarted;

    private TextView message;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        message = ((TextView) findViewById(R.id.message));

        final int services = GooglePlayServicesUtil.isGooglePlayServicesAvailable(this);
        if (services == ConnectionResult.SUCCESS) {
            message.setText(R.string.play_services_available);
        } else {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.error)
                    .setMessage(R.string.play_services_unavailable)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int i) {
                            dialog.dismiss();
                            finish();
                        }
                    })
                    .create()
                    .show();
        }

        // Need an instance of the MediaRouter, hold onto it for the lifetime of the sender application
        mediaRouter = MediaRouter.getInstance(this);

        // Create a MediaRouteSelector to filter discovery of Cast devices that can launch the receiver application
        mediaRouteSelector = new MediaRouteSelector.Builder()
                .addControlCategory(CastMediaControlIntent.categoryForCast(getString(R.string.receiver_id)))
                .build();

        // Trigger discovery of devices by adding the MediaRouter.Callback to the MediaRouter instance
        // Typically this is assigned when the activity is active and removed when the activity goes
        // into the background (see onResume and onPause)
        mediaCallback = new MyMediaRouterCallback();

        // Need to declare ConnectionCallbacks and OnConnectionFailedListener callbacks to be informed of
        // the connection status
        connectionCallbacks = new MyConnectionCallbacks();
        connectionFailedListener = new MyConnectionFailedListener();

        // Cast.Listener callbacks are used to inform the sender application about receiver application events
        castListener = new Cast.Listener() {
            @Override
            public void onApplicationStatusChanged() {
                if (apiClient != null) {
                    Log.i(TAG, String.format("onApplicationStatusChanged: %s", Cast.CastApi.getApplicationStatus(apiClient)));
                }
            }

            @Override
            public void onApplicationDisconnected(int statusCode) {
                Log.i(TAG, String.format("onApplicationDisconnected: %d", statusCode));
            }

            @Override
            public void onVolumeChanged() {
                if (apiClient != null) {
                    Log.i(TAG, String.format("onVolumeChanged: %s", Cast.CastApi.getVolume(apiClient)));
                }
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();

        mediaRouter.addCallback(mediaRouteSelector, mediaCallback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN);
    }

    @Override
    protected void onPause() {
        if (isFinishing()) {
            mediaRouter.removeCallback(mediaCallback);
        }

        super.onPause();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.main, menu);

        // The MediaRouteSelector is assigned to the MediaRouteActionProvider in the ActionBar menu
        MenuItem mediaRouteItem = menu.findItem(R.id.action_cast);
        // Must extend ActionBarActivity because of this next line
        MediaRouteActionProvider actionProvider = ((MediaRouteActionProvider) MenuItemCompat.getActionProvider(mediaRouteItem));
        actionProvider.setRouteSelector(mediaRouteSelector);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Once the custom channel is created, the sender can use that to send String messages to the receiver
     *
     * @param message  The message to send
     */
    private void sendMessage(String message) {
        if (apiClient != null && customChannel != null) {
            try {
                Cast.CastApi.sendMessage(apiClient, customChannel.getNameSpace(), message)
                        .setResultCallback(new ResultCallback<Status>() {
                            @Override
                            public void onResult(Status result) {
                                if (result.isSuccess()) {
                                    Log.i(TAG, "Message sent");
                                } else {
                                    Log.e(TAG, "Message failed");
                                }
                            }
                        });
            } catch (Exception e) {
                Log.e(TAG, "Error sending message", e);
            }
        }
    }

    // Tearing down the connection has to be done in a particular sequence
    private void teardown() {
        Log.i(TAG, "Teardown");
        if (apiClient != null) {
            if (applicationStarted) {
                if (apiClient.isConnected()) {
                    try {
                        Cast.CastApi.stopApplication(apiClient, sessionId);
                        if (customChannel != null) {
                            Cast.CastApi.removeMessageReceivedCallbacks(apiClient, customChannel.getNameSpace());
                            customChannel = null;
                        }
                    } catch (IOException e) {
                        Log.e(TAG, "Error removing custom channel", e);
                    }
                    apiClient.disconnect();
                    Log.i(TAG, "Disconnected");
                }
                applicationStarted = false;
            }
            apiClient = null;
        }
        selectedDevice = null;
        waitingForReconnect = false;
        sessionId = null;
    }


    /**
     * When the user selects a device from the Cast button device list, the application is informed of
     * the selected device by extending MediaRouter.Callback
     */
    private class MyMediaRouterCallback extends MediaRouter.Callback {

        @Override
        public void onRouteSelected(MediaRouter router, MediaRouter.RouteInfo info) {
            Log.i(TAG, "Device selected");

            selectedDevice = CastDevice.getFromBundle(info.getExtras());
            Cast.CastOptions.Builder optionsBuilder = Cast.CastOptions.builder(selectedDevice, castListener);

            // Cast APIs are invoked using GoogleApiClient, created using the GoogleApiClient.Builder and requires various callbacks
            apiClient = new GoogleApiClient.Builder(MainActivity.this)
                    .addApi(Cast.API, optionsBuilder.build())
                    .addConnectionCallbacks(connectionCallbacks)
                    .addOnConnectionFailedListener(connectionFailedListener)
                    .build();

            // Establish a connection
            apiClient.connect();
        }

        @Override
        public void onRouteUnselected(MediaRouter router, MediaRouter.RouteInfo info) {
            Log.i(TAG, "Device unselected");

            teardown();
            selectedDevice = null;

            super.onRouteUnselected(router, info);
        }
    }


    private class MyConnectionCallbacks implements GoogleApiClient.ConnectionCallbacks {

        // Once the connection is confirmed, the application can launch the application
        @Override
        public void onConnected(Bundle bundle) {
            if (waitingForReconnect) {
                // Recreate channels if returning from suspend
                waitingForReconnect = false;
                connectChannels();
            } else {
                // Initial connection
                try {
                    Cast.CastApi.launchApplication(apiClient, getString(R.string.receiver_id), false)
                            .setResultCallback(new ResultCallback<Cast.ApplicationConnectionResult>() {
                                @Override
                                public void onResult(Cast.ApplicationConnectionResult result) {
                                    Status status = result.getStatus();
                                    if (status.isSuccess()) {
                                        Log.i(TAG, "Connected successfully");

                                        ApplicationMetadata metadata = result.getApplicationMetadata();
                                        String appStatus = result.getApplicationStatus();
                                        boolean wasLaunched = result.getWasLaunched();

                                        applicationStarted = true;
                                        sessionId = result.getSessionId();

                                        // Once connected, the custom channel can be created using Cast.CastApi.setMessageReceivedCallbacks
                                        connectChannels();
                                    } else {
                                        teardown();
                                    }
                                }
                            });
                } catch (Exception e) {
                    Log.e(TAG, "Failed to launch application", e);
                }
            }
        }

        /**
         * The sender needs to track when it has become suspended
         *
         * @param cause  What caused the suspension
         */
        @Override
        public void onConnectionSuspended(int cause) {
            waitingForReconnect = true;
        }

        private void connectChannels() {
            customChannel = new MyCustomChannel();
            try {
                Cast.CastApi.setMessageReceivedCallbacks(apiClient, customChannel.getNameSpace(), customChannel);
            } catch (IOException e) {
                Log.e(TAG, "Failed to create custom channel", e);
            }
        }
    }


    private class MyConnectionFailedListener implements GoogleApiClient.OnConnectionFailedListener {

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
            teardown();
        }
    }


    /**
     * A custom channel needs to be created for the sender to communicate with the receiver, extends
     * the Cast.MessageReceivedCallbacks interface
     *
     * The sender can use the custom channel to send String messages to the receiver. Each custom channel
     * is defined by a unique namespace that must start with the prefix "urn:x-cast:". It is possible to
     * have multiple custom channels, each with a unique namespace.
     */
    private class MyCustomChannel implements Cast.MessageReceivedCallback {

        public String getNameSpace() {
            return getString(R.string.custom_namespace);
        }

        @Override
        public void onMessageReceived(CastDevice castDevice, String namespace, String message) {
            Log.i(TAG, String.format("onMessageReceived: %s", message));
        }
    }
}
