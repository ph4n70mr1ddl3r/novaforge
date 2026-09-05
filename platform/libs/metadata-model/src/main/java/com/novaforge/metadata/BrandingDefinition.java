package com.novaforge.metadata;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Tenant branding (ADR-009 §5): the presentation overrides a published app
 * carries to its runtime surface. Values ride the design tokens — the shell
 * maps them onto {@code --nf-color-accent} / {@code --nf-color-accent-contrast}
 * on its root; no component ever reads a raw color outside the token layer, so
 * light/dark and the accent re-map together.
 *
 * @param accent          the tenant accent, any CSS color (token override)
 * @param accentContrast  the readable foreground on accent surfaces — supply it
 *                        whenever the accent is light in light mode (WCAG 2.2 AA)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BrandingDefinition(String accent, String accentContrast) {
}
