package org.example.ohara.common.web;

/** A response for one versioned resource: its version is sent as the ETag (see {@link Etags}). */
public interface Versioned {

    long version();
}
