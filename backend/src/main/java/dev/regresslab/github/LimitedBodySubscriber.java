package dev.regresslab.github;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Cancel before buffering beyond the byte budget, including chunked responses. */
final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final int limit;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private Flow.Subscription subscription;
    LimitedBodySubscriber(int limit) { this.limit = limit; }
    @Override public CompletionStage<byte[]> getBody() { return body; }
    @Override public void onSubscribe(Flow.Subscription value) {
        subscription = value;
        subscription.request(1);
    }
    @Override public void onNext(List<ByteBuffer> buffers) {
        if (body.isDone()) return;
        for (var buffer : buffers) {
            int size = buffer.remaining();
            if (size > limit - output.size()) {
                body.completeExceptionally(new IOException("GitHub metadata exceeds the byte budget"));
                subscription.cancel();
                return;
            }
            byte[] bytes = new byte[size];
            buffer.get(bytes);
            output.writeBytes(bytes);
        }
        subscription.request(1);
    }
    @Override public void onError(Throwable error) { body.completeExceptionally(error); }
    @Override public void onComplete() { body.complete(output.toByteArray()); }
}
