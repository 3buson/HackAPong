package com.dragonhack.matic.hackapong;

//import com.dragonhack.matic.hackapong.PingPongActivity.R;
import com.dragonhack.matic.hackapong.PingPongActivity.*;
import com.thalmic.myo.AbstractDeviceListener;
import com.thalmic.myo.DeviceListener;
import com.thalmic.myo.Hub;
import com.thalmic.myo.Myo;
import com.thalmic.myo.Pose;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.content.Context;
import android.media.AudioManager;
import android.os.Message;
import android.view.MenuInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import java.util.ArrayList;

public class PingPongActivity extends Activity {
    /*** MYO STUFF ***/
    private static final String TAG = "MultipleMyosActivity";

    // We store each Myo object that we attach to in this list, so that we can keep track of the order we've seen
    // each Myo and give it a unique short identifier (see onAttach() and identifyMyo() below).
    private ArrayList<Myo> mKnownMyos = new ArrayList<Myo>();

    private MyoAdapter mAdapter;

    private int identifyMyo(Myo myo) {
        return mKnownMyos.indexOf(myo) + 1;
    }

    private class MyoAdapter extends ArrayAdapter<String> {

        public MyoAdapter(Context context, int count) {
            super(context, android.R.layout.simple_list_item_1);

            // Initialize adapter with items for each expected Myo.
            for (int i = 0; i < count; i++) {
                add(getString(R.string.waiting_message));
            }
        }

        public void setMessage(Myo myo, String message) {
            // identifyMyo returns IDs starting at 1, but the adapter indices start at 0.
            int index = identifyMyo(myo) - 1;

            // Replace the message.
            remove(getItem(index));
            insert(message, index);
        }
    }

    private DeviceListener mListener = new AbstractDeviceListener() {

        // Every time the SDK successfully attaches to a Myo armband, this function will be called.
        //
        // You can rely on the following rules:
        //  - onAttach() will only be called once for each Myo device
        //  - no other events will occur involving a given Myo device before onAttach() is called with it
        //
        // If you need to do some kind of per-Myo preparation before handling events, you can safely do it in onAttach().
        @Override
        public void onAttach(Myo myo, long timestamp) {

            // The object for a Myo is unique - in other words, it's safe to compare two Myo references to
            // see if they're referring to the same Myo.

            // Add the Myo object to our list of known Myo devices. This list is used to implement identifyMyo() below so
            // that we can give each Myo a nice short identifier.
            mKnownMyos.add(myo);

            if (mKnownMyos.size() > 1) {
                // both myos connected
                // hide myo connection screen, start pong
                Log.i(TAG, "Both myos connected, starting pong game!");

                mMyoView.setVisibility(View.INVISIBLE);
                mPongView.setVisibility(View.VISIBLE);
            }

            // Now that we've added it to our list, get our short ID for it and print it out.
            Log.i(TAG, "Attached to " + myo.getMacAddress() + ", now known as Myo " + identifyMyo(myo) + ".");
        }

        @Override
        public void onConnect(Myo myo, long timestamp) {
            mAdapter.setMessage(myo, "Myo " + identifyMyo(myo) + " has connected.");
        }

        @Override
        public void onDisconnect(Myo myo, long timestamp) {
            mAdapter.setMessage(myo, "Myo " + identifyMyo(myo) + " has disconnected.");
        }

        @Override
        public void onPose(Myo myo, long timestamp, Pose pose) {
            System.out.println("onPose: Myo " + identifyMyo(myo) + " onPose (" + pose.toString() + ") fired");

            mAdapter.setMessage(myo, "Myo " + identifyMyo(myo) + " switched to pose " + pose.toString() + ".");

            if (mKnownMyos.get(0) == myo) {
                // LEFT
                if (pose.toString().equals(pose.WAVE_IN.toString())) {
                    mPongView.setMyoRedLeftState();
                }
                // RIGHT
                else if (pose.toString().equals(pose.WAVE_OUT.toString())) {
                    mPongView.setMyoRedRightState();
                }
                // OTHER
                else {
                    mPongView.setMyoRedRestState();
                }
            } else if (mKnownMyos.get(1) == myo) {
                // LEFT
                if (pose.toString().equals(pose.WAVE_IN.toString())) {
                    mPongView.setMyoBlueLeftState();
                }
                // RIGHT
                else if (pose.toString().equals(pose.WAVE_OUT.toString())) {
                    mPongView.setMyoBlueRightState();
                }
                // OTHER
                else {
                    mPongView.setMyoBlueRestState();
                }
            } else {
                System.err.print("UNKNOWN MYO !!!");
            }
        }
    };
    /*** END MYO STUFF ***/

