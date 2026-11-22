package interview.guide.modules.voiceinterview.config;

import interview.guide.modules.auth.model.AccountPrincipal;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Component
@RequiredArgsConstructor
public class VoiceInterviewHandshakeInterceptor implements HandshakeInterceptor {
  public static final String USER_ID_ATTRIBUTE = "voiceInterview.userId";
  public static final String SESSION_ID_ATTRIBUTE = "voiceInterview.sessionId";

  private final VoiceInterviewService voiceInterviewService;

  @Override
  public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
      WebSocketHandler handler, Map<String, Object> attributes) {
    if (!(request.getPrincipal() instanceof Authentication authentication)
        || !authentication.isAuthenticated()
        || !(authentication.getPrincipal() instanceof AccountPrincipal account)) {
      response.setStatusCode(HttpStatus.UNAUTHORIZED);
      return false;
    }

    String path = request.getURI().getPath();
    String sessionKey = path.substring(path.lastIndexOf('/') + 1);
    try {
      Long sessionId = Long.valueOf(sessionKey);
      if (sessionId > 0 && sessionKey.equals(sessionId.toString())
          && voiceInterviewService.ownsSession(account.getUserId(), sessionId)) {
        attributes.put(USER_ID_ATTRIBUTE, account.getUserId());
        attributes.put(SESSION_ID_ATTRIBUTE, sessionId);
        return true;
      }
    } catch (NumberFormatException e) {
      response.setStatusCode(HttpStatus.NOT_FOUND);
      return false;
    }
    response.setStatusCode(HttpStatus.NOT_FOUND);
    return false;
  }

  @Override
  public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
      WebSocketHandler handler, Exception exception) {
    // 无需在握手完成后处理。
  }
}
