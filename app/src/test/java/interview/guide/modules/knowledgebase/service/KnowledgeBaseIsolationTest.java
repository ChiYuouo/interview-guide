package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.mapper.KnowledgeBaseMapperImpl;
import interview.guide.infrastructure.mapper.RagChatMapperImpl;
import interview.guide.modules.interview.service.InterviewSessionService;
import interview.guide.modules.knowledgebase.listener.QuestionGenStreamProducer;
import interview.guide.modules.knowledgebase.model.CreateKnowledgeBaseInterviewRequest;
import interview.guide.modules.knowledgebase.model.CreateKnowledgeBaseQuestionRequest;
import interview.guide.modules.knowledgebase.model.GenerateKnowledgeBaseQuestionsRequest;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionDTO;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionStatus;
import interview.guide.modules.knowledgebase.model.QuestionGenStatus;
import interview.guide.modules.knowledgebase.model.RagChatDTO.CreateSessionRequest;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionListItemDTO;
import interview.guide.modules.knowledgebase.model.UpdateKnowledgeBaseQuestionRequest;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringJUnitConfig(KnowledgeBaseIsolationTest.Config.class)
@Transactional
class KnowledgeBaseIsolationTest {
  @Autowired
  private KnowledgeBaseRepository repository;
  @Autowired
  private KnowledgeBaseListService listService;
  @Autowired
  private FileStorageService storageService;
  @Autowired
  private KnowledgeBaseQuestionService questionService;
  @Autowired
  private KnowledgeBaseInterviewService interviewService;
  @Autowired
  private QuestionGenStreamProducer questionProducer;
  @Autowired
  private InterviewSessionService interviewSessionService;
  @Autowired
  private RagChatSessionService ragChatSessionService;
  @Autowired
  private KnowledgeBaseQueryService queryService;

