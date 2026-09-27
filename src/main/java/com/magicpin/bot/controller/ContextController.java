package com.magicpin.bot.controller;

import com.magicpin.bot.repository.InMemoryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping({"", "/v1"})
@RequiredArgsConstructor
public class ContextController {

    private final InMemoryStore store;

    @PostMapping("/context")
    public Map<String, Object> save(@RequestBody Map<String, Object> req) {
        String scope     = requiredString(req, "scope");
        String contextId = requiredString(req, "context_id");

        // Key format must be "scope:context_id" — TickController looks up "trigger:trg_xxx"
        store.saveContext(scope, contextId, req);

        return Map.of("accepted", true);
    }

    private String requiredString(Map<String, Object> request, String field) {
        Object value = request == null ? null : request.get(field);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    field + " must be a non-empty string"
            );
        }
        return string;
    }
}
