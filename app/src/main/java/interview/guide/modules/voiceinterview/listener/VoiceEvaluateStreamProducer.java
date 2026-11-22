package interview.guide.modules.voiceinterview.listener;

import interview.guide.common.async.AbstractStreamProducer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 语音面试评估任务生产者
 */
@Slf4j
@Component
public class VoiceEvaluateStreamProducer extends AbstractStreamProducer<Long> {

    private final VoiceInterviewService voiceInterviewService;
    private final TransactionalExecutor transactionalExecutor;

    public VoiceEvaluateStreamProducer(RedisService redisService,
                                       @Lazy VoiceInterviewService voiceInterviewService,
                                       TransactionalExecutor transactionalExecutor) {
        super(redisService);
        this.voiceInterviewService = voiceInterviewService;
        this.transactionalExecutor = transactionalExecutor;
    }

    public void sendEvaluateTask(Long sessionId) {
        sendTask(sessionId);
    }

    @Override
    protected String taskDisplayName() {
        return "语音面试评估";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.VOICE_EVALUATE_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(Long sessionId) {
        return Map.of(
            AsyncTaskStreamConstants.FIELD_VOICE_SESSION_ID, sessionId.toString(),
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
        );
    }

    @Override
    protected String payloadIdentifier(Long sessionId) {
        return "voiceSessionId=" + sessionId;
    }

    @Override
    protected void onSendFailed(Long sessionId, String error) {
        transactionalExecutor.runRequiresNew(() -> voiceInterviewService.updateEvaluateStatus(
            sessionId, AsyncTaskStatus.FAILED, truncateError(error)));
    }
}
