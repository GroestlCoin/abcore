package com.greenaddress.abcore;


import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.Map;


public class ABCoreService extends Service {

    private final static String TAG = ABCoreService.class.getName();
    final static int NOTIFICATION_ID = 922430164;
    private Process mProcess;

    @Override
    public IBinder onBind(final Intent intent) {
        return null;
    }

    private void setupNotification() {
        final Intent myIntent = new Intent(this, MainActivity.class);
        final PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                myIntent, PendingIntent.FLAG_ONE_SHOT);
        final NotificationManager nMN = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        final Notification n = new Notification.Builder(this)
                .setContentTitle("Abcore is running")
                .setContentIntent(pendingIntent)
                .setContentText("Currently started")
                .setSmallIcon(R.drawable.ic_info_black_24dp)
                .setOngoing(true)
                .build();

        nMN.notify(NOTIFICATION_ID, n);
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {

        final String arch = Utils.getArch();
        final File dir = Utils.getDir(this);
        Log.i(TAG, "Core service msg");

        // start core
        try {
            final String aarch = arch.equals("arm64") ? "aarch64" : arch.equals("amd64") ? "x86_64" : arch;
            final String gnu;

            if (arch.equals("armhf")) {
                gnu = "gnueabihf";
            } else {
                gnu = "gnu";
            }

            final String ld_linux;

            // on arch linux it is usr/lib/ld-linux-x86-64.so.2
            final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            final Boolean archEnabled = prefs.getBoolean("archisenabled", false);

            if (archEnabled) {
                ld_linux = String.format("%s/usr/lib/ld-2.23.so", dir.getAbsoluteFile());
            } else if ("amd64".equals(arch) || "arm64".equals(arch)) {
                ld_linux = String.format("%s/lib/%s-linux-gnu/ld-2.22.so", dir.getAbsolutePath(), aarch);
            } else if ("armhf".equals(arch)) {
                ld_linux = String.format("%s/lib/ld-linux-armhf.so.3", dir.getAbsolutePath());
            } else {
                ld_linux = String.format("%s/lib/ld-linux.so.2", dir.getAbsoluteFile());
            }

            // allow to pass in a different datadir directory

            // HACK: if user sets a datadir in the bitcoin.conf file that should then be the one
            // used
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            final ProcessBuilder pb = new ProcessBuilder(ld_linux,
                    String.format("%s/usr/bin/groestlcoind", dir.getAbsolutePath()),
                    "-server=1",
                    String.format("-datadir=%s", Utils.getDataDir(this)),
                    String.format("-conf=%s", Utils.getBitcoinConf(this)));

            final Map<String, String> env = pb.environment();

            // unset LD_PRELOAD for devices such as Samsung S6 (LD_PRELOAD errors on libsigchain.so starting core although works ..)

            env.put("LD_PRELOAD", "");

            env.put("LD_LIBRARY_PATH",
                    String.format("%s:%s:%s:%s:%s:%s",
                            String.format("%s/lib", dir.getAbsolutePath()),
                            String.format("%s/usr/lib", dir.getAbsolutePath()),
                            String.format("%s/lib/%s-linux-%s", dir.getAbsolutePath(), aarch, gnu),
                            String.format("%s/lib/arm-linux-gnueabihf", dir.getAbsolutePath()),
                            String.format("%s/usr/lib/%s-linux-%s", dir.getAbsolutePath(), aarch, gnu),
                            String.format("%s/usr/lib/arm-linux-gnueabihf", dir.getAbsolutePath())
                            //String.format("%s/usr/lib/arm-linux-gnueabihf/openssl-1.0.2/engines", dir.getAbsolutePath())
                    ));

            pb.directory(new File(Utils.getDataDir(this)));

            mProcess = pb.start();
            final ProcessLogger.OnError er = new ProcessLogger.OnError() {
                @Override
                public void OnError(final String[] error) {
                    ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID);
                    final StringBuilder bf = new StringBuilder();
                    for (final String e : error) {
                        if (e != null && !e.isEmpty()) {
                            bf.append(String.format("%s%s", e, System.getProperty("line.separator")));
                        }
                    }
                    final Intent broadcastIntent = new Intent();
                    broadcastIntent.setAction(MainActivity.DownloadInstallCoreResponseReceiver.ACTION_RESP);
                    broadcastIntent.addCategory(Intent.CATEGORY_DEFAULT);
                    broadcastIntent.putExtra("abtcore", "exception");
                    broadcastIntent.putExtra("exception", bf.toString());

                    sendBroadcast(broadcastIntent);
                }
            };
            final ProcessLogger errorGobbler = new ProcessLogger(mProcess.getErrorStream(), er);
            final ProcessLogger outputGobbler = new ProcessLogger(mProcess.getInputStream(), er);

            errorGobbler.start();
            outputGobbler.start();

            setupNotification();

        } catch (final IOException e) {
            Log.i(TAG, "Native exception!");
            Log.i(TAG, e.getMessage());

            Log.i(TAG, e.getLocalizedMessage());

            e.printStackTrace();
        }
        Log.i(TAG, "background Task finished");


        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mProcess != null) {
            mProcess.destroy();
        }
    }
}