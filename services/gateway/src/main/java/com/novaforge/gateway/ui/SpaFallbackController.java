package com.novaforge.gateway.ui;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Same-origin SPA hosting (PHASE-2 §13 Q5, resolved): the browser bundles deploy
 * as static assets behind the gateway — {@code /static/runtime/**} and
 * {@code /static/builder/**} (classpath default, filesystem override for volume
 * mounts) — so the SPAs call the APIs same-origin and gateway CORS stays deferred
 * (PHASE-0 §6.1). Deep links ({@code /runtime/orders/123}) fall back to the
 * matching shell document; client routing takes over from there.
 */
@Controller
public class SpaFallbackController {

    private static final String RUNTIME = "/static/runtime/index.html";
    private static final String BUILDER = "/static/builder/index.html";

    @GetMapping({"/", "/runtime", "/runtime/**", "/builder", "/builder/**"})
    public ResponseEntity<Resource> spa(String path) {
        String document = path != null && path.startsWith("/builder") ? BUILDER : RUNTIME;
        Resource resource = resolve(document);
        return resource != null && resource.exists()
                ? ResponseEntity.ok().body(resource)
                : ResponseEntity.notFound().build();
    }

    private Resource resolve(String location) {
        String relative = location.replaceFirst("^/static/", "");
        Resource classPath = new ClassPathResource("static/" + relative);
        if (classPath.exists()) {
            return classPath;
        }
        return new FileSystemResource("/static/" + relative);
    }
}
