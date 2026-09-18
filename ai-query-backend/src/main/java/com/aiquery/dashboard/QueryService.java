package com.aiquery.dashboard;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aiquery.dashboard.ai.AiClient;
import jakarta.annotation.PostConstruct;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;

@Service
public class QueryService {
    private static final Logger logger = LoggerFactory.getLogger(QueryService.class);
    private final MongoTemplate mongoTemplate;
    private final ObjectMapper objectMapper;
    private final AiClient aiClient;
    private final String collection;
    private final String savedCardsCollection;
    private static final Pattern MORE_THAN_YEAR = Pattern.compile("(?:more than|over|last|past|older than)\\s+(\\d+)\\s*(?:year|years|yr|yrs)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MORE_THAN_MONTHS = Pattern.compile("(?:more than|over|last|past|older than)\\s+(\\d+)\\s*(?:month|months|mo|mos)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOOKBACK_PERIOD = Pattern.compile("(?:last|past|over|within)\\s+(\\d+)\\s*(day|days|week|weeks|month|months|year|years)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ISO_DATE = Pattern.compile("\\b(\\d{4}-\\d{2}-\\d{2})\\b");
    private static final String ANALYSIS_PROMPT = """
            You are a payment analytics engine.
            
            Use ONLY the provided Payment Records and Operational Logs.
            
            Return EXACTLY ONE valid JSON object.
            
            Types:
            text|metric|table|bar|stackedBar|line|area|pie|scatter|histogram
            
            Rules:
            - Never invent data.
            - Preserve exact source values.
            - Use null only when null in source.
            - Filter records based on the user query.
            - Calculate counts, sums, averages, min/max, percentages, trends, comparisons, and KPIs when requested.
            - Keep IDs, dates, amounts, currencies, parties, banks, statuses, message types, settlement, risk, fraud, sanctions, compliance, and relationships unchanged.
            - No markdown or text outside JSON.
            
            Output Selection:
            - DEFAULT TO text for every generic question, analysis request, lookup, explanation, summary, investigation, or request to "analyze records".
            - Use metric only when the user explicitly asks for one KPI, total, count, average, rate, or single number.
            - Use table only when the user explicitly asks for a table, tabular output, rows, columns, or a list of records.
            - Use a chart only when the user explicitly asks for a chart, graph, visualization, trend line, bar chart, pie chart, or similar graphical representation.
            - text = explanation/summary/lookup and is the required fallback when no table, metric, or chart is explicitly requested.
            - line = time trend
            - area = cumulative trend
            - bar = comparison
            - stackedBar = composition
            - pie = share/distribution
            - scatter = correlation
            - histogram = numeric distribution
            
            Chart Rules:
            - Auto-select fields from user intent.
            - Use dates for trends.
            - Use categories (status, bank, party, currency, messageType, risk, etc.) for comparisons.
            - Use numeric measures (amounts, counts, fees, scores, durations) for y values.
            - xKey/yKey must exist in data.
            - scatter requires two numeric fields.
            - Multiple metrics may use yKeys.
            - For pie charts, xKey is the slice category and yKey is the slice measure; use xAxisLabel for the category and yAxisLabel for the measure. Do not describe pie fields as chart axes.
            
            Formats:
            
                        Generic text template:
                        {
                            "type":"text",
                            "title":"Clear heading",
                            "summary":"A concise direct answer to the user's question",
                            "bullets":["Important finding", "Important finding"],
                            "details":[{"label":"Metric or field","value":"Value"}],
                            "sections":[{"heading":"Supporting details","items":[{"label":"Field","value":"Value"}]}],
                            "dataSources":["Payment records", "Operational logs"]
                        }
                        Always use this text template for generic requests such as "analyze last 3 month records". Put counts, statuses, totals, exceptions, fraud results, pending items, and other findings in the bullets, details, or sections.
            
            {"type":"metric","title":"","value":0,"unit":"","description":""}
            
            {"type":"table","title":"","columns":[],"rows":[]}
            
            {
             "type":"bar|stackedBar|line|area|pie|scatter|histogram",
             "title":"",
             "data":[],
             "xKey":"",
             "yKey":"",
             "yKeys":[],
             "xAxisLabel":"",
             "yAxisLabel":""
            }
            
            If no match:
            {"type":"text","title":"No Matching Data","summary":"No records matched the request."}
            
            User Query: %s
            
            Payment Records: %s
            
            Operational Logs: %s
            """;

    public QueryService(
            MongoTemplate mongoTemplate, ObjectMapper objectMapper, AiClient aiClient,
            @Value("${ai.collection}") String collection,
            @Value("${ai.saved-cards-collection:saved_cards}") String savedCardsCollection
    ) {
        this.mongoTemplate = mongoTemplate;
        this.objectMapper = objectMapper;
        this.aiClient = aiClient;
        this.collection = collection;
        this.savedCardsCollection = savedCardsCollection;
    }

    public Object answer(String query) {
        logger.info("Query started: queryLength={}, issueQuestion={}",
            query == null ? 0 : query.length(), isIssueQuestion(query));
        String normalizedQuery = normalize(query);
        Integer requestedYears = requestedYears(query);
        if (requestedYears != null && requestedYears > 1) {
            logger.info("Query rejected by time-range guardrail: requestedYears={}", requestedYears);
            return textResponse("Query restricted", "Queries over one year are not allowed. Narrow the time range to one year or less before running it.");
        }
        Document savedCard = findSavedCard(normalizedQuery);
        if (savedCard != null && savedCard.get("spec") instanceof Map<?, ?> savedSpec) {
            logger.info("Saved card matched for query: normalizedQueryLength={}", normalizedQuery.length());
            return new HashMap<>((Map<String, Object>) savedSpec);
        }
        List<Document> paymentContext = loadPaymentContext(query);
        List<Document> operationalLogs = isIssueQuestion(query) ? loadOperationalLogs() : List.of();
        logger.info("Query context loaded: paymentRecords={}, operationalLogs={}", paymentContext.size(), operationalLogs.size());
        if (!aiClient.isConfigured()) {
            logger.error("AI query skipped because API key is not configured");
            return textResponse("AI configuration needed", "Add AI_API_KEY to generate a live insight from your MongoDB data.");
        }

        try {
            String prompt = ANALYSIS_PROMPT.formatted(query, objectMapper.writeValueAsString(paymentContext), objectMapper.writeValueAsString(operationalLogs));
            String content = aiClient.complete(prompt);
            Map<String, Object> renderSpec = parseRenderSpec(content, prompt);
            logger.info("Query completed: responseType={}", renderSpec.get("type"));
            return renderSpec;
        } catch (Exception exception) {
            logger.error("Query failed: provider={}, queryLength={}", aiClient.providerName(), query.length(), exception);
            return textResponse("Query unavailable", exception.getMessage() == null
                    ? "The query could not be rendered. Check the model, MongoDB connection, and API logs."
                    : exception.getMessage());
        }
    }

    private Map<String, Object> parseRenderSpec(String content, String originalPrompt) throws Exception {
        try {
            return objectMapper.readValue(stripFences(content), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception parseException) {
            logger.warn("AI returned invalid JSON; requesting one compact JSON retry: contentLength={}", content.length());
            String retryPrompt = originalPrompt + """
                    
                    IMPORTANT RETRY RULES
                    The previous response was not valid JSON. Return a compact, complete JSON object now.
                    Do not use markdown fences, unescaped quotation marks, literal line breaks inside strings, or trailing text.
                    Keep the response below the token limit and use the required schema.
                    """;
            String retryContent = aiClient.complete(retryPrompt);
            try {
                return objectMapper.readValue(stripFences(retryContent), new TypeReference<Map<String, Object>>() {
                });
            } catch (Exception retryException) {
                logger.error("AI JSON retry failed: firstContentLength={}, retryContentLength={}",
                        content.length(), retryContent.length(), retryException);
                retryException.addSuppressed(parseException);
                throw retryException;
            }
        }
    }

    @PostConstruct
    void ensureMongoCollections() {
        List.of(collection, savedCardsCollection).stream()
                .distinct()
                .forEach(this::ensureMongoCollection);
    }

    private void ensureMongoCollection(String collectionName) {
        try {
            if (!mongoTemplate.collectionExists(collectionName)) {
                mongoTemplate.createCollection(collectionName);
                logger.info("MongoDB collection created: collection={}", collectionName);
            }
        } catch (Exception exception) {
            logger.error("MongoDB collection initialization failed: collection={}", collectionName, exception);
        }
    }

    public List<Document> savedCards() {
        return mongoTemplate.findAll(Document.class, savedCardsCollection);
    }

    public Document saveCard(String title, String query, Map<String, Object> spec) {
        String normalizedQuery = normalize(query);
        Document existingCard = findSavedCard(normalizedQuery);
        if (existingCard != null) {
            logger.info("Saved card already exists: normalizedQueryLength={}", normalizedQuery.length());
            return existingCard;
        }
        Document card = new Document("cardId", UUID.randomUUID().toString()).append("title", title).append("query", query)
                .append("normalizedQuery", normalizedQuery).append("spec", spec)
                .append("savedAt", Instant.now().toString());
        mongoTemplate.save(card, savedCardsCollection);
        logger.info("Saved card created: normalizedQueryLength={}", normalizedQuery.length());
        return card;
    }

    public void deleteCard(String id) {
        mongoTemplate.remove(new Query(Criteria.where("cardId").is(id)), savedCardsCollection);
    }

    public Document updateCard(String id, String title, String query, Map<String, Object> spec) {
        Document card = mongoTemplate.findOne(
                new Query(Criteria.where("cardId").is(id)),
                Document.class,
                savedCardsCollection
        );
        if (card == null) {
            throw new IllegalArgumentException("Saved card not found: " + id);
        }
        card.put("title", title);
        card.put("query", query);
        card.put("normalizedQuery", normalize(query));
        card.put("spec", spec);
        card.put("updatedAt", Instant.now().toString());
        mongoTemplate.save(card, savedCardsCollection);
        logger.info("Saved card updated: cardId={}, queryLength={}", id, query.length());
        return card;
    }

    private List<Document> loadPaymentContext(String prompt) {
        try {
            TimeWindow window = timeWindow(prompt);
            Query paymentQuery = new Query();
            if (window != null) {
                paymentQuery.addCriteria(Criteria.where("creationDateTime")
                        .gte(window.from().toString())
                        .lt(window.to().toString()));
            }
            List<Document> records = mongoTemplate.find(paymentQuery, Document.class, collection);
            logger.info("Payment context query completed: collection={}, records={}, bounded={}", collection, records.size(), window != null);
            return records;
        } catch (Exception exception) {
            logger.error("Payment context query failed: collection={}, promptLength={}", collection, prompt.length(), exception);
            return List.of();
        }
    }

    private TimeWindow timeWindow(String prompt) {
        ZonedDateTime current = Instant.now().atZone(ZoneOffset.UTC);
        Instant now = current.toInstant();
        Matcher dates = ISO_DATE.matcher(prompt);
        List<LocalDate> explicitDates = dates.results()
                .map(match -> LocalDate.parse(match.group(1)))
                .toList();
        if (explicitDates.size() >= 2) {
            logger.info("Payment context window selected from explicit date range: from={}, to={}", explicitDates.get(0), explicitDates.get(1));
            return new TimeWindow(explicitDates.get(0).atStartOfDay(ZoneOffset.UTC).toInstant(),
                    explicitDates.get(1).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        }
        if (explicitDates.size() == 1) {
            Instant from = explicitDates.get(0).atStartOfDay(ZoneOffset.UTC).toInstant();
            logger.info("Payment context window selected for explicit date: date={}", explicitDates.get(0));
            return new TimeWindow(from, from.plus(1, java.time.temporal.ChronoUnit.DAYS));
        }

        Matcher period = LOOKBACK_PERIOD.matcher(prompt);
        if (period.find()) {
            int amount = Integer.parseInt(period.group(1));
            String unit = period.group(2).toLowerCase();
            Instant from = switch (unit) {
                case "day", "days" -> now.minus(amount, java.time.temporal.ChronoUnit.DAYS);
                case "week", "weeks" -> now.minus(amount, java.time.temporal.ChronoUnit.WEEKS);
                case "month", "months" -> current.minusMonths(amount).toInstant();
                case "year", "years" -> current.minusYears(amount).toInstant();
                default -> now;
            };
            logger.info("Payment context window selected from relative period: amount={}, unit={}, from={}, to={}", amount, unit, from, now);
            return new TimeWindow(from, now);
        }

        logger.info("Payment context window is unbounded because the prompt has no date filter");
        return null;
    }

    private record TimeWindow(Instant from, Instant to) {
    }

    private List<Document> loadOperationalLogs() {
        try (var stream = getClass().getResourceAsStream("/operational-logs.log")) {
            if (stream == null) return List.of();
            try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                return reader.lines()
                        .filter(line -> !line.isBlank() && !line.startsWith("#"))
                        .map(this::parseOperationalLog)
                        .toList();
            }
        } catch (Exception exception) {
            logger.error("Operational log loading failed", exception);
            return List.of();
        }
    }

    private Document parseOperationalLog(String line) {
        String[] fields = line.split("\\|", 5);
        return new Document("timestamp", fields[0])
                .append("level", fields[1])
                .append("service", fields[2])
                .append("transactionId", fields[3])
                .append("message", fields.length == 5 ? fields[4] : "");
    }

    private Document findSavedCard(String normalizedQuery) {
        try {
            return mongoTemplate.findOne(new Query(Criteria.where("normalizedQuery").is(normalizedQuery)), Document.class, savedCardsCollection);
        } catch (Exception exception) {
            logger.error("Saved card lookup failed: normalizedQueryLength={}", normalizedQuery.length(), exception);
            return null;
        }
    }

    private boolean isIssueQuestion(String query) {
        return query.toLowerCase().matches(".*\\b(fail|failed|failure|error|issue|declined|rejected|why)\\b.*");
    }

    private Integer requestedYears(String query) {
        Matcher matcher = MORE_THAN_YEAR.matcher(query);
        if (matcher.find()) return Integer.valueOf(matcher.group(1));
        Matcher monthMatcher = MORE_THAN_MONTHS.matcher(query);
        if (monthMatcher.find() && Integer.parseInt(monthMatcher.group(1)) > 12) return 2;
        return null;
    }

    private String normalize(String query) {
        return query.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    private Map<String, Object> textResponse(String title, String summary) {
        return Map.of("type", "text", "title", title, "summary", summary);
    }

    private String stripFences(String content) {
        return content.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
    }
}