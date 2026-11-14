package com.mordor.kelly.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolTest {

    @Test
    void registerJsonContainsImCodeAndUsername() {
        String json = Protocol.register("OFFICE2024", "Alice");
        assertTrue(json.contains("\"type\":\"register\""));
        assertTrue(json.contains("\"im_code\":\"OFFICE2024\""));
        assertTrue(json.contains("\"username\":\"Alice\""));
    }

    @Test
    void registerOmitsUserIdWhenUnset() {
        String json = Protocol.register("OFFICE2024", "Alice");
        assertTrue(json.contains("\"im_code\":\"OFFICE2024\""));
        assertTrue(json.contains("\"username\":\"Alice\""));
        assertTrue(!json.contains("user_id"));
    }

    @Test
    void registerIncludesUserIdWhenPositive() {
        String json = Protocol.register("OFFICE2024", "Alice", 200003);
        assertTrue(json.contains("\"user_id\":200003"));
        Protocol.Incoming incoming = Protocol.parse(json);
        assertEquals("register", incoming.type());
        assertEquals(200003, incoming.userId());
    }

    @Test
    void textJsonEscapesQuotes() {
        String json = Protocol.text("say \"hi\"", "Bo\"b");
        Protocol.Incoming incoming = Protocol.parse(json);
        assertEquals("text", incoming.type());
        assertEquals("say \"hi\"", incoming.content());
        assertEquals("Bo\"b", incoming.username());
    }

    @Test
    void parseRegistered() {
        Protocol.Incoming msg = Protocol.parse(
                "{\"type\":\"registered\",\"data\":{\"user_id\":2,\"padding\":\"aB3dE5gH\"}}");
        assertEquals("registered", msg.type());
        assertEquals(2, msg.userId());
        assertEquals("aB3dE5gH", msg.padding());
    }

    @Test
    void parsePeerConnected() {
        Protocol.Incoming msg = Protocol.parse(
                "{\"type\":\"peer_connected\",\"data\":{\"user_id\":3,\"username\":\"Bob\"}}");
        assertEquals("peer_connected", msg.type());
        assertEquals(3, msg.userId());
        assertEquals("Bob", msg.username());
    }

    @Test
    void avatarJsonAndParse() {
        String json = Protocol.avatar("cipher-thumb");
        assertTrue(json.contains("\"type\":\"avatar\""));
        Protocol.Incoming msg = Protocol.parse(
                "{\"type\":\"avatar\",\"data\":{\"user_id\":2,\"username\":\"Alice\",\"content\":\"cipher-thumb\"}}");
        assertEquals("avatar", msg.type());
        assertEquals(2, msg.userId());
        assertEquals("Alice", msg.username());
        assertEquals("cipher-thumb", msg.content());
    }

    @Test
    void imageAndChunkEnvelope() {
        String image = Protocol.image("meta-cipher", "Alice");
        Protocol.Incoming img = Protocol.parse(image);
        assertEquals("image", img.type());
        assertEquals("meta-cipher", img.content());
        assertEquals("Alice", img.username());
        Protocol.Incoming chunk = Protocol.parse(Protocol.imageChunk("part", "Bob"));
        assertEquals("image_chunk", chunk.type());
        assertEquals("part", chunk.content());
        assertEquals("Bob", chunk.username());
    }

    @Test
    void parseError() {
        Protocol.Incoming msg = Protocol.parse(
                "{\"type\":\"error\",\"data\":{\"message\":\"Room is full (max 10 users)\"}}");
        assertEquals("error", msg.type());
        assertEquals("Room is full (max 10 users)", msg.message());
    }
}
