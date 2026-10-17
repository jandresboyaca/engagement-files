package com.caseware.fanout.application.fake;

import com.caseware.fanout.application.port.out.LoadEngagementPort;
import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.model.LoadOutcome;
import com.caseware.fanout.domain.model.WorkItemId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Answers from a script per engagement id; the last scripted outcome repeats. Unscripted
 * engagements answer 204. Records every call.
 */
public final class FakeLoadEngagement implements LoadEngagementPort {

    private static final LoadOutcome DEFAULT = new LoadOutcome.Answered(204);

    private final Map<EngagementId, Deque<LoadOutcome>> scripts = new HashMap<>();
    private final List<EngagementId> calls = new ArrayList<>();

    public synchronized FakeLoadEngagement script(EngagementId engagementId, LoadOutcome... outcomes) {
        scripts.put(engagementId, new ArrayDeque<>(List.of(outcomes)));
        return this;
    }

    @Override
    public synchronized LoadOutcome load(WorkItemId workItemId, FirmId firmId, EngagementId engagementId) {
        calls.add(engagementId);
        Deque<LoadOutcome> script = scripts.get(engagementId);
        if (script == null || script.isEmpty()) {
            return DEFAULT;
        }
        return script.size() == 1 ? script.peek() : script.poll();
    }

    public synchronized List<EngagementId> calls() {
        return List.copyOf(calls);
    }

    public synchronized long callsFor(EngagementId engagementId) {
        return calls.stream().filter(engagementId::equals).count();
    }
}