  @Test
  @DisplayName("知识库按账号去重，列表、搜索、下载和检索校验数据归属")
  void isolatesKnowledgeBases() {
    var own = saveKnowledgeBase(7L, "我的文档", "我的分类");
    var other = saveKnowledgeBase(8L, "他的文档", "他的分类");

    assertThat(repository.findByUserIdAndFileHash(7L, "same-hash"))
        .get().extracting(KnowledgeBaseEntity::getId).isEqualTo(own.getId());
    assertThat(repository.findByUserIdAndFileHash(8L, "same-hash"))
        .get().extracting(KnowledgeBaseEntity::getId).isEqualTo(other.getId());
    assertThat(listService.listKnowledgeBases(7L, VectorStatus.PENDING, "time"))
        .extracting(KnowledgeBaseListItemDTO::id).containsExactly(own.getId());
    // 两个账号的原始文件名都匹配，搜索只能返回当前账号的文件。
    assertThat(listService.search(7L, "guide.pdf"))
        .extracting(KnowledgeBaseListItemDTO::id).containsExactly(own.getId());
    assertThat(listService.getAllCategories(7L)).containsExactly("我的分类");
    assertThat(listService.listByCategory(7L, "他的分类")).isEmpty();
    assertThat(listService.getStatistics(7L).totalCount()).isEqualTo(1);
    assertThat(listService.getKnowledgeBase(7L, other.getId())).isEmpty();
    assertThatThrownBy(() -> listService.downloadFile(7L, other.getId()))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> listService.updateCategory(7L, other.getId(), "跨账号修改"))
        .isInstanceOf(BusinessException.class);
    assertThat(listService.requireOwnedKnowledgeBaseNames(7L, List.of(own.getId())))
        .containsExactlyEntriesOf(Map.of(own.getId(), "我的文档"));
    assertThatThrownBy(() ->
        listService.requireOwnedKnowledgeBaseNames(7L, List.of(own.getId(), other.getId())))
        .isInstanceOf(BusinessException.class);
    verify(storageService, never()).downloadFile(anyString());
  }

  @Test
  @DisplayName("知识库题目、生成状态和面试只允许所属账号操作")
  void isolatesQuestionsAndInterviews() {
    var own = saveKnowledgeBase(7L, "我的文档", "我的分类");
    var other = saveKnowledgeBase(8L, "他的文档", "他的分类");
    var createQuestion = new CreateKnowledgeBaseQuestionRequest(
        "mid", null, "JVM", "解释垃圾回收", null, null, List.of(), null, List.of(), null,
        KnowledgeBaseQuestionStatus.ACTIVE);
    var ownQuestion = questionService.createQuestion(7L, own.getId(), createQuestion);
    var otherQuestion = questionService.createQuestion(8L, other.getId(), createQuestion);
    var editQuestion = new UpdateKnowledgeBaseQuestionRequest(
        null, null, null, "新的题干", null, null, null, null, null, null, null);

    assertThat(questionService.listQuestions(7L, own.getId(), null, null, null, null))
        .extracting(KnowledgeBaseQuestionDTO::id).containsExactly(ownQuestion.id());
    assertThat(questionService.updateQuestion(7L, ownQuestion.id(), editQuestion).question())
        .isEqualTo("新的题干");
    assertThat(questionService.getGenerationStatus(7L, own.getId()).questionGenStatus())
        .isEqualTo(QuestionGenStatus.NONE);
    assertThat(interviewService.getCapacity(7L, own.getId(), "JVM", "mid", 1).followUpOptions())
        .anyMatch(option -> option.followUpCount() == 0 && option.selectable());

    assertNotFound(() -> questionService.listQuestions(7L, other.getId(), null, null, null, null),
        ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
    assertNotFound(() -> questionService.listCategories(7L, other.getId()),
        ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
    assertNotFound(() -> questionService.createQuestion(7L, other.getId(), createQuestion),
        ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
    assertNotFound(() -> questionService.updateQuestion(7L, otherQuestion.id(), editQuestion),
        ErrorCode.INTERVIEW_QUESTION_NOT_FOUND);
    assertNotFound(() -> questionService.updateStatus(7L, otherQuestion.id(),
        KnowledgeBaseQuestionStatus.DRAFT), ErrorCode.INTERVIEW_QUESTION_NOT_FOUND);
    assertNotFound(() -> questionService.deleteQuestion(7L, otherQuestion.id()),
        ErrorCode.INTERVIEW_QUESTION_NOT_FOUND);
    assertNotFound(() -> questionService.getGenerationStatus(7L, other.getId()),
        ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
    assertNotFound(() -> questionService.submitGenerationTask(7L, other.getId(),
        new GenerateKnowledgeBaseQuestionsRequest("mid", 1, 0, 1, null)),
        ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
    assertNotFound(() -> interviewService.getCapacity(7L, other.getId(), null, "mid", 1),
        ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
    assertNotFound(() -> interviewService.createSession(7L,
        new CreateKnowledgeBaseInterviewRequest(other.getId(), null, "mid", 1, 0, null)),
        ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);

    assertThat(questionService.listQuestions(8L, other.getId(), null, null, null, null))
        .extracting(KnowledgeBaseQuestionDTO::id).containsExactly(otherQuestion.id());
    assertThat(questionService.getGenerationStatus(8L, other.getId()).questionGenStatus())
        .isEqualTo(QuestionGenStatus.NONE);
    verifyNoInteractions(questionProducer, interviewSessionService);
  }

  @Test
  @DisplayName("RAG 会话、消息和关联知识库按账号隔离")
  void isolatesRagSessions() {
    var ownKb = saveKnowledgeBase(7L, "我的文档", "我的分类");
    var otherKb = saveKnowledgeBase(8L, "他的文档", "他的分类");
    var own = ragChatSessionService.createSession(7L,
        new CreateSessionRequest(List.of(ownKb.getId()), "我的会话"));
    var other = ragChatSessionService.createSession(8L,
        new CreateSessionRequest(List.of(otherKb.getId()), "他的会话"));
    Long messageId = ragChatSessionService.prepareStreamMessage(8L, other.id(), "他的问题");

    assertThat(ragChatSessionService.listSessions(7L))
        .extracting(SessionListItemDTO::id).containsExactly(own.id());
    assertThat(ragChatSessionService.listSessions(8L))
        .extracting(SessionListItemDTO::id).containsExactly(other.id());
    assertNotFound(() -> ragChatSessionService.getSessionDetail(7L, other.id()), ErrorCode.NOT_FOUND);
    assertNotFound(() -> ragChatSessionService.updateSessionTitle(7L, other.id(), "跨账号改名"),
        ErrorCode.NOT_FOUND);
    assertNotFound(() -> ragChatSessionService.togglePin(7L, other.id()), ErrorCode.NOT_FOUND);
    assertNotFound(() -> ragChatSessionService.deleteSession(7L, other.id()), ErrorCode.NOT_FOUND);
    assertNotFound(() -> ragChatSessionService.prepareStreamMessage(7L, other.id(), "跨账号提问"),
        ErrorCode.NOT_FOUND);
    assertNotFound(() -> ragChatSessionService.getStreamAnswer(7L, other.id(), "跨账号检索"),
        ErrorCode.NOT_FOUND);
    assertNotFound(() -> ragChatSessionService.completeStreamMessage(7L, messageId, "跨账号改写"),
        ErrorCode.NOT_FOUND);
    assertNotFound(() -> ragChatSessionService.createSession(7L,
        new CreateSessionRequest(List.of(ownKb.getId(), otherKb.getId()), null)), ErrorCode.NOT_FOUND);
    assertNotFound(() -> ragChatSessionService.updateSessionKnowledgeBases(7L, own.id(),
        List.of(otherKb.getId())), ErrorCode.NOT_FOUND);
    assertNotFound(() -> ragChatSessionService.updateSessionKnowledgeBases(7L, other.id(),
        List.of(ownKb.getId())), ErrorCode.NOT_FOUND);

    ragChatSessionService.completeStreamMessage(8L, messageId, "他的回答");
    var otherDetail = ragChatSessionService.getSessionDetail(8L, other.id());
    assertThat(otherDetail.title()).isEqualTo("他的会话");
    assertThat(otherDetail.messages()).extracting(message -> message.content())
        .containsExactly("他的问题", "他的回答");
    assertThat(ragChatSessionService.getSessionDetail(7L, own.id()).knowledgeBases())
        .extracting(KnowledgeBaseListItemDTO::id).containsExactly(ownKb.getId());
    assertThat(listService.getStatistics(7L).totalQuestionCount()).isZero();
    assertThat(listService.getStatistics(8L).totalQuestionCount()).isEqualTo(1);
    verifyNoInteractions(queryService);
  }

  private void assertNotFound(ThrowingCallable call, ErrorCode code) {
    assertThatThrownBy(call).isInstanceOfSatisfying(
        BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(code.getCode()));
  }

  private KnowledgeBaseEntity saveKnowledgeBase(Long userId, String name, String category) {
    var knowledgeBase = new KnowledgeBaseEntity();
    knowledgeBase.setUserId(userId);
    knowledgeBase.setFileHash("same-hash");
    knowledgeBase.setName(name);
    knowledgeBase.setCategory(category);
    knowledgeBase.setOriginalFilename("guide.pdf");
    knowledgeBase.setStorageKey("files/" + userId + "/guide.pdf");
    return repository.saveAndFlush(knowledgeBase);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableJpaRepositories(basePackageClasses = KnowledgeBaseRepository.class)
  @EnableTransactionManagement
  @Import({KnowledgeBaseListService.class, KnowledgeBaseMapperImpl.class,
      KnowledgeBaseQuestionService.class, KnowledgeBaseInterviewService.class,
      QuestionGenerationStateService.class, RagChatSessionService.class, RagChatMapperImpl.class})
  static class Config {
    @Bean
    DataSource dataSource() {
      return new DriverManagerDataSource("jdbc:h2:mem:kb-isolation;DB_CLOSE_DELAY=-1", "sa", "");
    }

    @Bean
    LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
      var factory = new LocalContainerEntityManagerFactoryBean();
      factory.setDataSource(dataSource);
      factory.setPackagesToScan(KnowledgeBaseEntity.class.getPackageName());
      factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
      factory.setJpaPropertyMap(Map.of(
          "hibernate.hbm2ddl.auto", "create-drop",
          "hibernate.physical_naming_strategy",
          "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl"));
      return factory;
    }

    @Bean
    JpaTransactionManager transactionManager(EntityManagerFactory factory) {
      return new JpaTransactionManager(factory);
    }

    @Bean
    FileStorageService fileStorageService() {
      return mock(FileStorageService.class);
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }

    @Bean
    QuestionGenStreamProducer questionGenStreamProducer() {
      return mock(QuestionGenStreamProducer.class);
    }

    @Bean
    InterviewSessionService interviewSessionService() {
      return mock(InterviewSessionService.class);
    }

    @Bean
    KnowledgeBaseQueryService queryService() {
      return mock(KnowledgeBaseQueryService.class);
    }

    @Bean
    KnowledgeBaseQueryProperties queryProperties() {
      return new KnowledgeBaseQueryProperties();
    }
  }
}
