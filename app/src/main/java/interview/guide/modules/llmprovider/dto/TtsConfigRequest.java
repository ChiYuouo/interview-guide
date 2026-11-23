package interview.guide.modules.llmprovider.dto;

public record TtsConfigRequest(
    String model,
    String apiKey,
    String voice,
    String format,
    Integer sampleRate,
    String mode,
    String languageType,
    Float speechRate,
    Integer volume,
    String url
) {
  public TtsConfigRequest(String model, String apiKey, String voice, String format,
      Integer sampleRate, String mode, String languageType, Float speechRate, Integer volume) {
    this(model, apiKey, voice, format, sampleRate, mode, languageType, speechRate, volume, null);
  }
}
