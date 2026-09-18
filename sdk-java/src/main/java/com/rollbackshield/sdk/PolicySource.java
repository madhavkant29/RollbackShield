package com.rollbackshield.sdk;

/**
 * Fetches the current policy snapshot for a contract. Implemented by an
 * adapter (e.g. an HTTP client against the control plane's policy endpoint).
 * Deliberately not specified further here — the evaluation hot path never
 * calls this directly; only {@link PolicyCache}'s background refresh does.
 */
public interface PolicySource {

    PolicySnapshot fetch(String contractId) throws PolicyFetchException;

    class PolicyFetchException extends Exception {
        public PolicyFetchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
