package com.mordor.kelly.kelsy.storage;

/** A storage error must never be interpreted as an empty search result. */
public final class StorageException extends RuntimeException {
    public enum Code { INVALID_ARGUMENT, NOT_FOUND, CONFLICT, IDENTITY_CONFLICT, PATH_REJECTED,
        TOO_LARGE, IO_ERROR, STORE_UNAVAILABLE, INDEX_UNAVAILABLE, UNSUPPORTED_SCHEMA, CLOSED }
    private final Code code;
    public StorageException(Code code, String message) { super(message); this.code = code; }
    public StorageException(Code code, String message, Throwable cause) { super(message, cause); this.code = code; }
    public Code code() { return code; }
}
