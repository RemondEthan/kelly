package com.mordor.kelly.ui.chat;

import javafx.application.Platform;
import java.util.concurrent.*;

/** Wait for toolkit initialization before any asynchronous image is created. */
final class FxTestSupport {
    private FxTestSupport() {}
    static void startToolkit() throws Exception {
        var ready=new CountDownLatch(1);
        try{Platform.startup(ready::countDown);}
        catch(IllegalStateException already){Platform.runLater(ready::countDown);}
        if(!ready.await(10,TimeUnit.SECONDS))throw new AssertionError("JavaFX startup timed out");
        var keepAlive=new FutureTask<Void>(()->{Platform.setImplicitExit(false);return null;});
        Platform.runLater(keepAlive);keepAlive.get(10,TimeUnit.SECONDS);
    }
    static void drain()throws Exception {
        var barrier=new FutureTask<Void>(()->null);
        try{Platform.runLater(barrier);}catch(IllegalStateException notStarted){return;}
        barrier.get(10,TimeUnit.SECONDS);
    }
}
