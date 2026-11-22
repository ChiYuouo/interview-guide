package interview.guide.modules.resume.service;

import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.resume.model.ResumeEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 简历删除服务
 * 处理简历删除的业务逻辑
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeDeleteService {
    
    private final ResumePersistenceService persistenceService;
    private final InterviewPersistenceService interviewPersistenceService;
    private final InterviewSessionCache interviewSessionCache;
    private final FileStorageService storageService;
    private final TransactionalExecutor transactionalExecutor;
    
    /**
     * 删除简历
     * 
     * @param id 简历ID
     */
    public void deleteResume(Long userId, Long id) {
        log.info("收到删除简历请求: id={}", id);

        DeletedResume deleted = transactionalExecutor.call(() -> {
            ResumeEntity resume = persistenceService.requireOwnedResume(userId, id);
            List<String> sessionIds = interviewPersistenceService.deleteSessionsByResumeId(userId, id);
            persistenceService.deleteResume(userId, id);
            return new DeletedResume(resume.getStorageKey(), sessionIds);
        });

        for (String sessionId : deleted.sessionIds()) {
            try {
                interviewSessionCache.deleteSession(userId, sessionId);
            } catch (Exception e) {
                log.warn("简历已删除，清理会话缓存失败: sessionId={}", sessionId, e);
            }
        }
        String storageKey = deleted.storageKey();
        try {
            storageService.deleteResume(storageKey);
        } catch (Exception e) {
            log.warn("简历记录已删除，清理存储文件失败: resumeId={}, fileKey={}", id, storageKey, e);
        }
        
        log.info("简历删除完成: id={}", id);
    }

    private record DeletedResume(String storageKey, List<String> sessionIds) {}
}

