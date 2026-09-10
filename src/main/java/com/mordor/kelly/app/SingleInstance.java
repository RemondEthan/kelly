package com.mordor.kelly.app;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 本机只跑一个客户端。第二个进程唤醒已有窗口后退出，避免旧昵称会话还活着。
 */
final class SingleInstance {

    private static final int PORT = 18732;

    private SingleInstance() {}

    static boolean claim(Runnable onShow) {
        try {
            ServerSocket server = new ServerSocket(PORT, 1, InetAddress.getLoopbackAddress());
            Thread t = new Thread(() -> listen(server, onShow), "kelly-instance");
            t.setDaemon(true);
            t.start();
            return true;
        } catch (IOException occupied) {
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), PORT)) {
                socket.getOutputStream().write('!');
            } catch (IOException ignored) {
                // 唤醒失败也退出，避免再开一条会话
            }
            return false;
        }
    }

    private static void listen(ServerSocket server, Runnable onShow) {
        while (!server.isClosed()) {
            try (Socket ignored = server.accept()) {
                if (onShow != null) {
                    onShow.run();
                }
            } catch (IOException ignored) {
                return;
            }
        }
    }
}
