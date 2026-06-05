package com.paicli.llm;

public class GLMClient extends AbstractOpenAiCompatibleClient {
    // 用于代码类模型（默认）的API URL
    private static final String CODING_API_URL = "https://open.bigmodel.cn/api/coding/paas/v4/chat/completions";
    // 用于多模态模型的API URL
    private static final String MULTIMODAL_API_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions";
    private static final String DEFAULT_MODEL = "glm-5.1";
    private final String apiKey;
    private final String model;
    private final String apiUrl;

    public GLMClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    public GLMClient(String apiKey, String model) {
        this(apiKey, model, null);
    }

    /**
     * 构造函数，用于自定义模型和API URL。
     */
    GLMClient(String apiKey, String model, String apiUrl) {
        this.apiKey = apiKey;
        this.model = model != null && !model.isBlank() ? model : DEFAULT_MODEL;
        this.apiUrl = apiUrl != null && !apiUrl.isBlank() ? apiUrl : selectApiUrl(this.model);
    }

    @Override
    protected String getApiUrl() {
        return apiUrl;
    }

    @Override
    protected String getModel() {
        return model;
    }

    @Override
    protected String getApiKey() {
        return apiKey;
    }

    @Override
    public String getModelName() {
        return model;
    }

    /**
     * 返回提供商名称，这里是"glm"。
     */
    @Override
    public String getProviderName() {
        return "glm";
    }

    @Override
    public int maxContextWindow() {
        return 200_000;
    }

    /**
     * 检查模型是否支持提示缓存功能。
     *
     * @return 如果模型支持提示缓存，则返回true；否则返回false。
     */
    @Override
    public boolean supportsPromptCaching() {
        return true;
    }

    /**
     * 返回GLM模型的提示缓存模式。
     *
     * @return 提示缓存模式字符串
     */
    @Override
    public String promptCacheMode() {
        return "glm-prompt-cache";
    }

    /**
     * 将内容部分转换为图片URL。
     *
     * @param part 内容部分对象
     * @return 图片URL字符串
     */
    @Override
    protected String toImageUrl(LlmClient.ContentPart part) {
        if (isGlm5v() && "image_base64".equals(part.type())) {
            return part.imageBase64();
        }
        return super.toImageUrl(part);
    }

    /**
     * 根据模型名称选择合适的API URL。
     *
     * @param model 模型名称字符串
     * @return API URL字符串
     */
    private static String selectApiUrl(String model) {
        String normalized = model == null ? "" : model.trim().toLowerCase();
        if (normalized.startsWith("glm-5v")) {
            return MULTIMODAL_API_URL;
        }
        return CODING_API_URL;
    }

    /**
     * 检查模型是否为GLM-5.1多模态模型。
     *
     * @return 如果模型为GLM-5.1多模态模型，则返回true；否则返回false。
     */
    private boolean isGlm5v() {
        return model != null && model.trim().toLowerCase().startsWith("glm-5v");
    }
}
