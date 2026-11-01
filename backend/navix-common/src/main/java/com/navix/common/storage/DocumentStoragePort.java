package com.navix.common.storage;

/**
 * Port for object storage, implemented by {@code StorageAdapter} in
 * {@code navix-storage} (delegating to {@code DocumentStorageService}) and
 * consumed by the loan aggregate. Keeps the AWS S3 SDK off the loan classpath.
 *
 * <p>Large bytes never transit the app on download: callers hand the borrower a
 * short-lived <b>presigned URL</b>. Server-side ingest ({@link #store} /
 * {@link #storeFromUrl}) is used only where bytes are fetched provider→app→S3
 * (e.g. the DigiLocker Aadhaar PDF), never browser→app.
 */
public interface DocumentStoragePort {

    /**
     * Deterministic, collision-safe key for an application artifact:
     * {@code applications/{applicationId}/{docType}/{timestamp}.{ext}}.
     */
    String buildApplicationKey(Long applicationId, String docType, String ext);

    /** Presigned URL for a direct browser PUT upload to {@code key}. */
    String presignUpload(String key, String contentType);

    /** Presigned URL for a direct GET download of {@code key} (short TTL). */
    String presignDownload(String key);

    /** Presigned GET of {@code key} valid for {@code ttl} (loan documents: 5 minutes). */
    String presignDownload(String key, java.time.Duration ttl);

    /** Server-side upload of bytes already in hand. */
    void store(String key, byte[] content, String contentType);

    /**
     * Fetch the bytes at {@code sourceUrl} (e.g. a provider's 10-minute presigned
     * URL) and store them at {@code key}. Bytes pass app→S3 only, never to the
     * browser. Returns the stored key.
     */
    String storeFromUrl(String key, String sourceUrl, String contentType);

    /** True if an object exists at {@code key}. */
    boolean exists(String key);

    /** Client uploads (PLAN Phase 11): PDF, JPG or PNG, at most 5 MB. */
    long MAX_UPLOAD_BYTES = 5L * 1024 * 1024;
    java.util.Set<String> UPLOAD_TYPES = java.util.Set.of("application/pdf", "image/jpeg", "image/png");

    /** Size and content type of a stored object. */
    record ObjectInfo(long sizeBytes, String contentType) {
    }

    /** The stored object's size and type (an S3 HEAD); empty when nothing is stored at {@code key}. */
    java.util.Optional<ObjectInfo> head(String key);

    /** Refuse a presign for anything but PDF, JPG or PNG ({@code UNSUPPORTED_FILE_TYPE}). */
    static void requireUploadType(String contentType) {
        String type = contentType == null ? "" : contentType.trim().toLowerCase(java.util.Locale.ROOT);
        if (!UPLOAD_TYPES.contains(type)) {
            throw new com.navix.common.exception.BusinessException("UNSUPPORTED_FILE_TYPE",
                    "Upload a PDF, JPG or PNG file");
        }
    }

    /**
     * The server-side half of the upload rules, run when a client hands back a key: the object is there, at most
     * 5 MB, and stored as PDF, JPG or PNG. A presigned PUT cannot cap the size, so this HEAD is the real check;
     * the content type is signed into the PUT, so what S3 holds is what the presign allowed.
     */
    default void requireUploaded(String key) {
        java.util.Optional<ObjectInfo> stored;
        try {
            stored = head(key);
        } catch (RuntimeException storageFailure) {
            // Fail closed, but as a retryable answer rather than a 500 (same wording as a failed presign).
            throw new com.navix.common.exception.BusinessException("UPLOAD_UNAVAILABLE",
                    "We can't accept uploads just now. Please try again in a few minutes.");
        }
        ObjectInfo info = stored.orElseThrow(() -> new com.navix.common.exception.BusinessException(
                "UPLOAD_NOT_FOUND", "We could not find that file. Please upload it again."));
        if (info.sizeBytes() > MAX_UPLOAD_BYTES) {
            throw new com.navix.common.exception.BusinessException("FILE_TOO_LARGE", "Files must be 5 MB or smaller");
        }
        requireUploadType(info.contentType());
    }

    /**
     * Server-side download of the bytes at {@code key} — used only where the app itself needs the
     * content (e.g. attaching a signed PDF to an email), never for a browser-facing read (that stays
     * on {@link #presignDownload}). Throws on a missing key or transport error.
     */
    byte[] fetch(String key);
}
