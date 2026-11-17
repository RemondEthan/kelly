package com.mordor.kelly.app;

import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledOnOs(OS.LINUX)
class SniTrayDbusTest {
    @Test
    void exportsTypedIconAndQtCompatibleMenuOverDbus() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/dbus-daemon"))
                && Files.isExecutable(Path.of("/usr/bin/gdbus")),
                "Requires dbus-daemon and gdbus");
        var daemon = new ProcessBuilder("dbus-daemon", "--session", "--nofork",
                "--print-address=1").redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            var reader = new BufferedReader(new InputStreamReader(
                    daemon.getInputStream(), StandardCharsets.UTF_8));
            String address = reader.readLine();
            assertNotNull(address);
            try (var bus = DBusConnectionBuilder.forAddress(address).build()) {
                var icon = new SniPixmap(1, 1, new byte[]{(byte) 255, 12, 34, 56});
                var item = new SniTray.Item(bus, null, List.of(icon));
                var opened = new AtomicBoolean();
                var menu = new SniTray.Menu(() -> opened.set(true), () -> {}, () -> {});
                bus.exportObject("/StatusNotifierItem", item);
                bus.exportObject("/MenuBar", menu);

                String pixmaps = call(address, bus.getUniqueName(), "/StatusNotifierItem",
                        "org.freedesktop.DBus.Properties.Get",
                        "org.kde.StatusNotifierItem", "IconPixmap");
                assertTrue(pixmaps.contains("[(1, 1, [byte 0xff, 0x0c, 0x22, 0x38])]"), pixmaps);
                String empty = call(address, bus.getUniqueName(), "/StatusNotifierItem",
                        "org.freedesktop.DBus.Properties.Get",
                        "org.kde.StatusNotifierItem", "OverlayIconPixmap");
                assertTrue(empty.contains("@a(iiay) []"), empty);
                String layout = call(address, bus.getUniqueName(), "/MenuBar",
                        "com.canonical.dbusmenu.GetLayout", "0", "-1", "[]");
                assertTrue(layout.startsWith("(uint32 1, (0,"), layout);
                assertTrue(layout.contains("打开 Kelly"), layout);
                assertTrue(layout.contains("隐藏窗口"), layout);
                assertTrue(layout.contains("退出"), layout);
                String properties = call(address, bus.getUniqueName(), "/MenuBar",
                        "org.freedesktop.DBus.Properties.GetAll", "com.canonical.dbusmenu");
                assertTrue(properties.contains("'Version': <uint32 3>"), properties);
                call(address, bus.getUniqueName(), "/MenuBar",
                        "com.canonical.dbusmenu.Event", "1", "clicked", "<0>", "0");
                assertTrue(opened.get(), "Menu click must reach its callback");
            }
        } finally {
            daemon.destroy();
            if (!daemon.waitFor(5, TimeUnit.SECONDS)) {
                daemon.destroyForcibly();
            }
        }
    }

    private static String call(String address, String destination, String path,
                               String method, String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("gdbus", "call", "--address", address,
                "--dest", destination, "--object-path", path, "--method", method, "--"));
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "D-Bus call timed out: " + method);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            return output;
        } finally {
            process.destroyForcibly();
        }
    }
}
