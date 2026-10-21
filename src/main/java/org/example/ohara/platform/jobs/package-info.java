/** Durable background jobs: modules enqueue work in their own transaction and register handlers for their job types. */
@NamedInterface("jobs")
package org.example.ohara.platform.jobs;

import org.springframework.modulith.NamedInterface;
