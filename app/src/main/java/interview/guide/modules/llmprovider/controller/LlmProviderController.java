package interview.guide.modules.llmprovider.controller;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.auth.model.AccountPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import interview.guide.modules.llmprovider.dto.AsrConfigDTO;
import interview.guide.modules.llmprovider.dto.AsrConfigRequest;
import interview.guide.modules.llmprovider.dto.CreateProviderRequest;
import interview.guide.modules.llmprovider.dto.DefaultProviderDTO;
import interview.guide.modules.llmprovider.dto.ProviderDTO;
import interview.guide.modules.llmprovider.dto.ProviderTestResult;
import interview.guide.modules.llmprovider.dto.TtsConfigDTO;
import interview.guide.modules.llmprovider.dto.TtsConfigRequest;
import interview.guide.modules.llmprovider.dto.UpdateProviderRequest;
import interview.guide.modules.llmprovider.service.LlmProviderConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/llm-provider")
@RequiredArgsConstructor
@Slf4j
public class LlmProviderController {

  private final LlmProviderConfigService configService;

  @GetMapping("/list")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
  public Result<List<ProviderDTO>> listProviders(@AuthenticationPrincipal AccountPrincipal account) {
    return Result.success(configService.listProviders(account.getUserId()));
  }

  @GetMapping("/{id}")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
  public Result<ProviderDTO> getProvider(@AuthenticationPrincipal AccountPrincipal account, @PathVariable String id) {
    return Result.success(configService.getProvider(account.getUserId(), id));
  }

  @PostMapping
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> createProvider(@AuthenticationPrincipal AccountPrincipal account, @RequestBody @Valid CreateProviderRequest request) {
    configService.createProvider(account.getUserId(), request);
    return Result.success();
  }

  @PutMapping("/{id}")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> updateProvider(@AuthenticationPrincipal AccountPrincipal account, @PathVariable String id,
      @RequestBody UpdateProviderRequest request) {
    configService.updateProvider(account.getUserId(), id, request);
    return Result.success();
  }

  @DeleteMapping("/{id}")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> deleteProvider(@AuthenticationPrincipal AccountPrincipal account, @PathVariable String id) {
    configService.deleteProvider(account.getUserId(), id);
    return Result.success();
  }

  @PostMapping("/{id}/test")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
  public Result<ProviderTestResult> testProvider(@AuthenticationPrincipal AccountPrincipal account, @PathVariable String id) {
    return Result.success(configService.testProvider(account.getUserId(), id));
  }

  @PostMapping("/reload")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> reloadProviders() {
    configService.reloadProviders();
    return Result.success();
  }

  @GetMapping("/default-provider")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
  public Result<DefaultProviderDTO> getDefaultProvider(@AuthenticationPrincipal AccountPrincipal account) {
    return Result.success(configService.getDefaultProvider(account.getUserId()));
  }

  @PutMapping("/default-provider")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> updateDefaultProvider(@AuthenticationPrincipal AccountPrincipal account, @RequestBody DefaultProviderDTO request) {
    configService.updateDefaultProvider(account.getUserId(), request);
    return Result.success();
  }

  @PutMapping("/default-embedding-provider")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> updateDefaultEmbeddingProvider(@AuthenticationPrincipal AccountPrincipal account, @RequestBody DefaultProviderDTO request) {
    configService.updateDefaultEmbeddingProvider(account.getUserId(), request);
    return Result.success();
  }

  // ===== Voice ASR/TTS Config =====

  @GetMapping("/voice/asr")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
  public Result<AsrConfigDTO> getAsrConfig(@AuthenticationPrincipal AccountPrincipal account) {
    return Result.success(configService.getAsrConfig(account.getUserId()));
  }

  @PutMapping("/voice/asr")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> updateAsrConfig(@AuthenticationPrincipal AccountPrincipal account, @RequestBody AsrConfigRequest request) {
    configService.updateAsrConfig(account.getUserId(), request);
    return Result.success();
  }

  @GetMapping("/voice/tts")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
  public Result<TtsConfigDTO> getTtsConfig(@AuthenticationPrincipal AccountPrincipal account) {
    return Result.success(configService.getTtsConfig(account.getUserId()));
  }

  @PutMapping("/voice/tts")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  public Result<Void> updateTtsConfig(@AuthenticationPrincipal AccountPrincipal account, @RequestBody TtsConfigRequest request) {
    configService.updateTtsConfig(account.getUserId(), request);
    return Result.success();
  }

  @PostMapping("/voice/asr/test")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
  public Result<ProviderTestResult> testAsrConfig(@AuthenticationPrincipal AccountPrincipal account) {
    return Result.success(configService.testAsrConfig(account.getUserId()));
  }
}
