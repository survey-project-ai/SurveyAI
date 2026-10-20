package com.yourcompany.surveyai.survey.application.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourcompany.surveyai.common.exception.NotFoundException;
import com.yourcompany.surveyai.common.exception.ValidationException;
import com.yourcompany.surveyai.common.repository.CompanyRepository;
import com.yourcompany.surveyai.survey.application.dto.response.SurveyImportPreviewOptionDto;
import com.yourcompany.surveyai.survey.application.dto.response.SurveyImportPreviewQuestionDto;
import com.yourcompany.surveyai.survey.application.dto.response.SurveyImportPreviewResponseDto;
import com.yourcompany.surveyai.survey.application.dto.response.SurveyImportPreviewSurveyDto;
import com.yourcompany.surveyai.survey.application.service.SurveyFileImportService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@Transactional(readOnly = true)
public class SurveyFileImportServiceImpl implements SurveyFileImportService {

    private static final Pattern NUMBERED_QUESTION_PATTERN = Pattern.compile("^\\s*(\\d+)[\\).:-]\\s*(.+)$");
    private static final Pattern QUESTION_CODE_PATTERN = Pattern.compile("^\\s*([A-Za-z]\\d+(?:\\.\\d+)?)\\s*[\\).:-]?\\s*(.*)$");
    private static final Pattern QUESTION_REFERENCE_PATTERN = Pattern.compile("\\b([A-Za-z]\\d+(?:\\.\\d+)?)\\b");
    private static final Pattern BULLET_PATTERN = Pattern.compile("^\\s*(?:[-*•]|[a-zA-Z]\\)|\\d+[\\).])\\s+(.+)$");

    private final CompanyRepository companyRepository;
    private final ObjectMapper objectMapper;
    private final DataFormatter dataFormatter = new DataFormatter();

    public SurveyFileImportServiceImpl(CompanyRepository companyRepository, ObjectMapper objectMapper) {
        this.companyRepository = companyRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public SurveyImportPreviewResponseDto previewImport(UUID companyId, MultipartFile file) {
        ensureCompanyExists(companyId);
        if (file == null || file.isEmpty()) {
            throw new ValidationException("İçe aktarmak için bir dosya seçin.");
        }

        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename().trim() : "survey-import";
        String extension = resolveExtension(fileName);

        ImportedSurveyDraft draft = switch (extension) {
            case "xlsx", "xls" -> parseExcel(fileName, file);
            case "csv" -> parseCsv(fileName, file);
            case "docx" -> parseDocx(fileName, file);
            case "pdf" -> parsePdf(fileName, file);
            case "txt" -> parseText(fileName, file);
            default -> throw new ValidationException("Desteklenmeyen dosya türü. Şu an xlsx, xls, csv, docx, pdf ve txt destekleniyor.");
        };

        return toPreviewDto(draft);
    }

    private ImportedSurveyDraft parseExcel(String fileName, MultipartFile file) {
        try (InputStream inputStream = file.getInputStream(); Workbook workbook = WorkbookFactory.create(inputStream)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new ValidationException("Excel dosyasında okunabilir bir sayfa bulunamadı.");
            }
            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rows = sheet.rowIterator();
            if (!rows.hasNext()) {
                throw new ValidationException("Excel dosyası boş görünüyor.");
            }

            Map<String, Integer> headerMap = buildHeaderMap(rows.next());
            if (!headerMap.containsKey("question_text") && !headerMap.containsKey("question")) {
                throw new ValidationException("Excel içe aktarımı için ilk satırda question_text veya question sütunu bulunmalı.");
            }

            List<String> warnings = new ArrayList<>();
            List<ImportedQuestionDraft> questions = new ArrayList<>();
            int order = 1;
            while (rows.hasNext()) {
                Row row = rows.next();
                String questionText = readCell(row, headerMap, "question_text", "question");
                if (questionText == null) {
                    continue;
                }

                String typeCell = readCell(row, headerMap, "question_type", "type");
                String description = readCell(row, headerMap, "description", "help_text", "note");
                String optionsCell = readCell(row, headerMap, "options", "choices");
                StructuredQuestionMetadata metadata = readStructuredMetadata(
                        readCell(row, headerMap, "group_code", "question_group_code", "matrix_group_code"),
                        readCell(row, headerMap, "group_title", "question_group_title", "matrix_group_title"),
                        readCell(row, headerMap, "row_label", "matrix_row_label"),
                        readCell(row, headerMap, "option_set_code", "matrix_option_set_code")
                );
                String branchConditionJson = readStructuredBranchConditionJson(
                        readCell(row, headerMap, "skip_if_group_code"),
                        readCell(row, headerMap, "skip_if_question_code"),
                        readCell(row, headerMap, "skip_if_row_code"),
                        readCell(row, headerMap, "skip_if_same_row"),
                        readCell(row, headerMap, "skip_if_option_codes", "skip_if_options", "skip_if_selected_option_codes"),
                        readCell(row, headerMap, "skip_if_answer_tags_any_of", "skip_if_answer_tags"),
                        readCell(row, headerMap, "skip_if_valid_answer_required"),
                        readCell(row, headerMap, "ask_if_group_code"),
                        readCell(row, headerMap, "ask_if_question_code"),
                        readCell(row, headerMap, "ask_if_row_code"),
                        readCell(row, headerMap, "ask_if_same_row"),
                        readCell(row, headerMap, "ask_if_option_codes", "ask_if_options", "ask_if_selected_option_codes"),
                        readCell(row, headerMap, "ask_if_answer_tags_any_of", "ask_if_answer_tags"),
                        readCell(row, headerMap, "ask_if_valid_answer_required"),
                        readCell(row, headerMap, "branch_operator", "branch_logic")
                );
                String codingMetadata = readCell(row, headerMap, "coding_json", "coding_categories", "coding_keywords");
                boolean required = parseRequired(readCell(row, headerMap, "required", "zorunlu"));

                ImportedQuestionDraft question = buildQuestionFromStructuredRow(
                        order,
                        questionText,
                        description,
                        typeCell,
                        optionsCell,
                        metadata,
                        branchConditionJson,
                        codingMetadata,
                        fileName,
                        warnings
                );
                question.required = required;
                questions.add(question);
                order += 1;
            }

            if (questions.isEmpty()) {
                throw new ValidationException("Excel dosyasında içe aktarılabilir soru satırı bulunamadı.");
            }

            return new ImportedSurveyDraft(
                    deriveSurveyName(fileName),
                    "Excel şablonundan içe aktarılan taslak.",
                    "tr",
                    "",
                    "",
                    2,
                    "FILE_UPLOAD",
                    null,
                    fileName,
                    buildSurveySourcePayload("excel", fileName, warnings),
                    questions,
                    warnings
            );
        } catch (IOException exception) {
            throw new ValidationException("Excel dosyası okunamadı.");
        }
    }

