package com.example.identityinspector.event;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class EventHub {
    private final CopyOnWriteArrayList<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
    private final ArrayDeque<InspectorEvent> history = new ArrayDeque<>();

    public synchronized List<InspectorEvent> history() {
        return List.copyOf(history);
    }

    public SseEmitter subscribe() {
        var emitter = new SseEmitter(0L);
        subscribers.add(emitter);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError(e -> subscribers.remove(emitter));
        try {
            emitter.send(SseEmitter.event().name("ready").data("connected"));
        } catch (IOException e) {
            subscribers.remove(emitter);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    public void publish(InspectorEvent incoming) {
        var event = incoming.withTimestamp();
        synchronized (this) {
            if (history.size() == 200) history.removeFirst();
            history.addLast(event);
        }
        for (var emitter : subscribers) {
            try {
                emitter.send(SseEmitter.event().name("inspector-event").data(event));
            } catch (Exception e) {
                subscribers.remove(emitter);
                emitter.complete();
            }
        }
    }

    public synchronized void clear() {
        history.clear();
    }
}
