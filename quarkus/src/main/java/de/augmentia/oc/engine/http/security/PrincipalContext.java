package de.augmentia.oc.engine.http.security;

import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Produces;

@RequestScoped
public class PrincipalContext {
    private Principal principal = Principal.anonymous();

    public void set(Principal principal) {
        this.principal = principal == null ? Principal.anonymous() : principal;
    }

    public Principal get() {
        return principal;
    }

    @Produces
    @RequestScoped
    public Principal principal() {
        return get();
    }
}
