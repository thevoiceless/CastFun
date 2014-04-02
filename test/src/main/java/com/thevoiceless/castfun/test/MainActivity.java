package com.thevoiceless.castfun.test;

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

import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;


public class MainActivity extends ActionBarActivity {

    private MediaRouter mediaRouter;
    private MediaRouteSelector mediaRouteSelector;
    private MediaRouter.Callback mediaCallback;

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
     * When the user selects a device from the Cast button device list, the application is informed
     * of the selected device by extending MediaRouter.Callback
     */
    private class MyMediaRouterCallback extends MediaRouter.Callback {

        @Override
        public void onRouteSelected(MediaRouter router, MediaRouter.RouteInfo info) {
            Log.i("cast", "Route selected");

            super.onRouteSelected(router, info);
        }

        @Override
        public void onRouteUnselected(MediaRouter router, MediaRouter.RouteInfo info) {
            Log.i("cast", "Route unselected");

            super.onRouteUnselected(router, info);
        }
    }
}
