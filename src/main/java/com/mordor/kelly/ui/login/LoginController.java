package com.mordor.kelly.ui.login;

import com.mordor.kelly.kelsy.KelsyPaths;
import com.mordor.kelly.kelsy.config.ConfigLoader;
import com.mordor.kelly.kelsy.config.KelsyConfig;
import com.mordor.kelly.service.SaveLastLoginService;

/**
 * 登录面板的业务侧：预填 / 校验 / 落盘。真正连服务器由 LoginPane + ImClient 完成。
 */
public class LoginController {

    private final SaveLastLoginService saveService;
    private final KelsyPaths kelsyPaths;

    public LoginController(SaveLastLoginService saveService) {
        this(saveService, KelsyPaths.defaults());
    }

    LoginController(SaveLastLoginService saveService, KelsyPaths kelsyPaths) {
        this.saveService = saveService;
        this.kelsyPaths = kelsyPaths;
    }

    public record Prefilled(String ip, String port, String imCode,
                            String username, String peerName) {}

    public record Input(String ip, String port, String imCode,
                        String password, String username, boolean offline,
                        String workspaceDir) {
        public Input(String ip, String port, String imCode,
                     String password, String username) {
            this(ip, port, imCode, password, username, false, "");
        }
    }

    public sealed interface Result {
        record Ok() implements Result {}
        record Invalid(String message) implements Result {}
    }

    public Prefilled prefill() {
        return new Prefilled(
                saveService.getServerIp(),
                saveService.getServerPort(),
                saveService.getImCode(),
                saveService.getUsername(),
                saveService.getPeerName());
    }

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

    public String workspaceDir() {
        return ConfigLoader.peek(kelsyPaths).workspaceDir();
    }

    public void save(Input input) {
        if (input.offline()) {
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

    public String avatarPath() {
        return saveService.getAvatarPath();
    }

    public void saveAvatarPath(String path) {
        saveService.saveAvatarPath(path);
    }

    public KelsyPaths kelsyPaths() {
        return kelsyPaths;
    }
}
