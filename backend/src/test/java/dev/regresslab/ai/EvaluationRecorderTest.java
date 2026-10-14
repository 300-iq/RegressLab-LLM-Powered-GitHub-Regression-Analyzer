package dev.regresslab.ai;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class EvaluationRecorderTest {
    @Test void refusesBrowserForwardedWrongPathAndOversizedRequests(@TempDir Path directory) throws Exception {
        try (var recorder=new InvestigatorEvaluation.Recorder(new AiSettings(true,"http://127.0.0.1:1","test:model",2),directory)) {
            var transport=HttpClient.newHttpClient();
            for (String header : java.util.List.of("Origin","Sec-Fetch-Site","Forwarded","X-Forwarded-For","X-Real-IP")) {
                var request=HttpRequest.newBuilder(URI.create(recorder.origin()+"/api/chat")).header(header,"untrusted")
                        .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}"));
                assertThat(transport.send(request.build(),HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
            }
            for (String path : java.util.List.of("/api/chat?url=evil","/execute","/api/pull","/api/chat/extra")) {
                var request=HttpRequest.newBuilder(URI.create(recorder.origin()+path)).header("Content-Type","application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"));
                assertThat(transport.send(request.build(),HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(404);
            }
            var simple=HttpRequest.newBuilder(URI.create(recorder.origin()+"/api/chat")).POST(HttpRequest.BodyPublishers.ofString("{}"));
            assertThat(transport.send(simple.build(),HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(415);
            var huge=HttpRequest.newBuilder(URI.create(recorder.origin()+"/api/chat")).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("x".repeat(65537)));
            assertThat(transport.send(huge.build(),HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(413);
            try (var files=Files.list(directory)) { assertThat(files.toList()).isEmpty(); }
        }
    }
    @Test void retainsRejectedModelResponseWithoutRunningIt(@TempDir Path directory) throws Exception {
        var upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        upstream.createContext("/api/chat",e -> {
            byte[] response="{\"message\":\"execute a command\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.sendResponseHeaders(200,response.length); e.getResponseBody().write(response); e.close();
        }); upstream.start();
        try (var recorder=new InvestigatorEvaluation.Recorder(new AiSettings(true,"http://127.0.0.1:"+upstream.getAddress().getPort()+"/","test:model",2),directory)) {
            var request=HttpRequest.newBuilder(URI.create(recorder.origin()+"/api/chat")).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"test:model\"}"));
            assertThat(HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString()).body()).contains("execute a command");
            assertThat(Files.readString(directory.resolve("raw-response.json"))).contains("execute a command");
            assertThat(Files.readString(directory.resolve("request.json"))).contains("test:model");
        } finally { upstream.stop(0); }
    }
}
