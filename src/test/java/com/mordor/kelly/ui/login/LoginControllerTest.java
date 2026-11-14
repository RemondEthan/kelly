package com.mordor.kelly.ui.login;

import com.mordor.kelly.kelsy.KelsyPaths;
import com.mordor.kelly.kelsy.config.ConfigLoader;
import com.mordor.kelly.kelsy.config.KelsyConfig;
import com.mordor.kelly.service.SaveLastLoginService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginControllerTest {

    private final LoginController controller = new LoginController(new SaveLastLoginService());

    @Test
    void onlineStillRequiresServerFields() {
        LoginController.Input empty = new LoginController.Input("", "", "", "", "", false, "");
        assertInstanceOf(LoginController.Result.Invalid.class, controller.validate(empty));
        LoginController.Input noUser = new LoginController.Input(
                "127.0.0.1", "3000", "ABC", "pw", "", false, "");
        assertInstanceOf(LoginController.Result.Invalid.class, controller.validate(noUser));
    }

    @Test
    void offlineAcceptsUsernameOnly() {
        LoginController.Input input = new LoginController.Input("", "", "", "", "法内狂徒", true, "");
        assertInstanceOf(LoginController.Result.Ok.class, controller.validate(input));
    }

    @Test
    void offlineRejectsBlankUsername() {
        LoginController.Input input = new LoginController.Input("", "", "", "", "  ", true, "");
        var result = controller.validate(input);
        assertInstanceOf(LoginController.Result.Invalid.class, result);
        assertTrue(((LoginController.Result.Invalid) result).message().contains("用户名"));
    }

    @Test
    void validateIgnoresBlankWorkspace() {
        LoginController.Input online = new LoginController.Input(
                "127.0.0.1", "3000", "ABC", "pw", "ada", false, "");
        assertInstanceOf(LoginController.Result.Ok.class, controller.validate(online));
        LoginController.Input offline = new LoginController.Input(
                "", "", "", "", "ada", true, "");
        assertInstanceOf(LoginController.Result.Ok.class, controller.validate(offline));
    }

    @Test
    void saveWritesWorkspaceAndKeepsKey(@TempDir Path tmp) throws Exception {
        KelsyPaths paths = KelsyPaths.forHome(tmp);
        Files.createDirectories(paths.config().getParent());
        Files.writeString(paths.config(), "{\"model\":{\"apiKey\":\"sk-x\"},\"workspaceDir\":\"old\"}");
        LoginController c = new LoginController(new SaveLastLoginService(), paths);
        c.save(new LoginController.Input(
                "127.0.0.1", "3000", "R", "pw", "ada", false, tmp.resolve("ws").toString()));
        KelsyConfig again = ConfigLoader.peek(paths);
        assertEquals("sk-x", again.model().apiKey());
        assertTrue(again.workspaceDir().contains("ws"));
        assertEquals("ada", again.lastUsername());
    }
}
