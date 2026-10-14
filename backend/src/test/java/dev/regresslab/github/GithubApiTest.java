package dev.regresslab.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.sun.net.httpserver.HttpServer;
import dev.regresslab.api.ApiProblem;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GithubApiTest {
    @FunctionalInterface interface Endpoint {void handle(com.sun.net.httpserver.HttpExchange exchange) throws Exception;}
    void withServer(Endpoint endpoint,java.util.function.Consumer<GithubApi> test) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        try(var executor=Executors.newCachedThreadPool()) {
            server.setExecutor(executor);
            server.createContext("/",exchange->{try{endpoint.handle(exchange);}catch(Exception ignored){}finally{exchange.close();}});
            server.start();
            var mapper=new ObjectMapper();mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxStringLength(2000).build());
            var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            var api=new GithubApi(mapper,new GithubSettings(true,"classpath:unused",""),client,URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Duration.ofMillis(300));
            try {test.accept(api);} finally {server.stop(0);executor.shutdownNow();}
        }
    }
    @Test void deadlineIncludesAResponseBodyThatStallsAfterHeaders() throws Exception {
        withServer(exchange->{
            exchange.sendResponseHeaders(200,2);
            exchange.getResponseBody().write('{');exchange.getResponseBody().flush();
            Thread.sleep(1000);exchange.getResponseBody().write('}');
        },api->assertThatThrownBy(()->api.get("/repos/300-iq/regresslab-java-demo"))
                .isInstanceOfSatisfying(ApiProblem.class,problem->assertThat(problem.code()).isEqualTo("GITHUB_UNAVAILABLE")));
    }
    @Test void allowsLongMetadataWithinTheResponseByteLimitWithoutRelaxingArtifactConstraints() throws Exception {
        byte[] body=("{\"body\":\""+"x".repeat(8000)+"\"}").getBytes(StandardCharsets.UTF_8);
        withServer(exchange->{exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);},
                api->assertThat(api.get("/repos/300-iq/regresslab-java-demo").path("body").asText()).hasSize(8000));
    }
    @Test void rejectsOversizedMetadataBodies() throws Exception {
        var bytes=new byte[1048577];java.util.Arrays.fill(bytes,(byte)' ');
        withServer(exchange->{exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);},
                api->assertThatThrownBy(()->api.get("/repos/300-iq/regresslab-java-demo")).isInstanceOf(ApiProblem.class));
    }
    @Test void doesNotFollowRedirectsOrAcceptNonObjectMetadata() throws Exception {
        withServer(exchange->{exchange.getResponseHeaders().add("Location","http://127.0.0.1:1/untrusted");exchange.sendResponseHeaders(302,-1);},
                api->assertThatThrownBy(()->api.get("/repos/300-iq/regresslab-java-demo")).isInstanceOf(ApiProblem.class));
        withServer(exchange->{exchange.sendResponseHeaders(200,2);exchange.getResponseBody().write("[]".getBytes(StandardCharsets.UTF_8));},
                api->assertThatThrownBy(()->api.get("/repos/300-iq/regresslab-java-demo")).isInstanceOf(ApiProblem.class));
    }
}
