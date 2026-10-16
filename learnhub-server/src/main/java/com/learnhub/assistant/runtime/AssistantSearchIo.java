package com.learnhub.assistant.runtime;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseListener;
import org.elasticsearch.client.RestClient;

public final class AssistantSearchIo {
    private AssistantSearchIo() { }

    public static Response perform(RestClient client, Request request) throws IOException {
        var scope = AssistantIoScope.current();
        if (scope == null) return client.performRequest(request);
        var remaining = scope.remaining();
        var future = new CompletableFuture<Response>();
        var pending = client.performRequestAsync(request, new ResponseListener() {
            @Override public void onSuccess(Response response) { future.complete(response); }
            @Override public void onFailure(Exception exception) { future.completeExceptionally(exception); }
        });
        try (var registration = scope.onCancel(() -> { pending.cancel(); future.cancel(true); })) {
            return future.get(remaining.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException error) {
            pending.cancel(); Thread.currentThread().interrupt();
            throw new IOException("助理检索已取消");
        } catch (TimeoutException error) {
            pending.cancel();
            throw new IOException("助理检索已超时");
        } catch (ExecutionException error) {
            if (error.getCause() instanceof IOException io) throw io;
            throw new IOException("助理检索失败");
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException("助理检索已取消"); }
    }
}
