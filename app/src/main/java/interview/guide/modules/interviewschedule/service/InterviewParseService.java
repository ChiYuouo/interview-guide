package interview.guide.modules.interviewschedule.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.log.ErrorLogSanitizer;
import interview.guide.modules.interviewschedule.model.CreateInterviewRequest;
import interview.guide.modules.interviewschedule.model.ParseResponse;
import interview.guide.modules.interviewschedule.model.ParsedInviteDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一的面试邀约解析服务 - 整合规则解析和AI解析
 * Simplified interview schedule parsing service combining rule-based and AI parsing
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewParseService {

    private final LlmProviderRegistry llmProviderRegistry;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final PromptSanitizer promptSanitizer;
    private final PromptTemplate systemPromptTemplate = new PromptTemplate(
        new ClassPathResource("prompts/interview-schedule-parse-system.st"));
    private final BeanOutputConverter<ParsedInviteDTO> outputConverter =
        new BeanOutputConverter<>(ParsedInviteDTO.class);

    // Date formatters
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DATE_TIME_FORMATTER_2 = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");

    private static final Map<String, Integer> CHINESE_NUMBERS = Map.of(
        "一", 1, "二", 2, "三", 3, "四", 4, "五", 5,
        "六", 6, "七", 7, "八", 8, "九", 9, "十", 10
    );

    // Feishu patterns
    private static final Pattern TIME_PATTERN_FEISHU = Pattern.compile("(?:时间|时段)[：:]\\s*(\\d{4}[-/]\\d{2}[-/]\\d{2}\\s+\\d{2}:\\d{2})");
    private static final Pattern LINK_PATTERN_FEISHU = Pattern.compile("https://meeting\\.feishu\\.cn/[^\\s\\n]+");
    private static final Pattern COMPANY_PATTERN_FEISHU = Pattern.compile("(?:公司|单位|组织)[：:]\\s*([^\\s\\n]{1,50})");
    private static final Pattern POSITION_PATTERN_FEISHU = Pattern.compile("(?:岗位|职位|职务)[：:]\\s*([^\\s\\n]{1,50})");
    private static final Pattern ROUND_PATTERN_FEISHU = Pattern.compile(
        "第\\s*([一二三四五六七八九十]|\\d+)\\s*[轮场]");

    // Tencent meeting patterns
    private static final Pattern TIME_PATTERN_TENCENT = Pattern.compile("(\\d{4}[-/]\\d{2}[-/]\\d{2})\\s+(\\d{2}:\\d{2})");
    private static final Pattern MEETING_ID_PATTERN_TENCENT = Pattern.compile("(?:会议号|ID)[：:]?\\s*(\\d{9,})");
    private static final Pattern PASSWORD_PATTERN_TENCENT = Pattern.compile("密码[：:]?\\s*(\\d{4,})");
    private static final Pattern COMPANY_PATTERN_TENCENT = Pattern.compile("(?:公司|单位)[：:]\\s*([^\\s\\n]{1,50})");
    private static final Pattern POSITION_PATTERN_TENCENT = Pattern.compile("(?:岗位|职位)[：:]\\s*([^\\s\\n]{1,50})");

    // Zoom patterns
    private static final Pattern LINK_PATTERN_ZOOM = Pattern.compile("https://zoom\\.us/j/[^\\s\\n]+");
    private static final Pattern DATE_PATTERN_ZOOM = Pattern.compile("(\\d{4}[-/]\\d{2}[-/]\\d{2})");
    private static final Pattern HOUR_PATTERN_ZOOM = Pattern.compile("(\\d{1,2}:\\d{2})");

    /**
     * Parse interview schedule text
     *
     * @param rawText Raw text to parse
     * @param source Source platform (feishu/tencent/zoom or null for auto-detect)
     * @return Parse response with extracted interview details
     */
    public ParseResponse parse(Long userId, String rawText, String source) {
        log.info("开始解析文本，来源: {}, 文本长度: {}", source, rawText != null ? rawText.length() : 0);

        if (rawText == null || rawText.trim().isEmpty()) {
            log.warn("Input text is null or empty");
            return new ParseResponse(false, null, 0.0, "none", "输入文本为空");
        }

        // Step 1: Try rule-based parsing
        CreateInterviewRequest result = tryRuleParsing(rawText, source);
        if (isValidResult(result)) {
            log.info("规则解析成功");
            return new ParseResponse(true, result, 0.95, "rule", "规则解析成功");
        }

        // Step 2: Rule parsing failed, try AI parsing
        log.info("规则解析失败，尝试 AI 解析");
        result = parseWithAI(userId, rawText);
        if (isValidResult(result)) {
            log.info("AI 解析成功");
            return new ParseResponse(true, result, 0.8, "ai", "AI 解析成功");
        }

        // Step 3: Both failed
        log.warn("所有解析方式均失败");
        return new ParseResponse(false, null, 0.0, "none", "解析失败");
    }

    private CreateInterviewRequest tryRuleParsing(String rawText, String source) {
        // Try source-specific format first
        if ("feishu".equalsIgnoreCase(source)) {
            return parseFeishu(rawText);
        } else if ("tencent".equalsIgnoreCase(source)) {
            return parseTencent(rawText);
        } else if ("zoom".equalsIgnoreCase(source)) {
            return parseZoom(rawText);
        }

        // No source specified, try all formats
        if (rawText.contains("飞书") || rawText.contains("Feishu") || rawText.contains("meeting.feishu.cn")) {
            CreateInterviewRequest result = parseFeishu(rawText);
            if (isValidResult(result)) return result;
        }

        if (rawText.contains("腾讯会议") || rawText.contains("Tencent Meeting") || rawText.contains("会议号")) {
            CreateInterviewRequest result = parseTencent(rawText);
            if (isValidResult(result)) return result;
        }

        if (rawText.contains("Zoom") || rawText.contains("zoom.us")) {
            CreateInterviewRequest result = parseZoom(rawText);
            if (isValidResult(result)) return result;
        }

        // Try all formats
        CreateInterviewRequest result = parseFeishu(rawText);
        if (isValidResult(result)) return result;

        result = parseTencent(rawText);
        if (isValidResult(result)) return result;

        return parseZoom(rawText);
    }

    // ========== Feishu Parsing ==========

    private CreateInterviewRequest parseFeishu(String rawText) {
        log.debug("尝试解析飞书格式");

        CreateInterviewRequest request = new CreateInterviewRequest();

        try {
            // Extract time
            Matcher timeMatcher = TIME_PATTERN_FEISHU.matcher(rawText);
            if (timeMatcher.find()) {
                String timeStr = timeMatcher.group(1);
                request.setInterviewTime(parseDateTime(timeStr));
            }

            // Extract link
            Matcher linkMatcher = LINK_PATTERN_FEISHU.matcher(rawText);
            if (linkMatcher.find()) {
                request.setMeetingLink(linkMatcher.group());
            }

            // Extract company
            Matcher companyMatcher = COMPANY_PATTERN_FEISHU.matcher(rawText);
            if (companyMatcher.find()) {
                request.setCompanyName(companyMatcher.group(1).trim());
            }

            // Extract position
            Matcher positionMatcher = POSITION_PATTERN_FEISHU.matcher(rawText);
            if (positionMatcher.find()) {
                request.setPosition(positionMatcher.group(1).trim());
            }

            // Extract round number
            Matcher roundMatcher = ROUND_PATTERN_FEISHU.matcher(rawText);
            if (roundMatcher.find()) {
                String round = roundMatcher.group(1);
                Integer chineseRound = CHINESE_NUMBERS.get(round);
                request.setRoundNumber(chineseRound != null ? chineseRound : Integer.parseInt(round));
            }

            request.setInterviewType("VIDEO");

            return request;

        } catch (Exception e) {
            log.error("飞书格式解析异常", e);
            return request;
        }
    }

    // ========== Tencent Meeting Parsing ==========

    private CreateInterviewRequest parseTencent(String rawText) {
        log.debug("尝试解析腾讯会议格式");

        CreateInterviewRequest request = new CreateInterviewRequest();

        try {
            // Extract time
            Matcher timeMatcher = TIME_PATTERN_TENCENT.matcher(rawText);
            if (timeMatcher.find()) {
                String timeStr = timeMatcher.group(1) + " " + timeMatcher.group(2);
                request.setInterviewTime(parseDateTime(timeStr));
            }

            // Extract meeting ID and password
            Matcher meetingIdMatcher = MEETING_ID_PATTERN_TENCENT.matcher(rawText);
            Matcher passwordMatcher = PASSWORD_PATTERN_TENCENT.matcher(rawText);

            StringBuilder meetingLink = new StringBuilder();
            if (meetingIdMatcher.find()) {
                meetingLink.append("会议号: ").append(meetingIdMatcher.group());
            }
            if (passwordMatcher.find()) {
                meetingLink.append(" 密码: ").append(passwordMatcher.group());
            }
            if (meetingLink.length() > 0) {
                request.setMeetingLink(meetingLink.toString());
            }

            // Extract company and position
            Matcher companyMatcher = COMPANY_PATTERN_TENCENT.matcher(rawText);
            if (companyMatcher.find()) {
                request.setCompanyName(companyMatcher.group(1).trim());
            }

            Matcher positionMatcher = POSITION_PATTERN_TENCENT.matcher(rawText);
            if (positionMatcher.find()) {
                request.setPosition(positionMatcher.group(1).trim());
            }

            request.setInterviewType("VIDEO");

            return request;

        } catch (Exception e) {
            log.error("腾讯会议格式解析异常", e);
            return request;
        }
    }

    // ========== Zoom Parsing ==========

    private CreateInterviewRequest parseZoom(String rawText) {
        log.debug("尝试解析 Zoom 格式");

        CreateInterviewRequest request = new CreateInterviewRequest();

        try {
            // Extract link
            Matcher linkMatcher = LINK_PATTERN_ZOOM.matcher(rawText);
            if (linkMatcher.find()) {
                request.setMeetingLink(linkMatcher.group());
            }

            // Extract date and hour
            Matcher dateMatcher = DATE_PATTERN_ZOOM.matcher(rawText);
            Matcher hourMatcher = HOUR_PATTERN_ZOOM.matcher(rawText);

            if (dateMatcher.find() && hourMatcher.find()) {
                String timeStr = dateMatcher.group(1) + " " + hourMatcher.group(1);
                request.setInterviewTime(parseDateTime(timeStr));
            }

            request.setInterviewType("VIDEO");

            return request;

        } catch (Exception e) {
            log.error("Zoom 格式解析异常", e);
            return request;
        }
    }

    // ========== AI Parsing ==========

    private CreateInterviewRequest parseWithAI(Long userId, String rawText) {
        ChatClient chatClient = llmProviderRegistry.getChatClientOrDefault(userId, null);
        try {
            String systemPrompt = systemPromptTemplate.render(Map.of(
                "currentDate", LocalDate.now().toString())) + "\n\n" + outputConverter.getFormat();
            String userPrompt = PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION + "\n" +
                promptSanitizer.wrapWithDelimiters("parse-input", promptSanitizer.sanitize(rawText));

            ParsedInviteDTO result = structuredOutputInvoker.invoke(
                chatClient, systemPrompt, userPrompt, outputConverter,
                ErrorCode.AI_SERVICE_ERROR, "面试邀约解析失败：", "面试邀约解析", log
            );
            if (result == null || !StringUtils.hasText(result.interviewTime())) {
                return null;
            }

            CreateInterviewRequest request = new CreateInterviewRequest();
            request.setCompanyName(stripToNull(result.companyName()));
            request.setPosition(stripToNull(result.position()));
            request.setInterviewTime(LocalDateTime.parse(result.interviewTime().strip()));
            request.setInterviewType(stripToNull(result.interviewType()));
            request.setMeetingLink(stripToNull(result.meetingLink()));
            if (result.roundNumber() != null) {
                request.setRoundNumber(result.roundNumber());
            }
            request.setInterviewer(stripToNull(result.interviewer()));
            request.setNotes(stripToNull(result.notes()));
            return request;
        } catch (RuntimeException e) {
            log.error("AI 解析异常: {}", ErrorLogSanitizer.summarize(e),
                ErrorLogSanitizer.forLogging(e));
            return null;
        }
    }

    private static String stripToNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    // ========== Helper Methods ==========

    private LocalDateTime parseDateTime(String timeStr) {
        try {
            timeStr = timeStr.replace("/", "-");
            if (timeStr.length() == 16) { // yyyy-MM-dd HH:mm
                return LocalDateTime.parse(timeStr, DATE_TIME_FORMATTER);
            } else if (timeStr.length() == 19) { // yyyy-MM-dd HH:mm:ss
                return LocalDateTime.parse(timeStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            }
            return LocalDateTime.parse(timeStr);
        } catch (Exception e) {
            log.error("时间解析失败: {}", timeStr, e);
            return null;
        }
    }

    private boolean isValidResult(CreateInterviewRequest result) {
        return result != null
                && StringUtils.hasText(result.getCompanyName())
                && StringUtils.hasText(result.getPosition())
                && result.getInterviewTime() != null;
    }
}
