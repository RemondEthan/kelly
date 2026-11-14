package com.mordor.kelly.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImClientIT {

    static boolean serverBinaryExists() {
        return serverBinary() != null;
    }

    @Test
    @EnabledIf("serverBinaryExists")
    void twoClientsExchangeEncryptedText() throws Exception {
        Process server = new ProcessBuilder(serverBinary().toString(), "13001")
                .redirectErrorStream(true)
                .start();
        try {
            assertTrue(server.isAlive());
            Thread.sleep(400);

            ImClient alice = new ImClient();
            ImClient bob = new ImClient();
            CompletableFuture<String> fromAlice = new CompletableFuture<>();
            bob.addListener(event -> {
                if (event instanceof ImClient.Event.Chat chat) {
                    fromAlice.complete(chat.plaintext());
                }
            });

            alice.connect("127.0.0.1", 13001, "ITROOM", "shared", "Alice")
                    .orTimeout(5, TimeUnit.SECONDS).join();
            bob.connect("127.0.0.1", 13001, "ITROOM", "shared", "Bob")
                    .orTimeout(5, TimeUnit.SECONDS).join();

            assertTrue(bob.roster().containsValue("Alice"),
                    "后登录客户端应立刻拿到已在房间里的对方");
            for (int i = 0; i < 50 && alice.roster().isEmpty(); i++) {
                Thread.sleep(50);
            }
            assertTrue(alice.roster().containsValue("Bob"),
                    "先登录客户端应收到后加入者的 peer_connected");

            alice.sendChat("hello bob");
            assertEquals("hello bob", fromAlice.get(5, TimeUnit.SECONDS));

            alice.close();
            bob.close();
        } finally {
            server.destroyForcibly();
        }
    }

    private static Path serverBinary() {
        Path[] candidates = {
                Path.of("server/build/kserver"),
                Path.of("server/dist/kserver"),
                Path.of("server/cmake-build-debug/kserver")
        };
        for (Path p : candidates) {
            if (Files.isExecutable(p)) {
                return p.toAbsolutePath();
            }
        }
        return null;
    }
}
