package com.novaforge.gateway.ui;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
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
 *
 * <p>This controller owns the whole {@code /runtime/**} + {@code /builder/**}
 * trees (it outranks the default static handler): a request that names a real
 * file under the bundle tree serves that file — with the filename's media type,
 * never content-negotiated (found live wiring the PHASE-2 §11 golden journey:
 * asset URLs fell through to the shell and browsers rejected the JSON-typed
 * module) — everything else is the SPA's shell document for its own prefix
 * (also found live: the shell picked by request param never bound, so /builder
 * served the runtime shell).</p>
 */
@Controller
public class SpaFallbackController {

    private static final String RUNTIME = "runtime";
    private static final String BUILDER = "builder";

    @GetMapping({"/", "/runtime", "/runtime/", "/runtime/**", "/builder", "/builder/", "/builder/**"})
    public ResponseEntity<Resource> spa(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String base = uri.startsWith("/" + BUILDER) ? BUILDER : RUNTIME;

        // A real file in the bundle tree (…/assets/x.js, favicon) serves as itself.
        if (uri.length() > ("/" + base).length() + 1) {
            String rest = uri.substring(("/" + base + "/").length());
            if (!rest.isEmpty()) {
                Resource file = resolve("/static/" + base + "/" + rest);
                if (file != null && file.exists() && file.isReadable()) {
                    MediaType type = mediaTypeOf(rest);
                    return ResponseEntity.ok().contentType(type).body(file);
                }
            }
        }
        // Deep link (or the prefix itself) → the SPA's shell document.
        Resource shell = resolve("/static/" + base + "/index.html");
        return shell != null && shell.exists()
                ? ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(shell)
                : ResponseEntity.notFound().build();
    }

    /** The filename's media type (JS modules must be JS — never negotiated JSON). */
    private static MediaType mediaTypeOf(String path) {
        return org.springframework.http.MediaTypeFactory
                .getMediaType(path)
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
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
