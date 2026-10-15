package org.openpnp.machine.pnpinventory;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

class PnpInventoryApiTest {
    private HttpServer server;
    private int status = 200;
    private String body = "{}";
    private PnpInventoryApi api;

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/private");
            exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
            if (status != 204) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        api = new PnpInventoryApi("127.0.0.1", server.getAddress().getPort(), "secret-token", 1000);
    }

    @AfterEach
    void cleanup() {
        server.stop(0);
    }

    @Test
    void everyHttpFailureIsActionableWithoutEchoingSecretsOrFollowingRedirects() {
        for (int code : new int[] {301, 302, 307, 308, 400, 401, 403, 404, 405, 408, 409,
                410, 412, 413, 415, 422, 428, 429, 500, 501, 502, 503, 504, 599}) {
            status = code;
            body = "{\"detail\":\"secret-password secret-token\"}";
            IOException error = assertThrows(IOException.class,
                    () -> api.request("POST", "sessions?private-query", new JsonObject()));
            assertTrue(error.getMessage().contains("HTTP " + code));
            assertFalse(error.getMessage().contains("secret"));
            assertFalse(error.getMessage().contains("private-query"));
            assertNull(error.getCause());
        }
    }

    @Test
    void invalidJsonAndWrongRootFailWithoutExposingResponse() {
        for (String payload : new String[] {"", "null", "[]", "true", "\"secret-token\"", "{bad-secret"}) {
            body = payload;
            IOException error = assertThrows(IOException.class, () -> api.request("GET", "feeders", null));
            assertTrue(error.getMessage().contains("invalid JSON"));
            assertFalse(error.getMessage().contains("secret"));
            assertNull(error.getCause());
        }
    }

    @Test
    void noContentIsAcceptedOnlyForDeletion() throws Exception {
        status = 204;
        assertEquals(0, api.request("DELETE", "sessions/private-session", null).entrySet().size());
        assertThrows(IOException.class, () -> api.request("POST", "operations", new JsonObject()));
        assertThrows(IOException.class, () -> api.request("GET", "feeders", null));
    }

    @Test
    void malformedSchemaFailsAsCheckedError() {
        for (String payload : new String[] {"{}", "{\"items\":null}", "{\"items\":{}}",
                "{\"items\":[null]}", "{\"items\":[{}]}",
                "{\"items\":[],\"nextCursor\":4}"}) {
            body = payload;
            assertThrows(IOException.class, () -> api.findSlot(7, ""));
        }
        JsonObject invalid = new JsonParser().parse("{\"revision\":1.5,\"enabled\":\"false\",\"id\":42}")
                .getAsJsonObject();
        assertThrows(IOException.class, () -> PnpInventoryApi.integer(invalid, "revision"));
        assertThrows(IOException.class, () -> PnpInventoryApi.bool(invalid, "enabled"));
        assertThrows(IOException.class, () -> PnpInventoryApi.required(invalid, "id"));
        assertThrows(IOException.class, () -> PnpInventoryApi.number(invalid, "missing"));
    }

    @Test
    void connectionFailureHasSafeActionableMessage() {
        server.stop(0);
        IOException error = assertThrows(IOException.class, () -> api.request("GET", "feeders", null));
        assertTrue(error.getMessage().contains("connection failed"));
        assertNull(error.getCause());
    }

    @Test
    void requestTimeoutReportsUncertainOutcome() throws Exception {
        server.createContext("/api/v1/slow", exchange -> {
            try {
                Thread.sleep(250);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            finally {
                exchange.close();
            }
        });
        PnpInventoryApi impatient = new PnpInventoryApi("127.0.0.1", server.getAddress().getPort(), "", 50);
        IOException error = assertThrows(IOException.class, () -> impatient.request("POST", "slow", new JsonObject()));
        assertTrue(error.getMessage().contains("timed out"));
        assertTrue(error.getMessage().contains("may already have accepted"));
    }

    @Test
    void invalidCredentialHeaderDoesNotLeakItsValue() throws Exception {
        PnpInventoryApi invalid = new PnpInventoryApi("127.0.0.1", server.getAddress().getPort(),
                "secret-token\ninvalid", 1000);
        IOException error = assertThrows(IOException.class, () -> invalid.request("GET", "feeders", null));
        assertFalse(error.getMessage().contains("secret-token"));
        assertNull(error.getCause());
    }

    @Test
    void interruptionRestoresInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> api.request("GET", "feeders", null));
            assertTrue(Thread.currentThread().isInterrupted());
        }
        finally {
            Thread.interrupted();
        }
    }
}
