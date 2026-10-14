package dev.regresslab.github;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class LimitedBodySubscriberTest {
    static class Subscription implements Flow.Subscription {
        boolean cancelled;
        @Override public void request(long count) {}
        @Override public void cancel() {cancelled=true;}
    }
    @Test void cancelsBeforeBufferingBeyondTheBudgetAcrossChunks() {
        var body=new LimitedBodySubscriber(4);var subscription=new Subscription();body.onSubscribe(subscription);
        body.onNext(List.of(ByteBuffer.wrap(new byte[]{1,2,3})));
        body.onNext(List.of(ByteBuffer.wrap(new byte[]{4,5})));
        assertThat(subscription.cancelled).isTrue();
        assertThat(body.getBody().toCompletableFuture()).isCompletedExceptionally();
    }
    @Test void completesOnlyAfterAllBoundedChunksArrive() {
        var body=new LimitedBodySubscriber(4);var subscription=new Subscription();body.onSubscribe(subscription);
        body.onNext(List.of(ByteBuffer.wrap(new byte[]{1,2}),ByteBuffer.wrap(new byte[]{3,4})));
        assertThat(body.getBody().toCompletableFuture()).isNotDone();body.onComplete();
        assertThat(body.getBody().toCompletableFuture().join()).containsExactly((byte)1,(byte)2,(byte)3,(byte)4);
        assertThat(subscription.cancelled).isFalse();
    }
}
