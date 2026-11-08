package com.inverseek.systemtests.support;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleResponse;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

public final class OpenApi {
    private final Map<String, OpenApiInteractionValidator> validators = new HashMap<>();
    public OpenApi(Path root) {
        for (String service : java.util.List.of("ingestion", "indexing", "search", "control")) {
            String module = service.equals("control") ? "control-module" : service + "-service";
            validators.put(service, OpenApiInteractionValidator.createForSpecificationUrl(
                    root.resolve(module + "/src/main/resources/openapi.yaml").toUri().toString()).build());
        }
    }
    public void validate(String service, String path, String method, HttpResponse<String> response) {
        validate(service, path, method, response.statusCode(), response.headers().map(), response.body());
    }
    public void validate(String service, String path, String method, int status,
                         Map<String, java.util.List<String>> headers, String body) {
        var builder = SimpleResponse.Builder.status(status).withBody(body);
        headers.forEach((name, values) -> builder.withHeader(name, values.toArray(String[]::new)));
        var report = validators.get(service).validateResponse(path.split("\\?", 2)[0],
                Request.Method.valueOf(method), builder.build());
        assertFalse(report.hasErrors(), () -> service + " " + method + " " + path + " HTTP " + status + ": " + report);
    }
}
