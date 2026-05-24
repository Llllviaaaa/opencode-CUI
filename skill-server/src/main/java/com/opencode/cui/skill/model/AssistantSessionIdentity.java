package com.opencode.cui.skill.model;

/**
 * Assistant identity for session routing.
 *
 * <p>Session lookup keys are not the same for every assistant kind:
 * local assistants have a real AK, while remote and default assistants are
 * identified by assistantAccount for business-session reuse.</p>
 */
public record AssistantSessionIdentity(
        String ak,
        String gatewayUserId,
        String assistantAccount,
        RouteKind routeKind) {

    public enum RouteKind {
        LOCAL,
        REMOTE,
        DEFAULT
    }

    public AssistantSessionIdentity {
        routeKind = routeKind != null ? routeKind : RouteKind.LOCAL;
    }

    public static AssistantSessionIdentity fromResolveOutcome(ResolveOutcome outcome,
            String requestedAssistantAccount) {
        String resolvedAccount = firstNonBlank(outcome.assistantAccount(), requestedAssistantAccount);
        RouteKind kind = outcome.remote() ? RouteKind.REMOTE : RouteKind.LOCAL;
        return new AssistantSessionIdentity(outcome.ak(), outcome.ownerWelinkId(), resolvedAccount, kind);
    }

    public static AssistantSessionIdentity local(String ak, String gatewayUserId, String assistantAccount) {
        return new AssistantSessionIdentity(ak, gatewayUserId, assistantAccount, RouteKind.LOCAL);
    }

    public static AssistantSessionIdentity defaultAssistant(String ak, String gatewayUserId,
            String assistantAccount) {
        return new AssistantSessionIdentity(ak, gatewayUserId, assistantAccount, RouteKind.DEFAULT);
    }

    public String lookupAk() {
        return routeKind == RouteKind.LOCAL ? ak : null;
    }

    public String lookupAssistantAccount() {
        return assistantAccount;
    }

    public boolean defaultAssistant() {
        return routeKind == RouteKind.DEFAULT;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
