package biz.thonbecker.personal.foosball.platform.tenant;

import biz.thonbecker.personal.foosball.platform.persistence.TenantRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.hibernate.Session;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Resolves the tenant URL segment and enables Hibernate's tenant filter. */
@Component
public class FoosballTenantFilter extends OncePerRequestFilter {

    private final TenantRepository tenantRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public FoosballTenantFilter(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        final var tenantSlug = resolveTenantSlug(request.getRequestURI());
        if (tenantSlug == null) {
            filterChain.doFilter(request, response);
            return;
        }

        final var tenant = tenantRepository.findBySlug(tenantSlug);
        if (tenant.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "Foosball tenant not found");
            return;
        }

        TenantContext.set(tenant.get().getId());
        try {
            request.setAttribute("foosballTenantSlug", tenant.get().getSlug());
            entityManager
                    .unwrap(Session.class)
                    .enableFilter("foosballTenant")
                    .setParameter("tenantId", tenant.get().getId());
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private String resolveTenantSlug(String requestUri) {
        final var webPrefix = "/foosball/";
        final var restPrefix = "/api/foosball/";
        final var tournamentPrefix = "/api/tournaments/";
        final String slug;
        if (requestUri.startsWith(webPrefix)) {
            slug = requestUri.substring(webPrefix.length()).split("/", 2)[0];
        } else if (requestUri.startsWith(restPrefix)) {
            slug = requestUri.substring(restPrefix.length()).split("/", 2)[0];
        } else if (requestUri.startsWith(tournamentPrefix)) {
            slug = requestUri.substring(tournamentPrefix.length()).split("/", 2)[0];
        } else {
            return null;
        }
        return slug.isBlank() ? null : slug;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }
}
