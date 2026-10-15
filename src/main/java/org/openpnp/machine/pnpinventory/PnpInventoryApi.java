package org.openpnp.machine.pnpinventory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import org.pmw.tinylog.Logger;
import com.google.gson.JsonArray;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** REST transport for the public /api/v1 contract. Never sends hardware commands directly. */
final class PnpInventoryApi {
    static final class HttpFailure extends IOException {
        final int status;
        HttpFailure(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final URI base;
    private final String token;
    private final int timeout;
    private final HttpClient client;

    PnpInventoryApi(String address, int port, String token, int timeout) throws Exception {
        URI host = new URI(address.contains("://") ? address : "http://" + address);
        if (host.getHost() == null || host.getUserInfo() != null || host.getQuery() != null
                || host.getFragment() != null || (host.getPath() != null && !host.getPath().isEmpty()
                && !host.getPath().equals("/"))
                || !("http".equals(host.getScheme()) || "https".equals(host.getScheme()))) {
            throw new IllegalArgumentException("Address must be an HTTP(S) hostname or IP, without a path or credentials.");
        }
        base = new URI(host.getScheme(), null, host.getHost(), port, "/api/v1/", null, null);
        this.token = token;
        this.timeout = timeout;
        client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeout)).build();
    }

    String getBaseAddress() {
        return base.toString();
    }

    JsonObject request(String method, String path, JsonObject body) throws Exception {
        // Log route categories only: session IDs, query values and payloads are private.
        String route = path.split("[/?]", 2)[0].replaceAll("[^a-zA-Z0-9-]", "_");
        String context = "PnPInventory " + method + " " + route;
        long started = System.nanoTime();
        Logger.debug("{} request started", context);
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path))
                    .timeout(Duration.ofMillis(timeout)).header("Accept", "application/json");
            if (token != null && !token.isEmpty()) {
                request.header("Authorization", "Bearer " + token);
            }
            if (body == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            }
            else {
                request.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
            }
            HttpResponse<String> response = client.send(request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            Logger.debug("{} returned HTTP {} in {} ms", context, status,
                    (System.nanoTime() - started) / 1_000_000);
            if (status < 200 || status >= 300) {
                // Never include remote response bodies: these may echo credentials.
                throw new HttpFailure(status, context + " returned HTTP " + status + ". " + guidance(status));
            }
            if (status == 204 && "DELETE".equals(method)) {
                return new JsonObject();
            }
            try {
                JsonElement parsed = new JsonParser().parse(response.body());
                if (!parsed.isJsonObject()) {
                    throw new IllegalStateException();
                }
                return parsed.getAsJsonObject();
            }
            catch (RuntimeException e) {
                throw new IOException(context + " returned an empty or invalid JSON object. Check the backend API version.");
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Logger.warn("{} interrupted; the backend may already have accepted the request", context);
            throw e;
        }
        catch (IllegalArgumentException e) {
            Logger.warn("{} could not be constructed; check endpoint and authentication configuration", context);
            throw new IOException(context + " has invalid request configuration. Check the endpoint and authentication settings.");
        }
        catch (IOException e) {
            String message;
            if (e instanceof HttpTimeoutException) {
                message = context + " timed out. The backend may already have accepted the request.";
            }
            else if (e.getMessage() != null && e.getMessage().startsWith(context + " ")) {
                message = e.getMessage();
            }
            else {
                message = context + " connection failed (" + e.getClass().getSimpleName()
                        + "). Check the server address, network and TLS configuration."
                        + " The backend may already have accepted the request.";
            }
            Logger.warn(message);
            if (e instanceof HttpFailure) { throw e; }
            // Transport/parser causes can contain credentials or response content.
            throw new IOException(message);
        }
    }

    private static String guidance(int status) {
        switch (status) {
            case 400: case 422: return "The backend rejected the request data. Check configuration and API compatibility.";
            case 401: return "Use Log in in the actuator settings.";
            case 403: return "The account is not permitted to perform this action. Check backend permissions.";
            case 404: case 410: return "The resource is missing or expired. Check the backend configuration.";
            case 405: case 415: case 501: return "Check the backend API version and supported methods.";
            case 409: case 412: case 428: return "Backend state or revision conflicts with this request. Resolve the assignment or job in the backend.";
            case 429: return "Backend rate limit reached. Wait before retrying; observe Retry-After in backend diagnostics.";
            default:
                if (status >= 300 && status < 400) {
                    return "Redirects are not followed. Configure the API server address directly.";
                }
                return status >= 500 ? "Backend service failed. Check backend diagnostics before retrying."
                        : "Request rejected. Check backend diagnostics and API compatibility.";
        }
    }

    static IOException invalid(String key) {
        Logger.warn("PnPInventory response has a missing or invalid {} field", key);
        return new IOException("PnPInventory response has a missing or invalid " + key + " field.");
    }

    static String required(JsonObject object, String key) throws IOException {
        JsonElement value = object == null ? null : object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().trim().isEmpty()) {
            throw invalid(key);
        }
        return value.getAsString();
    }

    static JsonObject object(JsonElement value, String key) throws IOException {
        if (value == null || !value.isJsonObject()) {
            throw invalid(key);
        }
        return value.getAsJsonObject();
    }

    static JsonArray items(JsonObject value) throws IOException {
        JsonElement items = value.get("items");
        if (items == null || !items.isJsonArray()) {
            throw invalid("items");
        }
        return items.getAsJsonArray();
    }

    static boolean bool(JsonObject object, String key) throws IOException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw invalid(key);
        }
        return value.getAsBoolean();
    }

    static double number(JsonObject object, String key) throws IOException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !Double.isFinite(value.getAsDouble())) {
            throw invalid(key);
        }
        return value.getAsDouble();
    }

    static long integer(JsonObject object, String key) throws IOException {
        number(object, key);
        try {
            return object.get(key).getAsBigDecimal().longValueExact();
        }
        catch (ArithmeticException | NumberFormatException e) {
            throw invalid(key);
        }
    }

    static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    JsonObject findSlot(int slot, String busId) throws Exception {
        String filter = "feeders?limit=200" + (busId.isEmpty() ? "" : "&busId=" + segment(busId));
        String path = filter;
        Set<String> seen = new HashSet<>();
        JsonObject found = null;
        do {
            JsonObject page = request("GET", path, null);
            for (JsonElement item : items(page)) {
                JsonObject feeder = object(item, "items entry");
                if ("fbus".equals(required(feeder, "kind")) && feeder.has("fbusAddress")
                        && !feeder.get("fbusAddress").isJsonNull()
                        && integer(feeder, "fbusAddress") == slot
                        && (busId.isEmpty() || busId.equals(required(feeder, "busId")))) {
                    if (found != null) {
                        throw new IOException("More than one feeder has slot " + slot + ". Configure the actuator Bus UUID.");
                    }
                    found = feeder;
                }
            }
            JsonElement cursor = page.get("nextCursor");
            if (cursor == null || cursor.isJsonNull()) {
                break;
            }
            if (!seen.add(required(page, "nextCursor"))) {
                throw new IOException("PnPInventory returned a repeated pagination cursor.");
            }
            path = filter + "&cursor=" + segment(required(page, "nextCursor"));
        } while (true);
        if (found == null) {
            throw new IOException("No feeder found at slot " + slot);
        }
        if (!bool(found, "online") || !bool(found, "enabled")
                || !"ready".equals(required(found, "commissioningState"))) {
            throw new IOException("Feeder at slot " + slot + " is offline, disabled, or not commissioned.");
        }
        return found;
    }
}
