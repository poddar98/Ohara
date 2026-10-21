package org.example.ohara.organization.application;

import org.example.ohara.common.security.CurrentActor;
import org.example.ohara.common.web.ApiException;
import org.example.ohara.common.web.PageResponse;
import org.example.ohara.organization.api.dto.ImportResponse;
import org.example.ohara.organization.api.dto.ImportRowResponse;
import org.example.ohara.organization.domain.BulkImportJob;
import org.example.ohara.organization.domain.BulkImportRow;
import org.example.ohara.organization.domain.Cohort;
import org.example.ohara.organization.domain.OrgRole;
import org.example.ohara.organization.infrastructure.persistence.BulkImportJobRepository;
import org.example.ohara.organization.infrastructure.persistence.BulkImportRowRepository;
import org.example.ohara.organization.infrastructure.persistence.CohortRepository;
import org.example.ohara.platform.jobs.JobRequest;
import org.example.ohara.platform.jobs.JobScheduler;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * CSV import of people into an organization. Upload = parse and validate every row (nothing is applied); confirm =
 * queue a BULK_IMPORT job that applies valid rows one by one. Columns: email (required), first_name, last_name,
 * external_ref (roll number). Header names are case-insensitive; common aliases are accepted.
 */
@Service
public class BulkImportService {

    static final int MAX_BYTES = 1024 * 1024;
    static final int MAX_ROWS = 5000;
    static final String JOB_TYPE = "BULK_IMPORT";

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Map<String, String> HEADER_ALIASES = Map.ofEntries(
        Map.entry("email", "email"), Map.entry("email_address", "email"), Map.entry("e-mail", "email"),
        Map.entry("first_name", "first_name"), Map.entry("firstname", "first_name"), Map.entry("first name", "first_name"),
        Map.entry("last_name", "last_name"), Map.entry("lastname", "last_name"), Map.entry("last name", "last_name"),
        Map.entry("external_ref", "external_ref"), Map.entry("roll_number", "external_ref"), Map.entry("roll_no", "external_ref"),
        Map.entry("roll number", "external_ref"), Map.entry("student_id", "external_ref"));

    private final BulkImportJobRepository jobs;
    private final BulkImportRowRepository rows;
    private final CohortRepository cohorts;
    private final OrgAuthorizationService authorization;
    private final JobScheduler jobScheduler;

    public BulkImportService(BulkImportJobRepository jobs, BulkImportRowRepository rows, CohortRepository cohorts,
                             OrgAuthorizationService authorization, JobScheduler jobScheduler) {
        this.jobs = jobs;
        this.rows = rows;
        this.cohorts = cohorts;
        this.authorization = authorization;
        this.jobScheduler = jobScheduler;
    }

    public record ImportWork(UUID importId) {
    }

    @Transactional
    public ImportResponse upload(CurrentActor actor, UUID organizationId, String fileName, byte[] content,
                                 BulkImportJob.Mode mode, OrgRole role, UUID cohortId) {
        authorization.require(actor, organizationId, OrgPermission.MANAGE_MEMBERS);
        if (content.length == 0) {
            throw new ApiException.BadRequest("empty_file", "The file is empty");
        }
        if (content.length > MAX_BYTES) {
            throw new ApiException.BadRequest("file_too_large", "Files are limited to 1 MB");
        }
        OrgRole effectiveRole = role == null ? OrgRole.LEARNER : role;
        if (cohortId != null) {
            if (effectiveRole != OrgRole.LEARNER) {
                throw new ApiException.BadRequest("cohort_requires_learner", "Only learners can be imported into a cohort");
            }
            Cohort cohort = cohorts.findByIdAndOrganizationId(cohortId, organizationId)
                .orElseThrow(() -> new ApiException.NotFound("cohort_not_found", "Cohort not found"));
            if (!cohort.acceptsMembers()) {
                throw new ApiException.Conflict("cohort_closed", "The cohort is " + cohort.getStatus().name().toLowerCase());
            }
        }
        String sha256 = sha256(content);
        if (jobs.existsByOrganizationIdAndFileSha256AndStatusNot(organizationId, sha256, BulkImportJob.Status.CANCELLED)) {
            throw new ApiException.Conflict("duplicate_file", "This file was already imported; cancel the earlier import to re-run it");
        }

        List<List<String>> records = parseCsv(content);
        Map<String, Integer> columns = columns(records.getFirst());
        List<List<String>> data = records.subList(1, records.size());
        if (data.isEmpty()) {
            throw new ApiException.BadRequest("no_rows", "The file has a header but no rows");
        }
        if (data.size() > MAX_ROWS) {
            throw new ApiException.BadRequest("too_many_rows", "Imports are limited to " + MAX_ROWS + " rows");
        }

        BulkImportJob job = jobs.save(new BulkImportJob(organizationId, cohortId, mode, effectiveRole,
            fileName == null || fileName.isBlank() ? "import.csv" : fileName, sha256, actor.userId()));
        List<BulkImportRow> parsed = validate(job, data, columns, mode);
        rows.saveAll(parsed);
        job.recordValidation(parsed.size(), (int) parsed.stream().filter(r -> !r.isInvalid()).count());
        return ImportResponse.of(job);
    }

