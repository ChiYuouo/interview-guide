package interview.guide.modules.knowledgebase;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.auth.model.AccountPrincipal;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.knowledgebase.model.CreateKnowledgeBaseInterviewRequest;
import interview.guide.modules.knowledgebase.model.CreateKnowledgeBaseQuestionRequest;
import interview.guide.modules.knowledgebase.model.GenerateKnowledgeBaseQuestionsRequest;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseInterviewCapacityResponse;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionDTO;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionStatus;
import interview.guide.modules.knowledgebase.model.QuestionGenStatusResponse;
import interview.guide.modules.knowledgebase.model.UpdateKnowledgeBaseQuestionRequest;
import interview.guide.modules.knowledgebase.model.UpdateKnowledgeBaseQuestionStatusRequest;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository.CategoryCount;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseInterviewService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQuestionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@RestController
@Validated
@RequiredArgsConstructor
public class KnowledgeBaseInterviewController {

  private final KnowledgeBaseQuestionService questionService;
  private final KnowledgeBaseInterviewService interviewService;

  @GetMapping("/api/knowledgebase/{id}/questions")
  public Result<List<KnowledgeBaseQuestionDTO>> listQuestions(
      @AuthenticationPrincipal AccountPrincipal account,
      @PathVariable Long id,
      @RequestParam(value = "status", required = false) KnowledgeBaseQuestionStatus status,
      @RequestParam(value = "category", required = false) String category,
      @RequestParam(value = "difficulty", required = false) String difficulty,
      @RequestParam(value = "keyword", required = false) String keyword) {
    return Result.success(
        questionService.listQuestions(account.getUserId(), id, status, category, difficulty, keyword));
  }

  @GetMapping("/api/knowledgebase/{id}/questions/categories")
  public Result<List<CategoryCount>> listCategories(
      @AuthenticationPrincipal AccountPrincipal account, @PathVariable Long id) {
    return Result.success(questionService.listCategories(account.getUserId(), id));
  }

  @PostMapping("/api/knowledgebase/{id}/questions/generate")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 2)
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 2)
  public Result<QuestionGenStatusResponse> generateQuestions(
      @AuthenticationPrincipal AccountPrincipal account,
      @PathVariable Long id,
      @Valid @RequestBody GenerateKnowledgeBaseQuestionsRequest request) {
    return Result.success(questionService.submitGenerationTask(account.getUserId(), id, request));
  }

  @GetMapping("/api/knowledgebase/{id}/questions/generation-status")
  public Result<QuestionGenStatusResponse> getQuestionGenerationStatus(
      @AuthenticationPrincipal AccountPrincipal account, @PathVariable Long id) {
    return Result.success(questionService.getGenerationStatus(account.getUserId(), id));
  }

  @PostMapping("/api/knowledgebase/{id}/questions")
  public Result<KnowledgeBaseQuestionDTO> createQuestion(
      @AuthenticationPrincipal AccountPrincipal account,
      @PathVariable Long id,
      @Valid @RequestBody CreateKnowledgeBaseQuestionRequest request) {
    return Result.success(questionService.createQuestion(account.getUserId(), id, request));
  }

  @PutMapping("/api/knowledgebase/questions/{questionId}")
  public Result<KnowledgeBaseQuestionDTO> updateQuestion(
      @AuthenticationPrincipal AccountPrincipal account,
      @PathVariable Long questionId,
      @RequestBody UpdateKnowledgeBaseQuestionRequest request) {
    return Result.success(questionService.updateQuestion(account.getUserId(), questionId, request));
  }

  @PutMapping("/api/knowledgebase/questions/{questionId}/status")
  public Result<KnowledgeBaseQuestionDTO> updateQuestionStatus(
      @AuthenticationPrincipal AccountPrincipal account,
      @PathVariable Long questionId,
      @Valid @RequestBody UpdateKnowledgeBaseQuestionStatusRequest request) {
    return Result.success(questionService.updateStatus(account.getUserId(), questionId, request.status()));
  }

  @DeleteMapping("/api/knowledgebase/questions/{questionId}")
  public Result<Void> deleteQuestion(
      @AuthenticationPrincipal AccountPrincipal account, @PathVariable Long questionId) {
    questionService.deleteQuestion(account.getUserId(), questionId);
    return Result.success(null);
  }

  @PostMapping("/api/knowledgebase-interviews/sessions")
  public Result<InterviewSessionDTO> createInterviewSession(
      @AuthenticationPrincipal AccountPrincipal account,
      @Valid @RequestBody CreateKnowledgeBaseInterviewRequest request) {
    return Result.success(interviewService.createSession(account.getUserId(), request));
  }

  @GetMapping("/api/knowledgebase/{id}/interview-capacity")
  public Result<KnowledgeBaseInterviewCapacityResponse> getInterviewCapacity(
      @AuthenticationPrincipal AccountPrincipal account,
      @PathVariable Long id,
      @RequestParam(value = "category", required = false) String category,
      @RequestParam(value = "difficulty", defaultValue = "mid") String difficulty,
      @RequestParam(value = "mainQuestionCount", defaultValue = "5")
      @Min(value = 1, message = "主问题数量最少1题")
      @Max(value = 20, message = "主问题数量最多20题")
      int mainQuestionCount) {
    return Result.success(
        interviewService.getCapacity(account.getUserId(), id, category, difficulty, mainQuestionCount));
  }
}