    /*** PONG STUFF ***/
    /** Called when the activity is first created. */
    private PongView mPongView;
    private ListView mMyoView;
    private AlertDialog mAboutBox;
    private RefreshHandler mRefresher;
    protected PowerManager.WakeLock mWakeLock;

    class RefreshHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            PingPongActivity.this.hideAboutBox();
        }

        public void sleep(long delay) {
            this.removeMessages(0);
            this.sendMessageDelayed(obtainMessage(0), delay);
        }
    }
    /*** END PONG STUFF ***/

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /*** PONG STUFF ***/
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.pong_view);
        mPongView = (PongView) findViewById(R.id.pong);
        mPongView.update();
        mRefresher = new RefreshHandler();

        mPongView.setVisibility(View.INVISIBLE);
        /*** END PONG STUFF ***/

        /*** MYO STUFF ***/
        mMyoView = (ListView) findViewById(R.id.list);

        // First, we initialize the Hub singleton.
        Hub hub = Hub.getInstance();
        if (!hub.init(this)) {
            // We can't do anything with the Myo device if the Hub can't be initialized, so exit.
            Toast.makeText(this, "Couldn't initialize Hub", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Disable standard Myo locking policy. All poses will be delivered.
        hub.setLockingPolicy(Hub.LockingPolicy.NONE);

        final int attachingCount = 2;

        // Set the maximum number of simultaneously attached Myos to 2.
        hub.setMyoAttachAllowance(attachingCount);

        Log.i(TAG, "Attaching to " + attachingCount + " Myo armbands.");

        // attachToAdjacentMyos() attaches to Myo devices that are physically very near to the Bluetooth radio
        // until it has attached to the provided count.
        // DeviceListeners attached to the hub will receive onAttach() events once attaching has completed.
        hub.attachToAdjacentMyos(attachingCount);

        // Next, register for DeviceListener callbacks.
        hub.addListener(mListener);

        // Attach an adapter to the ListView for showing the state of each Myo.
        mAdapter = new MyoAdapter(this, attachingCount);
        ListView listView = (ListView) findViewById(R.id.list);
        listView.setAdapter(mAdapter);
        /*** END MYO STUFF ***/

        /*** PONG STUFF ***/
        this.setVolumeControlStream(AudioManager.STREAM_MUSIC);

        final PowerManager pm = (PowerManager) this.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "Pong");
        mWakeLock.acquire();
        /*** END PONG STUFF ***/
    }

    protected void onStop() {
        super.onStop();

        if (mPongView != null) {
            mPongView.stop();
        }
    }

    protected void onResume() {
        super.onResume();

        if (mPongView != null) {
            mPongView.resume();
        }
    }

    protected void onDestroy() {
        super.onDestroy();

        /*** MYO STUFF ***/
        // We don't want any callbacks when the Activity is gone, so unregister the listener.
        Hub.getInstance().removeListener(mListener);
        // Shutdown the Hub. This will disconnect any Myo devices that are connected.
        Hub.getInstance().shutdown();
        /*** END MYO STUFF ***/

        /*** PONG STUFF ***/
        if (mPongView != null) {
            mPongView.releaseResources();
            mWakeLock.release();
        }
        /*** END PONG STUFF ***/
    }

    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        boolean result = super.onCreateOptionsMenu(menu);

        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.game_menu, menu);

        return result;
    }

    public boolean onOptionsItemSelected(MenuItem item) {
        if (mPongView != null) {
            super.onOptionsItemSelected(item);
            int id = item.getItemId();
            boolean flag = false;

            switch (id) {
                case R.id.menu_0p:
                    flag = true;
                    mPongView.setPlayerControl(false, false);
                    break;
                case R.id.menu_1p:
                    flag = true;
                    mPongView.setPlayerControl(false, true);
                    break;
                case R.id.menu_2p:
                    flag = true;
                    mPongView.setPlayerControl(true, true);
                    break;
                case R.id.menu_about:
                    mAboutBox = new AlertDialog.Builder(this).setIcon(android.R.drawable.ic_dialog_info)
                            .setTitle(R.string.about).setMessage(R.string.about_msg).show();
                    mPongView.pause();
                    mRefresher.sleep(5000);
                    break;
                case R.id.quit:
                    this.finish();
                    return true;

                case R.id.menu_toggle_sound:
                    mPongView.toggleMuted();
                    break;
            }

            if (flag) {
                mPongView.setShowTitle(false);
                mPongView.newGame();
            }

            return true;
        } else {
            return true;
        }
    }

    public void hideAboutBox() {
        if(mAboutBox != null) {
            mAboutBox.hide();
            mAboutBox = null;
        }
    }

    public static final String DB_PREFS = "Pong";
    public static final String PREF_MUTED = "pref_muted";
}