    private ImportedSurveyDraft parseCsv(String fileName, MultipartFile file) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            List<String> lines = reader.lines().toList();
            if (lines.isEmpty()) {
                throw new ValidationException("CSV dosyası boş görünüyor.");
            }

            Map<String, Integer> headerMap = buildHeaderMap(parseCsvLine(lines.get(0)));
            if (!headerMap.containsKey("question_text") && !headerMap.containsKey("question")) {
                throw new ValidationException("CSV içe aktarımı için ilk satırda question_text veya question sütunu bulunmalı.");
            }

            List<String> warnings = new ArrayList<>();
            List<ImportedQuestionDraft> questions = new ArrayList<>();
            int order = 1;
            for (int index = 1; index < lines.size(); index += 1) {
                List<String> row = parseCsvLine(lines.get(index));
                String questionText = readCell(row, headerMap, "question_text", "question");
                if (questionText == null) {
                    continue;
                }

                String typeCell = readCell(row, headerMap, "question_type", "type");
                String description = readCell(row, headerMap, "description", "help_text", "note");
                String optionsCell = readCell(row, headerMap, "options", "choices");
                StructuredQuestionMetadata metadata = readStructuredMetadata(
                        readCell(row, headerMap, "group_code", "question_group_code", "matrix_group_code"),
                        readCell(row, headerMap, "group_title", "question_group_title", "matrix_group_title"),
                        readCell(row, headerMap, "row_label", "matrix_row_label"),
                        readCell(row, headerMap, "option_set_code", "matrix_option_set_code")
                );
                String branchConditionJson = readStructuredBranchConditionJson(
                        readCell(row, headerMap, "skip_if_group_code"),
                        readCell(row, headerMap, "skip_if_question_code"),
                        readCell(row, headerMap, "skip_if_row_code"),
                        readCell(row, headerMap, "skip_if_same_row"),
                        readCell(row, headerMap, "skip_if_option_codes", "skip_if_options", "skip_if_selected_option_codes"),
                        readCell(row, headerMap, "skip_if_answer_tags_any_of", "skip_if_answer_tags"),
                        readCell(row, headerMap, "skip_if_valid_answer_required"),
                        readCell(row, headerMap, "ask_if_group_code"),
                        readCell(row, headerMap, "ask_if_question_code"),
                        readCell(row, headerMap, "ask_if_row_code"),
                        readCell(row, headerMap, "ask_if_same_row"),
                        readCell(row, headerMap, "ask_if_option_codes", "ask_if_options", "ask_if_selected_option_codes"),
                        readCell(row, headerMap, "ask_if_answer_tags_any_of", "ask_if_answer_tags"),
                        readCell(row, headerMap, "ask_if_valid_answer_required"),
                        readCell(row, headerMap, "branch_operator", "branch_logic")
                );
                String codingMetadata = readCell(row, headerMap, "coding_json", "coding_categories", "coding_keywords");
                boolean required = parseRequired(readCell(row, headerMap, "required", "zorunlu"));

                ImportedQuestionDraft question = buildQuestionFromStructuredRow(
                        order,
                        questionText,
                        description,
                        typeCell,
                        optionsCell,
                        metadata,
                        branchConditionJson,
                        codingMetadata,
                        fileName,
                        warnings
                );
                question.required = required;
                questions.add(question);
                order += 1;
            }

            if (questions.isEmpty()) {
                throw new ValidationException("CSV dosyasında içe aktarılabilir soru satırı bulunamadı.");
            }

