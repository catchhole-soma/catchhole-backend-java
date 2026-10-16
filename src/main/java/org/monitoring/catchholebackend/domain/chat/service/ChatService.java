package org.monitoring.catchholebackend.domain.chat.service;

import org.monitoring.catchholebackend.domain.chat.dto.ChatContract.*;

import java.util.*;

public interface ChatService {
    View submit(Long member, UUID work, String key, Submit request);

    View get(Long member, UUID work, UUID question);

    View retry(Long member, UUID work, UUID question, Retry request);

    Context start(UUID id, String token, UUID runner);

    Context context(UUID id, String token, UUID runner);

    View finish(UUID id, String token, UUID runner, Finish request);

    CallView reserve(UUID id, String token, UUID runner, Reserve request);

    CallView report(UUID id, String token, UUID runner, UUID call, Usage request);

    Dispatch operation(UUID id, Operation request);

    OperationalReadiness operationalReadiness();
}
