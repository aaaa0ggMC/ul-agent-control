package aaaa0ggmc.ulac.server;

import aaaa0ggmc.ulac.Actions;
import aaaa0ggmc.ulac.UlAgentControl;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 回环 HTTP 控制端点。只绑 127.0.0.1；token 鉴权（X-ULAC-Token 头或 ?token=）。
 * 连接信息（host/port/token）写入 <gameDir>/ul-agent-control.json 供 node SDK 发现。
 */
public final class ControlServer {

    public static volatile File infoFile;
    private static HttpServer server;
    private static String token;

    private ControlServer() {
    }

    public static synchronized void start(File gameDir) {
        if (server != null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            sb.append(Integer.toHexString(ThreadLocalRandom.current().nextInt(16)));
        }
        token = sb.toString() + Long.toHexString(System.nanoTime() & 0xFFFFFFL);

        int port = Integer.getInteger("ulac.port", 27631);
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        } catch (IOException e) {
            try {
                port = 0;
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e2) {
                UlAgentControl.LOGGER.error("[ulac] failed to bind control port", e2);
                return;
            }
        }
        server.createContext("/", ControlServer::handle);
        server.start();
        port = server.getAddress().getPort();

        infoFile = new File(gameDir, "ul-agent-control.json");
        JsonObject info = new JsonObject();
        info.addProperty("host", "127.0.0.1");
        info.addProperty("port", port);
        info.addProperty("token", token);
        info.addProperty("pid", ManagementFactory.getRuntimeMXBean().getName().split("@")[0]);
        try {
            Files.write(infoFile.toPath(), info.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            UlAgentControl.LOGGER.error("[ulac] failed to write info file", e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (server != null) {
                server.stop(0);
            }
        }, "ulac-shutdown"));
    }

    private static void handle(HttpExchange ex) throws IOException {
        JsonObject resp;
        try {
            String query = ex.getRequestURI().getQuery();
            String given = ex.getRequestHeaders().getFirst("X-ULAC-Token");
            if (given == null && query != null && query.startsWith("token=")) {
                given = query.substring("token=".length());
            }
            if (token != null && !token.equals(given)) {
                resp = err("bad token");
            } else {
                String path = ex.getRequestURI().getPath();
                String method = ex.getRequestMethod();
                if ("GET".equals(method) && "/state".equals(path)) {
                    JsonObject req = new JsonObject();
                    req.addProperty("action", "state");
                    resp = Actions.dispatch(req);
                } else if ("POST".equals(method) && "/cmd".equals(path)) {
                    String body = readBody(ex.getRequestBody());
                    try {
                        resp = Actions.dispatch(new JsonParser().parse(body).getAsJsonObject());
                    } catch (Exception e) {
                        resp = err("bad request: " + e);
                    }
                } else {
                    resp = err("not found");
                }
            }
        } catch (Exception e) {
            resp = err(String.valueOf(e));
        }
        byte[] bytes = resp.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String readBody(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toString("UTF-8");
    }

    private static JsonObject err(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", msg);
        return o;
    }
}
