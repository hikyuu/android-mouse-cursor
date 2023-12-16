package com.chetbox.mousecursor;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.support.annotation.RequiresApi;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.sql.Time;
import java.util.Arrays;
import java.util.Date;
import java.util.TimeZone;
import java.util.Timer;

public class MouseAccessibilityService extends AccessibilityService {

    private static final String TAG = MouseAccessibilityService.class.getName();

    private View cursorView;
    private LayoutParams cursorLayout;
    private WindowManager windowManager;
    private NotificationManager notificationManager;
    private LocalServerSocket serverSocket;
    private ReceiveThread receiveThread;

    private static void logNodeHierachy(AccessibilityNodeInfo nodeInfo, int depth) {
        Rect bounds = new Rect();
        nodeInfo.getBoundsInScreen(bounds);

        StringBuilder sb = new StringBuilder();
        if (depth > 0) {
            for (int i = 0; i < depth; i++) {
                sb.append("  ");
            }
            sb.append("\u2514 ");
        }
        sb.append(nodeInfo.getClassName());
        sb.append(" (").append(nodeInfo.getChildCount()).append(")");
        sb.append(" ").append(bounds.toString());
        if (nodeInfo.getText() != null) {
            sb.append(" - \"").append(nodeInfo.getText()).append("\"");
        }
        Log.v(TAG, sb.toString());

        for (int i = 0; i < nodeInfo.getChildCount(); i++) {
            AccessibilityNodeInfo childNode = nodeInfo.getChild(i);
            if (childNode != null) {
                logNodeHierachy(childNode, depth + 1);
            }
        }
    }

    private static AccessibilityNodeInfo findSmallestNodeAtPoint(AccessibilityNodeInfo sourceNode, int x, int y) {
        Rect bounds = new Rect();
        sourceNode.getBoundsInScreen(bounds);

        if (!bounds.contains(x, y)) {
            return null;
        }

        for (int i = 0; i < sourceNode.getChildCount(); i++) {
            AccessibilityNodeInfo nearestSmaller = findSmallestNodeAtPoint(sourceNode.getChild(i), x, y);
            if (nearestSmaller != null) {
                return nearestSmaller;
            }
        }
        return sourceNode;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {

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
        cursorLayout.height = 24;
        cursorLayout.width = 24;

//        int LAYOUT_FLAG;
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//            LAYOUT_FLAG = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
//        } else {
//            LAYOUT_FLAG = WindowManager.LayoutParams.TYPE_PHONE;
//        }
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        receiveThread = new ReceiveThread();
        receiveThread.start();

    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (windowManager != null && cursorView != null) {
            windowManager.removeView(cursorView);
        }
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "socket关闭异常");
            }
        }
    }

    private void click() {
        Log.d(TAG, String.format("Click [%d, %d]", cursorLayout.x, cursorLayout.y));
        AccessibilityNodeInfo nodeInfo = getRootInActiveWindow();
        if (nodeInfo == null) return;
        AccessibilityNodeInfo nearestNodeToMouse = findSmallestNodeAtPoint(nodeInfo, cursorLayout.x, cursorLayout.y + 50);
        if (nearestNodeToMouse != null) {
            logNodeHierachy(nearestNodeToMouse, 0);
            nearestNodeToMouse.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        nodeInfo.recycle();
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
                        int gameMap = Integer.parseInt(pos[2]);
                        cursorLayout.x = posX;
                        cursorLayout.y = posY;
                        new Handler(getMainLooper()).post(() -> {
                            try {
                                if (gameMap > 0) {
                                    cursorView.setVisibility(View.INVISIBLE);
                                } else {
                                    cursorView.setVisibility(View.VISIBLE);
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


    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        Log.d(TAG, "onStartCommand()");
        if (cursorView.getWindowId() == null) {
            windowManager.addView(cursorView, cursorLayout);
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
