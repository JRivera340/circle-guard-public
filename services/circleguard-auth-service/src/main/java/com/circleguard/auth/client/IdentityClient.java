package com.circleguard.auth.client;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import java.util.*;

@Component
public class IdentityClient {
    // In a real microservice, this would use Feign or WebClient
    private final RestTemplate restTemplate = new RestTemplate();
    
    @org.springframework.beans.factory.annotation.Value("${identity.service.url:http://localhost:8083}")
    private String identityServiceUrl;

    public UUID getAnonymousId(String realIdentity) {
        Map<String, String> request = Map.of("realIdentity", realIdentity);
        Map response = restTemplate.postForObject(identityServiceUrl + "/api/v1/identities/map", request, Map.class);
        return UUID.fromString(response.get("anonymousId").toString());
    }
}