            return new ImportedSurveyDraft(
                    deriveSurveyName(fileName),
                    "CSV şablonundan içe aktarılan taslak.",
                    "tr",
                    "",
                    "",
                    2,
                    "FILE_UPLOAD",
                    null,
                    fileName,
                    buildSurveySourcePayload("csv", fileName, warnings),
                    questions,
                    warnings
            );
        } catch (IOException exception) {
            throw new ValidationException("CSV dosyası okunamadı.");
        }
    }

    private ImportedSurveyDraft parseDocx(String fileName, MultipartFile file) {
        try (InputStream inputStream = file.getInputStream(); XWPFDocument document = new XWPFDocument(inputStream)) {
            List<String> lines = document.getParagraphs().stream()
                    .map(XWPFParagraph::getText)
                    .filter(text -> text != null && !text.isBlank())
                    .toList();
            return parseHeuristicDocument(fileName, lines, "docx");
        } catch (IOException exception) {
            throw new ValidationException("Word dosyası okunamadı.");
        }
    }

    private ImportedSurveyDraft parseText(String fileName, MultipartFile file) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            return parseHeuristicDocument(fileName, reader.lines().toList(), "txt");
        } catch (IOException exception) {
            throw new ValidationException("Metin dosyası okunamadı.");
        }
    }

    private ImportedSurveyDraft parsePdf(String fileName, MultipartFile file) {
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            List<String> lines = stripper.getText(document)
                    .lines()
                    .map(String::trim)
                    .filter(line -> !line.isBlank())
                    .toList();
            ImportedSurveyDraft draft = parseHeuristicDocument(fileName, lines, "pdf");
            draft.warnings.add("PDF importu düz metin çıkarımına dayanır; sütunlu veya taranmış PDF'lerde önizlemeyi kontrol etmeniz önerilir.");
            return draft;
        } catch (IOException exception) {
            throw new ValidationException("PDF dosyası okunamadı.");
        }
    }

    private ImportedSurveyDraft parseHeuristicDocument(String fileName, List<String> rawLines, String sourceKind) {
        List<String> lines = rawLines.stream().map(String::trim).filter(line -> !line.isBlank()).toList();
        if (lines.isEmpty()) {
            throw new ValidationException("Belge içinde okunabilir içerik bulunamadı.");
        }

        List<String> warnings = new ArrayList<>();
        List<QuestionBlock> blocks = new ArrayList<>();
        QuestionBlock current = null;

        for (String line : lines) {
            Matcher numbered = NUMBERED_QUESTION_PATTERN.matcher(line);
            Matcher bullet = BULLET_PATTERN.matcher(line);

            if (numbered.matches()) {
                current = new QuestionBlock(numbered.group(2).trim());
                blocks.add(current);
                continue;
            }

            if (current == null || looksLikeStandaloneQuestion(line)) {
                current = new QuestionBlock(line);
                blocks.add(current);
                continue;
            }

            if (bullet.matches()) {
                current.options.add(bullet.group(1).trim());
                continue;
            }

            if (current.description == null) {
                current.description = line;
            } else {
                current.description = current.description + " " + line;
            }
        }

        List<ImportedQuestionDraft> questions = new ArrayList<>();
        int order = 1;
        for (QuestionBlock block : blocks) {
            ImportedQuestionDraft question = inferQuestionFromBlock(order, block, warnings);
            if (question != null) {
                questions.add(question);
                order += 1;
            }
        }

        if (questions.isEmpty()) {
            throw new ValidationException("Belgeden soru yapısı çıkarılamadı. Word/TXT için numaralı soru düzeni önerilir.");
        }

        warnings.add("Word/TXT importu heuristik çalışır; taslak oluşunca soru tiplerini ve seçenekleri kontrol etmeniz önerilir.");

        return new ImportedSurveyDraft(
                deriveSurveyName(fileName),
                "Belgeden içe aktarılan taslak.",
                "tr",
                "",
                "",
                2,
                "FILE_UPLOAD",
                null,
                fileName,
                buildSurveySourcePayload(sourceKind, fileName, warnings),
                questions,
                warnings
        );
    }

    private ImportedQuestionDraft buildQuestionFromStructuredRow(
            int order,
            String questionText,
            String description,
            String typeCell,
            String optionsCell,
            StructuredQuestionMetadata metadata,
            String branchConditionJson,
            String codingMetadata,
            String fileName,
            List<String> warnings
    ) {
        ImportedQuestionDraft question = new ImportedQuestionDraft();
        question.code = "question_" + order;
        question.title = questionText.trim();
        question.description = description;
        question.required = true;
        question.options = splitOptions(optionsCell);
        question.type = normalizeStructuredType(typeCell, question.title, question.options, warnings);
        question.branchConditionJson = branchConditionJson != null ? branchConditionJson : inferBranchConditionJson(question.title, question.description, metadata);
        question.settingsJson = buildSettingsJson(question.type, metadata, question.options, question.title, codingMetadata);
        question.sourcePayloadJson = buildQuestionSourcePayload(
                "file-row",
                fileName,
                question.title,
                description,
                question.options,
                metadata,
                question.branchConditionJson,
                codingMetadata
        );
        return question;
    }

    private ImportedQuestionDraft inferQuestionFromBlock(int order, QuestionBlock block, List<String> warnings) {
        if (block.title == null || block.title.isBlank()) {
            return null;
        }
        ImportedQuestionDraft question = new ImportedQuestionDraft();
        question.code = "question_" + order;
        question.title = block.title.trim();
        question.description = block.description;
        question.required = true;
        question.options = block.options.stream().map(String::trim).filter(option -> !option.isBlank()).toList();
        question.type = inferQuestionType(question.title, question.description, question.options, warnings);
        question.branchConditionJson = inferBranchConditionJson(question.title, question.description, null);
        question.settingsJson = buildSettingsJson(question.type, null, question.options, question.title, null);
        question.sourcePayloadJson = buildQuestionSourcePayload(
                "heuristic-document",
                null,
                question.title,
                question.description,
                question.options,
                null,
                question.branchConditionJson,
                null
        );
        return question;
    }

    private String normalizeStructuredType(String typeCell, String title, List<String> options, List<String> warnings) {
        String normalized = normalize(typeCell);
        if (normalized == null) {
            warnings.add("Bazı satırlarda question_type boştu; tip otomatik çıkarıldı.");
            return inferQuestionType(title, null, options, warnings);
        }
        return switch (normalized) {
            case "short_text", "shorttext", "text", "open_ended" -> "short_text";
            case "long_text", "longtext", "textarea" -> "long_text";
            case "single_choice", "radio", "choice" -> "single_choice";
            case "multi_choice", "checkbox", "checkboxes" -> "multi_choice";
            case "dropdown", "select" -> "dropdown";
            case "yes_no", "boolean" -> "yes_no";
            case "rating_1_5", "rating5", "scale5" -> "rating_1_5";
            case "rating_1_10", "rating10", "scale10" -> "rating_1_10";
            case "date" -> "date";
            case "full_name", "fullname", "name" -> "full_name";
            case "number", "numeric" -> "number";
            case "phone", "phone_number" -> "phone";
            default -> {
                warnings.add("Tanınmayan question_type bulundu; '" + typeCell + "' yerine otomatik tip çıkarıldı.");
                yield inferQuestionType(title, null, options, warnings);
            }
        };
    }

    private String inferQuestionType(String title, String description, List<String> options, List<String> warnings) {
        String combined = normalize((title == null ? "" : title) + " " + (description == null ? "" : description));
        if (combined == null) {
            return "short_text";
        }
        if (combined.contains("1-10") || combined.contains("10 uzerinden") || combined.contains("10 üzerinden")) {
            return "rating_1_10";
        }
        if (combined.contains("1-5") || combined.contains("5 uzerinden") || combined.contains("5 üzerinden")
                || combined.contains("puan") || combined.contains("degerlendir")) {
            return "rating_1_5";
        }
        if (combined.contains("ad soyad") || combined.contains("isim soyisim")) {
            return "full_name";
        }
        if (combined.contains("telefon") || combined.contains("gsm")) {
            return "phone";
        }
        if (combined.contains("tarih")) {
            return "date";
        }
        if (combined.contains("kac") || combined.contains("kaç") || combined.contains("adet") || combined.contains("sayi")) {
            return "number";
        }
        if (options.size() == 2 && isYesNoOptions(options)) {
            return "yes_no";
        }
        if (!options.isEmpty()) {
            if (combined.contains("birden fazla") || combined.contains("hangileri")) {
                return "multi_choice";
            }
            return "single_choice";
        }
        if (combined.contains("neden") || combined.contains("aciklay")) {
            return "long_text";
        }
        warnings.add("Bazı sorular için tip çıkarımı kısa metin olarak yapıldı: " + title);
        return "short_text";
    }

    private boolean isYesNoOptions(List<String> options) {
        List<String> normalized = options.stream().map(this::normalize).toList();
        return normalized.contains("evet") && (normalized.contains("hayir") || normalized.contains("hayır") || normalized.contains("no"));
    }

    private String buildSettingsJson(
            String type,
            StructuredQuestionMetadata metadata,
            List<String> options,
            String title,
            String codingMetadata
    ) {
        ObjectNode node = objectMapper.createObjectNode().put("builderType", type);
        if ("rating_1_5".equals(type)) {
            node.put("ratingScale", 5);
        } else if ("rating_1_10".equals(type)) {
            node.put("ratingScale", 10);
        }
        if (metadata != null && metadata.groupCode() != null) {
            node.put("groupCode", metadata.groupCode());
            node.put("groupTitle", metadata.groupTitle() != null ? metadata.groupTitle() : title);
            node.put("rowLabel", metadata.rowLabel() != null ? metadata.rowLabel() : title);
            String rowCode = slugify(metadata.rowLabel() != null ? metadata.rowLabel() : title);
            node.put("rowCode", rowCode);
            node.put("rowKey", rowCode);
            String optionSetCode = metadata.optionSetCode() != null ? metadata.optionSetCode() : inferOptionSetCode(options);
            if (optionSetCode != null) {
                node.put("optionSetCode", optionSetCode);
            }
            node.put("matrixType", "GRID_SINGLE_CHOICE");
        }
        appendCodingSettings(node, codingMetadata);
        appendChoiceAliases(node, options);
        return node.toString();
    }

    private String buildSurveySourcePayload(String sourceKind, String fileName, List<String> warnings) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("sourceKind", sourceKind);
        payload.put("fileName", fileName);
        ArrayNode warningArray = payload.putArray("warnings");
        warnings.forEach(warningArray::add);
        return payload.toString();
    }

    private String buildQuestionSourcePayload(
            String sourceKind,
            String fileName,
            String title,
            String description,
            List<String> options,
            StructuredQuestionMetadata metadata,
            String branchConditionJson,
            String codingMetadata
    ) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("sourceKind", sourceKind);
        if (fileName != null) {
            payload.put("fileName", fileName);
        }
        payload.put("title", title);
        if (description != null) {
            payload.put("description", description);
        }
        ArrayNode optionArray = payload.putArray("options");
        options.forEach(optionArray::add);
        if (metadata != null && metadata.groupCode() != null) {
            payload.put("groupCode", metadata.groupCode());
            if (metadata.groupTitle() != null) {
                payload.put("groupTitle", metadata.groupTitle());
            }
            if (metadata.rowLabel() != null) {
                payload.put("rowLabel", metadata.rowLabel());
                payload.put("rowCode", slugify(metadata.rowLabel()));
            }
            String optionSetCode = metadata.optionSetCode() != null ? metadata.optionSetCode() : inferOptionSetCode(options);
            if (optionSetCode != null) {
                payload.put("optionSetCode", optionSetCode);
            }
        }
        String normalizedBranchConditionJson = trimToNull(branchConditionJson);
        if (normalizedBranchConditionJson != null && !"{}".equals(normalizedBranchConditionJson)) {
            try {
                payload.set("branchCondition", objectMapper.readTree(normalizedBranchConditionJson));
            } catch (Exception ignored) {
                payload.put("branchConditionJson", normalizedBranchConditionJson);
            }
        }
        appendCodingSourceMetadata(payload, codingMetadata);
        return payload.toString();
    }

    private StructuredQuestionMetadata readStructuredMetadata(
            String groupCode,
            String groupTitle,
            String rowLabel,
            String optionSetCode
    ) {
        String normalizedGroupCode = trimToNull(groupCode);
        if (normalizedGroupCode == null) {
            return null;
        }
        return new StructuredQuestionMetadata(
                normalizedGroupCode,
                trimToNull(groupTitle),
                trimToNull(rowLabel),
                trimToNull(optionSetCode)
        );
    }

    private String readStructuredBranchConditionJson(
            String skipGroupCode,
            String skipQuestionCode,
            String skipRowCode,
            String skipSameRow,
            String skipOptionCodes,
            String skipAnswerTags,
            String skipValidAnswerRequired,
            String askGroupCode,
            String askQuestionCode,
            String askRowCode,
            String askSameRow,
            String askOptionCodes,
            String askAnswerTags,
            String askValidAnswerRequired,
            String branchOperator
    ) {
        ObjectNode root = objectMapper.createObjectNode();
        String normalizedOperator = trimToNull(branchOperator);
        if (normalizedOperator != null) {
            root.put("operator", "ANY".equalsIgnoreCase(normalizedOperator) ? "ANY" : "ALL");
        }

        ObjectNode skipRule = buildStructuredBranchRule(
                skipGroupCode,
                skipQuestionCode,
                skipRowCode,
                skipSameRow,
                skipOptionCodes,
                skipAnswerTags,
                skipValidAnswerRequired
        );
        if (skipRule != null) {
            root.set("skipIf", skipRule);
        }

        ObjectNode askRule = buildStructuredBranchRule(
                askGroupCode,
                askQuestionCode,
                askRowCode,
                askSameRow,
                askOptionCodes,
                askAnswerTags,
                askValidAnswerRequired
        );
        if (askRule != null) {
            root.set("askIf", askRule);
        }

        return root.isEmpty() ? "{}" : root.toString();
    }

    private ObjectNode buildStructuredBranchRule(
            String groupCode,
            String questionCode,
            String rowCode,
            String sameRow,
            String optionCodes,
            String answerTags,
            String validAnswerRequired
    ) {
        String normalizedGroupCode = trimToNull(groupCode);
        String normalizedQuestionCode = trimToNull(questionCode);
        String normalizedRowCode = trimToNull(rowCode);
        List<String> selectedOptionCodes = splitBranchOptionCodes(optionCodes);
        List<String> branchAnswerTags = splitBranchOptionCodes(answerTags);
        Boolean sameRowValue = parseOptionalBoolean(sameRow);
        Boolean validAnswerRequiredValue = parseOptionalBoolean(validAnswerRequired);

        if (normalizedGroupCode == null
                && normalizedQuestionCode == null
                && normalizedRowCode == null
                && selectedOptionCodes.isEmpty()
                && branchAnswerTags.isEmpty()
                && sameRowValue == null
                && validAnswerRequiredValue == null) {
            return null;
        }

        ObjectNode rule = objectMapper.createObjectNode();
        if (normalizedGroupCode != null) {
            rule.put("groupCode", normalizedGroupCode);
        }
        if (normalizedQuestionCode != null) {
            rule.put("questionCode", normalizedQuestionCode);
        }
        if (normalizedRowCode != null) {
            rule.put("rowCode", normalizedRowCode);
        }
        if (sameRowValue != null) {
            rule.put("sameRowCode", sameRowValue);
        }
        if (!selectedOptionCodes.isEmpty()) {
            ArrayNode optionArray = rule.putArray("selectedOptionCodes");
            selectedOptionCodes.forEach(optionArray::add);
        }
        if (!branchAnswerTags.isEmpty()) {
            ArrayNode tagArray = rule.putArray("answerTagsAnyOf");
            branchAnswerTags.forEach(tagArray::add);
        }
        if (validAnswerRequiredValue != null) {
            rule.put("validAnswerRequired", validAnswerRequiredValue);
        }
        return rule;
    }

    private List<String> splitBranchOptionCodes(String rawValue) {
        String normalized = trimToNull(rawValue);
        if (normalized == null) {
            return List.of();
        }
        String delimiter = normalized.contains("|") ? "\\|" : normalized.contains(";") ? ";" : normalized.contains(",") ? "," : "\\n";
        return Arrays.stream(normalized.split(delimiter))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private Boolean parseOptionalBoolean(String rawValue) {
        String normalized = normalize(rawValue);
        if (normalized == null) {
            return null;
        }
        if (List.of("true", "evet", "yes", "1").contains(normalized)) {
            return true;
        }
        if (List.of("false", "hayir", "hayır", "no", "0").contains(normalized)) {
            return false;
        }
        return null;
    }

    private String inferBranchConditionJson(String title, String description, StructuredQuestionMetadata metadata) {
        String combined = trimToNull((title == null ? "" : title) + " " + (description == null ? "" : description));
        if (combined == null) {
            return "{}";
        }

        String normalizedCombined = normalize(combined);
        if (normalizedCombined == null || (!normalizedCombined.contains("sorulmayacak") && !normalizedCombined.contains("sorulmaz"))) {
            return "{}";
        }

        String currentQuestionCode = extractLeadingQuestionCode(title);
        String referencedCode = extractReferencedQuestionCode(combined, currentQuestionCode);
        List<String> selectedOptionCodes = inferSelectedOptionCodes(normalizedCombined);
        if (referencedCode == null || selectedOptionCodes.isEmpty()) {
            return "{}";
        }

        ObjectNode root = objectMapper.createObjectNode();
        List<String> inferredAnswerTags = inferBranchAnswerTags(selectedOptionCodes);
        boolean useKnowledgePositiveAskIf = inferredAnswerTags.contains("knowledge_negative");
        ObjectNode branchNode = root.putObject(useKnowledgePositiveAskIf ? "askIf" : "skipIf");
        if (metadata != null && metadata.groupCode() != null) {
            branchNode.put("groupCode", referencedCode);
            if (!normalize(referencedCode).equals(normalize(metadata.groupCode()))) {
                branchNode.put("sameRowCode", true);
            }
        } else {
            branchNode.put("questionCode", referencedCode);
        }
        if (useKnowledgePositiveAskIf) {
            branchNode.putArray("answerTagsAnyOf").add("knowledge_positive");
        } else {
            ArrayNode optionArray = branchNode.putArray("selectedOptionCodes");
            selectedOptionCodes.forEach(optionArray::add);
            if (!inferredAnswerTags.isEmpty()) {
                ArrayNode tagArray = branchNode.putArray("answerTagsAnyOf");
                inferredAnswerTags.forEach(tagArray::add);
            }
        }
        return root.toString();
    }

    private String extractLeadingQuestionCode(String text) {
        String value = trimToNull(text);
        if (value == null) {
            return null;
        }
        Matcher matcher = QUESTION_CODE_PATTERN.matcher(value);
        return matcher.matches() ? matcher.group(1).toUpperCase(Locale.ROOT) : null;
    }

    private String extractReferencedQuestionCode(String text, String currentQuestionCode) {
        String value = trimToNull(text);
        if (value == null) {
            return null;
        }
        Matcher matcher = QUESTION_REFERENCE_PATTERN.matcher(value);
        while (matcher.find()) {
            String candidate = matcher.group(1).toUpperCase(Locale.ROOT);
            if (currentQuestionCode == null || !candidate.equalsIgnoreCase(currentQuestionCode)) {
                return candidate;
            }
        }
        return null;
    }

    private List<String> inferSelectedOptionCodes(String normalizedText) {
        Set<String> selectedOptionCodes = new LinkedHashSet<>();
        if (normalizedText.contains("hic duymadim")) {
            selectedOptionCodes.add("hic_duymadim");
        }
        if (normalizedText.contains("duydum ama tanimiyorum") || normalizedText.contains("tanimiyorum")) {
            selectedOptionCodes.add("duydum_ama_tanimiyorum");
        }
        if (normalizedText.contains("bilmiyorum")) {
            selectedOptionCodes.add("bilmiyorum");
        }
        if (normalizedText.contains("cevap vermedi")) {
            selectedOptionCodes.add("cevap_yok");
        }
        if (normalizedText.contains("hayir")) {
            selectedOptionCodes.add("hayir");
        }
        if (normalizedText.contains("evet")) {
            selectedOptionCodes.add("evet");
        }
        return List.copyOf(selectedOptionCodes);
    }

    private List<String> inferBranchAnswerTags(List<String> selectedOptionCodes) {
        Set<String> tags = new LinkedHashSet<>();
        for (String optionCode : selectedOptionCodes) {
            String normalized = normalize(optionCode);
            if (normalized == null) {
                continue;
            }
            if (normalized.contains("hic duymad")) {
                tags.add("knowledge_negative");
                tags.add("knowledge_never_heard");
            }
            if (normalized.contains("duydum") && normalized.contains("tanimiyorum")) {
                tags.add("knowledge_negative");
                tags.add("knowledge_heard_but_unknown");
            }
            if ((normalized.contains("tanimiyorum") || normalized.contains("bilmiyorum"))
                    && !normalized.contains("taniyorum")) {
                tags.add("knowledge_negative");
            }
            if ((normalized.contains("taniyorum") || normalized.contains("biliyorum"))
                    && !normalized.contains("tanimiyorum")) {
                tags.add("knowledge_positive");
            }
            if (normalized.contains("evet")) {
                tags.add("yes");
            }
            if (normalized.contains("hayir")) {
                tags.add("no");
            }
        }
        return List.copyOf(tags);
    }

    private void appendCodingSettings(ObjectNode settingsNode, String codingMetadata) {
        ObjectNode categoriesNode = parseCodingCategories(codingMetadata);
        if (categoriesNode == null || categoriesNode.isEmpty()) {
            return;
        }
        settingsNode.with("coding").set("categories", categoriesNode);
    }

    private void appendCodingSourceMetadata(ObjectNode payload, String codingMetadata) {
        ObjectNode categoriesNode = parseCodingCategories(codingMetadata);
        if (categoriesNode == null || categoriesNode.isEmpty()) {
            return;
        }
        payload.set("codingCategories", categoriesNode);
    }

    private ObjectNode parseCodingCategories(String codingMetadata) {
        String normalized = trimToNull(codingMetadata);
        if (normalized == null) {
            return null;
        }

        try {
            if (normalized.startsWith("{")) {
                JsonNode root = objectMapper.readTree(normalized);
                JsonNode categoriesNode = root.path("coding").path("categories");
                if (categoriesNode.isMissingNode() || !categoriesNode.isObject()) {
                    categoriesNode = root.path("categories");
                }
                if (categoriesNode.isObject()) {
                    return (ObjectNode) categoriesNode.deepCopy();
                }
                if (root.isObject()) {
                    return (ObjectNode) root.deepCopy();
                }
            }
        } catch (Exception ignored) {
            // Fall back to simple parsing below.
        }

        ObjectNode categories = objectMapper.createObjectNode();
        Arrays.stream(normalized.split(";"))
                .map(String::trim)
                .filter(entry -> !entry.isBlank())
                .forEach(entry -> {
                    String[] parts = entry.split("[:=]", 2);
                    if (parts.length != 2) {
                        return;
                    }
                    String categoryCode = slugify(parts[0]);
                    if (categoryCode.isBlank()) {
                        return;
                    }
                    ArrayNode aliases = categories.putArray(categoryCode);
                    Arrays.stream(parts[1].split("\\|"))
                            .map(String::trim)
                            .filter(value -> !value.isBlank())
                            .forEach(aliases::add);
                });
        return categories.isEmpty() ? null : categories;
    }

    private void appendChoiceAliases(ObjectNode settingsNode, List<String> options) {
        if (options.isEmpty()) {
            return;
        }
        Map<String, List<String>> aliases = buildKnownChoiceAliases(options);
        if (aliases.isEmpty()) {
            return;
        }
        ObjectNode aliasesNode = settingsNode.putObject("aliases");
        aliases.forEach((key, values) -> {
            ArrayNode valueArray = aliasesNode.putArray(key);
            values.forEach(valueArray::add);
        });
    }

    private Map<String, List<String>> buildKnownChoiceAliases(List<String> options) {
        List<String> normalizedOptions = options.stream()
                .map(this::normalize)
                .toList();
        if (normalizedOptions.containsAll(List.of(
                "cok iyi taniyorum",
                "taniyorum",
                "biraz taniyorum",
                "duydum ama tanimiyorum",
                "hic duymadim"
        ))) {
            Map<String, List<String>> aliases = new LinkedHashMap<>();
            aliases.put("cok_iyi_taniyorum", List.of("cok iyi taniyorum", "çok iyi tanıyorum", "cok iyi biliyorum"));
            aliases.put("taniyorum", List.of("taniyorum", "tanıyorum", "biliyorum"));
            aliases.put("biraz_taniyorum", List.of("biraz taniyorum", "biraz tanıyorum", "az taniyorum"));
            aliases.put("duydum_ama_tanimiyorum", List.of("duydum ama tanimiyorum", "duydum ama tanımıyorum", "ismini duydum"));
            aliases.put("hic_duymadim", List.of("hic duymadim", "hiç duymadım", "duymadim", "duymadım"));
            return aliases;
        }
        return Map.of();
    }

    private String inferOptionSetCode(List<String> options) {
        return buildKnownChoiceAliases(options).isEmpty() ? null : "familiarity_5";
    }

    private SurveyImportPreviewResponseDto toPreviewDto(ImportedSurveyDraft draft) {
        return new SurveyImportPreviewResponseDto(
                new SurveyImportPreviewSurveyDto(
                        draft.name,
                        draft.summary,
                        draft.languageCode,
                        draft.introPrompt,
                        draft.closingPrompt,
                        draft.maxRetryPerQuestion,
                        draft.sourceProvider,
                        draft.sourceExternalId,
                        draft.sourceFileName,
                        draft.sourcePayloadJson,
                        draft.questions.stream().map(question -> new SurveyImportPreviewQuestionDto(
                                question.code,
                                question.type,
                                question.title,
                                question.description,
                                question.required,
                                question.branchConditionJson,
                                question.settingsJson,
                                question.sourceExternalId,
                                question.sourcePayloadJson,
                                question.options.stream().map(SurveyImportPreviewOptionDto::new).toList()
                        )).toList()
                ),
                draft.warnings
        );
    }

    private Map<String, Integer> buildHeaderMap(Row headerRow) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (Cell cell : headerRow) {
            String normalized = normalize(dataFormatter.formatCellValue(cell));
            if (normalized != null) {
                map.put(normalized, cell.getColumnIndex());
            }
        }
        return map;
    }

    private Map<String, Integer> buildHeaderMap(List<String> headers) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int index = 0; index < headers.size(); index += 1) {
            String normalized = normalize(headers.get(index));
            if (normalized != null) {
                map.put(normalized, index);
            }
        }
        return map;
    }

    private String readCell(Row row, Map<String, Integer> headerMap, String... aliases) {
        for (String alias : aliases) {
            Integer index = headerMap.get(alias);
            if (index == null) {
                continue;
            }
            Cell cell = row.getCell(index);
            String value = cell != null ? trimToNull(dataFormatter.formatCellValue(cell)) : null;
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String readCell(List<String> values, Map<String, Integer> headerMap, String... aliases) {
        for (String alias : aliases) {
            Integer index = headerMap.get(alias);
            if (index == null || index >= values.size()) {
                continue;
            }
            String value = trimToNull(values.get(index));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int index = 0; index < line.length(); index += 1) {
            char currentChar = line.charAt(index);
            if (currentChar == '"') {
                if (inQuotes && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    current.append('"');
                    index += 1;
                } else {
                    inQuotes = !inQuotes;
                }
                continue;
            }
            if (currentChar == ',' && !inQuotes) {
                values.add(current.toString());
                current = new StringBuilder();
                continue;
            }
            current.append(currentChar);
        }
        values.add(current.toString());
        return values;
    }

    private boolean parseRequired(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return true;
        }
        return !List.of("false", "hayir", "hayır", "no", "0").contains(normalized);
    }

    private List<String> splitOptions(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return List.of();
        }
        String delimiter = normalized.contains("|") ? "\\|" : normalized.contains(";") ? ";" : normalized.contains("\n") ? "\n" : ",";
        return Arrays.stream(normalized.split(delimiter))
                .map(String::trim)
                .filter(option -> !option.isBlank())
                .collect(Collectors.toList());
    }

    private String deriveSurveyName(String fileName) {
        String baseName = fileName.replaceAll("\\.[^.]+$", "");
        return baseName.isBlank() ? "Dosya Importu" : baseName.replace('_', ' ').replace('-', ' ').trim();
    }

    private String slugify(String input) {
        String normalized = normalize(input);
        if (normalized == null) {
            return "question";
        }
        return normalized.replaceAll("[^a-z0-9]+", "_").replaceAll("(^_|_$)", "");
    }

    private String resolveExtension(String fileName) {
        int index = fileName.lastIndexOf('.');
        if (index < 0 || index == fileName.length() - 1) {
            throw new ValidationException("Dosya uzantısı bulunamadı.");
        }
        return fileName.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private String normalize(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        return trimmed.toLowerCase(Locale.ROOT)
                .replace('ı', 'i')
                .replace('ğ', 'g')
                .replace('ü', 'u')
                .replace('ş', 's')
                .replace('ö', 'o')
                .replace('ç', 'c');
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean looksLikeStandaloneQuestion(String line) {
        String normalized = normalize(line);
        return normalized != null && (line.endsWith("?")
                || normalized.contains("1-5")
                || normalized.contains("1-10")
                || normalized.contains("puan")
                || normalized.contains("degerlendir")
                || normalized.contains("hangisi")
                || normalized.contains("hangileri"));
    }

    private void ensureCompanyExists(UUID companyId) {
        if (!companyRepository.existsById(companyId)) {
            throw new NotFoundException("Şirket bulunamadı: " + companyId);
        }
    }

    private static class ImportedSurveyDraft {
        private final String name;
        private final String summary;
        private final String languageCode;
        private final String introPrompt;
        private final String closingPrompt;
        private final Integer maxRetryPerQuestion;
        private final String sourceProvider;
        private final String sourceExternalId;
        private final String sourceFileName;
        private final String sourcePayloadJson;
        private final List<ImportedQuestionDraft> questions;
        private final List<String> warnings;

        private ImportedSurveyDraft(
                String name,
                String summary,
                String languageCode,
                String introPrompt,
                String closingPrompt,
                Integer maxRetryPerQuestion,
                String sourceProvider,
                String sourceExternalId,
                String sourceFileName,
                String sourcePayloadJson,
                List<ImportedQuestionDraft> questions,
                List<String> warnings
        ) {
            this.name = name;
            this.summary = summary;
            this.languageCode = languageCode;
            this.introPrompt = introPrompt;
            this.closingPrompt = closingPrompt;
            this.maxRetryPerQuestion = maxRetryPerQuestion;
            this.sourceProvider = sourceProvider;
            this.sourceExternalId = sourceExternalId;
            this.sourceFileName = sourceFileName;
            this.sourcePayloadJson = sourcePayloadJson;
            this.questions = questions;
            this.warnings = warnings;
        }
    }

    private static class ImportedQuestionDraft {
        private String code;
        private String type;
        private String title;
        private String description;
        private boolean required;
        private String branchConditionJson = "{}";
        private String settingsJson;
        private String sourceExternalId;
        private String sourcePayloadJson;
        private List<String> options = List.of();
    }

    private record StructuredQuestionMetadata(
            String groupCode,
            String groupTitle,
            String rowLabel,
            String optionSetCode
    ) {
    }

    private static class QuestionBlock {
        private final String title;
        private String description;
        private final List<String> options = new ArrayList<>();

        private QuestionBlock(String title) {
            this.title = title;
        }
    }
}