    @Transactional(readOnly = true)
    public List<ImportResponse> list(CurrentActor actor, UUID organizationId) {
        authorization.require(actor, organizationId, OrgPermission.VIEW_MEMBERS);
        return jobs.findTop50ByOrganizationIdOrderByCreatedAtDesc(organizationId).stream().map(ImportResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public ImportResponse get(CurrentActor actor, UUID organizationId, UUID importId) {
        authorization.require(actor, organizationId, OrgPermission.VIEW_MEMBERS);
        return ImportResponse.of(job(organizationId, importId));
    }

    @Transactional(readOnly = true)
    public PageResponse<ImportRowResponse> rows(CurrentActor actor, UUID organizationId, UUID importId,
                                                BulkImportRow.Status status, int page, int size) {
        authorization.require(actor, organizationId, OrgPermission.VIEW_MEMBERS);
        job(organizationId, importId);
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), PageResponse.clampSize(size));
        return PageResponse.of(status == null
            ? rows.findByJobIdOrderByRowNumber(importId, pageRequest)
            : rows.findByJobIdAndStatusInOrderByRowNumber(importId, Set.of(status), pageRequest), ImportRowResponse::of);
    }

    /** Rows that need attention (invalid or failed), as CSV the admin can fix and re-upload. */
    @Transactional(readOnly = true)
    public String errorReport(CurrentActor actor, UUID organizationId, UUID importId) {
        authorization.require(actor, organizationId, OrgPermission.VIEW_MEMBERS);
        job(organizationId, importId);
        StringBuilder csv = new StringBuilder("row_number,email,first_name,last_name,external_ref,error_code,error_detail\n");
        for (BulkImportRow row : rows.findByJobIdAndStatusInOrderByRowNumber(importId,
                EnumSet.of(BulkImportRow.Status.INVALID, BulkImportRow.Status.FAILED))) {
            csv.append(row.getRowNumber()).append(',')
                .append(csvField(row.getEmail())).append(',')
                .append(csvField(row.getFirstName())).append(',')
                .append(csvField(row.getLastName())).append(',')
                .append(csvField(row.getExternalRef())).append(',')
                .append(csvField(row.getErrorCode())).append(',')
                .append(csvField(row.getErrorDetail())).append('\n');
        }
        return csv.toString();
    }

    @Transactional
    public ImportResponse confirm(CurrentActor actor, UUID organizationId, UUID importId) {
        authorization.require(actor, organizationId, OrgPermission.MANAGE_MEMBERS);
        BulkImportJob job = job(organizationId, importId);
        if (job.getStatus() != BulkImportJob.Status.VALIDATED) {
            throw new ApiException.Conflict("import_not_confirmable", "The import is " + job.getStatus().name().toLowerCase());
        }
        job.confirm();
        jobScheduler.enqueue(JobRequest.of(JOB_TYPE, JOB_TYPE + ":" + importId, new ImportWork(importId)).withMaxAttempts(10));
        return ImportResponse.of(job);
    }

