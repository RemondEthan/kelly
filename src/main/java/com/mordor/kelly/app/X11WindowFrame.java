package com.mordor.kelly.app;

import com.sun.javafx.stage.WindowHelper;
import com.sun.jna.Memory;
import com.sun.jna.NativeLong;
import com.sun.jna.platform.unix.X11;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

import java.util.Arrays;
import java.util.function.Consumer;

final class X11WindowFrame {
    private static final int MWM_HINTS_FUNCTIONS = 1;
    private static final int MWM_HINTS_DECORATIONS = 2;
    private static final int MWM_FUNC_ALL = 1;

    private X11WindowFrame() {
    }

    static boolean isDeepinSession() {
        String desktop = System.getenv("XDG_CURRENT_DESKTOP");
        return "x11".equalsIgnoreCase(System.getenv("XDG_SESSION_TYPE"))
                && desktop != null && Arrays.stream(desktop.split(":"))
                .anyMatch(name -> name.equalsIgnoreCase("DDE") || name.equalsIgnoreCase("Deepin"));
    }

    static void install(Stage stage, Consumer<Boolean> setCustomFrame) {
        stage.addEventHandler(WindowEvent.WINDOW_SHOWN,
                event -> setCustomFrame.accept(hideDecorations(stage)));
    }

    private static boolean hideDecorations(Stage stage) {
        try {
            long xid = WindowHelper.getPeer(stage).getRawHandle();
            if (xid == 0) {
                return false;
            }
            X11 x11 = X11.INSTANCE;
            X11.Display display = x11.XOpenDisplay(null);
            if (display == null) {
                return false;
            }
            try (Memory hints = new Memory(5L * NativeLong.SIZE)) {
                hints.clear();
                // MWM_FUNC_ALL preserves window actions, while decorations stays zero.
                // Xlib expects native longs here even though the property format is 32 bits.
                hints.setNativeLong(0, new NativeLong(MWM_HINTS_FUNCTIONS | MWM_HINTS_DECORATIONS));
                hints.setNativeLong(NativeLong.SIZE, new NativeLong(MWM_FUNC_ALL));
                X11.Atom motif = x11.XInternAtom(display, "_MOTIF_WM_HINTS", false);
                x11.XChangeProperty(display, new X11.Window(xid), motif, motif, 32,
                        X11.PropModeReplace, hints, 5);
                x11.XSync(display, false);
                return true;
            } finally {
                x11.XCloseDisplay(display);
            }
        } catch (LinkageError | RuntimeException e) {
            System.getLogger(X11WindowFrame.class.getName())
                    .log(System.Logger.Level.WARNING, "Cannot hide X11 window decorations", e);
            return false;
        }
    }
}
