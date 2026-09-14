package com.mordor.kelly.app;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 单实例保证机制——确保同一时间只有一个 Kelly 客户端运行。
 *
 * <p>工作原理：
 * <ul>
 *   <li>第一个进程在 localhost 的固定端口 {@value #PORT} 上创建 {@link ServerSocket}</li>
 *   <li>守护线程 {@code kelly-instance} 持续监听该端口的连接请求</li>
 *   <li>第二个进程启动时尝试连接该端口，连接成功说明已有实例在运行</li>
 *   <li>第二个进程向已运行实例发送 '!' 字符作为唤醒信号</li>
 *   <li>已运行实例收到连接后，调用 {@code onShow} 回调恢复窗口到前台</li>
 *   <li>第二个进程在发送唤醒信号后退出，避免同时运行两个会话</li>
 * </ul>
 *
 * <p>为什么需要单实例：
 * 如果多个进程同时运行，会创建多个 IM 连接，导致消息重复接收、
 * 旧昵称会话残留等问题。
 */
final class SingleInstance {

    /** 用于单实例检测的固定端口号 */
    private static final int PORT = 18732;

    private SingleInstance() {}

    /**
     * 尝试获取单实例所有权。
     *
     * <p>如果成功绑定端口，启动监听线程并返回 true（当前是唯一实例）。
     * 如果端口已被占用，向已运行实例发送唤醒信号并返回 false（当前不是主实例，应退出）。
     *
     * @param onShow 当已有实例收到唤醒信号时调用的回调（恢复窗口到前台）
     * @return true 表示当前进程拥有实例所有权，false 表示应退出
     */
    static boolean claim(Runnable onShow) {
        try {
            // 绑定 localhost 固定端口，backlog=1 只允许一个排队连接
            ServerSocket server = new ServerSocket(PORT, 1, InetAddress.getLoopbackAddress());
            // 启动守护线程持续监听连接请求
            Thread t = new Thread(() -> listen(server, onShow), "kelly-instance");
            t.setDaemon(true);
            t.start();
            return true; // 端口绑定成功，当前是唯一实例
        } catch (IOException occupied) {
            // 端口已被占用，说明已有实例在运行
            // 向已有实例发送 '!' 唤醒信号
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), PORT)) {
                socket.getOutputStream().write('!');
            } catch (IOException ignored) {
                // 唤醒失败也退出，避免再开一条会话
            }
            return false; // 返回 false，调用方应退出进程
        }
    }

    /**
     * 监听线程：持续接受连接请求，收到连接时触发唤醒回调。
     *
     * <p>每当有新连接进来（即第二个进程启动并发送 '!'），
     * 就调用 {@code onShow} 回调将窗口恢复到前台。
     * ServerSocket 关闭时线程退出。
     *
     * @param server 监听中的 ServerSocket
     * @param onShow 唤醒回调（恢复窗口）
     */
    private static void listen(ServerSocket server, Runnable onShow) {
        while (!server.isClosed()) {
            try (Socket ignored = server.accept()) {
                // 收到连接，触发回调恢复窗口
                if (onShow != null) {
                    onShow.run();
                }
            } catch (IOException ignored) {
                return; // ServerSocket 已关闭，退出监听
            }
        }
    }
}