    /** Before confirmation nothing was applied; during processing, rows already applied stay applied. */
    @Transactional
    public ImportResponse cancel(CurrentActor actor, UUID organizationId, UUID importId) {
        authorization.require(actor, organizationId, OrgPermission.MANAGE_MEMBERS);
        BulkImportJob job = job(organizationId, importId);
        if (job.getStatus() != BulkImportJob.Status.VALIDATED && job.getStatus() != BulkImportJob.Status.PROCESSING) {
            throw new ApiException.Conflict("import_not_cancellable", "The import is " + job.getStatus().name().toLowerCase());
        }
        boolean wasValidated = job.getStatus() == BulkImportJob.Status.VALIDATED;
        job.cancel();
        if (wasValidated) {
            rows.cancelPending(importId);
        }
        return ImportResponse.of(job);
    }

    private BulkImportJob job(UUID organizationId, UUID importId) {
        return jobs.findByIdAndOrganizationId(importId, organizationId)
            .orElseThrow(() -> new ApiException.NotFound("import_not_found", "Import not found"));
    }

    // --- parsing & validation ----------------------------------------------------------------------

    private static List<List<String>> parseCsv(byte[] content) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException ex) {
            throw new ApiException.BadRequest("not_utf8", "The file must be UTF-8 encoded CSV");
        }
        try {
            List<List<String>> records = CsvParser.parse(text);
            if (records.isEmpty()) {
                throw new ApiException.BadRequest("empty_file", "The file is empty");
            }
            return records;
        } catch (CsvParser.MalformedCsvException ex) {
            throw new ApiException.BadRequest("malformed_csv", ex.getMessage());
        }
    }

    private static Map<String, Integer> columns(List<String> header) {
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String canonical = HEADER_ALIASES.get(header.get(i).trim().toLowerCase(Locale.ROOT));
            if (canonical != null) {
                columns.putIfAbsent(canonical, i);
            }
        }
        if (!columns.containsKey("email")) {
            throw new ApiException.BadRequest("missing_email_column",
                "The header must include an 'email' column (optional: first_name, last_name, external_ref)");
        }
        return columns;
    }

    private static List<BulkImportRow> validate(BulkImportJob job, List<List<String>> data, Map<String, Integer> columns,
                                                BulkImportJob.Mode mode) {
        Set<String> seenEmails = new HashSet<>();
        Set<String> seenRefs = new HashSet<>();
        List<BulkImportRow> result = new ArrayList<>(data.size());
        for (int i = 0; i < data.size(); i++) {
            List<String> record = data.get(i);
            String email = cell(record, columns.get("email"));
            String firstName = cell(record, columns.get("first_name"));
            String lastName = cell(record, columns.get("last_name"));
            String externalRef = cell(record, columns.get("external_ref"));
            String normalizedEmail = email == null ? null : email.toLowerCase(Locale.ROOT);

            BulkImportRow row = new BulkImportRow(job.getId(), i + 2, normalizedEmail, firstName, lastName, externalRef);
            if (email == null || email.length() > 320 || !EMAIL.matcher(email).matches()) {
                row.invalid("INVALID_EMAIL", "Not a valid email address");
            } else if (!seenEmails.add(normalizedEmail)) {
                row.invalid("DUPLICATE_IN_FILE", "This email appears earlier in the file");
            } else if (mode == BulkImportJob.Mode.CREATE_ACCOUNTS && (firstName == null || lastName == null)) {
                row.invalid("MISSING_NAME", "first_name and last_name are required to create accounts");
            } else if ((firstName != null && firstName.length() > 100) || (lastName != null && lastName.length() > 100)) {
                row.invalid("NAME_TOO_LONG", "Names are limited to 100 characters");
            } else if (externalRef != null && externalRef.length() > 64) {
                row.invalid("EXTERNAL_REF_TOO_LONG", "external_ref is limited to 64 characters");
            } else if (externalRef != null && !seenRefs.add(externalRef)) {
                row.invalid("DUPLICATE_EXTERNAL_REF", "This external_ref appears earlier in the file");
            }
            result.add(row);
        }
        return result;
    }

    private static String cell(List<String> record, Integer index) {
        if (index == null || index >= record.size()) {
            return null;
        }
        String value = record.get(index).trim();
        return value.isEmpty() ? null : value;
    }

    private static String csvField(String value) {
        if (value == null) {
            return "";
        }
        // Neutralise spreadsheet formulas in values that came from user input.
        String safe = !value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        return safe.contains(",") || safe.contains("\"") || safe.contains("\n")
            ? "\"" + safe.replace("\"", "\"\"") + "\""
            : safe;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
