package com.mordor.kelly.ui.login;

import com.mordor.kelly.kelsy.KelsyPaths;
import com.mordor.kelly.kelsy.config.ConfigLoader;
import com.mordor.kelly.kelsy.config.KelsyConfig;
import com.mordor.kelly.service.SaveLastLoginService;

/**
 * 登录面板的业务控制器：预填、校验和持久化登录信息。
 *
 * <h3>职责分离</h3>
 * <p>本类只处理业务逻辑（预填、校验、落盘），
 * 真正的网络连接由 {@link com.mordor.kelly.ui.login.LoginPane} + {@link com.mordor.kelly.service.ImClient} 完成。</p>
 *
 * <h3>数据流</h3>
 * <ol>
 *   <li>{@link #prefill()} - 从上次保存的登录信息预填表单</li>
 *   <li>{@link #validate(Input)} - 校验用户输入</li>
 *   <li>{@link #save(Input)} - 保存登录信息到本地存储</li>
 * </ol>
 *
 * <h3>结果类型</h3>
 * <p>使用 sealed interface {@link Result} 表示校验结果：
 * {@link Result.Ok} 或 {@link Result.Invalid}（包含错误信息）。</p>
 */
public class LoginController {

    /** 上次登录信息保存服务 */
    private final SaveLastLoginService saveService;
    /** Kelsy 配置文件路径管理 */
    private final KelsyPaths kelsyPaths;

    /**
     * 使用默认 Kelsy 路径构造。
     */
    public LoginController(SaveLastLoginService saveService) {
        this(saveService, KelsyPaths.defaults());
    }

    /**
     * 使用指定 Kelsy 路径构造（便于测试注入）。
     */
    LoginController(SaveLastLoginService saveService, KelsyPaths kelsyPaths) {
        this.saveService = saveService;
        this.kelsyPaths = kelsyPaths;
    }

    /**
     * 预填数据记录：上次保存的登录信息。
     */
    public record Prefilled(String ip, String port, String imCode,
                            String username, String peerName) {}

    /**
     * 用户输入数据记录：表单中填写的所有字段。
     */
    public record Input(String ip, String port, String imCode,
                        String password, String username, boolean offline,
                        String workspaceDir) {
        /** 在线模式构造（workspaceDir 使用默认值） */
        public Input(String ip, String port, String imCode,
                     String password, String username) {
            this(ip, port, imCode, password, username, false, "");
        }
    }

    /**
     * 校验结果密封接口。
     */
    public sealed interface Result {
        /** 校验通过 */
        record Ok() implements Result {}
        /** 校验失败，包含错误信息 */
        record Invalid(String message) implements Result {}
    }

    /**
     * 获取上次保存的登录信息，用于预填表单。
     *
     * @return 预填数据
     */
    public Prefilled prefill() {
        return new Prefilled(
                saveService.getServerIp(),
                saveService.getServerPort(),
                saveService.getImCode(),
                saveService.getUsername(),
                saveService.getPeerName());
    }

    /**
     * 校验用户输入。
     *
     * <p>在线模式校验：IP、端口（1-65535）、IM_CODE、口令、用户名均不能为空。
     * 脱机模式只校验用户名。</p>
     *
     * @param input 用户输入
     * @return 校验结果
     */
    public Result validate(Input input) {
        if (input.offline()) {
            if (input.username().isBlank()) {
                return new Result.Invalid("请输入用户名");
            }
            return new Result.Ok();
        }
        if (input.ip().isBlank()) {
            return new Result.Invalid("请输入服务器 IP");
        }
        if (input.port().isBlank()) {
            return new Result.Invalid("请输入端口");
        }
        if (!input.port().matches("\\d+")) {
            return new Result.Invalid("端口必须是数字");
        }
        int port = Integer.parseInt(input.port());
        if (port < 1 || port > 65535) {
            return new Result.Invalid("端口必须在 1-65535 之间");
        }
        if (input.imCode().isBlank()) {
            return new Result.Invalid("请输入 IM_CODE");
        }
        if (input.password().isBlank()) {
            return new Result.Invalid("请输入初始口令");
        }
        if (input.username().isBlank()) {
            return new Result.Invalid("请输入用户名");
        }
        return new Result.Ok();
    }

    /**
     * 获取 Kelsy 工作区目录路径。
     */
    public String workspaceDir() {
        return ConfigLoader.peek(kelsyPaths).workspaceDir();
    }

    /**
     * 保存登录信息到本地存储。
     *
     * <p>保存内容：
     * <ul>
     *   <li>SaveLastLoginService: IP、端口、IM_CODE、用户名</li>
     *   <li>KelsyConfig: 工作区目录、用户名、模型设置</li>
     * </ul>
     *
     * @param input 用户输入
     */
    public void save(Input input) {
        if (input.offline()) {
            // 脱机模式：保持服务器信息不变，只更新用户名
            saveService.save(
                    saveService.getServerIp(),
                    saveService.getServerPort(),
                    saveService.getImCode(),
                    input.username(),
                    saveService.getPeerName());
        } else {
            saveService.save(input.ip(), input.port(), input.imCode(),
                    input.username(), saveService.getPeerName());
        }
        // 更新 Kelsy 配置
        String dir = input.workspaceDir() == null ? "" : input.workspaceDir().strip();
        if (dir.isEmpty()) {
            dir = KelsyConfig.DEFAULT_WORKSPACE_DIR;
        }
        ConfigLoader.ensureAndHasApiKey(kelsyPaths);
        KelsyConfig current = ConfigLoader.peek(kelsyPaths);
        ConfigLoader.save(kelsyPaths, new KelsyConfig(
                current.model(), dir, input.username(),
                current.selfAvatarPath(), current.kelsyAvatarPath()));
    }

    /**
     * 获取保存的头像路径。
     */
    public String avatarPath() {
        return saveService.getAvatarPath();
    }

    /**
     * 保存头像路径。
     */
    public void saveAvatarPath(String path) {
        saveService.saveAvatarPath(path);
    }

    /**
     * 获取 Kelsy 路径管理器。
     */
    public KelsyPaths kelsyPaths() {
        return kelsyPaths;
    }
}
