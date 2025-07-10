package com.chetbox.mousecursor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class MouseAccessibilityService extends Service implements Choreographer.FrameCallback{

    private static final String TAG = MouseAccessibilityService.class.getName();

    private View cursorView;
    private View accurateView;
    private LayoutParams cursorLayout;
    private LayoutParams accurateLayout;
    private WindowManager windowManager;
    private NotificationManager notificationManager;
    private LocalServerSocket serverSocket;
    private final AtomicInteger cursorX = new AtomicInteger(0);
    private final AtomicInteger cursorY = new AtomicInteger(0);
    private final AtomicInteger cursorStopTime = new AtomicInteger(0);
    private final AtomicReference<LocalSocket> socket = new AtomicReference<>();
    private final AtomicInteger localAccurateMode = new AtomicInteger(0);
    private Choreographer choreographer;
    private final AtomicBoolean gameMap = new AtomicBoolean(true);
    private ReceiveThread receiveThread;

    @Override
    @RequiresApi(api = Build.VERSION_CODES.O)
    public void onCreate() {
//        Log.d(TAG, "onCreate()");
        super.onCreate();
        // 初始化 Choreographer
        choreographer = Choreographer.getInstance();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        initMouseCursor();
        initAccurate();

//        int LAYOUT_FLAG;
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//            LAYOUT_FLAG = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
//        } else {
//            LAYOUT_FLAG = WindowManager.LayoutParams.TYPE_PHONE;
//        }
        receiveThread = new ReceiveThread();
    }

    @Override
    public void doFrame(long frameTimeNanos) {
//        Log.d(TAG, "同步");
        // 1. 更新视图位置（仅在 VSYNC 信号到来时执行）

        try {
            if (localAccurateMode.get() != (gameMap.get()?1:0)) {
                if (gameMap.get()) {
//                    accurateView.setVisibility(View.VISIBLE);
                    cursorView.setVisibility(View.INVISIBLE);
                } else {
                    accurateView.setVisibility(View.INVISIBLE);
                    cursorView.setVisibility(View.VISIBLE);
                }
                localAccurateMode.set(gameMap.get()?1:0);
                windowManager.updateViewLayout(accurateView, accurateLayout);
            }
            windowManager.updateViewLayout(cursorView, cursorLayout);
        } catch (NumberFormatException e) {
            Log.e(TAG, "数据转换异常",e);
        }
        // 2. 注册下一次回调，实现持续同步
        choreographer.postFrameCallback(this);
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
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        choreographer.removeFrameCallback(this); // 移除 VSYNC 回调

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

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private class ServerThread implements Runnable {
        //定义当前线程所处理的Socket
        // private Socket socket = null;
        // 该线程所处理的Socket对应的输入流
        private final BufferedReader reader;

        private final  OutputStream senderOutput;

        public ServerThread(LocalSocket socket) throws IOException {
            Log.i(TAG, "连接成功建立！");
            InputStream input = socket.getInputStream();
            reader = new BufferedReader(new InputStreamReader(input));
            this.senderOutput = socket.getOutputStream();
        }

        @Override
        public void run() {
            try {
                String receivedData;
                // 循环读取数据
                while ((receivedData = reader.readLine()) != null) {
                    try {
                        JSONObject jsonObject = new JSONObject(receivedData);
                        cursorLayout.x = jsonObject.getInt("x");
                        cursorLayout.y = jsonObject.getInt("y");
                        gameMap.set(jsonObject.getBoolean("gameMap"));
                    }catch (JSONException e) {
                        Log.e(TAG, "JSON解析异常", e);
                        Log.e(TAG, "JSON数据:"+receivedData);
                    }
                    catch (Exception e) {
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
            } catch (IOException e) {
                Log.e(TAG, "ServerSocket创建异常" + e.getMessage());
                return;
            }
            ScheduledExecutorService socketSchedule = Executors.newSingleThreadScheduledExecutor();
            Runnable task = () -> {
                try {
                    new Handler(getMainLooper()).post(() -> {
                        boolean update = false;
                        if (localAccurateMode.get() == 0 && cursorX.get() == cursorLayout.x && cursorY.get() == cursorLayout.y) {
                            cursorStopTime.incrementAndGet();
                        } else {
                            cursorStopTime.set(0);
                            cursorX.set(cursorLayout.x);
                            cursorY.set(cursorLayout.y);
                        }
                        if (localAccurateMode.get() != -1 && cursorStopTime.get() >= 5) {
                            if (accurateView.getVisibility() == View.VISIBLE) {
                                accurateView.setVisibility(View.INVISIBLE);
                                update = true;
                            }
                            if (cursorView.getVisibility() == View.VISIBLE) {
                                cursorView.setVisibility(View.INVISIBLE);
                                update = true;
                            }
                            if (update) {
                                windowManager.updateViewLayout(accurateView, accurateLayout);
                                windowManager.updateViewLayout(cursorView, cursorLayout);
                                localAccurateMode.set(-1);
                            }
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "socket schedule 异常" + Arrays.toString(e.getStackTrace()));
                }
            };
            socketSchedule.scheduleWithFixedDelay(task, 0,1, TimeUnit.SECONDS);

            while (true) {
                try {
                    Log.d(TAG, "等待连接");
                    LocalSocket localSocket = serverSocket.accept();
                    new Thread(new ServerThread(localSocket)).start();
                    socket.set(localSocket);
                } catch (Exception e) {
                    Log.e(TAG, "Socket连接异常：" + e.getMessage());
                }
            }
        }
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
        // 注册 VSYNC 回调
        receiveThread.start();
        choreographer.postFrameCallback(this);
        return START_STICKY;
    }

}
