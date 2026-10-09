package com.yourcompany.surveyai.operation.application.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourcompany.surveyai.auth.application.RequestAuthContext;
import com.yourcompany.surveyai.call.domain.entity.CallAttempt;
import com.yourcompany.surveyai.call.domain.entity.CallJob;
import com.yourcompany.surveyai.call.domain.enums.CallAttemptStatus;
import com.yourcompany.surveyai.call.domain.enums.CallJobStatus;
import com.yourcompany.surveyai.call.domain.enums.CallProvider;
import com.yourcompany.surveyai.call.repository.CallAttemptRepository;
import com.yourcompany.surveyai.call.repository.CallJobRepository;
import com.yourcompany.surveyai.common.domain.entity.AppUser;
import com.yourcompany.surveyai.common.domain.entity.Company;
import com.yourcompany.surveyai.common.exception.NotFoundException;
import com.yourcompany.surveyai.common.exception.ValidationException;
import com.yourcompany.surveyai.common.repository.CompanyRepository;
import com.yourcompany.surveyai.operation.application.dto.response.ImportedSurveyOperationResponseDto;
import com.yourcompany.surveyai.operation.application.service.ImportedSurveyOperationService;
import com.yourcompany.surveyai.operation.domain.entity.Operation;
import com.yourcompany.surveyai.operation.domain.entity.OperationContact;
import com.yourcompany.surveyai.operation.domain.enums.OperationContactStatus;
import com.yourcompany.surveyai.operation.domain.enums.OperationSourceType;
import com.yourcompany.surveyai.operation.domain.enums.OperationStatus;
import com.yourcompany.surveyai.operation.repository.OperationContactRepository;
import com.yourcompany.surveyai.operation.repository.OperationRepository;
import com.yourcompany.surveyai.response.domain.entity.SurveyAnswer;
import com.yourcompany.surveyai.response.domain.entity.SurveyResponse;
import com.yourcompany.surveyai.response.domain.enums.SurveyResponseStatus;
import com.yourcompany.surveyai.response.repository.SurveyAnswerRepository;
import com.yourcompany.surveyai.response.repository.SurveyResponseRepository;
import com.yourcompany.surveyai.survey.domain.entity.Survey;
import com.yourcompany.surveyai.survey.domain.entity.SurveyQuestion;
import com.yourcompany.surveyai.survey.domain.entity.SurveyQuestionOption;
import com.yourcompany.surveyai.survey.domain.enums.QuestionType;
import com.yourcompany.surveyai.survey.domain.enums.SurveyStatus;
import com.yourcompany.surveyai.survey.repository.SurveyQuestionOptionRepository;
import com.yourcompany.surveyai.survey.repository.SurveyQuestionRepository;
import com.yourcompany.surveyai.survey.repository.SurveyRepository;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ImportedSurveyOperationServiceImpl implements ImportedSurveyOperationService {

    private static final Set<String> NAME_HEADERS = Set.of("respondent_name", "full_name", "name", "ad_soyad", "adsoyad");
    private static final Set<String> PHONE_HEADERS = Set.of("respondent_phone", "phone", "phone_number", "telefon", "telefon_numarasi", "gsm");
    private static final Set<String> START_HEADERS = Set.of("started_at", "start_time", "gorusme_baslangic");
    private static final Set<String> COMPLETE_HEADERS = Set.of("completed_at", "completion_time", "response_date", "gorusme_bitis");
    private static final Set<String> EXTERNAL_HEADERS = Set.of("respondent_id", "external_ref", "contact_id", "respondent_code");
    private static final Set<String> SUMMARY_HEADERS = Set.of("summary", "ai_summary", "ozet");
    private static final Set<String> TRANSCRIPT_HEADERS = Set.of("transcript", "notes", "notlar", "aciklama");

    private final CompanyRepository companyRepository;
    private final SurveyRepository surveyRepository;
    private final SurveyQuestionRepository surveyQuestionRepository;
    private final SurveyQuestionOptionRepository surveyQuestionOptionRepository;
    private final OperationRepository operationRepository;
    private final OperationContactRepository operationContactRepository;
    private final CallJobRepository callJobRepository;
    private final CallAttemptRepository callAttemptRepository;
    private final SurveyResponseRepository surveyResponseRepository;
    private final SurveyAnswerRepository surveyAnswerRepository;
    private final RequestAuthContext requestAuthContext;
    private final ObjectMapper objectMapper;
    private final DataFormatter formatter = new DataFormatter();

    public ImportedSurveyOperationServiceImpl(
            CompanyRepository companyRepository,
            SurveyRepository surveyRepository,
            SurveyQuestionRepository surveyQuestionRepository,
            SurveyQuestionOptionRepository surveyQuestionOptionRepository,
            OperationRepository operationRepository,
            OperationContactRepository operationContactRepository,
            CallJobRepository callJobRepository,
            CallAttemptRepository callAttemptRepository,
            SurveyResponseRepository surveyResponseRepository,
            SurveyAnswerRepository surveyAnswerRepository,
            RequestAuthContext requestAuthContext,
            ObjectMapper objectMapper
    ) {
        this.companyRepository = companyRepository;
        this.surveyRepository = surveyRepository;
        this.surveyQuestionRepository = surveyQuestionRepository;
        this.surveyQuestionOptionRepository = surveyQuestionOptionRepository;
        this.operationRepository = operationRepository;
        this.operationContactRepository = operationContactRepository;
        this.callJobRepository = callJobRepository;
        this.callAttemptRepository = callAttemptRepository;
        this.surveyResponseRepository = surveyResponseRepository;
        this.surveyAnswerRepository = surveyAnswerRepository;
        this.requestAuthContext = requestAuthContext;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public ImportedSurveyOperationResponseDto importCompletedSurvey(UUID companyId, UUID surveyId, MultipartFile file, String operationName) {
        Company company = companyRepository.findById(companyId).orElseThrow(() -> new NotFoundException("Company not found: " + companyId));
        Survey survey = surveyRepository.findByIdAndCompany_IdAndDeletedAtIsNull(surveyId, companyId)
                .orElseThrow(() -> new NotFoundException("Survey not found for company: " + surveyId));
        if (survey.getStatus() == SurveyStatus.ARCHIVED) {
            throw new ValidationException("Arsivlenmis ankete veri eklenemez.");
        }
        if (file == null || file.isEmpty()) {
            throw new ValidationException("Ice aktarmak icin dolu bir Excel veya CSV dosyasi secin.");
        }

        List<SurveyQuestion> questions = surveyQuestionRepository.findAllBySurvey_IdAndDeletedAtIsNullOrderByQuestionOrderAsc(surveyId);
        if (questions.isEmpty()) {
            throw new ValidationException("Bu ankete veri eklemek icin once soru seti tanimlanmalidir.");
        }

        Map<UUID, List<SurveyQuestionOption>> optionsByQuestionId = surveyQuestionOptionRepository
                .findAllBySurveyQuestion_IdInAndDeletedAtIsNullOrderBySurveyQuestion_IdAscOptionOrderAsc(
                        questions.stream().map(SurveyQuestion::getId).toList()
                )
                .stream()
                .collect(Collectors.groupingBy(option -> option.getSurveyQuestion().getId(), LinkedHashMap::new, Collectors.toList()));

        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename().trim() : "survey-data-import";
        ImportedSheet sheet = parseSheet(fileName, file, questions, optionsByQuestionId);
        Operation operation = createImportedOperation(company, survey, resolveUser(companyId), sheet, operationName);
        int importedResponseCount = persistRows(company, operation, sheet);

        return new ImportedSurveyOperationResponseDto(
                survey.getId(),
                operation.getId(),
                survey.getName(),
                operation.getName(),
                questions.size(),
                importedResponseCount,
                sheet.warnings()
        );
    }

    private ImportedSheet parseSheet(String fileName, MultipartFile file, List<SurveyQuestion> questions, Map<UUID, List<SurveyQuestionOption>> optionsByQuestionId) {
        List<List<String>> matrix = switch (extension(fileName)) {
            case "xlsx", "xls" -> readExcel(file);
            case "csv" -> readCsv(file);
            default -> throw new ValidationException("Yalnizca xlsx, xls ve csv destekleniyor.");
        };
        if (matrix.size() < 2) {
            throw new ValidationException("Baslik satiri ve en az bir cevap satiri gereklidir.");
        }

        List<String> headers = matrix.get(0).stream().map(value -> value == null ? "" : value.trim()).toList();
        MetaColumns meta = findMetaColumns(headers);
        List<RowPayload> rows = new ArrayList<>();
        for (int rowIndex = 1; rowIndex < matrix.size(); rowIndex += 1) {
            Map<Integer, String> cells = new LinkedHashMap<>();
            boolean hasData = false;
            for (int columnIndex = 0; columnIndex < headers.size(); columnIndex += 1) {
                String value = columnIndex < matrix.get(rowIndex).size() ? trimToNull(matrix.get(rowIndex).get(columnIndex)) : null;
                cells.put(columnIndex, value);
                hasData = hasData || value != null;
            }
            if (hasData) {
                rows.add(new RowPayload(rowIndex + 1, cells));
            }
        }
        if (rows.isEmpty()) {
            throw new ValidationException("Dosyada ice aktarilabilir cevap satiri bulunamadi.");
        }

        List<MappedQuestionColumn> mappedColumns = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (int index = 0; index < headers.size(); index += 1) {
            if (meta.isMeta(index)) {
                continue;
            }
            String header = trimToNull(headers.get(index));
            if (header == null) {
                continue;
            }
            SurveyQuestion question = matchQuestion(header, questions);
            if (question == null) {
                warnings.add("Kolon mevcut anket sorulari ile eslesmedigi icin atlandi: " + header);
                continue;
            }
            mappedColumns.add(new MappedQuestionColumn(index, question, optionsByQuestionId.getOrDefault(question.getId(), List.of())));
        }
        if (mappedColumns.isEmpty()) {
            throw new ValidationException("Excel kolonlari mevcut anket sorulari ile eslesmedi. Kolon adlarini soru kodu veya soru basligi ile hizalayin.");
        }
        for (SurveyQuestion question : questions) {
            boolean matched = mappedColumns.stream().anyMatch(column -> column.question().getId().equals(question.getId()));
            if (!matched) {
                warnings.add("Bu soru icin import kolonu bulunamadi: " + question.getTitle());
            }
        }

        return new ImportedSheet(fileName, headers, rows, mappedColumns, meta, List.copyOf(warnings), questions.size());
    }

    private Operation createImportedOperation(Company company, Survey survey, AppUser user, ImportedSheet sheet, String operationName) {
        OffsetDateTime startedAt = sheet.rows().stream().map(row -> parseTimestamp(valueOf(row, sheet.meta().startedAt()))).filter(Objects::nonNull).min(OffsetDateTime::compareTo).orElse(OffsetDateTime.now());
        OffsetDateTime completedAt = sheet.rows().stream().map(row -> parseTimestamp(valueOf(row, sheet.meta().completedAt()))).filter(Objects::nonNull).max(OffsetDateTime::compareTo).orElse(startedAt);

        Operation operation = new Operation();
        operation.setCompany(company);
        operation.setSurvey(survey);
        operation.setName(trimToNull(operationName) != null ? operationName.trim() : survey.getName() + " - Saha Veri Import");
        operation.setStatus(OperationStatus.COMPLETED);
        operation.setSourceType(OperationSourceType.IMPORTED_SURVEY_RESULTS);
        operation.setSourcePayloadJson(buildOperationPayload(sheet, survey));
        operation.setStartedAt(startedAt);
        operation.setCompletedAt(completedAt);
        operation.setCreatedBy(user);
        return operationRepository.save(operation);
    }

    private int persistRows(Company company, Operation operation, ImportedSheet sheet) {
        int responseCount = 0;
        int syntheticIndex = 1;
        for (RowPayload row : sheet.rows()) {
            OffsetDateTime completedAt = firstNonNull(parseTimestamp(valueOf(row, sheet.meta().completedAt())), operation.getCompletedAt());
            OffsetDateTime startedAt = firstNonNull(parseTimestamp(valueOf(row, sheet.meta().startedAt())), completedAt.minusMinutes(1));

            OperationContact contact = new OperationContact();
            contact.setCompany(company);
            contact.setOperation(operation);
            contact.setExternalRef(firstNonBlank(valueOf(row, sheet.meta().externalRef()), "import-row-" + row.rowNumber()));
            contact.setPhoneNumber(firstNonBlank(valueOf(row, sheet.meta().phone()), "import-" + String.format(Locale.ROOT, "%06d", syntheticIndex)));
            contact.setFirstName(extractFirstName(valueOf(row, sheet.meta().name())));
            contact.setLastName(extractLastName(valueOf(row, sheet.meta().name())));
            contact.setMetadataJson(buildContactPayload(sheet, row));
            contact.setStatus(OperationContactStatus.COMPLETED);
            contact.setRetryCount(0);
            contact.setLastCallAt(completedAt);
            OperationContact savedContact = operationContactRepository.save(contact);

            CallJob callJob = new CallJob();
            callJob.setCompany(company);
            callJob.setOperation(operation);
            callJob.setOperationContact(savedContact);
            callJob.setStatus(CallJobStatus.COMPLETED);
            callJob.setPriority((short) 1);
            callJob.setScheduledFor(startedAt);
            callJob.setAvailableAt(startedAt);
            callJob.setAttemptCount(1);
            callJob.setMaxAttempts(1);
            callJob.setIdempotencyKey(operation.getId() + ":import:" + savedContact.getExternalRef());
            CallJob savedJob = callJobRepository.save(callJob);

            CallAttempt attempt = new CallAttempt();
            attempt.setCompany(company);
            attempt.setCallJob(savedJob);
            attempt.setOperation(operation);
            attempt.setOperationContact(savedContact);
            attempt.setAttemptNumber(1);
            attempt.setProvider(CallProvider.MANUAL);
            // No provider call exists for imported rows. A synthetic id collided with the global
            // UNIQUE (provider, provider_call_id) on every later import; NULLs do not collide.
            attempt.setProviderCallId(null);
            attempt.setStatus(CallAttemptStatus.COMPLETED);
            attempt.setDialedAt(startedAt);
            attempt.setConnectedAt(startedAt);
            attempt.setEndedAt(completedAt);
            attempt.setDurationSeconds(Math.max((int) java.time.Duration.between(startedAt, completedAt).getSeconds(), 0));
            attempt.setHangupReason("IMPORT_COMPLETED");
            attempt.setRawProviderPayload(buildAttemptPayload(sheet, row));
            CallAttempt savedAttempt = callAttemptRepository.save(attempt);

            SurveyResponse response = new SurveyResponse();
            response.setCompany(company);
            response.setSurvey(operation.getSurvey());
            response.setOperation(operation);
            response.setOperationContact(savedContact);
            response.setCallAttempt(savedAttempt);
            response.setStatus(SurveyResponseStatus.COMPLETED);
            response.setCompletionPercent(completionPercent(sheet, row));
            response.setRespondentPhone(savedContact.getPhoneNumber());
            response.setStartedAt(startedAt);
            response.setCompletedAt(completedAt);
            response.setTranscriptText(valueOf(row, sheet.meta().transcript()));
            response.setTranscriptJson(buildResponsePayload(sheet, row));
            response.setAiSummaryText(valueOf(row, sheet.meta().summary()));
            SurveyResponse savedResponse = surveyResponseRepository.save(response);

            for (MappedQuestionColumn column : sheet.mappedColumns()) {
                String rawValue = row.cells().get(column.index());
                if (rawValue != null) {
                    surveyAnswerRepository.save(buildAnswer(company, savedResponse, column, rawValue));
                }
            }
            responseCount += 1;
            syntheticIndex += 1;
        }
        return responseCount;
    }

    private SurveyAnswer buildAnswer(Company company, SurveyResponse response, MappedQuestionColumn column, String rawValue) {
        SurveyAnswer answer = new SurveyAnswer();
        answer.setCompany(company);
        answer.setSurveyResponse(response);
        answer.setSurveyQuestion(column.question());
        answer.setAnswerType(column.question().getQuestionType());
        answer.setRetryCount(0);
        answer.setConfidenceScore(BigDecimal.ONE);
        answer.setRawInputText(rawValue);
        answer.setValid(true);

        BigDecimal normalizedNumber = null;
        List<String> normalizedValues = List.of();
        if (column.question().getQuestionType() == QuestionType.OPEN_ENDED) {
            answer.setAnswerText(rawValue);
        } else if (column.question().getQuestionType() == QuestionType.NUMBER) {
            normalizedNumber = parseDecimal(rawValue);
            answer.setAnswerNumber(normalizedNumber);
            answer.setAnswerText(normalizedNumber == null ? rawValue : normalizedNumber.stripTrailingZeros().toPlainString());
            if (normalizedNumber == null) {
                answer.setValid(false);
                answer.setInvalidReason("Sayisal deger sayiya cevrilemedi");
            }
        } else if (column.question().getQuestionType() == QuestionType.RATING) {
            normalizedNumber = parseDecimal(rawValue);
            answer.setAnswerNumber(normalizedNumber);
            if (normalizedNumber == null) {
                answer.setValid(false);
                answer.setInvalidReason("Puan degeri sayiya cevrilemedi");
            }
        } else if (column.question().getQuestionType() == QuestionType.SINGLE_CHOICE) {
            SurveyQuestionOption matched = matchOption(column.options(), rawValue);
            answer.setSelectedOption(matched);
            answer.setAnswerText(matched != null ? matched.getLabel() : rawValue);
            if (matched == null) {
                answer.setValid(false);
                answer.setInvalidReason("Secenek eslestirilemedi");
            } else {
                normalizedValues = List.of(matched.getLabel());
            }
        } else {
            List<String> requestedValues = splitMulti(rawValue);
            List<String> matched = column.options().stream()
                    .filter(option -> requestedValues.stream().anyMatch(value -> normalize(value).equals(normalize(option.getLabel()))))
                    .map(SurveyQuestionOption::getLabel)
                    .toList();
            answer.setAnswerText(matched.isEmpty() ? rawValue : String.join(", ", matched));
            if (matched.isEmpty()) {
                answer.setValid(false);
                answer.setInvalidReason("Coklu secim secenekleri eslestirilemedi");
            } else {
                normalizedValues = matched;
            }
        }
        answer.setAnswerJson(buildAnswerPayload(answer, rawValue, normalizedNumber, normalizedValues));
        return answer;
    }

    private SurveyQuestion matchQuestion(String header, List<SurveyQuestion> questions) {
        String normalizedHeader = normalize(header);
        return questions.stream()
                .filter(question -> normalizedHeader.equals(normalize(question.getCode()))
                        || normalizedHeader.equals(normalize(question.getTitle()))
                        || normalizedHeader.equals(normalize("question_" + question.getQuestionOrder()))
                        || normalizedHeader.equals(normalize("soru_" + question.getQuestionOrder())))
                .findFirst()
                .orElse(null);
    }

    private MetaColumns findMetaColumns(List<String> headers) {
        Integer name = null;
        Integer phone = null;
        Integer startedAt = null;
        Integer completedAt = null;
        Integer externalRef = null;
        Integer summary = null;
        Integer transcript = null;
        for (int index = 0; index < headers.size(); index += 1) {
            String normalized = normalizeHeader(headers.get(index));
            if (normalized == null) {
                continue;
            }
            if (name == null && NAME_HEADERS.contains(normalized)) {
                name = index;
            } else if (phone == null && PHONE_HEADERS.contains(normalized)) {
                phone = index;
            } else if (startedAt == null && START_HEADERS.contains(normalized)) {
                startedAt = index;
            } else if (completedAt == null && COMPLETE_HEADERS.contains(normalized)) {
                completedAt = index;
            } else if (externalRef == null && EXTERNAL_HEADERS.contains(normalized)) {
                externalRef = index;
            } else if (summary == null && SUMMARY_HEADERS.contains(normalized)) {
                summary = index;
            } else if (transcript == null && TRANSCRIPT_HEADERS.contains(normalized)) {
                transcript = index;
            }
        }
        return new MetaColumns(name, phone, startedAt, completedAt, externalRef, summary, transcript);
    }

    private List<List<String>> readExcel(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream(); Workbook workbook = WorkbookFactory.create(inputStream)) {
            if (workbook.getNumberOfSheets() == 0) {
                return List.of();
            }
            Sheet sheet = workbook.getSheetAt(0);
            List<List<String>> rows = new ArrayList<>();
            for (Row row : sheet) {
                List<String> values = new ArrayList<>();
                for (int i = 0; i < Math.max(row.getLastCellNum(), 0); i += 1) {
                    values.add(formatter.formatCellValue(row.getCell(i)));
                }
                rows.add(values);
            }
            return rows;
        } catch (IOException exception) {
            throw new ValidationException("Excel dosyasi okunamadi.");
        }
    }

    private List<List<String>> readCsv(MultipartFile file) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().map(this::parseCsvLine).toList();
        } catch (IOException exception) {
            throw new ValidationException("CSV dosyasi okunamadi.");
        }
    }

    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i += 1) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i += 1;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                values.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(ch);
            }
        }
        values.add(current.toString());
        return values;
    }

    private AppUser resolveUser(UUID companyId) {
        AppUser user = requestAuthContext.requireUser();
        return user.getCompany().getId().equals(companyId) ? user : null;
    }

    private String buildOperationPayload(ImportedSheet sheet, Survey survey) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("importKind", "completed_survey_results");
        payload.put("displayMessage", "Operasyon Import edilmistir.");
        payload.put("surveyId", survey.getId().toString());
        payload.put("surveyName", survey.getName());
        payload.put("fileName", sheet.fileName());
        payload.put("responseCount", sheet.rows().size());
        payload.put("mappedQuestionCount", sheet.mappedColumns().size());
        ArrayNode warnings = payload.putArray("warnings");
        sheet.warnings().forEach(warnings::add);
        return payload.toString();
    }

    private String buildContactPayload(ImportedSheet sheet, RowPayload row) {
        return objectMapper.createObjectNode().put("importKind", "completed_survey_results").put("rowNumber", row.rowNumber()).toString();
    }

    private String buildAttemptPayload(ImportedSheet sheet, RowPayload row) {
        return objectMapper.createObjectNode().put("importKind", "completed_survey_results").put("fileName", sheet.fileName()).put("rowNumber", row.rowNumber()).toString();
    }

    private String buildResponsePayload(ImportedSheet sheet, RowPayload row) {
        ObjectNode payload = objectMapper.createObjectNode().put("importKind", "completed_survey_results").put("rowNumber", row.rowNumber());
        if (sheet.meta().summary() != null) {
            payload.put("summary", valueOf(row, sheet.meta().summary()));
        }
        if (sheet.meta().transcript() != null) {
            payload.put("transcript", valueOf(row, sheet.meta().transcript()));
        }
        return payload.toString();
    }

    private String buildAnswerPayload(SurveyAnswer answer, String rawValue, BigDecimal normalizedNumber, List<String> normalizedValues) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("questionId", answer.getSurveyQuestion().getId().toString());
        payload.put("questionCode", answer.getSurveyQuestion().getCode());
        payload.put("answerType", answer.getAnswerType().name());
        payload.put("rawValue", rawValue);
        payload.put("valid", answer.isValid());
        if (normalizedNumber != null) {
            payload.put("normalizedNumber", normalizedNumber);
        }
        if (!normalizedValues.isEmpty()) {
            ArrayNode items = payload.putArray("normalizedValues");
            normalizedValues.forEach(items::add);
        }
        if (answer.getSelectedOption() != null) {
            payload.put("selectedOptionId", answer.getSelectedOption().getId().toString());
            payload.put("selectedOptionLabel", answer.getSelectedOption().getLabel());
        }
        if (answer.getInvalidReason() != null) {
            payload.put("invalidReason", answer.getInvalidReason());
        }
        return payload.toString();
    }

    private BigDecimal completionPercent(ImportedSheet sheet, RowPayload row) {
        long answeredCount = sheet.mappedColumns().stream()
                .map(column -> row.cells().get(column.index()))
                .filter(Objects::nonNull)
                .count();
        if (sheet.totalQuestionCount() <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(answeredCount)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(sheet.totalQuestionCount()), 2, RoundingMode.HALF_UP);
    }

    private SurveyQuestionOption matchOption(List<SurveyQuestionOption> options, String rawValue) {
        String normalizedValue = normalize(rawValue);
        if (normalizedValue == null) {
            return null;
        }
        return options.stream()
                .filter(option -> normalizedValue.equals(normalize(option.getLabel()))
                        || normalizedValue.equals(normalize(option.getValue()))
                        || normalizedValue.equals(normalize(option.getOptionCode())))
                .findFirst()
                .orElse(null);
    }

    private List<String> splitMulti(String rawValue) {
        String normalized = trimToNull(rawValue);
        if (normalized == null) {
            return List.of();
        }
        return Arrays.stream(normalized.split("\\s*[|;,]\\s*"))
                .map(this::trimToNull)
                .filter(Objects::nonNull)
                .toList();
    }

    private BigDecimal parseDecimal(String rawValue) {
        String normalized = trimToNull(rawValue);
        if (normalized == null) {
            return null;
        }
        try {
            return new BigDecimal(normalized.replace(",", ".")).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private OffsetDateTime parseTimestamp(String rawValue) {
        String normalized = trimToNull(rawValue);
        if (normalized == null) {
            return null;
        }
        List<java.util.function.Function<String, OffsetDateTime>> parsers = List.of(
                value -> OffsetDateTime.parse(value),
                value -> LocalDateTime.parse(value).atOffset(ZoneOffset.UTC),
                value -> LocalDate.parse(value).atStartOfDay().atOffset(ZoneOffset.UTC)
        );
        for (java.util.function.Function<String, OffsetDateTime> parser : parsers) {
            try {
                return parser.apply(normalized);
            } catch (DateTimeParseException ignored) {
                // continue
            }
        }
        return null;
    }

    private String valueOf(RowPayload row, Integer index) {
        return index == null ? null : row.cells().get(index);
    }

    private OffsetDateTime firstNonNull(OffsetDateTime primary, OffsetDateTime fallback) {
        return primary != null ? primary : fallback;
    }

    private String firstNonBlank(String primary, String fallback) {
        return trimToNull(primary) != null ? primary.trim() : fallback;
    }

    private String extractFirstName(String fullName) {
        String normalized = trimToNull(fullName);
        if (normalized == null) {
            return "Import";
        }
        String[] parts = normalized.split("\\s+");
        return parts[0];
    }

    private String extractLastName(String fullName) {
        String normalized = trimToNull(fullName);
        if (normalized == null) {
            return "Katilimci";
        }
        String[] parts = normalized.split("\\s+");
        if (parts.length <= 1) {
            return "Katilimci";
        }
        return String.join(" ", Arrays.copyOfRange(parts, 1, parts.length));
    }

    private String normalizeHeader(String value) {
        return normalize(value);
    }

    private String normalize(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }
        String ascii = Normalizer.normalize(normalized, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return ascii.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String extension(String fileName) {
        int index = fileName.lastIndexOf('.');
        if (index < 0 || index == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private record ImportedSheet(
            String fileName,
            List<String> headers,
            List<RowPayload> rows,
            List<MappedQuestionColumn> mappedColumns,
            MetaColumns meta,
            List<String> warnings,
            int totalQuestionCount
    ) {}

    private record RowPayload(int rowNumber, Map<Integer, String> cells) {}

    private record MappedQuestionColumn(int index, SurveyQuestion question, List<SurveyQuestionOption> options) {}

    private record MetaColumns(
            Integer name,
            Integer phone,
            Integer startedAt,
            Integer completedAt,
            Integer externalRef,
            Integer summary,
            Integer transcript
    ) {
        private boolean isMeta(int index) {
            return Objects.equals(name, index) || Objects.equals(phone, index) || Objects.equals(startedAt, index)
                    || Objects.equals(completedAt, index) || Objects.equals(externalRef, index)
                    || Objects.equals(summary, index) || Objects.equals(transcript, index);
        }
    }
}
