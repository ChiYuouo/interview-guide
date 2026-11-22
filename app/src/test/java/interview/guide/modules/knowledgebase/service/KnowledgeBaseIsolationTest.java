package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.mapper.KnowledgeBaseMapperImpl;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringJUnitConfig(KnowledgeBaseIsolationTest.Config.class)
@Transactional
class KnowledgeBaseIsolationTest {
  @Autowired
  private KnowledgeBaseRepository repository;
  @Autowired
  private KnowledgeBaseListService listService;
  @Autowired
  private FileStorageService storageService;

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
  @Import({KnowledgeBaseListService.class, KnowledgeBaseMapperImpl.class})
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
  }
}
