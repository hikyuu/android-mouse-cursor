package com.chetbox.mousecursor;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.Handler;
import android.support.annotation.RequiresApi;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.accessibility.AccessibilityEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicInteger;

public class MouseAccessibilityService extends AccessibilityService {

    private static final String TAG = MouseAccessibilityService.class.getName();

    private View cursorView;
    private View accurateView;
    private LayoutParams cursorLayout;
    private LayoutParams accurateLayout;
    private WindowManager windowManager;
    private NotificationManager notificationManager;
    private LocalServerSocket serverSocket;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        Log.d(TAG, "onAccessibilityEvent");
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.d(TAG, event.getPackageName().toString());
        }
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "onInterrupt");
    }

    @Override
    @RequiresApi(api = Build.VERSION_CODES.O)
    public void onCreate() {
        Log.d(TAG, "onCreate()");
        super.onCreate();

        initMouseCursor();
        initAccurate();

//        int LAYOUT_FLAG;
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//            LAYOUT_FLAG = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
//        } else {
//            LAYOUT_FLAG = WindowManager.LayoutParams.TYPE_PHONE;
//        }
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        ReceiveThread receiveThread = new ReceiveThread();
        receiveThread.start();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void initAccurate() {
        accurateView = View.inflate(getBaseContext(), R.layout.accurate, null);
        accurateView.setVisibility(View.INVISIBLE);
        accurateLayout = new LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                LayoutParams.TYPE_APPLICATION_OVERLAY,
                LayoutParams.FLAG_DISMISS_KEYGUARD |
                        LayoutParams.FLAG_NOT_FOCUSABLE |
                        LayoutParams.FLAG_NOT_TOUCHABLE |
                        LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        accurateLayout.gravity = Gravity.CENTER;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void initMouseCursor() {
        cursorView = View.inflate(getBaseContext(), R.layout.cursor, null);

        cursorLayout = new LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                LayoutParams.TYPE_APPLICATION_OVERLAY,
                LayoutParams.FLAG_DISMISS_KEYGUARD |
                        LayoutParams.FLAG_NOT_FOCUSABLE |
                        LayoutParams.FLAG_NOT_TOUCHABLE |
                        LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        cursorLayout.gravity = Gravity.TOP | Gravity.START;
        cursorLayout.x = 0;
        cursorLayout.y = 0;
//        cursorLayout.height = 24;
//        cursorLayout.width = 24;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (windowManager != null) {
            if (cursorView != null) {
            windowManager.removeView(cursorView);
            }
            if (accurateView != null) {
                windowManager.removeView(accurateView);
            }
        }
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "socket关闭异常");
            }
        }
    }

    private class ServerThread implements Runnable {
        //定义当前线程所处理的Socket
        // private Socket socket = null;
        // 该线程所处理的Socket对应的输入流
        private final BufferedReader br;

        public ServerThread(LocalSocket socket) throws IOException {
            InputStream inputStream = socket.getInputStream();
            br = new BufferedReader(new InputStreamReader(inputStream));
        }

         AtomicInteger showMouse = new AtomicInteger(0);
        @Override
        public void run() {
            try {
                String line = "";
                while ((line = br.readLine()) != null) {
                    try {
//                        Log.d(TAG, "接收数据：" + line);
                        String[] pos = line.split(",");
                        if (pos.length < 3) continue;
                        int posX = Integer.parseInt(pos[0]);
                        int posY = Integer.parseInt(pos[1]);
                        int whetherToShow = Integer.parseInt(pos[2]);
                        cursorLayout.x = posX;
                        cursorLayout.y = posY;
                        new Handler(getMainLooper()).post(() -> {
                            try {

                                if (showMouse.get() != whetherToShow) {
//                                    Log.d(TAG, "showMouse:"+showMouse + "  whetherToShow:" + whetherToShow);
                                    if (whetherToShow > 0) {
                                        accurateView.setVisibility(View.VISIBLE);
                                        cursorView.setVisibility(View.INVISIBLE);
                                    } else {
                                        accurateView.setVisibility(View.INVISIBLE);
                                        cursorView.setVisibility(View.VISIBLE);
                                    }
                                    showMouse.set(whetherToShow);
                                    windowManager.updateViewLayout(accurateView, accurateLayout);
                                }
                                windowManager.updateViewLayout(cursorView, cursorLayout);
                            } catch (NumberFormatException e) {
                                Log.e(TAG, "数据转换异常");
                            } catch (Exception e) {
                                Log.e(TAG, "更新鼠标异常", e);
                            }
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "未知异常", e);
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "IO异常", e);
            }
        }
    }

    private class ReceiveThread extends Thread {
        @Override
        public void run() {
            try {
                serverSocket = new LocalServerSocket("mouse-cursor");
                while (true) {
                    LocalSocket socket = serverSocket.accept();
                    new Thread(new ServerThread(socket)).start();
                }
            } catch (Exception e) {
                Log.e(TAG, "Socket创建异常" + e.getMessage());
            }
        }
    }

    @Override
    public void onServiceConnected() {
        Log.d(TAG, "onServiceConnected()");
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        Log.d(TAG, "onStartCommand()");
        if (cursorView.getWindowId() == null) {
            windowManager.addView(cursorView, cursorLayout);
            windowManager.addView(accurateView, accurateLayout);
        }
        String NOTIFICATION_CHANNEL_ID = "com.chetbox.mousecursor";
        String channelName = "鼠标指针服务";
        NotificationChannel chan = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            chan = new NotificationChannel(NOTIFICATION_CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_NONE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            chan.setLightColor(Color.BLUE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            chan.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        }
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        assert manager != null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(chan);
        }
        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID);
        Notification notification = notificationBuilder.setOngoing(true)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("鼠标指针后台服务")
                .setPriority(NotificationManager.IMPORTANCE_MIN)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
        startForeground(110, notification);
        return START_STICKY;
    }
}
